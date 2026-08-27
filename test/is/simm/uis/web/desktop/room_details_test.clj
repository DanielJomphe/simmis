(ns is.simm.uis.web.desktop.room-details-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.uis.web.desktop.room-details :as rd]))

(def room "room-1")

(deftest a-first-request-starts-a-load
  (let [[state action] (rd/begin {} room false)]
    (is (= :start action))
    (is (true? (get-in state [room :in-flight?])))
    (is (false? (get-in state [room :pending?])))))

(deftest an-unforced-request-rides-along-with-the-load-in-flight
  (let [[state _] (rd/begin {} room false)
        [state' action] (rd/begin state room false)]
    (is (= :skip action) "data will exist either way — one fetch answers both")
    (is (= state state'))))

(deftest a-forced-request-mid-flight-is-queued-not-dropped
  (testing "the in-flight answer predates the write, so it cannot be the last word"
    (let [[state _] (rd/begin {} room false)
          [state' action] (rd/begin state room true)]
      (is (= :queue action))
      (is (true? (get-in state' [room :pending?])))
      (testing "and it runs when the in-flight load retires"
        (let [[state'' action'] (rd/finish state' room)]
          (is (= :start action'))
          (is (true? (get-in state'' [room :in-flight?])))
          (is (false? (get-in state'' [room :pending?])))
          (testing "exactly once — the queue does not loop"
            (is (= [{} :idle] (rd/finish state'' room)))))))))

(deftest finishing-a-quiet-load-leaves-no-bookkeeping
  (let [[state _] (rd/begin {} room false)]
    (is (= [{} :idle] (rd/finish state room)))))

(deftest rooms-are-tracked-independently
  (let [[state _] (rd/begin {} room false)
        [state' action] (rd/begin state "room-2" false)]
    (is (= :start action) "another room's load is not blocked by this one")
    (is (true? (get-in state' [room :in-flight?])))
    (is (true? (get-in state' ["room-2" :in-flight?])))))

(deftest a-forced-request-with-nothing-in-flight-starts-immediately
  (let [[state action] (rd/begin {} room true)]
    (is (= :start action))
    (is (false? (get-in state [room :pending?])))))
