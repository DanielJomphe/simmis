(ns is.simm.runtimes.bootstrap
  "Bootstrap module that creates and binds execution context globally.

   This module MUST be loaded before any namespace that creates signals.
   It is designed to be loaded first via shadow-cljs preloads.

   Loading order:
   1. bootstrap.cljs - binds *execution-context* globally
   2. signals.cljc, pages.cljc etc. - can create signals at defonce time
   3. web.cljs - uses the already-bound context"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.distributed.core :as dist]))

;; Create execution context at namespace load time
;; This MUST happen before any signal namespaces load
(defonce execution-context
  (let [ctx (ctx/create-execution-context)]
    (js/console.log "[BOOTSTRAP] Creating execution context...")
    ;; Register as default context for remote task invocation
    (dist/register-context! :default ctx)
    ;; Bind globally - signals created at namespace load time need this
    (set! rtc/*execution-context* ctx)
    (js/console.log "[BOOTSTRAP] Execution context bound globally")
    ctx))

;; Verify binding is active
(js/console.log "[BOOTSTRAP] *execution-context* bound?" (boolean rtc/*execution-context*))
