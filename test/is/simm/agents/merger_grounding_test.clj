(ns is.simm.agents.merger-grounding-test
  "\"Yes\" means yes.

   `review-op` asks a model whether a proposed wiki edit is grounded in the
   source, then reads the LAST yes/no out of the reply. The regex was
   case-INSENSITIVE and the comparison case-SENSITIVE, so `\"Yes.\"` — the form
   a model overwhelmingly answers with — read as NOT grounded. `merge-summary!`
   then dropped the claim and logged it under `::grounding-failures`, so a
   grounded edit was silently lost and the log blamed the model for it."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.agents.merger :as merger]))

(defn- verdict
  "Run `review-op` against a stubbed model reply and read its answer."
  [reply]
  (with-redefs [requiring-resolve
                (fn [sym]
                  (if (= sym 'dvergr.tools.llm-call/cheap-llm-call)
                    (fn [& _] {:text reply})
                    (clojure.core/requiring-resolve sym)))]
    (merger/review-op {:op :add :title "T" :text "claim"} "source text")))

(deftest yes-is-case-insensitive
  (testing "every casing a model actually produces reads as grounded"
    (doseq [r ["yes" "Yes" "YES" "Yes." "yes."
               "The claim appears in the source. Yes"]]
      (is (true? (boolean (verdict r))) (str "should be grounded: " (pr-str r)))))

  (testing "and no still means no, in any casing"
    (doseq [r ["no" "No" "NO" "No." "Not supported by the source. No"]]
      (is (false? (boolean (verdict r))) (str "should NOT be grounded: " (pr-str r))))))

(deftest the-last-verdict-wins
  (testing "leaked deliberation before the answer does not decide it"
    ;; Reasoning models think out loud; only the final token is the verdict.
    (is (true? (boolean (verdict "First I thought no, but on reflection: Yes"))))
    (is (false? (boolean (verdict "This looks like a yes at first. No"))))))
