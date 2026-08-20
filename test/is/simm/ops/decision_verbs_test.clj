(ns is.simm.ops.decision-verbs-test
  "A resolved proposal cannot be re-decided.

   `dismiss-proposal!` was the ONE decision verb that did not require the
   proposal to be OPEN. `accept-proposal!`, `dismiss-fork!` and `accept-fork!`
   all go through `open-proposal!`; this one used `get-proposal` and only
   checked existence. So dismissing an ALREADY-ACCEPTED proposal succeeded: it
   flipped `:proposal/status` to `:dismissed` and overwrote
   `:proposal/resolved-at`, rewriting the record of a merge that had already
   landed on trunk and could not be taken back."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.system-db :as sdb]
            [is.simm.ops.proposals :as props]))

(defn- fresh-system-db []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (doto (d/connect cfg) (d/transact sdb/schema))))

(defn- proposal-row!
  "A proposal in `status`, with no forks — enough to exercise the guards."
  [conn status]
  (let [id (random-uuid)]
    (d/transact conn [{:proposal/id id
                       :proposal/title "already decided"
                       :proposal/status status
                       :proposal/created-at (java.util.Date.)}])
    id))

(deftest dismiss-refuses-a-resolved-proposal
  (let [conn (fresh-system-db)]
    (with-redefs [sdb/get-conn (fn [] conn)]
      (testing "an ACCEPTED proposal cannot be dismissed"
        (let [id (proposal-row! conn :accepted)
              e (try (props/dismiss-proposal! id :note "changed my mind") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "must refuse")
          (is (= :accepted
                 (:proposal/status
                  (d/q '[:find (pull ?p [:proposal/status]) . :in $ ?id
                         :where [?p :proposal/id ?id]] @conn id)))
              "and must not have rewritten the record of the merge")))

      (testing "an already-DISMISSED proposal cannot be dismissed again"
        (let [id (proposal-row! conn :dismissed)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (props/dismiss-proposal! id :note "again")))))

      (testing "an OPEN one still can — the guard is not blanket"
        (let [id (proposal-row! conn :open)]
          (is (some? (props/dismiss-proposal! id :note "no thanks")))
          (is (= :dismissed
                 (:proposal/status
                  (d/q '[:find (pull ?p [:proposal/status]) . :in $ ?id
                         :where [?p :proposal/id ?id]] @conn id)))))))))

(deftest unknown-proposal-still-refused
  (let [conn (fresh-system-db)]
    (with-redefs [sdb/get-conn (fn [] conn)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (props/dismiss-proposal! (random-uuid)))))))
