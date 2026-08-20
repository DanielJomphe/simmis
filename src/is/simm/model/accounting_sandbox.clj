(ns is.simm.model.accounting-sandbox
  "Expose the kontor accounting kernel INSIDE the dvergr `clojure_eval` sandbox,
   so an agent (Vár) can post governed double-entry entries against the room's
   book with plain code.

   dvergr owns the generic hook (`dvergr.sandbox/register-ns-injector!`, `(fn
   [sci-ctx opts])`); the kontor-specific binding lives here because simmis
   depends on kontor and dvergr does not. Call `register!` once at startup.

   The book is the room's GOVERNED content-db (the store
   `accounting/ensure-accounting-schema!` installs the `:kontor.*` schema on and
   `kontor.governance/govern!` gates), NOT the messages store bound to `*room*`.
   So the injector resolves the content-db conn from the `:room-id` it is handed
   and binds it as `kontor/*book*`; the book verbs are pre-applied to it."
  (:require [datahike.api :as d]
            [sci.core :as sci]
            [kontor.book :as book]
            [kontor.money :as money]
            [is.simm.model.system-db :as system-db]
            [is.simm.model.room-databases :as room-dbs]
            [dvergr.sandbox :as sandbox]
            [taoensso.telemere :as log]))

(defn room-book-conn
  "Resolve the governed content-db conn (the room's book) from `room-id`, or nil
   if the room has no content-db scope / it is not connectable."
  [room-id]
  (when room-id
    (when-let [sys (system-db/get-conn)]
      (when-let [scope (d/q '[:find ?s .
                              :in $ ?rid
                              :where [?r :room/id ?rid] [?r :room/content-db-scope ?s]]
                            @sys room-id)]
        (room-dbs/connect-room-database scope)))))

(defn- chart
  "The book's accounts as `[{:path :name :type}]`, ordered by path.

   Discovery, and it is not optional. `kontor.book/entry!` REFUSES an account
   ref that does not resolve (ADR-124 — an unresolved slot was read as a tempid
   and the money went nowhere), and kontor ships no default chart, so an agent
   with the verbs but no way to enumerate accounts must GUESS a path and gets
   refused for every guess that misses. Handing it the chart turns posting from
   a guessing game into a lookup."
  [conn]
  (->> (d/q '[:find ?p ?t :where [?a :kontor.account/path ?p] [?a :kontor.account/type ?t]]
            @conn)
       (mapv (fn [[p t]] {:path p :type t}))
       (sort-by :path)
       vec))

(defn- journals
  "Journal codes with their types. The verbs resolve BY TYPE, so an agent that
   knows only codes cannot tell which one `pay!` will pick — and a book whose
   cash journal is called `CR` rather than `CSH` is normal, not exotic."
  [conn]
  (->> (d/q '[:find ?c ?t :where [?j :kontor.journal/code ?c] [?j :kontor.journal/type ?t]]
            @conn)
       (mapv (fn [[c t]] {:code c :type t}))
       (sort-by :code)
       vec))

(defn- commodities
  "What this book can be denominated in. `starter-book` seeds USD so the verbs
   are reachable; a tenant's real currency is whatever they added beside it, and
   an agent posting in the wrong one produces a balanced entry in a currency
   nobody uses."
  [conn]
  (->> (d/q '[:find ?s ?n :where [?c :kontor.commodity/symbol ?s]
              [(get-else $ ?c :kontor.commodity/name "") ?n]] @conn)
       (mapv (fn [[s n]] {:symbol s :name n}))
       (sort-by :symbol)
       vec))

