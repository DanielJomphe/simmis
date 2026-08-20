(ns is.simm.ops.book-fork-test
  "A BOOK fork: postings proposed on a branch of the room store, reviewed as
   double entry, landed on trunk by `accept-fork!`.

   Before this, an agent's `kontor/entry!` wrote straight to the room's live
   book — money movements were the one artifact it produced that a human could
   not see before it existed. A book fork makes them a proposal like any other.

   The second deftest answers the question the change raises: kontor validates
   sum-to-zero IN THE WRITER (`kontor.governance/govern!` registers a
   `datahike.tx-preds` predicate by store-id), and a merge does not go through
   `d/transact` — so does the governor still run? Statically yes:
   datahike's `writer/default-write-fn-map` wraps `'merge!` in `with-tx-pred`
   exactly as it wraps `'transact!`, and `check-report` keys on the store-id in
   the report's `db-after`, which a branch shares with its trunk. This test
   proves it empirically rather than by reading, because a book that could
   acquire an unbalanced entry by way of a merge would be a silent hole under
   the whole governed-store claim."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as kcore]
            [kontor.governance :as gov]
            [is.simm.model.system-db :as sdb]
            [is.simm.ops.proposals :as props]
            [is.simm.ops.semantic-diff :as sd]
            [is.simm.runtimes.branching :as branching]
            [is.simm.runtimes.context :as ctx]))

(def ^:private usd  [:kontor.commodity/symbol "USD"])
(def ^:private gen  [:kontor.journal/code "GEN"])
(def ^:private bank [:kontor.account/path "Assets:Bank"])
(def ^:private sales [:kontor.account/path "Income:Sales"])

(defn- governed-branchable-book!
  "A governed kontor book on a BRANCHABLE store — `:commit-graph?` on top of the
   `:crypto-hash?`/`:keep-history?` kontor already wants, because branch ancestry
   (and therefore `merge-kb!`) is read from the commit graph. This is the shape
   `store/install!` produces for a room store, minus the room plumbing."
  []
  (let [scope (random-uuid)
        conn (kcore/create-test-db {:store {:backend :memory :id scope}
                                    :schema-flexibility :write
                                    :keep-history? true
                                    :crypto-hash? true
                                    :commit-graph? true})]
    (d/transact conn
                [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Bank" :kontor.account/type :asset
                  :kontor.account/active true}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income
                  :kontor.account/active true}])
    (gov/govern! conn)
    (branching/register-system! conn scope)
    [scope conn]))

(defn- fresh-system-db []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (doto (d/connect cfg) (d/transact sdb/schema))))

(defn- postings [db]
  (or (d/q '[:find (count ?p) . :where [?p :kontor.posting/amount _]] db) 0))

(defn- balance-by-commodity
  "Signed sum per commodity across the WHOLE book. Zero is the double-entry
   invariant: every transaction sums to zero, so their union does too. `?p` is
   bound so two legs with the same amount are not collapsed by `:find`'s set
   semantics — the exact trap `kontor.governance` documents."
  [db]
  (->> (d/q '[:find ?p ?sym ?amt :where
              [?p :kontor.posting/amount ?amt]
              [?p :kontor.posting/commodity ?c] [?c :kontor.commodity/symbol ?sym]]
            db)
       (reduce (fn [m [_ sym amt]] (update m sym (fnil + 0M) (bigdec amt))) {})))

(defn- narrations [db]
  (set (d/q '[:find [?n ...] :where [?t :kontor.transaction/narration ?n]] db)))

(deftest book-fork-is-absent-from-trunk-until-accepted-then-balances
  (ctx/with-server-context
    (let [sys-conn (fresh-system-db)]
      (with-redefs [sdb/get-conn (fn [] sys-conn)]
        (let [[scope conn] (governed-branchable-book!)
              base (branching/branch-head-id scope :db)
              {branch :branch} (branching/branch-kb! scope "q3-invoice")
              bconn (branching/get-kb-conn-on-branch scope branch)]

          (testing "the entry posts on the BRANCH, leaving the live book untouched"
            (book/entry! bconn {:debit-account bank :credit-account sales
                                :amount 1250 :commodity usd :journal gen
                                :narration "Invoice 2026-041 — Tröskel retainer"
                                :effective-date #inst "2026-07-15"})
            (is (= 2 (postings @bconn)))
            (is (zero? (postings @conn)) "nothing on trunk before the human decides"))

          (testing "the diff renders the postings as double entry, exactly"
            (let [diff (sd/semantic-diff scope branch :book :base-commit base)
                  entry (first (:entries diff))]
              (is (= :book (:system-type diff)))
              (is (= 1 (count (:entries diff))))
              (is (= "Invoice 2026-041 — Tröskel retainer" (:narration entry)))
              (is (= #inst "2026-07-15" (:effective-date entry)))
              (is (= "GEN" (:journal entry)))
              (is (true? (:balanced? entry)))
              (is (true? (:balanced? diff)))
              (is (= #{{:account "Assets:Bank" :side :debit
                        :amount "1250.00" :commodity "USD"}
                       {:account "Income:Sales" :side :credit
                        :amount "1250.00" :commodity "USD"}}
                     (set (:lines entry)))
                  "amounts are exact decimal STRINGS — never a float, never a raw BigDecimal")))

          (testing "accepting the fork lands the postings on trunk, still balanced"
            (let [pid (props/file-proposal!
                       {:title "Q3 retainer invoice"
                        :author (random-uuid)
                        :forks [{:scope scope :branch branch :base-commit base
                                 :system-type :book}]})
                  result (props/accept-fork! pid scope branch)]
              (is (= :accepted (:status result)) (str "unexpected: " (pr-str result)))
              (let [trunk @(branching/get-kb-conn scope)]
                (is (= 2 (postings trunk)) "both legs landed — a half-merged entry is corruption")
                (is (= #{"Invoice 2026-041 — Tröskel retainer"} (narrations trunk)))
                (is (= {"USD" 0M} (balance-by-commodity trunk))
                    "the book balances after the merge")))))))))

(deftest merging-a-book-fork-re-runs-the-governor
  (testing "an unbalanced branch cannot be merged into a governed trunk"
    (ctx/with-server-context
      (let [[scope conn] (governed-branchable-book!)
            {branch :branch} (branching/branch-kb! scope "unbalanced")
            bconn (branching/get-kb-conn-on-branch scope branch)]
        ;; The governor has to be lifted to CREATE the bad branch — it rejects
        ;; the write on the branch too (same store-id). That is the point: the
        ;; only way this state can exist is a writer that was not governed, and
        ;; the question is whether the merge into a governed trunk catches it.
        (gov/ungovern! conn)
        (d/transact bconn
                    [{:db/id -1 :kontor.transaction/journal gen
                      :kontor.transaction/effective-date #inst "2026-07-15"
                      :kontor.transaction/state :draft
                      :kontor.transaction/narration "off by one"}
                     {:db/id -2 :kontor.posting/transaction -1
                      :kontor.posting/account bank
                      :kontor.posting/amount 5M :kontor.posting/commodity usd
                      :kontor.posting/display-type :product}
                     {:db/id -3 :kontor.posting/transaction -1
                      :kontor.posting/account sales
                      :kontor.posting/amount -4M :kontor.posting/commodity usd
                      :kontor.posting/display-type :product}])
        (gov/govern! conn)
        (is (thrown? Exception (branching/merge-kb! scope branch :db))
            "the writer's tx-pred runs on merge, not only on transact")
        (is (zero? (postings @(branching/get-kb-conn scope)))
            "the rejected merge left the trunk untouched")))))
