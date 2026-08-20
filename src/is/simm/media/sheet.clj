(ns is.simm.media.sheet
  "Live spreadsheet models for agents — the `sheet/*` SCI vocabulary.

   Companion to `dvergr.media.doc` (which turns a PDF into TEXT). A
   spreadsheet is not text: it is a *computation*, and flattening it into
   an LLM context is both useless and ruinous (a 50k-cell sheet is ~2MB of
   tokens). So we hand the agent a HANDLE onto a server-side model and let
   it ASK QUESTIONS — `get`, `range`, `deps`, `dependents`, `explain` — and
   PERTURB it — `set!`, `recalc`. See doc/document-intake-design.md §2.5.

   The model is `rechentafel`: a pure-Clojure Excel interpreter (parser →
   per-cell dependency graph → dirty-set topological recalc, 412 Excel
   functions). Loading is Apache POI (`rechentafel.poi`, values + formula
   STRINGS; POI's cached results are ignored — we re-evaluate).

   The invariants, in order of importance:

   1. **The agent never holds the bytes, and never the whole grid.** Every
      read is bounded (`max-range-cells`); every write returns only the
      cells whose value actually CHANGED (the dirty set), so a what-if
      costs ~10 tokens rather than 2MB.
   2. **We never rebuild a workbook.** `save-as!` re-opens the ORIGINAL
      .xlsx with POI and patches only the cells the agent set. Formatting,
      charts, pivots and conditional formats survive because we never
      touched them — the one thing that makes an in-place spreadsheet edit
      honest (§0.3). It writes a NEW content-addressed blob at a NEW path;
      nothing is ever mutated in place.
   3. **Expected failures are DATA, not stack traces.** Every fn returns a
      map; a failure is `{:error <keyword> :message <string>}` the agent can
      read and act on. A formula rechentafel does not implement evaluates to
      Excel's own `#NAME?` — which is exactly what Excel would show.

   The filesystem is injected (`sci-namespace`), so this ns knows nothing
   about drives, rooms or muschel: `is.simm.agents.room-agents` binds it to
   the room's /drive mount, and tests bind it to a temp dir."
  (:require [rechentafel.eval :as e]
            [rechentafel.cell :as c]
            [rechentafel.address :as addr]
            [rechentafel.rc :as rc]
            [rechentafel.unparse :as unparse]
            [rechentafel.poi :as poi]
            ;; registers the 412 Excel functions into rechentafel's registry
            [rechentafel.functions.all]
            [is.simm.model.blobs :as blobs]
            [clojure.string :as str]
            [taoensso.telemere :as log])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.poi.ss.usermodel WorkbookFactory Workbook Sheet Row Cell]))

;; =============================================================================
;; Limits — the "be willing to say no" tier (doc/document-intake-design.md §6.3)
;; =============================================================================

