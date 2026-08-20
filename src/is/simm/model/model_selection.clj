(ns is.simm.model.model-selection
  "Pick a model by FAMILY and VERSION, and resolve to a concrete provider id at
   TURN time.

   The bug this exists to kill: the model id used to be frozen into an agent's
   :actor/config when the agent was created. Bumping the code default then did
   nothing for agents that already existed — Vár ran glm-5p1 for eleven days
   after we'd 'switched' to 5p2, and every provider quirk we chased in that time
   came from a model we believed we had stopped using. Configuration captured at
   creation cannot be corrected by changing code; it can only be migrated, and
   nobody remembers to write the migration.

   So: an agent stores what the HUMAN chose — a family, and either a pinned
   version or :auto — and the concrete id is computed fresh on every turn. A new
   release is picked up by restarting, not by a migration.

   FAMILY is the id with its version slot blanked, which makes it provider-shaped
   rather than a name we have to curate:

     accounts/fireworks/models/glm-5p2      → accounts/fireworks/models/glm-*
     accounts/fireworks/models/kimi-k2p6    → accounts/fireworks/models/kimi-*
     accounts/fireworks/models/deepseek-v4-pro → accounts/fireworks/models/deepseek-*-pro

   VERSION is the token that filled the slot (\"5p2\", \"k2p6\", \"v4\"), ordered by
   its digit groups — 5p2 > 5p1, k2p6 > k2p5. :auto means \"newest the provider
   actually offers\", read from the live catalog, not from a list we maintain."
  (:require [clojure.string :as str]
            [taoensso.telemere :as log]))

(def default-model
  "The id an agent falls back to when the provider catalog is unreachable and
   nothing else resolves. Single source of truth — `parties/default-model`
   re-exports this. Keep in sync with the Fireworks registry: older suffixes
   (glm-5, kimi-k2-thinking) 404 once a new version ships."
  "accounts/fireworks/models/glm-5p2")

;; ---------------------------------------------------------------------------
;; Version grammar
;; ---------------------------------------------------------------------------

(def ^:private version-token-re
  ;; 5p2, k2p6, v4 — an optional letter, digits, optionally `p` + digits.
  ;; Deliberately rejects "120b" and "oss": a size or a codename is not a
  ;; version, and treating it as one would let `auto` pick gpt-oss-120b over
  ;; gpt-oss-20b as if it were an upgrade.
  #"^[a-z]?\d+(p\d+)?$")

(defn- tokens [id] (str/split (str id) #"-"))

(defn version-of
  "The version token of a model id, or nil when it carries no version."
  [id]
  (->> (tokens id)
       (filter #(re-matches version-token-re %))
       last))

(defn family-of
  "The id with its version slot blanked — the family key. nil when unversioned
   (such a model is its own family and can never be auto-upgraded)."
  [id]
  (when-let [v (version-of id)]
    (->> (tokens id)
         (map #(if (= % v) "*" %))
         (str/join "-"))))

(defn- version-key
  "Sortable key for a version token: its digit groups, in order. \"k2p6\" → [2 6]."
  [v]
  (mapv parse-long (re-seq #"\d+" (str v))))

(defn id-for
  "The concrete id in `family` carrying `version`."
  [family version]
  (str/replace (str family) "*" (str version)))

;; ---------------------------------------------------------------------------
;; Provider catalog — what is ACTUALLY on offer, not what we remember
;; ---------------------------------------------------------------------------

(def ^:private catalog-ttl-ms (* 10 60 1000))

(defonce ^:private catalog-cache (atom {:at 0 :ids nil}))

(defn- fetch-catalog!
  "Model ids the provider currently serves (OpenAI-compatible /models)."
  []
  (let [base (System/getenv "OPENAI_BASE_URL")
        key  (System/getenv "OPENAI_API_KEY")]
    (when (and base key)
      (try
        (let [resp ((requiring-resolve 'babashka.http-client/get)
                    (str base "/models")
                    {:headers {"Authorization" (str "Bearer " key)}
                     :timeout 10000})
              body ((requiring-resolve 'jsonista.core/read-value)
                    (:body resp)
                    ((requiring-resolve 'jsonista.core/object-mapper) {:decode-key-fn true}))]
          (->> (:data body) (map :id) (remove nil?) vec seq))
        (catch Throwable t
          (log/log! {:level :warn :id ::catalog-fetch-failed
                     :data {:error (ex-message t)}})
          nil)))))

(defn catalog
  "Cached model catalog. Falls back to the last good answer, then to the
   configured default — an agent must still be able to run when the provider's
   model list is unreachable."
  ([] (catalog false))
  ([force?]
   (let [{:keys [at ids]} @catalog-cache
         fresh? (and ids (< (- (System/currentTimeMillis) at) catalog-ttl-ms))]
     (if (and fresh? (not force?))
       ids
       (if-let [fetched (fetch-catalog!)]
         (do (reset! catalog-cache {:at (System/currentTimeMillis) :ids fetched})
             fetched)
         (or ids [default-model]))))))

(defn versions-in
  "Versions of `family` the provider offers, newest first."
  [family]
  (->> (catalog)
       (filter #(= family (family-of %)))
       (map version-of)
       (sort-by version-key #(compare %2 %1))
       vec))

(defn families
  "Families on offer → {family [versions newest-first]}. Powers a UI picker:
   choose the family, then :auto or a pinned version."
  []
  (->> (catalog)
       (keep family-of)
       distinct
       (map (fn [f] [f (versions-in f)]))
       (into {})))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(def default-family
  "Family a new agent gets. The VERSION is deliberately not part of this — that
   is the whole point (see the ns docstring)."
  (or (family-of default-model)
      "accounts/fireworks/models/glm-*"))

(defn resolve-model
  "Concrete model id for a selection.

   {:family f :version \"5p2\"} — pinned: the human chose this exact version.
   {:family f :version :auto}   — newest version of f the provider offers.
   {:model id}                  — a fully pinned id (legacy configs, and the
                                  escape hatch for a model with no version).

   A pinned version that the provider has withdrawn resolves to the newest in
   its family instead of 404-ing the turn — being one minor version off beats
   an agent that cannot speak."
  [{:keys [model family version] :as selection}]
  (cond
    ;; explicit id wins — nothing to resolve
    (and model (not family)) model

    (nil? family)
    default-model

    (or (nil? version) (= version :auto) (= version "auto"))
    (if-let [newest (first (versions-in family))]
      (id-for family newest)
      (do (log/log! {:level :warn :id ::family-not-in-catalog
                     :data {:family family :falling-back-to default-model}})
          default-model))

    :else
    (let [id (id-for family version)]
      (if (some #{id} (catalog))
        id
        (let [newest (first (versions-in family))]
          (log/log! {:level :warn :id ::pinned-version-unavailable
                     :data {:selection selection :pinned id :using (when newest (id-for family newest))}})
          (if newest (id-for family newest) default-model))))))

(defn resolve-config
  "Model id for an agent's :actor/config.

   Honours the family/version form AND a legacy fully-pinned :model — but the
   family form WINS when both are present, so a migrated agent follows its
   family instead of the stale id we are trying to grow out of."
  [{:keys [model-family model-version model]}]
  (resolve-model {:family model-family
                  :version model-version
                  :model (when-not model-family model)}))
