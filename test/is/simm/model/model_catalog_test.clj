(ns is.simm.model.model-catalog-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
      (registry/register-model!
       {:id fireworks-model
        :name "GLM 5.2"
        :provider :fireworks
        :api-type :openai-chat
        :capabilities #{:tools :streaming :system-prompt}
        :context 131072
        :max-output 8192
        :pricing {:input 1 :output 1}
        :quirks {}})
      (try
        (f)
        (finally
          (reset! registry/registry before)
          (selection/reset-catalog!))))))

(defn- endpoint-choices []
  (filterv #(#{"openai" "fireworks"} (:provider %)) (catalog/choices)))

(defn- with-config [fixture env f]
  (binding [selection/*env-lookup* env
            selection/*provider-base-urls*
            {:openai (str (:base-url fixture) "/openai")
             :fireworks (str (:base-url fixture) "/fireworks")}]
    (selection/reset-catalog!)
    (f)))

(deftest picker-follows-provider-availability-and-retains-provenance
  (fake/with-server
   (fn [{:keys [base-url] :as fixture}]
     (with-config fixture {}
       #(is (= [] (endpoint-choices)) "no keys offer no endpoint models"))

     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.6-luna"])
     (with-config fixture {"OPENAI_API_KEY" openai-key}
       (fn []
         (let [rows (endpoint-choices)]
           (is (= #{"openai"} (set (map :provider rows))))
           (is (every? #(= "OPENAI_API_KEY" (:credential-source %)) rows))
           (is (every? #(= (str base-url "/openai") (:base-url %)) rows))
           (is (every? :reachable? rows))
           (is (every? :model-id rows)))))

     (fake/respond! fixture "/fireworks/models" fireworks-key [fireworks-model])
     (with-config fixture {"FIREWORKS_API_KEY" fireworks-key}
       (fn []
         (let [rows (endpoint-choices)]
           (is (= #{"fireworks"} (set (map :provider rows))))
           (is (every? #(= "FIREWORKS_API_KEY" (:credential-source %)) rows))
           (is (every? #(= :openai-compatible (:endpoint-kind %)) rows)))))

     (with-config fixture {"OPENAI_API_KEY" openai-key
                           "FIREWORKS_API_KEY" fireworks-key}
       #(is (= #{"openai" "fireworks"}
               (set (map :provider (endpoint-choices)))))))))

(deftest picker-distinguishes-custom-and-identical-endpoints
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
         (let [rows (endpoint-choices)
               by-provider (group-by :provider rows)]
           (is (= #{"openai" "fireworks"} (set (keys by-provider))))
           (is (every? #(= shared-base (:base-url %)) rows))
           (is (every? #(= :openai-compatible (:endpoint-kind %)) rows))
           (is (every? (complement :native-openai?) (get by-provider "openai")))
           (is (every? #(= "OPENAI_API_KEY" (:credential-source %))
                       (get by-provider "openai")))
           (is (every? #(= "FIREWORKS_API_KEY" (:credential-source %))
                       (get by-provider "fireworks")))))))))

(deftest outages-are-not-picker-availability
  (fake/with-server
   (fn [fixture]
     (fake/respond! fixture "/openai/models" openai-key ["gpt-5.6-luna"])
     (fake/respond! fixture "/fireworks/models" fireworks-key [fireworks-model])
     (with-config fixture {"OPENAI_API_KEY" openai-key
                           "FIREWORKS_API_KEY" fireworks-key}
       (fn []
         (is (= #{"openai" "fireworks"}
                (set (map :provider (endpoint-choices)))))
         (fake/outage! fixture "/fireworks/models" fireworks-key)
         (selection/catalog true)
         (is (= #{"openai"} (set (map :provider (endpoint-choices))))
             "partial outage removes only the unreachable provider")
         (is (= :unreachable
                (:reachability
                 (first (filter #(= :fireworks (:provider %))
                                (selection/catalog)))))
             "last-known Fireworks state remains explicit")
         (fake/outage! fixture "/openai/models" openai-key)
         (selection/catalog true)
         (is (= [] (endpoint-choices))
             "total outage leaves no endpoint model available"))))))
