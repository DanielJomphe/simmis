(ns is.simm.agents.closed-proposal-test
  "A filed proposal must not brick the agent that was contributing to it.

   MEASURED on dev.simm.is 2026-08-07. A proposal Vár was contributing to was
   filed, which set `:closed true` on her private overlay. From then on EVERY
   `clojure_eval` returned the governance error — `(+ 1 1)` included — because
   `clojure_eval` and `shell` are in `repo-tools`, `wrap-repo-tool` calls
   `repo-fork!` on every execute, and `repo-fork!` routes into
   `overlay-branch!`, which throws when the overlay is closed.

   Her own report: \"Same stale message on every call, including `(+ 1 1)`.\"
   It was not stale. It was the guard, every time.

   The trap that made it permanent: the error tells the agent to run
   `(proposal/release!)`, but that is an SCI verb evaluated THROUGH
   `clojure_eval` — the escape hatch sat behind the door it was meant to open.

   `overlay-branch!`'s own comment claims \"Reads never reach this fn ... only
   writing is refused\". These tests hold it to that."
  (:require [clojure.test :refer [deftest is testing]]
            [muschel.fs :as mfs]
            [is.simm.runtimes.branching]
            [is.simm.agents.room-agents :as ra]))

(def ^:private wrap-repo-tool      #'ra/wrap-repo-tool)
(def ^:private read-only-filesystem #'ra/read-only-filesystem)

(def ^:private room  #uuid "c3768711-9136-4cef-9ca9-f294c05b9fc0")
(def ^:private agent #uuid "6a0f5d17-d005-4c73-aeaa-82219953bf58")

(def ^:private closed-state
  "Verbatim from the production registry — and note what is NOT in it.

   `file!` stamps exactly `{:closed true :title … :proposal …}` and DISCARDS
   `:forks`, `:bases`, `:types` and `:ctx-forks`. An earlier version of this
   test wrote `(assoc closed-state :ctx-forks {…})` to exercise the read-only
   path, which pinned a state the system never produces — so the test passed
   while the production path fell through to a writable trunk workspace."
  {:closed true
   :title "Restructure scratch-ui-thoughts for coherence"
   :proposal "22351be7-a6c2-4196-b3e2-5bcf8d5026a3"})

(defn- tool
  "A stand-in for `clojure_eval` that records whether its body ran and what
   filesystem it was handed."
  [ran seen-fs]
  {:name "clojure_eval"
   :execute (fn [_params tctx]
              (reset! ran true)
              (reset! seen-fs (:filesystem tctx))
              {:ok "(+ 1 1) => 2"})})

(defrecord FakeFS [log]
  mfs/FS
  (-resolve [_ p] (swap! log conj [:resolve p]) p)
  (-cwd [_] "/")
  (-cd! [_ p] (swap! log conj [:cd! p]) p)
  (-exists? [_ p] (swap! log conj [:exists? p]) true)
  (-stat [_ p] {:path p})
  (-list-dir [_ _] [])
  (-read-file [_ p] (swap! log conj [:read p]) "contents")
  (-read-bytes [_ _] (byte-array 0))
  (-open-source [_ _] nil)
  (-open-sink [_ p a] (swap! log conj [:WROTE p a]) :sink)
  (-mkdir [_ p] (swap! log conj [:WROTE-mkdir p]) true)
  (-delete [_ p] (swap! log conj [:WROTE-delete p]) true)
  (-rename [_ f t] (swap! log conj [:WROTE-rename f t]) true)
  (-touch [_ p] (swap! log conj [:WROTE-touch p]) true)
  (-chmod [_ p m] (swap! log conj [:WROTE-chmod p m]) true)
  (-symlink [_ t l] (swap! log conj [:WROTE-symlink t l]) true)
  (-chown [_ p o g] (swap! log conj [:WROTE-chown p o g]) true)
  (-sandbox-relativize [_ p] p)
  (-physical-path [_ p] p))

;; =============================================================================
;; The tool must run
;; =============================================================================

(def ^:private repo-scope #uuid "227e7b2d-5fa1-4caf-92ef-71837b927e6d")

(defn- with-repo
  "Run `f` with a room that HAS a repo and a stubbed fork workspace.

   Both stubs are load-bearing. `room-repo-scope` reads the system DB, and a
   dev database has no repo for this room — without the stub `repo-fork!`
   short-circuits on `(when-let [scope ...])` and never reaches the guard, so
   every assertion below passes against the BROKEN code too. That false green
   is exactly what happened on the first version of this test; it was caught
   only by reverting the fix and seeing the suite stay green."
  [fs f]
  (with-redefs-fn {#'ra/room-repo-scope (constantly repo-scope)
                   #'is.simm.runtimes.branching/repo-fork-workspace
                   (constantly {:filesystem fs :workspace {:stub true}})}
    f))

(deftest a-closed-proposal-does-not-block-evaluation
  (testing "the tool body runs, on the state file! ACTUALLY produces"
    ;; No :ctx-forks — the real post-filing token.
    (let [ran (atom false) seen (atom :unset)]
      (with-repo (->FakeFS (atom []))
        (fn []
          (let [t (wrap-repo-tool (tool ran seen) (fn [] (atom closed-state)) room agent)
                r ((:execute t) {:code "(+ 1 1)"} {})]
            (is (true? @ran) "the inner tool ran")
            (is (= {:ok "(+ 1 1) => 2"} r) "and its own result came back"))))))

  (testing "EVERY tool still runs after filing — including clojure_eval"
    ;; The original bug: refusing outright bricked the agent, because
    ;; `(proposal/release!)` is evaluated THROUGH clojure_eval.
    (let [ran (atom 0)]
      (with-repo (->FakeFS (atom []))
        (fn []
          (doseq [n ["read_file" "grep" "write_file" "shell" "clojure_eval"]]
            (let [t (wrap-repo-tool (assoc (tool (atom false) (atom nil)) :name n
                                           :execute (fn [_ _] (swap! ran inc) :ok))
                                    (fn [] (atom closed-state)) room agent)]
              ((:execute t) {} {})))
          (is (= 5 @ran) "all five ran")))))

  (testing "...but NONE of them can write, even with no tree to wrap"
    ;; THE regression. Handing the tool nothing let it resolve its own
    ;; workspace — writable — so a write after filing reached trunk. And
    ;; `default-repo-workspace` resolves nil outside a live room context, so
    ;; "wrap the default" alone was not enough: the wrapper must refuse on a
    ;; nil delegate too.
    (let [seen (atom :unset)]
      (with-repo (->FakeFS (atom []))
        (fn []
          (let [t (wrap-repo-tool (assoc (tool (atom false) seen) :name "clojure_eval")
                                  (fn [] (atom closed-state)) room agent)]
            ((:execute t) {} {})
            (is (some? @seen)
                "a filesystem WAS injected — passing nothing means trunk, writable")
            (let [e (try (mfs/-open-sink @seen "/x" false) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :proposal-closed (:type (ex-data e))) "writes refuse"))
            (is (nil? (mfs/-read-file @seen "/x")) "and reads answer empty"))))))

  (testing "with a fork still open, the tool gets that tree READ-ONLY"
    (let [ran (atom false) seen (atom :unset)
          ov  (atom (assoc closed-state :ctx-forks {[repo-scope agent] :fork}))]
      (with-repo (->FakeFS (atom []))
        (fn []
          (let [t (wrap-repo-tool (assoc (tool ran seen) :name "clojure_eval")
                                  (fn [] ov) room agent)]
            ((:execute t) {} {})
            (is (some? @seen) "a filesystem was injected")
            (is (= "contents" (mfs/-read-file @seen "/a.txt")) "reads work")
            (let [e (try (mfs/-open-sink @seen "/a.txt" false)
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= :proposal-closed (:type (ex-data e))) "writes refuse")))))))

  (testing "no overlay at all — unchanged, ordinary ungoverned case"
    (let [ran (atom false) seen (atom :unset)]
      (with-repo (->FakeFS (atom []))
        (fn []
          (let [t (wrap-repo-tool (tool ran seen) (fn [] (atom nil)) room agent)]
            (is (= {:ok "(+ 1 1) => 2"} ((:execute t) {} {})))
            (is (true? @ran))))))))

(deftest read-only-tools-never-mint-a-fork
  (testing "a pure READ tool under an open proposal does not mint"
    ;; `repo-fork!` says "minted on first WRITE". Before repo-write-tools it
    ;; minted on every call, so one `grep` produced a canonical branch
    ;; identical to trunk and file! then ran a full test suite over it.
    (let [minted (atom 0)
          ov (atom {:title "open" :forks {}})]
      (with-redefs-fn {#'ra/room-repo-scope (constantly repo-scope)
                       #'ra/repo-fork! (fn [& _] (swap! minted inc) nil)
                       #'is.simm.runtimes.branching/repo-fork-workspace
                       (constantly {:filesystem (->FakeFS (atom [])) :workspace {}})}
        (fn []
          (doseq [read-tool ["read_file" "grep" "glob" "clj_kondo" "run_tests"]]
            (let [t (wrap-repo-tool (assoc (tool (atom false) (atom nil)) :name read-tool)
                                    (fn [] ov) room agent)]
              ((:execute t) {} {})))
          (is (zero? @minted) "five read tools, zero forks minted")))))

  (testing "a WRITE-capable tool does mint"
    (let [minted (atom 0)
          ov (atom {:title "open" :forks {}})]
      (with-redefs-fn {#'ra/room-repo-scope (constantly repo-scope)
                       #'ra/repo-fork! (fn [& _] (swap! minted inc) nil)
                       #'is.simm.runtimes.branching/repo-fork-workspace
                       (constantly {:filesystem (->FakeFS (atom [])) :workspace {}})}
        (fn []
          (doseq [w ["write_file" "edit_file" "clojure_edit" "shell" "clojure_eval"]]
            (let [t (wrap-repo-tool (assoc (tool (atom false) (atom nil)) :name w)
                                    (fn [] ov) room agent)]
              ((:execute t) {} {})))
          (is (= 5 @minted) "every write-capable tool mints"))))))

;; =============================================================================
;; ... but writes through a closed fork are still refused
;; =============================================================================

(deftest reads-pass-and-writes-refuse
  (let [log (atom [])
        ro  (read-only-filesystem (->FakeFS log) closed-state)]

    (testing "every read delegates to the underlying filesystem"
      (is (= "contents" (mfs/-read-file ro "/a.txt")))
      (is (true? (mfs/-exists? ro "/a.txt")))
      (is (= "/a.txt" (mfs/-resolve ro "/a.txt")))
      (is (= [] (mfs/-list-dir ro "/")))
      (is (= {:path "/a.txt"} (mfs/-stat ro "/a.txt"))))

    (testing "every write refuses, and never reaches the real filesystem"
      (doseq [[op f] [["write"   #(mfs/-open-sink ro "/a.txt" false)]
                      ["mkdir"   #(mfs/-mkdir ro "/d")]
                      ["delete"  #(mfs/-delete ro "/a.txt")]
                      ["rename"  #(mfs/-rename ro "/a" "/b")]
                      ["touch"   #(mfs/-touch ro "/a")]
                      ["chmod"   #(mfs/-chmod ro "/a" 0)]
                      ["symlink" #(mfs/-symlink ro "/a" "/b")]
                      ["chown"   #(mfs/-chown ro "/a" "u" "g")]]]
        (let [e (try (f) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) (str op " must throw"))
          (is (= :proposal-closed (:type (ex-data e))) (str op " reports why"))
          (is (= op (:op (ex-data e))) (str op " names the operation")))))

    (testing "not one write reached the underlying filesystem"
      (is (empty? (filter #(re-find #"WROTE" (str (first %))) @log))))

    (testing "the refusal names the proposal, so the agent can act on it"
      (let [e (try (mfs/-open-sink ro "/a.txt" false)
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (re-find #"Restructure scratch-ui-thoughts" (.getMessage e)))
        (is (re-find #"22351be7" (.getMessage e)))
        (is (re-find #"proposal/release!" (.getMessage e))
            "and still points at the recovery verb — which is now reachable")))))

;; =============================================================================
;; The guard itself is untouched for genuine writes
;; =============================================================================

(deftest the-write-guard-still-refuses
  (testing "overlay-branch! still throws on a closed overlay"
    ;; The governance guarantee this whole mechanism exists for. kb/* and
    ;; kontor/* writes go through here and must keep refusing — the fix
    ;; deliberately changes only the TOOL wrapper, not the write path.
    (let [ob #'ra/overlay-branch!
          e (try (ob (atom closed-state) (random-uuid) agent :kb) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "a governed KB write on a closed proposal still refuses")
      (is (= :proposal-closed (:type (ex-data e)))))))
