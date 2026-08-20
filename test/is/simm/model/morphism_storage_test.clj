(ns is.simm.model.morphism-storage-test
  "Where a morphism's values live — one derivation, checked against a REAL store.

   A morphism either stores under an attribute derived from its name
   (`S/Page/summary` → `:S.Page/summary`) or under `:morphism/storage-attr`, a
   reflected view onto a domain system's own attribute (`S/Page/created-at` →
   `:entity/created-at`, `S/Posting/amount` → `:kontor.posting/amount`).

   Reflected is the MAJORITY: on a `store/install!`ed store most morphisms
   carry one, including `created-at` and `updated-at` on `S/Page`, which every
   wiki page has. Three of the five copies of this derivation did not know
   that, so reflected properties rendered blank and refused to save.

   THE FIXTURE IS THE POINT. `crud_test` builds its store from
   `schema/full-schema` + `ensure-seed-data!` and stops — that store contains
   ZERO reflected morphisms, so a test written on it passes against a
   storage-blind implementation and proves nothing. This namespace uses
   `store/install!`, which is the only thing that produces the real mix, and
   the central test DERIVES its expectations from the store rather than pinning
   a literal — so it cannot pass by pinning a state the system never builds."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.store :as store]
            [is.simm.model.morphism :as mor]
            [is.simm.model.crud :as crud]))

(defn- installed-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (store/install! conn)
      conn)))

(defn- all-morphisms [db]
  (d/q '[:find [(pull ?m [:entity/uuid :entity/name :morphism/storage-attr]) ...]
         :where [?m :entity/name ?n] [?m :morphism/src _]]
       db))

;; =============================================================================
;; The property that would have caught the regression the day it landed
;; =============================================================================

(deftest every-morphism-resolves-to-a-declared-attribute
  (testing "for EVERY morphism the store actually contains, the attribute we
            derive exists in the schema"
    ;; Storage-blind derivation computes `:S.Page/created-at` for a morphism
    ;; whose values live at `:entity/created-at`. That attribute is not
    ;; declared, so a read finds nothing and a write throws :transact/schema
    ;; (every store is :schema-flexibility :write). Deriving the expectation
    ;; from the store is what makes this un-fakeable.
    (let [conn (installed-conn)
          db @conn
          schema (d/schema db)
          ms (all-morphisms db)
          undeclared (->> ms
                          (remove #(contains? schema (mor/attr-of %)))
                          (mapv (juxt :entity/name #(str (mor/attr-of %)))))]
      (is (seq ms) "the fixture produced morphisms at all")
      (is (empty? undeclared)
          (str "morphisms whose derived attribute is not declared: "
               (pr-str (take 10 undeclared)))))))

(deftest the-fixture-really-contains-reflected-morphisms
  (testing "otherwise every assertion here is vacuous"
    ;; Guards against the crud_test trap: a fixture with no reflected
    ;; morphisms makes a storage-blind implementation look correct.
    (let [ms (all-morphisms @(installed-conn))
          reflected (filter mor/reflected? ms)]
      (is (seq reflected) "store/install! must produce reflected morphisms")
      (is (some #(= "S/Page/created-at" (:entity/name %)) reflected)
          "S/Page/created-at is reflected onto :entity/created-at"))))

;; =============================================================================
;; The derivation itself
;; =============================================================================

(deftest storage-attr-wins-over-the-name
  (is (= :entity/created-at
         (mor/attr-of {:entity/name "S/Page/created-at"
                       :morphism/storage-attr :entity/created-at})))
  (is (= :S.Page/summary (mor/attr-of {:entity/name "S/Page/summary"})))
  (testing "the name derivation keeps the namespace at every arity"
    ;; A `replace-first`-based variant lived in three places and yields an
    ;; UNNAMESPACED keyword for two segments.
    (is (= :S.Page/title (mor/name->attr-ident "S/Page/title")))
    (is (= :S/title (mor/name->attr-ident "S/title")))
    (is (some? (namespace (mor/name->attr-ident "S/title"))))))

;; =============================================================================
;; Deleting a reflected morphism drops the VIEW, never the owner's data
;; =============================================================================

(deftest deleting-a-reflected-morphism-leaves-the-data
  (testing "S/Page/created-at is dropped; :entity/created-at survives"
    ;; Deliberately NOT :kontor.posting/amount — the starter book has no
    ;; postings, so a broken safeguard would retract nothing and pass green.
    ;; Every entity in the store carries :entity/created-at, so a regression
    ;; here destroys hundreds of datoms and fails loudly.
    (let [conn (installed-conn)
          m (d/q '[:find (pull ?m [:entity/uuid :morphism/storage-attr]) .
                   :where [?m :entity/name "S/Page/created-at"]] @conn)
          ;; EXCLUDING the morphism entity itself, which carries its own
          ;; :entity/created-at and legitimately loses it when retracted. The
          ;; question is whether OTHER entities keep theirs.
          others (fn [] (->> (d/q '[:find [?u ...] :where
                                    [?e :entity/created-at _] [?e :entity/uuid ?u]] @conn)
                             (remove #{(:entity/uuid m)})
                             count))
          before (others)]
      (is (= :entity/created-at (:morphism/storage-attr m)) "fixture sanity")
      (is (pos? before) "there are values to lose")
      (let [r (crud/delete-morphism! conn (:entity/uuid m))
            after (others)]
        (is (:success r))
        (is (true? (:reflected? r)))
        (is (zero? (:affected-entities r)) "reports that it touched no values")
        (is (= before after) "the owning system's data is untouched")
        (is (nil? (d/q '[:find ?m . :where [?m :entity/name "S/Page/created-at"]] @conn))
            "but the categorical view is gone")))))

(deftest deleting-a-plain-morphism-does-retract-its-values
  (testing "the non-reflected path still removes data, and says how much"
    (let [conn (installed-conn)
          page (random-uuid)]
      (d/transact conn [{:entity/uuid page :S.Page/summary "hello"}])
      (let [m (d/q '[:find (pull ?m [:entity/uuid]) .
                     :where [?m :entity/name "S/Page/summary"]] @conn)
            r (crud/delete-morphism! conn (:entity/uuid m))]
        (is (false? (:reflected? r)))
        (is (pos? (:affected-entities r)) "it counted what it removed")
        (is (empty? (d/q '[:find ?e :where [?e :S.Page/summary _]] @conn)))))))

(deftest a-stale-morphism-uuid-is-refused-not-thrown-past
  (testing "a lookup ref for an absent entity throws :entity-id/missing"
    (let [conn (installed-conn)
          e (try (crud/delete-morphism! conn (random-uuid)) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :no-such-morphism (:type (ex-data e)))))))
