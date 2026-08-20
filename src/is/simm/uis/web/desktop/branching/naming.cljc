(ns is.simm.uis.web.desktop.branching.naming
  "Branch naming helpers. v1 is purely heuristic; later steps replace the
   stub with a cheap LLM auto-namer (see doc/archive/branching-systematic-design.md
   § Auto-naming).

   Two concerns:
   1. **Slug generation** — what to send to the server's `branch-kb!`
      when the user clicks Branch without supplying a name. Cheap +
      collision-tolerant since `branch-kb!` is idempotent on slug.
   2. **Display name** — what the sidebar / pill / dropdown renders. We
      keep the branch keyword as the canonical id; display name is a
      pretty derivative.")

(defn fresh-slug
  "Stub: short timestamp+random tag. The server sanitizes anyway. Later
   replaced by an LLM-derived name when the user's intent prompt arrives
   from the Try-prompt flow."
  []
  (let [t (-> #?(:clj (java.util.Date.) :cljs (js/Date.))
              #?(:clj .getTime :cljs .getTime))
        n (#?(:clj rand-int :cljs rand-int) 9999)]
    (str "fork-" (mod t 100000) "-" n)))

(defn display-name
  "Pretty-print a branch keyword for the chrome. Trunk → 'main'. Forks
   strip the trunk prefix so `:db-fork-abc-12345` renders as
   `fork-abc-12345`. When a manual / LLM-derived name is later stored
   alongside the branch row, this fn becomes the read path."
  ([branch-kw] (display-name branch-kw nil))
  ([branch-kw row]
   (cond
     (:display-name row) (:display-name row)
     (= :db branch-kw) "main"
     (nil? branch-kw) "main"
     :else (let [s (name branch-kw)]
             (if (clojure.string/starts-with? s "db-")
               (subs s 3)
               s)))))
