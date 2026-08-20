(ns is.simm.ops.feed
  "Feed — what is happening, across the system and every room.

   A VIEW over sources, like `ops.tasks` and `ops.accounting-report`: the
   things that belong in a feed already exist somewhere with an owner, and
   giving them a second home would mean a table that has to be kept true.

     :system    deployment announcements (`resources/feed/announcements.edn`)
     :mention   a message that named you — carries its AUTHOR and its text
     :proposal  a ForkSet someone filed or resolved — carries its author
     :page      a page that was CREATED, with an excerpt of what it says

   Two deliberate choices about what is not here.

   Page EDITS are gone. They were the bulk of the rows and the least worth
   reading; a feed where every row carries the same weight is an activity log,
   and activity logs are dull because nothing on them is worth opening.

   Rows carry an ACTOR wherever the data has one — that is most of the
   difference between a social feed and a changelog. It also bounds honestly
   what can be shown: pages and blocks record no author ANYWHERE. Not on the
   entity, not in tx metadata (a KB transaction carries only `:db/txInstant`),
   not in yggdrasil's commit meta. So a page row names the wiki it landed in
   rather than inventing a person. Attributing edits needs authorship recorded
   at write time; until then \"Operations Playbook\" is true and a name would
   not be.

   Absent on purpose: activity beyond your own projects. That is a recommender
   over other people's work, and the hard part is what you are ALLOWED to see —
   an authorization design, not a query."
  (:require [is.simm.model.references :as refs]
            [is.simm.model.access :as access]
            [is.simm.model.parties :as parties]
            [is.simm.model.room-databases :as room-dbs]
            [is.simm.model.rooms :as rooms]
            [is.simm.ops.proposals :as props]
            [is.simm.ops.tasks :as tasks]
            [is.simm.runtimes.branching :as branching]
            [is.simm.runtimes.context :as ctx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [taoensso.telemere :as log]))

;; =============================================================================
;; Source: system announcements
;; =============================================================================

(defn announcements
  "Deployment announcements, newest first.

   Read from disk PER CALL, not memoised at load: the whole reason this is a
   file is that someone can publish a line without a deploy.

   Classpath first (`resources` is on `:paths` and copied into the uberjar),
   with a working-directory fallback. Same lookup as
   `demo.scenario/read-scenario`; before `resources` was on the classpath
   `io/resource` returned nil here and the announcements silently vanished
   from a feed that still looked fine."
  []
  (try
    (if-let [r (or (io/resource "feed/announcements.edn")
                   (let [f (io/file "resources" "feed" "announcements.edn")]
                     (when (.exists f) f)))]
      (->> (edn/read-string (slurp r))
           (mapv (fn [a]
                   {:id (str "system:" (name (:id a)))
                    :source :system
                    :kind (:kind a)
                    :actor "simmis"
                    :title (:title a)
                    :body (:body a)
                    :at (:at a)}))
           (sort-by :at #(compare %2 %1))
           vec)
      (do (log/log! {:level :warn :id ::announcements-missing
                     :msg "feed/announcements.edn not found on the classpath or in resources/"})
          []))
    (catch Exception e
      (log/log! {:level :warn :id ::announcements-unreadable
                 :data {:error (.getMessage e)}})
      [])))

;; =============================================================================
;; Excerpting
;; =============================================================================

(def ^:private strip-html
  "See `refs/strip-html` — one implementation, shared."
  refs/strip-html)
(defn- excerpt [s n]
  (when-let [t (strip-html s)]
    (when (seq t)
      (if (> (count t) n) (str (subs t 0 n) "…") t))))

;; =============================================================================
;; Source: mentions — where a real person addressed you
;; =============================================================================

(def ^:private mentions-q
  '[:find [(pull ?m [:entity/uuid :block/content :S.Message/sent-at
                     {:S.Message/author [:entity/uuid :S.User/display-name]}]) ...]
    :in $ ?handle
    :where [?m :S.Message/party-mentions ?handle]])

(defn- room-mentions
  "Messages in one room that named `handle`.

   The source that makes the feed social and the linking pay off at once: the
   ref opens the room ANCHORED on the exact message, which the chat view
   already knows how to do."
  [room handle since]
  (ctx/with-server-context
    (if-let [scope (:room/content-db-scope room)]
      (try
        (if-let [conn (room-dbs/connect-room-database scope)]
          (->> (d/q mentions-q @conn handle)
               (keep (fn [m]
                       (let [at (:S.Message/sent-at m)]
                         (when (and at (pos? (compare at since)))
                           ;; scope-qualified: messages live in per-room
                           ;; stores, so a uuid alone is not unique across
                           ;; rooms (same reason as the page ids below)
                           {:id (str "mention:" scope "/" (:entity/uuid m))
                            :source :mention
                            :kind :mention
                            :actor (get-in m [:S.Message/author :S.User/display-name])
                            :title (str "mentioned you in " (:room/name room))
                            :body (excerpt (:block/content m) 240)
                            :where (:room/name room)
                            :ref {:kind :message
                                  :room (str (:room/id room))
                                  :scope (str scope)
                                  :message (str (:entity/uuid m))
                                  :title (:room/name room)}
                            :at at}))))
               vec)
          [])
        (catch Exception e
          (log/log! {:level :warn :id ::room-mentions-failed
                     :data {:room (str (:room/id room)) :error (.getMessage e)}})
          []))
      [])))

