(ns is.simm.ops.reconciliation
  "Customer accounts as RECORDS, and the query that reads them.

   \"Who was charged and never provisioned\" is the proof that simmis holds a
   company's records rather than documents about them, so it has to be a
   DATALOG QUERY OVER FACTS. The absence of `:S.Customer/provisioned-at` is the
   incident, and datalog can express absence (`missing?`) against an index —
   a wiki page listing the affected accounts could only ever be a snapshot of
   an answer, correct until the first credit was booked.

   That is also why nothing here pulls the customers into Clojure and filters
   them: the window bound and the absence bound are both `:where` clauses, so
   the same question runs against a 46-row demo fixture and against a real
   book, and it runs at whatever `as-of` the caller hands it — which is what
   makes \"stand on 09:00 and the books are wrong\" a query rather than a prop.

   Money is minor units (long cents) end to end. `:total-major` is a
   BigDecimal, produced by moving the decimal point, never a double: the
   figure this namespace returns is the one that gets posted into a
   double-entry ledger."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [taoensso.telemere :as log]))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(defn read-incident
  "Read a customer fixture from `resources/demo/<id>.edn` (default `:incident`).

   Classpath first (`resources` is on `:paths` and copied into the uberjar),
   with a working-directory fallback. Same lookup as
   `demo.scenario/read-scenario`."
  ([] (read-incident :incident))
  ([id]
   (let [rel (str "demo/" (name id) ".edn")
         src (or (io/resource rel)
                 (let [f (io/file "resources" rel)] (when (.exists f) f)))]
     (some-> src slurp edn/read-string))))

(defn- ->inst
  "Fixture timestamps are ISO-8601 strings (scenario convention — an explicit
   instant is what makes a re-seed reproduce the same timeline). Queries and
   tests hand in `java.util.Date` directly, so accept both at the boundary and
   keep `Date` as the single internal representation."
  [t]
  (cond
    (instance? java.util.Date t) t
    (instance? java.time.Instant t) (java.util.Date/from t)
    (string? t) (java.util.Date/from (java.time.Instant/parse t))
    :else (throw (ex-info "not a timestamp" {:value t}))))

;; ---------------------------------------------------------------------------
;; Seeding
;; ---------------------------------------------------------------------------

(defn- customer-tx
  [{:keys [account-id email plan amount-cents charged-at provisioned-at credited-at]}]
  (let [charged (->inst charged-at)]
    (cond-> {;; Derived, not random: a re-seed reproduces the same entity, so a
             ;; test or a mention can name the record across runs. The
             ;; account-id is already `:db.unique/identity`, so this only adds
             ;; a stable handle in the shared entity space.
             :entity/uuid (hasch/uuid [:customer account-id])
             ;; CONTENT time, which is what the timeline rail should plot. The
             ;; rail reads `:db/txInstant` today and a one-minute seeder piles
             ;; every dot into the left 5% of it (doc/demo-script-v3.md §5);
             ;; `:entity/created-at` is the honest axis and costs one datom.
             :entity/created-at charged
             :entity/updated-at (->inst (or credited-at provisioned-at charged-at))
             :S.Customer/account-id account-id
             :S.Customer/charged-at charged}
      email (assoc :S.Customer/email email)
      plan (assoc :S.Customer/plan plan)
      amount-cents (assoc :S.Customer/amount-cents (long amount-cents))
      ;; NOT `(assoc :S.Customer/provisioned-at nil)` — datahike would reject
      ;; the nil, and a placeholder value would destroy the query: absence is
      ;; the fact being recorded.
      provisioned-at (assoc :S.Customer/provisioned-at (->inst provisioned-at))
      credited-at (assoc :S.Customer/credited-at (->inst credited-at)))))

