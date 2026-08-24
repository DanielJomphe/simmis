(ns is.simm.uis.web.desktop.model-picker-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.uis.web.desktop.views.model-picker :as picker]))

(def unavailable-row
  {:value "gpt-*-luna"
   :available? false
   :availability :needs-credential
   :availability-label "Credential required"
   :availability-explanation
   "Set OPENAI_API_KEY in the server environment, then restart simmis."})

(deftest unavailable-rows-have-focusable-semantic-disabled-state
  (let [attrs (picker/semantic-attrs unavailable-row)]
    (is (= "button" (:role attrs)))
    (is (= 0 (:tabindex attrs)))
    (is (= "true" (:aria-disabled attrs)))
    (is (= (picker/explanation-id (:value unavailable-row))
           (:aria-describedby attrs))))

  (let [attrs (picker/semantic-attrs
               (assoc unavailable-row :available? true))]
    (is (= "false" (:aria-disabled attrs)))
    (is (nil? (:aria-describedby attrs)))))

(deftest pointer-and-keyboard-activation-are-both-fail-closed
  (let [selected (atom [])
        unavailable (picker/option-attrs unavailable-row #(swap! selected conj %))
        available (picker/option-attrs (assoc unavailable-row :available? true)
                                       #(swap! selected conj %))]
    (testing "disabled rows reject mouse, Enter, and Space"
      ((:on-click unavailable) {})
      ((:on-key-down unavailable) {:key "Enter"})
      ((:on-key-down unavailable) {:key " "})
      (is (= [] @selected)))
    (testing "available rows accept pointer and activation keys"
      ((:on-click available) {})
      ((:on-key-down available) {:key "Enter"})
      ((:on-key-down available) {:key " "})
      ((:on-key-down available) {:key "ArrowDown"})
      (is (= ["gpt-*-luna" "gpt-*-luna" "gpt-*-luna"] @selected)))))
