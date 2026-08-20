(ns is.simm.uis.web.desktop.views.mermaid
  "Render a mermaid diagram string to inline SVG. mermaid is loaded from CDN in
   index.html (same as lucide/vega) rather than bundled — its full npm build
   pulls @upsetjs/venn.js, which shadow can't resolve. Client-only. Used by the
   Schedules/workflow view to draw the katzen `->mermaid` topology."
  (:require [org.replikativ.spindel.dom.elements :as el]
            [org.replikativ.spindel.dom.foreign])
  (:require-macros [org.replikativ.spindel.dom.elements :as el]
                   [org.replikativ.spindel.dom.foreign :refer [foreign-node]]))

(defonce ^:private inited? (atom false))

(defn- ensure-init! []
  (when (and (not @inited?) (exists? js/mermaid))
    (.initialize js/mermaid
                 #js {:startOnLoad false
                      :theme "dark"
                      ;; strict: never honour click/script directives in a
                      ;; diagram string (defense-in-depth — the string is built
                      ;; from schedule metadata, but treat it as data regardless).
                      :securityLevel "strict"
                      :flowchart #js {:htmlLabels true :curve "basis"}})
    (reset! inited? true)))

(defn render-into!
  "Imperatively render mermaid `code` into DOM element `el`. For markdown
   post-processing (chat/wiki) where a ```mermaid fence is replaced in place.
   On failure or if the CDN script hasn't loaded, leaves the raw code as text."
  [el code]
  (when (and el (string? code) (seq code))
    (if (exists? js/mermaid)
      (do (ensure-init!)
          (-> (.render js/mermaid (str "mmdsvg-" (js/Math.abs (hash code))) code)
              (.then (fn [res] (set! (.-innerHTML el) (.-svg ^js res))))
              (.catch (fn [_e] (set! (.-textContent el) code)))))
      (set! (.-textContent el) code))))

(defn diagram
  "A foreign-node that renders `code` (a mermaid string) to inline SVG. Keyed on
   the code so it re-renders when the diagram changes. mermaid.render is async;
   on failure (or if the CDN script hasn't loaded) we leave the raw code so the
   panel degrades to text rather than throwing into the render tree."
  [code]
  (foreign-node
    {:key (str "mmd-" (hash code))
     :class "mermaid-diagram"
     :on-mount
     (fn [container]
       (when (and container (string? code) (seq code))
         (if (exists? js/mermaid)
           (do (ensure-init!)
               (-> (.render js/mermaid (str "mmdsvg-" (js/Math.abs (hash code))) code)
                   (.then (fn [res] (set! (.-innerHTML container) (.-svg ^js res))))
                   (.catch (fn [_e]
                             (set! (.-className container) "mermaid-diagram mermaid-diagram--error")
                             (set! (.-textContent container) "diagram unavailable")))))
           (do (set! (.-className container) "mermaid-diagram mermaid-diagram--error")
               (set! (.-textContent container) "diagram unavailable")))))}))