(defn seed-customers!
  "Transact `customers` (maps with unnamespaced `:account-id :email :plan
   :amount-cents :charged-at :provisioned-at :credited-at`) into `conn`.
   Returns the number of records written.

   Upserts on `:S.Customer/account-id`, so re-seeding corrects a fixture rather
   than duplicating it.

   `opts` may carry `:at` (a `:db/txInstant` for the transaction) and `:author`
   (a party uuid string for `:tx/author`). Back-dating a transaction works —
   `datahike.db.transaction/next-tx-instant` is `^:dynamic` and a caller's
   `:db/txInstant` wins — which is how the incident lands on the timeline at
   09:04 instead of at seed time."
  ([conn customers] (seed-customers! conn customers nil))
  ([conn customers {:keys [at author]}]
   (let [tx (mapv customer-tx customers)
         meta (cond-> {} at (assoc :db/txInstant (->inst at))
                      author (assoc :tx/author (str author)))]
     (d/transact conn (cond-> {:tx-data tx} (seq meta) (assoc :tx-meta meta)))
     (log/log! {:level :info :id ::customers-seeded
                :msg "Customer records seeded"
                :data {:count (count tx)}})
     (count tx))))

(defn seed-incident!
  "Seed a whole fixture map (`read-incident`'s shape) into `conn`. Returns
   `{:incident :window :seeded}` so the caller can hand the window straight to
   the query without re-reading the file.

   A fixture may carry `:at` / `:author`; they go to `seed-customers!` as
   transaction metadata, which is how a seeder writing at 21:00 still puts the
   incident on the timeline where it happened."
  ([conn] (seed-incident! conn (read-incident)))
  ([conn {:keys [incident window customers] :as fixture}]
   (when-not (seq customers)
     (throw (ex-info "fixture has no customers" {:incident incident})))
   {:incident incident
    :window window
    :seeded (seed-customers! conn customers (select-keys fixture [:at :author]))}))

;; ---------------------------------------------------------------------------
;; The reconciliation query
;; ---------------------------------------------------------------------------

(def ^:private customer-pull
  [:S.Customer/account-id :S.Customer/email :S.Customer/plan
   :S.Customer/amount-cents :S.Customer/charged-at :S.Customer/credited-at])

(def ^:private windowed-q
  "Charged inside [from to] and never provisioned.

   `<=` here is not `clojure.core/<=`: datahike resolves it to `-decreasing?`,
   which has a `Date` implementation, so instants compare directly and the
   window needs no millisecond conversion.

   `missing?` probes the entity's attributes in the index. That is what lets
   \"never provisioned\" stay an absence rather than a flag some writer has to
   remember to set — a flag is a second copy of the truth, and this query
   exists because the first copy is the point."
  '[:find [?c ...]
    :in $ ?from ?to
    :where
    [?c :S.Customer/charged-at ?t]
    [(<= ?from ?t)]
    [(<= ?t ?to)]
    [(missing? $ ?c :S.Customer/provisioned-at)]])

(def ^:private all-time-q
  '[:find [?c ...]
    :where
    [?c :S.Customer/charged-at _]
    [(missing? $ ?c :S.Customer/provisioned-at)]])

(defn- customers
  "Eids → records, oldest charge first. `pull-many` rather than `(pull ?c …)`
   in `:find`: the pattern stays one def shared by every entry point here."
  [db eids]
  (->> (d/pull-many db customer-pull eids)
       (sort-by :S.Customer/charged-at)
       vec))

(defn charged-not-provisioned
  "Customers charged in `window` (`{:from :to}`, inclusive) with no
   `:S.Customer/provisioned-at`. Without a window, every such customer ever —
   which is a different question, and answering it by accident is how an
   incident report picks up an unrelated failure from last week."
  ([db]
   (customers db (d/q all-time-q db)))
  ([db {:keys [from to]}]
   (customers db (d/q windowed-q db (->inst from) (->inst to)))))

(defn owed-credits
  "Charged, never provisioned, and not yet credited — the list to act on.

   Distinct from `charged-not-provisioned`, which is what HAPPENED. Once a
   credit is booked the customer is still part of the incident, so the two
   answers diverge the moment `record-credits!` runs; a credit lane driven off
   the incident list alone would refund the same customer twice."
  ([db] (vec (remove :S.Customer/credited-at (charged-not-provisioned db))))
  ([db window] (vec (remove :S.Customer/credited-at
                            (charged-not-provisioned db window)))))

(defn exposure
  "What is at risk: `{:count :total-cents :total-major :customers}`.

   Computed once and carried, so the agent's message, the credit lane and the
   UI all report the same number instead of each running its own sum — three
   independent totals for one incident is exactly the failure this namespace
   exists to prevent.

   Arity 1 summarises a list you already have (from `charged-not-provisioned`
   or `owed-credits`); arity 2 runs the incident query for `window` itself."
  ([customer-rows]
   (let [cents (reduce + 0 (keep :S.Customer/amount-cents customer-rows))]
     {:count (count customer-rows)
      :total-cents cents
      ;; exact minor→major units; `(/ cents 100.0)` is the bug this avoids
      :total-major (.movePointLeft (bigdec cents) 2)
      :customers (vec customer-rows)}))
  ([db window]
   (exposure (charged-not-provisioned db window))))

;; ---------------------------------------------------------------------------
;; Closing the loop
;; ---------------------------------------------------------------------------

(defn record-credits!
  "Stamp `:S.Customer/credited-at` on each account id. Returns the count.

   The credit lane's write-back: without it `credited-at` is an attribute
   nothing ever sets and `owed-credits` can only ever equal the incident list.
   Upserts by account id, so an account already credited is simply restamped
   rather than duplicated — but `owed-credits` is what should be feeding this."
  [conn account-ids at]
  (let [when- (->inst at)
        tx (mapv (fn [id] {:S.Customer/account-id id :S.Customer/credited-at when-})
                 account-ids)]
    (d/transact conn tx)
    (log/log! {:level :info :id ::credits-recorded
               :msg "Customer credits recorded"
               :data {:count (count tx) :at when-}})
    (count tx)))
