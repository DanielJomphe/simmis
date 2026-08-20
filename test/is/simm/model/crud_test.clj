(ns is.simm.model.crud-test
  "Tests for CRUD operations, especially page rename scenarios.

   Run via repl-mcp:
   (require '[is.simm.model.crud-test :as ct])
   (ct/run-all-tests)"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [is.simm.model.db :as db]
            [is.simm.model.crud :as crud]
            [is.simm.model.schema :as schema]
            [is.simm.model.seed :as seed]))

;; ============================================================================
;; Test Database Setup
;; ============================================================================

(defn create-test-db
  "Create a fresh in-memory test database with schema"
  []
  (let [cfg {:store {:backend :memory
                     :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}
        _ (d/delete-database cfg)
        _ (d/create-database cfg)
        conn (d/connect cfg)]
    ;; Install schema + the katzen type entities (S/Page etc.) that
    ;; crud/create-page! resolves since the one-way-projection refactor.
    (d/transact conn schema/full-schema)
    (seed/ensure-seed-data! conn)
    conn))

(defn setup-test-database
  "Create a test database (no database entity needed in new model)"
  []
  (let [conn (create-test-db)]
    {:conn conn}))

;; ============================================================================
;; Test Helper Functions
;; ============================================================================

(defn create-test-page
  "Create a page with given title and optional blocks.

   `create-block!` destructures :parent/:order/:content — the old :page-uuid /
   :type / :position keys were silently IGNORED, so every block landed at the
   top level instead of under the page and `list-blocks` returned nothing."
  [conn title & blocks]
  (let [page-uuid (crud/create-page! conn {:title title})]
    (doseq [content blocks]
      (crud/create-block! conn {:parent page-uuid
                                :content content}))
    page-uuid))

(defn get-page-title
  "Get the title of a page by UUID. (crud/get-page was removed in the
   katzen one-way-projection refactor — read the title directly.)"
  [conn page-uuid]
  (d/q '[:find ?title . :in $ ?uuid
         :where [?e :entity/uuid ?uuid] [?e :S.Page/title ?title]]
       @conn page-uuid))

(defn get-block-contents
  "Get all block contents for a page"
  [conn page-uuid]
  (let [blocks (crud/list-blocks @conn page-uuid)]
    (mapv :block/content blocks)))

(defn count-pages-with-title
  "Count how many pages exist with given title.

   `:page/title` is NOT a datom — crud/ synthesizes it on returned maps for
   backward compatibility. The stored attribute is `:S.Page/title` (katzen
   one-way projection), so the old query silently matched nothing."
  [conn title]
  (count (d/q '[:find ?e
                :in $ ?title
                :where
                [?e :S.Page/title ?title]]
              @conn title)))

(defn get-backlink-count
  "Get number of backlinks to a page"
  [conn page-uuid]
  (let [backlinks (crud/get-backlinks @conn page-uuid)]
    (reduce + (map (fn [[_ blocks]] (count blocks)) backlinks))))

;; ============================================================================
;; Tests for Conflict Detection
;; ============================================================================

(deftest test-rename-no-conflict
  (testing "Rename page when no conflict exists"
    (let [{:keys [conn]} (setup-test-database)
          page-uuid (create-test-page conn "Original Title")]

      ;; Rename should succeed
      (let [result (crud/update-page-title! conn page-uuid "New Title" {})]
        (is (:success result) "Rename should succeed")
        (is (= "New Title" (get-page-title conn page-uuid)))
        (is (= 0 (count-pages-with-title conn "Original Title")))
        (is (= 1 (count-pages-with-title conn "New Title")))))))

(deftest test-rename-with-conflict-detection
  (testing "Detect conflict when target title already exists"
    (let [{:keys [conn]} (setup-test-database)
          page1-uuid (create-test-page conn "Page 1")
          page2-uuid (create-test-page conn "Page 2")]

      ;; Try to rename Page 1 to Page 2 (conflict)
      (let [result (crud/update-page-title! conn page1-uuid "Page 2" {})]
        (is (true? (:conflict result)) "Should detect conflict")
        (is (some? (:existing-page-uuid result)) "Should return existing page UUID")
        (is (= page2-uuid (:existing-page-uuid result)))
        ;; Original title should be unchanged
        (is (= "Page 1" (get-page-title conn page1-uuid)))))))

;; ============================================================================
;; Tests for Overwrite Logic
;; ============================================================================

