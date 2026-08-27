(ns is.simm.uis.web.desktop.room-details
  "ONE loader for a room's settings payload (`sig/admin-data`).

   Every panel that shows room details reads the same signal, so they must
   also share the way it is refilled. Two rules live here.

   1. The remote spin runs inside a `go` block. A spin invoked from a RENDER
      BODY becomes a created-child of the render spin, and the next
      `invalidate-created-spins!` — which fires on every parent re-run —
      cancels it mid-flight, so the result never lands. The go block runs
      outside any spin scope (`*spin-id*` unbound), so the load is a root spin
      and survives re-renders. This is why the agent inspector's saves looked
      like nothing happened: the write landed, the reload it asked for was
      cancelled, and the panel kept showing its pre-save render.

   2. A load already in flight is not duplicated, but a load requested AFTER a
      write must not be dropped either — the in-flight answer predates that
      write. A forced request that arrives mid-flight is queued and re-issued
      when the current one finishes. `begin`/`finish` are the whole state
      machine, kept pure so they can be tested without a browser."
  ;; Every require is cljs-only: `begin`/`finish` are pure and load under
  ;; Clojure for tests, while `signals` reaches for browser-only state.
  #?(:cljs
     (:require [is.simm.uis.web.desktop.signals :as sig]
               [clojure.core.async :refer [go <! put! promise-chan] :include-macros true]
               [is.simm.uis.web.desktop.chat-remote :as chat-remote]
               [org.replikativ.spindel.engine.core :as rtc]
               [is.simm.uis.web.desktop.runtime :refer [runtime]]
               [is.simm.runtimes.web :as web])))

(defn begin
  "Register a load request for `room-id`. Returns `[state' action]`, where
   action is `:start` (issue it now), `:queue` (one is in flight and this one
   must run after it) or `:skip` (one is in flight and will answer this too).

   `force?` marks a request that must observe state written after the in-flight
   load was issued — a save. An unforced request only wants data to exist."
  [state room-id force?]
  (let [{:keys [in-flight?]} (get state room-id)]
    (cond
      (not in-flight?) [(assoc state room-id {:in-flight? true :pending? false}) :start]
      force?           [(assoc-in state [room-id :pending?] true) :queue]
      :else            [state :skip])))

(defn finish
  "Retire the in-flight load for `room-id`. Returns `[state' action]`, where
   `:start` means a forced request arrived meanwhile and must run now."
  [state room-id]
  (if (get-in state [room-id :pending?])
    [(assoc state room-id {:in-flight? true :pending? false}) :start]
    [(dissoc state room-id) :idle]))

#?(:cljs (defonce ^:private loads (atom {})))

#?(:cljs
   (defn- fetch! [room-id]
     (go
       (let [ch (promise-chan)]
         (binding [rtc/*execution-context* runtime]
           (let [s (chat-remote/load-room-details! web/server-id room-id)]
             (s (fn [result] (put! ch {:ok result}))
                (fn [err] (put! ch {:err err})))))
         (let [{:keys [ok err]} (<! ch)
               ;; JavaScript is single-threaded here, so read-compute-write on
               ;; the bookkeeping atom needs no retry loop.
               [state' action] (finish @loads room-id)]
           (reset! loads state')
           (binding [rtc/*execution-context* runtime]
             (if err
               (js/console.error "[room-details] load error:" err)
               (reset! sig/admin-data ok)))
           (when (= :start action)
             (fetch! room-id)))))))

#?(:cljs
   (defn load!
     "Fire-and-forget refill of `sig/admin-data` from `room-id`'s details.

      Pass `:force? true` after a write, so the reload observes it rather than
      settling for an answer that was already in flight."
     ([room-id] (load! room-id nil))
     ([room-id {:keys [force?]}]
      (when room-id
        (let [[state' action] (begin @loads room-id (boolean force?))]
          (reset! loads state')
          (when (= :start action)
            (fetch! room-id)))))))
