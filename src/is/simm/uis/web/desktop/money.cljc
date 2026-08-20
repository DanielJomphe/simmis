(ns is.simm.uis.web.desktop.money
  "How a ledger amount reads on screen.

   Its own namespace, like `timeline-layout`, for the same two reasons: it is
   pure (string in, string out — no signals, no DOM, no ctx) and it is the part
   of the Accounting view that is worth ASSERTING about. The view namespaces
   cannot be loaded outside the browser, so logic that lives in them cannot be
   tested; logic that lives here can, and money is the last thing in this app
   that should go unasserted.

   Nothing here knows about SEK. The commodity is rendered in its own column by
   the view — see `views/accounting`."
  (:require [clojure.string :as str]))

(def group-sep
  "NO-BREAK SPACE between thousands.

   Not a locale call. `toLocaleString`/`Intl` with a hardcoded tag would pick a
   convention for every book in the workspace out of one team's currency, and
   the workspace already writes money this way in its own prose — `5 000 SEK`,
   `50 000 SEK` in the Spend Authority handbook page. Space grouping (ISO 31-0)
   is also the one convention that is unambiguous on both sides of the
   English/Swedish split this app straddles: `1,500` means two different numbers
   in the two, `1 500` means one.

   NO-BREAK, so a figure never wraps in the middle of itself."
  ;; Written as an escape, not as a literal character: a raw U+00A0 in source
  ;; survives no copy-paste and looks exactly like the space it must not be.
  "\u00a0")

(defn format-amount
  "A stored ledger amount as a figure: grouped thousands, at least 2 decimals.

   The server sends the BigDecimal's own `str` (`ops.accounting-report/account-rows`),
   so this is string→string and never goes through a float — `885300` must not
   become `885300.00000000001` on the way to a balance sheet.

   Decimals are PADDED to two and never truncated: the commodity's precision is
   2 and every amount arrives at that scale, but a value that somehow carries
   more digits is still money, and dropping digits from money to tidy a column
   is the wrong trade.

   Negatives keep a leading minus rather than taking accounting parentheses.
   These rows are a TRIAL BALANCE, where a negative is a credit balance — equity
   and income sit there permanently and are not losses — and `(480 000.00)`
   reads as a deficit to everyone who is not an accountant. This view is read by
   founders.

   Anything that is not a plain decimal is returned untouched. This formats; it
   does not judge. A value the server could not render (`kontor.money.Money@…`
   was exactly that, once) is more useful shown than mangled into a figure."
  [amount]
  (let [s (str/trim (str amount))]
    (if-not (re-matches #"[+-]?\d+(?:\.\d+)?" s)
      s
      (let [neg? (str/starts-with? s "-")
            digits (str/replace s #"^[+-]" "")
            dot (str/index-of digits ".")
            int-part (if dot (subs digits 0 dot) digits)
            frac (if dot (subs digits (inc dot)) "")
            frac (if (< (count frac) 2) (str frac (subs "00" (count frac))) frac)
            grouped (->> (reverse int-part)
                         (partition-all 3)
                         (map #(apply str (reverse %)))
                         reverse
                         (str/join group-sep))]
        (str (when neg? "-") grouped "." frac)))))
