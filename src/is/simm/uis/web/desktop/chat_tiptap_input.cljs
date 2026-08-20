(ns is.simm.uis.web.desktop.chat-tiptap-input
  "TipTap-based chat input with [[page]] and @user mention autocomplete.

   Provides rich text input for chat messages with the same editing experience
   as wiki blocks - page references and user mentions are highlighted and
   trigger autocomplete dropdowns.

   Usage:
   (chat-tiptap-input {:on-send (fn [html-content] ...)
                       :search-pages-fn (fn [query] ...)
                       :search-users-fn (fn [query] ...)
                       :placeholder \"Type a message...\"})"
  (:require ["@tiptap/core" :refer [Editor]]
            ["@tiptap/starter-kit" :default StarterKit]
            ["@tiptap/extension-link" :default TiptapLink]
            ["@tiptap/extension-placeholder" :default Placeholder]
            [is.simm.uis.web.desktop.tiptap-references :as tiptap-refs]
            [is.simm.uis.web.desktop.people :as people]
            [is.simm.uis.web.desktop.datahike-query :as dq]
            [datahike.api :as d]
            [is.simm.uis.web.desktop.db-signal :as db-signal]))

;; ============================================================================
;; User Search Function
;; ============================================================================

(defn search-users-in-db
  "Search people (users, agents, contacts) for @ autocomplete.
   Returns a JS array of {name: handle, displayName: string, id: string}.
   The dropdown shows displayName, but inserts the handle (no spaces).

   Backed by `people/all` — the roster the server already sends as `:contacts`
   — which replaced a datahike projection of the same data. Needs no db, so it
   also works before any replica has connected."
  [query]
  (let [query-lower (when query (.toLowerCase query))]
    (try
      (->> (people/all)
           ;; Only people with a handle are mentionable (need one to insert).
           (filter (fn [{:keys [handle display-name]}]
                     (and (seq handle)
                          (or (empty? query)
                              (.includes (.toLowerCase (str display-name)) query-lower)
                              (.includes (.toLowerCase (str handle)) query-lower)))))
           (take 10)
           (map (fn [{:keys [entity/uuid handle display-name]}]
                  {:name handle
                   :displayName display-name
                   :id (str uuid)}))
           clj->js)
      (catch :default e
        (js/console.error "[chat-input] Failed to search users:" e)
        #js []))))

(defn search-pages-in-db
  "Search for pages by title in the database.
   Returns a JS array of {title: string} for the suggestion extension."
  [query]
  (let [db (db-signal/get-db)
        query-lower (when query (.toLowerCase query))]
    (if db
      (try
        (let [all-pages (d/q '[:find ?title
                                :where [?p :S.Page/title ?title]]
                             db)
              filtered (->> all-pages
                           (map first)
                           (filter (fn [title]
                                     (or (empty? query)
                                         (.includes (.toLowerCase title) query-lower))))
                           (take 10)
                           (map (fn [title] {:title title})))]
          (clj->js filtered))
        (catch :default e
          (js/console.error "[chat-input] Failed to search pages:" e)
          #js []))
      #js [])))

;; ============================================================================
;; TipTap Chat Editor
;; ============================================================================

(defn create-chat-editor
  "Create a TipTap editor configured for chat input.

   Options:
   - element: DOM element to mount the editor on
   - on-send: Callback when Enter is pressed (receives HTML content)
   - placeholder: Placeholder text
   - search-pages-fn: Optional custom page search function
   - search-users-fn: Optional custom user search function"
  [{:keys [element on-send placeholder search-pages-fn search-users-fn]}]
  (let [page-search-fn (or search-pages-fn search-pages-in-db)
        user-search-fn (or search-users-fn search-users-in-db)

        ;; Create page suggestion extension
        page-suggestion-ext (tiptap-refs/create-page-suggestion-extension
                              #js {:searchFn page-search-fn
                                   :onSelect (fn [page-title]
                                               (js/console.log "[chat-input] Page selected:" page-title))})

        ;; Create user suggestion extension
        user-suggestion-ext (tiptap-refs/create-user-suggestion-extension
                              #js {:searchFn user-search-fn
                                   :onSelect (fn [user]
                                               (js/console.log "[chat-input] User selected:" (.-name user)))})

        ;; Atom to hold editor reference for use in handleKeyDown
        editor-ref (atom nil)

        ;; Create the editor
        editor (Editor.
                 #js {:element element
                      :extensions #js [;; Minimal starter kit (no heading, code blocks, etc.)
                                       ;; :link false — StarterKit ships a link extension; we
                                       ;; register TiptapLink below with our own config. Both
                                       ;; makes TipTap warn "Duplicate extension names: ['link']".
                                       (.configure StarterKit
                                                   #js {:heading false
                                                        :codeBlock false
                                                        :blockquote false
                                                        :horizontalRule false
                                                        :hardBreak false
                                                        :link false})
                                       ;; Autolink typed/pasted URLs while composing
                                       ;; (openOnClick false — clicking in the input
                                       ;; is for editing, not navigation).
                                       (.configure TiptapLink
                                                   #js {:openOnClick false
                                                        :autolink true
                                                        :linkOnPaste true})
                                       ;; Placeholder (the HTML `placeholder`
                                       ;; attribute does nothing on
                                       ;; contenteditable — needs the extension)
                                       (.configure Placeholder
                                                   #js {:placeholder (or placeholder
                                                                         "Type a message...")})
                                       ;; Reference marks
                                       tiptap-refs/page-reference-extension
                                       tiptap-refs/user-reference-extension
                                       ;; Autocomplete
                                       page-suggestion-ext
                                       user-suggestion-ext]
                      :content ""
                      :editorProps #js {:attributes #js {:class "chat-tiptap-editor"
                                                          :placeholder (or placeholder "Type a message...")}
                                        :handleKeyDown (fn [_view event]
                                                         ;; Enter to send (unless Shift held for newline)
                                                         (when (and (= (.-key event) "Enter")
                                                                    (not (.-shiftKey event)))
                                                           (when-let [ed @editor-ref]
                                                             ;; Check if suggestion dropdown is open
                                                             (let [suggestion-open? (some-> element
                                                                                            (.querySelector ".tippy-box"))]
                                                               (when (and on-send (not suggestion-open?))
                                                                 (.preventDefault event)
                                                                 (let [html (.getHTML ed)]
                                                                   ;; Only send if there's content
                                                                   (when (and html
                                                                              (not= html "<p></p>")
                                                                              (not= html ""))
                                                                     (on-send html)
                                                                     ;; Clear editor
                                                                     (-> ed
                                                                         (.chain)
                                                                         (.clearContent)
                                                                         (.focus)
                                                                         (.run))))
                                                                 true)))))}})]
    ;; Store editor reference for handleKeyDown closure
    (reset! editor-ref editor)
    editor))

(defn destroy-chat-editor
  "Clean up a TipTap editor instance."
  [editor]
  (when editor
    (.destroy editor)))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn get-editor-text
  "Get plain text from the editor."
  [editor]
  (when editor
    (.getText editor)))

(defn get-editor-html
  "Get HTML content from the editor."
  [editor]
  (when editor
    (.getHTML editor)))

(defn clear-editor
  "Clear editor content."
  [editor]
  (when editor
    (-> editor
        (.chain)
        (.clearContent)
        (.focus)
        (.run))))

(defn focus-editor
  "Focus the editor."
  [editor]
  (when editor
    (-> editor
        (.chain)
        (.focus)
        (.run))))

;; ============================================================================
;; HTML to Plain Text Extraction (for database storage)
;; ============================================================================

(defn html-to-text
  "Extract plain text from HTML content.
   Preserves [[page]] and @user references."
  [html]
  (when html
    (let [temp-div (js/document.createElement "div")]
      (set! (.-innerHTML temp-div) html)
      (.-textContent temp-div))))
