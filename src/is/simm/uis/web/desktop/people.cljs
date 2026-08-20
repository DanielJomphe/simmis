(ns is.simm.uis.web.desktop.people
  "Who you can @-mention, and who a profile card is about — from the roster the
   server already sends.

   This replaces a datahike projection. `S/Person` rows were written into the
   app store by `model.address-book` for exactly one reason: humans live in the
   un-synced dvergr system DB as `:party/*`, so a client had no local source for
   them. The projection gave `search-people` something to query.

   But `chat-remote/load-rooms!` already ships the same fields as `:contacts` —
   id, type, handle, display-name, avatar, plus model/provider for agents — and
   it is strictly better as a roster:

     • server-filtered to the parties you can actually reach, where the
       projection held every person in one shared store and every client
       replicated all of it;
     • already in a signal the sidebar renders from, so no second copy to keep
       fresh;
     • no store required, which is what let the app store be retired.

   Only the address-book PROJECTION is gone. The `S/Person` type stays, in KB
   stores, because that is the CRM/wiki type agents write contact pages with
   (`kb/add-type!`, `:S.Person/company`) — a different thing that happened to
   share a name."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as rtc]
            [is.simm.uis.web.desktop.runtime :refer [runtime]]
            [is.simm.uis.web.desktop.signals :as sig]))

(defn all
  "Everyone nameable, sorted by display name.

   The union of `:directory` (everyone in the tenant) and `:contacts` (the
   people you actually deal with). Both are needed and neither suffices:
   `:contacts` is explicit contacts plus people in shared rooms — right for
   `who do I talk to`, too narrow for `who can I @-mention`, since naming a
   colleague you share no room with is exactly how you pull them into a page.
   The projection this replaces made everyone mentionable, and dropping that
   silently would have been a regression hidden inside a refactor.

   Deduped by id, with the contact entry winning: it is the richer record.

   Shape kept identical to the query this replaces —
   `[{:entity/uuid :display-name :handle :avatar :is-ai?} …]` — so its three
   consumers did not have to change how they read it."
  []
  (binding [rtc/*execution-context* runtime]
    (let [ur @sig/user-rooms
          rows (when (map? ur) (concat (:directory ur) (:contacts ur)))]
      (->> rows
           (reduce (fn [acc c] (assoc acc (:id c) c)) {})
           vals
           (map (fn [c]
                  {:entity/uuid (:id c)
                   :display-name (or (:display-name c) (:handle c) "Unknown")
                   :handle (or (:handle c) "")
                   :avatar (or (:avatar c) "")
                   :is-ai? (= :agent (:type c))}))
           (sort-by :display-name)
           vec))))

(defn by-handle
  "One person by @handle, or nil. The profile card and autocomplete resolve
   through the same list, so the two cannot drift."
  [handle]
  (when-not (str/blank? (str handle))
    (some (fn [p] (when (= (:handle p) handle) p)) (all))))
