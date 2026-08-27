(ns is.simm.uis.web.desktop.tab-heal
  "Open tabs, reconciled against the room roster.

   Pure, and dependency-free ON PURPOSE — the same reason `routes.cljc` is.
   This started inside `user_rooms_sync.cljs` and could not be tested there:
   that namespace requires `signals`, which does not load on the JVM at all,
   and `clojure -X:test` does not execute `.cljs`. The rules below are subtle
   enough to be worth stating as assertions rather than as prose (a
   placeholder, a missing scope, a missing name, a room that is simply gone,
   and a verdict that must be revocable), so they get a namespace that can be
   exercised without a DOM, a browser, or a running app.

   `user-rooms-sync` owns the WHEN — it calls this every time the roster
   lands. This owns the WHAT.")

(defn heal-chat-tab
  "Reconcile one tab against `rooms`: fill in what the roster knows, or mark
   the tab as pointing at no room this party can open.

   THE FACT ARRIVES WITH THE ROSTER, which is why the reconciliation is keyed
   to it. A chat tab can be created BEFORE the roster exists: `router/init!`
   runs at boot step 3 and applies a `/room/<id>` deep link immediately, while
   the roster is fetched from the sidebar spin, which does not exist until step
   4 mounts it. `refs/ref->tab` resolves `:db-scope` from that roster, so on a
   cold-boot deep link it resolves to nothing — and the chat column then waits
   forever for a replica nobody ever asked to connect. The URL also carries no
   room name, which is why such a tab reads \"Chat\".

   `:room-missing?` is the other half, and it is a CONCLUSION, not a timeout.
   The roster is the complete list of rooms this party can open; once it has
   arrived and does not name this room, the tab cannot be loaded — now, and not
   merely not-yet. A later refresh that does name the room clears the flag, so
   a room shared with you mid-session heals rather than staying condemned."
  [tab rooms personal-room]
  (if (not= :chat (:type tab))
    tab
    (let [room-id      (get-in tab [:data :room-id])
          placeholder? (= room-id "personal-ai-placeholder")
          room         (cond
                         placeholder? personal-room
                         room-id (first (filter #(= room-id (str (:room/id %))) rooms))
                         :else nil)]
      (if room
        (cond-> (assoc-in tab [:data :room-id] (str (:room/id room)))
          true (update :data dissoc :room-missing?)
          (nil? (get-in tab [:data :db-scope]))
          (assoc-in [:data :db-scope] (str (:room/content-db-scope room)))
          ;; Adopt the room's name when the tab is not showing a real one: it
          ;; either has none (a deep link carries no title) or is showing the
          ;; boot layout's stand-in "Assistants". A tab that has a real name
          ;; keeps it.
          (or placeholder? (nil? (get-in tab [:data :room-name])))
          (-> (assoc-in [:data :room-name] (:room/name room))
              (assoc :title (:room/name room))))
        ;; No room-id at all is the same conclusion by a shorter route: the
        ;; legacy `:chat-room` backlink opens a tab carrying only a title.
        (assoc-in tab [:data :room-missing?] true)))))

(defn heal-chat-tabs
  "`heal-chat-tab` across a whole column layout."
  [cols rooms personal-room]
  (mapv (fn [col]
          (update col :tabs
                  (fn [tabs] (mapv #(heal-chat-tab % rooms personal-room) tabs))))
        cols))
