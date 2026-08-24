(ns is.simm.model.model-catalog
  "The model list every picker shows, built server-side.

   Two halves, and the split is the point. The FAMILIES are curated: a family
   string makes a poor name, and which families to put in front of someone is a
   judgement call. The VERSIONS under each family are DERIVED from last-known
   provider and registry facts. Availability is explicit on every row; a family
   never disappears merely because its credential or account access does.

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

(defn- provenance
  "Last-known endpoint facts for a provider/model pair. Picker rows retain
   these instead of flattening availability back to an unscoped id."
  [provider id]
  (merge
   {:credential-source (:credential-source (ms/provider-contract provider))}
   (some #(when (and (= (keyword provider) (:provider %))
                     (= id (:model-id %)))
            (select-keys % [:base-url :credential-source :reachability
                            :reachable? :model-id :endpoint-kind
                            :native-openai?]))
         (ms/catalog))))

(defn reasoning-disabled-for-tools?
  "Whether the configured provider will apply this model's tools workaround.

   The registry identifies affected OpenAI models. dvergr deliberately applies
   the workaround only to native OpenAI requests; a custom OPENAI_BASE_URL is
   merely protocol-compatible and must not receive an OpenAI-native field."
  [provider id]
  (let [provider (keyword provider)
        endpoint (some #(when (= provider (:provider %)) %) (ms/provider-endpoints))]
    (boolean
     (and (registry/get-quirk id :chat-tools-need-effort-none?)
          (or (not= :openai provider)
              (:native-openai? endpoint))))))

(defn reasoning-copy
  "The words a screen shows for this model's reasoning, plus the tooltip."
  [no-reasoning?]
  {:reasoning-copy (if no-reasoning? reasoning-off-copy reasoning-on-copy)
   :reasoning-explanation (when no-reasoning? reasoning-off-explanation)})

(def ^:private max-versions
  "How many pinned versions to offer under a family.

   A picker is a shortlist, not an archive: two keeps `latest` plus the nearest
   explicit pins. Server validation accepts only rows in this shortlist."
  2)

(defn availability-copy
  "Human copy for one authoritative availability result."
  [provider {:keys [state credential-source]}]
  (case state
    :available
    {:availability-label "Available"
     :availability-explanation "Available to this account and supported by simmis."}

    :needs-credential
    {:availability-label "Credential required"
     :availability-explanation
     (str "Set " credential-source " in the server environment, then restart simmis.")}

    :not-implemented
    {:availability-label "Not yet supported"
     :availability-explanation "Not yet supported."}

    :unavailable-to-account
    {:availability-label "Unavailable to account"
     :availability-explanation
     (str (provider-label provider) " does not make this model available to this account.")}

    :temporarily-unreachable
    {:availability-label "Temporarily unreachable"
     :availability-explanation
     (str (provider-label provider)
          " model availability could not be refreshed. Last-known status is retained, "
          "but this choice cannot be used until the provider responds.")}))

(defn- with-availability
  [row]
  (let [availability (ms/model-availability (:provider row) (:resolves row))]
    (merge row
           {:availability (:state availability)
            :availability-reason (:reason availability)
            :available? (:available? availability)
            :disabled? (not (:available? availability))}
           (select-keys availability [:credential-source :last-success-at])
           (availability-copy (:provider row) availability)
           (reasoning-copy (:no-reasoning? row))
           (provenance (:provider row) (:resolves row)))))

(defn- family-rows
  "One always-visible `latest` row, then last-known/registered version rows."
  [{:keys [family label provider]}]
  (let [versions (take max-versions (ms/known-versions-in provider family))
        latest-id (:candidate (ms/resolve-selection {:family family
                                                     :version :auto
                                                     :provider provider}))]
    (into [(with-availability
            {:kind :family
             :value family
             :label (str label " (latest)")
             :provider provider
             :provider-label (provider-label provider)
             :resolves latest-id
             :no-reasoning? (reasoning-disabled-for-tools? provider latest-id)})]
          (map (fn [v]
                 (let [id (ms/id-for family v)]
                   (with-availability
                    {:kind :version
                     :value id
                     :label (str label " " (version-label v))
                     :provider provider
                     :provider-label (provider-label provider)
                     :resolves id
                     :no-reasoning? (reasoning-disabled-for-tools? provider id)})))
               versions))))

(defn choices
  "Every row a model picker should show, in order. Each row carries its own
   copy, so the client never composes a sentence of its own."
  []
  (mapv identity
        (mapcat (fn [{:keys [family model label provider] :as entry}]
                  (if family
                    (family-rows entry)
                    [(with-availability
                      {:kind :model
                       :value model
                       :label label
                       :provider provider
                       :provider-label (provider-label provider)
                       :resolves model
                       :no-reasoning? (reasoning-disabled-for-tools? provider model)})]))
                curated)))

(defn choice
  "Picker row for `value`, or nil when the value is outside the curated list."
  [value]
  (some #(when (= value (:value %)) %) (choices)))

(defn require-available-choice!
  "Return the curated available row, or reject an unavailable/unknown choice."
  [value]
  (let [row (choice value)]
    (if (:available? row)
      row
      (throw (ex-info "Model choice is unavailable"
                      {:type :model-choice-unavailable
                       :model-choice value
                       :availability (or (:availability row) :not-implemented)
                       :availability-reason (or (:availability-reason row)
                                                :not-curated)
                       :credential-source (:credential-source row)})))))

(defn selected?
  "Is `row` the one this config uses? `model-info` comes from
   `room-agents/describe-model`."
  [row {:keys [family auto? model candidate]}]
  (if (and family auto?)
    (= (:value row) family)
    (= (:value row) (or model candidate))))

(defn choice-label
  "The picker's own label for what this config selected, so the configuration
   panel names a model exactly the way the list that set it does. nil when the
   config points at something the picker does not offer."
  [model-info]
  (->> (choices)
       (filter #(selected? % model-info))
       first
       :label))
