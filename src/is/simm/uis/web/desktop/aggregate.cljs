(ns is.simm.uis.web.desktop.aggregate
  "ONE coalescing gate for the perspective aggregates.

   Feed, Tasks, Accounting and Proposals each fetch by RPC into a signal, and
   each needs the same two properties: never run two fetches at once, and never
   lose an invalidation that arrives while one is running.

   Both were implemented — twice, in different places, and the pair defeated
   each other. Each view owned a `loading?` flag that returned early, and
   `perspectives-sync` owned an in-flight/stale pair around the call. The view's
   flag won: a nudge arriving during the fetch that the view itself had started
   hit the early return, which called `done` as though the fetch had landed, so
   the sync cleared its stale bit and the write that caused the nudge was never
   reflected. Two correct-looking guards, one dropped update.

   So the gate lives HERE, once, next to the fetch it guards — and the
   invalidation layer just asks for a refresh without knowing anything about
   timing. No timers: a burst coalesces because the pending bit survives the
   in-flight fetch, and it settles the moment writes stop."
  (:require [clojure.string]))

(defn gate
  "State for one aggregate. `defonce` it beside the signal it fills."
  []
  (atom {:in-flight? false :pending? false}))

(defn run!
  "Run `fetch!` under `g`, coalescing concurrent requests.

   `fetch!` takes a completion callback and MUST call it exactly once, on both
   the success and failure paths — otherwise the gate stays shut and that
   aggregate never refreshes again for the rest of the session."
  [g fetch!]
  (if (:in-flight? @g)
    (swap! g assoc :pending? true)
    (do (swap! g assoc :in-flight? true)
        (fetch! (fn []
                  (let [pending? (:pending? @g)]
                    (swap! g assoc :in-flight? false :pending? false)
                    ;; One more pass for whatever arrived mid-flight. Recursion
                    ;; rather than a loop because each pass is itself async.
                    (when pending? (run! g fetch!))))))))

(defn reopen!
  "Force the gate open.

   For the one case the pending bit cannot cover: a fetch that never completes
   because the socket dropped mid-invoke, leaving `:in-flight?` set forever.
   Call this on reconnect — real coordination on a real event, not a timeout."
  [g]
  (swap! g assoc :in-flight? false :pending? false))
