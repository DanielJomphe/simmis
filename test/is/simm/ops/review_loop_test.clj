(ns is.simm.ops.review-loop-test
  "Comments and request-changes: the third move a review needs.

   Before this a reviewer's only options were to accept a change as it stood or
   refuse it. For an agent-authored change both are wasteful — the agent can
   revise, and asking costs less than either — but there was nowhere to ask:
   the one free-form field is written at the moment of decision.

   What these pin is the part that is easy to get subtly wrong. A request for
   changes must reopen the contributor onto the SAME branch, must not move the
   recorded fork point, and must leave the proposal open. Each failure is quiet:
   a new branch strands the reviewed work, a re-recorded base makes the next
   diff hide everything done so far, and an accidental settle turns a request
   into a decision."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.store :as store]
            [is.simm.model.system-db :as sdb]
            [is.simm.ops.proposals :as props]
            [is.simm.runtimes.branching :as branching]
            [is.simm.runtimes.context :as ctx]))

(defn- fresh-kb []
  (let [scope (random-uuid)
        cfg {:store {:backend :memory :id scope}
             :keep-history? true :crypto-hash? true :commit-graph? true
             :schema-flexibility :write}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (store/install! conn)
      (branching/register-system! conn scope)
      [scope conn])))

(defn- fresh-system-db []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (doto (d/connect cfg) (d/transact sdb/schema))))

(defn- filed-proposal
  "An open proposal with one KB fork carrying one page. Returns
   `{:id :scope :branch :author}`."
  []
  (ctx/with-server-context
    (let [[scope _conn] (fresh-kb)
          author (random-uuid)
          base (branching/head-id scope :kb (branching/trunk-of scope :kb))
          {:keys [branch]} (branching/fork-branch! scope :kb "review")]
      (d/transact (branching/get-kb-conn-on-branch scope branch)
                  [{:S.Page/title "Draft page" :entity/uuid (random-uuid)}])
      {:id (props/file-proposal!
            {:title "Needs another pass" :room (random-uuid) :author author
             :forks [{:scope scope :branch branch :base-commit base
                      :system-type :kb :author author}]})
       :scope scope :branch branch :author author :base base})))

;; ---------------------------------------------------------------------------

(deftest a-comment-is-recorded-and-decides-nothing
  (with-redefs [sdb/get-conn (constantly (fresh-system-db))]
    (let [{:keys [id]} (filed-proposal)]
      (props/add-comment! id {:body "  Could you name it something clearer?  "})
      (let [p (props/get-proposal id)
            [c] (:proposal/comments p)]
        (is (= 1 (count (:proposal/comments p))))
        (is (= "Could you name it something clearer?" (:proposal.comment/body c))
            "trimmed, so leading whitespace from a textarea is not stored")
        (is (= :comment (:proposal.comment/kind c)))
        (is (some? (:proposal.comment/at c)))
        (is (= :open (:proposal/status p))
            "commenting is not a decision — the proposal stays open")))))

(deftest an-empty-comment-is-refused
  (with-redefs [sdb/get-conn (constantly (fresh-system-db))]
    (let [{:keys [id]} (filed-proposal)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (props/add-comment! id {:body "   "}))
          "a blank comment records nothing and would read as a reviewer who
           said something")
      (is (empty? (:proposal/comments (props/get-proposal id)))))))

(deftest a-comment-can-address-one-fork
  (with-redefs [sdb/get-conn (constantly (fresh-system-db))]
    (let [{:keys [id branch]} (filed-proposal)]
      (props/add-comment! id {:body "this fork only" :fork-branch branch})
      (props/add-comment! id {:body "the whole set"})
      (let [cs (:proposal/comments (props/get-proposal id))
            scoped (filter :proposal.comment/fork-branch cs)]
        (is (= 2 (count cs)))
        (is (= 1 (count scoped)))
        (is (= (name branch) (:proposal.comment/fork-branch (first scoped)))
            "addressed by branch NAME, so a dismissed fork's row surviving or
             not cannot change what the comment was about")))))

(deftest request-changes-reopens-the-same-branch-and-keeps-the-fork-point
  (with-redefs [sdb/get-conn (constantly (fresh-system-db))]
    (let [{:keys [id scope branch author base]} (filed-proposal)
          room (:proposal/room (props/get-proposal id))
          reopen! (requiring-resolve 'is.simm.agents.room-agents/reopen-fork!)
          cell ((requiring-resolve 'is.simm.agents.room-agents/private-overlay)
                room author)]
      ;; the agent is CLOSED after filing — the state request-changes must lift
      (reset! cell {:closed true :title "Needs another pass" :proposal (str id)})

      (ctx/with-server-context
        (let [{:keys [comment reopened]}
              (props/request-changes! id {:body "please rename the page"
                                          :fork-branch branch})]
          (is (some? comment))
          (is (= 1 (count reopened)) "the fork's author was put back to work")))

      (let [o @cell]
        (testing "the closed token is lifted"
          (is (nil? (:closed o))))
        (testing "onto the SAME branch — a new one would strand the reviewed work"
          (is (= branch (get-in o [:forks [scope author]]))))
        (testing "and the fork point is NOT moved"
          (is (= base (get-in o [:bases [scope author]]))
              "re-recording it as today's trunk head would make the next diff
               hide everything the fork has already done")))

      (testing "the proposal is still open and now carries the request"
        (let [p (props/get-proposal id)]
          (is (= :open (:proposal/status p)))
          (is (= :changes-requested
                 (:proposal.comment/kind (first (:proposal/comments p))))
              "recorded as a request, so a proposal that went round twice does
               not read afterwards as one accepted first time"))))))

(deftest request-changes-refuses-an-unknown-fork
  (with-redefs [sdb/get-conn (constantly (fresh-system-db))]
    (let [{:keys [id]} (filed-proposal)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ctx/with-server-context
                     (props/request-changes! id {:body "x" :fork-branch :no-such})))))))

(deftest request-changes-refuses-a-resolved-proposal
  (with-redefs [sdb/get-conn (constantly (fresh-system-db))]
    (let [{:keys [id]} (filed-proposal)]
      (ctx/with-server-context (props/dismiss-proposal! id))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ctx/with-server-context
                     (props/request-changes! id {:body "too late"})))
          "asking for changes to something already decided would leave the
           contributor writing toward a branch nobody will merge"))))
