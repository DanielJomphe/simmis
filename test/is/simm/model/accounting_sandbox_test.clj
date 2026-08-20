(ns is.simm.model.accounting-sandbox-test
  "The room-agent sandbox (`clojure_eval`) can post GOVERNED double-entry
   accounting: the `kontor` namespace injected by
   `is.simm.model.accounting-sandbox` writes to the room's governed book, so a
   balanced entry is accepted and persists, while an unbalanced write against the
   same conn is rejected in the writer. Mirrors what Vár does at runtime, minus
   the room/system-db plumbing (we build the governed conn directly)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [sci.core :as sci]
            [kontor.core :as kcore]
            [kontor.governance :as gov]
            [is.simm.model.accounting-sandbox :as as]))

(def ^:private eur  [:kontor.commodity/symbol "EUR"])
(def ^:private gen  [:kontor.journal/code "GEN"])
(def ^:private cash [:kontor.account/path "Assets:Cash"])
(def ^:private rev  [:kontor.account/path "Income:Sales"])

(defn- governed-book!
  "A fresh in-memory kontor book with commodity/journal/accounts installed and
   the store governed by `kontor.governance/validate-report` — the same shape
   `accounting/ensure-accounting-schema!` produces for a room's content-db."
  []
  (let [conn (kcore/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                 {:kontor.journal/code "GEN" :kontor.journal/type :general}
                 {:kontor.account/path "Assets:Cash"  :kontor.account/type :asset  :kontor.account/active true}
                 {:kontor.account/path "Income:Sales" :kontor.account/type :income :kontor.account/active true}])
    (gov/govern! conn)
    conn))

(defn- sandbox-ctx
  "A SCI context with the `kontor` namespace injected over `conn` (via the pure
   `kontor-bindings` the real dvergr injector uses) plus a minimal `d` surface —
   the same two namespaces a live agent sandbox exposes for accounting work."
  [conn]
  (sci/init
   {:namespaces {'kontor (as/kontor-bindings conn)
                 'd {'transact (fn [c tx] (d/transact c tx))
                     'q (fn [query & args] (apply d/q query args))}}}))

(defn- posting-count [conn]
  (or (d/q '[:find (count ?p) . :where [?p :kontor.posting/amount _]] @conn) 0))

(deftest sandbox-posts-a-governed-balanced-entry
  (testing "a balanced kontor/entry! through clojure_eval is accepted and persists"
    (let [conn (governed-book!)
          ctx  (sandbox-ctx conn)]
      (is (zero? (posting-count conn)))
      (sci/eval-string*
       ctx
       "(kontor/entry! {:debit-account [:kontor.account/path \"Assets:Cash\"]
                        :credit-account [:kontor.account/path \"Income:Sales\"]
                        :amount 100 :commodity [:kontor.commodity/symbol \"EUR\"]
                        :journal [:kontor.journal/code \"GEN\"]
                        :effective-date #inst \"2026-03-15\"})")
      (is (= 2 (posting-count conn)) "both legs of the balanced entry landed"))))

(deftest sandbox-rejects-an-unbalanced-write
  (testing "an unbalanced raw write against the governed book is rejected in the writer"
    (let [conn (governed-book!)
          ctx  (sandbox-ctx conn)
          run  #(sci/eval-string*
                 ctx
                 "(d/transact kontor/*book*
                    [{:db/id -1 :kontor.transaction/journal [:kontor.journal/code \"GEN\"]
                      :kontor.transaction/effective-date #inst \"2026-03-15\" :kontor.transaction/state :draft}
                     {:db/id -2 :kontor.posting/transaction -1 :kontor.posting/account [:kontor.account/path \"Assets:Cash\"]
                      :kontor.posting/amount 5M :kontor.posting/commodity [:kontor.commodity/symbol \"EUR\"] :kontor.posting/display-type :product}
                     {:db/id -3 :kontor.posting/transaction -1 :kontor.posting/account [:kontor.account/path \"Income:Sales\"]
                      :kontor.posting/amount -4M :kontor.posting/commodity [:kontor.commodity/symbol \"EUR\"] :kontor.posting/display-type :product}])")]
      (is (thrown? Exception (run)) "the governor rejects the sum-to-zero violation")
      (is (zero? (posting-count conn)) "nothing from the rejected write persisted"))))
