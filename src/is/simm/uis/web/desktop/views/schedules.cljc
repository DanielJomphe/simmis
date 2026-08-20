(ns is.simm.uis.web.desktop.views.schedules
  "Schedules agenda — slice 1 of the calendar.

   Read-only agenda over dvergr's per-room scheduler (`:schedule/*`
   entities), aggregated server-side by chat-remote/load-schedules!.
   Grouped by fire day (Today / Tomorrow / date); each row shows fire
   time, room, agent, task and cadence. Slice 2 adds actions
   (enable/disable, delete, create human :once events) and the week
   grid; slice 3 the month view (see doc/roadmap.md Track 4)."
  (:require [org.replikativ.spindel.dom.elements :as el]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.effects.track :refer [track]]
            [is.simm.uis.web.desktop.views.core :as vc]
            [is.simm.uis.web.desktop.signals :as sig]
            #?(:cljs [clojure.core.async :refer [go <! put! promise-chan] :include-macros true])
            #?(:cljs [org.replikativ.spindel.engine.core :as rtc])
            #?(:cljs [is.simm.uis.web.desktop.runtime :refer [runtime]])
            #?(:cljs [is.simm.uis.web.desktop.chat-remote :as chat-remote])
            #?(:cljs [is.simm.uis.web.desktop.views.mermaid :as mmd])
            #?(:cljs [is.simm.runtimes.web :as web]))
  #?(:cljs (:require-macros [org.replikativ.spindel.spin.cps :refer [spin]]
                            [org.replikativ.spindel.dom.foreach :refer [ifor-each]])))

;; =============================================================================
;; Loading
;; =============================================================================

#?(:cljs (defonce ^:private loading? (atom false)))

