(ns is.simm.model.fractional-index-test
  "Block ordering. This namespace had NO tests, which is how the following
   survived: inserting seven times at the top of a list, the seventh key sorted
   to the BOTTOM, and from the eighth on the generator reissued keys that were
   already in use, so two blocks shared an order.

   The one property that matters is `a < k < b` under plain lexicographic
   comparison — that is how the keys are read back (`:block/order` sorted as a
   string). Everything else here is a named instance of it."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.model.fractional-index :as frac]))

(defn- between? [a k b]
  (and (or (nil? a) (neg? (compare a k)))
       (or (nil? b) (neg? (compare k b)))))

(defn- insert!
  "Insert a new key at index `i` of vector `ks`, the way a block editor does."
  [ks i]
  (let [a (when (pos? i) (nth ks (dec i)))
        b (when (< i (count ks)) (nth ks i))
        k (frac/generate-key-between a b)]
    {:key k :a a :b b :ks (vec (concat (take i ks) [k] (drop i ks)))}))

;; =============================================================================
;; The regression
;; =============================================================================

(deftest repeated-insertion-at-the-top-stays-at-the-top
  (testing "the seventh prepend used to sort to the bottom"
    ;; Halving the leading digit reaches 0 in six steps; the seventh had no
    ;; room left and answered with the HIGHEST character in the alphabet.
    (loop [ks [(frac/generate-key-between nil nil)]
           n 0]
      (when (< n 30)
        (let [b (first ks)
              k (frac/generate-key-between nil b)]
          (is (neg? (compare k b))
              (str "prepend #" (inc n) ": " (pr-str k) " must sort below " (pr-str b)))
          (recur (vec (cons k ks)) (inc n)))))))

(deftest keys-are-never-reissued
  (testing "a prepend run used to restart the halving and hand back old keys"
    (let [ks (loop [ks [(frac/generate-key-between nil nil)] n 0]
               (if (>= n 30) ks
                   (recur (vec (cons (frac/generate-key-between nil (first ks)) ks))
                          (inc n))))]
      (is (= (count ks) (count (set ks))) "every order key is distinct")
      (is (= ks (vec (sort ks))) "and the list is in the order it was built"))))

(deftest incrementing-past-the-end-of-the-alphabet-goes-UP
  (testing "a string of all-maximum characters"
    ;; The carry-out case: the loop zeroes every digit, and the result used to
    ;; be min-char prepended to THOSE zeroes — \"~~\" incremented to \"!!!\",
    ;; which sorts below its input. Reached through the diff-of-1 branch.
    (let [a (str \~ \~)
          k (frac/generate-key-between a nil)]
      (is (neg? (compare a k)) (str (pr-str k) " must sort above " (pr-str a))))
    (let [k (frac/generate-key-between "P~" "Q!")]
      (is (between? "P~" k "Q!")))))

(deftest the-floor-is-refused-not-faked
  (testing "nothing sorts below a bare minimum character"
    (is (thrown? clojure.lang.ExceptionInfo
                 (frac/generate-key-between nil "!"))
        "returning a key that breaks the ordering is what this replaced")))

;; =============================================================================
;; The property
;; =============================================================================

(deftest random-insertions-keep-the-list-sorted
  (testing "3000 insertions at random positions"
    (let [bad (atom [])]
      (dotimes [_ 50]
        (loop [ks [(frac/generate-key-between nil nil)] n 0]
          (when (< n 60)
            (let [{:keys [key a b ks]} (insert! ks (rand-int (inc (count ks))))]
              (when-not (between? a key b)
                (swap! bad conj {:a a :k key :b b}))
              (recur ks (inc n))))))
      (is (empty? @bad) (str "ordering violations: " (pr-str (take 3 @bad)))))))

(deftest the-three-insertion-patterns
  (doseq [[label step]
          [[:prepend (fn [ks] (:ks (insert! ks 0)))]
           [:append  (fn [ks] (:ks (insert! ks (count ks))))]
           [:middle  (fn [ks] (:ks (insert! ks (max 1 (quot (count ks) 2)))))]]]
    (testing (str label " 150 times")
      (let [ks (loop [ks [(frac/generate-key-between nil nil)] n 0]
                 (if (>= n 150) ks (recur (step ks) (inc n))))]
        (is (= ks (vec (sort ks))) (str label " keeps the list sorted"))
        (is (= (count ks) (count (set ks))) (str label " issues distinct keys"))))))

;; =============================================================================
;; The worked examples, which were all wrong
;; =============================================================================

(deftest the-documented-examples-are-what-the-code-returns
  (testing "the docstrings described the base-62 reference implementation"
    (is (= "P0"  (frac/generate-key-between nil nil)))
    (is (= "P0P" (frac/generate-key-between "P0" nil)))
    (is (= "80"  (frac/generate-key-between nil "P0")))
    (is (= "P08" (frac/generate-key-between "P0" "P0P")))))