(defn- balances
  "Current balance per account per commodity, as plain strings.

   Answers `what do we have` without the agent reconstructing it from postings,
   and lets it CHECK its own work — post, then read the balance back."
  [conn]
  (let [db @conn]
    (->> (d/q '[:find ?path ?sym (sum ?amt)
                :with ?p
                :where
                [?p :kontor.posting/account ?a] [?a :kontor.account/path ?path]
                [?p :kontor.posting/amount ?amt]
                [?p :kontor.posting/commodity ?c] [?c :kontor.commodity/symbol ?sym]]
              db)
         (mapv (fn [[path sym amt]] {:account path :commodity sym :amount (str amt)}))
         (sort-by (juxt :account :commodity))
         vec)))

(defn kontor-bindings
  "The `kontor` sandbox namespace map for a governed book `conn`: `*book*`, the
   book verbs pre-applied to it, non-committing `validate-entry`, the money
   helpers, and the DISCOVERY fns without which the verbs are unusable — see
   `chart`. Pure — no I/O — so tests can build a sandbox ctx over any conn.

   The 3-arity takes SEPARATE `write-fn` and `read-fn`, each resolved on EVERY
   verb call, which is how `room-agents/add-book-ns!` routes an agent's postings
   onto a proposal's fork branch: the branch is minted lazily on the first write,
   so the conn cannot be decided when the namespace is installed. `conn` still
   supplies `*book*` — a var holds a value and cannot re-resolve, so it stays the
   trunk handle rather than a branch conn that would go stale the moment the
   proposal is filed.

   The read/write split is not cosmetic. When one fn served both, calling
   `balances` or `accounts` MINTED a fork: a live run filed a proposal whose only
   two forks were branches created by agents merely LOOKING at the book, byte
   for byte identical to trunk, and the reviewer was asked to accept or refuse
   them. Reads now resolve an existing fork if there is one — so an agent still
   reads its own proposed postings back — and trunk otherwise."
  ([conn] (kontor-bindings conn (constantly conn) (constantly conn)))
  ([conn write-fn] (kontor-bindings conn write-fn write-fn))
  ([conn write-fn read-fn]
   {'*book*           conn
    ;; discovery first: an agent has to read the book before it can write to it.
    ;; These take `read-fn`, so looking at the book cannot fork it.
    'accounts         (fn [] (chart (read-fn)))
    'journals         (fn [] (journals (read-fn)))
    'commodities      (fn [] (commodities (read-fn)))
    'balances         (fn [] (balances (read-fn)))
    'entry!           (fn [opts] (book/entry! (write-fn) opts))
    'sell!            (fn [opts] (book/sell! (write-fn) opts))
    'buy!             (fn [opts] (book/buy! (write-fn) opts))
    'pay!             (fn [opts] (book/pay! (write-fn) opts))
    'receive!         (fn [opts] (book/receive! (write-fn) opts))
    'receive-payment! (fn [opts] (book/receive-payment! (write-fn) opts))
    'pay-bill!        (fn [opts] (book/pay-bill! (write-fn) opts))
    'transfer!        (fn [opts] (book/transfer! (write-fn) opts))
    'adjust!          (fn [opts] (book/adjust! (write-fn) opts))
    'validate-entry   (fn [opts] (book/validate-entry (read-fn) opts))
    'money            money/money
    'add              money/add
    'sub              money/sub
    'sum              money/sum}))

(defn add-kontor-ns!
  "dvergr ns-injector — `(fn [sci-ctx opts])`, opts = {:room-id …}. Binds the
   `kontor` namespace in the sandbox against the room's governed book. No-op when
   the room has no content-db (nothing to govern)."
  [sci-ctx {:keys [room-id]}]
  (when-let [conn (room-book-conn room-id)]
    (sci/add-namespace! sci-ctx 'kontor (kontor-bindings conn))))

(defn register!
  "Register `add-kontor-ns!` with dvergr's sandbox so every agent sandbox gets
   the `kontor` namespace. Idempotent (register-ns-injector! dedups by fn)."
  []
  (sandbox/register-ns-injector! add-kontor-ns!)
  (log/log! {:level :info :id ::kontor-ns-injector-registered
             :msg "kontor accounting namespace injector registered with dvergr sandbox"})
  add-kontor-ns!)
