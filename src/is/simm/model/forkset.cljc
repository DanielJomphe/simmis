(ns is.simm.model.forkset
  "Where a ForkSet belongs, as ONE shared function.

   doc/archive/navigation-redesign.md: the nav has no \"Proposals\" bucket of its own,
   because \"proposal\" names the mechanism rather than the thing a person is
   looking for. A ForkSet is a proposed future, and what places it is whether
   it can land: one that does not merge is a FUTURE, one that is ready is a
   TASK — something you can act on now, listed beside everything else that is
   ready.

   Pure, and `.cljc` deliberately. The server routes when it aggregates a
   per-user list; the client routes as each card's tier streams in. Two
   implementations of this rule would drift, and the drift would read as an
   item that is a Task in the list and a Future once you open it.

   Nothing here is stored. Trunk moves, so a ForkSet legitimately demotes from
   Task back to Future without anyone touching it, and a persisted tier would
   go stale silently — the failure class this codebase keeps meeting.")

(def default-intent
  "`:proposal/intent` is absent on every row filed before the attribute
   existed. Absent means `:change`, because a patch was the only kind there
   was."
  :change)

(def intents
  "`:change` is an edit to the present. The other three are proposed futures
   that are not patches: a budget, a goal, a scenario. They are ForkSets over
   the same substrate — that is the point of the model — but they are not
   things to merge today, so they never route to Tasks."
  #{:change :budget :goal :scenario})

(defn destination
  "`:tasks` | `:futures` | `:unclassified`, from a ForkSet's intent and its
   dvergr fork tier.

   `tier` nil means mergeability has not been computed yet (it costs a 3-way
   compare per fork and streams in per card). That is `:unclassified` rather
   than a guess: guessing puts the item in a view it will visibly jump out of
   a second later.

   An unrecognised tier — dvergr adding a fourth — routes to `:futures`. Tasks
   claims readiness, and a tier we do not understand has not established it."
  [intent tier]
  (let [intent (or intent default-intent)]
    (cond
      (not= :change intent) :futures
      (nil? tier) :unclassified
      (contains? #{:reviewable :trivial} tier) :tasks
      :else :futures)))

(defn auto-mergeable?
  "A `:trivial` change contributed no substantive additions, so accepting it
   is a formality. Surfaced so the UI can say that rather than demanding the
   same review as a real edit."
  [intent tier]
  (and (= :change (or intent default-intent)) (= :trivial tier)))
