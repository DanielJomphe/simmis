(ns is.simm.model.type-tables-test
  "The one coherence condition worth stating about the schema projection.

   Projecting a katzen schema into category S goes through two maps that must
   agree:

     attr-type ──attr-type->primitive──▶ S primitive ──codomain->db-type──▶ :db.type/*
         │                                                                      ▲
         └──────────────────── attr-type->db-type ──────────────────────────────┘

   The direct edge exists because the primitive collapses `:Identity`,
   `:String` and `:Keyword` onto `S/String`, which is fine for the categorical
   view and wrong for storage — `kind : Entity -> Keyword` was declared
   `:db.type/string`, datahike refused to alter it, and `project-schema!` threw
   mid-`doseq` so every attr after `kind` silently never projected, for months.

   So the two edges are allowed to disagree exactly where that collapse is
   deliberate, and nowhere else. Nothing enforced that until this file. This is
   a TEST rather than an abstraction on purpose: the coherence is real and
   checkable, and a shared layer over two callers would be a layer over two
   callers."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.model.schema :as schema]
            [is.simm.model.katzen-projection :as kp]))

(def ^:private collapse-exceptions
  "attr-types whose direct storage type deliberately differs from the one
   reached through the S primitive. Each must be justified here."
  {:Keyword "collapses onto S/String for the view, stored as a keyword"
   :Symbol  "same collapse; stored as a symbol"
   :BigDec  "collapses onto S/Number for the view, stored as bigdec (money)"
   :BigInt  "same collapse; stored as bigint"
   :Ref     "view shows an identifier (S/String); storage stays a ref"})

(deftest the-triangle-commutes-except-where-documented
  (let [shared (filter #(contains? kp/attr-type->primitive %)
                       (keys schema/attr-type->db-type))
        bad (for [t shared
                  :let [direct (schema/attr-type->db-type t)
                        ;; the default `project-schema!` itself applies
                        via    (schema/codomain->db-type
                                (get kp/attr-type->primitive t "S/String"))]
                  :when (and (not= direct via)
                             (not (contains? collapse-exceptions t)))]
              [t {:direct direct :via-primitive via}])]
    (is (seq shared) "the two tables share keys at all")
    (is (empty? bad)
        (str "attr-types where storage disagrees with the primitive and no "
             "exception is recorded: " (pr-str (vec bad))))))

(deftest every-recorded-exception-is-real
  (testing "an exception that no longer diverges is stale — delete it"
    (let [stale (for [[t _why] collapse-exceptions
                      :when (= (schema/attr-type->db-type t)
                               (schema/codomain->db-type
                                (get kp/attr-type->primitive t "S/String")))]
                  t)]
      (is (empty? stale) (str "no longer divergent: " (pr-str (vec stale)))))))

(deftest simmis-covers-katzens-attr-types
  (testing "katzen is upstream and authoritative on the vocabulary"
    ;; It had :BigDec :BigInt :Symbol :URI when simmis had none of them, so a
    ;; kontor money column reflected as a Long and nothing noticed.
    (let [katzen @(requiring-resolve 'katzen.acset.datahike/attr-type->value-type)
          missing (remove #(contains? schema/attr-type->db-type %) (keys katzen))]
      (is (empty? missing)
          (str "attr-types katzen knows and simmis does not: " (pr-str (vec missing)))))))

(deftest number-is-the-one-deliberate-divergence-from-katzen
  (testing "and it is written down where it happens"
    (let [katzen @(requiring-resolve 'katzen.acset.datahike/attr-type->value-type)
          differs (for [[t v] katzen
                        :let [ours (schema/attr-type->db-type t)]
                        :when (and ours (not= ours v))]
                    [t {:katzen v :simmis ours}])]
      (is (= [:Number] (mapv first differs))
          (str "unreviewed divergences from katzen: " (pr-str (vec differs)))))))

(def ^:private property-type-of @#'kp/attr-type->property-type)

(deftest every-attr-type-has-a-ui-property-type
  (testing "otherwise the property box picks an editor by falling back"
    ;; `:Bool` had no entry and defaulted to :text — a checkbox rendered as a
    ;; text box. Inert only because no live schema emitted :Bool.
    (let [missing (remove #(contains? property-type-of %)
                          (keys schema/attr-type->db-type))]
      (is (empty? missing)
          (str "attr-types with no UI property-type: " (pr-str (vec missing)))))))
