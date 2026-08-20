(ns is.simm.ops.diff-summary
  "Hierarchical, navigable summaries of a proposal diff.

   WHY ONE CALL. The whole diff goes to the model at once and the model returns
   the entire tree. Summarizing each page separately would be cheaper to
   orchestrate and strictly worse: only a model that sees every change together
   can write \"these five pages all move onboarding into one place\". That
   sentence is the entire value — a reader who wanted per-page prose could read
   the pages.

   WHY PAGES, NOT OPS. Sections must cite what they cover or expanding one
   cannot show the right blocks. Citing every op is exact but costs roughly a
   hundred output tokens PER OP (measured 2026-07-25: 100 ops → 10.4k output
   tokens, 34s), which does not survive a real restructuring. Citing pages costs
   what the page count costs (same diff: 1.8–6.6k, 6–24s), and a page is the
   unit a reader expands anyway. Ops inherit their page's section, so every
   change is still anchored.

   WHY THE MODEL IS CHECKED. At page granularity a page plausibly belongs to two
   themes, and the model duplicates and drops accordingly — measured 1 run in 5
   put 15 pages in two sections and forgot 2 entirely. So the tree is repaired
   deterministically (`reconcile`) rather than retried into a better mood. The
   invariant that matters is that EVERY changed page appears exactly once: a
   summary which quietly omits a page is worse than no summary, because the
   reader believes they have seen everything.

   Summaries are keyed by the branch's COMMIT id, so a cached summary describes
   exactly the state it was written for and a branch that moves simply misses
   the cache instead of showing stale prose."
  (:require [is.simm.model.references :as refs]
            [is.simm.ops.semantic-diff :as sdiff]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [taoensso.telemere :as log]))

(def ^:private default-max-tokens
  ;; Reasoning models spend output budget on thinking before emitting anything.
  ;; At 4000 the model consumed the entire budget and returned EMPTY content;
  ;; the budget has to cover the thinking AND the tree.
  16000)

(def ^:private strip-html
  "See `refs/strip-html` — one implementation, shared. This one used to drop
   tags without decoding entities, so `&amp;` reached the LLM summary."
  refs/strip-html)
