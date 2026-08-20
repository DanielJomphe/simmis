(ns is.simm.uis.web.desktop.tasks-remote
  "Spin-remotes for the Tasks view. Thin pass-throughs over `is.simm.ops.tasks`;
   uuids cross the wire as strings.

   The party is taken from the JWT principal, never from the client. A global
   task list is exactly where one user could ask for another's work items, so
   the aggregate is only ever built for whoever is actually authenticated —
   `ops.tasks/list-tasks` then filters each KB through `can?`."
  (:require [org.replikativ.spindel.distributed.macros :refer [defn-spin-remote]
             :include-macros true]
            [org.replikativ.spindel.distributed.core :as dist]
            #?(:clj [is.simm.ops.tasks :as tasks])
            #?(:clj [is.simm.model.access :as access])
            #?(:clj [is.simm.runtimes.context :as ctx])))

#?(:clj
   (defn- wire
     "One task, uuid-free. `:source` stays a keyword — it is a closed set the
      client dispatches on."
     [t]
     (-> t
         (update :scope #(some-> % str))
         (update :page #(some-> % str))
         (update :forkset #(some-> % str))
         (update :room #(some-> % str))
         (update :assignee #(some-> % str))
         (dissoc :dispatch-id))))

(defn-spin-remote list-tasks!
  [server-id include-done?]
  (spin-remote server-id [include-done?]
    (let [d (identity include-done?)]
      #?(:clj (ctx/with-server-context
                (if-let [party (access/authenticated-party-id)]
                  (mapv wire (tasks/list-tasks party :include-done? (boolean d)))
                  ;; an unauthenticated caller gets a refusal, not an empty list:
                  ;; "no tasks" and "not allowed to ask" must not look the same
                  (throw (ex-info "authentication required" {:error :authentication-required}))))
         :cljs nil))))

(defn-spin-remote set-task-status!
  [server-id scope-str page-str status]
  (spin-remote server-id [scope-str page-str status]
    (let [sc (identity scope-str) pg (identity page-str) st (identity status)]
      #?(:clj (ctx/with-server-context
                (tasks/update-task! (java.util.UUID/fromString sc)
                                    (java.util.UUID/fromString pg)
                                    {:status st})
                {:status (tasks/normalize-status st)})
         :cljs nil))))
