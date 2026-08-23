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
     gpt-5.6-luna                           → gpt-*-luna

   VERSION is the token that filled the slot (\"5p2\", \"k2p6\", \"v4\", \"5.6\"),
   ordered by its digit groups — 5p2 > 5p1, k2p6 > k2p5, 5.6 > 5.5. :auto means
   \"newest the provider actually offers\", read from the live catalog, not from a
   list we maintain.

   The CATALOG spans every OpenAI-compatible endpoint this machine has a key for
   — Fireworks and OpenAI both, merged — so a family resolves against the
   provider that actually serves it."
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
  ;; 5p2, k2p6, v4, 5.6 — an optional letter, digits, optionally a minor part
  ;; after `p` (Fireworks writes 5p2) or `.` (OpenAI writes 5.6).
  ;; Deliberately rejects "120b" and "oss": a size or a codename is not a
  ;; version, and treating it as one would let `auto` pick gpt-oss-120b over
  ;; gpt-oss-20b as if it were an upgrade.
  #"^[a-z]?\d+([p.]\d+)?$")

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

(def ^:private version-key-width 4)

(defn- version-key
  "Sortable key for a version token: its digit groups, in order, PADDED with
   zeros. \"k2p6\" → [2 6 0 0].

   The padding is the whole point. Clojure compares vectors by COUNT first, so
   the unpadded keys made \"3.5\" [3 5] outrank \"4\" [4] — `:auto` on the
   gpt-*-turbo family would have picked gpt-3.5-turbo over gpt-4-turbo. Padded,
   the comparison is major-then-minor, which is what a version means."
  [v]
  (let [groups (mapv parse-long (re-seq #"\d+" (str v)))]
    (into groups (repeat (- version-key-width (count groups)) 0))))

(defn id-for
  "The concrete id in `family` carrying `version`."
  [family version]
  (str/replace (str family) "*" (str version)))

;; ---------------------------------------------------------------------------
;; Provider catalog — what is ACTUALLY on offer, not what we remember
;; ---------------------------------------------------------------------------

(def ^:private catalog-ttl-ms (* 10 60 1000))

(defonce ^:private catalog-cache (atom {:at 0 :ids nil}))

(def ^:private openai-base "https://api.openai.com/v1")
(def ^:private fireworks-base "https://api.fireworks.ai/inference/v1")

(def ^:private snapshot-id-re
  ;; gpt-5.5-2026-04-23 — a dated snapshot of a model we already list under its
  ;; rolling id. The date's digit groups would parse as a version token, so
  ;; leaving these in would have `:auto` chasing release dates inside families
  ;; that exist only because a snapshot id got split apart.
  #".*-\d{4}-\d{2}-\d{2}$")

(defn- endpoints
  "Every OpenAI-compatible /models endpoint this machine is configured for,
   as {base-url api-key}.

   Two, not one. `OPENAI_BASE_URL` used to be REQUIRED here, so the normal way
   to hold an OpenAI key — the key alone — fetched nothing at all: the catalog
   stayed empty, and an empty catalog makes every `:auto` selection fall back
   to `default-model` while logging that the family isn't on offer. And a
   machine holding both keys serves both families, so asking one endpoint
   would strand the other's agents.

   Keyed by base url, so an `OPENAI_BASE_URL` aimed at Fireworks (or at a
   local endpoint) collapses into one entry instead of being asked twice."
  []
  (let [base (System/getenv "OPENAI_BASE_URL")]
    (cond-> {}
      (System/getenv "FIREWORKS_API_KEY")
      (assoc fireworks-base (System/getenv "FIREWORKS_API_KEY"))

      (System/getenv "OPENAI_API_KEY")
      (assoc (or base openai-base) (System/getenv "OPENAI_API_KEY")))))

(defn- fetch-endpoint!
  "Model ids one OpenAI-compatible endpoint serves, or nil when it can't be
   reached."
  [base key]
  (try
    (let [resp ((requiring-resolve 'babashka.http-client/get)
                (str base "/models")
                {:headers {"Authorization" (str "Bearer " key)}
                 :timeout 10000})
          body ((requiring-resolve 'jsonista.core/read-value)
                (:body resp)
                ((requiring-resolve 'jsonista.core/object-mapper) {:decode-key-fn true}))]
      (->> (:data body)
           (map :id)
           (remove nil?)
           (remove #(re-matches snapshot-id-re %))
           vec
           seq))
    (catch Throwable t
      (log/log! {:level :warn :id ::catalog-fetch-failed
                 :data {:base base :error (ex-message t)}})
      nil)))

(defn- fetch-catalog!
  "Model ids the configured providers currently serve, merged. nil when no
   endpoint answered — the caller keeps its last good answer rather than
   treating a network failure as an empty catalog."
  []
  (seq (into [] (comp (map (fn [[base key]] (fetch-endpoint! base key)))
                      (remove nil?)
                      cat)
             (endpoints))))

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

(defn- registered?
  "Does dvergr's registry carry this id? A model has to be in BOTH places to be
   usable: the provider's catalog says it can be reached, the registry says what
   its context window is and what it costs."
  [id]
  (boolean ((requiring-resolve 'dvergr.model.registry/get-model) id)))

(defn newest-usable
  "Newest version of `family` that the provider serves AND the registry knows.

   Not simply the newest on offer. Fireworks serves kimi-k3 and minimax-m3 today
   while dvergr's registry stops at k2p6 and m2p7, and `get-model!` throws on an
   id it does not know — so `:auto` on those families would have picked a model
   that kills the turn. One version behind beats a turn that cannot start.

   Falls back to the newest served when the registry knows none of them, which
   is the state of a registry that has not loaded yet."
  [family]
  (let [vs (versions-in family)
        known (filter #(registered? (id-for family %)) vs)]
    (when (and (seq vs) (seq known) (not= (first vs) (first known)))
      (log/log! {:level :info :id ::newer-version-not-registered
                 :data {:family family :serving (first vs) :using (first known)}}))
    (or (first known) (first vs))))

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

    ;; NOTHING chosen. nil, not `default-model`, so the caller can fall back to
    ;; the owner's preference before the code default. This used to answer
    ;; `default-model` here, which made `ensure-agent-joined!`'s
    ;; `(or resolved fallback-model default-model)` unreachable past its first
    ;; branch: Settings > Model Preference wrote a row nothing ever read.
    (nil? family)
    nil

    (or (nil? version) (= version :auto) (= version "auto"))
    (if-let [newest (newest-usable family)]
      (id-for family newest)
      (do (log/log! {:level :warn :id ::family-not-in-catalog
                     :data {:family family :falling-back-to default-model}})
          default-model))

    :else
    (let [id (id-for family version)]
      (if (some #{id} (catalog))
        id
        (let [newest (newest-usable family)]
          (log/log! {:level :warn :id ::pinned-version-unavailable
                     :data {:selection selection :pinned id :using (when newest (id-for family newest))}})
          (if newest (id-for family newest) default-model))))))

(defn resolve-config
  "Model id for an agent's :actor/config, or nil when it configures no model.

   Honours the family/version form AND a legacy fully-pinned :model — but the
   family form WINS when both are present, so a migrated agent follows its
   family instead of the stale id we are trying to grow out of."
  [{:keys [model-family model-version model]}]
  (resolve-model {:family model-family
                  :version model-version
                  :model (when-not model-family model)}))

(defn family? [s] (str/includes? (str s) "*"))

(defn resolve-string
  "One string, either form. `gpt-*-luna` is a family at its latest version;
   `gpt-5.5` is a pinned id.

   One attribute holds both because a person's model preference should not
   freeze the way an agent's config used to. Picking \"latest\" stores the
   family and re-resolves on every turn."
  [s]
  (when (seq (str s))
    (if (family? s)
      (resolve-model {:family s :version :auto})
      s)))

(defn describe
  "What an agent's stored config actually means, for display and for a picker's
   current value.

   {:family :version :auto? :model :configured?}. `:model` is the id the next
   turn will use, `:configured?` is false when the agent chose nothing and
   inherits its owner's preference."
  [{:keys [model-family model-version model] :as cfg}]
  (let [auto? (or (nil? model-version) (= model-version :auto) (= model-version "auto"))]
    {:family      model-family
     :version     (when-not auto? model-version)
     :auto?       (boolean (and model-family auto?))
     :model       (resolve-config cfg)
     :configured? (boolean (or model-family model))}))
