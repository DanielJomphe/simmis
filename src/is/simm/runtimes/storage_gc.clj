(ns is.simm.runtimes.storage-gc
  "Periodic storage GC across every persistent store.

   Datahike's index is immutable: every transaction writes new index-node blobs
   and leaves the superseded ones orphaned, reclaimed ONLY by an explicit,
   offline GC that never runs automatically. Without this the stores grow
   without bound — dvergr's own `gc-stores!` docstring reports a long-lived
   `.dvergr` reaching tens of GB of orphaned `.ksv` blobs.

   dvergr has the sweep (`dvergr.system.rooms/gc-stores!`, which coordinates
   system-db + each room's kb/msgs/repo/data through one `ygg/gc!` per room ctx),
   but the loop that drives it lives in dvergr's own daemon — and simmis boots
   through `is.simm.runtimes.web`, not that daemon, so nothing has ever swept.

   ORPHAN-ONLY, DELIBERATELY. We pass no `:remove-before`, so `gc-stores!`
   defaults to epoch and reclaims only unreachable garbage: every branch head and
   its full history is kept. A retention window would collapse datahike snapshots
   older than a cutoff — that is a data-retention policy decision, not a
   housekeeping one, and it is not ours to make implicitly. When we do want one,
   it belongs behind an explicit, per-store setting (media wants aggressive
   retention; the books want none), not a global default.

   Runs in the writer's process on purpose: `d/gc-storage` must, or it can sweep
   blobs belonging to in-flight commits. This is the simmis web JVM, which is the
   writer for these stores — so this namespace must not be turned into a sidecar
   or a cron script."
  (:require [dvergr.system.rooms :as srooms]
            [is.simm.runtimes.context :as ctx]
            [taoensso.telemere :as log]))

(defonce ^:private gc-thread (atom nil))

(def ^:private default-interval-ms
  "6 hours — matches dvergr's daemon default. Orphan reclamation is not urgent;
   the cost of sweeping too often is real (it walks every store)."
  21600000)

(defn sweep!
  "Run one orphan-only GC sweep across all stores. Returns the report map.
   Safe to run live and safe to call by hand from the REPL."
  []
  (ctx/with-server-context
    (try
      (let [r (srooms/gc-stores! {})]
        (log/log! {:level :info :id ::sweep-complete
                   :msg "Storage GC sweep complete (orphan-only)"
                   :data r})
        r)
      (catch Throwable e
        (log/log! {:level :warn :id ::sweep-failed
                   :msg "Storage GC sweep failed"
                   :data {:error (.getMessage e)}})
        {:error (.getMessage e)}))))

(defn start!
  "Start the periodic sweep (boot sweep, then every `interval-ms`). Idempotent."
  ([] (start! default-interval-ms))
  ([interval-ms]
   (when-not @gc-thread
     (let [t (Thread.
              (fn []
                (loop []
                  (sweep!)
                  (Thread/sleep (long interval-ms))
                  (when @gc-thread (recur))))
              "simmis-storage-gc")]
       (.setDaemon t true)
       (reset! gc-thread t)
       (.start t)
       (log/log! {:level :info :id ::started
                  :msg (str "Storage GC started (orphan-only, every "
                            (quot interval-ms 3600000) "h)")})))
   :started))

(defn stop! []
  (reset! gc-thread nil)
  :stopped)
