(ns is.simm.uis.web.desktop.room-actions)

(defn add-tab-to-column
  "Append `tab` and make it active in the explicitly identified column.

   Callers resolve the target from the UI that owns the action. This small,
   pure layout operation deliberately does not consult active-column-id."
  [columns col-id tab]
  (let [target-idx (or (first (keep-indexed (fn [idx column]
                                               (when (= (:id column) col-id) idx))
                                             columns))
                       0)]
    (update-in columns [target-idx]
               (fn [column]
                 (-> column
                     (update :tabs conj tab)
                     (assoc :active-tab (:id tab)))))))

(defn room-header-tab
  "Describe a room-header tab action with the column that rendered it.

   The header button fires before the enclosing column's click handler, so its
   destination must be carried from the render path rather than inferred from
   the currently active column."
  [col-id action room-id room-name]
  (case action
    :screens [:screens {:room-id room-id :room-name room-name}
              {:title (str room-name " Screens") :new-tab? true :col-id col-id}]
    :video [:video {:room-id room-id :room-name room-name}
            {:title (str room-name " Call") :new-tab? true :col-id col-id}]
    :files [:files {:room-id room-id}
            {:title (str room-name " Files") :new-tab? true :col-id col-id}]
    :settings [:room-settings {:room-id room-id}
               {:title (str room-name " Settings") :new-tab? true :col-id col-id}]))
