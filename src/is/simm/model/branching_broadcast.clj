(ns is.simm.model.branching-broadcast
  "Server-side broadcast of KB branch lifecycle events to subscribed clients.

   The server-side branch operations in `is.simm.runtimes.branching`
   (`branch-kb!`, `merge-kb!`, `discard-kb-branch!`) call `emit-event!`
   on completion. This namespace publishes those events plus per-branch
   tx-occurred events (via datahike's native `d/listen`) onto a single
   kabel pubsub topic `:branching/event`. Clients subscribe and update
   their per-KB branch caches and editor projections reactively.

   The pubsub channel is the *event* channel — payloads are small
   metadata maps. Branch *data* (datoms) flows through the existing
   per-KB kabel store sync — branches in datahike are pointers inside
   the same store, so once a branch is created, the client's local
   konserve replica already has the data it needs to project via
   `(d/branch-as-db local-conn branch-kw)`."
  (:require [clojure.string]
            [datahike.api :as d]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [taoensso.telemere :as log]))

(def topic
  "Single topic carrying all branch lifecycle events. Payload shape:
     {:type      :branch/created | :branch/discarded | :branch/merged
                 | :branch/tx-occurred
      :db-scope  <kb-uuid>
      :branch    <branch-keyword>
      :parent    <parent-branch-keyword>  ; on :created / :merged
      :commit    <commit-id>              ; on :tx-occurred / :merged
      :at        <inst>}"
  :branching/event)

(def ^:private listener-key-prefix ::branching-tx-listener)

;; --- server-peer plumbing ---------------------------------------------------

(defonce ^:private peer-ref
  ;; Set by ensure-topic-registered!; reused by emit-event! callers that
  ;; only have the db-scope in hand. Single peer per JVM in simmis today.
  (atom nil))

(defn ensure-topic-registered!
  "Register the branching topic on `server-peer` if not already. Caches
   the peer for later `emit-event!` calls. Idempotent."
  [server-peer]
  (reset! peer-ref server-peer)
  (when-not (pubsub/topic-registered? server-peer topic)
    (pubsub/register-topic! server-peer topic
                            {:strategy (proto/pub-sub-only-strategy nil)})
    (log/log! {:level :info :id ::topic-registered
               :msg "Branching broadcast topic registered"
               :data {:topic topic}})))

(defn emit-event!
  "Publish a branch event onto the topic. `event` should include at
   least `:type` and `:db-scope`. Adds `:at` automatically. No-op when
   the server peer isn't registered yet (e.g., during boot ordering)."
  [event]
  (if-let [peer @peer-ref]
    (let [payload (-> event
                      (assoc :at (java.util.Date.))
                      ;; ensure stringified scope so transit round-trips cleanly
                      (update :db-scope #(if (uuid? %) (str %) %)))]
      (pubsub/publish! peer topic payload)
      (log/log! {:level :debug :id ::event-published
                 :msg "Branching event published"
                 :data payload}))
    (log/log! {:level :debug :id ::no-peer-skip
               :msg "No server peer registered; skipping branching event"
               :data event})))

;; --- per-conn datahike tx hooks for :tx-occurred events --------------------

(defn install-kb-tx-listener!
  "Install a datahike `d/listen` on a KB connection that publishes a
   `:branch/tx-occurred` event whenever the underlying store commits.
   The event carries the branch the commit landed on (read from the
   tx's db-after config) and the commit-id. Idempotent."
  [conn db-scope]
  (let [k (keyword (str (name listener-key-prefix) "-" db-scope))]
    (d/listen conn k
              (fn [{:keys [db-after]}]
                (when db-after
                  (let [branch (get-in db-after [:config :branch])
                        commit (get-in db-after [:meta :datahike/commit-id])]
                    (emit-event! {:type :branch/tx-occurred
                                  :db-scope db-scope
                                  :branch branch
                                  :commit (when commit (str commit))})))))
    (log/log! {:level :info :id ::tx-listener-installed
               :msg "KB tx-listener installed for branching broadcast"
               :data {:db-scope (str db-scope)}})))

(defn uninstall-kb-tx-listener!
  [conn db-scope]
  (d/unlisten conn (keyword (str (name listener-key-prefix) "-" db-scope))))

(def ^:private book-listener-key-prefix ::book-tx-listener)

(defn- touches-ns?
  "Did this transaction write an attribute in `ns-prefix`?

   The filter is the whole point. A room store holds messages as well as its
   book, so an unfiltered listener would publish a second pubsub message for
   EVERY chat message — doubling the traffic of the busiest write path in the
   app to notify a view that changes a few times a day. Messages already have
   their own notify topic; the book does not. The system DB is the same story
   at larger scale: every actor, ledger and budget write passes through it."
  [tx-data ns-prefix]
  (boolean
   (some (fn [d]
           (when-let [a (:a d)]
             (and (keyword? a)
                  (some-> (namespace a) (clojure.string/starts-with? ns-prefix)))))
         tx-data)))

(defn install-book-tx-listener!
  "Publish `:book/tx-occurred` when a room store's BOOK changes.

   Room stores had no listener at all, so the Accounting perspective was the
   one view that could not know it was stale — which is why it kept a Refresh
   button after the others lost theirs. Idempotent."
  [conn db-scope]
  (let [k (keyword (str (name book-listener-key-prefix) "-" db-scope))]
    (d/listen conn k
              (fn [{:keys [db-after tx-data]}]
                (when (and db-after (touches-ns? tx-data "kontor"))
                  (emit-event! {:type :book/tx-occurred
                                :db-scope db-scope
                                :branch (get-in db-after [:config :branch])
                                :commit (some-> (get-in db-after [:meta :datahike/commit-id]) str)}))))
    (log/log! {:level :info :id ::book-tx-listener-installed
               :msg "Room-store book listener installed"
               :data {:db-scope (str db-scope)}})))

(def ^:private proposal-listener-key ::proposal-tx-listener)

(defn install-proposal-tx-listener!
  "Publish `:proposal/tx-occurred` when a proposal row moves in the SYSTEM DB.

   Filing a proposal was the one aggregate-moving write with no event behind
   it. The fork BRANCHES emit `:branch/created` when the agent opens its
   overlay — minutes before `ops.proposals/file-proposal!` writes the row — so
   the refresh those events triggered ran against a workspace where the
   proposal did not exist yet, and nothing invalidated the cache afterwards. A
   Task row opening that proposal then found no match in a list fetched before
   it was filed, and the inbox said it had been accepted or dismissed: the
   opposite of the truth, asserted confidently.

   Resolution (accept / dismiss, whole-proposal or per-fork) writes
   `:proposal/status` through the same conn, so it rides the same listener.

   The payload carries NO proposal data — clients re-fetch through
   `ops.proposals/visible-proposals`, which is where the visibility decision
   belongs. It also carries no `:db-scope`: a proposal spans scopes, and the
   consumers keyed by scope (the KB branch cache, the history subway) have
   nothing to do here. Idempotent."
  [conn]
  (when conn
    (d/listen conn proposal-listener-key
              (fn [{:keys [tx-data]}]
                (when (touches-ns? tx-data "proposal")
                  (emit-event! {:type :proposal/tx-occurred}))))
    (log/log! {:level :info :id ::proposal-tx-listener-installed
               :msg "System-DB proposal listener installed"})))

(defn uninstall-proposal-tx-listener!
  [conn]
  (when conn (d/unlisten conn proposal-listener-key)))
