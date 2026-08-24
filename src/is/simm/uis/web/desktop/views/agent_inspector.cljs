(ns is.simm.uis.web.desktop.views.agent-inspector
  "Agent Inspector — Phase 1: config, KB connections, chat link."
  (:require [org.replikativ.spindel.dom.elements :as el]
            [is.simm.uis.web.desktop.views.core :as vc]
            [is.simm.uis.web.desktop.views.model-picker :as model-picker]
            [is.simm.uis.web.desktop.signals :as sig]
            [is.simm.runtimes.web :as web]
            [is.simm.uis.web.desktop.chat-remote :as chat-remote]
            [org.replikativ.spindel.engine.core :as rtc]
            [is.simm.uis.web.desktop.runtime :refer [runtime]]
            [datahike.api :as d]
            [clojure.string :as str])
  (:require-macros [org.replikativ.spindel.dom.elements :as el]
                   [org.replikativ.spindel.dom.foreach :refer [ifor-each]]))

(defn render-agent-inspector
  "Render the agent inspector panel.

   data map keys:
   - :agent-id   string UUID
   - :room-id    string UUID of the room this agent belongs to
   - :agent-name string
   - :model-info map from room-agents/describe-model

   admin-data: result of load-room-details!
   room-states: current room-states signal value (map of scope-str → {:db db})"
  [data admin-data room-states]
  (let [{:keys [agent-id room-id agent-name model-info]} data
        model (or (:model model-info) (:candidate model-info))
        label (cond
                (str/includes? (or model "") "claude") "Claude"
                (str/includes? (or model "") "gpt")    "GPT"
                (str/includes? (or model "") "glm")    "GLM"
                :else (or model "?"))]
    ;; Trigger load if not yet available
    ;; Guard on THIS room's details. `sig/admin-data` is shared by six panels;
    ;; a plain nil-guard leaves the inspector rendering another room's data.
    (when (or (nil? admin-data)
              (not= (some-> admin-data :room :room/id str) (str room-id)))
      (when room-id
        (let [s (chat-remote/load-room-details! web/server-id room-id)]
          (s (fn [result]
               (binding [rtc/*execution-context* runtime]
                 (reset! sig/admin-data result)))
             (fn [err] (js/console.error "[agent-inspector] load error:" err))))))

    (let [;; db-scope from admin-data (populated by load-room-details!)
          room-db-scope (when admin-data (str (get-in admin-data [:room :room/content-db-scope])))
          room-db (when (and room-states room-db-scope)
                    (get-in room-states [room-db-scope :db]))
          ;; Stats from room DB ledger (reactive — updated via konserve-sync)
          msg-count (when room-db
                      (or (d/q '[:find (count ?e) . :where [?e :S.Message/sent-at _]] room-db) 0))
          total-cost-microdollars (when room-db
                                    (or (d/q '[:find (sum ?c) . :where [?l :ledger/cost-microdollars ?c]] room-db) 0))
          input-tokens (when room-db
                         (or (d/q '[:find (sum ?a) . :where [?l :ledger/resource :input-tokens] [?l :ledger/amount ?a]] room-db) 0))
          output-tokens (when room-db
                          (or (d/q '[:find (sum ?a) . :where [?l :ledger/resource :output-tokens] [?l :ledger/amount ?a]] room-db) 0))
          budget-dollars (or (:room/budget-dollars (:room admin-data)) 10.0)
          budget-microdollars (long (* budget-dollars 1000000))
          budget-pct (if (pos? budget-microdollars)
                       (min 1.0 (/ (double total-cost-microdollars) budget-microdollars))
                       0.0)
          cost-dollars (/ (double total-cost-microdollars) 1000000.0)]

    (el/div {:class "agent-inspector"}
      ;; Header
      (el/div {:class "agent-inspector-header"}
        (el/div {:class "agent-inspector-avatar"}
          (vc/icon "bot"))
        (el/div {:class "agent-inspector-title"}
          (el/h2 {:class "agent-inspector-name"} (or agent-name "Agent"))
          (el/span {:class "agent-model-badge"} label))
        (when room-id
          (el/button {:class "agent-inspector-chat-btn"
                      :title "Open chat room"
                      :on-click (fn [_]
                                  (let [room-name (or (get-in admin-data [:room :room/name]) "Chat")]
                                    (sig/open-tab! :chat
                                                   {:room-id room-id :room-name room-name}
                                                   {:title room-name :new-tab? true})))}
            (vc/icon "message-square")
            (el/span {} "Open Chat"))))

      ;; Body
      (if (nil? admin-data)
        (el/div {:class "agent-inspector-loading"} "Loading…")

        (let [agent-config (->> (:agents admin-data)
                                (filter #(= (str (:party/id %)) agent-id))
                                first)
              ;; Freshest first: the room details reload after every save, while
              ;; the tab's own copy is whatever the nav had when it was opened.
              chosen (or (:model-info agent-config) model-info)
              room-kbs (:knowledge-bases admin-data)]

          (el/div {:class "agent-inspector-body"}

            ;; Configuration section. Everything here comes from :model-info,
            ;; which the server computes with the SAME function the turn uses
            ;; (room-agents/describe-model). Reading :party/model directly is
            ;; what used to print "—" for an agent following a family, next to
            ;; a provider it never chose.
            (let [{:keys [model candidate model-short choice-label provider-label no-reasoning?
                          reasoning-copy reasoning-explanation selection-label available?
                          availability-label availability-explanation]} chosen
                  display-model (or model candidate)]
              (el/div {:class "agent-inspector-section"}
                (el/h3 {:class "agent-inspector-section-title"} "Configuration")
                (el/div {:class "agent-inspector-config"}
                  ;; The PICKER's own label, printed verbatim. Composing a
                  ;; second name here is what produced "family latest" beside a
                  ;; list that says "(latest)".
                  (el/div {:class "agent-inspector-row"}
                    (el/span {:class "agent-inspector-label"} "Selection")
                    (el/span {:class "agent-inspector-value"}
                      selection-label))
                  (el/div {:class "agent-inspector-row"}
                    (el/span {:class "agent-inspector-label"} "Model")
                    (el/span {:class "agent-inspector-value"}
                      (or choice-label display-model "—")))
                  ;; The short id, so every provider reads the same way here.
                  ;; The full id stays one hover away for the path-addressed
                  ;; ones.
                  (el/div {:class "agent-inspector-row"}
                    (el/span {:class "agent-inspector-label"} "Resolves to")
                    (el/span {:class (vc/class-names "agent-inspector-value"
                                                     "agent-inspector-value--mono"
                                                     (when (not= display-model model-short) "has-tooltip"))
                              :data-tooltip (or display-model "")}
                      (or model-short display-model "—")))
                  (el/div {:class "agent-inspector-row"}
                    (el/span {:class "agent-inspector-label"} "Provider")
                    (el/span {:class "agent-inspector-value"}
                      (or provider-label "unknown")))
                  (when-not available?
                    (el/div {:class "agent-inspector-row"}
                      (el/span {:class "agent-inspector-label"} "Availability")
                      (el/span {:class "agent-inspector-value agent-inspector-value--unavailable"
                                :data-tooltip availability-explanation}
                        availability-label)))
                  ;; Reasoning gets its own row rather than a suffix on the
                  ;; provider, where it read as a claim about OpenAI. Same words
                  ;; as the picker's tag, from the same server field, and the
                  ;; tooltip names the tools it is talking about.
                  (el/div {:class "agent-inspector-row"}
                    (el/span {:class "agent-inspector-label"} "Reasoning")
                    ;; A LITERAL attribute map. el/span reads the first form as
                    ;; attributes only when it is written as a map; a cond->
                    ;; that builds one at runtime renders as text.
                    (el/span {:class (vc/class-names "agent-inspector-value"
                                                     (when no-reasoning? "has-tooltip"))
                              :data-tooltip (or reasoning-explanation "")}
                      (or reasoning-copy "on")))
                  (el/div {:class "agent-inspector-row"}
                    (el/span {:class "agent-inspector-label"} "Auto-respond")
                    (el/span {:class "agent-inspector-value"}
                      (if (:party/auto-respond? agent-config) "Yes" "No"))))))

            ;; Model picker. Same list Settings shows, same meaning: a "latest"
            ;; row stores the family so releases arrive on their own.
            (el/div {:class "agent-inspector-section"}
              (el/h3 {:class "agent-inspector-section-title"} "Model")
              (el/div {:class "settings-model-list"}
                ;; :value is the key, `:selected?` rides in the item. See the
                ;; same list in settings.cljc for why the tick must not be part
                ;; of the key.
                (let [override? (:configured? chosen)
                      rows (into [(assoc (:inheritance-choice chosen)
                                         :selected? (not override?))]
                                 (map (fn [c]
                                        (assoc c :selected?
                                               (and override?
                                                    (if (and (:family chosen)
                                                             (:auto? chosen))
                                                      (= (:value c) (:family chosen))
                                                      (= (:value c)
                                                         (or (:model chosen)
                                                             (:candidate chosen))))))))
                                 (:model-choices admin-data))]
                  (ifor-each :value rows
                    (fn [row]
                      (model-picker/render-option
                       row
                       (fn [value]
                         (let [s (chat-remote/update-agent-config!
                                  web/server-id agent-id agent-name value nil)]
                           (s (fn [_] (reset! sig/admin-data nil))
                              (fn [err]
                                (js/console.error
                                 "[agent-inspector] model error:" err)))))))))))

            ;; System prompt section — always editable
            (let [prompt (or (:party/system-prompt agent-config) "")
                  ta-id  (str "agent-inspector-prompt-" agent-id)]
              (el/div {:class "agent-inspector-section"}
              (el/h3 {:class "agent-inspector-section-title"} "System Prompt")
              (el/textarea
                {:id ta-id
                 ;; The key carries the agent, so switching contacts builds a
                 ;; DIFFERENT node rather than reusing this one.
                 :key ta-id
                 :class "settings-textarea"
                 :rows 10
                 :placeholder "Describe the agent's personality and role..."
                 ;; The old guard filled the textarea only when it was EMPTY,
                 ;; which is true once and never again. The node survives a
                 ;; switch from one agent to the next, so Lun's inspector showed
                 ;; Vár's prompt under Lun's id, and Save would have written it
                 ;; onto Lun. Refill when the agent behind the node changes, and
                 ;; only then, so an edit in progress survives a re-render.
                 ;; getAttribute/setAttribute, not .dataset: the compiler
                 ;; cannot infer a type for the dataset property and warns.
                 :ref (fn [node]
                        (when (and node (not= (.getAttribute node "data-agent-id") agent-id))
                          (.setAttribute node "data-agent-id" agent-id)
                          (set! (.-value node) prompt)))})
              (el/button
                {:class "settings-btn settings-btn--primary"
                 :style {:margin-top "0.5rem"}
                 :on-click (fn [_]
                             (let [ta (.getElementById js/document (str "agent-inspector-prompt-" agent-id))
                                   sp (.-value ta)
                                   ;; "" for the model choice: this button saves
                                   ;; the prompt, and must not re-pin a model.
                                   s  (chat-remote/update-agent-config!
                                        web/server-id agent-id agent-name "" sp)]
                               (s (fn [_]
                                    (reset! sig/admin-data nil))
                                  (fn [err] (js/console.error "[agent-inspector] update error:" err)))))}
                "Save")))

            ;; Knowledge bases section
            (el/div {:class "agent-inspector-section"}
              (el/h3 {:class "agent-inspector-section-title"} "Wikis")
              (if (seq room-kbs)
                (el/div {:class "agent-inspector-kb-list"}
                  (ifor-each #(str (:kb/id %)) room-kbs
                    (fn [kb]
                      (el/div {:key (str (:kb/id kb))
                               :class "agent-inspector-kb-item"}
                        (vc/icon "database")
                        (el/span {} (or (:kb/name kb) "KB"))))))
                (el/div {:class "agent-inspector-empty"} "No wikis attached")))

            ;; Context stats section (reactive from room DB ledger)
            (el/div {:class "agent-inspector-section"}
              (el/h3 {:class "agent-inspector-section-title"} "Context")
              (el/div {:class "agent-inspector-config"}
                (el/div {:class "agent-inspector-row"}
                  (el/span {:class "agent-inspector-label"} "Messages")
                  (el/span {:class "agent-inspector-value"} (str (or msg-count 0))))
                (el/div {:class "agent-inspector-row"}
                  (el/span {:class "agent-inspector-label"} "Input tokens")
                  (el/span {:class "agent-inspector-value"} (str (or input-tokens 0))))
                (el/div {:class "agent-inspector-row"}
                  (el/span {:class "agent-inspector-label"} "Output tokens")
                  (el/span {:class "agent-inspector-value"} (str (or output-tokens 0))))
                (el/div {:class "agent-inspector-row"}
                  (el/span {:class "agent-inspector-label"} "Cost")
                  (el/span {:class "agent-inspector-value"}
                    (str "$" (.toFixed cost-dollars 6))))
                (el/div {:class "agent-inspector-row"}
                  (el/span {:class "agent-inspector-label"} "Budget")
                  (el/span {:class "agent-inspector-value"}
                    (str "$" (.toFixed budget-dollars 2)))))
              ;; Budget progress bar
              (el/div {:class "agent-inspector-budget-bar"}
                (el/div {:class (str "agent-inspector-budget-fill"
                                     (when (>= budget-pct 0.9) " agent-inspector-budget-fill--critical")
                                     (when (and (>= budget-pct 0.7) (< budget-pct 0.9)) " agent-inspector-budget-fill--warn"))
                         :style {:width (str (* budget-pct 100) "%")}}))
              (el/div {:class "agent-inspector-budget-label"}
                (str (.toFixed (* budget-pct 100) 1) "% used · $"
                     (.toFixed (- budget-dollars cost-dollars) 4) " remaining"))))))))))
