(ns is.simm.model.fractional-index
  "Fractional indexing for maintaining order of blocks.

  Generates lexicographically sortable strings that allow O(1) insertions
  without renumbering existing blocks.

  Based on:
  - https://www.figma.com/blog/realtime-editing-of-ordered-sequences/
  - https://github.com/rocicorp/fractional-indexing

  The alphabet here is printable ASCII (33-126), NOT the base-62 `0-9A-Za-z`
  of the reference implementation. Every worked example in this file was
  carried over from that implementation unchanged, so none of them described
  what this code does — see `fractional-index-test`, which computes them.

  Example:
    (generate-key-between nil nil)           ;; => \"P0\"
    (generate-key-between \"P0\" nil)         ;; => \"P0P\"
    (generate-key-between nil \"P0\")         ;; => \"80\"")

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private base-digits
  "Digits used for base-95 encoding (ASCII printable chars, excluding space).
   Using chars 33-126: ! \" # $ % & ' ( ) * + , - . / 0-9 : ; < = > ? @ A-Z [ \\ ] ^ _ ` a-z { | } ~"
  (mapv char (range 33 127)))

(def ^:private base (count base-digits))  ; 94

(def ^:private base-half (quot base 2))   ; 47

;; Special characters for positions
(def ^:private min-char (first base-digits))   ; "!"
(def ^:private max-char (last base-digits))    ; "~"

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- char->digit
  "Convert a character to its position in base-digits.

  CLJS-safe: handles both real chars (CLJ) and single-char strings (CLJS,
  where `(vec \"a0\")` yields strings rather than chars and `(int \"a\")`
  returns 0 due to NaN coercion, which silently broke the both-given
  branch of `generate-key-between`)."
  [c]
  (let [code #?(:clj (int c)
                :cljs (.charCodeAt (str c) 0))]
    (- code 33)))

(defn- digit->char
  "Convert a digit (0-93) to its corresponding character."
  [d]
  (nth base-digits d))

(defn- increment-string
  "The next string after `s` in the order these keys are compared in, which is
   plain lexicographic comparison over the printable-ASCII alphabet.

   The carry-out case is what makes this not simply arithmetic. When every
   digit overflows — `s` is all `~` — the loop has zeroed the whole vector, and
   the old code returned `min-char` prepended to THOSE zeroed digits: `\"~~\"`
   incremented to `\"!!!\"`, which sorts BELOW its input. Appending `min-char`
   to the ORIGINAL string is the correct answer, because a string is always
   less than itself plus anything."
  [s]
  (let [len (count s)]
    (loop [i (dec len)
           chars (vec s)
           carry 1]
      (if (or (< i 0) (zero? carry))
        (if (pos? carry)
          (str s min-char)
          (apply str chars))
        (let [digit (char->digit (chars i))
              new-digit (+ digit carry)]
          (if (>= new-digit base)
            (recur (dec i) (assoc chars i min-char) 1)
            (recur (dec i) (assoc chars i (digit->char new-digit)) 0)))))))

(defn- midpoint
  "Calculate the midpoint character between two digits."
  [a b]
  (digit->char (quot (+ a b) 2)))

;; =============================================================================
;; Key Generation
;; =============================================================================

(defn generate-key-between
  "Generate a fractional index between two positions.

   Args:
     a - Lower bound key (string), or nil for start
     b - Upper bound key (string), or nil for end

   Returns: A string that sorts lexicographically between a and b.

   Examples (COMPUTED, not copied — the numbers here described the base-62
   alphabet of the JavaScript library this was ported from, and none of them
   was ever a value this code returns):
     (generate-key-between nil nil)          => \"P0\"
     (generate-key-between \"P0\" nil)        => \"P0P\"
     (generate-key-between nil \"P0\")        => \"80\"
     (generate-key-between \"P0\" \"P0P\")     => \"P08\""
  [a b]
  (cond
    ;; Both nil: return initial key
    (and (nil? a) (nil? b))
    (str (digit->char base-half) "0")

    ;; Only a given: append to get next
    (nil? b)
    (str a (digit->char base-half))

    ;; Only b given: a key strictly BELOW b.
    ;;
    ;; Halving b's leading digit converges on 0 in about seven steps, and the
    ;; old code answered that case with `(digit->char (dec base))` — the
    ;; HIGHEST character. MEASURED: inserting seven times at the top of a list,
    ;; the seventh key sorted to the bottom, and from the eighth on the halving
    ;; restarted and reissued keys already in use, so two blocks shared an
    ;; order. Once b is at the floor there is no room to the left of its first
    ;; digit, so keep that digit and find room further in.
    (nil? a)
    (let [first-digit (char->digit (first b))]
      (cond
        (pos? first-digit) (str (digit->char (quot first-digit 2)) "0")
        (seq (subs b 1))   (str min-char (generate-key-between nil (subs b 1)))
        ;; b is `min-char` alone: nothing sorts below it, and no key this
        ;; namespace generates is ever a bare min-char. Refuse loudly rather
        ;; than return something that breaks the ordering silently, which is
        ;; the failure this whole branch exists to end.
        :else (throw (ex-info "no order key sorts below this one"
                              {:error :fractional-index/at-floor :b b}))))

    ;; Both given: find midpoint
    :else
    (let [a-chars (vec a)
          b-chars (vec b)
          max-len (max (count a-chars) (count b-chars))]
      (loop [i 0
             result []]
        (if (>= i max-len)
          ;; If we've exhausted both strings, append a midpoint char
          (apply str (conj result (digit->char base-half)))
          (let [a-digit (if (< i (count a-chars))
                         (char->digit (a-chars i))
                         0)
                b-digit (if (< i (count b-chars))
                         (char->digit (b-chars i))
                         base)
                diff (- b-digit a-digit)]
            (cond
              ;; Digits are the same, continue
              (= a-digit b-digit)
              (recur (inc i) (conj result (digit->char a-digit)))

              ;; Difference of 1, need to go deeper
              (= diff 1)
              (if (< i (count a-chars))
                ;; a has more digits, increment from there
                (let [rest-a (apply str (subvec a-chars (inc i)))
                      incremented (if (seq rest-a)
                                   (increment-string rest-a)
                                   (str (digit->char 1)))]
                  (apply str (concat result [(digit->char a-digit)] incremented)))
                ;; a is shorter, append midpoint after b's digit - 1
                (apply str (concat result [(digit->char (dec b-digit))] [(digit->char base-half)])))

              ;; Room to insert
              :else
              (apply str (conj result (midpoint a-digit b-digit))))))))))
