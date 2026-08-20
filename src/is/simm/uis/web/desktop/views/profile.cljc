(ns is.simm.uis.web.desktop.views.profile
  "Lightweight party profile — the destination for an @mention click. Shows a
   person's avatar, display name, @handle and type from the S/Person address-book
   projection in the client's synced simmis store (the same records the
   @-autocomplete reads via people/all). Humans, agents and contacts all
   resolve here because the server projects every party as an S/Person (see
   is.simm.model.address-book). Richer data (shared rooms, mention backlinks, CRM
   for non-user contacts) is a follow-up.
   See doc/archive/mentions-notifications-contacts-design.md."
  (:require [org.replikativ.spindel.dom.elements :as el]
            [is.simm.uis.web.desktop.views.core :as vc]
            #?(:cljs [is.simm.uis.web.desktop.people :as people])
            [clojure.string :as str])
  #?(:cljs (:require-macros [org.replikativ.spindel.dom.elements :as el])))

#?(:cljs
   (defn- lookup-person
     "Find the S/Person address-book record for `handle` in the (tracked) db.
      Returns {:entity/uuid :display-name :handle :avatar :is-ai?} or nil.
      Shares the resolver with @-autocomplete so the two can never drift.

      `db` is ignored and kept for the caller's shape: the roster now comes from
      the `:contacts` signal rather than a projection into the app store."
     [_db handle]
     (people/by-handle handle)))

(defn- initial [s]
  (let [s (str s)]
    (if (str/blank? s) "?" (str/upper-case (subs s 0 1)))))

(defn render-profile-content
  "Render a person's profile card. `handle` is the @handle; `db` is the tracked
   simmis db. Reads reactively, so it fills in once the db is ready."
  [handle db]
  #?(:cljs
     (let [p       (lookup-person db handle)
           display (or (:display-name p) handle)
           avatar  (:avatar p)
           kind    (cond (nil? p)      "Unknown"
                         (:is-ai? p)   "Agent"
                         :else         "Person")]
       (el/div {:class "settings-page"}
         (el/div {:class "settings-header"}
           (vc/icon "at-sign" {:class "settings-header-icon"})
           (el/h2 {} display))
         (el/div {:class "settings-sections"}
           (el/div {:class "settings-section"}
             (el/div {:style {:display "flex" :align-items "center" :gap "12px"
                              :margin-bottom "8px"}}
               (el/div {:style {:width "48px" :height "48px" :border-radius "50%"
                                :display "flex" :align-items "center"
                                :justify-content "center" :font-size "20px"
                                :font-weight "600"
                                :background "var(--accent-subtle, #ececec)"
                                :color "var(--accent, #2563eb)"}}
                 (if (str/blank? avatar) (initial display) avatar))
               (el/div {}
                 (el/div {:class "settings-value"} display)
                 (el/div {:class "settings-env-key"} (str "@" handle))))
             (el/div {:class "settings-field"}
               (el/label {} "Type")
               (el/div {:class "settings-value settings-role-badge"} kind))
             (when-not p
               (el/p {:class "settings-section-desc"}
                 (str "No profile found for @" handle " in your workspace yet.")))))))
     :clj
     (el/div {:class "settings-page"}
       (el/h2 {} (str "@" handle)))))
