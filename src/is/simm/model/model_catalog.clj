(ns is.simm.model.model-catalog
  "The model list every picker shows, built server-side.

   Two halves, and the split is the point. The FAMILIES are curated: a family
   string makes a poor name, and which families to put in front of someone is a
   judgement call. The VERSIONS under each family are DERIVED, from the versions
   the provider serves that dvergr's registry also knows.

   Hand-writing both halves is what made the list incoherent: GLM had a pinned
   5.2 row because someone typed one, while the GPT families had none because
   nobody did. Now every family gets the same treatment by construction, and a
   version that ships stops being invisible until a human notices."
  (:require [clojure.string :as str]
            [is.simm.model.model-selection :as ms]
            [dvergr.model.registry :as registry]))

(def curated
  "Ordered. A `:family` entry expands into a `latest` row plus one row per
   version on offer; a `:model` entry is a single row for something that has no
   version to follow."
  [{:family "accounts/fireworks/models/glm-*" :label "GLM" :provider "fireworks"}
   {:family "accounts/fireworks/models/kimi-*" :label "Kimi" :provider "fireworks"}
   {:family "accounts/fireworks/models/minimax-*" :label "MiniMax" :provider "fireworks"}
   {:family "accounts/fireworks/models/deepseek-*-pro" :label "DeepSeek Pro" :provider "fireworks"}
   {:model "accounts/fireworks/models/qwen3p6-plus" :label "Qwen3.6 Plus" :provider "fireworks"}

   {:family "gpt-*-sol" :label "GPT Sol" :provider "openai"}
   {:family "gpt-*-terra" :label "GPT Terra" :provider "openai"}
   {:family "gpt-*-luna" :label "GPT Luna" :provider "openai"}
   {:family "gpt-*" :label "GPT" :provider "openai"}
   {:family "gpt-*-mini" :label "GPT mini" :provider "openai"}

   {:model "claude-sonnet-4-6" :label "Claude Sonnet 4.6" :provider "anthropic"}
   {:model "claude-opus-4-7" :label "Claude Opus 4.7" :provider "anthropic"}])

(defn- version-label
  "A version token as a person writes it. `5p2` is Fireworks' spelling of 5.2,
   and `k2p6` of K2.6."
  [v]
  (let [s (-> (str v) (str/replace #"(?<=\d)p(?=\d)" "."))]
    (if (re-find #"^[a-z]" s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s)))

(def provider-labels
  "How a provider is written for a person. One map, used by the picker rows and
   by `room-agents/describe-model`, so a screen cannot show `openai` in one
   place and `OPENAI` in another."
  {:openai "OpenAI"
   :fireworks "Fireworks"
   :anthropic "Anthropic"
   :claude-code "Claude Code"})

(defn short-id
  "A model id as a person reads it.

   Fireworks addresses a model by path, `accounts/fireworks/models/glm-5p2`,
   while OpenAI uses a bare `gpt-5.6-luna`. Printed raw, one `Running now` row
   showed a path and the next showed a name, for the same kind of fact. The
   prefix carries no information the Provider row does not already give."
  [id]
  (some-> id str (str/split #"/") last))

(defn provider-label [provider]
  (get provider-labels (keyword provider) (some-> provider name)))

(def reasoning-off-copy
  "ONE phrase for the reasoning caveat. The configuration panel puts it after a
   `Reasoning` label, the picker row prefixes it with the word, and both read as
   the same sentence. Two phrasings for one fact is how a reader ends up
   believing there are two facts."
  "off while tools are attached")

(def reasoning-on-copy "on")

(def reasoning-off-explanation
  "The tooltip behind that phrase. Names the tools, because \"tools\" on its own
   means nothing to someone who has not read the turn code."
  (str "Every turn carries this room's tools: clojure_eval, shell, file reading "
       "and editing, search, tests. This model refuses tool calls unless its "
       "reasoning is switched off, so simmis switches it off and keeps the tools."))

(defn- registered? [id] (boolean (registry/get-model id)))

(defn- no-reasoning? [id]
  (boolean (registry/get-quirk id :chat-tools-need-effort-none?)))

(defn reasoning-copy
  "The words a screen shows for this model's reasoning, plus the tooltip."
  [no-reasoning?]
  {:reasoning-copy (if no-reasoning? reasoning-off-copy reasoning-on-copy)
   :reasoning-explanation (when no-reasoning? reasoning-off-explanation)})

(def ^:private max-versions
  "How many pinned versions to offer under a family.

   The models.dev overlay registers everything OpenAI currently serves, so the
   bare `gpt-*` family knows seven versions back to gpt-4. A picker is a
   shortlist, not an archive: two keeps `latest` plus the one you would roll
   back to. Anything older is still reachable by writing the id into the
   agent's config."
  2)

(defn- family-rows
  "One `latest` row for `family`, then a row per version on offer. Empty when
   nothing in the family is both served and registered, which is how a family
   the current keys cannot reach drops out of the picker instead of being
   offered and then failing at turn time."
  [{:keys [family label provider]}]
  (let [versions (->> (ms/versions-in family)
                      (filter #(registered? (ms/id-for family %)))
                      (take max-versions))]
    (when-let [latest (first versions)]
      (into [{:kind :family
              :value family
              :label (str label " (latest)")
              :provider provider
              :provider-label (provider-label provider)
              :resolves (ms/id-for family latest)
              :no-reasoning? (no-reasoning? (ms/id-for family latest))}]
            (map (fn [v]
                   (let [id (ms/id-for family v)]
                     {:kind :version
                      :value id
                      :label (str label " " (version-label v))
                      :provider provider
                      :provider-label (provider-label provider)
                      :resolves id
                      :no-reasoning? (no-reasoning? id)}))
                 versions)))))

(defn choices
  "Every row a model picker should show, in order. Each row carries its own
   copy, so the client never composes a sentence of its own."
  []
  (mapv (fn [row] (merge row (reasoning-copy (:no-reasoning? row))))
        (mapcat (fn [{:keys [family model label provider] :as entry}]
                  (cond
                    family (family-rows entry)
                    (and model (registered? model))
                    [{:kind :model
                      :value model
                      :label label
                      :provider provider
                      :provider-label (provider-label provider)
                      :resolves model
                      :no-reasoning? (no-reasoning? model)}]
                    :else []))
                curated)))

(defn selected?
  "Is `row` the one this config uses? `model-info` comes from
   `room-agents/describe-model`."
  [row {:keys [family auto? model]}]
  (if (and family auto?)
    (= (:value row) family)
    (= (:value row) model)))

(defn choice-label
  "The picker's own label for what this config selected, so the configuration
   panel names a model exactly the way the list that set it does. nil when the
   config points at something the picker does not offer."
  [model-info]
  (->> (choices)
       (filter #(selected? % model-info))
       first
       :label))