(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

;; ---------------------------------------------------------------------------
;; The digest the model reads
;; ---------------------------------------------------------------------------

(defn page-digest
  "One row per changed page: a stable id, the page, its op counts, and a sample
   of the content. `diffs` is `semantic-diff/proposal-diff` output."
  [diffs]
  (vec
   (for [[si fork] (map-indexed vector diffs)
         [pi page] (map-indexed vector (:pages fork))
         :let [ops (:ops page)
               f (frequencies (map (comp name :op) ops))
               sample (some (fn [o] (let [t (strip-html (or (get-in o [:after :content])
                                                            (get-in o [:before :content])))]
                                      (when (seq t) t)))
                            ops)]]
     {:pid (str "s" si "-p" pi)
      :system (name (or (:system-type fork) :kb))
      :title (or (:title page) "Untitled")
      :added (+ (get f "add" 0) (get f "create" 0))
      :edited (get f "edit" 0)
      :removed (+ (get f "remove" 0) (get f "archive" 0))
      :sample (clip sample 160)})))

(defn- digest-line [{:keys [pid title added edited removed sample]}]
  (str pid " | " (pr-str title)
       " | +" added " ~" edited " -" removed
       (when (seq sample) (str "\n    " sample))))

(defn build-prompt [{:keys [title rationale digest]}]
  (str
   "You are summarizing a proposed knowledge-base change for a NON-PROGRAMMER who
will read your summary first and expand only what looks worth checking.

THE AUTHOR'S STATED TASK
  Title: " title "
  Rationale: " (or rationale "(none given)") "

THE PAGES THAT CHANGED
'+N' added, '~N' edited, '-N' removed, then a sample of the content.

" (str/join "\n" (map digest-line digest)) "

Return ONLY an EDN map, no prose, no code fences:

{:headline \"one sentence, plain language, what this change accomplishes\"
 :risk \"none\" | \"low\" | \"review-carefully\"
 :risk-note \"why, if not none — especially anything DELETED that looks valuable\"
 :sections [{:title \"short section title\"
             :summary \"1-2 sentences a non-programmer understands\"
             :pages [\"s0-p0\" ...]
             :subsections [{:title .. :summary .. :pages [..]}]}]}

RULES
- Group by INTENT, not alphabetically. If one intent spans many pages, that is
  ONE section — say what those pages have in common.
- Every page id above must appear EXACTLY ONCE in the tree. Never drop a page.
- Only use page ids that appear above. Never invent one.
- Subsections only where a section genuinely has parts. Do not pad.
- Call out removals explicitly. A skimming reader must not miss deleted content."))

;; ---------------------------------------------------------------------------
;; Parse + repair
;; ---------------------------------------------------------------------------

(defn- parse-edn [text]
  (let [t (-> (str text)
              (str/replace #"(?s)^.*?```(?:edn|clojure)?\s*" "")
              (str/replace #"(?s)```.*$" "")
              str/trim)
        t (if (str/starts-with? t "{") t (str/trim (str text)))]
    (try (edn/read-string t)
         (catch Exception _ nil))))

(defn- collect-pages [sections]
  (mapcat (fn [s] (concat (:pages s) (collect-pages (:subsections s)))) sections))

(defn reconcile
  "Force the anchoring invariant to hold whatever the model returned: keep the
   first occurrence of a page, drop later ones, and sweep anything it forgot
   into an explicit section. The repair is REPORTED, never silent — a summary
   that quietly rearranges the diff is exactly the failure this guards."
  [summary digest]
  (let [want (mapv :pid digest)
        want-set (set want)
        seen (volatile! #{})
        prune (fn prune [sections]
                (vec (for [s sections]
                       (assoc s
                              :pages (vec (for [p (:pages s)
                                                :when (and (want-set p)
                                                           (not (@seen p)))]
                                            (do (vswap! seen conj p) p)))
                              :subsections (prune (:subsections s))))))
        sections (prune (:sections summary))
        missing (vec (remove @seen want))
        dropped (- (count (collect-pages (:sections summary))) (count @seen))]
    {:summary (cond-> (assoc summary :sections sections)
                (seq missing)
                (update :sections (fnil conj [])
                        {:title "Other changes"
                         :summary (str (count missing) " page(s) the summary did not "
                                       "otherwise mention, listed so nothing is hidden.")
                         :pages missing}))
     :repaired {:swept-in missing :dropped-duplicates dropped}}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn summarize-diff
  "Hierarchical summary of `diffs` (semantic-diff/proposal-diff output), given
   the author's stated task. Returns
   {:headline :risk :risk-note :sections [...] :digest [...] :repaired {...}},
   or nil when the model call fails — callers keep rendering the raw diff, which
   is always the source of truth."
  [{:keys [title rationale diffs model max-tokens]}]
  (let [digest (page-digest diffs)]
    (when (seq digest)
      (let [prompt (build-prompt {:title title :rationale rationale :digest digest})
            r ((requiring-resolve 'dvergr.tools.llm-call/cheap-llm-call)
               prompt "" (cond-> {:max-tokens (or max-tokens default-max-tokens)}
                           model (assoc :model model)))
            parsed (parse-edn (:text r))]
        (cond
          (:error r)
          (do (log/log! {:level :warn :id ::llm-call-failed
                         :data {:error (:error r)}})
              nil)

          (not (map? parsed))
          (do (log/log! {:level :warn :id ::unparseable
                         :msg "Diff summary was not EDN — falling back to the raw diff"
                         :data {:chars (count (str (:text r)))
                                :usage (:usage r)}})
              nil)

          :else
          (let [{:keys [summary repaired]} (reconcile parsed digest)]
            (when (or (seq (:swept-in repaired))
                      (pos? (long (:dropped-duplicates repaired 0))))
              (log/log! {:level :info :id ::summary-repaired
                         :msg "Model's section tree did not anchor cleanly; repaired"
                         :data repaired}))
            (assoc summary :digest digest :repaired repaired)))))))
