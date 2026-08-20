(ns is.simm.runtimes.context
  "Server-side execution context for spindel.

   This namespace MUST be loaded before any code that creates signals.
   It provides the single server-wide execution context that all
   spindel operations (signals, tasks, effects) use.

   Usage:
   1. Require this namespace early in your require chain
   2. Use `with-server-context` to run code within the context
   3. Signals created within the context are automatically registered"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.distributed.core :as dist]
            [taoensso.telemere :as log]))

;; Create the server-wide execution context at load time.
;; This must happen before any signals are created.
(defonce server-context
  (let [ectx (ctx/create-execution-context)]
    (log/log! {:level :info
               :id ::server-context-created
               :msg "Server execution context created"})
    ectx))

;; Register on every namespace load — the registry atom may be reset
;; when shadow-cljs recompiles and reloads spindel's CLJC files.
(dist/register-context! :default server-context)

(defmacro with-server-context
  "Execute body with the server context bound.
   Use this when creating signals at namespace load time."
  [& body]
  `(binding [rtc/*execution-context* server-context]
     ~@body))

(defn bind-server-context!
  "Bind the server context for the current thread.
   Returns the context for chaining."
  []
  (alter-var-root #'rtc/*execution-context* (constantly server-context))
  server-context)
