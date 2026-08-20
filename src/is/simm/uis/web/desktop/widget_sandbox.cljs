(ns is.simm.uis.web.desktop.widget-sandbox
  "Client-side SCI sandbox for evaluating widget code from blocks.

   A 'widget' is a Clojure expression stored as :block/widget-code. The
   renderer evaluates it inside this curated SCI context and uses the
   resulting vnode in place of the block's text content.

   Vocabulary exposed to the widget:
     el/div, el/span, el/p, el/h1..h6, el/ul, el/ol, el/li, el/table,
     el/thead, el/tbody, el/tr, el/th, el/td, el/button, el/input,
     el/strong, el/em, el/code, el/pre, el/img, el/a
       — produce vnodes (without delta tracking; suitable for static views).

     dh/q, dh/pull, dh/entity
       — read-only datahike against the page's effective DB (passed in).

   What is NOT exposed:
     - No JS interop (no js/* ns, no js classes, no .-foo property access).
     - No DOM access (no foreign-node, no real DOM nodes).
     - No filesystem / network (SCI default).
     - No eval/load-file/load-string.

   Future work:
     - kb/* writers (transact via authorized remote calls).
     - track/await for reactivity (need spindel SCI macro context port).
     - Per-eval timeout (interrupt-fn).

   Threat model: widget code authored by Vár or by another user; evaluated
   in the viewer's session. Must not exfiltrate, escape into JS, or freeze
   the tab beyond a budget."
  (:require [sci.core :as sci]
            [sci.ctx-store :as sci-ctx]
            [datahike.api :as d]
            [org.replikativ.spindel.dom.elements :as el]
            [org.replikativ.spindel.dom.core :as core]))

;; ---------------------------------------------------------------------------
;; Element function wrappers
;; ---------------------------------------------------------------------------
;;
;; The el/* macros require a spindel execution context for delta tracking.
;; For widget code we use simple-element instead — produces a plain vnode
;; without caching/delta machinery. This is fine for the V0 pure-render path:
;; the WIDGET BLOCK ITSELF is wrapped in spindel context by the block
;; renderer, so reactivity flows in via the parent (re-evaluate widget when
;; underlying db changes).

(def ^:private event-attr-keys
  "Attribute keys whose values are functions that escape into the host.
   We wrap them so each invocation runs under the widget's deadline budget."
  #{:on-click :on-change :on-input :on-submit :on-keydown :on-keyup :on-keypress
    :on-focus :on-blur :on-mouseenter :on-mouseleave :on-mousedown :on-mouseup
    :on-dblclick :on-contextmenu})

(defn- wrap-event-handlers
  "Wrap any event-handler fn in attrs so each invocation:
     1. runs under the widget's deadline budget (`with-deadline`),
     2. binds `sci.ctx-store/*ctx*` to the ctx held in `ctx-atom` (the
        handler is an SCI-defined closure; calling it from outside SCI
        without the ctx fails with 'No context found in sci.ctx-store/*ctx*').
   `ctx-atom` is filled in after SCI ctx construction (see `make-sandbox-ctx`)."
  [attrs with-deadline ctx-atom]
  (if (map? attrs)
    (reduce-kv (fn [m k v]
                 (if (and (contains? event-attr-keys k) (fn? v))
                   (assoc m k (fn [& args]
                                (with-deadline
                                  #(try
                                     (sci-ctx/with-ctx @ctx-atom
                                       (apply v args))
                                     (catch :default e
                                       (js/console.error
                                         (str "widget event handler error: "
                                              (or (.-message e) (str e))))
                                       nil)))))
                   (assoc m k v)))
               {} attrs)
    attrs))

(defn- mk-el [tag with-deadline ctx-atom]
  (fn [& args]
    (let [[attrs children] (el/parse-element-args args)
          attrs' (wrap-event-handlers attrs with-deadline ctx-atom)]
      (el/simple-element tag attrs' children))))

(defn- element-bindings-for
  "Build the el/* namespace map for an SCI ctx, with event-handler wrapping."
  [with-deadline ctx-atom]
  (into {}
        (map (fn [tag]
               [(symbol (name tag)) (mk-el tag with-deadline ctx-atom)])
             [:div :span :p :h1 :h2 :h3 :h4 :h5 :h6
              :ul :ol :li
              :table :thead :tbody :tr :th :td
              :button :input :textarea :select :option
              :strong :em :code :pre :small
              :img :a :br :hr :section :article :header :footer :nav :aside
              :label :form :details :summary])))

(defn- text [s]
  (core/make-text-vnode (str s)))

;; ---------------------------------------------------------------------------
;; SCI context setup
;; ---------------------------------------------------------------------------

(def ^:private default-timeout-ms
  "Wall-clock budget for a single widget evaluation. Beyond this, an interrupt
   fires inside SCI and aborts the eval. Keeps a runaway widget from freezing
   the tab. 250ms is generous for read-mostly UI rendering."
  250)

(defn- make-deadline-state
  "Returns a {:interrupt-fn :with-deadline}. The interrupt-fn is given to
   SCI; it fires only when a deadline is active (SCI's interrupt-fn runs at
   every user-fn entry, including invocations of closures that escape into
   the host as event handlers — without the active flag we'd time out a
   button click hours later).

   `with-deadline` wraps a thunk: arms the deadline, runs, disarms in a
   finally. For initial eval (and event handlers) we want a budget; outside
   those windows the interrupt is a no-op."
  [timeout-ms]
  (let [active-until (atom 0)]
    {:interrupt-fn (fn []
                     (let [until @active-until]
                       (when (and (pos? until) (> (.getTime (js/Date.)) until))
                         (throw (ex-info (str "widget timeout (" timeout-ms "ms)") {})))))
     :with-deadline (fn [thunk]
                      (reset! active-until (+ (.getTime (js/Date.)) timeout-ms))
                      (try (thunk)
                           (finally (reset! active-until 0))))}))

(defn make-sandbox-ctx
  "Create a fresh SCI context for evaluating a widget.

   Args:
     db        — current Datahike DB value (for read queries via `dh/*`).
     conn      — current Datahike connection (for writes via `kb/*`); may be
                 nil when no live conn is available, in which case writes
                 throw a friendly error.
     timeout-ms — wall-clock budget for the eval.

   Each render gets a fresh ctx so atom state from prior renders cannot
   leak. Event handlers defined inside widget code (e.g. :on-click) close
   over the captured `conn` and can issue writes; those writes flow through
   the kabel writer attached to `conn`, so they are authorized as the
   current viewer (not the agent that wrote the code).

   Returns {:ctx <sci-ctx> :with-deadline (fn [thunk] …)}.  Callers run
   `eval-string*` inside `(with-deadline …)` for the initial eval; event
   handlers in the produced vnode tree run under the same budget on each
   firing (see wrap-event-handlers)."
  [db conn timeout-ms]
  (let [{:keys [interrupt-fn with-deadline]} (make-deadline-state timeout-ms)
        ;; Atom holding this widget's SCI ctx — used by event handlers to
        ;; restore `sci.ctx-store/*ctx*` when fired outside the SCI eval.
        ;; Filled in immediately after `sci/init` returns.
        ctx-atom (atom nil)
        require-conn (fn [op]
                       (when-not conn
                         (throw (ex-info (str op " requires a live KB conn (no db-scope on the page)")
                                         {:op op}))))
        next-block-order (fn [page-uuid]
                           (let [max-order (or (d/q '[:find (max ?o) .
                                                      :in $ ?puuid
                                                      :where
                                                      [?p :entity/uuid ?puuid]
                                                      [?b :block/parent ?p]
                                                      [?b :block/order ?o]]
                                                    db page-uuid)
                                               "a")]
                             (str max-order "z")))
        ctx (sci/init
              {:classes {:allow :all-cljs}
               :deny '[eval load-file load-string js* set!]
               :interrupt-fn interrupt-fn
               :namespaces
               {'el (assoc (element-bindings-for with-deadline ctx-atom) 'text text)

                'dh {'q (fn [query & args] (apply d/q query db args))
                     'pull (fn
                             ([selector eid] (d/pull db selector eid))
                             ([selector eid db'] (d/pull (or db' db) selector eid)))
                     'entity (fn
                               ([eid] (d/entity db eid))
                               ([db' eid] (d/entity (or db' db) eid)))
                     'datoms (fn [& args] (apply d/datoms db args))}

                ;; Writes — go through the kabel writer attached to `conn`,
                ;; so the tx is authorized server-side as the current
                ;; viewer's session. CLJS datahike requires async transact!.
                'kb {'transact! (fn [tx-data]
                                  (require-conn 'kb/transact!)
                                  (d/transact! conn tx-data))

                     ;; Install schema for an attribute. Required before first
                     ;; use of any attr not already in the KB schema, because
                     ;; KB DBs are :schema-flexibility :write. Idempotent: a
                     ;; second call for an existing ident is a no-op.
                     ;; Examples:
                     ;;   (kb/install-attr! :todo/text :db.type/string :db.cardinality/one)
                     ;;   (kb/install-attr! :todo/done? :db.type/boolean :db.cardinality/one)
                     ;;   (kb/install-attr! :tag/labels :db.type/string :db.cardinality/many)
                     'install-attr! (fn [ident value-type cardinality]
                                      (require-conn 'kb/install-attr!)
                                      (let [latest @conn
                                            already? (d/q '[:find ?e . :in $ ?id :where [?e :db/ident ?id]]
                                                          latest ident)]
                                        (when-not already?
                                          (d/transact! conn [{:db/ident ident
                                                              :db/valueType value-type
                                                              :db/cardinality cardinality}]))
                                        ident))

                     'upsert-block! (fn [page-uuid html-content]
                                      (require-conn 'kb/upsert-block!)
                                      (let [block-uuid (random-uuid)
                                            now (js/Date.)]
                                        (d/transact! conn
                                                     [{:entity/uuid block-uuid
                                                       :entity/created-at now
                                                       :entity/updated-at now
                                                       :instance/of-role [:entity/name "S/Block"]
                                                       :block/parent [:entity/uuid page-uuid]
                                                       :block/order (next-block-order page-uuid)
                                                       :block/content html-content}])
                                        block-uuid))

                     ;; Accept either a plain uuid or a lookup ref
                     ;; [:entity/uuid <uuid>] (or any other unique-ident
                     ;; lookup), since both shapes are common in Datalog code.
                     'set-attr! (fn [entity-ref attr value]
                                  (require-conn 'kb/set-attr!)
                                  (let [eid (if (uuid? entity-ref)
                                              [:entity/uuid entity-ref]
                                              entity-ref)]
                                    (d/transact! conn [(into {:db/id eid} {attr value})])))

                     ;; Read-modify-write against the live conn — safe for
                     ;; rapid clicks (e.g. counters). Reads from the conn
                     ;; (latest state) rather than the captured-at-render db.
                     'update-attr! (fn [entity-ref attr f]
                                     (require-conn 'kb/update-attr!)
                                     (let [eid (if (uuid? entity-ref)
                                                 [:entity/uuid entity-ref]
                                                 entity-ref)
                                           latest @conn
                                           old-val (get (d/pull latest [attr] eid) attr)]
                                       (d/transact! conn [(into {:db/id eid} {attr (f old-val)})])))

                     'retract! (fn [entity-ref]
                                 (require-conn 'kb/retract!)
                                 (let [eid (if (uuid? entity-ref)
                                             [:entity/uuid entity-ref]
                                             entity-ref)]
                                   (d/transact! conn [[:db/retractEntity eid]])))}

                ;; The current db (and conn, if present) are exposed
                ;; directly for advanced use.
                'user {'db db 'conn conn}}})]
    (reset! ctx-atom ctx)
    {:ctx ctx :with-deadline with-deadline}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn eval-widget
  "Evaluate widget code against `db` (read) and `conn` (writes).
   Returns:
     {:ok    <vnode>}     on success
     {:error <error-msg>} on parse or evaluation failure (including timeout).

   Never throws — intended to be called inside a render path. Optional
   `timeout-ms` overrides the default budget."
  ([code db] (eval-widget code db nil default-timeout-ms))
  ([code db conn] (eval-widget code db conn default-timeout-ms))
  ([code db conn timeout-ms]
   (try
     (let [{:keys [ctx with-deadline]} (make-sandbox-ctx db conn timeout-ms)
           result (with-deadline #(sci/eval-string* ctx (str code)))]
       {:ok result})
     (catch :default e
       {:error (or (.-message e) (str e))}))))