;; =============================================================================
;; Source: proposals — filed by someone, about something
;; =============================================================================

(defn- proposal-items [party since]
  (ctx/with-server-context
    (->> (props/visible-proposals party)
         (keep (fn [p]
                 (let [resolved? (not= :open (:proposal/status p))
                       at (or (:proposal/resolved-at p) (:proposal/created-at p))]
                   (when (and at (pos? (compare at since)))
                     {:id (str "proposal:" (:proposal/id p) ":" (name (:proposal/status p)))
                      :source :proposal
                      :kind (if resolved? :proposal-resolved :proposal-filed)
                      :actor (some-> (:proposal/author p) parties/get-party
                                     :party/display-name)
                      :title (if resolved?
                               (str (:proposal/title p) " — " (name (:proposal/status p)))
                               (:proposal/title p))
                      :body (or (:proposal/resolution-note p) (:proposal/summary p))
                      :ref {:kind :proposal :id (str (:proposal/id p))
                            :title (:proposal/title p)}
                      :at at}))))
         vec)))

;; =============================================================================
;; Source: pages created — content, with a taste of it
;; =============================================================================

(def ^:private new-pages-q
  '[:find [(pull ?e [:entity/uuid :entity/name :entity/created-at
                     :S.Page/title :S.Page/kind :S.Page/archived]) ...]
    :where [?e :instance/of-role [:entity/name "S/Page"]]])

(defn- page-excerpt
  "The first block's text, so the row says something rather than only naming a
   file. That is what a feed is for — you decide whether to open it FROM the
   row, not after."
  [db page-uuid]
  (->> (d/q '[:find [(pull ?b [:block/content :block/order]) ...]
              :in $ ?uuid
              :where [?page :entity/uuid ?uuid] [?b :block/parent ?page]]
            db page-uuid)
       (sort-by #(or (:block/order %) 0))
       (keep #(excerpt (:block/content %) 240))
       first))

(defn- kb-new-pages
  "Pages CREATED in one KB. Edits are excluded on purpose — see the ns doc."
  [scope kb-name since]
  (ctx/with-server-context
    (if-let [conn (branching/get-kb-conn scope)]
      (try
        (let [db @conn]
          (->> (d/q new-pages-q db)
               (remove :S.Page/archived)
               ;; chat-summary records are derived artifacts, not authored pages
               (remove #(= :chat-summary (:S.Page/kind %)))
               (keep (fn [p]
                       (let [created (:entity/created-at p)]
                         (when (and created (pos? (compare created since)))
                           ;; A page is (store, uuid): the same uuid exists in
                           ;; several KBs (seeded pages share uuids), so a bare
                           ;; "page:<uuid>" collides ACROSS scopes. The feed's
                           ;; :id is the ifor-each key, so a collision gave two
                           ;; different rows one keyed address and every
                           ;; descendant beneath them collided too
                           ;; (::addr-collision, simmis #74 — measured as
                           ;; "Finance" and "Comms" rows sharing an address).
                           {:id (str "page:" scope "/" (:entity/uuid p))
                            :source :page
                            :kind :page-new
                            ;; No author exists for a page (see ns doc) — the
                            ;; wiki is the truthful actor.
                            :actor kb-name
                            :title (or (:S.Page/title p) (:entity/name p) "Untitled")
                            :body (page-excerpt db (:entity/uuid p))
                            :where kb-name
                            :ref {:kind :page :scope (str scope)
                                  :page (str (:entity/uuid p))
                                  :title (or (:S.Page/title p) (:entity/name p))}
                            :at created}))))
               vec))
        (catch Exception e
          (log/log! {:level :warn :id ::kb-new-pages-failed
                     :data {:scope (str scope) :kb kb-name :error (.getMessage e)}})
          []))
      [])))

;; =============================================================================
;; The aggregate
;; =============================================================================

(def default-sources
  "What a feed shows when the user has expressed no preference. Exposed so a
   settings toggle has something to write against."
  #{:system :mention :proposal :page})

(defn items
  "Everything this party can see happening, newest first.

   `:sources` — subset of `default-sources`. `:since` (default 30 days) bounds
   the scan, `:limit` (default 60) the result. A feed is a sample of the
   recent, not an archive — the archive is Timelines."
  [party & {:keys [since limit sources]
            :or {limit 60 sources default-sources}}]
  (when-not party (throw (ex-info "the feed requires an authenticated party" {})))
  (let [since (or since (java.util.Date. (- (System/currentTimeMillis)
                                            (* 30 24 60 60 1000))))
        want? #(contains? sources %)
        handle (:party/handle (parties/get-party party))
        mentions (when (and (want? :mention) handle)
                   (mapcat #(room-mentions % handle since)
                           (filter #(access/can? party :read {:room (:room/id %)})
                                   (rooms/get-party-rooms party))))
        pages (when (want? :page)
                (mapcat (fn [{:kb/keys [db-scope name]}]
                          (kb-new-pages db-scope name since))
                        (tasks/visible-kbs party)))
        proposals (when (want? :proposal) (proposal-items party since))]
    ;; Announcements PIN above the rest rather than sorting in by time: one
    ;; that slid under a morning's activity is one nobody read. Everything else
    ;; is strictly newest-first.
    (->> (concat (when (want? :system) (announcements))
                 (->> (concat mentions proposals pages)
                      (sort-by :at #(compare %2 %1))))
         (take limit)
         vec)))