(def ^:const xlsx-mime
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

;; A full rechentafel model of a very large workbook is millions of cells and
;; will not fit; §6.3 says say no and offer the range the user actually wants.
;; The 10-100MB background-job tier is NOT built (see the PR body).
(def max-open-bytes (* 20 1024 1024))

;; Bounded reads. A range read past this is refused with the cell count, so the
;; agent can narrow it rather than blow its context.
(def max-range-cells 2000)

;; A recalc that changes more cells than this returns a truncated list plus the
;; true count — the agent still learns the blast radius without paying for it.
(def max-changed 200)

(def max-listed 200)

;; Handles are server-side and cheap; they hold a workbook value, so cap them.
(def max-handles 16)
(def handle-ttl-ms (* 30 60 1000))

;; =============================================================================
;; Errors as data
;; =============================================================================

(defn- fail!
  "Throw an agent-actionable failure. `guarded` turns it into a map."
  ([kind msg] (fail! kind msg {}))
  ([kind msg data]
   (throw (ex-info msg (assoc data :error kind)))))

(defmacro ^:private guarded
  "Every sheet/* fn returns DATA. A failure raised by `fail!` carries its own
   :error keyword and becomes {:error … :message …}. Anything else is a bug on
   OUR side, not the agent's: log it and report :internal, rather than let a
   stack trace escape into the turn (or — worse — mislabel it as the agent's
   mistake; classification happens where the failure is RAISED, never here)."
  [& body]
  `(try
     ~@body
     (catch clojure.lang.ExceptionInfo ex#
       (let [d# (ex-data ex#)]
         (if (:error d#)
           (assoc d# :message (ex-message ex#))
           (do (log/log! {:level :warn :id ::sheet-error
                          :msg "sheet/* call failed" :data {:error (str ex#)}})
               {:error :internal :message (ex-message ex#)}))))
     (catch Throwable t#
       (log/log! {:level :warn :id ::sheet-error
                  :msg "sheet/* call failed" :data {:error (str t#)}})
       {:error :internal :message (or (ex-message t#) (str t#))})))

;; Excel's own rendering of an error value. `#NAME?` is what the agent sees
;; when it hits a function rechentafel does not implement — the same thing
;; Excel would show, and something the agent can reason about.
(def ^:private err-text
  {:null "#NULL!" :div0 "#DIV/0!" :value "#VALUE!" :ref "#REF!"
   :name "#NAME?" :num "#NUM!" :na "#N/A" :getting-data "#GETTING_DATA"
   :spill "#SPILL!" :calc "#CALC!" :circular "#REF!"})

(defn- render
  "A rechentafel tagged value → what the agent sees. Errors carry their
   Excel text so the model recognises them without a lookup table."
  [v]
  (case (:t v)
    :err   {:t :err :v (:v v) :text (get err-text (:v v) "#ERROR!")}
    :blank {:t :blank}
    (or v {:t :blank})))

;; =============================================================================
;; Addressing — "Model!C7" / "A1:D20" ⇄ rechentafel cell ids
;; =============================================================================

(defn- idx->name [wb]
  (into {} (map (fn [[n i]] [i n])) (:sheet-names wb)))

(defn- sheet-index [wb sheet-name]
  (if (nil? sheet-name)
    0
    (or (get (:sheet-names wb) sheet-name)
        ;; Excel sheet names are case-insensitive on lookup
        (some (fn [[n i]] (when (.equalsIgnoreCase ^String n ^String sheet-name) i))
              (:sheet-names wb))
        (fail! :unknown-sheet (str "no such sheet: " sheet-name)
               {:sheets (vec (sort-by (:sheet-names wb) (keys (:sheet-names wb))))}))))

(defn- parse-cell-ref
  "\"Model!C7\" / \"C7\" → cell id. Sheet defaults to the first sheet."
  [wb ref]
  (when-not (string? ref)
    (fail! :bad-ref (str "cell ref must be a string like \"Model!C7\", got: " (pr-str ref))))
  (let [[sheet tail] (addr/parse-sheet-prefix (str/trim ref))
        si (sheet-index wb sheet)
        a1 (addr/parse-a1 (str/replace tail "$" ""))]
    (when-not a1
      (fail! :bad-ref (str "not a cell reference: " ref)))
    (c/pack (long si) (long (:row a1)) (long (:col a1)))))

(defn- parse-range-ref
  "\"Model!A1:D20\" / \"A1:D20\" / a single cell → {:sheet :r0 :c0 :r1 :c1}."
  [wb ref]
  (when-not (string? ref)
    (fail! :bad-ref (str "range ref must be a string like \"A1:D20\", got: " (pr-str ref))))
  (let [[sheet tail] (addr/parse-sheet-prefix (str/trim ref))
        si    (sheet-index wb sheet)
        parts (str/split tail #":")
        cells (map #(addr/parse-a1 (str/replace % "$" "")) parts)]
    (when (or (> (count parts) 2) (some nil? cells) (empty? cells))
      (fail! :bad-ref (str "not a range: " ref)))
    (let [[a b] (if (= 1 (count cells)) [(first cells) (first cells)] cells)]
      {:sheet si
       :r0 (min (:row a) (:row b)) :r1 (max (:row a) (:row b))
       :c0 (min (:col a) (:col b)) :c1 (max (:col a) (:col b))})))

(defn- cell-ref-str [wb id]
  (str (get (idx->name wb) (c/sheet id) "?") "!" (c/->a1 id)))

(def ^:private max-excel-row 1048575)
(def ^:private max-excel-col 16383)

(defn- range-ref-str
  "Render a rechentafel range shape as A1. Whole-column / whole-row refs
   collapse to \"A:A\" / \"1:1\" rather than a comically long span."
  [wb {:keys [sheet r0 r1 c0 c1]}]
  (let [nm (get (idx->name wb) sheet "?")
        whole-col? (and (zero? (long r0)) (= (long r1) max-excel-row))
        whole-row? (and (zero? (long c0)) (= (long c1) max-excel-col))]
    (cond
      whole-col? (str nm "!" (addr/col-idx->letters c0) ":" (addr/col-idx->letters c1))
      whole-row? (str nm "!" (inc (long r0)) ":" (inc (long r1)))
      (and (= r0 r1) (= c0 c1)) (str nm "!" (addr/format-a1 r0 c0))
      :else (str nm "!" (addr/format-a1 r0 c0) ":" (addr/format-a1 r1 c1)))))

(defn- range-cells [{:keys [sheet r0 r1 c0 c1]}]
  (for [r (range r0 (inc r1))
        cc (range c0 (inc c1))]
    (c/pack (long sheet) (long r) (long cc))))

(defn- range-size ^long [{:keys [r0 r1 c0 c1]}]
  (* (inc (- (long r1) (long r0))) (inc (- (long c1) (long c0)))))

(defn- contains-cell? [{:keys [sheet r0 r1 c0 c1]} id]
  (and (= (long sheet) (c/sheet id))
       (<= (long r0) (c/row id) (long r1))
       (<= (long c0) (c/col id) (long c1))))

;; =============================================================================
;; Formulas
;; =============================================================================

(defn- formula-str
  "The formula text of a cell, as Excel writes it (leading `=`), or nil for
   a literal. rechentafel stores an R1C1-normalised AST; resolve it at the
   cell and unparse."
  [wb id]
  (when-let [ast (get (:formulas wb) id)]
    (str "=" (unparse/unparse (rc/resolve-at ast (c/row id) (c/col id))))))

;; =============================================================================
;; Loading — bytes → a rechentafel workbook
;; =============================================================================

(defn- load-bytes
  "Bytes → a recalculated rechentafel workbook.

   `rechentafel.poi/load-workbook` takes a PATH (the released 0.1.4 has no
   bytes/stream arity — see the PR body), so we stage the bytes in a temp
   file. That is also the memory-friendlier way to hand a large .xlsx to POI:
   POI's own docs recommend a File over an InputStream precisely because a
   stream must be buffered whole. The temp file is deleted before we return."
  [^bytes bs]
  (let [tmp (.toFile (Files/createTempFile "simmis-sheet" ".xlsx"
                                           (make-array FileAttribute 0)))]
    (try
      (Files/write (.toPath tmp) bs (make-array java.nio.file.OpenOption 0))
      (try
        (poi/load-workbook (.getAbsolutePath tmp))
        (catch Throwable t
          (fail! :unreadable
                 (str "not a readable spreadsheet (.xlsx/.xls): " (or (ex-message t) (str t)))
                 {:hint "if this is a CSV, read it as text; if it is a PDF, use doc/extract-text"})))
      (finally
        (.delete tmp)))))

;; =============================================================================
;; The handle registry — server-side, so the model stays out of the context
;; =============================================================================

(defonce ^:private handles (atom {}))
(defonce ^:private counter (atom 0))

(defn- now-ms [] (System/currentTimeMillis))

(defn- evict-stale!
  "Drop expired handles, then the least-recently-used ones over the cap.
   (The design doc says GC with the turn; there is no turn boundary visible
   from here, so it is TTL + LRU — see the PR body.)"
  []
  (swap! handles
         (fn [hs]
           (let [t (now-ms)
                 live (into {} (remove (fn [[_ h]] (> (- t @(:touched h)) handle-ttl-ms))) hs)]
             (if (<= (count live) max-handles)
               live
               (into {} (drop (- (count live) max-handles)
                              (sort-by (fn [[_ h]] @(:touched h)) live))))))))

(defn- handle-id [h]
  (cond (string? h) h
        (map? h) (or (:handle h) (:id h))
        :else nil))

(defn- lookup [h]
  (let [id (handle-id h)]
    (or (clojure.core/get @handles id)
        (fail! :unknown-handle
               (str "no open workbook for handle " (pr-str id)
                    " — (sheet/open \"/drive/…xlsx\") returns a fresh one")
               {:open (vec (keys @handles))}))))

(defn- touch! [h] (reset! (:touched h) (now-ms)) h)

(defn- wb-of [h] @(:wb (touch! h)))

;; =============================================================================
;; Summaries — what `open` puts in the agent's context (and nothing more)
;; =============================================================================

(defn- sheet-dims
  "Used extent of an MTV sheet. A sheet is a vector of columns; a column is
   {:blocks … :max-row …} — the shape `rechentafel.mtv` publishes."
  [sheet]
  {:rows (inc (reduce (fn [m col] (max m (long (:max-row col -1)))) -1 sheet))
   :cols (count sheet)})

(defn- sheet-cells ^long [sheet]
  (reduce (fn [n col]
            (+ n (reduce (fn [m b] (if (= :empty (:type b)) m (+ m (long (:len b)))) )
                         0 (:blocks col))))
          0 sheet))

(defn- header-preview
  "First row of a sheet (up to 12 cells) — the one thing worth spending
   tokens on up front, because it tells the agent what the columns MEAN."
  [wb si sheet]
  (let [cols (min 12 (count sheet))]
    (vec (for [cc (range cols)]
           (:v (e/get-cell wb (c/pack (long si) 0 (long cc))))))))

(defn- summarize [wb]
  (let [names (idx->name wb)
        sheets (vec (for [[si sheet] (map-indexed vector (:sheets wb))
                          :let [dims (sheet-dims sheet)]]
                      (merge {:name (get names si)} dims
                             {:cells (sheet-cells sheet)}
                             (when (pos? (:rows dims))
                               {:first-row (header-preview wb si sheet)}))))]
    {:sheets sheets
     :cells (reduce + 0 (map :cells sheets))
     :formulas (count (:formulas wb))}))

;; =============================================================================
;; Public operations (bound into SCI by `sci-namespace`)
;; =============================================================================

(defn- do-open [{:keys [read-bytes stat]} scope path]
  (let [size (:size (when stat (stat path)))]
    ;; Refuse BEFORE the bytes are read where the fs can tell us the size.
    (when (and size (> (long size) max-open-bytes))
      (fail! :too-large
             (str "spreadsheet is " (long size) " bytes; the in-process model tops out at "
                  max-open-bytes)
             {:size size :limit max-open-bytes
              :hint "ask for the sheet/range you actually need, or split the file"}))
    (let [bs (read-bytes path)]
      (when-not bs (fail! :no-such-file (str "no such file: " path)))
      (when (> (count bs) max-open-bytes)
        (fail! :too-large
               (str "spreadsheet is " (count bs) " bytes; the in-process model tops out at "
                    max-open-bytes)
               {:size (count bs) :limit max-open-bytes}))
      (let [t0 (now-ms)
            wb (load-bytes bs)
            id (str "sheet-" (swap! counter inc))
            h  {:id id :path path :scope scope
                :sha (blobs/sha256-hex bs) :size (count bs)
                :wb (atom wb) :edits (atom []) :touched (atom (now-ms))}]
        (swap! handles assoc id h)
        (evict-stale!)
        (log/log! {:level :info :id ::opened
                   :msg "spreadsheet model opened"
                   :data {:path path :scope scope :bytes (count bs)
                          :ms (- (now-ms) t0)}})
        (merge {:handle id :path path} (summarize wb))))))

(defn- do-sheets [h]
  (let [wb (wb-of (lookup h))
        names (idx->name wb)]
    (vec (for [si (range (count (:sheets wb)))] (get names si)))))

(defn- do-get [h ref]
  (let [hh (lookup h)
        wb (wb-of hh)
        id (parse-cell-ref wb ref)]
    (cond-> (assoc (render (e/get-cell wb id)) :ref (cell-ref-str wb id))
      (formula-str wb id) (assoc :f (formula-str wb id)))))

(defn- do-range [h ref]
  (let [hh (lookup h)
        wb (wb-of hh)
        rng (parse-range-ref wb ref)
        n (range-size rng)]
    (when (> n max-range-cells)
      (fail! :range-too-large
             (str "that range is " n " cells; reads are capped at " max-range-cells)
             {:cells n :limit max-range-cells
              :hint "narrow the range, or use sheet/get / sheet/deps to ask a question instead"}))
    {:ref (range-ref-str wb rng)
     :rows (vec (for [r (range (:r0 rng) (inc (:r1 rng)))]
                  (vec (for [cc (range (:c0 rng) (inc (:c1 rng)))]
                         (render (e/get-cell wb (c/pack (long (:sheet rng)) (long r) (long cc))))))))}))

(defn- do-deps
  "Direct precedents: what feeds this cell. One hop into rechentafel's
   dependency graph — no scanning, and the reason a 50k-cell sheet is
   answerable at all."
  [h ref]
  (let [hh (lookup h)
        wb (wb-of hh)
        id (parse-cell-ref wb ref)]
    (if-not (contains? (:formulas wb) id)
      {:ref (cell-ref-str wb id) :deps [] :note "not a formula — nothing feeds it"}
      {:ref (cell-ref-str wb id)
       :f (formula-str wb id)
       :deps (vec (sort (map #(range-ref-str wb %) (clojure.core/get (:reads wb) id))))})))

(defn- do-dependents
  "Reverse: which formulas READ this cell (what breaks if it moves). Scans
   the read-shape index — O(#formula cells), microseconds on real workbooks."
  [h ref]
  (let [hh (lookup h)
        wb (wb-of hh)
        id (parse-cell-ref wb ref)
        hits (for [[fid shapes] (:reads wb)
                   :when (some #(contains-cell? % id) shapes)]
               fid)
        hits (sort hits)]
    {:ref (cell-ref-str wb id)
     :count (count hits)
     :dependents (vec (for [fid (take max-listed hits)]
                        {:ref (cell-ref-str wb fid) :f (formula-str wb fid)}))
     :truncated (> (count hits) max-listed)}))

(defn- do-formulas
  "Every formula on a sheet — the 'how does this model work' view, without
   the values. Bounded; a formula-heavy sheet returns the first `max-listed`."
  [h sheet-name]
  (let [hh (lookup h)
        wb (wb-of hh)
        si (sheet-index wb sheet-name)
        ids (sort (filter #(= (long si) (c/sheet %)) (keys (:formulas wb))))]
    {:sheet (get (idx->name wb) si)
     :count (count ids)
     :formulas (vec (for [id (take max-listed ids)]
                      {:ref (c/->a1 id) :f (formula-str wb id)
                       :value (render (e/get-cell wb id))}))
     :truncated (> (count ids) max-listed)}))

(defn- do-explain
  "One-hop explanation of a computed cell: its value, its formula, and the
   value of everything that feeds it. The question an analyst actually asks."
  [h ref]
  (let [hh (lookup h)
        wb (wb-of hh)
        id (parse-cell-ref wb ref)
        shapes (clojure.core/get (:reads wb) id)]
    {:ref (cell-ref-str wb id)
     :value (render (e/get-cell wb id))
     :f (formula-str wb id)
     :inputs (vec (for [shape (sort-by (juxt :sheet :r0 :c0) shapes)
                        :let [n (range-size shape)]]
                    (if (> n 20)
                      {:range (range-ref-str wb shape) :cells n
                       :note "too wide to expand — use sheet/range"}
                      {:range (range-ref-str wb shape)
                       :cells (vec (for [cid (range-cells shape)]
                                     (cond-> {:ref (cell-ref-str wb cid)
                                              :value (render (e/get-cell wb cid))}
                                       (formula-str wb cid) (assoc :f (formula-str wb cid)))))})))}))

(defn- changed-cells
  "The cells whose VALUE actually moved between two workbook states, over the
   candidate set (the dirty set rechentafel computed + the cells we wrote)."
  [wb-before wb-after candidates]
  (->> candidates
       sort
       (keep (fn [id]
               (let [before (e/cell-value wb-before id)
                     after  (e/cell-value wb-after id)]
                 (when (not= before after)
                   {:ref (cell-ref-str wb-after id)
                    :from (render before)
                    :to (render after)}))))))

(defn- apply-edits!
  "Set cells, recalc, and return ONLY what changed. This is the differentiator:
   a what-if costs the agent the size of the dirty set, not the size of the grid.

   `locking` the handle's workbook atom: an agent turn is sequential, but two
   tool calls from different turns must not interleave a read-modify-write."
  [hh pairs]
  (locking (:wb hh)
    (let [wb0 @(:wb hh)
          ;; A formula the PARSER rejects is the agent's mistake, and the only
          ;; failure `set-cell` raises. Classify it here, at the raise site —
          ;; the model is left untouched (we never wrote `staged` back).
          staged (try
                   (reduce (fn [wb [id input]] (e/set-cell wb id input)) wb0 pairs)
                   (catch clojure.lang.ExceptionInfo ex
                     (fail! :bad-formula
                            (str "that formula does not parse: " (ex-message ex)))))
          dirty (:dirty staged)
          wb1 (e/recalc staged)
          candidates (into (set (map first pairs)) dirty)
          changed (changed-cells wb0 wb1 candidates)]
      (reset! (:wb hh) wb1)
      (swap! (:edits hh) into pairs)
      {:changed (vec (take max-changed changed))
       :count (count changed)
       :truncated (> (count changed) max-changed)})))

(defn- coerce-input
  "What the agent may put in a cell: a number, string, boolean, nil (blank),
   or a leading-`=` formula string. Anything else is a mistake worth naming."
  [v]
  (cond
    (or (number? v) (string? v) (boolean? v) (nil? v)) v
    :else (fail! :bad-value
                 (str "a cell takes a number, string, boolean, nil, or a \"=FORMULA\" string; got: "
                      (pr-str v)))))

(defn- do-set! [h ref v]
  (let [hh (lookup h)
        wb (wb-of hh)
        id (parse-cell-ref wb ref)]
    (apply-edits! hh [[id (coerce-input v)]])))

(defn- do-set-many! [h m]
  (when-not (map? m)
    (fail! :bad-value (str "set-many! takes a map of ref → value; got: " (pr-str m))))
  (let [hh (lookup h)
        wb (wb-of hh)
        pairs (vec (for [[ref v] m] [(parse-cell-ref wb ref) (coerce-input v)]))]
    (apply-edits! hh pairs)))

(defn- do-recalc
  "Force a recalc. `set!` already recalcs, so this is for volatile formulas
   (NOW/TODAY/RAND/OFFSET/INDIRECT), which rechentafel re-seeds every pass."
  [h]
  (let [hh (lookup h)]
    (locking (:wb hh)
      (let [wb0 @(:wb hh)
            wb1 (e/recalc wb0)
            candidates (into (set (keys (:formulas wb1))) (:volatile wb1))
            changed (changed-cells wb0 wb1 candidates)]
        (reset! (:wb hh) wb1)
        {:changed (vec (take max-changed changed))
         :count (count changed)
         :truncated (> (count changed) max-changed)}))))

;; -----------------------------------------------------------------------------
;; save-as! — patch the ORIGINAL workbook; never rebuild it
;; -----------------------------------------------------------------------------

(defn- patch-xlsx
  "Re-open the original .xlsx with POI and write ONLY the cells the agent set.
   Everything else — styles, number formats, charts, pivots, conditional
   formatting, images — is untouched, so it survives (doc/document-intake-design
   §0.3). `setForceFormulaRecalculation` makes Excel recompute the dependent
   formulas on open, which is exactly what our own recalc already did."
  ^bytes [^bytes original edits]
  (with-open [in (ByteArrayInputStream. original)
              ^Workbook poi (WorkbookFactory/create in)]
    (doseq [[id input] edits]
      (let [^Sheet sh (.getSheetAt poi (int (c/sheet id)))
            r (int (c/row id))
            cc (int (c/col id))
            ^Row row (or (.getRow sh r) (.createRow sh r))
            ^Cell cell (or (.getCell row cc) (.createCell row cc))]
        (cond
          (and (string? input) (str/starts-with? ^String input "="))
          (.setCellFormula cell (subs input 1))

          (number? input)  (.setCellValue cell (double input))
          (boolean? input) (.setCellValue cell (boolean input))
          (string? input)  (.setCellValue cell ^String input)
          (nil? input)     (.setBlank cell))))
    (.setForceFormulaRecalculation poi true)
    (with-open [out (ByteArrayOutputStream.)]
      (.write poi out)
      (.toByteArray out))))

(defn- do-save-as! [{:keys [read-bytes stat write-file!]} h dest]
  (let [hh (lookup h)
        edits @(:edits hh)]
    (when-not write-file!
      (fail! :read-only "this sandbox has no writable drive"))
    (when-not (and (string? dest) (str/ends-with? (str/lower-case dest) ".xlsx"))
      (fail! :bad-path (str "destination must be an .xlsx path, e.g. \"/drive/model-v2.xlsx\"; got: "
                            (pr-str dest))))
    (when (= dest (:path hh))
      (fail! :bad-path
             (str "refusing to overwrite the original " (:path hh)
                  " — save to a new path; the original blob stays immutable")))
    (when (and stat (stat dest))
      (fail! :exists (str dest " already exists — pick a new name")))
    (when (empty? edits)
      (fail! :no-edits "nothing to save — no cells were changed"))
    ;; The patch must apply to the bytes we MODELLED. If the file moved under
    ;; us, our edits describe a workbook that no longer exists — say so.
    (let [original (read-bytes (:path hh))]
      (when-not original (fail! :no-such-file (str "original is gone: " (:path hh))))
      (when-not (= (blobs/sha256-hex original) (:sha hh))
        (fail! :file-changed
               (str (:path hh) " changed since it was opened — reopen it and redo the edits")))
      (let [patched (patch-xlsx original edits)
            node (write-file! dest patched xlsx-mime)]
        (log/log! {:level :info :id ::saved
                   :msg "spreadsheet patched and saved as a new blob"
                   :data {:from (:path hh) :to dest :edits (count edits)
                          :bytes (count patched)}})
        {:path dest
         :bytes (count patched)
         :edits (count edits)
         :node (str (:fs.node/id node))
         :blob (:fs.node/blob node)
         :note "the original file is unchanged; formatting, charts and pivots are preserved (only the edited cells were rewritten)"}))))

(defn- do-close [h]
  (let [id (handle-id h)]
    (swap! handles dissoc id)
    {:closed id}))

;; =============================================================================
;; The SCI vocabulary
;; =============================================================================

(defn sci-namespace
  "Build the `sheet/*` map for `sci/add-namespace!`.

   `fs` injects the filesystem:
     :read-bytes  (fn [path] → bytes)          required
     :stat        (fn [path] → {:size n} | nil) optional — enables the
                                                pre-read size gate + the
                                                overwrite check
     :write-file! (fn [path bytes mime] → node) optional — without it,
                                                save-as! is unavailable

   `scope` is an opaque key (the room uuid) recorded on each handle."
  [fs scope]
  {'open        (fn [path] (guarded (do-open fs scope path)))
   'sheets      (fn [h] (guarded (do-sheets h)))
   'get         (fn [h ref] (guarded (do-get h ref)))
   'range       (fn [h ref] (guarded (do-range h ref)))
   'deps        (fn [h ref] (guarded (do-deps h ref)))
   'dependents  (fn [h ref] (guarded (do-dependents h ref)))
   'formulas    (fn ([h] (guarded (do-formulas h nil)))
                  ([h sheet] (guarded (do-formulas h sheet))))
   'explain     (fn [h ref] (guarded (do-explain h ref)))
   'set!        (fn [h ref v] (guarded (do-set! h ref v)))
   'set-many!   (fn [h m] (guarded (do-set-many! h m)))
   'recalc      (fn [h] (guarded (do-recalc h)))
   'save-as!    (fn [h dest] (guarded (do-save-as! fs h dest)))
   'close       (fn [h] (guarded (do-close h)))})

(def prompt-block
  "What the agent is told about `sheet/*`. Kept next to the code so the two
   cannot drift."
  (str "\n\nSPREADSHEETS (.xlsx on the drive) are a live MODEL, not text —"
       " `doc/extract-text` returns nothing for them. In clojure_eval:\n"
       "- `(def h (sheet/open \"/drive/model.xlsx\"))` — opens it server-side;"
       " you get a handle + sheet names/dimensions, never the grid\n"
       "- `(sheet/get h \"Model!C7\")` / `(sheet/range h \"Model!A1:D20\")` — bounded reads\n"
       "- `(sheet/deps h \"Model!C7\")` — what FEEDS a cell;"
       " `(sheet/explain h \"Model!C7\")` — its formula + the values behind it;"
       " `(sheet/dependents h \"Inputs!B2\")` — what would break\n"
       "- `(sheet/set! h \"Inputs!B2\" 0.05)` — WHAT-IF: recalculates and returns"
       " only the cells that changed. This is how you answer \"what if growth is 5%\".\n"
       "- `(sheet/save-as! h \"/drive/model-v2.xlsx\")` — writes a NEW file, patching"
       " only the cells you changed (their formatting/charts survive; the original is untouched)\n"
       "Read with sheet/get and sheet/range — never try to dump the whole sheet."))
