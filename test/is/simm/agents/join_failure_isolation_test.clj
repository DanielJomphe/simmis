(ns is.simm.agents.join-failure-isolation-test
  "BUG-B: one agent with an unavailable model must not block the room.

   `ensure-agent-joined!` fails closed when a participant's model cannot run —
   that is deliberate. This covers the caller: `post-user-message!` isolates
   the failure per agent, so the human's message is posted, the healthy agents
   still join and answer, and the failure is visible in both channels (a
   Telemere :warn for the operator, a room note for the person watching the
   silence)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [is.simm.agents.room-agents :as room-agents]
            [is.simm.model.parties :as parties]
            [is.simm.model.rooms :as rooms]
            [taoensso.telemere :as tel]))

(defn- room-agents-var [sym]
  (ns-resolve 'is.simm.agents.room-agents sym))

(def ^:private var-vor (random-uuid))
(def ^:private sol (random-uuid))
(def ^:private lun (random-uuid))
(def ^:private sender (random-uuid))

(defn- agent-party [id nm]
  {:party/id id :party/display-name nm :party/type :agent :party/auto-respond? true})

(def ^:private agents
  [(agent-party var-vor "Vár") (agent-party sol "Sol") (agent-party lun "Lun")])

(defn- unavailable-model-ex [agent-id]
  (ex-info "Agent model is unavailable"
           {:type :model-unavailable
            :agent-id agent-id
            :model "accounts/fireworks/models/qwen3p6-plus"
            :provider :fireworks
            :availability :unavailable-to-account
            :availability-reason nil
            :availability-label "Unavailable to account"
            :availability-explanation
            "Fireworks does not make this model available to this account."}))

(defn- run-send
  "Run `post-user-message!` against stubbed room plumbing, with `joinable` the
   agents whose join succeeds. Returns {:result :posted :joined :signals}."
  [text joinable]
  (let [room-uuid (random-uuid)
        room {:ctx nil :id :test-room}
        posted (atom [])
        joined (atom [])
        {:keys [value signals]}
        (tel/with-signals
          (with-redefs-fn
            {(room-agents-var 'ensure-providers!) (constantly nil)
             #'rooms/get-room-agents (constantly agents)
             #'parties/get-party (constantly {:party/id sender
                                              :party/type :human
                                              :party/display-name "You"})
             #'room-agents/live-room (constantly room)
             #'room-agents/ensure-room-party-entity! (constantly nil)
             #'room-agents/ensure-room-projector! (constantly nil)
             #'room-agents/ensure-agent-joined!
             (fn [_room _room-uuid agent _conn]
               (if (contains? (set joinable) (:party/id agent))
                 (swap! joined conj (:party/id agent))
                 (throw (unavailable-model-ex (:party/id agent)))))
             #'d/room-target (constantly nil)
             #'d/post! (fn [_room msg] (swap! posted conj msg) msg)}
            (fn [] (room-agents/post-user-message! room-uuid text sender nil))))]
    {:result value
     :room-uuid room-uuid
     :posted @posted
     :joined @joined
     :signals signals}))

(defn- user-messages [posted text]
  (filter #(= text (:content %)) posted))

(defn- notes [posted text]
  (remove #(= text (:content %)) posted))

(deftest one-unavailable-agent-does-not-block-the-room
  (let [text "status please"
        {:keys [result posted joined signals]} (run-send text [var-vor sol])]

    (testing "the user's message is posted anyway"
      (is (= :ok (:status result)))
      (let [sent (user-messages posted text)]
        (is (= 2 (count sent)) "one copy per JOINED recipient")
        (is (= 1 (count (set (map :id sent))))
            "all copies share one id so the projector dedupes them")
        (is (= #{(room-agents/party->actor-kw var-vor)
                 (room-agents/party->actor-kw sol)}
               (set (map :to sent))))))

    (testing "the healthy agents still join and are still addressed"
      (is (= #{var-vor sol} (set joined)))
      (is (= [var-vor sol] (:recipients result)))
      (is (= [lun] (:unavailable result))))

    (testing "the failure is logged at :warn with agent, model and reason"
      (let [warn (first (filter #(= ::room-agents/agent-join-failed (:id %)) signals))]
        (is (some? warn) "a Telemere signal names the join failure")
        (is (= :warn (:level warn)))
        (is (= "Lun" (-> warn :data :agent)))
        (is (= lun (-> warn :data :agent-id)))
        (is (= "accounts/fireworks/models/qwen3p6-plus" (-> warn :data :model)))
        (is (= :unavailable-to-account (-> warn :data :availability)))))

    (testing "a room note names the failing agent and why it cannot run"
      (let [note (first (notes posted text))]
        (is (some? note) "the room gets a note, not just the log")
        (is (= (room-agents/party->actor-kw lun) (:from note))
            "authored by the agent that cannot run")
        (is (str/includes? (:content note) "Lun"))
        (is (str/includes? (:content note) "accounts/fireworks/models/qwen3p6-plus"))
        (is (str/includes? (:content note) "Unavailable to account"))
        (is (= :system (get-in note [:metadata :role])))))

    (testing "the note is addressed back to the sender, so no agent is woken"
      (let [note (first (notes posted text))]
        (is (= (room-agents/party->actor-kw sender) (:to note)))))

    (testing "the note says the rest of the room still works"
      (is (str/includes? (:content (first (notes posted text)))
                         "other agents in this room are unaffected")))))

(deftest every-agent-unavailable-still-posts-the-message
  (let [text "anyone home?"
        {:keys [result posted joined signals]} (run-send text [])]

    (testing "the send succeeds — the message was sent, the agents are broken"
      (is (= :ok (:status result)))
      (is (empty? joined))
      (is (empty? (:recipients result)))
      (is (= #{var-vor sol lun} (set (:unavailable result)))))

    (testing "the message is still in the timeline, addressed to no agent"
      (let [sent (user-messages posted text)]
        (is (= 1 (count sent)))
        (is (= (room-agents/party->actor-kw sender) (:to (first sent)))
            "self-addressed: persisted and rendered, delivered to no participant")))

    (testing "the room says why, once per failing agent"
      (is (= 3 (count (notes posted text))))
      (is (= #{"Vár" "Sol" "Lun"}
             (set (for [n (notes posted text)
                        nm ["Vár" "Sol" "Lun"]
                        :when (str/includes? (:content n) nm)]
                    nm)))))

    (testing "no note claims other agents are unaffected — none of them are"
      (is (not-any? #(str/includes? (:content %) "unaffected") (notes posted text))))

    (testing "every failure reaches the operator log too"
      (is (= 3 (count (filter #(= ::room-agents/agent-join-failed (:id %)) signals)))))))

(deftest a-room-with-no-agents-still-broadcasts
  (let [text "mirror this"
        room-uuid (random-uuid)
        room {:ctx nil :id :test-room}
        posted (atom [])
        result (with-redefs-fn
                 {(room-agents-var 'ensure-providers!) (constantly nil)
                  #'rooms/get-room-agents (constantly [])
                  #'parties/get-party (constantly {:party/id sender
                                                   :party/type :human
                                                   :party/display-name "You"})
                  #'room-agents/live-room (constantly room)
                  #'room-agents/ensure-room-party-entity! (constantly nil)
                  #'room-agents/ensure-room-projector! (constantly nil)
                  #'d/room-target (constantly nil)
                  #'d/post! (fn [_room msg] (swap! posted conj msg) msg)}
                 (fn [] (room-agents/post-user-message! room-uuid text sender nil)))]
    (testing "the broadcast target is unchanged, so mirrors keep relaying"
      (is (= :ok (:status result)))
      (is (= 1 (count @posted)))
      (is (nil? (:to (first @posted)))))))
