(ns is.simm.uis.reactive-datahike-test
  "Prototype: Datahike tx-data → Spindel Interval pipeline.

   This tests the integration pattern for reactive Datahike queries:
   1. Query runs on db-after
   2. tx-data provides the delta (datoms added/retracted)
   3. Result diff produces {:added :removed :updated}
   4. Translate to spindel interval format for ifor-each

   Goal: Validate the data flow before client-side integration."
  (:require [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.incremental.deltaable :as delta]
            [is.simm.uis.web.desktop.datahike-query :as dq]
            [clojure.set :as set]))

;; =============================================================================
;; Test Schema - Simple block structure
;; =============================================================================

(def block-schema
  [{:db/ident :block/uuid
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :block/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :block/parent
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :block/order
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

;; =============================================================================
;; Query Diff Utilities
;; =============================================================================

(defn diff-by-key
  "Diff two collections by key function.
   Returns {:added [...] :removed [...] :updated [{:key k :old o :new n} ...]}"
  [old-coll new-coll key-fn]
  (let [old-by-key (into {} (map (juxt key-fn identity)) old-coll)
        new-by-key (into {} (map (juxt key-fn identity)) new-coll)
        old-keys (set (keys old-by-key))
        new-keys (set (keys new-by-key))
        added-keys (set/difference new-keys old-keys)
        removed-keys (set/difference old-keys new-keys)
        common-keys (set/intersection old-keys new-keys)
        updated (for [k common-keys
                      :let [old-item (old-by-key k)
                            new-item (new-by-key k)]
                      :when (not= old-item new-item)]
                  {:key k :old old-item :new new-item})]
    {:added (mapv new-by-key added-keys)
     :removed (mapv old-by-key removed-keys)
     :updated (vec updated)}))

(defn query-diff->spindel-deltas
  "Convert {:added :removed :updated} to spindel delta format.

   Spindel ifor-each expects deltas in this format:
   - {:delta :add :value item}              ; appends if no :path
   - {:delta :add :path [idx] :value item}  ; inserts at position
   - {:delta :remove :old-value item}       ; key extracted via key-fn
   - {:delta :update :value new-item :old-value old-item}

   Note: ifor-each applies key-fn to :value/:old-value to determine keys.
   No need to pre-compute :key field."
  [diff _key-fn]
  (concat
   ;; Additions - without :path, appends at end
   ;; TODO: For ordered insertion, compute position and add :path [idx]
   (for [item (:added diff)]
     {:delta :add
      :value item})
   ;; Removals - ifor-each uses key-fn on :old-value
   (for [item (:removed diff)]
     {:delta :remove
      :old-value item})
   ;; Updates - ifor-each uses key-fn on :value to find in cache
   (for [{:keys [old new]} (:updated diff)]
     {:delta :update
      :old-value old
      :value new})))

;; =============================================================================
;; Reactive Query State
;; =============================================================================

(defn make-reactive-query
  "Create a reactive query that maintains state and produces intervals.

   Returns: {:query-fn fn, :prev-result atom, :run! fn}

   run! takes a db and returns an Interval with:
   - :old = previous result
   - :new = current result
   - :deltas = translated deltas for incremental processing"
  [query-fn key-fn]
  (let [prev-result (atom nil)]
    {:query-fn query-fn
     :key-fn key-fn
     :prev-result prev-result
     :run! (fn [db]
             (let [old-result @prev-result
                   new-result (query-fn db)
                   diff (if old-result
                          (diff-by-key old-result new-result key-fn)
                          {:added (vec new-result) :removed [] :updated []})
                   deltas (query-diff->spindel-deltas diff key-fn)]
               (reset! prev-result new-result)
               (iv/->Interval old-result new-result (vec deltas))))}))

;; =============================================================================
;; Block Queries
;; =============================================================================

(defn blocks-query
  "Query all blocks with their parent UUID."
  [db]
  (d/q '[:find [(pull ?b [:block/uuid :block/content :block/order
                          {:block/parent [:block/uuid]}]) ...]
         :where [?b :block/uuid]]
       db))

