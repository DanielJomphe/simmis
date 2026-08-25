(ns is.simm.model.agent-model-inheritance-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [dvergr.chat.schema :as chat-schema]
            [is.simm.agents.room-agents :as room-agents]
            [is.simm.agents.templates :as templates]
            [is.simm.model.model-catalog :as catalog]
            [is.simm.model.model-selection :as selection]
            [is.simm.model.parties :as parties]
            [is.simm.model.system-db :as system-db]
            [is.simm.uis.web.desktop.chat-remote :as chat-remote]))

(def ^:dynamic *conn* nil)

(use-fixtures
 :once
 (fn [run]
   (let [cfg {:store {:backend :memory :id (random-uuid)}
              :schema-flexibility :write
              :keep-history? false}]
     (d/create-database cfg)
     (let [conn (d/connect cfg)]
       (chat-schema/ensure-full-schema! conn)
       (d/transact conn system-db/schema)
       (binding [*conn* conn]
         (with-redefs [system-db/get-conn (constantly conn)]
           (run)))
       (d/release conn)))))

(defn- seed-owner!
  ([] (seed-owner! nil))
  ([preferred-model]
   (let [party-id (random-uuid)]
     (d/transact *conn*
                 [(cond-> {:actor/id (parties/party-id->actor-id party-id)
                           :actor/kind :human
                           :actor/name "Owner"
                           :actor/status :online
                           :actor/created-at (java.util.Date.)
                           :party/id party-id}
                    preferred-model
                    (assoc :party/preferred-model preferred-model))])
     party-id)))

(defn- stored-config [party-id]
  (some-> (d/q '[:find ?config .
                 :in $ ?actor-id
                 :where [?e :actor/id ?actor-id]
                        [?e :actor/config ?config]]
               @*conn* (parties/party-id->actor-id party-id))
          edn/read-string))

(defn- rejection [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest creation-inherits-unless-an-override-was-explicit
  (let [owner-id (seed-owner! "gpt-*-luna")
        existing (parties/create-agent!
                  owner-id
                  {:display-name "Preferred"
                   :model "gpt-5.5"
                   :provider :openai
                   :template :researcher})
        existing-before (stored-config (:party/id existing))
        inherited (parties/create-agent!
                   owner-id
                   {:display-name "Inherited"
                    :template :secretary})
        explicit-family (parties/create-agent!
                         owner-id
                         {:display-name "Family"
                          :model-family "gpt-*-luna"})]
    (testing "a new ordinary agent stores no model selection"
      (is (= {:auto-respond? true :template :secretary}
             (stored-config (:party/id inherited))))
      (is (not-any? #(contains? inherited %)
                    [:party/model :party/model-family :party/model-version])))
    (testing "a deliberate family choice is still stored"
      (is (= "gpt-*-luna" (:model-family
                            (stored-config (:party/id explicit-family)))))
      (is (= :auto (:model-version
                    (stored-config (:party/id explicit-family))))))
    (testing "creating an inheriting agent does not rewrite an existing agent"
      (is (= existing-before (stored-config (:party/id existing))))
      (is (= "gpt-5.5" (:model existing-before)))
      (is (= :openai (:provider existing-before))))))

(deftest persona-options-never-copy-template-model-metadata
  (let [opts (templates/agent-options
              "Researcher"
              {:id :researcher
               :system-prompt "Research carefully."
               :model "gpt-5.5"
               :model-family "gpt-*-luna"
               :model-version :auto
               :provider :openai})]
    (is (= {:display-name "Researcher"
            :auto-respond? true
            :template :researcher
            :system-prompt "Research carefully."}
           opts))))

(deftest explicit-override-and-clearing-round-trip-through-storage
  (let [owner-choice "gpt-*-luna"
        owner-id (seed-owner! owner-choice)
        agent (parties/create-agent! owner-id {:display-name "Agent"})
        agent-id (str (:party/id agent))
        validated (atom [])]
    (with-redefs [catalog/require-available-choice!
                  (fn [value]
                    (swap! validated conj value)
                    {:value value :available? true})
                  catalog/require-usable-preference!
                  (fn [value]
                    (swap! validated conj value)
                    {:value value :available? true})
                  room-agents/describe-model identity]
      (testing "an exact override stores only the exact model form"
        (chat-remote/update-agent-config-server agent-id "" "gpt-5.5" nil)
        (is (= "gpt-5.5" (:model (stored-config (:party/id agent)))))
        (is (not (contains? (stored-config (:party/id agent)) :model-family))))
      (testing "a family override replaces the exact form"
        (chat-remote/update-agent-config-server agent-id "" "gpt-*-luna" nil)
        (is (= "gpt-*-luna" (:model-family
                              (stored-config (:party/id agent)))))
        (is (= :auto (:model-version
                      (stored-config (:party/id agent)))))
        (is (not (contains? (stored-config (:party/id agent)) :model))))
      (testing "the inheritance row validates the owner choice then removes all override keys"
        (chat-remote/update-agent-config-server
         agent-id "" catalog/inherit-choice-value nil)
        (is (= ["gpt-5.5" "gpt-*-luna" owner-choice] @validated))
        (is (= {:auto-respond? true}
               (stored-config (:party/id agent))))))))

(deftest owner-without-preference-inherits-the-validated-product-default
  (let [owner-id (seed-owner!)
        agent (parties/create-agent!
               owner-id {:display-name "Preferred" :model "gpt-5.5"})
        validated (atom [])]
    (with-redefs [catalog/require-available-choice!
                  (fn [value]
                    (swap! validated conj value)
                    {:value value :available? true})
                  catalog/require-usable-preference!
                  (fn [value]
                    (swap! validated conj value)
                    {:value value :available? true})
                  room-agents/describe-model identity]
      (chat-remote/update-agent-config-server
       (str (:party/id agent)) "" catalog/inherit-choice-value nil))
    (is (= [parties/default-model] @validated))
    (is (= {:auto-respond? true}
           (stored-config (:party/id agent))))))

(deftest unavailable-owner-preference-cannot-clear-an-override
  (let [owner-choice "gpt-*-luna"
        owner-id (seed-owner! owner-choice)
        agent (parties/create-agent!
               owner-id {:display-name "Preferred" :model "gpt-5.5"})
        before (stored-config (:party/id agent))]
    (with-redefs [catalog/require-usable-preference!
                  (fn [value]
                    (throw (ex-info "Model choice is unavailable"
                                    {:type :model-choice-unavailable
                                     :model-choice value
                                     :availability :needs-credential})))]
      (let [data (rejection
                  #(chat-remote/update-agent-config-server
                    (str (:party/id agent)) "Renamed"
                    catalog/inherit-choice-value "Changed prompt"))]
        (is (= :model-choice-unavailable (:type data)))
        (is (= owner-choice (:model-choice data)))
        (is (= :needs-credential (:availability data)))))
    (is (= before (stored-config (:party/id agent)))
        "model, name, and prompt remain unchanged when inheritance is invalid")))

