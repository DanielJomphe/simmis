(ns is.simm.model.mail-credentials-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.model.mail-credentials :as credentials]))

(deftest encrypted-mail-config-roundtrip
  (let [account-id (random-uuid)
        config {:email "person@example.com"
                :imap {:host "imap.example.com" :port 993
                       :user "person" :pass "not-returned-to-browser"}}
        master-key-var (ns-resolve 'is.simm.model.mail-credentials 'master-key)]
    (with-redefs-fn
      {master-key-var (delay (byte-array (map byte (range 32))))}
      (fn []
        (let [{:keys [nonce ciphertext]} (credentials/encrypt account-id config)]
          (testing "the encrypted registry value contains no plaintext secret"
            (is (not (.contains ciphertext "not-returned-to-browser"))))
          (testing "the account UUID is authenticated as AAD"
            (is (= config (credentials/decrypt account-id nonce ciphertext)))
            (is (thrown? Exception
                         (credentials/decrypt (random-uuid) nonce ciphertext)))))))))
