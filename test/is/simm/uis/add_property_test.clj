(ns is.simm.uis.add-property-test
  "The server, not the browser, decides a property's `:db/valueType`.

   `-add-property-handler` declares a `:db/ident`. Datahike schema is
   APPEND-ONLY: once an attribute has a value type, nothing takes it back. The
   handler used to transact the `:value-type` the client sent, verbatim — so a
   browser could permanently fix the type of any attribute it could name, in
   any store its writer could reach, and the docstring's claim that \"the
   server decides … the value type, because those are schema\" was not true of
   the code under it.

   It is also the one table that has to agree with `codomain->db-type`, which
   every other reader of these attributes goes through. The client kept a
   private copy, and the two disagreed about numbers."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [<!!]]
            [datahike.api :as d]
            [is.simm.model.schema :as schema]
            [is.simm.model.seed :as seed]
            [is.simm.uis.web.desktop.block-remote :as br]))

(def ^:private page-uuid #uuid "dddddddd-0000-0000-0000-000000000001")

(defn- fresh-store []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn schema/full-schema)
      ;; the primitive objects a property can point at
      (d/transact conn [seed/category-S])
      (d/transact conn [seed/object-String seed/object-Number seed/object-Float
                        seed/object-Boolean seed/object-Date])
      ;; the type page the properties are declared ON — `:morphism/src` is a ref
      (d/transact conn [{:entity/uuid page-uuid :entity/name "S/Widget"}])
      conn)))

(defn- add-property!
  "`go-try` hands a failure back as a channel VALUE, so a test that only takes
   from the channel sees `nil` where the handler threw. Re-throw it."
  [conn args]
  (with-redefs [br/resolve-conn (fn [& _] conn)]
    (let [r (<!! (br/-add-property-handler args))]
      (if (instance? Throwable r) (throw r) r))))

(defn- value-type-of [conn attr]
  (:db/valueType (d/pull @conn [:db/valueType] [:db/ident attr])))


;; =============================================================================

(deftest a-client-supplied-value-type-is-ignored
  (let [conn (fresh-store)]
    (testing "a text property is :db.type/string however the client asks"
      ;; `:value-type` is not even in the handler's destructuring any more; if
      ;; it is ever put back, this is what catches it being trusted.
      (add-property! conn {:type-page-uuid page-uuid
                           :morphism-name "S/Widget/label"
                           :value-type :db.type/ref      ;; <- the lie
                           :property-type :text
                           :target-object-name "S/String"})
      (is (= :db.type/string (value-type-of conn :S.Widget/label))
          "derived from the target object, not from the wire"))))

(deftest the-derivation-is-the-canonical-table
  (let [conn (fresh-store)]
    (testing "every primitive goes through codomain->db-type"
      (doseq [[object attr-name attr] [["S/String"  "S/Widget/a" :S.Widget/a]
                                       ["S/Number"  "S/Widget/b" :S.Widget/b]
                                       ["S/Float"   "S/Widget/c" :S.Widget/c]
                                       ["S/Boolean" "S/Widget/d" :S.Widget/d]
                                       ["S/Date"    "S/Widget/e" :S.Widget/e]]]
        (add-property! conn {:type-page-uuid page-uuid
                             :morphism-name attr-name
                             :target-object-name object})
        (is (= (schema/codomain->db-type object) (value-type-of conn attr))
            (str object " matches the canonical mapping"))))))

(deftest a-relation-is-a-ref
  (let [conn (fresh-store)
        target #uuid "dddddddd-0000-0000-0000-000000000002"]
    (d/transact conn [{:entity/uuid target :entity/name "S/Other"}])
    (add-property! conn {:type-page-uuid page-uuid
                         :morphism-name "S/Widget/other"
                         :value-type :db.type/string     ;; <- the lie
                         :target-type-uuid target})
    (is (= :db.type/ref (value-type-of conn :S.Widget/other))
        "a relation target forces :db.type/ref regardless of what was sent")))

(deftest an-unknown-primitive-is-refused-rather-than-defaulted
  (let [conn (fresh-store)]
    (is (thrown? Exception
                 (add-property! conn {:type-page-uuid page-uuid
                                      :morphism-name "S/Widget/f"
                                      :target-object-name "S/NoSuchThing"}))
        "an object this store does not have is not silently a string")))
