(ns is.simm.uis.web.desktop.views.new-room
  "Team creation dialog — name + human member picker.

   \"Team\" is the UI's word for a `:group` room (views.core/room-nouns). The
   entity stays a room everywhere below the surface — dvergr owns the concept
   and the agent vocabulary uses it."
  (:require [org.replikativ.spindel.dom.elements :as el]
            [is.simm.uis.web.desktop.views.core :as vc]
            [is.simm.uis.web.desktop.signals :as sig]
            #?(:cljs [is.simm.uis.web.desktop.chat-remote :as cr])
            #?(:cljs [is.simm.uis.web.desktop.user-rooms-sync :as urs])
            #?(:cljs [is.simm.runtimes.web :as web]))
  #?(:cljs (:require-macros [org.replikativ.spindel.dom.elements :as el]
                            [org.replikativ.spindel.dom.foreach :refer [ifor-each]])))

(defn render-new-room-content
  "all-humans is a list of party maps or nil."
  [all-humans]
  (let [current-user #?(:cljs @sig/current-user :clj nil)]
    (el/div {:class "settings-page"}
      (el/div {:class "settings-header"}
        (vc/icon "plus-circle" {:class "settings-header-icon"})
        (el/h2 {} "New Team"))

      (el/div {:class "settings-sections"}
        (el/div {:class "settings-section"}
          (el/h3 {:class "settings-section-title"} "Team Name")
          (el/input {:id "new-room-name"
                     :type "text"
                     :class "settings-input"
                     :placeholder "e.g. Product Team"
                     :style {:width "100%"}}))

        (el/div {:class "settings-section"}
          (el/h3 {:class "settings-section-title"} "Members")
          (el/p {:class "settings-section-desc"}
            "Select people to add to this room. You will be added automatically.")
          (if all-humans
            (let [others (filterv (fn [h]
                                    (and current-user
                                         (not= (str (:party/id h)) (:id current-user))))
                                  all-humans)]
              (el/div {:class "settings-model-list"}
                (ifor-each #(str (:party/id %)) others
                  (fn [h]
                    (let [pid (:party/id h)
                          name (:party/display-name h)
                          email (:party/email h)]
                      (el/div {:key (str pid)
                               :class "settings-model-option"
                               :on-click (fn [_]
                                           #?(:cljs
                                              (let [el (.getElementById js/document (str "member-" pid))]
                                                (when el
                                                  (set! (.-checked el) (not (.-checked el)))))
                                              :clj nil))}
                        (el/input {:id (str "member-" pid)
                                   :type "checkbox"
                                   :style {:margin-right "8px"}})
                        (el/div {:class "settings-model-name"} (or name email))
                        (el/div {:class "settings-model-provider"} email)))))))
            (el/div {:class "settings-loading"}
              (el/p {} "Loading..."))))

        (el/div {:class "settings-section"}
          (el/button {:class "settings-btn settings-btn--primary"
                      :style {:width "100%" :padding "10px"}
                      :on-click (fn [_]
                                  #?(:cljs
                                     (let [name-el (.getElementById js/document "new-room-name")
                                           room-name (when name-el (.-value name-el))
                                           member-ids (when all-humans
                                                        (->> all-humans
                                                             (filter (fn [h]
                                                                       (when-let [cb (.getElementById js/document (str "member-" (:party/id h)))]
                                                                         (.-checked cb))))
                                                             (mapv #(str (:party/id %)))))]
                                       (when (and room-name (seq room-name) current-user)
                                         (let [s (cr/create-room! web/server-id (:id current-user)
                                                                   room-name (or member-ids []))]
                                           (s (fn [result]
                                                ;; REFETCH, don't blank. This used to
                                                ;; `(reset! sig/user-rooms nil)` and lean on the
                                                ;; nil-guard in nav's shell spin to refetch — but
                                                ;; that spin tracks `current-user`, not
                                                ;; `user-rooms`, so nothing re-ran it. The TEAMS
                                                ;; section DOES track `user-rooms`, so it
                                                ;; re-rendered on the nil and showed "No teams
                                                ;; yet": creating a team emptied the sidebar.
                                                ;; Whether the new team turned up at all came
                                                ;; down to a race with the server's
                                                ;; `user-rooms/dirty` invalidation — if that
                                                ;; landed after this callback the list was
                                                ;; restored, if before, the nil won.
                                                (urs/refresh-user-rooms! (:id current-user))
                                                (js/console.log "[new-room] created:" (pr-str result))
                                                ;; Dismiss the dialog. Without this a SUCCESSFUL
                                                ;; create looked exactly like a failed one: the
                                                ;; form just sat there.
                                                (sig/close-tab-of-type! :new-room))
                                              (fn [err]
                                                (js/console.error "[new-room] error:" err)
                                                ;; ...and a real failure said nothing at all,
                                                ;; only to the console nobody had open.
                                                (sig/show-error! "Could not create the team."
                                                                 (str err)))))))
                                     :clj nil))}
            "Create Team"))))))
