(ns is.simm.model.katzen-projection
  "One-way PROJECTION of a katzen ACSet schema-map into simmis's category S
   (Object/Morphism blocks). katzen is the single SOURCE OF TRUTH for shared
   schemas (e.g. `katzen.schema.knowledge`); category S is a generated,
   queryable VIEW that the UI renders and users can extend.

   There is deliberately NO reverse direction: simmis does not author these
   shared schemas, so it never reads S back into a katzen schema-map. Reasoning,
   composition, migration and cross-ACSet `katzen.xref` all run on the katzen
   schema-maps directly — not on this projection — so the projection only has to
   be faithful enough for S's UI + storage, which it is.

   Conventions: a katzen object `Foo` is the simmis type `S/Foo`; a katzen
   morphism `m` out of object `Dom` is the simmis property `S/Dom/m`. A katzen
   Hom (codom = object) is a `:relation` property; a katzen Attr (codom =
   attr-type) is a typed property whose dst is the simmis primitive for that
   attr-type. `:cardinality :many` maps to simmis's native many — no junction;
   reified relations are ordinary Objects on both sides.

   The projection is LOSSLESS on the categorically load-bearing facts:
     - `:cardinality` (native many, not a junction object),
     - the exact katzen attr-type — stored verbatim on `:morphism/attr-type`,
       so the `:Identity`/`:Keyword`/`:String` distinction survives the
       collapse onto simmis's coarser primitive objects,
     - attr `:unique` — stored on `:morphism/unique` AND propagated to the
       generated datahike storage attribute, so an Identity key (the KB `title`)
       stays an actual key in S."
  (:require [datahike.api :as d]
            [is.simm.model.crud :as crud]))

;; ---------------------------------------------------------------------------
;; attr-type → simmis primitive object / UI property-type

(def attr-type->primitive
  "katzen attr-type → simmis primitive type entity-name. Several attr-types
   collapse onto one primitive object (e.g. Identity/String/Keyword → S/String);
   the exact attr-type is preserved separately on `:morphism/attr-type`."
  {:Identity "S/String" :String "S/String" :Str "S/String" :Keyword "S/String"
   :URI "S/String" :Symbol "S/String"
   :BigDec "S/Number" :BigInt "S/Number"
   ;; A reference the schema cannot name a target for. The VIEW shows an
   ;; identifier; the STORAGE stays a ref — a documented divergence, asserted
   ;; in type-tables-test.
   :Ref "S/String"
   :Long "S/Number" :Int "S/Number" :Integer "S/Number" :Number "S/Number"
   :Float "S/Float" :Double "S/Float"
   :Boolean "S/Boolean" :Bool "S/Boolean"
   :Instant "S/Date" :Date "S/Date"
   :UUID "S/UUID"})

(def ^:private attr-type->property-type
  {:Identity :text :String :text :Str :text :Keyword :text
   :URI :text :Symbol :text
   :BigDec :number :BigInt :number
   :Bool :checkbox
   :Ref :relation
   :Long :number :Int :number :Integer :number :Number :number
   :Float :number :Double :number :Boolean :checkbox
   :Instant :date :Date :date :UUID :text})

(defn- obj-name [o] (str "S/" (name o)))
(defn- morph-name [dom m] (str "S/" (name dom) "/" (name m)))

(defn- exists? [db entity-name]
  (some? (d/q '[:find ?e . :in $ ?n :where [?e :entity/name ?n]] db entity-name)))

;; ---------------------------------------------------------------------------
;; katzen → simmis

(defn project-schema!
  "Project katzen `schema` into category S: a type per object, a property per
   hom (`:relation`, dst = the object) and per attr (dst = the primitive for its
   attr-type), carrying `:cardinality`, the exact `:attr-type`, and `:unique`.
   Returns `schema`.

   `:identify` (opts) maps katzen objects onto EXISTING S types instead of
   minting parallels — the binding-functor object map (doc/kb-unification.md).
   E.g. `{:Entity \"S/Page\"}`: katzen's knowledge Entity IS the wiki page;
   its attrs project as additional optional properties on S/Page
   (S/Page/summary, S/Page/links, …) unless a same-named morphism already
   exists (S/Page/title — which thereby IS katzen's `title : Entity → Identity`
   binding, one datom, no duplication).

   IDEMPOTENT — safe to call on every boot: `create-type!` already skips
   existing types, and morphisms are skipped when a block of the same
   `:entity/name` (unique-identity) already exists. (`create-morphism!` itself is
   NOT idempotent, so we guard it here.)"
  ([conn schema] (project-schema! conn schema {}))
  ([conn schema {:keys [identify storage-ns storage-attrs]}]
   ;; `:storage-attrs` (opts) pins INDIVIDUAL morphisms onto an existing
   ;; datahike attribute — `{:Entity {:created-at :entity/created-at}}`. Use it
   ;; when only SOME of an object's attrs already have storage elsewhere; when
   ;; all of them share one namespace, `:storage-ns` below is the shorthand.
   ;;
   ;; `:storage-ns` (opts) maps katzen objects onto an EXISTING datahike attribute
   ;; namespace, so their attrs REUSE the domain system's own storage instead of
   ;; minting parallel :S.* attrs — e.g. `{:Posting \"kontor.posting\"}` projects
   ;; `amount : Posting → Bigdec` onto `:kontor.posting/amount`. The categorical
   ;; view then renders/queries the real ledger data with zero query-time
   ;; indirection; if the attr already exists it is reused, never re-declared.
   (let [s-type (fn [o] (or (get identify o) (obj-name o)))]
     (doseq [o (:objects schema)
             :when (not (contains? identify o))]
       (crud/create-type! conn {:name (name o)}))
     (let [morph (fn [mname dom dst cardinality property-type extra]
                   (let [dom-type (s-type dom)
                         n (str dom-type "/" (name mname))
                         ;; Per-morphism override wins over the per-object
                         ;; namespace: katzen's `created-at : Entity → Instant`
                         ;; IS `:entity/created-at`, while its siblings `title`
                         ;; and `kind` are genuinely `:S.Page/*`. One namespace
                         ;; for the whole object cannot say that.
                         storage-attr (or (get-in storage-attrs [dom mname])
                                          (when-let [sns (get storage-ns dom)]
                                            (keyword sns (name mname))))]
                     (when-not (exists? @conn n)
                       (crud/create-morphism!
                        conn (merge {:name n :src dom-type :dst dst
                                     :cardinality (or cardinality :one)
                                     :property-type property-type}
                                    (when storage-attr {:storage-attr storage-attr})
                                    extra)))))]
       (doseq [{mname :name :keys [dom codom cardinality]} (:homs schema)]
         (morph mname dom (s-type codom) cardinality :relation nil))
       (doseq [{mname :name :keys [dom codom cardinality unique]} (:attrs schema)]
         (morph mname dom (attr-type->primitive codom "S/String") cardinality
                (attr-type->property-type codom :text)
                (cond-> {:attr-type codom}
                  unique (assoc :unique unique))))))
   schema))
