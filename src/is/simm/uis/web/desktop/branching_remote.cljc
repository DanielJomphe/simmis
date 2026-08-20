(ns is.simm.uis.web.desktop.branching-remote
  "Spin-remote shims around server-side branching ops in
   `is.simm.runtimes.branching`. Each remote is a thin pass-through that
   accepts string-typed UUIDs (the wire format) and converts on entry."
  (:require [org.replikativ.spindel.distributed.macros :refer [defn-spin-remote]
             :include-macros true]
            [org.replikativ.spindel.distributed.core :as dist]
            #?(:clj [is.simm.runtimes.branching :as branching])
            #?(:clj [is.simm.runtimes.context :as ctx])))

(defn-spin-remote list-kb-branches!
  [server-id db-scope-str]
  (spin-remote server-id [db-scope-str]
    (let [s (identity db-scope-str)]
      #?(:clj (ctx/with-server-context
                (let [scope (java.util.UUID/fromString s)]
                  ;; Set of branch keywords. Serialize as a sorted vec
                  ;; so the wire format is stable.
                  (vec (sort (or (branching/list-kb-branches scope) #{})))))
         :cljs nil))))

(defn-spin-remote kb-commit-graph!
  [server-id db-scope-str]
  (spin-remote server-id [db-scope-str]
    (let [s (identity db-scope-str)]
      #?(:clj (ctx/with-server-context
                (let [scope (java.util.UUID/fromString s)]
                  (branching/kb-commit-graph scope)))
         :cljs nil))))

;; Create a new branch on a KB. `slug` becomes the suffix on the parent's
;; branch name (sanitized server-side). Returns {:branch :parent :existed?}.
(defn-spin-remote branch-kb!
  [server-id db-scope-str slug parent-branch]
  (spin-remote server-id [db-scope-str slug parent-branch]
    (let [s (identity db-scope-str)
          sl (identity slug)
          pb (identity parent-branch)]
      #?(:clj (ctx/with-server-context
                (let [scope (java.util.UUID/fromString s)]
                  (if pb
                    (branching/branch-kb! scope sl pb)
                    (branching/branch-kb! scope sl))))
         :cljs nil))))

(defn-spin-remote discard-kb-branch!
  [server-id db-scope-str branch-kw]
  (spin-remote server-id [db-scope-str branch-kw]
    (let [s (identity db-scope-str)
          b (identity branch-kw)]
      #?(:clj (ctx/with-server-context
                (let [scope (java.util.UUID/fromString s)]
                  (branching/discard-kb-branch! scope b)))
         :cljs nil))))

;; Merge `source-branch` into `target-branch` (defaults to trunk :db).
(defn-spin-remote merge-kb!
  [server-id db-scope-str source-branch target-branch]
  (spin-remote server-id [db-scope-str source-branch target-branch]
    (let [s (identity db-scope-str)
          src (identity source-branch)
          tgt (identity target-branch)]
      #?(:clj (ctx/with-server-context
                (let [scope (java.util.UUID/fromString s)
                      target (or tgt :db)]
                  (branching/merge-kb! scope src target)))
         :cljs nil))))

;; Return the diff between two branches via yggdrasil.protocols/diff.
;; Shape is adapter-specific (DatahikeDiff record). Serialized for wire.
(defn-spin-remote kb-diff!
  [server-id db-scope-str from-branch to-branch]
  (spin-remote server-id [db-scope-str from-branch to-branch]
    (let [s (identity db-scope-str)
          a (identity from-branch)
          b (identity to-branch)]
      #?(:clj (ctx/with-server-context
                (let [scope (java.util.UUID/fromString s)
                      diff (branching/kb-diff scope a b)]
                  ;; Convert the DatahikeDiff record into a plain map for
                  ;; transit-friendly serialization. :added-datoms and
                  ;; :removed-datoms are counts; :entities-touched is a count.
                  (when diff
                    (into {} diff))))
         :cljs nil))))