(deftest picker-row-and-summary-distinguish-inherited-from-explicit
  (binding [selection/*env-lookup* {}]
    (selection/reset-catalog!)
    (let [owner-id (seed-owner! "gpt-*-luna")
          inherited (parties/create-agent! owner-id {:display-name "Inherited"})
          explicit (parties/create-agent!
                    owner-id {:display-name "Explicit" :model "gpt-5.5"})
          inherited-info (room-agents/describe-model inherited)
          explicit-info (room-agents/describe-model explicit)
          inherit-row (:inheritance-choice inherited-info)]
      (testing "inheritance is a selected state, not an implicit explicit model"
        (is (false? (:configured? inherited-info)))
        (is (:inherited? inherited-info))
        (is (= :owner-preference (:selection-source inherited-info)))
        (is (= "Inherited from owner preference" (:selection-label inherited-info))))
      (testing "the first-class row names and validates the resolved owner preference"
        (is (= catalog/inherit-choice-value (:value inherit-row)))
        (is (= "Use owner preference — GPT Luna (Latest)" (:label inherit-row)))
        (is (= :owner-preference (:inheritance-source inherit-row)))
        (is (false? (:available? inherit-row))))
      (testing "an existing explicit choice stays visibly explicit"
        (is (:configured? explicit-info))
        (is (false? (:inherited? explicit-info)))
        (is (= :agent-override (:selection-source explicit-info)))
        (is (= "Explicit override" (:selection-label explicit-info)))))))

(deftest owner-without-preference-is-labelled-as-product-default-inheritance
  (binding [selection/*env-lookup* {}]
    (selection/reset-catalog!)
    (let [owner-id (seed-owner!)
          agent (parties/create-agent! owner-id {:display-name "Inherited"})
          info (room-agents/describe-model agent)]
      (is (false? (:configured? info)))
      (is (:inherited? info))
      (is (= :product-default (:selection-source info)))
      (is (= "Inherited from product default" (:selection-label info)))
      (is (re-find #"^Use owner preference — not set; product default "
                   (get-in info [:inheritance-choice :label]))))))
