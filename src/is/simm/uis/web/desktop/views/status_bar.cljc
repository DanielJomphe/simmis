(ns is.simm.uis.web.desktop.views.status-bar
  "Status bar view component for Spindel delta rendering.

   The status bar displays:
   - Loading states with optional progress
   - Success messages (auto-dismiss)
   - Error messages with type badges and expandable details

   This is a pure view function - state is managed via signals."
  (:require [org.replikativ.spindel.dom.elements :as dom]
            [is.simm.uis.web.desktop.views.core :as vc]))

;; =============================================================================
;; Status Icons
;; =============================================================================

(defn- status-icon
  "Render the appropriate icon for the status type.
   Uses explicit :key to ensure proper delta rendering when type changes."
  [status-type]
  (case status-type
    :loading (dom/div {:key :loading-icon :class "status-icon status-icon--loading"}
               (vc/icon :loader {:class "spin"}))
    :success (dom/div {:key :success-icon :class "status-icon status-icon--success"}
               (vc/icon :check-circle))
    :error   (dom/div {:key :error-icon :class "status-icon status-icon--error"}
               (vc/icon :alert-circle))
    nil))

;; =============================================================================
;; Error Type Badge
;; =============================================================================

(defn- error-type-badge
  "Render an error type badge for classification.
   Uses explicit :key for delta rendering."
  [error-type]
  (when error-type
    (let [label (case error-type
                  :network    "Network"
                  :validation "Validation"
                  :server     "Server"
                  :not-found  "Not Found"
                  :permission "Permission"
                  "Error")]
      (dom/span {:key :error-badge :class (str "error-type-badge error-type-badge--" (name error-type))}
        label))))

;; =============================================================================
;; Progress Bar
;; =============================================================================

(defn- progress-bar
  "Render a progress bar."
  [progress]
  (when progress
    (dom/div {:class "status-progress"}
      (dom/div {:class "status-progress-bar"
                :style {:width (str progress "%")}}))))

;; =============================================================================
;; Main Status Bar View
;; =============================================================================

(defn status-bar-view
  "Render the status bar component.

   Props:
     :status - Status state map from signals:
       {:visible?   boolean
        :type       :idle | :loading | :success | :error
        :message    string
        :details    string or nil
        :error-type keyword or nil
        :expanded?  boolean
        :progress   number or nil}
     :on-dismiss - Callback to dismiss the status bar
     :on-toggle-expand - Callback to toggle details expansion"
  [{:keys [status on-dismiss on-toggle-expand]}]
  (let [{:keys [visible? type message details error-type expanded? progress]} status]
    ;; Always render the container for grid layout, but only show content when visible
    (dom/div {:class (vc/class-names "status-bar"
                                     (when visible? (str "status-bar--" (name (or type :idle))))
                                     (when expanded? "status-bar--expanded")
                                     (when-not visible? "status-bar--hidden"))}
      (when visible?
        (list
          ;; Icon
          (status-icon type)

          ;; Error type badge (only for errors)
          (when (and (= type :error) error-type)
            (error-type-badge error-type))

          ;; Message
          (dom/div {:class "status-bar__message"}
            message)

          ;; Progress bar (only for loading)
          (when (= type :loading)
            (progress-bar progress))

          ;; Actions
          (dom/div {:class "status-bar__actions"}
            ;; Expand/collapse details button (only if details exist)
            ;; Uses explicit :key to ensure proper delta rendering
            (when details
              (vc/icon-button (if expanded? :chevron-up :chevron-down)
                              {:key :toggle-details
                               :class "status-bar__action"
                               :title (if expanded? "Hide details" "Show details")
                               :on-click on-toggle-expand}))

            ;; Dismiss button (not for loading)
            ;; Uses explicit :key to ensure proper delta rendering
            (when (not= type :loading)
              (vc/icon-button :x {:key :dismiss-btn
                                  :class "status-bar__action status-bar__dismiss"
                                  :title "Dismiss"
                                  :on-click on-dismiss})))

          ;; Expandable details section
          ;; Uses explicit :key to ensure proper delta rendering
          (when (and expanded? details)
            (dom/div {:key :details-section :class "status-bar__details"}
              (dom/pre {:class "status-bar__details-text"}
                details))))))))

;; =============================================================================
;; Inline Status Indicator
;; =============================================================================

(defn inline-status
  "Render a small inline status indicator (for buttons, etc.).

   Props:
     :type - :loading | :success | :error
     :message - Optional tooltip message

   Note: Uses explicit :key on icons for proper delta rendering when type changes."
  [{:keys [type message]}]
  (dom/span {:class (vc/class-names "inline-status"
                                    (str "inline-status--" (name type)))
             :title message}
    (case type
      :loading (vc/icon :loader {:key :loading-icon :class "spin"})
      :success (vc/icon :check {:key :success-icon})
      :error   (vc/icon :alert-triangle {:key :error-icon})
      nil)))

;; =============================================================================
;; Toast Notification (Alternative Display)
;; =============================================================================

(defn toast-view
  "Render a toast notification (alternative to status bar).

   Props:
     :type - :info | :success | :warning | :error
     :message - Toast message
     :on-dismiss - Callback to dismiss

   Note: Uses explicit :key for proper delta rendering when type changes."
  [{:keys [type message on-dismiss]}]
  (let [icon-name (case type
                    :info    :info
                    :success :check-circle
                    :warning :alert-triangle
                    :error   :alert-circle
                    :info)
        ;; Use type as key to differentiate icons
        icon-key (keyword (str (name type) "-toast-icon"))]
    (dom/div {:class (vc/class-names "toast" (str "toast--" (name type)))}
      (vc/icon icon-name {:key icon-key :class "toast__icon"})
      (dom/span {:class "toast__message"} message)
      (vc/icon-button :x {:key :toast-dismiss
                          :class "toast__dismiss"
                          :on-click on-dismiss}))))
