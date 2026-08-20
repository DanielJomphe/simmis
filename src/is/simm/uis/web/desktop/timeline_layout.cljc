(ns is.simm.uis.web.desktop.timeline-layout
  "Where things sit on the Timelines rail, as pure geometry.

   The rail carries THREE different relations to the present, and the whole
   design is letting position mean each of them honestly rather than drawing one
   undifferentiated row of dots:

     left of now      EARLIER THAN   trunk commits; the only region where
                                     distance is elapsed time
     parallel to now  INSTEAD OF     a ready ForkSet is not later than the
                                     present, it is an ALTERNATIVE present — it
                                     could BE the present the moment you accept
                                     it, which is exactly why it routes to Tasks
     short of now     BLOCKED        a conflicted ForkSet's line cannot reach
                                     the present, and that is WHY it is a Future
                                     rather than a Task
     right of now     LATER THAN     schedules, which have real `:next-fire`
                                     timestamps

   So mergeability becomes geometry instead of a badge, and the picture agrees
   with the Tasks/Futures lists by construction — both read `fs/destination` —
   rather than by our keeping two representations in step.

   `.cljc` and pure so the math is testable without a browser. Every function
   returns a fraction of the rail's width in [0,1]; the view multiplies by 100
   and emits a percentage, so nothing here needs to know a pixel."
  (:require [is.simm.model.forkset :as fs]))

(def past-frac
  "Where `now` sits across the rail. The past gets the majority because it is
   continuous and dense; the future holds a handful of scheduled runs."
  0.68)

(def ^:private blocked-gap
  "How far short of `now` a conflicted fork's line stops. Large enough to read
   as `does not reach` at a glance rather than as a rounding error."
  0.07)

(def ^:private minute-ms 60000)

(defn- log1p [x] (Math/log (+ 1.0 (double x))))

(defn- clamp01 [x] (max 0.0 (min 1.0 x)))

(def ^:private min-span-ms
  "A workspace minutes old would otherwise divide by ~zero and slam every dot
   against `now`."
  (* 5 minute-ms))

(defn past-x
  "Position of a past instant, in [0, past-frac].

   LOG-WARPED, not linear. A store's history spans weeks while the changes
   anyone is looking for are minutes old, and on a linear axis those pile into
   the last pixel — the demo's own `scrub back to this morning` would be a
   sub-pixel target. Warping by log(age) spends resolution where the density of
   interest is, and the axis ticks (`axis-ticks`) label the warp so the rail
   states its scale instead of quietly misleading about it."
  [ms now-ms span-ms]
  (let [age (max 0 (- now-ms ms))
        span (max span-ms min-span-ms)]
    (* past-frac
       (clamp01 (- 1.0 (/ (log1p (/ age minute-ms))
                          (log1p (/ span minute-ms))))))))

(def ^:private tick-ladder
  [[(* 5 minute-ms) "5m"]
   [(* 60 minute-ms) "1h"]
   [(* 6 60 minute-ms) "6h"]
   [(* 24 60 minute-ms) "1d"]
   [(* 3 24 60 minute-ms) "3d"]
   [(* 7 24 60 minute-ms) "1w"]
   [(* 30 24 60 minute-ms) "1mo"]])

(defn axis-ticks
  "Labelled gridlines for the past region — the warp made legible.

   Only rungs the data actually spans: a workspace two hours old gets `5m` and
   `1h` and stops, rather than drawing a `1mo` mark with nothing behind it."
  [now-ms span-ms]
  (vec (for [[age label] tick-ladder
             :when (<= age (max span-ms min-span-ms))]
         {:label label
          :age-ms age
          :x (past-x (- now-ms age) now-ms span-ms)})))

(def future-span-ms
  "How far right the rail looks. A day: schedules fire on daily-ish cadences,
   and a longer window pushes every one of them into the left edge of the
   future region."
  (* 24 60 minute-ms))

(defn future-x
  "Position of a future instant, in [past-frac, 1]. LINEAR — the future region
   is one day wide, so there is no density problem to warp away, and a linear
   `in six hours` is read correctly without consulting a scale."
  [ms now-ms]
  (+ past-frac
     (* (- 1.0 past-frac)
        (clamp01 (/ (double (max 0 (- ms now-ms))) future-span-ms)))))

(defn fork-line
  "Geometry for one ForkSet: `{:x0 :x1 :dest :reaches-now?}`.

   `x0` is where the line STARTS — when the proposal was filed, which is when
   its branch was minted, so it is the divergence point. A long-open proposal
   visibly runs alongside more trunk than a fresh one.

   `x1` is where it ENDS, and that is the load-bearing part. A ready ForkSet
   ends AT now: its final commit is concurrent with the present and can be
   merged into it. A conflicted one stops short — the line cannot reach the
   present, which is the same fact its Futures routing expresses. One whose
   tier has not arrived yet stops a hair short and says nothing; it must not
   claim readiness it has not established, and it must not JUMP when the tier
   lands, so the neutral position sits between the two outcomes."
  [{:keys [created-at intent tier]} now-ms span-ms]
  (let [dest (fs/destination intent tier)
        created-ms (if created-at (.getTime created-at) now-ms)
        x1 (case dest
             :tasks past-frac
             :futures (- past-frac blocked-gap)
             (- past-frac (/ blocked-gap 2.0)))]
    {:dest dest
     :x0 (min (past-x created-ms now-ms span-ms) x1)
     :x1 x1
     :reaches-now? (= dest :tasks)}))

(defn span-ms
  "How far back the rail reaches: the age of the oldest commit shown, floored so
   a young workspace still gets a usable axis."
  [commits now-ms]
  (let [oldest (reduce min now-ms (map :ms commits))]
    (max min-span-ms (- now-ms oldest))))
