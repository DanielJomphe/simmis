(ns is.simm.model.seed-bootstrap-test
  "The two pages every store is installed with are marked as INFRASTRUCTURE.

   `SKILL` and `Getting Started` are part of what a simmis store is — the same
   status as its schema — and nobody in the workspace wrote them. Without a
   marker they are indistinguishable from real work in the Timelines audit
   panel, which reported 21 blocks of change in a wiki created that afternoon:
   the view accounting for the act of provisioning rather than for the work.

   Marked in the DATA rather than filtered by title in the view, so this test
   pins the attribute and not a string."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.schema :as schema]
            [is.simm.model.seed :as seed]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn schema/full-schema)
      conn)))

(defn- kind-of [db page-uuid]
  (d/q '[:find ?k . :in $ ?u
         :where [?e :entity/uuid ?u] [?e :S.Page/kind ?k]]
       db page-uuid))

(deftest the-seed-marks-its-own-pages
  (let [conn (fresh-conn)]
    (seed/ensure-seed-data! conn)
    (testing "both bootstrap pages carry the marker"
      (doseq [u seed/bootstrap-page-uuids]
        (is (= :bootstrap (kind-of @conn u))
            (str "page " u " must be marked as bootstrap"))))
    (testing "the marker rides the page's OWN creation transaction — no repair
              write is needed on a store seeded by this version"
      (let [before (:max-tx @conn)]
        (seed/mark-bootstrap-pages! conn)
        (is (= before (:max-tx @conn)))))))

(deftest an-older-store-is-backfilled-once
  (let [conn (fresh-conn)]
    (seed/ensure-seed-data! conn)
    ;; What a store seeded before the marker existed looks like.
    (doseq [u seed/bootstrap-page-uuids]
      (let [e (d/q '[:find ?e . :in $ ?u :where [?e :entity/uuid ?u]] @conn u)]
        (d/transact conn [[:db/retract e :S.Page/kind :bootstrap]])))
    (is (every? nil? (map #(kind-of @conn %) seed/bootstrap-page-uuids))
        "precondition: the marker is gone")

    (seed/mark-bootstrap-pages! conn)
    (testing "the repair reaches a store that predates the marker"
      (doseq [u seed/bootstrap-page-uuids]
        (is (= :bootstrap (kind-of @conn u)))))

    (testing "and does not write again on the next boot — an unconditional
              upsert would mint a transaction in every store at every start"
      (let [before (:max-tx @conn)]
        (seed/mark-bootstrap-pages! conn)
        (is (= before (:max-tx @conn)))))))

(deftest the-pages-are-marked-not-hidden
  (testing "`:S.Page/archived` is untouched — nav, search and [[Getting Started]]
            links still see them; only the audit panel excludes them"
    (let [conn (fresh-conn)]
      (seed/ensure-seed-data! conn)
      (doseq [u seed/bootstrap-page-uuids]
        (is (false? (d/q '[:find ?a . :in $ ?u
                           :where [?e :entity/uuid ?u] [?e :S.Page/archived ?a]]
                         @conn u)))))))
