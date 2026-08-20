(ns is.simm.ops.reconciliation-test
  "The reconciliation query is the demo's \"records, not documents\" proof, so
   what is asserted here is that it EXCLUDES correctly. A query that returned
   every customer would satisfy a naive count against a fixture where every
   customer is affected — hence the late-provisioned and out-of-window cases."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.schema :as schema]
            [is.simm.ops.reconciliation :as recon]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn schema/full-schema)
      conn)))

(def ^:private window
  {:from "2026-07-27T09:04:00Z" :to "2026-07-27T15:04:00Z"})

(def ^:private customers
  [{:account-id "cus_in_1" :email "a@example.com" :plan "Starter"
    :amount-cents 2900 :charged-at "2026-07-27T09:06:00Z"}
   {:account-id "cus_in_2" :email "b@example.com" :plan "Team"
    :amount-cents 7900 :charged-at "2026-07-27T11:30:00Z"}
   {:account-id "cus_in_3" :email "c@example.com" :plan "Business"
    :amount-cents 14900 :charged-at "2026-07-27T14:59:00Z"}
   ;; charged in the window and provisioned by hand hours later — the case an
   ;; "unprovisioned as of the window's end" filter gets wrong
   {:account-id "cus_late" :email "d@example.com" :plan "Team"
    :amount-cents 7900 :charged-at "2026-07-27T10:00:00Z"
    :provisioned-at "2026-07-27T18:40:00Z"}
   ;; provisioned immediately: what the handler does when it works
   {:account-id "cus_ok" :email "e@example.com" :plan "Starter"
    :amount-cents 2900 :charged-at "2026-07-27T09:20:00Z"
    :provisioned-at "2026-07-27T09:20:14Z"}
   ;; unprovisioned, but the day BEFORE — owed something, not owed by this
   ;; incident
   {:account-id "cus_before" :email "f@example.com" :plan "Business"
    :amount-cents 14900 :charged-at "2026-07-26T16:58:00Z"}
   ;; unprovisioned, after the handler was fixed
   {:account-id "cus_after" :email "g@example.com" :plan "Starter"
    :amount-cents 2900 :charged-at "2026-07-27T15:40:00Z"}])

(defn- ids [rows] (set (map :S.Customer/account-id rows)))

(deftest charged-but-never-provisioned
  (let [conn (fresh-conn)]
    (recon/seed-customers! conn customers)
    (let [db @conn
          found (recon/charged-not-provisioned db window)]
      (testing "exactly the unprovisioned charges inside the window"
        (is (= #{"cus_in_1" "cus_in_2" "cus_in_3"} (ids found))))
      (testing "a customer provisioned LATE is provisioned, not affected"
        (is (not (contains? (ids found) "cus_late"))))
      (testing "the window bounds, not just the absence, do work"
        (is (not (contains? (ids found) "cus_before")) "charged the day before")
        (is (not (contains? (ids found) "cus_after")) "charged after the fix")
        (is (= #{"cus_in_1" "cus_in_2" "cus_in_3" "cus_before" "cus_after"}
               (ids (recon/charged-not-provisioned db)))
            "un-windowed is a genuinely different, larger answer"))
      (testing "the window is inclusive and reads instants, not strings"
        (is (contains? (ids (recon/charged-not-provisioned
                             db {:from "2026-07-27T09:06:00Z"
                                 :to "2026-07-27T09:06:00Z"}))
                       "cus_in_1")))
      (testing "rows carry what a credit note needs, oldest charge first"
        (is (= ["cus_in_1" "cus_in_2" "cus_in_3"]
               (mapv :S.Customer/account-id found)))
        (is (= "a@example.com" (:S.Customer/email (first found))))))))

(deftest exposure-sums-the-affected-only
  (let [conn (fresh-conn)]
    (recon/seed-customers! conn customers)
    (let [e (recon/exposure @conn window)]
      (is (= 3 (:count e)))
      (is (= (+ 2900 7900 14900) (:total-cents e)) "the provisioned ones are not at risk")
      (is (= 3 (count (:customers e))) "the list rides along, so nothing recomputes")
      (testing "major units are exact"
        (is (= (bigdec "257.00") (:total-major e)))
        (is (instance? java.math.BigDecimal (:total-major e)))))))

(deftest credits-are-owed-until-they-are-booked
  (let [conn (fresh-conn)]
    (recon/seed-customers! conn customers)
    (is (= 3 (:count (recon/exposure (recon/owed-credits @conn window)))))
    (recon/record-credits! conn ["cus_in_2"] "2026-07-27T16:10:00Z")
    (let [db @conn]
      (testing "a credited customer is still part of the incident"
        (is (= 3 (count (recon/charged-not-provisioned db window)))))
      (testing "but is no longer owed one — no double refund"
        (let [e (recon/exposure (recon/owed-credits db window))]
          (is (= 2 (:count e)))
          (is (= (+ 2900 14900) (:total-cents e))))))))

(deftest incident-fixture-is-the-demo-figures
  (testing "resources/demo/incident.edn seeds, and the query finds 41 accounts"
    (let [conn (fresh-conn)
          fixture (recon/read-incident)
          {:keys [window seeded]} (recon/seed-incident! conn fixture)
          e (recon/exposure @conn window)]
      (is (= 46 seeded))
      (is (= 41 (:count e)) "the number the script says on camera")
      ;; SEK minor units (öre). Kestrel is Swedish and its ledger is in SEK, so
      ;; the customer fixture is too — a company whose books and whose Stripe
      ;; charges disagree on currency is incoherent on screen.
      (is (= 2765900 (:total-cents e)))
      (is (= (bigdec "27659.00") (:total-major e)) "plausible for 41 small accounts, not impressive")
      (testing "every affected account is a real-looking record, not a stub"
        (let [rows (recon/charged-not-provisioned @conn window)]
          (is (every? :S.Customer/email rows))
          (is (every? :S.Customer/plan rows))
          (is (every? pos? (map :S.Customer/amount-cents rows)))
          (is (= 41 (count (set (map :S.Customer/account-id rows))))
              "no duplicate account ids in the fixture"))))))
