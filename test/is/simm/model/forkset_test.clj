(ns is.simm.model.forkset-test
  "Routing is the whole point of `is.simm.model.forkset`: it decides which VIEW
   a ForkSet appears in. A silent change here relocates work in the UI, which
   is a worse failure than an exception — nobody notices a task that stopped
   being listed. So the table is pinned."
  (:require [clojure.test :refer [deftest testing is]]
            [is.simm.model.forkset :as fs]))

(deftest destination-table
  (testing "a change routes on mergeability"
    (is (= :tasks (fs/destination :change :reviewable)))
    (is (= :tasks (fs/destination :change :trivial))
        "trivial still lands — it is ready, just uninteresting")
    (is (= :futures (fs/destination :change :conflict))
        "not mergeable ⇒ a future, not a task"))

  (testing "intents other than :change never claim readiness"
    (doseq [i [:budget :goal :scenario]
            t [:reviewable :trivial :conflict nil]]
      (is (= :futures (fs/destination i t))
          (str i " with tier " t " must stay a future — a plan is not a patch"))))

  (testing "an uncomputed tier is not a guess"
    (is (= :unclassified (fs/destination :change nil))
        "mergeability streams in per card; guessing makes the item jump views"))

  (testing "absent intent means :change"
    (is (= (fs/destination :change :reviewable) (fs/destination nil :reviewable)))
    (is (= (fs/destination :change :conflict) (fs/destination nil :conflict)))
    (is (= :change fs/default-intent)))

  (testing "a tier this code does not know does not reach Tasks"
    (is (= :futures (fs/destination :change :some-future-dvergr-tier))
        "Tasks asserts readiness; an unrecognised tier has not established it")))

(deftest auto-mergeable
  (is (true? (fs/auto-mergeable? :change :trivial)))
  (is (true? (fs/auto-mergeable? nil :trivial)) "default intent applies here too")
  (is (false? (fs/auto-mergeable? :change :reviewable)))
  (is (false? (fs/auto-mergeable? :change :conflict)))
  (is (false? (fs/auto-mergeable? :change nil)))
  (is (false? (fs/auto-mergeable? :budget :trivial))
      "a budget whose branch happens to merge is still not a formality"))

(deftest intents-are-closed
  (is (contains? fs/intents fs/default-intent))
  (is (= #{:change :budget :goal :scenario} fs/intents)
      "file-proposal! validates against this set — widening it is a schema
       decision, not an incidental edit"))
