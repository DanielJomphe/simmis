(ns is.simm.uis.web.desktop.feed-remote
  "Spin-remote for the Feed. Thin pass-through over `is.simm.ops.feed`; the
   party comes from the JWT principal and every KB is `can?`-filtered there."
  (:require [org.replikativ.spindel.distributed.macros :refer [defn-spin-remote]
             :include-macros true]
            [org.replikativ.spindel.distributed.core :as dist]
            #?(:clj [is.simm.ops.feed :as feed])
            #?(:clj [is.simm.model.access :as access])
            #?(:clj [is.simm.runtimes.context :as ctx])))

(defn-spin-remote load-feed!
  [server-id]
  (spin-remote server-id []
    #?(:clj (ctx/with-server-context
              (if-let [party (access/authenticated-party-id)]
                (mapv #(-> % (update :scope (fn [s] (some-> s str)))
                             (update :page (fn [p] (some-> p str))))
                      (feed/items party))
                (throw (ex-info "authentication required"
                                {:error :authentication-required}))))
       :cljs nil)))
