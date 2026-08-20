(ns is.simm.model.store-narrative-time-test
  "A store installed at the beginning of its NARRATIVE time, and a page query
   that answers rather than throws when it is not.

   The failure this pins down (measured 2026-07-27): seeded content is
   back-dated via `:tx-meta {:db/txInstant …}` while the store's own schema and
   seed are stamped when it is provisioned, so a cut between the two sits in a
   database that has pages but no vocabulary. There a lookup ref to a seed
   entity — `[?e :instance/of-role [:entity/name \"S/Page\"]]` — throws `Nothing
   found for entity id`, because a missing lookup ref is an ERROR in datahike,
   not an empty match. The Timelines rail makes exactly that cut reachable with
   one click.

   Two independent fixes, tested independently: install the store at narrative
   time (`store/install!`'s `at`), and resolve the role as a VALUE before using
   it (`datahike-query/page-role-eid`) so a store nobody back-dated degrades to
   an empty wiki instead of an exception."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.store :as store]
            [is.simm.uis.web.desktop.datahike-query :as dq]))

(defn- inst [s] (java.util.Date/from (java.time.Instant/parse s)))

;; The scenario's shape: an incident fixture back-dated to 03:04, read from a
;; reference standing at 09:00 the same morning.
(def ^:private t0      (inst "2026-01-05T08:00:00Z"))  ; narrative start
(def ^:private t-page  (inst "2026-03-04T03:04:00Z"))  ; the incident page
(def ^:private cut-pre (inst "2026-01-01T00:00:00Z"))  ; before anything at all
(def ^:private cut-mid (inst "2026-02-01T00:00:00Z"))  ; installed, no content yet
(def ^:private cut-09  (inst "2026-03-04T09:00:00Z"))  ; the demo's beat

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- add-incident-page! [conn]
  (d/transact conn
              {:tx-data [{:entity/uuid #uuid "00000000-0000-0000-0000-0000000f1e1d"
                          :entity/name "Incident 03:04"
                          :instance/of-role [:entity/name "S/Page"]
                          :S.Page/title "Incident 03:04"
                          :S.Page/archived false}]
               :tx-meta {:db/txInstant t-page}}))

(defn- pages
  "The client's page query, degrade-safe form."
  [db]
  (if-let [role (d/q dq/page-role-query db)]
    (d/q dq/pages-by-role-query db role)
    []))

(defn- naive-pages
  "The form that throws. Kept so the test fails if datahike ever starts
   tolerating an unresolvable lookup ref and the guard becomes cargo."
  [db]
  (d/q '[:find [(pull ?e [:entity/uuid :S.Page/title]) ...]
         :where [?e :instance/of-role [:entity/name "S/Page"]]]
       db))

(defn- titles [rows] (set (map :S.Page/title rows)))

(deftest install-at-narrative-time
  (let [conn (fresh-conn)]
    (store/install! conn t0)
    (add-incident-page! conn)

    (testing "the seed's own role entity carries the narrative instant, not wall clock"
      (let [tx (d/q '[:find ?tx . :where [?r :entity/name "S/Page" ?tx]] @conn)]
        (is (= t0 (d/q '[:find ?i . :in $ ?tx :where [?tx :db/txInstant ?i]] @conn tx)))))

    (testing "the demo beat: a cut after the incident sees it, and does not throw"
      (let [db (d/as-of @conn cut-09)]
        (is (contains? (titles (pages db)) "Incident 03:04"))
        ;; the whole point of installing at t0 — even the unguarded form works
        (is (contains? (titles (naive-pages db)) "Incident 03:04"))))

    (testing "installed but empty: pages the install itself seeded, no incident"
      (let [db (d/as-of @conn cut-mid)]
        (is (not (contains? (titles (pages db)) "Incident 03:04")))))

    (testing "before the store existed: empty, never an exception"
      (let [db (d/as-of @conn cut-pre)]
        (is (nil? (d/q dq/page-role-query db)))
        (is (= [] (pages db)))))))

(deftest wall-clock-install-degrades-instead-of-throwing
  ;; The case the install fix cannot reach: a store somebody else made, whose
  ;; history predates its schema. Here the 09:00 cut IS the pre-install cut.
  (let [conn (fresh-conn)]
    (store/install! conn)                                   ; wall clock, as today
    (add-incident-page! conn)
    (let [db (d/as-of @conn cut-09)]
      (testing "the store's vocabulary does not exist at this cut"
        (is (nil? (d/q dq/page-role-query db))))
      (testing "the lookup-ref query throws — this is the bug, still present"
        (is (thrown-with-msg? Exception #"Nothing found for entity id"
                              (naive-pages db))))
      (testing "the guarded query reports an empty wiki instead"
        (is (= [] (pages db)))))))