#?(:cljs
   (defn load-schedules!
     "Fire-and-forget refresh of sig/schedules-data via the server.
      Runs the remote spin inside a go block (root spin — survives
      parent re-renders, same pattern as load-room-details!)."
     []
     (when-not @loading?
       (reset! loading? true)
       (go
         (let [ch (promise-chan)]
           (binding [rtc/*execution-context* runtime]
             (let [s (chat-remote/load-schedules! web/server-id)]
               (s (fn [result] (put! ch {:ok result}))
                  (fn [err] (put! ch {:err err})))))
           (let [{:keys [ok err]} (<! ch)]
             (reset! loading? false)
             (binding [rtc/*execution-context* runtime]
               (if err
                 (js/console.error "[schedules] load error:" err)
                 (reset! sig/schedules-data
                         {:schedules (:schedules ok)
                          :diagrams (:diagrams ok)
                          :shapes (:shapes ok)
                          :loaded-at (js/Date.now)})))))))))

;; =============================================================================
;; Formatting
;; =============================================================================

(defn- fire-time-str [epoch-ms]
  #?(:cljs (when epoch-ms
             (let [d (js/Date. epoch-ms)
                   pad #(if (< % 10) (str "0" %) (str %))]
               (str (pad (.getHours d)) ":" (pad (.getMinutes d)))))
     :clj nil))

(defn- day-key
  "Group key: days-from-today (0 today, 1 tomorrow, ...), nil when no fire."
  [epoch-ms]
  #?(:cljs (when epoch-ms
             (let [now (js/Date.)
                   today (.setHours (js/Date.) 0 0 0 0)
                   that (.setHours (js/Date. epoch-ms) 0 0 0 0)]
               (js/Math.round (/ (- that today) 86400000))))
     :clj nil))

(defn- day-label [days epoch-ms]
  #?(:cljs (cond
             (nil? days) "Unscheduled"
             (zero? days) "Today"
             (= 1 days) "Tomorrow"
             :else (.toLocaleDateString (js/Date. epoch-ms) js/undefined
                                        #js {:weekday "long" :month "short" :day "numeric"}))
     :clj (str days)))

(defn- cadence-str [{:keys [kind every at interval-ms on on-day]}]
  (case kind
    :recurring (str "every " (some-> every name)
                    (when at (str " @ " at))
                    (when on (str " on " (name on)))
                    (when on-day (str " (day " on-day ")")))
    :interval (when interval-ms
                (let [mins (js/Math.round (/ interval-ms 60000.0))]
                  (if (>= mins 60)
                    (str "every " (js/Math.round (/ mins 60.0)) "h")
                    (str "every " mins "m"))))
    :once "once"
    (some-> kind name)))

;; =============================================================================
;; Rendering
;; =============================================================================

(defn- schedule-row [s]
  (el/div {:key (:id s) :class "schedule-row"}
    (el/span {:class "schedule-time"} (or (fire-time-str (:next-fire s)) "—"))
    (el/div {:class "schedule-main"}
      (el/div {:class "schedule-task"} (:task s))
      (el/div {:class "schedule-meta"}
        (el/span {:class "schedule-room"} (:room-name s))
        (when (:agent-name s)
          (el/span {:class "schedule-agent"} (:agent-name s)))
        (el/span {:class "schedule-cadence"} (cadence-str s))))))

#?(:cljs
   (defn render-schedules
     "Agenda tab content. Spin tracking sig/schedules-data; triggers a
      load when the signal is still empty (idempotent via loading?)."
     []
     (spin
       (let [data (iv/get-new (track sig/schedules-data))
             _ (when (nil? data) (load-schedules!))
             schedules (vec (sort-by #(or (:next-fire %) js/Number.MAX_SAFE_INTEGER)
                                     (:schedules data)))
             groups (->> schedules
                         (group-by #(day-key (:next-fire %)))
                         (sort-by (fn [[k _]] (if (nil? k) 9999999 k)))
                         vec)]
         (el/div {:class "content-schedules"}
           (el/div {:class "schedules-header"}
             (el/h2 {} "Schedules")
             (el/button {:class "schedules-refresh"
                         :title "Refresh"
                         :on-click (fn [_] (load-schedules!))}
               (vc/icon "refresh-cw")))
           ;; Per-room workflow topology diagrams (katzen → mermaid). The map
           ;; view above the agenda: one node per background workflow → the KB
           ;; it feeds — so you see WHAT is running at a glance, not per-line.
           (when-let [diagrams (seq (:diagrams data))]
             (let [shapes  (:shapes data)
                   by-room (group-by :room-name (:schedules data))]
               (el/div {:class "workflow-diagrams"}
                 (el/div {:class "workflow-diagrams-title"} "Workflows")
                 (ifor-each first (vec diagrams)
                   (fn [[room-name code]]
                     (el/div {:key (str "wf-" room-name) :class "workflow-room"}
                       (el/div {:class "workflow-room-label"} room-name)
                       (mmd/diagram code)
                       ;; agent-authored per-workflow shapes (design B): the
                       ;; actual steps of each workflow that has declared one
                       (ifor-each :id
                         (vec (filter #(contains? shapes (:id %)) (get by-room room-name)))
                         (fn [s]
                           (el/div {:key (str "shape-" (:id s)) :class "workflow-shape"}
                             (el/div {:class "workflow-shape-label"}
                               (or (:description s) (:task s)))
                             (mmd/diagram (get shapes (:id s))))))))))))
           (cond
             (nil? data)
             (el/p {:class "schedules-empty"} "Loading schedules…")

             (empty? schedules)
             (el/div {:class "schedules-empty"}
               (el/p {} "No schedules yet.")
               (el/p {:class "schedules-hint"}
                 "Ask an agent to schedule a recurring task — e.g. “run the research flow every morning at 9”."))

             :else
             (el/div {:class "schedules-groups"}
               (ifor-each first groups
                 (fn [[days ss]]
                   (el/div {:key (str "day-" days) :class "schedule-group"}
                     (el/div {:class "schedule-day-label"}
                       (day-label days (:next-fire (first ss))))
                     (el/div {:class "schedule-group-rows"}
                       (ifor-each :id (vec ss) schedule-row))))))))))))
