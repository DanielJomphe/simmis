(ns is.simm.model.model-selection-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [is.simm.model.fake-models-server :as fake]
            [is.simm.model.model-selection :as selection]))

(def ^:private openai-key "fixture-openai-key")
(def ^:private fireworks-key "fixture-fireworks-key")

(use-fixtures
  :each
  (fn [f]
    (selection/reset-catalog!)
    (try (f) (finally (selection/reset-catalog!)))))

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
                  (mapv :model-id (selection/available-catalog))))))))))

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
