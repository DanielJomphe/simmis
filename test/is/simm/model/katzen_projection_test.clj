(ns is.simm.model.katzen-projection-test
  "katzen → simmis-S projection. A katzen schema is the source of truth; it
   projects into category S as Object/Morphism blocks. One-way only: S is a
   generated view, never read back into a katzen schema."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.schema :as schema]
            [is.simm.model.katzen-projection :as kp]
            [is.simm.model.store :as store]
            [katzen.schema.knowledge :as kbschema]))

(def ^:private minimal-seed
  "Enough of category S for the projection: S itself, S/EntityType (referenced by
   create-type!), and the primitive value types the attr-types map onto."
  (into [{:entity/uuid (random-uuid) :entity/name "S"}
         {:entity/uuid (random-uuid) :entity/name "S/EntityType"
          :object/of-category [:entity/name "S"]}]
        (for [p ["S/String" "S/Number" "S/Float" "S/Boolean" "S/Date" "S/UUID"]]
          {:entity/uuid (random-uuid) :entity/name p
           :object/of-category [:entity/name "S"] :object/primitive? true})))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn schema/full-schema)
      (d/transact conn minimal-seed)
      conn)))

(defn- morphism [db nm]
  (d/q '[:find (pull ?m [:entity/name :morphism/cardinality :morphism/property-type
                         :morphism/attr-type :morphism/unique :morphism/storage-attr
                         {:morphism/src [:entity/name]} {:morphism/dst [:entity/name]}]) .
         :in $ ?n :where [?m :entity/name ?n]] db nm))

(deftest project-katzen-schema-as-simmis-types
  (let [conn (fresh-conn)]
    (kp/project-schema! conn kbschema/schema)
    (let [db @conn]
      (testing "each katzen object becomes a type S/<Object>"
        (is (some? (d/q '[:find ?e . :where [?e :entity/name "S/Entity"]] db))))
      (testing "the identity attr title → typed property, faithful attr-type + uniqueness"
        (let [m (morphism db "S/Entity/title")]
          (is (= "S/Entity" (-> m :morphism/src :entity/name)))
          (is (= "S/String"  (-> m :morphism/dst :entity/name)))
          (is (= :one (:morphism/cardinality m)))
          (is (= :Identity (:morphism/attr-type m)) "exact katzen attr-type preserved")
          (is (= :db.unique/value (:morphism/unique m)) "title uniqueness preserved")))
      (testing "uniqueness is propagated to the generated storage attribute"
        (is (= :db.unique/value (get-in (:schema db) [:S.Entity/title :db/unique]))
            "S.Entity/title is an actual key in S, not a plain string column"))
      (testing "a Keyword attr keeps its exact attr-type (collapsed dst, faithful type)"
        (let [m (morphism db "S/Entity/kind")]
          (is (= "S/String" (-> m :morphism/dst :entity/name)))
          (is (= :Keyword (:morphism/attr-type m)))))
      (testing "a cardinality-many hom → a native :many :relation property"
        (let [m (morphism db "S/Entity/links")]
          (is (= "S/Entity" (-> m :morphism/dst :entity/name)) "links is Entity→Entity")
          (is (= :many (:morphism/cardinality m)) "native many, not a junction")
          (is (= :relation (:morphism/property-type m))))))
    (testing "idempotent — a second projection creates nothing new (boot-safe)"
      (let [n-before (count (d/q '[:find ?m :where [?m :morphism/of-category _]] @conn))]
        (kp/project-schema! conn kbschema/schema)
        (is (= n-before (count (d/q '[:find ?m :where [?m :morphism/of-category _]] @conn)))
            "no duplicate morphisms on re-projection")))))

(deftest storage-attribute-keeps-the-exact-attr-type
  (testing "the collapsed PRIMITIVE must not decide the datahike valueType"
    ;; `:Identity`, `:String` and `:Keyword` all collapse onto the S/String
    ;; primitive. Deriving storage from that primitive declared katzen's
    ;; `kind : Entity -> Keyword` as `:db.type/string`, which contradicted the
    ;; `:db.type/keyword` that `full-schema` already declares for
    ;; `:S.Page/kind`. datahike refused the alter, `project-schema!` threw
    ;; mid-`doseq`, and every attr AFTER `kind` silently never projected.
    (let [conn (fresh-conn)]
      (kp/project-schema! conn kbschema/schema)
      (let [sch (:schema @conn)]
        (is (= :db.type/keyword (get-in sch [:S.Entity/kind :db/valueType]))
            "a Keyword attr is stored as a keyword, not a string")
        (is (= :db.type/string (get-in sch [:S.Entity/title :db/valueType]))
            "an Identity attr is still a string")
        (is (= :db.type/instant (get-in sch [:S.Entity/created-at :db/valueType]))
            "an Instant attr is still an instant")))))

(deftest storage-attrs-pins-individual-morphisms
  (testing ":storage-attrs maps ONE morphism onto an existing attribute"
    (let [conn (fresh-conn)]
      (kp/project-schema! conn kbschema/schema
                          {:storage-attrs {:Entity {:created-at :entity/created-at}}})
      (is (= :entity/created-at
             (:morphism/storage-attr (morphism @conn "S/Entity/created-at")))
          "created-at reuses the universal entity timestamp")
      (is (not (contains? (:schema @conn) :S.Entity/created-at))
          "and mints no parallel attribute for the same fact")
      (is (nil? (:morphism/storage-attr (morphism @conn "S/Entity/title")))
          "its siblings are unaffected"))))

(deftest the-kb-binding-projects-every-katzen-attr
  (testing "store/kb-binding-opts — the binding every store applies"
    (let [conn (fresh-conn)]
      ;; S/Page is what :identify maps Entity onto, so it must exist first.
      (d/transact conn [{:entity/uuid (random-uuid) :entity/name "S/Page"
                         :object/of-category [:entity/name "S"]
                         :instance/of-role [:entity/name "S/EntityType"]}])
      (kp/project-schema! conn kbschema/schema store/kb-binding-opts)
      (let [db @conn]
        (testing "no attr is dropped — the whole doseq runs to completion"
          (doseq [{:keys [name]} (:attrs kbschema/schema)]
            (is (some? (morphism db (str "S/Page/" (clojure.core/name name))))
                (str "S/Page/" (clojure.core/name name) " projected"))))
        (testing "no S/Entity twin beside S/Page"
          (is (nil? (morphism db "S/Entity/title"))))
        (testing "timestamps reuse :entity/*, they do not duplicate it"
          (is (not (contains? (:schema db) :S.Page/created-at)))
          (is (not (contains? (:schema db) :S.Page/updated-at))))
        (testing "kind agrees with the declaration full-schema already carries"
          (is (= :db.type/keyword (get-in (:schema db) [:S.Page/kind :db/valueType]))))))))