(defn page-blocks-query
  "Query blocks for a specific page (by parent UUID)."
  [db page-uuid]
  (d/q '[:find [(pull ?b [:block/uuid :block/content :block/order
                          {:block/parent [:block/uuid]}]) ...]
         :in $ ?page-uuid
         :where
         [?page :block/uuid ?page-uuid]
         [?b :block/parent ?page]]
       db page-uuid))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest test-diff-by-key
  (testing "basic diff operations"
    (let [old [{:id 1 :name "Alice"} {:id 2 :name "Bob"}]
          new [{:id 1 :name "Alicia"} {:id 3 :name "Charlie"}]
          diff (diff-by-key old new :id)]
      (is (= 1 (count (:added diff))))
      (is (= 3 (:id (first (:added diff)))))
      (is (= 1 (count (:removed diff))))
      (is (= 2 (:id (first (:removed diff)))))
      (is (= 1 (count (:updated diff))))
      (is (= {:key 1 :old {:id 1 :name "Alice"} :new {:id 1 :name "Alicia"}}
             (first (:updated diff)))))))

(deftest test-query-diff-to-spindel-deltas
  (testing "delta translation matches spindel ifor-each format"
    (let [diff {:added [{:id 3 :name "Charlie"}]
                :removed [{:id 2 :name "Bob"}]
                :updated [{:key 1 :old {:id 1 :name "Alice"} :new {:id 1 :name "Alicia"}}]}
          deltas (vec (query-diff->spindel-deltas diff :id))
          add-delta (first (filter #(= :add (:delta %)) deltas))
          remove-delta (first (filter #(= :remove (:delta %)) deltas))
          update-delta (first (filter #(= :update (:delta %)) deltas))]
      (is (= 3 (count deltas)))

      ;; :add delta format - no :key, just :value (and optionally :path)
      (is (= :add (:delta add-delta)))
      (is (= {:id 3 :name "Charlie"} (:value add-delta)))
      (is (nil? (:key add-delta)) "ifor-each extracts key via key-fn, not from delta")

      ;; :remove delta format - :old-value only
      (is (= :remove (:delta remove-delta)))
      (is (= {:id 2 :name "Bob"} (:old-value remove-delta)))

      ;; :update delta format - :value and :old-value
      (is (= :update (:delta update-delta)))
      (is (= {:id 1 :name "Alicia"} (:value update-delta)))
      (is (= {:id 1 :name "Alice"} (:old-value update-delta))))))

(deftest test-datahike-reactive-query
  (testing "reactive query produces intervals"
    (let [cfg {:store {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history? false}
          _ (d/delete-database cfg)
          _ (d/create-database cfg)
          conn (d/connect cfg)]

      ;; Setup schema
      (d/transact conn block-schema)

      ;; Create reactive query
      (let [reactive-q (make-reactive-query blocks-query :block/uuid)
            run! (:run! reactive-q)]

        ;; Initial state - empty
        (let [iv (run! @conn)]
          (is (nil? (:old iv)))
          (is (empty? (:new iv)))
          (is (empty? (:deltas iv))))

        ;; Add first block
        (let [uuid1 (java.util.UUID/randomUUID)]
          (d/transact conn [{:block/uuid uuid1
                             :block/content "First block"
                             :block/order 0}])

          (let [iv (run! @conn)]
            (is (= 0 (count (:old iv))))
            (is (= 1 (count (:new iv))))
            (is (= 1 (count (:deltas iv))))
            (is (= :add (:delta (first (:deltas iv)))))))

        ;; Add second block
        (let [uuid2 (java.util.UUID/randomUUID)]
          (d/transact conn [{:block/uuid uuid2
                             :block/content "Second block"
                             :block/order 1}])

          (let [iv (run! @conn)]
            (is (= 1 (count (:old iv))))
            (is (= 2 (count (:new iv))))
            (is (= 1 (count (:deltas iv))))
            (is (= :add (:delta (first (:deltas iv)))))))

        ;; Update a block
        (let [blocks (:new (run! @conn))
              first-uuid (:block/uuid (first blocks))]
          (d/transact conn [{:block/uuid first-uuid
                             :block/content "Updated first block"}])

          (let [iv (run! @conn)]
            (is (= 2 (count (:old iv))))
            (is (= 2 (count (:new iv))))
            (is (= 1 (count (:deltas iv))))
            (is (= :update (:delta (first (:deltas iv))))))))

      (d/release conn))))

(deftest test-tree-structure-query
  (testing "hierarchical block queries"
    (let [cfg {:store {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history? false}
          _ (d/delete-database cfg)
          _ (d/create-database cfg)
          conn (d/connect cfg)
          page-uuid (java.util.UUID/randomUUID)]

      ;; Setup schema
      (d/transact conn block-schema)

      ;; Create page (root block)
      (d/transact conn [{:block/uuid page-uuid
                         :block/content "Page Title"
                         :block/order 0}])

      ;; Create child blocks
      (let [block1-uuid (java.util.UUID/randomUUID)
            block2-uuid (java.util.UUID/randomUUID)
            block1a-uuid (java.util.UUID/randomUUID)]

        (d/transact conn [{:block/uuid block1-uuid
                           :block/content "Block 1"
                           :block/parent [:block/uuid page-uuid]
                           :block/order 0}
                          {:block/uuid block2-uuid
                           :block/content "Block 2"
                           :block/parent [:block/uuid page-uuid]
                           :block/order 1}])

        ;; Nested child
        (d/transact conn [{:block/uuid block1a-uuid
                           :block/content "Block 1a"
                           :block/parent [:block/uuid block1-uuid]
                           :block/order 0}])

        ;; Query children of page
        (let [children (page-blocks-query @conn page-uuid)]
          (is (= 2 (count children)))
          (is (every? #(= page-uuid (get-in % [:block/parent :block/uuid])) children)))

        ;; Query children of block1
        (let [grandchildren (page-blocks-query @conn block1-uuid)]
          (is (= 1 (count grandchildren)))
          (is (= "Block 1a" (:block/content (first grandchildren))))))

      (d/release conn))))

(deftest test-flatten-tree-to-document-order
  (testing "DFS traversal produces document order"
    (let [cfg {:store {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history? false}
          _ (d/delete-database cfg)
          _ (d/create-database cfg)
          conn (d/connect cfg)
          page-uuid (java.util.UUID/randomUUID)
          block1-uuid (java.util.UUID/randomUUID)
          block2-uuid (java.util.UUID/randomUUID)
          block1a-uuid (java.util.UUID/randomUUID)
          block1b-uuid (java.util.UUID/randomUUID)]

      ;; Setup schema
      (d/transact conn block-schema)

      ;; Create tree: Page > [Block1 > [Block1a, Block1b], Block2]
      (d/transact conn [{:block/uuid page-uuid :block/content "Page" :block/order 0}])
      (d/transact conn [{:block/uuid block1-uuid
                         :block/content "Block 1"
                         :block/parent [:block/uuid page-uuid]
                         :block/order 0}
                        {:block/uuid block2-uuid
                         :block/content "Block 2"
                         :block/parent [:block/uuid page-uuid]
                         :block/order 1}])
      (d/transact conn [{:block/uuid block1a-uuid
                         :block/content "Block 1a"
                         :block/parent [:block/uuid block1-uuid]
                         :block/order 0}
                        {:block/uuid block1b-uuid
                         :block/content "Block 1b"
                         :block/parent [:block/uuid block1-uuid]
                         :block/order 1}])

      ;; Build index for DFS
      (let [all-blocks (blocks-query @conn)
            by-parent (group-by #(get-in % [:block/parent :block/uuid]) all-blocks)]

        ;; DFS flatten function
        (letfn [(flatten-tree [parent-uuid]
                  (let [children (->> (get by-parent parent-uuid [])
                                      (sort-by :block/order))]
                    (mapcat (fn [block]
                              (cons block (flatten-tree (:block/uuid block))))
                            children)))]

          ;; Get document order starting from page
          (let [doc-order (flatten-tree page-uuid)
                contents (mapv :block/content doc-order)]
            (is (= ["Block 1" "Block 1a" "Block 1b" "Block 2"] contents)))))

      (d/release conn))))

(deftest test-tx-data-analysis
  (testing "tx-data provides datom-level deltas"
    (let [cfg {:store {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history? false}
          _ (d/delete-database cfg)
          _ (d/create-database cfg)
          conn (d/connect cfg)]

      ;; Setup schema
      (d/transact conn block-schema)

      ;; Add a block and capture tx-report
      (let [uuid1 (java.util.UUID/randomUUID)
            tx-report (d/transact conn [{:block/uuid uuid1
                                         :block/content "Test block"
                                         :block/order 0}])]

        ;; tx-data contains datoms
        (is (seq (:tx-data tx-report)))

        ;; Check datom structure
        (let [datoms (:tx-data tx-report)]
          ;; Each datom has [e a v tx added?]
          (is (every? #(= 5 (count %)) datoms))

          ;; All additions for new block
          (is (every? #(nth % 4) datoms)) ; added? = true

          ;; Contains our attributes
          (let [attrs (set (map #(nth % 1) datoms))]
            (is (contains? attrs :block/uuid))
            (is (contains? attrs :block/content))
            (is (contains? attrs :block/order)))))

      ;; Update and check tx-data
      (let [blocks (blocks-query @conn)
            uuid1 (:block/uuid (first blocks))
            tx-report (d/transact conn [{:block/uuid uuid1
                                         :block/content "Updated content"}])]

        ;; For update without history, we just see the new datom
        (let [datoms (:tx-data tx-report)
              content-datoms (filter #(= :block/content (nth % 1)) datoms)]
          (is (seq content-datoms))))

      (d/release conn))))

;; =============================================================================
;; Tests for datahike-query module
;; =============================================================================

(deftest test-query-with-deltas-module
  (testing "query-with-deltas produces correct intervals"
    ;; Clear cache from previous test runs
    (dq/clear-cache!)

    (let [cfg {:store {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history? false}
          _ (d/delete-database cfg)
          _ (d/create-database cfg)
          conn (d/connect cfg)]

      ;; Setup schema
      (d/transact conn block-schema)

      ;; Create a simple query function
      (let [query-fn (fn [db]
                       (d/q '[:find [(pull ?b [:block/uuid :block/content :block/order]) ...]
                              :where [?b :block/uuid]]
                            db))
            key-fn :block/uuid]

        ;; First query - should be all adds
        (let [uuid1 (java.util.UUID/randomUUID)]
          (d/transact conn [{:block/uuid uuid1
                             :block/content "First block"
                             :block/order 0}])

          ;; Wrap db in interval (simulating what track would do)
          (let [db-iv (iv/->Interval nil @conn nil)
                result-iv (dq/query-with-deltas db-iv query-fn key-fn :test-query)]

            (is (nil? (iv/get-old result-iv)))
            (is (= 1 (count (iv/get-new result-iv))))
            (is (= 1 (count (iv/get-deltas result-iv))))
            (is (= :add (:delta (first (iv/get-deltas result-iv)))))))

        ;; Second query after adding more - should have add delta
        (let [uuid2 (java.util.UUID/randomUUID)]
          (d/transact conn [{:block/uuid uuid2
                             :block/content "Second block"
                             :block/order 1}])

          (let [db-iv (iv/->Interval nil @conn nil)
                result-iv (dq/query-with-deltas db-iv query-fn key-fn :test-query)]

            (is (= 1 (count (iv/get-old result-iv))))  ; Previous result
            (is (= 2 (count (iv/get-new result-iv))))  ; New result
            (is (= 1 (count (iv/get-deltas result-iv))))
            (is (= :add (:delta (first (iv/get-deltas result-iv)))))))

        ;; Update a block - should have update delta
        (let [blocks (query-fn @conn)
              first-uuid (:block/uuid (first blocks))]
          (d/transact conn [{:block/uuid first-uuid
                             :block/content "Updated content"}])

          (let [db-iv (iv/->Interval nil @conn nil)
                result-iv (dq/query-with-deltas db-iv query-fn key-fn :test-query)]

            (is (= 2 (count (iv/get-old result-iv))))
            (is (= 2 (count (iv/get-new result-iv))))
            (is (= 1 (count (iv/get-deltas result-iv))))
            (is (= :update (:delta (first (iv/get-deltas result-iv))))))))

      (d/release conn))))

(deftest test-flatten-to-document-order
  (testing "tree flattening produces correct document order"
    (let [page-uuid (java.util.UUID/randomUUID)
          block1-uuid (java.util.UUID/randomUUID)
          block2-uuid (java.util.UUID/randomUUID)
          block1a-uuid (java.util.UUID/randomUUID)
          block1b-uuid (java.util.UUID/randomUUID)

          ;; Blocks with parent references
          blocks [{:block/uuid block1-uuid
                   :block/content "Block 1"
                   :block/parent {:block/uuid page-uuid}
                   :block/order 0}
                  {:block/uuid block2-uuid
                   :block/content "Block 2"
                   :block/parent {:block/uuid page-uuid}
                   :block/order 1}
                  {:block/uuid block1a-uuid
                   :block/content "Block 1a"
                   :block/parent {:block/uuid block1-uuid}
                   :block/order 0}
                  {:block/uuid block1b-uuid
                   :block/content "Block 1b"
                   :block/parent {:block/uuid block1-uuid}
                   :block/order 1}]

          doc-order (dq/flatten-to-document-order blocks page-uuid)
          contents (mapv :block/content doc-order)]

      (is (= ["Block 1" "Block 1a" "Block 1b" "Block 2"] contents)))))

(deftest test-blocks-in-document-order-interval
  (testing "blocks-in-document-order produces interval with correct deltas"
    (let [page-uuid (java.util.UUID/randomUUID)
          block1-uuid (java.util.UUID/randomUUID)

          old-blocks [{:block/uuid block1-uuid
                       :block/content "Block 1"
                       :block/parent {:block/uuid page-uuid}
                       :block/order 0}]

          block2-uuid (java.util.UUID/randomUUID)
          new-blocks [{:block/uuid block1-uuid
                       :block/content "Block 1"
                       :block/parent {:block/uuid page-uuid}
                       :block/order 0}
                      {:block/uuid block2-uuid
                       :block/content "Block 2"
                       :block/parent {:block/uuid page-uuid}
                       :block/order 1}]

          ;; Create input interval (simulating query result)
          input-iv (iv/->Interval old-blocks new-blocks nil)

          ;; Get document-ordered interval
          result-iv (dq/blocks-in-document-order input-iv page-uuid :block/uuid)]

      (is (= 1 (count (iv/get-old result-iv))))
      (is (= 2 (count (iv/get-new result-iv))))
      (is (= 1 (count (iv/get-deltas result-iv))))
      (is (= :add (:delta (first (iv/get-deltas result-iv))))))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'is.simm.uis.reactive-datahike-test)

  ;; Interactive testing
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}]
    (d/delete-database cfg)
    (d/create-database cfg)
    (def conn (d/connect cfg))
    (d/transact conn block-schema)

    (def rq (make-reactive-query blocks-query :block/uuid))

    ;; Add blocks
    (d/transact conn [{:block/uuid (java.util.UUID/randomUUID)
                       :block/content "First"
                       :block/order 0}])

    (def iv ((:run! rq) @conn))
    (println "New:" (:new iv))
    (println "Deltas:" (:deltas iv))))
