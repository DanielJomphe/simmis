(ns is.simm.uis.web.desktop.mail-remote
  "Spindel remote boundary for the Briefkasten mail knowledge source."
  (:require [org.replikativ.spindel.distributed.macros :refer [defn-spin-remote]
             :include-macros true]
            #?(:clj [clojure.string :as str])
            #?(:clj [is.simm.model.access :as access])
            #?(:clj [is.simm.model.mail-accounts :as mail])))

#?(:clj
   (defn- uuid [s]
     (if (uuid? s) s (java.util.UUID/fromString (str s)))))

#?(:clj
   (defn- stringify-instants [m]
     (reduce-kv (fn [out k v]
                  (assoc out k (if (instance? java.util.Date v) (str v) v)))
                {} m)))

#?(:clj
   (defn- require-owner! [account-id]
     (let [party-id (access/authenticated-party-id)]
       (when-not (mail/owned-by? account-id party-id)
         (throw (ex-info "Only the mail account owner may perform this action"
                         {:mail-account/id account-id})))
       party-id)))

(defn-spin-remote load-mail-accounts!
  [server-id]
  (spin-remote server-id []
    #?(:clj (let [party-id (access/authenticated-party-id)]
              (mail/list-accounts party-id))
       :cljs nil)))

(defn-spin-remote test-mail-connection!
  [server-id config]
  (spin-remote server-id [config]
    (let [cfg (identity config)]
      #?(:clj (do (access/authenticated-party-id)
                  (mail/test-connection! cfg))
         :cljs nil))))

(defn-spin-remote save-mail-account!
  [server-id config]
  (spin-remote server-id [config]
    (let [cfg (identity config)]
      #?(:clj (let [party-id (access/authenticated-party-id)]
                (mail/save-account! party-id
                                    (cond-> cfg (:id cfg) (update :id uuid))))
         :cljs nil))))

(defn-spin-remote sync-mail-account!
  [server-id account-id-str]
  (spin-remote server-id [account-id-str]
    (let [id-str (identity account-id-str)]
      #?(:clj (let [account-id (uuid id-str)]
                (require-owner! account-id)
                (mail/sync-now! account-id))
         :cljs nil))))

(defn-spin-remote load-mail-folders!
  [server-id account-id-str]
  (spin-remote server-id [account-id-str]
    (let [id-str (identity account-id-str)]
      #?(:clj (mail/list-folders (uuid id-str))
         :cljs nil))))

(defn-spin-remote load-mail-page!
  [server-id account-id-str folder query offset limit]
  (spin-remote server-id [account-id-str folder query offset limit]
    (let [id-str (identity account-id-str)
          f (identity folder)
          q (identity query)
          o (identity offset)
          n (identity limit)]
      #?(:clj (let [account-id (uuid id-str)
                    rows (if (str/blank? q)
                           (mail/list-messages account-id f {:offset o :limit n})
                           (mail/search account-id q {:limit n}))]
                (mapv stringify-instants rows))
         :cljs nil))))

(defn-spin-remote load-mail-message!
  [server-id account-id-str folder uid]
  (spin-remote server-id [account-id-str folder uid]
    (let [id-str (identity account-id-str)
          f (identity folder)
          message-uid (identity uid)]
      #?(:clj (some-> (mail/read-message (uuid id-str) f message-uid)
                      stringify-instants)
         :cljs nil))))
