(ns is.simm.model.billing
  "Per-party LLM accounting on top of dvergr's ledger.

   Replaces the old :llm-log/* + :party-budget/* schema: every party gets
   ONE deterministic billing chat (`party-billing-chat-id`), and usage is
   recorded as dvergr :ledger/* entries against that chat via
   `dvergr.chat.accounting/record-usage!` (which computes microdollar cost
   from the model registry itself). Budgets attach to the same billing
   chat using dvergr's :budget/* schema; 'used' is derived from the
   ledger, so there is no separate counter to keep in sync.

   Old :llm-log/* rows in existing stores are left in place (dead data)."
  (:require [is.simm.model.system-db :as system-db]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.chat.accounting :as acct]
            [datahike.api :as d]
            [taoensso.telemere :as log]))

;; =============================================================================
;; Billing chat (one per party, deterministic id)
;; =============================================================================

(defn party-billing-chat-id
  "Deterministic chat UUID for a party's billing ledger."
  [party-uuid]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "simmis-party-billing|" party-uuid))))

(defn ensure-billing-chat!
  "Ensure the party's billing chat entity exists. Returns the chat UUID."
  ([party-uuid] (ensure-billing-chat! (system-db/get-conn) party-uuid))
  ([conn party-uuid]
   (let [chat-id (party-billing-chat-id party-uuid)]
     (when-not (d/q '[:find ?e . :in $ ?cid :where [?e :chat/id ?cid]]
                    @conn chat-id)
       (d/transact conn [(chat-schema/create-chat-entity
                          {:id chat-id
                           :title (str "billing " party-uuid)})]))
     chat-id)))

;; =============================================================================
;; Recording Usage
;; =============================================================================

(defn record-usage!
  "Record an LLM usage event onto the party's billing-chat ledger.
   party-id is the billing owner. Cost is computed by dvergr from the
   model registry — no caller-supplied cost needed.

   usage: {:model str :provider str-or-kw :input-tokens n :output-tokens n}

   CURRENTLY UNCALLED. Its only caller was a `:run-turn-fn` wrapper in
   `is.simm.agents.room-agents` that read a `:message/usage` field which does
   not exist in dvergr, so this ledger was never written and the admin
   dashboard reported $0.00. That wrapper is gone; spend is read from the room
   stores, where dvergr's turn path actually writes it.

   Party-level accounting returns with the prepaid-budget model as kontor
   postings (a party's prepaid balance, drawn down by room consumption) rather
   than as a second `:ledger/*` ledger duplicating the room's."
  [party-id {:keys [model provider input-tokens output-tokens]}]
  (when-let [conn (system-db/get-conn)]
    (let [chat-id (ensure-billing-chat! conn party-id)
          provider-kw (some-> provider keyword)
          ;; nil :model/:provider are fine — acct/record-usage! cond->'s on them
          record! (fn [resource amount]
                    (acct/record-usage! conn chat-id resource amount
                                        :model model :provider provider-kw))]
      (when input-tokens (record! :input-tokens input-tokens))
      (when output-tokens (record! :output-tokens output-tokens))
      (log/log! {:level :debug :id ::llm-usage-recorded
                 :data {:party-id party-id :model model
                        :input-tokens input-tokens
                        :output-tokens output-tokens}}))))

;; =============================================================================
;; Querying Usage
;; =============================================================================

(defn get-party-usage
  "Aggregate usage stats for a party from its billing-chat ledger.
   Returns {:total-input-tokens :total-output-tokens :total-cost-microdollars}."
  [party-id]
  (when-let [conn (system-db/get-conn)]
    (let [chat-id (party-billing-chat-id party-id)
          rows (d/q '[:find ?resource (sum ?amount) (sum ?cost)
                      :keys resource total-amount total-cost
                      :in $ ?cid
                      :where
                      [?c :chat/id ?cid]
                      [?l :ledger/context ?c]
                      [?l :ledger/resource ?resource]
                      [?l :ledger/amount ?amount]
                      [?l :ledger/cost-microdollars ?cost]]
                    @conn chat-id)]
      (reduce (fn [acc {:keys [resource total-amount total-cost]}]
                (cond-> (update acc :total-cost-microdollars + total-cost)
                  (= resource :input-tokens)
                  (update :total-input-tokens + total-amount)
                  (= resource :output-tokens)
                  (update :total-output-tokens + total-amount)))
              {:total-input-tokens 0
               :total-output-tokens 0
               :total-cost-microdollars 0}
              rows))))

;; =============================================================================
;; Budgets (dvergr :budget/* attached to the party's billing chat)
;; =============================================================================

(defn get-party-budget
  "Return {:total :used :remaining} microdollars, or nil if unset.
   Total comes from the :budget/* entity on the billing chat; used is
   derived from the ledger (sum of :ledger/cost-microdollars)."
  [party-id]
  (when-let [conn (system-db/get-conn)]
    (let [chat-id (party-billing-chat-id party-id)
          total (d/q '[:find ?t . :in $ ?cid
                       :where
                       [?c :chat/id ?cid]
                       [?b :budget/context ?c]
                       [?b :budget/total-microdollars ?t]]
                     @conn chat-id)]
      (when total
        (let [used (acct/get-total-cost conn chat-id)]
          {:total total :used used :remaining (- total used)})))))

(defn set-party-budget!
  "Set the party's total budget (microdollars) on its billing chat."
  [party-id total-microdollars]
  (when-let [conn (system-db/get-conn)]
    (let [chat-id (ensure-billing-chat! conn party-id)]
      (d/transact conn [{:budget/context [:chat/id chat-id]
                         :budget/total-microdollars (long total-microdollars)}]))))

(defn has-party-budget?
  "True if the party can afford cost-microdollars (or has no budget set)."
  [party-id cost-microdollars]
  (if-let [b (get-party-budget party-id)]
    (>= (:remaining b) cost-microdollars)
    true))

;; =============================================================================
;; Admin Queries
;; =============================================================================

(defn- room-ledger-stats
  "Sum `:ledger/*` across every room store — where the turn path actually writes.

   `dvergr.chat.context/account-usage!` records usage on the ROOM's conn, so the
   room stores hold the real spend. Reading it means touching each room's
   database; rooms are hydrated at boot and their conns are cached, so this is a
   fan-out of in-memory queries rather than a fan-out of connects. A room that
   isn't hydrated in this process contributes nothing rather than failing the
   whole dashboard."
  []
  (let [scopes (d/q '[:find [?scope ...]
                      :where [?r :room/content-db-scope ?scope]]
                    @(system-db/get-conn))
        connect (requiring-resolve 'is.simm.model.room-databases/connect-room-database)]
    (reduce
     (fn [acc scope]
       (if-let [db (try (some-> (connect scope) deref)
                        (catch Throwable e
                          ;; An unreachable room store silently drops that
                          ;; room's spend from the total — the dashboard then
                          ;; UNDER-reports cost, which looks like a plausible
                          ;; number rather than a failure. Never silent.
                          (log/log! {:level :warn :id ::room-spend-unreadable
                                     :msg "Room store unreadable — its spend is missing from the total"
                                     :data {:scope (str scope) :error (.getMessage e)}})
                          nil))]
         (let [cost (or (d/q '[:find (sum ?c) . :where [?l :ledger/cost-microdollars ?c]] db) 0)
               rows (d/q '[:find ?model (count ?l) (sum ?cost)
                           :keys model count total-cost
                           :where
                           [?l :ledger/model ?model]
                           [?l :ledger/cost-microdollars ?cost]]
                         db)]
           (-> acc
               (update :total-cost-microdollars + cost)
               (update :by-model
                       (fn [m]
                         (reduce (fn [m {:keys [model count total-cost]}]
                                   (-> m
                                       (update-in [model :count] (fnil + 0) count)
                                       (update-in [model :total-cost] (fnil + 0) total-cost)))
                                 m rows)))))
         acc))
     {:total-cost-microdollars 0 :by-model {}}
     scopes)))

(defn get-system-stats
  "System-wide stats for the admin dashboard.
   Returns {:total-humans :total-agents :total-parties
            :total-cost-microdollars :model-usage [{:model :count :total-cost}]}.

   Party counts come from :actor/kind (with legacy :party/type fallback for rows
   kabel-auth created since the last migration).

   Cost and model usage are aggregated over the ROOM stores, not the system DB.
   The system DB's billing-chat ledger was only ever written by
   `record-usage!` below, whose one caller depended on a `:message/usage` field
   that does not exist anywhere in dvergr — so that ledger has always been
   empty, and this dashboard reported $0.00 while real spend accumulated in the
   room stores."
  []
  (when-let [conn (system-db/get-conn)]
    (let [kinds (->> (d/q '[:find ?e ?kind ?type
                            :where
                            [?e :party/id _]
                            [(get-else $ ?e :actor/kind :none) ?kind]
                            [(get-else $ ?e :party/type :none) ?type]]
                          @conn)
                     (map (fn [[_ kind type]]
                            (if (not= kind :none) kind type))))
          total-humans (count (filter #(= :human %) kinds))
          total-agents (count (filter #(= :agent %) kinds))
          {:keys [total-cost-microdollars by-model]} (room-ledger-stats)]
      {:total-humans total-humans
       :total-agents total-agents
       :total-parties (count kinds)
       :total-cost-microdollars total-cost-microdollars
       :model-usage (->> by-model
                         (mapv (fn [[model m]] (assoc m :model model)))
                         (sort-by :total-cost >)
                         vec)})))
