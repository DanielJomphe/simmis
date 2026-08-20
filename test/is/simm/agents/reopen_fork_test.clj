(ns is.simm.agents.reopen-fork-test
  "`reopen-fork!` must not reopen into an UNRELATED proposal.

   Requesting changes on a filed proposal puts the author back to writing on
   the fork branch it already filed. It does that by rewriting the author's
   private overlay cell — and that cell is a single slot per (room, agent).

   Three states can be in it. `nil` (idle) and `:closed` (just filed) both
   start clean. The third — the agent has SINCE opened a proposal of its own —
   used to fall through to `(or o {})`: the old proposal's branch was merged
   into the new one's `:forks`, and `(or title (:title o) …)` put the OLD
   title first, renaming work in progress. The agent's next `file!` then filed
   that borrowed branch a second time, into a second proposal.

   The same-proposal reopen must keep working: a reviewer may request changes
   on several forks of one proposal, and the second call finds the overlay the
   first one created."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.agents.room-agents :as ra]
            [is.simm.runtimes.branching :as branching]))

(def ^:private room #uuid "aaaaaaaa-0000-0000-0000-000000000001")
(def ^:private agent-id #uuid "bbbbbbbb-0000-0000-0000-000000000002")

(defn- cell []
  (#'ra/private-overlay room agent-id))

(defn- reopen! [scope branch title]
  ;; The base seed reads a live branch head; the decision under test happens
  ;; before that, so a stub keeps the test pure.
  (with-redefs [branching/head-id (fn [& _] :head)
                branching/trunk-of (fn [& _] :trunk)]
    (ra/reopen-fork! room agent-id scope branch :kb title)))

(defn- reset-cell! [v]
  (swap! @#'ra/proposal-overlays dissoc [room agent-id])
  (reset! (cell) v))

;; =============================================================================

(deftest reopens-into-an-idle-overlay
  (testing "nothing in progress — the ordinary path"
    (reset-cell! nil)
    (is (true? (reopen! :scope-a :fork-1 "the filed one")))
    (let [o @(cell)]
      (is (= "the filed one" (:title o)))
      (is (= :fork-1 (get-in o [:forks [:scope-a agent-id]])))
      (is (nil? (:closed o)) "reopened, not still filed"))))

(deftest reopens-into-a-just-filed-overlay
  (testing "the state request-changes normally finds: filed, closed"
    (reset-cell! {:closed true :proposal #uuid "cccccccc-0000-0000-0000-000000000003"
                  :title "the filed one"
                  :forks {[:scope-a agent-id] :fork-1}})
    (is (true? (reopen! :scope-a :fork-1 "the filed one")))
    (let [o @(cell)]
      (is (nil? (:closed o)))
      (is (nil? (:proposal o)) "no longer pointing at the filed proposal")
      (is (= :fork-1 (get-in o [:forks [:scope-a agent-id]]))))))

(deftest a-second-fork-of-the-SAME-proposal-still-reopens
  (testing "a reviewer requests changes on two forks of one proposal"
    (reset-cell! {:closed true :title "the filed one"
                  :forks {[:scope-a agent-id] :fork-1
                          [:scope-b agent-id] :fork-2}})
    (is (true? (reopen! :scope-a :fork-1 "the filed one")))
    ;; Second call finds the overlay the first one left OPEN — same title.
    (is (true? (reopen! :scope-b :fork-2 "the filed one"))
        "the guard must not mistake the first reopen for a rival proposal")
    (let [o @(cell)]
      (is (= :fork-1 (get-in o [:forks [:scope-a agent-id]])))
      (is (= :fork-2 (get-in o [:forks [:scope-b agent-id]]))
          "both forks reopened into the one overlay"))))

(deftest refuses-to-reopen-into-an-UNRELATED-open-proposal
  (let [in-progress {:title "the new thing I am working on"
                     :forks {[:scope-z agent-id] :fork-new}
                     :types {[:scope-z agent-id] :kb}
                     :bases {[:scope-z agent-id] :base-new}}]
    (reset-cell! in-progress)
    (testing "the call reports failure rather than contaminating"
      (is (false? (reopen! :scope-a :fork-old "the OLD filed one"))))
    (testing "and leaves the agent's work in progress exactly as it was"
      (let [o @(cell)]
        (is (= "the new thing I am working on" (:title o))
            "not renamed to the old proposal's title")
        (is (nil? (get-in o [:forks [:scope-a agent-id]]))
            "the old proposal's branch was not merged in")
        (is (= in-progress o) "untouched")))))

(deftest a-refused-reopen-does-not-leak-into-the-next-filing
  (testing "the branch the agent would file next is still only its own"
    (reset-cell! {:title "mine" :forks {[:scope-z agent-id] :fork-new}})
    (reopen! :scope-a :fork-old "someone else's")
    (is (= {[:scope-z agent-id] :fork-new} (:forks @(cell)))
        "one fork — filing this overlay cannot re-file the old branch")))
