(ns is.simm.model.model-catalog-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.model.registry :as registry]
            [is.simm.model.fake-models-server :as fake]
            [is.simm.model.model-catalog :as catalog]
            [is.simm.model.model-selection :as selection]))

(def ^:private openai-key "catalog-openai-key")
(def ^:private fireworks-key "catalog-fireworks-key")
(def ^:private fireworks-model "accounts/fireworks/models/glm-5p2")

(use-fixtures
 :each
 (fn [f]
   (let [before @registry/registry]
     (selection/reset-catalog!)
     ;; The picker loads dvergr's resource-backed models itself, so load them
     ;; here rather than registering a stand-in Fireworks entry: a stub would be
     ;; overwritten mid-test by that load, and a test could no longer assert
     ;; that a /models RESPONSE registers nothing.
     (registry/load-models-resource!)
     (try
       (f)
       (finally
         (reset! registry/registry before)
         (selection/reset-catalog!))))))

(defn- with-config [fixture env f]
  (binding [selection/*env-lookup* env
            selection/*provider-base-urls*
            {:openai (str (:base-url fixture) "/openai")
             :fireworks (str (:base-url fixture) "/fireworks")}]
    (selection/reset-catalog!)
    (f)))

(defn- row [rows value]
  (some #(when (= value (:value %)) %) rows))

(defn- curated-values []
  (into #{} (map #(or (:family %) (:model %))) catalog/curated))

(defn- with-unloaded-registry
  "Run `f` against the registry a fresh JVM has before anything loads
   models.edn. dvergr's `ensure-models-loaded!` reads a process-wide flag, so
   stripping the entries is only half of that state — a run whose earlier test
   already loaded the resource would otherwise start from a flag that says the
   work is done."
  [f]
  (let [loaded @registry/registry
        flag @#'registry/models-loaded?
        was-loaded? @flag]
    (try
      (reset! registry/registry
              (into {} (remove #(= :fireworks (:provider (val %)))) loaded))
      (reset! flag false)
      (f)
      (finally
        (reset! registry/registry loaded)
        (reset! flag was-loaded?)))))

(deftest preferred-version-status-keeps-the-preference-selected
  (let [info {:preferred? true
              :preferred-model "gpt-5.5-luna"
              :preferred-family "gpt-*-luna"
              :preferred-version "5.5"
              :preferred-availability {:state :unavailable-to-account
                                       :available? false}
              :model "gpt-5.6-luna"
              :candidate "gpt-5.5-luna"
              :fallback? true
              :available? true}]
    (is (= "5.5 unavailable; using a newer Luna"
           (catalog/preferred-status-copy info)))
    (is (catalog/selected? {:value "gpt-5.5-luna"} info))
    (is (not (catalog/selected? {:value "gpt-5.6-luna"} info))
        "the fallback never replaces the selected preference")))

(deftest preferred-version-without-forward-candidate-has-unavailable-copy
  (is (= "5.6 unavailable; no newer Luna is usable"
         (catalog/preferred-status-copy
          {:preferred? true
           :preferred-family "gpt-*-luna"
           :preferred-version "5.6"
           :preferred-availability {:state :unavailable-to-account
                                    :available? false}
           :fallback? false
           :available? false}))))

(deftest availability-copy-renders-every-state-including-an-unknown-one
  (doseq [state [:available :needs-credential :not-implemented
                 :unavailable-to-account :temporarily-unreachable]]
    (testing state
      (let [copy (catalog/availability-copy "openai" {:state state})]
        (is (string? (:availability-label copy)))
        (is (string? (:availability-explanation copy))))))
  (testing "a selection that resolved to no candidate still renders"
    (is (= {:availability-label "Unavailable"
            :availability-explanation
            "OpenAI could not confirm this model for this account."}
           (catalog/availability-copy "openai" nil)))))

(deftest next-join-copy-never-promises-an-unavailable-resolution
  (is (= "Resolves to glm-5p2 when next joined"
         (catalog/next-join-copy {:available? true :model-short "glm-5p2"})))
  (is (= "Credential required — will not join until this resolves"
         (catalog/next-join-copy {:available? false
                                  :model-short "glm-5p2"
                                  :availability-label "Credential required"})))
  (is (= "Unavailable — will not join until this resolves"
         (catalog/next-join-copy {:available? false}))))

(deftest an-unresolved-selection-has-no-label
  (is (nil? (catalog/model-label nil))
      "a nil id must not match the first curated family entry")
  (is (= "GLM 5.2" (catalog/model-label fireworks-model)))
  (is (= "Qwen3.6 Plus"
         (catalog/model-label "accounts/fireworks/models/qwen3p6-plus"))))

(deftest every-curated-family-and-model-stays-visible-without-credentials
  (fake/with-server
   (fn [fixture]
     (with-config fixture {}
       (fn []
         (let [rows (catalog/choices)
               by-value (into #{} (map :value) rows)]
           (is (every? by-value (curated-values)))
           (is (every? :disabled? rows))
           (is (every? #(= :needs-credential (:availability %)) rows))
           (doseq [[provider env-var]
                   [["openai" "OPENAI_API_KEY"]
                    ["fireworks" "FIREWORKS_API_KEY"]
                    ["anthropic" "ANTHROPIC_API_KEY"]]]
             (let [provider-rows (filter #(= provider (:provider %)) rows)]
               (is (seq provider-rows))
               (is (every? #(= env-var (:credential-source %)) provider-rows))
               (is (every? #(and (str/includes?
                                  (:availability-explanation %) env-var)
                                 (str/includes?
                                  (:availability-explanation %) "restart"))
                           provider-rows))))
           (is (empty? @(:requests fixture))
               "rendering missing-credential rows makes no provider calls")))))))

(deftest fireworks-rows-do-not-wait-for-the-first-agent-turn
  ;; Fireworks model metadata lives in dvergr's resources/models.edn, which
  ;; only provider initialization reads. The picker is reachable before any
  ;; agent has run — opening Settings on a fresh boot — and every Fireworks
  ;; family, the product default included, then read "Not supported" and
  ;; could not be saved.
  (with-unloaded-registry
   (fn []
     (is (nil? (registry/get-model fireworks-model))
         "precondition: no Fireworks metadata is loaded yet")
     (fake/with-server
      (fn [fixture]
        (fake/respond! fixture "/fireworks/models" fireworks-key
                       [fireworks-model])
        (with-config fixture {"FIREWORKS_API_KEY" fireworks-key}
          (fn []
            (let [rows (catalog/choices)
                  latest (row rows "accounts/fireworks/models/glm-*")]
              (is (some? (registry/get-model fireworks-model))
                  "building the picker is what loaded the metadata")
              (is (= :available (:availability latest)))
              (is (= fireworks-model (:resolves latest)))
              (is (= fireworks-model
                     (:resolves (catalog/require-available-choice!
                                 "accounts/fireworks/models/glm-*")))
                  "and the save path accepts it")))))))))

(deftest repeated-picker-renders-keep-a-runtime-registration
  ;; Loading the resource and MERGING it is not the same operation as making
  ;; sure it is loaded. The picker did the first, on every call, so the
  ;; resource's own metadata was written back over anything registered since —
  ;; a runtime or custom entry lost its pricing and name to nothing more than a
  ;; second render of the list, or a second validation of a stored preference.
  (fake/with-server
   (fn [fixture]
     (fake/respond! fixture "/fireworks/models" fireworks-key [fireworks-model])
     (with-config fixture {"FIREWORKS_API_KEY" fireworks-key}
       (fn []
         ;; The first render legitimately loads: this asserts about the ones
         ;; after it.
         (catalog/choices)
         (let [runtime-name "GLM 5.2 (runtime)"
               runtime-pricing {:input 9.99 :output 19.99}]
           (is (not= runtime-pricing
                     (:pricing (registry/get-model fireworks-model)))
               "precondition: the runtime values differ from the resource's")
           (registry/register-model!
            (assoc (registry/get-model fireworks-model)
                   :name runtime-name
                   :pricing runtime-pricing))
           (dotimes [_ 3]
             (let [latest (row (catalog/choices)
                               "accounts/fireworks/models/glm-*")]
               (is (= :available (:availability latest))
                   "and availability still reads from the same registry"))
             (catalog/choice fireworks-model)
             (catalog/require-available-choice!
              "accounts/fireworks/models/glm-*")
             (catalog/require-usable-preference! fireworks-model))
           (let [registered (registry/get-model fireworks-model)]
             (is (= runtime-name (:name registered)))
             (is (= runtime-pricing (:pricing registered))))))))))

(deftest served-registry-and-account-states-share-one-picker-model
  (fake/with-server
   (fn [fixture]
     (fake/respond! fixture "/openai/models" openai-key
                    ["gpt-5.6-luna" "gpt-9.9-luna"])
     (with-config fixture {"OPENAI_API_KEY" openai-key}
       (fn []
         (let [before @registry/registry
               rows (catalog/choices)
               latest (row rows "gpt-*-luna")
               supported (row rows "gpt-5.6-luna")
               unregistered (row rows "gpt-9.9-luna")
               withdrawn (row rows "gpt-*")]
           (testing "served + registered + implemented is the only usable state"
             (is (= :available (:availability latest)))
             (is (= "gpt-5.6-luna" (:resolves latest)))
             (is (:available? supported)))
           (testing "served + unregistered is explicit and never registered"
             (is (= :not-implemented (:availability unregistered)))
             (is (= :registry-missing (:availability-reason unregistered)))
             (is (= "Not supported" (:availability-label unregistered)))
             (is (= (str "simmis has no metadata or adapter for this model, "
                         "so it cannot run here.")
                    (:availability-explanation unregistered)))
             (is (nil? (registry/get-model "gpt-9.9-luna")))
             (is (= before @registry/registry)
                 "GET /models never mutates the registry"))
           (testing "registered + not served is unavailable to this account"
             (is (= :unavailable-to-account (:availability withdrawn)))
             (is (:disabled? withdrawn)))
           (testing "other curated providers remain visible"
             (is (= :needs-credential
                    (:availability (row rows
                                        "accounts/fireworks/models/glm-*"))))
             (is (= :needs-credential
                    (:availability (row rows "claude-sonnet-4-6")))))))))))

(deftest transient-failure-retains-last-known-rows-but-disables-them
  (fake/with-server
   (fn [fixture]
     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.6-luna"])
     (with-config fixture {"OPENAI_API_KEY" openai-key}
       (fn []
         (let [before (catalog/choices)]
           (is (= :available (:availability (row before "gpt-*-luna"))))
           (fake/outage! fixture "/openai/models" openai-key)
           (selection/catalog true)
           (let [after (catalog/choices)
                 luna (row after "gpt-*-luna")]
             (is (every? (into #{} (map :value) after) (curated-values)))
             (is (= :temporarily-unreachable (:availability luna)))
             (is (:disabled? luna))
             (is (str/includes? (:availability-explanation luna)
                                "Last-known status is retained"))
             (is (= "gpt-5.6-luna" (:resolves luna))))))))))

(deftest an-outage-does-not-relabel-a-family-as-unsupported
  (fake/with-server
   (fn [fixture]
     (let [unregistered "accounts/fireworks/models/glm-9p9"]
       (fake/respond! fixture "/fireworks/models" fireworks-key
                      [fireworks-model unregistered])
       (with-config fixture {"FIREWORKS_API_KEY" fireworks-key}
         (fn []
           (let [latest #(row (catalog/choices)
                              "accounts/fireworks/models/glm-*")]
             (is (= :available (:availability (latest))))
             (is (= fireworks-model (:resolves (latest))))
             (fake/outage! fixture "/fireworks/models" fireworks-key)
             (selection/catalog true)
             (is (= :temporarily-unreachable (:availability (latest))))
             (is (= fireworks-model (:resolves (latest)))
                 "the Latest row keeps naming the newest version simmis can run")
             (is (= :not-implemented
                    (:availability (row (catalog/choices) unregistered)))
                 "a served but unregistered version stays unsupported"))))))))

(deftest identical-endpoint-urls-retain-provider-provenance
  (fake/with-server
   (fn [{:keys [base-url] :as fixture}]
     (let [shared-base (str base-url "/shared")]
       (fake/respond! fixture "/shared/models" openai-key ["gpt-5.6-luna"])
       (fake/respond! fixture "/shared/models" fireworks-key [fireworks-model])
       (binding [selection/*env-lookup* {"OPENAI_API_KEY" openai-key
                                        "OPENAI_BASE_URL" shared-base
                                        "FIREWORKS_API_KEY" fireworks-key}
                 selection/*provider-base-urls* {:openai shared-base
                                                 :fireworks shared-base}]
         (selection/reset-catalog!)
         (let [available (filterv :available? (catalog/choices))
               by-provider (group-by :provider available)]
           (is (= #{"openai" "fireworks"} (set (keys by-provider))))
           (is (every? #(= shared-base (:base-url %)) available))
           (is (every? #(= "OPENAI_API_KEY" (:credential-source %))
                       (get by-provider "openai")))
           (is (every? #(= "FIREWORKS_API_KEY" (:credential-source %))
                       (get by-provider "fireworks")))))))))
