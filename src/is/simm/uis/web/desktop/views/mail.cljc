(ns is.simm.uis.web.desktop.views.mail
  "Self-tracking Spindel mail browser over Briefkasten snapshots."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.dom.elements :as el]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [is.simm.uis.web.desktop.signals :as sig]
            [is.simm.uis.web.desktop.views.core :as vc]
            #?(:cljs [cljs.core.async :refer [go <! promise-chan put!]])
            #?(:cljs [is.simm.runtimes.web :as web])
            #?(:cljs [is.simm.uis.web.desktop.mail-remote :as remote])
            #?(:cljs [is.simm.uis.web.desktop.runtime :refer [runtime]])
            #?(:cljs [org.replikativ.spindel.engine.core :as rtc]))
  #?(:cljs (:require-macros [org.replikativ.spindel.dom.elements :as el]
                            [org.replikativ.spindel.spin.cps :refer [spin]])))

#?(:cljs (defonce ^:private requests (atom #{})))
(def page-size 50)

#?(:cljs
   (defn- rpc! [request-key make-spin success]
     (when-not (contains? @requests request-key)
       (swap! requests conj request-key)
       (go
         (let [ch (promise-chan)]
           (binding [rtc/*execution-context* runtime]
             (let [s (make-spin)]
               (s #(put! ch {:ok %}) #(put! ch {:error %}))))
           (let [{:keys [ok error]} (<! ch)]
             (swap! requests disj request-key)
             (binding [rtc/*execution-context* runtime]
               (if error
                 (swap! sig/mail-data assoc :loading? false :error (str error))
                 (success ok)))))))))

#?(:cljs
   (defn load-page! [& [offset]]
     (let [{:keys [account-id folder query]} @sig/mail-data
           offset (or offset 0)]
       (when (and account-id folder)
         (swap! sig/mail-data assoc :loading? true :offset offset :message nil)
         (rpc! [:page account-id folder query offset]
               #(remote/load-mail-page! web/server-id (str account-id) folder
                                        (or query "") offset page-size)
               #(swap! sig/mail-data assoc :messages % :loading? false :error nil))))))

#?(:cljs
   (defn- select-folder! [folder]
     (swap! sig/mail-data assoc :folder folder :query "" :offset 0)
     (load-page! 0)))

#?(:cljs
   (defn- load-folders! [account-id]
     (rpc! [:folders account-id]
           #(remote/load-mail-folders! web/server-id (str account-id))
           (fn [folders]
             (let [names (mapv #(or (:name %) (:mail.folder/name %)) folders)
                   folder (or (some #{"INBOX"} names) (first names) "INBOX")]
               (swap! sig/mail-data assoc :folders names :folder folder)
               (load-page! 0))))))

#?(:cljs
   (defn- select-account! [account-id]
     (swap! sig/mail-data assoc :account-id account-id :folders []
            :messages [] :message nil :query "" :offset 0)
     (load-folders! account-id)))

#?(:cljs
   (defn- load-accounts! []
     (rpc! :accounts
           #(remote/load-mail-accounts! web/server-id)
           (fn [accounts]
             (let [selected (or (:account-id @sig/mail-data)
                                (:mail-account/id (first accounts)))]
               (swap! sig/mail-data assoc :accounts accounts :account-id selected)
               (when selected (load-folders! selected)))))))

#?(:cljs
   (defn- open-message! [row]
     (let [account-id (:account-id @sig/mail-data)
           folder (or (:folder row) (:mail.message/folder row)
                      (:folder @sig/mail-data))
           uid (or (:uid row) (:mail.message/uid row))]
       (rpc! [:message account-id folder uid]
             #(remote/load-mail-message! web/server-id (str account-id) folder uid)
             #(swap! sig/mail-data assoc :message %)))))

(defn- value [row plain namespaced]
  (or (get row plain) (get row namespaced)))

(defn- message-row [row]
  (let [uid (value row :uid :mail.message/uid)
        subject (value row :subject :mail.message/subject)
        from (value row :from :mail.message/from)
        date (value row :date :mail.message/date)]
    (el/button {:key (str (or (:folder row) "") "/" uid)
                :class "mail-message-row"
                :on-click #(do % #?(:cljs (open-message! row) :clj nil))}
      (el/div {:class "mail-message-from"} (or from "Unknown sender"))
      (el/div {:class "mail-message-subject"} (or subject "(no subject)"))
      (el/div {:class "mail-message-date"} (str date)))))

(defn- message-detail [message]
  (if message
    (el/article {:class "mail-detail"}
      (el/h2 {} (or (:mail.message/subject message) "(no subject)"))
      (el/div {:class "mail-detail-meta"}
        (el/div {} (str "From: " (:mail.message/from message)))
        (when-let [to (:mail.message/to message)] (el/div {} (str "To: " to)))
        (el/div {} (str (:mail.message/date message))))
      (el/pre {:class "mail-body"} (or (:mail.message/body-text message) ""))
      (when-let [attachments (seq (:mail.message/attachments message))]
        (el/div {:class "mail-attachments"}
          (el/h3 {} "Attachments")
          (for [attachment attachments]
            (el/div {:key (str (:mail.attachment/blob attachment)
                               (:mail.attachment/filename attachment))
                     :class "mail-attachment"}
              (vc/icon "paperclip")
              (el/span {} (or (:mail.attachment/filename attachment) "Attachment")))))))
    (el/div {:class "mail-detail mail-detail--empty"}
      (vc/icon "mail-open")
      (el/p {} "Select a message to read it."))))

(defn render-mail
  "Return a narrow spin that owns only `sig/mail-data`."
  []
  #?(:cljs
     (spin
       (let [state (iv/get-new (track sig/mail-data))
             {:keys [accounts account-id folders folder messages message
                     query loading? error offset]} state]
         (when (nil? accounts) (load-accounts!))
         (el/div {:class "mail-page"}
           (el/header {:class "mail-toolbar"}
             (vc/icon "mail")
             (el/select {:class "settings-input mail-account-select"
                         :value (str account-id)
                         :on-change #(select-account! (.. % -target -value))}
               (for [account accounts]
                 (el/option {:key (str (:mail-account/id account))
                             :value (str (:mail-account/id account))}
                   (:mail-account/name account))))
             (el/input {:id "mail-search-input"
                        :class "settings-input mail-search"
                        :value query
                        :placeholder "Search mail"
                        :on-input #(swap! sig/mail-data assoc :query (.. % -target -value))
                        :on-keydown #(when (= "Enter" (.-key %)) (load-page! 0))})
             (el/button {:class "settings-btn"
                         :on-click #(load-page! 0)} "Search")
             (el/button {:class "settings-btn"
                         :title "Sync from IMAP"
                         :on-click (fn [_]
                                     (rpc! [:sync account-id]
                                           #(remote/sync-mail-account! web/server-id
                                                                       (str account-id))
                                           (fn [_] (load-page! 0))))}
               (vc/icon "refresh-cw")))
           (if (empty? accounts)
             (el/div {:class "mail-empty"}
               (vc/icon "mail-plus")
               (el/h2 {} "Connect an email account")
               (el/p {} "Add an IMAP connection in Settings. Credentials stay encrypted on the server.")
               (el/button {:class "settings-btn settings-btn--primary"
                           :on-click #(sig/open-or-activate-tab! :settings nil
                                                                {:title "Settings"})}
                 "Open Settings"))
             (el/div {:class "mail-layout"}
               (el/aside {:class "mail-folders"}
                 (for [name folders]
                   (el/button {:key name
                               :class (vc/class-names "mail-folder"
                                                      (when (= name folder) "active"))
                               :on-click #(select-folder! name)}
                     (vc/icon "folder") name)))
               (el/section {:class "mail-list"}
                 (when error (el/div {:class "mail-error"} error))
                 (if loading?
                   (el/div {:class "settings-loading"} "Loading…")
                   (for [row messages] (message-row row)))
                 (el/div {:class "mail-pagination"}
                   (el/button {:class "settings-btn" :disabled (zero? (or offset 0))
                               :on-click #(load-page! (max 0 (- (or offset 0) page-size)))}
                     "Previous")
                   (el/button {:class "settings-btn" :disabled (< (count messages) page-size)
                               :on-click #(load-page! (+ (or offset 0) page-size))}
                     "Next")))
               (message-detail message))))))
     :clj (el/div {:class "mail-page"} "Mail")))
