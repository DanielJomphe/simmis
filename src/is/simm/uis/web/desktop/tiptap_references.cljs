(ns is.simm.uis.web.desktop.tiptap-references
  "TipTap extensions for page, block, and user references.

   Provides Mark extensions:
   - PageReference: Handles [[Page Name]] syntax
   - BlockReference: Handles ((block-uuid)) syntax
   - UserReference: Handles @Username syntax

   Provides Suggestion extensions:
   - PageSuggestion: Autocomplete for page references
   - UserSuggestion: Autocomplete for user mentions"
  (:require ["@tiptap/core" :refer [Mark Extension markInputRule markPasteRule]]
            ["@tiptap/suggestion" :default Suggestion]
            ["@tiptap/pm/state" :refer [PluginKey]]
            ["tippy.js" :default tippy]))

;; ============================================================================
;; Page Reference Extension
;; ============================================================================

(def page-reference-extension
  "TipTap Mark extension for [[Page Name]] references"
  (.create Mark
           #js {:name "pageReference"

                :inclusive false
                :excludes "_"

                :addOptions (fn []
                              (this-as this
                                       #js {:HTMLAttributes #js {:class "page-reference"}
                                            :onNavigate nil}))

                :addAttributes (fn []
                                 (this-as this
                                          #js {:pageName
                                               #js {:default nil
                                                    :parseHTML (fn [element]
                                                                 (.getAttribute element "data-page-name"))
                                                    :renderHTML (fn [attributes]
                                                                  (let [page-name (.-pageName ^js attributes)]
                                                                    (if page-name
                                                                      #js {:data-page-name page-name}
                                                                      #js {})))}
                                               :displayText
                                               #js {:default nil
                                                    :parseHTML (fn [element]
                                                                 (.getAttribute element "data-display-text"))
                                                    :renderHTML (fn [attributes]
                                                                  (let [display-text (.-displayText ^js attributes)]
                                                                    (if display-text
                                                                      #js {:data-display-text display-text}
                                                                      #js {})))}
                                               ;; Cross-database reference (dh:// URI). Same-KB links
                                               ;; leave this nil and carry the title in :pageName; a
                                               ;; cross-db link carries its exact pointer here so the
                                               ;; Mark round-trips it (renderHTML → data-ref) instead
                                               ;; of ProseMirror dropping the span on load. The raw
                                               ;; pointer surfaces on hover via `title`.
                                               :reference
                                               #js {:default nil
                                                    :parseHTML (fn [element]
                                                                 (.getAttribute element "data-ref"))
                                                    :renderHTML (fn [attributes]
                                                                  (let [reference (.-reference ^js attributes)]
                                                                    (if reference
                                                                      #js {:data-ref reference :title reference}
                                                                      #js {})))}}))

                :parseHTML (fn []
                             (this-as this
                                      #js [#js {:tag "span[data-page-name]"}
                                           #js {:tag "span[data-ref]"}]))

                :renderHTML (fn [props]
                              (this-as this
                                       (let [html-attrs (.-HTMLAttributes props)
                                             options (.-options this)
                                             merged-attrs (.assign js/Object
                                                                   #js {}
                                                                   (.-HTMLAttributes options)
                                                                   html-attrs
                                                                   ;; Non-editable so clicks propagate to app handler for navigation
                                                                   #js {:contenteditable "false"})]
                                         ;; Use 0 to preserve the actual text content (which includes [[]])
                                         #js ["span" merged-attrs 0])))

                :addCommands (fn []
                               (this-as this
                                        #js {:setPageReference
                                             (fn [attributes]
                                               (fn [props]
                                                 (let [commands (.-commands props)]
                                                   (.setMark commands (.-name this) attributes))))

                                             :unsetPageReference
                                             (fn []
                                               (fn [props]
                                                 (let [commands (.-commands props)]
                                                   (.unsetMark commands (.-name this)))))}))

                :addInputRules (fn []
                                 (this-as this
                                          #js [(markInputRule #js {:find #"(\[\[[^\]]+\]\])\s$"
                                                                   :type (.-type this)
                                                                   :getAttributes (fn [match]
                                                                                   (let [full-text (aget match 1)  ;; Group 1 is what gets marked
                                                                                         page-name (.substring full-text 2 (- (.-length full-text) 2))]
                                                                                     #js {:pageName page-name
                                                                                          :displayText page-name}))})]))

                :addPasteRules (fn []
                                 (this-as this
                                          #js [(markPasteRule #js {:find (js/RegExp "\\[\\[[^\\]]+\\]\\]" "g")
                                                                   :type (.-type this)
                                                                   :getAttributes (fn [match]
                                                                                   (let [full-text (aget match 0)
                                                                                         page-name (.substring full-text 2 (- (.-length full-text) 2))]
                                                                                     #js {:pageName page-name
                                                                                          :displayText page-name}))})]))}))

;; ============================================================================
;; Block Reference Extension
;; ============================================================================

(def block-reference-extension
  "TipTap Mark extension for ((block-uuid)) references"
  (.create Mark
           #js {:name "blockReference"

                :inclusive false
                :excludes "_"

                :addOptions (fn []
                              (this-as this
                                       #js {:HTMLAttributes #js {:class "block-reference"}
                                            :onNavigate nil}))

                :addAttributes (fn []
                                 (this-as this
                                          #js {:blockUuid
                                               #js {:default nil
                                                    :parseHTML (fn [element]
                                                                 (.getAttribute element "data-block-uuid"))
                                                    :renderHTML (fn [attributes]
                                                                  (let [block-uuid (.-blockUuid ^js attributes)]
                                                                    (if block-uuid
                                                                      #js {:data-block-uuid block-uuid}
                                                                      #js {})))}}))

                :parseHTML (fn []
                             (this-as this
                                      #js [#js {:tag "span[data-block-uuid]"}]))

                :renderHTML (fn [props]
                              (this-as this
                                       (let [html-attrs (.-HTMLAttributes props)
                                             options (.-options this)
                                             merged-attrs (.assign js/Object
                                                                   #js {}
                                                                   (.-HTMLAttributes options)
                                                                   html-attrs)]
                                         ;; Use 0 to preserve the actual text content (which includes (()))
                                         #js ["span" merged-attrs 0])))

                :addCommands (fn []
                               (this-as this
                                        #js {:setBlockReference
                                             (fn [attributes]
                                               (fn [props]
                                                 (let [commands (.-commands props)]
                                                   (.setMark commands (.-name this) attributes))))

                                             :unsetBlockReference
                                             (fn []
                                               (fn [props]
                                                 (let [commands (.-commands props)]
                                                   (.unsetMark commands (.-name this)))))}))

                :addInputRules (fn []
                                 (this-as this
                                          #js [(markInputRule #js {:find #"(\(\([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\)\))\s$"
                                                                   :type (.-type this)
                                                                   :getAttributes (fn [match]
                                                                                   (let [full-text (aget match 1)  ;; Group 1 is what gets marked
                                                                                         block-uuid (.substring full-text 2 (- (.-length full-text) 2))]
                                                                                     #js {:blockUuid block-uuid}))})]))

                :addPasteRules (fn []
                                 (this-as this
                                          #js [(markPasteRule #js {:find (js/RegExp "\\(\\([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\)\\)" "g")
                                                                   :type (.-type this)
                                                                   :getAttributes (fn [match]
                                                                                   (let [full-text (aget match 0)
                                                                                         block-uuid (.substring full-text 2 (- (.-length full-text) 2))]
                                                                                     #js {:blockUuid block-uuid}))})]))}))

;; ============================================================================
;; Page Suggestion Extension (Autocomplete)
;; ============================================================================

(defn create-page-suggestion-extension
  "Creates a TipTap suggestion extension for page autocomplete.

   Options:
   - search-fn: async function (query) => [pages]
   - on-select: function (page-title) => void"
  [options]
  (let [search-fn (.-searchFn ^js options)
        on-select (.-onSelect ^js options)]
    (.create Extension
             #js {:name "pageSuggestion"

                  :addProseMirrorPlugins
                  (fn []
                    (this-as this
                             (let [suggestion-options
                                   #js {:editor (.-editor this)
                                        :pluginKey (PluginKey. "pageSuggestion")
                                        :char "[["

                                        :allow (fn [props] true)

                                        :items (fn [props]
                                                (let [query (.-query props)]
                                                  (search-fn query)))

                                        :render (fn []
                                                  (let [popup (atom nil)
                                                        component (atom nil)
                                                        selected-index (atom 0)
                                                        items-cache (atom #js [])
                                                        command-cache (atom nil)
                                                        render-items! (fn []
                                                                        (when-let [comp @component]
                                                                          (let [items @items-cache
                                                                                command @command-cache
                                                                                current-index @selected-index]
                                                                            (set! (.-innerHTML comp) "")
                                                                            (if (> (.-length items) 0)
                                                                              (doseq [i (range (.-length items))]
                                                                                (let [item (aget items i)
                                                                                      div (js/document.createElement "div")
                                                                                      page-title (.-title item)
                                                                                      is-selected? (= i current-index)]
                                                                                  (set! (.-className div) (if is-selected?
                                                                                                           "suggestion-item suggestion-item-selected"
                                                                                                           "suggestion-item"))
                                                                                  (set! (.-textContent div) page-title)
                                                                                  (.addEventListener div "click"
                                                                                                     (fn []
                                                                                                       (command #js {:title page-title})))
                                                                                  (.appendChild comp div)))
                                                                              (let [div (js/document.createElement "div")]
                                                                                (set! (.-className div) "suggestion-item suggestion-empty")
                                                                                (set! (.-textContent div) "No pages found")
                                                                                (.appendChild comp div))))))]
                                                    #js {:onStart
                                                         (fn [props]
                                                           (let [container (js/document.createElement "div")]
                                                             (set! (.-className container) "suggestion-dropdown")
                                                             (reset! component container)
                                                             (reset! selected-index 0)
                                                             (reset! popup
                                                                     (tippy "body"
                                                                            #js {:getReferenceClientRect (.-clientRect props)
                                                                                 :appendTo (fn [] (.-body js/document))
                                                                                 :content container
                                                                                 :showOnCreate true
                                                                                 :interactive true
                                                                                 :trigger "manual"
                                                                                 :placement "bottom-start"}))))

                                                         :onUpdate
                                                         (fn [props]
                                                           (let [items (.-items props)
                                                                 command (.-command props)
                                                                 current-index @selected-index]
                                                             ;; Cache items and command for keyboard navigation
                                                             (reset! items-cache items)
                                                             (reset! command-cache command)
                                                             ;; Clamp selected-index to valid range
                                                             (when (>= current-index (.-length items))
                                                               (reset! selected-index (max 0 (dec (.-length items)))))
                                                             ;; Render the items
                                                             (render-items!)
                                                             ;; Update popup position
                                                             (when-let [p @popup]
                                                               (when (aget p 0)
                                                                 (.setProps (aget p 0)
                                                                            #js {:getReferenceClientRect (.-clientRect props)})))))

                                                         :onKeyDown
                                                         (fn [props]
                                                           (let [event (.-event props)
                                                                 key (.-key event)
                                                                 items @items-cache
                                                                 items-count (.-length items)
                                                                 command @command-cache]
                                                             (cond
                                                               ;; Escape - close dropdown
                                                               (= key "Escape")
                                                               (do (.preventDefault event)
                                                                   true)

                                                               ;; Arrow Up - navigate up
                                                               (and (= key "ArrowUp") (> items-count 0))
                                                               (do (.preventDefault event)
                                                                   (reset! selected-index
                                                                          (mod (+ @selected-index items-count -1) items-count))
                                                                   ;; Trigger re-render
                                                                   (render-items!)
                                                                   true)

                                                               ;; Arrow Down - navigate down
                                                               (and (= key "ArrowDown") (> items-count 0))
                                                               (do (.preventDefault event)
                                                                   (reset! selected-index
                                                                          (mod (inc @selected-index) items-count))
                                                                   ;; Trigger re-render
                                                                   (render-items!)
                                                                   true)

                                                               ;; Enter - select current item
                                                               (and (= key "Enter") (> items-count 0))
                                                               (do (.preventDefault event)
                                                                   (let [selected-item (aget items @selected-index)]
                                                                     (when selected-item
                                                                       (command #js {:title (.-title selected-item)})))
                                                                   true)

                                                               :else false)))

                                                         :onExit
                                                         (fn []
                                                           (when-let [p @popup]
                                                             (when (aget p 0)
                                                               (.destroy (aget p 0))))
                                                           ;; Belt-and-suspenders: explicitly remove the
                                                           ;; container element. tippy.destroy disposes
                                                           ;; of its own wrapper, but in some lifecycle
                                                           ;; paths (e.g. editor destroyed before the
                                                           ;; suggestion exits cleanly) the inner
                                                           ;; container leaks into <body>. A leaked
                                                           ;; empty <div class="suggestion-dropdown"/>
                                                           ;; breaks every Enter/ArrowUp/ArrowDown in
                                                           ;; block_editor.cljs, which gate on
                                                           ;; (querySelector ".suggestion-dropdown").
                                                           (when-let [^js c @component]
                                                             (when (.-parentNode c)
                                                               (.remove c)))
                                                           (reset! popup nil)
                                                           (reset! component nil)
                                                           (reset! selected-index 0)
                                                           (reset! items-cache #js [])
                                                           (reset! command-cache nil))}))

                                        :command
                                        (fn [props]
                                          (let [editor (.-editor props)
                                                range (.-range props)
                                                attributes (.-props props)
                                                page-title (.-title attributes)
                                                from (.-from range)
                                                to (.-to range)
                                                full-text (str "[[" page-title "]]")]
                                            ;; Delete the trigger range, insert the marked [[Page]]
                                            ;; text, then APPEND an unmarked space. The trailing
                                            ;; unmarked text node is essential: the
                                            ;; page-reference Mark renders its span as
                                            ;; contenteditable="false" (so chat clicks
                                            ;; propagate to the app handler for navigation).
                                            ;; Without the trailing space the cursor stays
                                            ;; against the contenteditable=false boundary
                                            ;; and the browser silently refuses subsequent
                                            ;; keystrokes. Inserting an unmarked " " pushes
                                            ;; the cursor cleanly outside the mark span and
                                            ;; typing resumes.
                                            (-> (.chain editor)
                                                (.focus)
                                                (.deleteRange #js {:from from :to to})
                                                (.insertContent
                                                  #js [#js {:type "text"
                                                            :text full-text
                                                            :marks #js [#js {:type "pageReference"
                                                                             :attrs #js {:pageName page-title
                                                                                         :displayText page-title}}]}
                                                       #js {:type "text" :text " "}])
                                                (.run))
                                            (when on-select
                                              (on-select page-title))))}]
                               #js [(Suggestion suggestion-options)])))})))

;; ============================================================================
;; User Reference Extension (Mark)
;; ============================================================================

(def user-reference-extension
  "TipTap Mark extension for @Username references"
  (.create Mark
           #js {:name "userReference"

                :inclusive false
                :excludes "_"

                :addOptions (fn []
                              (this-as this
                                       #js {:HTMLAttributes #js {:class "user-reference"}
                                            :onNavigate nil}))

                :addAttributes (fn []
                                 (this-as this
                                          #js {:userName
                                               #js {:default nil
                                                    :parseHTML (fn [element]
                                                                 (.getAttribute element "data-user-name"))
                                                    :renderHTML (fn [attributes]
                                                                  (let [user-name (.-userName ^js attributes)]
                                                                    (if user-name
                                                                      #js {:data-user-name user-name}
                                                                      #js {})))}
                                               :userId
                                               #js {:default nil
                                                    :parseHTML (fn [element]
                                                                 (.getAttribute element "data-user-id"))
                                                    :renderHTML (fn [attributes]
                                                                  (let [user-id (.-userId ^js attributes)]
                                                                    (if user-id
                                                                      #js {:data-user-id user-id}
                                                                      #js {})))}}))

                :parseHTML (fn []
                             (this-as this
                                      #js [#js {:tag "span[data-user-name]"}]))

                :renderHTML (fn [props]
                              (this-as this
                                       (let [html-attrs (.-HTMLAttributes props)
                                             options (.-options this)
                                             merged-attrs (.assign js/Object
                                                                   #js {}
                                                                   (.-HTMLAttributes options)
                                                                   html-attrs)]
                                         #js ["span" merged-attrs 0])))

                :addCommands (fn []
                               (this-as this
                                        #js {:setUserReference
                                             (fn [attributes]
                                               (fn [props]
                                                 (let [commands (.-commands props)]
                                                   (.setMark commands (.-name this) attributes))))

                                             :unsetUserReference
                                             (fn []
                                               (fn [props]
                                                 (let [commands (.-commands props)]
                                                   (.unsetMark commands (.-name this)))))}))

                :addInputRules (fn []
                                 (this-as this
                                          #js [(markInputRule #js {:find #"(@[A-Za-z][A-Za-z0-9 ]*)\s$"
                                                                   :type (.-type this)
                                                                   :getAttributes (fn [match]
                                                                                   (let [full-text (aget match 1)
                                                                                         user-name (.substring full-text 1)]
                                                                                     #js {:userName user-name}))})]))

                :addPasteRules (fn []
                                 (this-as this
                                          #js [(markPasteRule #js {:find (js/RegExp "@[A-Za-z][A-Za-z0-9 ]*" "g")
                                                                   :type (.-type this)
                                                                   :getAttributes (fn [match]
                                                                                   (let [full-text (aget match 0)
                                                                                         user-name (.substring full-text 1)]
                                                                                     #js {:userName user-name}))})]))}))

;; ============================================================================
;; User Suggestion Extension (Autocomplete)
;; ============================================================================

(defn create-user-suggestion-extension
  "Creates a TipTap suggestion extension for user mention autocomplete.

   Options:
   - search-fn: async function (query) => [{:name string :id string} ...]
   - on-select: function (user) => void"
  [options]
  (let [search-fn (.-searchFn ^js options)
        on-select (.-onSelect ^js options)]
    (.create Extension
             #js {:name "userSuggestion"

                  :addProseMirrorPlugins
                  (fn []
                    (this-as this
                             (let [suggestion-options
                                   #js {:editor (.-editor this)
                                        :pluginKey (PluginKey. "userSuggestion")
                                        :char "@"

                                        :allow (fn [props] true)

                                        :items (fn [props]
                                                (let [query (.-query props)]
                                                  (search-fn query)))

                                        :render (fn []
                                                  (let [popup (atom nil)
                                                        component (atom nil)
                                                        selected-index (atom 0)
                                                        items-cache (atom #js [])
                                                        command-cache (atom nil)
                                                        render-items! (fn []
                                                                        (when-let [comp @component]
                                                                          (let [items @items-cache
                                                                                command @command-cache
                                                                                current-index @selected-index]
                                                                            (set! (.-innerHTML comp) "")
                                                                            (if (> (.-length items) 0)
                                                                              (doseq [i (range (.-length items))]
                                                                                (let [item (aget items i)
                                                                                      div (js/document.createElement "div")
                                                                                      ;; name is the handle (for insertion)
                                                                                      ;; displayName is the full name (for display)
                                                                                      user-handle (.-name item)
                                                                                      user-display (or (.-displayName item) user-handle)
                                                                                      user-id (.-id item)
                                                                                      is-selected? (= i current-index)]
                                                                                  (set! (.-className div) (if is-selected?
                                                                                                           "suggestion-item suggestion-item-selected"
                                                                                                           "suggestion-item"))
                                                                                  ;; Show display name in dropdown
                                                                                  (set! (.-textContent div) (str "@" user-display))
                                                                                  (.addEventListener div "click"
                                                                                                     (fn []
                                                                                                       ;; Pass handle as name for insertion
                                                                                                       (command #js {:name user-handle :id user-id})))
                                                                                  (.appendChild comp div)))
                                                                              (let [div (js/document.createElement "div")]
                                                                                (set! (.-className div) "suggestion-item suggestion-empty")
                                                                                (set! (.-textContent div) "No users found")
                                                                                (.appendChild comp div))))))]
                                                    #js {:onStart
                                                         (fn [props]
                                                           (let [container (js/document.createElement "div")]
                                                             (set! (.-className container) "suggestion-dropdown")
                                                             (reset! component container)
                                                             (reset! selected-index 0)
                                                             (reset! popup
                                                                     (tippy "body"
                                                                            #js {:getReferenceClientRect (.-clientRect props)
                                                                                 :appendTo (fn [] (.-body js/document))
                                                                                 :content container
                                                                                 :showOnCreate true
                                                                                 :interactive true
                                                                                 :trigger "manual"
                                                                                 :placement "bottom-start"}))))

                                                         :onUpdate
                                                         (fn [props]
                                                           (let [items (.-items props)
                                                                 command (.-command props)
                                                                 current-index @selected-index]
                                                             (reset! items-cache items)
                                                             (reset! command-cache command)
                                                             (when (>= current-index (.-length items))
                                                               (reset! selected-index (max 0 (dec (.-length items)))))
                                                             (render-items!)
                                                             (when-let [p @popup]
                                                               (when (aget p 0)
                                                                 (.setProps (aget p 0)
                                                                            #js {:getReferenceClientRect (.-clientRect props)})))))

                                                         :onKeyDown
                                                         (fn [props]
                                                           (let [event (.-event props)
                                                                 key (.-key event)
                                                                 items @items-cache
                                                                 items-count (.-length items)
                                                                 command @command-cache]
                                                             (cond
                                                               (= key "Escape")
                                                               (do (.preventDefault event)
                                                                   true)

                                                               (and (= key "ArrowUp") (> items-count 0))
                                                               (do (.preventDefault event)
                                                                   (reset! selected-index
                                                                          (mod (+ @selected-index items-count -1) items-count))
                                                                   (render-items!)
                                                                   true)

                                                               (and (= key "ArrowDown") (> items-count 0))
                                                               (do (.preventDefault event)
                                                                   (reset! selected-index
                                                                          (mod (inc @selected-index) items-count))
                                                                   (render-items!)
                                                                   true)

                                                               (and (= key "Enter") (> items-count 0))
                                                               (do (.preventDefault event)
                                                                   (let [selected-item (aget items @selected-index)]
                                                                     (when selected-item
                                                                       (command #js {:name (.-name selected-item)
                                                                                     :id (.-id selected-item)})))
                                                                   true)

                                                               :else false)))

                                                         :onExit
                                                         (fn []
                                                           (when-let [p @popup]
                                                             (when (aget p 0)
                                                               (.destroy (aget p 0))))
                                                           ;; Belt-and-suspenders: explicitly remove the
                                                           ;; container element. tippy.destroy disposes
                                                           ;; of its own wrapper, but in some lifecycle
                                                           ;; paths (e.g. editor destroyed before the
                                                           ;; suggestion exits cleanly) the inner
                                                           ;; container leaks into <body>. A leaked
                                                           ;; empty <div class="suggestion-dropdown"/>
                                                           ;; breaks every Enter/ArrowUp/ArrowDown in
                                                           ;; block_editor.cljs, which gate on
                                                           ;; (querySelector ".suggestion-dropdown").
                                                           (when-let [^js c @component]
                                                             (when (.-parentNode c)
                                                               (.remove c)))
                                                           (reset! popup nil)
                                                           (reset! component nil)
                                                           (reset! selected-index 0)
                                                           (reset! items-cache #js [])
                                                           (reset! command-cache nil))}))

                                        :command
                                        (fn [props]
                                          (let [editor (.-editor props)
                                                range (.-range props)
                                                attributes (.-props props)
                                                user-name (.-name attributes)
                                                user-id (.-id attributes)
                                                from (.-from range)
                                                to (.-to range)
                                                full-text (str "@" user-name)]
                                            ;; Append an unmarked trailing space — the
                                            ;; userReference Mark renders contenteditable=
                                            ;; "false" (so chat clicks navigate to the
                                            ;; user's page). Without the trailing space
                                            ;; the cursor lands inside the non-editable
                                            ;; span boundary and subsequent typing is
                                            ;; silently swallowed. See page-suggestion
                                            ;; :command above for the same fix.
                                            (-> (.chain editor)
                                                (.focus)
                                                (.deleteRange #js {:from from :to to})
                                                (.insertContent
                                                  #js [#js {:type "text"
                                                            :text full-text
                                                            :marks #js [#js {:type "userReference"
                                                                             :attrs #js {:userName user-name
                                                                                         :userId user-id}}]}
                                                       #js {:type "text" :text " "}])
                                                (.run))
                                            (when on-select
                                              (on-select #js {:name user-name :id user-id}))))}]
                               #js [(Suggestion suggestion-options)])))})))
