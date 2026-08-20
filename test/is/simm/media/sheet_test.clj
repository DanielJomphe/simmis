(ns is.simm.media.sheet-test
  "Drives the whole spreadsheet path the way an AGENT drives it: a REAL .xlsx
   (written by POI, with interdependent formulas, a number format and a bold
   style), opened through a REAL SCI sandbox with the `sheet/*` vocabulary
   installed — open → read a computed cell → change an input → recalc →
   observe the dependents move → save a patched copy.

   The filesystem is a temp dir rather than a room drive; `sheet/sci-namespace`
   takes the fs as a parameter precisely so the model is testable without
   booting a room."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [is.simm.media.sheet :as sheet]
            [sci.core :as sci])
  (:import [java.io File FileOutputStream]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.poi.ss.usermodel WorkbookFactory CellType]
           [org.apache.poi.xssf.usermodel XSSFWorkbook]))

;; =============================================================================
;; Fixture: a real workbook with a real dependency chain
;;
;;   Inputs!B1 = 0.10   (growth)      Model!B1 = =Inputs!B2*(1+Inputs!B1)
;;   Inputs!B2 = 1000   (base)        Model!B2 = =B1*(1+Inputs!B1)
;;                                    Model!B3 = =SUM(B1:B2)          <- 2 hops down
;;                                    Model!B4 = =NOTAFUNCTION(B1)    <- unimplemented
;;                                    Model!B5 = =Inputs!B2/0         <- #DIV/0!
;; =============================================================================

(def ^:dynamic *dir* nil)

(defn- write-fixture! ^File [^File dir]
  (let [f (io/file dir "model.xlsx")]
    (with-open [wb (XSSFWorkbook.)
                out (FileOutputStream. f)]
      (let [bold (doto (.createCellStyle wb)
                   (.setFont (doto (.createFont wb) (.setBold true))))
            pct  (doto (.createCellStyle wb)
                   (.setDataFormat (.getFormat (.createDataFormat wb) "0.00%")))
            inputs (.createSheet wb "Inputs")
            model  (.createSheet wb "Model")
            put! (fn [sh r c v & [style]]
                   (let [row (or (.getRow sh (int r)) (.createRow sh (int r)))
                         cell (.createCell row (int c))]
                     (cond
                       (and (string? v) (str/starts-with? v "=")) (.setCellFormula cell (subs v 1))
                       (number? v) (.setCellValue cell (double v))
                       :else (.setCellValue cell ^String (str v)))
                     (when style (.setCellStyle cell style))
                     cell))]
        ;; Inputs
        (put! inputs 0 0 "growth" bold)
        (put! inputs 0 1 0.10 pct)          ;; Inputs!B1 — a PERCENT-formatted cell
        (put! inputs 1 0 "base" bold)
        (put! inputs 1 1 1000)              ;; Inputs!B2
        ;; Model
        (put! model 0 0 "year 1" bold)
        (put! model 0 1 "=Inputs!B2*(1+Inputs!B1)")   ;; Model!B1 = 1100
        (put! model 1 0 "year 2" bold)
        (put! model 1 1 "=B1*(1+Inputs!B1)")          ;; Model!B2 = 1210
        (put! model 2 0 "total" bold)
        (put! model 2 1 "=SUM(B1:B2)")                ;; Model!B3 = 2310
        (put! model 3 0 "broken" bold)
        (put! model 3 1 "=NOTAFUNCTION(B1)")          ;; Model!B4 = #NAME?
        (put! model 4 0 "bad math" bold)
        (put! model 4 1 "=Inputs!B2/0")               ;; Model!B5 = #DIV/0!
        (.write wb out)))
    f))

(use-fixtures :each
  (fn [t]
    (let [dir (.toFile (Files/createTempDirectory "simmis-sheet-test"
                                                  (make-array FileAttribute 0)))]
      (try
        (write-fixture! dir)
        (binding [*dir* dir] (t))
        (finally
          (run! io/delete-file (reverse (file-seq dir))))))))

;; =============================================================================
;; The agent's sandbox: a real SCI ctx with sheet/* over the temp dir
;; =============================================================================

