(ns is.simm.uis.web.desktop.accounting-remote
  "Spin-remote for the Accounting perspective. Thin pass-through over
   `is.simm.ops.accounting-report`; the party comes from the JWT principal and
   every book is `can?`-filtered there."
  (:require [org.replikativ.spindel.distributed.macros :refer [defn-spin-remote]
             :include-macros true]
            [org.replikativ.spindel.distributed.core :as dist]
            #?(:clj [is.simm.ops.accounting-report :as report])
            #?(:clj [is.simm.model.access :as access])
            #?(:clj [is.simm.runtimes.context :as ctx])))

(defn-spin-remote load-position!
  [server-id]
  (spin-remote server-id []
    #?(:clj (ctx/with-server-context
              (if-let [party (access/authenticated-party-id)]
                (mapv #(update % :room str) (report/workspace-position party))
                (throw (ex-info "authentication required"
                                {:error :authentication-required}))))
       :cljs nil)))
