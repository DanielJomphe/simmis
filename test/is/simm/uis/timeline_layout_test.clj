(ns is.simm.uis.timeline-layout-test
  "The rail's geometry states things about the world — `this is older than
   that`, `this one can merge and that one cannot` — so the claims are worth
   asserting directly rather than eyeballing in a browser."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.uis.web.desktop.timeline-layout :as tl]))

(def ^:private now 1000000000000)
(def ^:private minute 60000)
(def ^:private hour (* 60 minute))
(def ^:private day (* 24 hour))

(defn- inst [ms] (java.util.Date. (long ms)))

(deftest past-region-is-ordered-and-bounded
  (let [span (* 7 day)
        x #(tl/past-x % now span)]
    (testing "now sits at the boundary, the span's start at the left edge"
      (is (== tl/past-frac (x now)))
      (is (zero? (x (- now span)))))
    (testing "older is further left, without exception"
      (let [xs (map x [now (- now hour) (- now day) (- now (* 3 day)) (- now span)])]
        (is (apply > xs) (str "not monotonic: " (vec xs)))))
    (testing "nothing escapes the past region, including a clock skew from the future"
      (is (<= 0.0 (x (- now (* 100 day))) tl/past-frac))
      (is (== tl/past-frac (x (+ now day))) "a future instant clamps to now"))))

(deftest past-region-spends-resolution-where-the-density-is
  (testing "the last hour gets more width than the day before it"
    ;; The point of the log warp. On a linear axis over a week the last hour
    ;; would be 0.6% of the rail — an unclickable target, and the demo scrubs
    ;; back to `this morning`.
    (let [span (* 7 day)
          x #(tl/past-x % now span)
          last-hour (- (x now) (x (- now hour)))
          previous-day (- (x (- now hour)) (x (- now day)))]
      (is (> last-hour previous-day)))))

(deftest axis-ticks-describe-only-what-exists
  (testing "a two-hour-old workspace gets no 1mo gridline"
    (let [labels (set (map :label (tl/axis-ticks now (* 2 hour))))]
      (is (contains? labels "5m"))
      (is (contains? labels "1h"))
      (is (not (contains? labels "1d")))
      (is (not (contains? labels "1mo")))))
  (testing "ticks land inside the past region, ordered like the ages they name"
    (let [ticks (tl/axis-ticks now (* 30 day))]
      (is (seq ticks))
      (is (every? #(<= 0.0 (:x %) tl/past-frac) ticks))
      (is (apply > (map :x ticks)) "older rungs further left"))))

(deftest a-ready-fork-reaches-now-and-a-blocked-one-does-not
  (let [span (* 7 day)
        line (fn [intent tier]
               (tl/fork-line {:created-at (inst (- now day))
                              :intent intent :tier tier}
                             now span))]
    (testing "mergeable: the line ends AT the present — it could BE the present"
      (let [{:keys [x1 dest reaches-now?]} (line :change :reviewable)]
        (is (= :tasks dest))
        (is reaches-now?)
        (is (== tl/past-frac x1))))
    (testing "trivial is mergeable too"
      (is (:reaches-now? (line :change :trivial))))
    (testing "conflicted: the line is visibly stopped short of the present"
      (let [{:keys [x1 dest reaches-now?]} (line :change :conflict)]
        (is (= :futures dest))
        (is (not reaches-now?))
        (is (< x1 tl/past-frac))))
    (testing "a budget is a future whatever its tier — it is not a patch to land"
      (is (not (:reaches-now? (line :budget :trivial)))))
    (testing "tier not yet computed: neutral, and it claims nothing"
      (let [{:keys [x1 dest reaches-now?]} (line :change nil)]
        (is (= :unclassified dest))
        (is (not reaches-now?))
        (is (< (:x1 (line :change :conflict)) x1 tl/past-frac)
            "between the two outcomes, so the tier landing is a nudge not a jump")))))

(deftest a-fork-line-runs-forward-from-where-it-diverged
  (let [span (* 7 day)
        fresh (tl/fork-line {:created-at (inst (- now hour)) :intent :change :tier :reviewable} now span)
        old   (tl/fork-line {:created-at (inst (- now (* 5 day))) :intent :change :tier :reviewable} now span)]
    (is (< (:x0 old) (:x0 fresh)) "the long-open proposal runs alongside more trunk")
    (is (= (:x1 old) (:x1 fresh)) "but both can merge now, so both end at now")))

(deftest a-blocked-forks-line-never-inverts
  (testing "a proposal filed seconds ago but conflicted still draws left-to-right"
    ;; x0 would otherwise land right of x1 and the line would render backwards.
    (let [{:keys [x0 x1]} (tl/fork-line {:created-at (inst now) :intent :change :tier :conflict}
                                        now (* 7 day))]
      (is (<= x0 x1)))))

(deftest the-future-region-is-linear-and-bounded
  (let [x #(tl/future-x % now)]
    (is (== tl/past-frac (x now)) "now is the boundary from both sides")
    (is (== 1.0 (x (+ now (* 2 tl/future-span-ms)))) "beyond the window clamps to the edge")
    (testing "linear: equal waits are equal distances"
      (let [a (- (x (+ now (* 6 hour))) (x now))
            b (- (x (+ now (* 12 hour))) (x (+ now (* 6 hour))))]
        (is (< (abs (- a b)) 1e-9))))))

(deftest span-floors-a-young-workspace
  (testing "no commits at all still yields a usable axis rather than a divide-by-zero"
    (is (pos? (tl/span-ms [] now))))
  (testing "the span reaches the oldest commit"
    (is (= (* 3 day) (tl/span-ms [{:ms (- now (* 3 day))} {:ms (- now hour)}] now)))))