(defn- temp-fs []
  {:read-bytes (fn [path]
                 (let [f (io/file *dir* (str/replace path #"^/" ""))]
                   (when (.exists f)
                     (Files/readAllBytes (.toPath f)))))
   :stat (fn [path]
           (let [f (io/file *dir* (str/replace path #"^/" ""))]
             (when (.exists f) {:size (.length f) :type :file})))
   :write-file! (fn [path ^bytes bs _mime]
                  (let [f (io/file *dir* (str/replace path #"^/" ""))]
                    (io/make-parents f)
                    (with-open [o (FileOutputStream. f)] (.write o bs))
                    {:fs.node/id (random-uuid) :fs.node/blob "test-blob"}))})

(defn- sandbox []
  (let [ctx (sci/init {})]
    (sci/add-namespace! ctx 'sheet (sheet/sci-namespace (temp-fs) :test-room))
    ctx))

(defn- ev
  "Evaluate agent code in the sandbox — the exact path a `clojure_eval` tool
   call takes."
  [ctx code]
  (sci/eval-string* ctx code))

;; =============================================================================

(deftest open-returns-a-handle-and-a-summary-not-the-grid
  (let [ctx (sandbox)
        r (ev ctx "(sheet/open \"/model.xlsx\")")]
    (testing "a handle, not a workbook"
      (is (string? (:handle r)) (pr-str r))
      (is (nil? (:sheets (:wb r)))))
    (testing "sheet names + dimensions"
      (is (= ["Inputs" "Model"] (mapv :name (:sheets r))))
      (is (= [2 5] (mapv :rows (:sheets r))))
      (is (= [2 2] (mapv :cols (:sheets r))))
      (is (= 5 (:formulas r))))
    (testing "the header row is previewed — it says what the columns MEAN"
      (is (= ["growth" 0.1] (:first-row (first (:sheets r))))))
    (testing "sheets"
      (is (= ["Inputs" "Model"] (ev ctx (str "(sheet/sheets \"" (:handle r) "\")")))))))

(deftest reads-computed-cells-recalculated-by-our-own-interpreter
  (let [ctx (sandbox)
        h (:handle (ev ctx "(sheet/open \"/model.xlsx\")"))
        get* (fn [ref] (ev ctx (format "(sheet/get \"%s\" \"%s\")" h ref)))]
    (testing "formula cells carry BOTH the value and the formula text"
      (is (= {:t :num :v 1100.0 :ref "Model!B1" :f "=Inputs!B2*(1+Inputs!B1)"} (get* "Model!B1")))
      (is (= 1210.0 (:v (get* "Model!B2"))))
      (is (= 2310.0 (:v (get* "Model!B3")))))
    (testing "a literal has no :f"
      (is (= {:t :num :v 1000.0 :ref "Inputs!B2"} (get* "Inputs!B2"))))
    (testing "a cell that was never written is blank, not an error"
      (is (= {:t :blank :ref "Model!Z99"} (get* "Model!Z99"))))
    (testing "bounded range read"
      (let [r (ev ctx (format "(sheet/range \"%s\" \"Model!A1:B3\")" h))]
        (is (= "Model!A1:B3" (:ref r)))
        (is (= [["year 1" 1100.0] ["year 2" 1210.0] ["total" 2310.0]]
               (mapv #(mapv :v %) (:rows r))))))))

(deftest deps-and-dependents-answer-what-a-converter-cannot
  (let [ctx (sandbox)
        h (:handle (ev ctx "(sheet/open \"/model.xlsx\")"))]
    (testing "what feeds this cell — one hop into the dependency graph"
      (is (= ["Model!B1:B2"] (:deps (ev ctx (format "(sheet/deps \"%s\" \"Model!B3\")" h)))))
      (is (= ["Inputs!B1" "Inputs!B2"]
             (:deps (ev ctx (format "(sheet/deps \"%s\" \"Model!B1\")" h))))))
    (testing "a literal has no precedents, and says so"
      (let [r (ev ctx (format "(sheet/deps \"%s\" \"Inputs!B2\")" h))]
        (is (= [] (:deps r)))
        (is (string? (:note r)))))
    (testing "what breaks if this input moves"
      (let [r (ev ctx (format "(sheet/dependents \"%s\" \"Inputs!B1\")" h))]
        (is (= 2 (:count r)))
        (is (= #{"Model!B1" "Model!B2"} (set (map :ref (:dependents r)))))))
    (testing "explain: value + formula + the values behind it"
      (let [r (ev ctx (format "(sheet/explain \"%s\" \"Model!B1\")" h))]
        (is (= 1100.0 (:v (:value r))))
        (is (= "=Inputs!B2*(1+Inputs!B1)" (:f r)))
        (is (= #{"Inputs!B1" "Inputs!B2"} (set (map :range (:inputs r)))))
        (is (= #{0.1 1000.0}
               (set (map #(-> % :cells first :value :v) (:inputs r)))))))
    (testing "formulas: how the model works, without the grid"
      (let [r (ev ctx (format "(sheet/formulas \"%s\" \"Model\")" h))]
        (is (= 5 (:count r)))
        (is (false? (:truncated r)))
        (is (= "=SUM(B1:B2)" (some #(when (= "B3" (:ref %)) (:f %)) (:formulas r))))))))

(deftest what-if-set-recalcs-and-returns-only-the-changed-cells
  (let [ctx (sandbox)
        h (:handle (ev ctx "(sheet/open \"/model.xlsx\")"))
        r (ev ctx (format "(sheet/set! \"%s\" \"Inputs!B1\" 0.05)" h))]
    (testing "the dirty set, not the grid"
      (is (= 4 (:count r)))                       ;; the input + its 3 dependents
      (is (false? (:truncated r)))
      (is (= #{"Inputs!B1" "Model!B1" "Model!B2" "Model!B3"}
             (set (map :ref (:changed r))))))
    (testing "each change carries from → to"
      (let [b3 (some #(when (= "Model!B3" (:ref %)) %) (:changed r))]
        (is (= 2310.0 (-> b3 :from :v)))
        (is (= 2152.5 (-> b3 :to :v)))))          ;; 1050 + 1102.5
    (testing "the model is now in the new state"
      (is (= 1050.0 (:v (ev ctx (format "(sheet/get \"%s\" \"Model!B1\")" h)))))
      (is (= 2152.5 (:v (ev ctx (format "(sheet/get \"%s\" \"Model!B3\")" h))))))
    (testing "recalc is idempotent on a non-volatile model"
      (is (= 0 (:count (ev ctx (format "(sheet/recalc \"%s\")" h))))))
    (testing "the formula cells kept their formulas — we changed an INPUT"
      (is (= "=SUM(B1:B2)" (:f (ev ctx (format "(sheet/get \"%s\" \"Model!B3\")" h))))))
    (testing "set-many! in one recalc"
      (let [r2 (ev ctx (format "(sheet/set-many! \"%s\" {\"Inputs!B1\" 0.10 \"Inputs!B2\" 2000})" h))]
        (is (= 5 (:count r2)))
        (is (= 4620.0 (:v (ev ctx (format "(sheet/get \"%s\" \"Model!B3\")" h)))))))
    (testing "an agent can write a FORMULA too"
      (ev ctx (format "(sheet/set! \"%s\" \"Model!B6\" \"=Model!B3*2\")" h))
      (is (= 9240.0 (:v (ev ctx (format "(sheet/get \"%s\" \"Model!B6\")" h))))))))

(deftest save-as-patches-the-original-and-never-rebuilds-it
  (let [ctx (sandbox)
        h (:handle (ev ctx "(sheet/open \"/model.xlsx\")"))
        _ (ev ctx (format "(sheet/set! \"%s\" \"Inputs!B1\" 0.05)" h))
        r (ev ctx (format "(sheet/save-as! \"%s\" \"/out/model-v2.xlsx\")" h))]
    (is (= "/out/model-v2.xlsx" (:path r)) (pr-str r))
    (is (= 1 (:edits r)))
    (testing "the ORIGINAL is untouched"
      (with-open [wb (WorkbookFactory/create (io/file *dir* "model.xlsx"))]
        (is (= 0.10 (.getNumericCellValue (.getCell (.getRow (.getSheet wb "Inputs") 0) 1))))))
    (with-open [wb (WorkbookFactory/create (io/file *dir* "out/model-v2.xlsx"))]
      (let [cell (fn [sh r c] (.getCell (.getRow (.getSheet wb sh) (int r)) (int c)))]
        (testing "the edited input landed"
          (is (= 0.05 (.getNumericCellValue (cell "Inputs" 0 1)))))
        (testing "formulas are still FORMULAS — we patched the input, not the model"
          (is (= CellType/FORMULA (.getCellType (cell "Model" 2 1))))
          (is (= "SUM(B1:B2)" (.getCellFormula (cell "Model" 2 1)))))
        (testing "formatting survived because we never rebuilt the file"
          (is (true? (.getBold (.getFontAt wb (.getFontIndexAsInt
                                               (.getCellStyle (cell "Inputs" 0 0)))))))
          (is (= "0.00%" (.getDataFormatString (.getCellStyle (cell "Inputs" 0 1))))))))))

(deftest failure-modes-are-data-the-agent-can-act-on
  (let [ctx (sandbox)
        h (:handle (ev ctx "(sheet/open \"/model.xlsx\")"))]
    (testing "a formula rechentafel does not implement → Excel's own #NAME?, not a crash"
      (let [r (ev ctx (format "(sheet/get \"%s\" \"Model!B4\")" h))]
        (is (= {:t :err :v :name :text "#NAME?" :ref "Model!B4"
                :f "=NOTAFUNCTION(B1)"} r))))
    (testing "a division by zero → #DIV/0!, and it is visible in the model"
      (is (= "#DIV/0!" (:text (ev ctx (format "(sheet/get \"%s\" \"Model!B5\")" h))))))
    (testing "an unknown sheet names the sheets that DO exist"
      (let [r (ev ctx (format "(sheet/get \"%s\" \"Nope!A1\")" h))]
        (is (= :unknown-sheet (:error r)))
        (is (= ["Inputs" "Model"] (:sheets r)))))
    (testing "a ref that is not a ref"
      (is (= :bad-ref (:error (ev ctx (format "(sheet/get \"%s\" \"not-a-ref\")" h))))))
    (testing "a formula that does not parse is reported, and the model is unharmed"
      (let [r (ev ctx (format "(sheet/set! \"%s\" \"Model!B7\" \"=SUM(\")" h))]
        (is (= :bad-formula (:error r)))
        (is (string? (:message r))))
      (is (= 2310.0 (:v (ev ctx (format "(sheet/get \"%s\" \"Model!B3\")" h))))))
    (testing "a stale/never-opened handle"
      (is (= :unknown-handle (:error (ev ctx "(sheet/get \"nope-42\" \"A1\")")))))
    (testing "a missing file"
      (is (= :no-such-file (:error (ev ctx "(sheet/open \"/nope.xlsx\")")))))
    (testing "a corrupt file → :unreadable with a hint, not a POI stack trace"
      (spit (io/file *dir* "corrupt.xlsx") "this is not a zip container")
      (let [r (ev ctx "(sheet/open \"/corrupt.xlsx\")")]
        (is (= :unreadable (:error r)))
        (is (string? (:hint r)))))
    (testing "a range too wide to read is REFUSED with its size — not truncated silently"
      (let [r (ev ctx (format "(sheet/range \"%s\" \"Model!A1:Z1000\")" h))]
        (is (= :range-too-large (:error r)))
        (is (= 26000 (:cells r)))
        (is (= sheet/max-range-cells (:limit r)))))
    (testing "a file too large to model is refused BEFORE it is read"
      (let [big (io/file *dir* "big.xlsx")]
        (with-open [o (FileOutputStream. big)]
          (.write o (byte-array (inc sheet/max-open-bytes))))
        (let [r (ev ctx "(sheet/open \"/big.xlsx\")")]
          (is (= :too-large (:error r)))
          (is (string? (:hint r))))))
    (testing "save-as! never mutates in place: it refuses the source path…"
      (is (= :bad-path (:error (ev ctx (format "(sheet/save-as! \"%s\" \"/model.xlsx\")" h))))))
    (testing "…refuses to clobber an existing file…"
      (spit (io/file *dir* "taken.xlsx") "already here")
      (is (= :exists (:error (ev ctx (format "(sheet/save-as! \"%s\" \"/taken.xlsx\")" h))))))
    (testing "…refuses a non-xlsx destination…"
      (is (= :bad-path (:error (ev ctx (format "(sheet/save-as! \"%s\" \"/model.csv\")" h))))))
    (testing "…and refuses a no-op save (this handle changed nothing)"
      (is (= :no-edits (:error (ev ctx (format "(sheet/save-as! \"%s\" \"/fresh.xlsx\")" h))))))
    (testing "close"
      (is (= h (:closed (ev ctx (format "(sheet/close \"%s\")" h)))))
      (is (= :unknown-handle (:error (ev ctx (format "(sheet/get \"%s\" \"A1\")" h))))))))
