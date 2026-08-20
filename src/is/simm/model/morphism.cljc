(ns is.simm.model.morphism
  "Where a morphism's data actually lives.

   ONE derivation, shared by the server write path and the client read path,
   because there were five and three of them did not know about reflected
   morphisms.

   A morphism is a property or relation in category S. Its values are stored
   either:

     - under an attribute derived from its NAME (`S/Page/summary` →
       `:S.Page/summary`) — the ordinary case; or
     - under `:morphism/storage-attr`, a REFLECTED view onto a domain system's
       own attribute (`S/Posting/amount` → `:kontor.posting/amount`,
       `S/Page/created-at` → `:entity/created-at`). Category S then describes
       the ledger, or the universal entity timestamps, while the data stays
       where its owner put it.

   Reflected is not the exception: on a store built by `store/install!`, 80 of
   125 morphisms carry a storage attr — including the `created-at` and
   `updated-at` on `S/Page`, which every wiki page has. A derivation that
   ignores `:morphism/storage-attr` therefore reads a nonexistent attribute and
   renders blank, and writes one datahike refuses (`:transact/schema`, because
   every store is `:schema-flexibility :write`)."
  (:require [clojure.string :as str]))

(defn name->attr-ident
  "`\"S/Page/title\"` → `:S.Page/title`. The category path becomes the
   namespace, the last segment the name.

   Splits on EVERY `/`. A `replace-first`-based variant existed in three
   places and is wrong for anything but a three-segment name: `\"S/title\"`
   yields `(keyword \"S.title\")`, which has NO namespace, and `\"A/B/C/d\"`
   yields the name `\"C/d\"`. Every morphism in the live seed happens to be
   three-segment, so the divergence never fired — latent, not observed."
  [morphism-name]
  (when morphism-name
    (let [parts (str/split (str morphism-name) #"/")]
      (keyword (str/join "." (butlast parts)) (last parts)))))

(defn attr-of
  "The attribute a morphism ENTITY's values live under.

   `:morphism/storage-attr` wins when present — that is the whole point of a
   reflected morphism. Falls back to the name derivation.

   Takes the pulled entity (a map), not a name, precisely so the storage attr
   is in scope. A caller holding only a name cannot answer this question, which
   is how the three storage-blind copies came about."
  [morphism]
  (or (:morphism/storage-attr morphism)
      (name->attr-ident (:entity/name morphism))))

(defn reflected?
  "Does this morphism describe data another system owns?

   Such data is READ through category S and must not be WRITTEN through it:
   the owning system has its own invariants (kontor's governor gates postings
   on balance and sealing), and a wiki property box is not a governed writer."
  [morphism]
  (some? (:morphism/storage-attr morphism)))
