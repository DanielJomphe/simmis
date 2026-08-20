(ns is.simm.agents.vocab-test
  "The agent's vocabulary is an API with exactly one consumer that cannot read
   the source, so its documentation is load-bearing rather than courtesy.

   These tests pin the two things that silently rot: metadata that stops being
   attached (the fns keep working, they just go invisible to `doc` again), and
   doc entries that outlive the verb they describe (worse than no entry — `doc`
   would advertise a call that fails)."
  (:require [clojure.test :refer [deftest testing is]]
            [is.simm.agents.vocab :as vocab]))

(deftest with-docs-attaches-metadata
  (testing "a documented fn carries :arglists and :doc for dvergr to surface"
    (let [out (vocab/with-docs 'kb
                {'ensure-page! (fn [_ _] :ok)}
                '{ensure-page! {:arglists ([db title]) :doc "Creates a page."}})
          m (meta (get out 'ensure-page!))]
      (is (= '([db title]) (:arglists m)))
      (is (= "Creates a page." (:doc m)))
      (is (= 'ensure-page! (:name m)))))

  (testing "the fn itself still works — metadata must not wrap or replace it"
    (let [out (vocab/with-docs 'kb {'f (fn [x] (* 2 x))}
                               '{f {:arglists ([x]) :doc "Doubles."}})]
      (is (= 6 ((get out 'f) 3)))))

  (testing "an undocumented fn passes through untouched rather than erroring"
    (let [out (vocab/with-docs 'kb {'g (fn [] :g)} '{})]
      (is (= :g ((get out 'g))))
      (is (nil? (:doc (meta (get out 'g))))))))

(deftest documented-verbs-exist
  (testing "no doc entry describes a verb that is gone"
    ;; `undocumented` is the inverse check; this one guards the direction that
    ;; actively misleads, so it is an assertion rather than a report.
    (doseq [[ns-sym docs] {'kb vocab/kb-docs
                           'wiki vocab/wiki-docs
                           'proposal vocab/proposal-docs}]
      (doseq [[sym entry] docs]
        (is (:doc entry) (str ns-sym "/" sym " has no :doc"))
        (is (:arglists entry) (str ns-sym "/" sym " has no :arglists"))
        (is (every? vector? (:arglists entry))
            (str ns-sym "/" sym " :arglists must be a seq of arg VECTORS"))))))

(deftest arglists-are-plausible
  (testing "kb verbs take the KB handle first — the sandbox resolves it through
            a conn-fn so an active proposal can redirect the write to a branch,
            and a verb that forgets it writes to the wrong place"
    (doseq [[sym entry] vocab/kb-docs
            :when (not= 'read-page sym)   ; reads the ambient KB, takes no handle
            args (:arglists entry)]
      (is (= 'db (first args))
          (str "kb/" sym " should take db first, got " (pr-str args))))))
