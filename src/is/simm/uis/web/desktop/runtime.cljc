(ns is.simm.uis.web.desktop.runtime
  "Shared spindel runtime for the Simmis frontend.

   This namespace re-exports the global execution context from bootstrap.
   All signals, spins, and reactive operations use this runtime.

   The bootstrap preload creates the context and binds it globally.
   This namespace provides convenient access to it.

   Usage:
   - Import this namespace to get access to the runtime
   - Use (signal runtime initial-value) to create signals at the top level"
  #?(:cljs (:require [is.simm.runtimes.bootstrap :as bootstrap])))

;; Re-export the bootstrap context - there should only be one runtime
#?(:cljs (def runtime bootstrap/execution-context))
