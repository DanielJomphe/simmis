(ns is.simm.model.model-selection-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.model.registry :as registry]
            [is.simm.model.fake-models-server :as fake]
            [is.simm.model.model-selection :as selection]))

(def ^:private openai-key "fixture-openai-key")
(def ^:private fireworks-key "fixture-fireworks-key")

(use-fixtures
  :each
  (fn [f]
    (let [before @registry/registry]
      (selection/reset-catalog!)
      (registry/register-model!
       {:id "accounts/fireworks/models/glm-5p2"
        :name "GLM 5.2"
        :provider :fireworks
        :api-type :openai-chat
        :capabilities #{:tools :streaming :system-prompt}
        :context 131072
        :max-output 8192
        :pricing {:input 1 :output 1}
        :quirks {}})
      (try (f)
           (finally
             (reset! registry/registry before)
             (selection/reset-catalog!))))))

(defn- fixture-bases [base-url]
  {:openai (str base-url "/openai")
   :fireworks (str base-url "/fireworks")})

(defn- with-config [fixture env f]
  (binding [selection/*env-lookup* env
            selection/*provider-base-urls* (fixture-bases (:base-url fixture))]
    (selection/reset-catalog!)
    (f)))

(defn- by-provider [records]
  (into {} (map (juxt :provider identity)) records))

(defn- catalog-facts [record]
  (select-keys record [:provider :base-url :credential-source :reachability
                       :reachable? :model-id :endpoint-kind :native-openai?]))

(deftest version-family-and-ordering
  (is (= "5.6" (selection/version-of "gpt-5.6-luna")))
  (is (= "gpt-*-luna" (selection/family-of "gpt-5.6-luna")))
  (is (= "accounts/fireworks/models/glm-*"
         (selection/family-of "accounts/fireworks/models/glm-5p2"))))

(deftest pure-availability-state-matrix
  (let [base {:provider-known? true
              :credential-present? true
              :registered? true
              :implemented? true
              :catalog-required? true
              :catalog-reachability :reachable
              :served? true}
        cases [["served + registered + implemented"
                {}
                :available]
               ["missing provider credential"
                {:credential-present? false}
                :needs-credential]
               ["served + unregistered"
                {:registered? false}
                :not-implemented]
               ["registered + adapter gap"
                {:implemented? false}
                :not-implemented]
               ["registered + not served"
                {:served? false}
                :unavailable-to-account]
               ["neither served nor registered"
                {:registered? false :served? false}
                :not-implemented]
               ["transient catalog failure"
                {:catalog-reachability :temporarily-unreachable}
                :temporarily-unreachable]
               ["provider without a catalog endpoint"
                {:catalog-required? false
                 :catalog-reachability :not-required
                 :served? false}
                :available]
               ["unknown provider/adapter"
                {:provider-known? false}
                :not-implemented]]]
    (doseq [[label overrides expected] cases]
      (testing label
        (is (= expected
               (selection/availability-state (merge base overrides)))))))

  (is (= :registry-missing
         (selection/availability-reason
          {:provider-known? true :credential-present? true
           :registered? false :implemented? false
           :catalog-required? true :catalog-reachability :reachable
           :served? true})))
  (is (= :adapter-missing
         (selection/availability-reason
          {:provider-known? true :credential-present? true
           :registered? true :implemented? false
           :catalog-required? true :catalog-reachability :reachable
           :served? true}))))

(defn- availability-stub [usable seen]
  (fn
    ([id]
     ((availability-stub usable seen) (selection/infer-provider id) id))
    ([provider id]
     (swap! seen conj [provider id])
     (let [available? (contains? @usable [(keyword provider) id])]
       {:state (if available? :available :unavailable-to-account)
        :available? available?
        :provider (keyword provider)
        :model-id id}))))

(deftest latest-stores-a-family-and-upgrades-to-the-newest-usable-version
  (let [versions (atom ["5.5"])
        usable (atom #{[:openai "gpt-5.5-luna"]})
        seen (atom [])]
    (with-redefs [selection/known-versions-in (fn [_ _] @versions)
                  selection/model-availability (availability-stub usable seen)]
      (let [selection {:provider :openai
                       :family "gpt-*-luna"
                       :version :auto}
            before (selection/resolve-selection selection)]
        (is (= :latest (:selection-kind before)))
        (is (= "gpt-5.5-luna" (:model before)))
        (is (false? (:preferred? before)))
        (reset! versions ["5.6" "5.5"])
        (swap! usable conj [:openai "gpt-5.6-luna"])
        (let [after (selection/resolve-selection selection)]
          (is (= "gpt-5.6-luna" (:model after)))
          (is (= "gpt-*-luna" (:family selection))
              "Latest remains a stored family rather than becoming a version"))))))

(deftest preferred-version-remains-selected-while-usable
  (let [usable (atom #{[:openai "gpt-5.5-luna"]})
        seen (atom [])]
    (with-redefs [selection/known-versions-in (fn [_ _] ["5.6" "5.5"])
                  selection/model-availability (availability-stub usable seen)]
      (let [result (selection/resolve-selection
                    {:provider :openai :model "gpt-5.5-luna"})]
        (is (= :preferred-version (:selection-kind result)))
        (is (= "gpt-5.5-luna" (:preferred-model result)))
        (is (= "gpt-5.5-luna" (:candidate result)))
        (is (= "gpt-5.5-luna" (:model result)))
        (is (false? (:fallback? result)))
        (is (:available? result))))))

(deftest withdrawn-preferred-version-falls-forward-within-family
  (let [usable (atom #{[:openai "gpt-5.6-luna"]})
        seen (atom [])]
    (with-redefs [selection/known-versions-in (fn [provider family]
                                               (is (= :openai provider))
                                               (is (= "gpt-*-luna" family))
                                               ["5.6" "5.5"])
                  selection/model-availability (availability-stub usable seen)]
      (let [result (selection/resolve-selection
                    {:provider :openai
                     :family "gpt-*-luna"
                     :version "5.5"})]
        (is (= "gpt-5.5-luna" (:preferred-model result)))
        (is (= :unavailable-to-account
               (get-in result [:preferred-availability :state])))
        (is (= "gpt-5.6-luna" (:model result)))
        (is (= "gpt-5.6-luna" (:fallback-model result)))
        (is (= :preferred-version-unavailable (:fallback-reason result)))
        (is (:fallback? result))
        (is (:available? result))
        (is (= :available (get-in result [:resolved-availability :state])))))))

(deftest unavailable-preferred-version-with-no-newer-candidate-is-unavailable
  (let [usable (atom #{[:openai "gpt-5.4-luna"]
                       [:fireworks selection/default-model]})
        seen (atom [])]
    (with-redefs [selection/known-versions-in (fn [_ _] ["5.6" "5.5" "5.4"])
                  selection/model-availability (availability-stub usable seen)]
      (let [result (selection/resolve-selection
                    {:provider :openai :model "gpt-5.5-luna"})]
        (is (= "gpt-5.5-luna" (:preferred-model result)))
        (is (nil? (:model result)))
        (is (false? (:fallback? result)))
        (is (false? (:available? result)))
        (is (not-any? #(= [:openai "gpt-5.4-luna"] %) @seen)
            "an older usable version is never considered")
        (is (not-any? #(= [:fireworks selection/default-model] %) @seen)
            "the product fallback is never considered")))))

(deftest preferred-fallback-refuses-other-families
  (let [usable (atom #{[:openai "gpt-5.6-sol"]
                       [:fireworks selection/default-model]})
        seen (atom [])]
    (with-redefs [selection/known-versions-in
                  (fn [provider family]
                    (is (= [:openai "gpt-*-luna"] [provider family]))
                    ["5.5"])
                  selection/model-availability (availability-stub usable seen)]
      (let [result (selection/resolve-selection
                    {:provider :openai :model "gpt-5.5-luna"})]
        (is (nil? (:model result)))
        (is (every? #(= "gpt-5.5-luna" (second %)) @seen))
        (is (false? (:available? result)))))))

(deftest preferred-fallback-is-provider-isolated
  (let [usable (atom #{[:fireworks "gpt-5.6-luna"]})
        seen (atom [])]
    (with-redefs [selection/known-versions-in
                  (fn [provider family]
                    (is (= [:openai "gpt-*-luna"] [provider family]))
                    ["5.6" "5.5"])
                  selection/model-availability (availability-stub usable seen)]
      (let [result (selection/resolve-selection
                    {:provider :openai :model "gpt-5.5-luna"})]
        (is (nil? (:model result)))
        (is (false? (:fallback? result)))
        (is (every? #(= :openai (first %)) @seen)
            "another provider's same-looking id is never consulted")))))

(deftest preferred-version-recovers-automatically-when-it-reappears
  (let [usable (atom #{[:openai "gpt-5.6-luna"]})
        seen (atom [])
        selection {:provider :openai :model "gpt-5.5-luna"}]
    (with-redefs [selection/known-versions-in (fn [_ _] ["5.6" "5.5"])
                  selection/model-availability (availability-stub usable seen)]
      (let [during-withdrawal (selection/resolve-selection selection)]
        (is (= "gpt-5.5-luna" (:preferred-model during-withdrawal)))
        (is (= "gpt-5.6-luna" (:model during-withdrawal)))
        (is (:fallback? during-withdrawal)))
      (swap! usable conj [:openai "gpt-5.5-luna"])
      (let [after-recovery (selection/resolve-selection selection)]
        (is (= "gpt-5.5-luna" (:preferred-model after-recovery)))
        (is (= "gpt-5.5-luna" (:model after-recovery)))
        (is (false? (:fallback? after-recovery)))
        (is (= :available
               (get-in after-recovery [:preferred-availability :state])))
        (is (= {:provider :openai :model "gpt-5.5-luna"} selection)
            "fallback is computed, never persisted over the preference")))))

(deftest latest-names-the-newest-supported-version-when-none-is-usable
  ;; Under an outage or a missing credential nothing in the family is usable,
  ;; but the Latest row still has to name something. Naming the newest KNOWN
  ;; version picked up ids the provider serves and dvergr does not implement,
  ;; which reported a reachability problem as "Not supported".
  (binding [selection/*env-lookup* {}]
    (with-redefs [selection/known-versions-in (fn [& _] ["9p9" "5p2"])]
      (let [result (selection/resolve-selection
                    {:provider :fireworks
                     :family "accounts/fireworks/models/glm-*"
                     :version :auto})]
        (is (= "accounts/fireworks/models/glm-5p2" (:candidate result))
            "an unregistered newer version never becomes the named candidate")
        (is (nil? (:model result)))
        (is (false? (:available? result)))
        (is (= :needs-credential (get-in result [:availability :state])))))))

(deftest a-family-with-no-known-version-still-reports-a-state
  ;; A stored Latest preference can outlive the registry entries behind it.
  ;; The resolver used to answer that with no availability at all, and every
  ;; display surface then had nothing to render.
  (binding [selection/*env-lookup* {"OPENAI_API_KEY" openai-key}]
    (with-redefs [selection/known-versions-in (fn [_ _] [])
                  selection/provider-catalog-status
                  (constantly {:reachability :reachable :served-model-ids #{}})]
      (let [result (selection/resolve-selection
                    {:provider :openai :family "gpt-*-luna" :version :auto})]
        (is (= :latest (:selection-kind result)))
        (is (nil? (:candidate result)))
        (is (nil? (:model result)))
        (is (false? (:available? result)))
        (is (= :not-implemented (get-in result [:availability :state])))
        (is (= :registry-missing (get-in result [:availability :reason])))))))

(deftest no-keys-do-not-invent-availability
  (fake/with-server
   (fn [fixture]
     (with-config fixture {}
       (fn []
         (is (empty? (selection/provider-endpoints)))
         (is (= [] (selection/catalog true)))
         (is (= [] (selection/available-catalog)))
         (is (empty? @(:requests fixture))))))))

(deftest each-provider-key-is-used-alone
  (fake/with-server
   (fn [{:keys [base-url requests] :as fixture}]
     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.6-luna"])
     (with-config fixture {"OPENAI_API_KEY" openai-key}
       (fn []
         (let [records (selection/catalog true)]
           (is (= [{:provider :openai
                    :base-url (str base-url "/openai")
                    :credential-source "OPENAI_API_KEY"
                    :reachability :reachable
                    :reachable? true
                    :model-id "gpt-5.6-luna"
                    :endpoint-kind :openai-native
                    :native-openai? true}]
                  (mapv catalog-facts records)))
           (is (not-any? #(contains? % :credential) records)))))
     (reset! requests [])
     (fake/respond! fixture "/fireworks/models" fireworks-key
                    ["accounts/fireworks/models/glm-5p2"])
     (with-config fixture {"FIREWORKS_API_KEY" fireworks-key}
       (fn []
         (let [records (selection/catalog true)]
           (is (= [{:provider :fireworks
                    :base-url (str base-url "/fireworks")
                    :credential-source "FIREWORKS_API_KEY"
                    :reachability :reachable
                    :reachable? true
                    :model-id "accounts/fireworks/models/glm-5p2"
                    :endpoint-kind :openai-compatible
                    :native-openai? false}]
                  (mapv catalog-facts records)))
           (is (not-any? #(contains? % :credential) records)))))
     (is (= [{:path "/fireworks/models"
              :authorization (str "Bearer " fireworks-key)}]
            @requests)))))

(deftest both-provider-keys-remain-scoped
  (fake/with-server
   (fn [{:keys [requests] :as fixture}]
     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.6-luna"])
     (fake/respond! fixture "/fireworks/models" fireworks-key
                    ["accounts/fireworks/models/glm-5p2"])
     (with-config fixture {"OPENAI_API_KEY" openai-key
                           "FIREWORKS_API_KEY" fireworks-key}
       (fn []
         (let [records (selection/catalog true)]
           (is (= #{:openai :fireworks} (set (map :provider records))))
           (is (= {"/openai/models" (str "Bearer " openai-key)
                   "/fireworks/models" (str "Bearer " fireworks-key)}
                  (into {} (map (juxt :path :authorization)) @requests)))))))))

(deftest custom-openai-base-is-compatible-not-native
  (fake/with-server
   (fn [{:keys [base-url requests] :as fixture}]
     (let [custom-base (str base-url "/compatible")]
       (fake/respond! fixture "/compatible/models" openai-key ["gpt-5.5"])
       (with-config fixture {"OPENAI_API_KEY" openai-key
                             "OPENAI_BASE_URL" custom-base}
         (fn []
           (let [record (first (selection/catalog true))]
             (is (= custom-base (:base-url record)))
             (is (= :openai (:provider record)))
             (is (= :openai-compatible (:endpoint-kind record)))
             (is (false? (:native-openai? record)))
             (is (= [{:path "/compatible/models"
                      :authorization (str "Bearer " openai-key)}]
                    @requests)))))))))

(deftest identical-urls-do-not-collapse-provider-records
  (fake/with-server
   (fn [{:keys [base-url requests] :as fixture}]
     (let [shared-base (str base-url "/shared")]
       (fake/respond! fixture "/shared/models" openai-key ["gpt-5.5"])
       (fake/respond! fixture "/shared/models" fireworks-key
                      ["accounts/fireworks/models/glm-5p2"])
       (binding [selection/*env-lookup* {"OPENAI_API_KEY" openai-key
                                        "OPENAI_BASE_URL" shared-base
                                        "FIREWORKS_API_KEY" fireworks-key}
                 selection/*provider-base-urls* {:openai shared-base
                                                 :fireworks shared-base}]
         (let [records (by-provider (selection/catalog true))]
           (is (= #{:openai :fireworks} (set (keys records))))
           (is (= shared-base (:base-url (:openai records))))
           (is (= shared-base (:base-url (:fireworks records))))
           (is (= "OPENAI_API_KEY" (:credential-source (:openai records))))
           (is (= "FIREWORKS_API_KEY" (:credential-source (:fireworks records))))
           (is (= #{(str "Bearer " openai-key) (str "Bearer " fireworks-key)}
                  (set (map :authorization @requests))))))))))

(deftest partial-outage-preserves-only-that-providers-last-good-state
  (fake/with-server
   (fn [fixture]
     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.5"])
     (fake/respond! fixture "/fireworks/models" fireworks-key
                    ["accounts/fireworks/models/glm-5p2"])
     (with-config fixture {"OPENAI_API_KEY" openai-key
                           "FIREWORKS_API_KEY" fireworks-key}
       (fn []
         (selection/catalog true)
         (fake/respond! fixture "/openai/models" openai-key ["gpt-5.6-luna"])
         (fake/outage! fixture "/fireworks/models" fireworks-key)
         (let [records (by-provider (selection/catalog true))]
           (is (= "gpt-5.6-luna" (:model-id (:openai records))))
           (is (= :reachable (:reachability (:openai records))))
           (is (= "accounts/fireworks/models/glm-5p2"
                  (:model-id (:fireworks records))))
           (is (= :unreachable (:reachability (:fireworks records))))
           (is (= ["gpt-5.6-luna"]
                  (mapv :model-id (selection/available-catalog))))
           (is (= #{"accounts/fireworks/models/glm-5p2"}
                  (:served-model-ids
                   (selection/provider-catalog-status :fireworks)))
               "last-known served evidence is retained")
           (is (= :temporarily-unreachable
                  (:state
                   (selection/model-availability
                    :fireworks "accounts/fireworks/models/glm-5p2"))))
           (is (nil? (selection/resolve-model
                      {:provider :fireworks
                       :model "accounts/fireworks/models/glm-5p2"})))))))))

(deftest total-outage-retains-history-without-claiming-availability
  (fake/with-server
   (fn [fixture]
     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.5"])
     (fake/respond! fixture "/fireworks/models" fireworks-key
                    ["accounts/fireworks/models/glm-5p2"])
     (with-config fixture {"OPENAI_API_KEY" openai-key
                           "FIREWORKS_API_KEY" fireworks-key}
       (fn []
         (selection/catalog true)
         (fake/outage! fixture "/openai/models" openai-key)
         (fake/outage! fixture "/fireworks/models" fireworks-key)
         (let [records (selection/catalog true)]
           (is (= 2 (count records)))
           (is (every? #(= :unreachable (:reachability %)) records))
           (is (= [] (selection/available-catalog)))
           (is (= [] (selection/versions-in "gpt-*-luna"))))
         (selection/reset-catalog!)
         (is (= [] (selection/catalog true))
             "an outage with no last-known-good state invents no model"))))))
