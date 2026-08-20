(ns is.simm.uis.web.desktop.user-rooms-sync
  "Client-side: keep `sig/user-rooms` fresh.

   Two responsibilities:
   1. `refresh-user-rooms!` — call `load-rooms!`, reset the signal, eagerly
      connect to all returned KBs, and replace the personal-ai placeholder tab
      on first load. Idempotent — safe to call repeatedly (the placeholder
      swap is a no-op once the placeholder is gone).
   2. `subscribe!` — subscribe once to the server's `:user-rooms/dirty` pubsub
      topic. When the server publishes a set of party-ids and the current
      user's id is in the set, refresh."
  (:require [clojure.core.async :refer [go <!] :include-macros true]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [org.replikativ.spindel.engine.core :as rtc]
            [is.simm.uis.web.desktop.runtime :refer [runtime]]
            [is.simm.uis.web.desktop.signals :as sig]
            [is.simm.uis.web.desktop.db-signal :as db-sig]
            [is.simm.uis.web.desktop.chat-remote :as cr]
            [is.simm.uis.web.desktop.branching-sync :as br-sync]
            [is.simm.runtimes.web :as web]))

(def ^:private dirty-topic :user-rooms/dirty)

(defn refresh-user-rooms!
  "Fetch load-rooms! for `party-id`, reset the signal, eager-connect KBs,
   and (on first load) swap the personal-ai placeholder tab for the real
   room. Safe to call multiple times."
  [party-id]
  (let [s (cr/load-rooms! web/server-id party-id)]
    (s (fn [result]
         (binding [rtc/*execution-context* runtime]
           (reset! sig/user-rooms result)
           ;; Eagerly connect to all owned/shared KBs so the nav's page
           ;; lists are live for the session.
           (doseq [kb (:knowledge-bases result)]
             (when-let [scope (:kb/db-scope kb)]
               (db-sig/connect-kb! scope @web/client)
               ;; Seed kb-branches for the sidebar tree. Pubsub events
               ;; thereafter keep it fresh.
               (br-sync/refresh-kb-branches! scope)))
           ;; Replace the placeholder personal-ai tab with the real room
           ;; (only meaningful on first load; harmless thereafter).
           (when-let [pr (first (filter #(= (:room/type %) :personal-ai)
                                        (:rooms result)))]
             (let [real-id  (str (:room/id pr))
                   db-scope (str (:room/content-db-scope pr))]
               (swap! sig/layout-columns
                      (fn [cols]
                        (mapv (fn [col]
                                (update col :tabs
                                        (fn [tabs]
                                          (mapv (fn [t]
                                                  (if (= (get-in t [:data :room-id])
                                                         "personal-ai-placeholder")
                                                    (-> t
                                                        (assoc :title (:room/name pr))
                                                        (assoc-in [:data :room-id] real-id)
                                                        (assoc-in [:data :room-name] (:room/name pr))
                                                        (assoc-in [:data :db-scope] db-scope))
                                                    t))
                                                tabs))))
                              cols)))))))
       (fn [err] (js/console.error "[user-rooms-sync] load-rooms error:" err)))))

(defonce ^:private subscribed? (atom false))

(defn subscribe!
  "Subscribe to the user-rooms invalidation topic. The server publishes
   `{:party-ids #{...}}` after any system-DB tx that affects a roster.
   If our party-id is in the set, refresh. Idempotent — only subscribes
   once per page load."
  [party-id-str]
  (when (and party-id-str (not @subscribed?))
    (reset! subscribed? true)
    (let [my-id (uuid party-id-str)
          strategy (proto/pub-sub-only-strategy
                     (fn [{:keys [party-ids]}]
                       (when (contains? party-ids my-id)
                         (refresh-user-rooms! party-id-str))))]
      (go
        (let [result (<! (pubsub/subscribe! @web/client #{dirty-topic}
                                            {:strategies {dirty-topic strategy}}))]
          (if (:error result)
            (do (reset! subscribed? false)
                (js/console.error "[user-rooms-sync] subscribe failed:" (pr-str result)))
            (js/console.log "[user-rooms-sync] subscribed to user-rooms invalidations")))))))
