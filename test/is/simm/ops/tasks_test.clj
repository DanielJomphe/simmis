(ns is.simm.ops.tasks-test
  "S/Task pages: the page-level source of the Tasks aggregate.

   The KB store is a real in-memory datahike store with the real seed, so this
   pins the thing most likely to break silently — that `ensure-task-schema!` is
   reached by the ONE installer path, and that a task page is findable BY TYPE
   rather than by scanning pages. When S/Task stopped being installed, the
   symptom would be an empty task list, which is indistinguishable from having
   no tasks.

   `branching/get-kb-conn` is redirected at this store: the aggregate's KB
   fan-out and `can?` filtering need a system DB and a registry, and those are
   covered by the access tests. What is exercised here is create → read →
   update on the actual code path."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.schema :as schema]
            [is.simm.model.seed :as seed]
            [is.simm.ops.tasks :as tasks]
            [is.simm.runtimes.branching :as branching]))

(defn- fresh-kb-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn schema/full-schema)
      ;; the same call the store installer makes — if S/Task ever falls out of
      ;; this path, this test fails instead of the UI quietly showing nothing
      (seed/ensure-seed-data! conn)
      conn)))

(def ^:private scope (random-uuid))

(defmacro ^:private with-kb [[conn-sym] & body]
  `(let [~conn-sym (fresh-kb-conn)]
     (with-redefs [branching/get-kb-conn (fn [~'_] ~conn-sym)]
       ~@body)))

(deftest task-type-is-installed-by-the-seed
  (with-kb [conn]
    (testing "S/Task exists as a category-S type"
      (is (some? (d/q '[:find ?e . :where [?e :entity/name "S/Task"]] @conn))))
    (testing "its properties are declared as datahike attributes"
      (doseq [a [:S.Task/status :S.Task/priority :S.Task/due
                 :S.Task/done-at :S.Task/assignee :S.Task/forkset]]
        (is (contains? (:schema @conn) a) (str a " must be declared"))))
    (testing "assignee and forkset are VALUE uuids, not refs into another db"
      (is (= :db.type/uuid (get-in (:schema @conn) [:S.Task/assignee :db/valueType])))
      (is (= :db.type/uuid (get-in (:schema @conn) [:S.Task/forkset :db/valueType]))))))

(deftest create-read-update
  (with-kb [conn]
    (let [assignee (random-uuid)
          due (java.util.Date. 1800000000000)
          id (tasks/create-task! scope {:title "Chase the gas certificate"
                                        :priority "high" :assignee assignee :due due})]
      (testing "a task page is BOTH an S/Page and an S/Task"
        (let [roles (->> (d/pull @conn '[{:instance/of-role [:entity/name]}]
                                 [:entity/uuid id])
                         :instance/of-role (map :entity/name) set)]
          (is (= #{"S/Page" "S/Task"} roles)
              "being a page is the point — blocks, links and the renderer come free")))

      (testing "it is found by TYPE and adapted to the aggregate shape"
        (let [[t & more] (tasks/kb-tasks scope "Ops Playbook")]
          (is (nil? more) "exactly one task")
          (is (= :page (:source t)))
          (is (= "Chase the gas certificate" (:title t)))
          (is (= "open" (:status t)) "no status supplied ⇒ open")
          (is (= "high" (:priority t)))
          (is (= assignee (:assignee t)))
          (is (= due (:due t)))
          (is (= id (:page t)))
          (is (= "Ops Playbook" (:kb-name t)))))

      (testing "done stamps :S.Task/done-at"
        (tasks/update-task! scope id {:status "done"})
        (let [p (d/pull @conn '[:S.Task/status :S.Task/done-at] [:entity/uuid id])]
          (is (= "done" (:S.Task/status p)))
          (is (some? (:S.Task/done-at p)))))

      (testing "reopening RETRACTS it — a reopened task must not read as finished"
        (tasks/update-task! scope id {:status "open"})
        (let [p (d/pull @conn '[:S.Task/status :S.Task/done-at] [:entity/uuid id])]
          (is (= "open" (:S.Task/status p)))
          (is (nil? (:S.Task/done-at p)))))

      (testing "only the keys supplied are written"
        (tasks/update-task! scope id {:priority "low"})
        (let [t (first (tasks/kb-tasks scope "Ops Playbook"))]
          (is (= "low" (:priority t)))
          (is (= assignee (:assignee t)) "assignee untouched")
          (is (= "open" (:status t)) "status untouched"))))))

(deftest archived-tasks-are-not-tasks
  (with-kb [conn]
    (let [id (tasks/create-task! scope {:title "Old one"})]
      (d/transact conn [{:db/id [:entity/uuid id] :S.Page/archived true}])
      (is (empty? (tasks/kb-tasks scope "KB"))
          "archiving a page removes it from the wiki; it must leave the task list too"))))

(deftest missing-page-is-loud
  (with-kb [_conn]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tasks/update-task! scope (random-uuid) {:status "done"}))
        "updating a page that isn't there must throw, not no-op silently")))

(deftest status-vocabulary
  (testing "blank and nil normalise to the default"
    (is (= "open" (tasks/normalize-status nil)))
    (is (= "open" (tasks/normalize-status "  "))))
  (testing "case and whitespace are normalised"
    (is (= "in-progress" (tasks/normalize-status " In-Progress "))))
  (testing "an unknown status is KEPT, not coerced"
    (is (= "waiting-on-tenant" (tasks/normalize-status "waiting-on-tenant"))
        "an agent that said something specific must not have it rewritten"))
  (testing "only done is closed — blocked is still a task you have"
    (is (false? (tasks/open? "done")))
    (is (true? (tasks/open? "blocked")))
    (is (true? (tasks/open? "waiting-on-tenant")))
    (is (true? (tasks/open? nil)))))