(deftest test-overwrite-existing-page
  (testing "Overwrite existing page with same title"
    (let [{:keys [conn]} (setup-test-database)
          ;; Create "Page A" with some content
          page-a-uuid (create-test-page conn "Page A"
                                       "<p>Content from Page A</p>")
          ;; Create "Page B" that we want to rename to "Page A"
          page-b-uuid (create-test-page conn "Page B"
                                       "<p>Content from Page B</p>")
          ;; Create a third page that references "Page A"
          page-c-uuid (create-test-page conn "Page C"
                                       "<p>Link to [[Page A]]</p>")]

      ;; Perform overwrite: rename Page B to Page A, deleting old Page A
      (let [result (crud/update-page-title! conn page-b-uuid "Page A" {:overwrite? true})]
        (is (:success result) "Overwrite should succeed")

        ;; Page B should now have title "Page A"
        (is (= "Page A" (get-page-title conn page-b-uuid)))

        ;; Old Page A should be deleted
        (is (nil? (get-page-title conn page-a-uuid)) "Old page should be deleted")

        ;; Only one page named "Page A" should exist
        (is (= 1 (count-pages-with-title conn "Page A")))

        ;; References should point to the renamed page (Page B)
        (let [backlinks (crud/get-backlinks @conn page-b-uuid)]
          (is (pos? (count backlinks)) "Renamed page should have backlinks"))))))

;; ============================================================================
;; Tests for Merge Logic
;; ============================================================================

(deftest test-merge-pages
  (testing "Merge two pages with same title"
    (let [{:keys [conn]} (setup-test-database)
          ;; Create "Target" with some blocks
          target-uuid (create-test-page conn "Target"
                                       "<p>Block 1 from target</p>"
                                       "<p>Block 2 from target</p>")
          ;; Create "Source" that we want to rename to "Target"
          source-uuid (create-test-page conn "Source"
                                       "<p>Block 1 from source</p>"
                                       "<p>Block 2 from source</p>")
          ;; Create pages that reference each
          ref-target-uuid (create-test-page conn "Refs Target"
                                           "<p>Link to [[Target]]</p>")
          ref-source-uuid (create-test-page conn "Refs Source"
                                           "<p>Link to [[Source]]</p>")]

      ;; Count backlinks before merge
      (let [target-backlinks-before (get-backlink-count conn target-uuid)
            source-backlinks-before (get-backlink-count conn source-uuid)]

        ;; Perform merge: rename Source to Target, merging content
        (let [result (crud/update-page-title! conn source-uuid "Target" {:merge? true})]
          (is (:success result) "Merge should succeed")

          ;; Source should now have title "Target"
          (is (= "Target" (get-page-title conn source-uuid)))

          ;; Old target page should be deleted
          (is (nil? (get-page-title conn target-uuid)) "Old target page should be deleted")

          ;; Only one page named "Target" should exist
          (is (= 1 (count-pages-with-title conn "Target")))

          ;; Source page should have blocks from both pages
          (let [blocks (get-block-contents conn source-uuid)]
            (is (= 4 (count blocks)) "Should have 4 blocks (2 from each page)"))

          ;; All references should now point to the merged page
          (let [backlinks (get-backlink-count conn source-uuid)]
            (is (= (+ target-backlinks-before source-backlinks-before) backlinks)
                "Merged page should have all backlinks from both pages")))))))

;; ============================================================================
;; Tests for Reference Updates
;; ============================================================================

(deftest test-rename-updates-references
  (testing "Renaming a page updates all wiki link references"
    (let [{:keys [conn]} (setup-test-database)
          target-uuid (create-test-page conn "Old Name")
          ref-page-uuid (create-test-page conn "Referring Page"
                                         "<p>Simple link: [[Old Name]]</p>"
                                         "<p>Custom display: [[Old Name][Custom]]</p>")]

      ;; Rename the target page
      (let [result (crud/update-page-title! conn target-uuid "New Name" {})]
        (is (:success result))
        (is (= 2 (:blocks-updated result)) "Should update 2 blocks")

        ;; Check that references were updated in the referring page
        (let [blocks (get-block-contents conn ref-page-uuid)]
          (is (some #(re-find #"\[\[New Name\]\]" %) blocks)
              "Simple link should be updated")
          (is (some #(re-find #"\[\[New Name\]\[Custom\]\]" %) blocks)
              "Custom display link should preserve display text"))

        ;; Check that backlinks still work
        (let [backlinks (get-backlink-count conn target-uuid)]
          (is (= 2 backlinks) "Should still have 2 backlinks"))))))

;; ============================================================================
;; Test Runner
;; ============================================================================

(defn run-all-tests
  "Run all tests and return results"
  []
  (println "\n========================================")
  (println "Running CRUD Tests")
  (println "========================================\n")

  (let [results (clojure.test/run-tests 'is.simm.model.crud-test)]
    (println "\n========================================")
    (println "Test Summary:")
    (println (format "  Tests run: %d" (:test results)))
    (println (format "  Passed: %d" (:pass results)))
    (println (format "  Failed: %d" (:fail results)))
    (println (format "  Errors: %d" (:error results)))
    (println "========================================\n")
    results))

(defn run-test
  "Run a single test by name"
  [test-name]
  (clojure.test/test-var (resolve test-name)))
