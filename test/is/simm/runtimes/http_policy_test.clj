(ns is.simm.runtimes.http-policy-test
  "The HTTP plane's deny-by-default gate.

   The RPC plane refuses an unpoliced fn; the HTTP plane had no equivalent and
   grew route by route with an ad-hoc `require-auth` inside each handler. Two
   routes got through that way — `GET /blobs/:id` and `/apps/<slug>/`, both
   serving files to anyone who could reach the port.

   The guarantee is now at BOOT: `validate-auth-declared!` is reitit's
   `:validate` hook, so a route with no `:auth` throws when the router is
   CONSTRUCTED and the server never starts. That is stronger than a test —
   a test can be skipped, a boot failure cannot — so what is asserted here is
   the MECHANISM: that the validator rejects what it should, accepts what it
   should, and that the middleware enforces each declared form."
  (:require [clojure.test :refer [deftest is testing]]
            [reitit.ring :as ring]
            [is.simm.model.access :as access]
            [is.simm.runtimes.auth-config :as auth-cfg]
            [is.simm.runtimes.http-auth :as hauth]))

(defn- build
  "Construct a router the way `web/make-handler` does. Returns :built, or the
   offending route list from the validator's ex-data."
  [routes]
  (try (ring/router routes {:validate hauth/validate-auth-declared!
                            :data {:middleware [hauth/auth-middleware]}})
       :built
       (catch Exception e (or (:routes (ex-data e)) (.getMessage e)))))

(def ^:private ok (fn [_] {:status 200 :body "ok"}))

;; =============================================================================
;; Boot-time validation
;; =============================================================================

(deftest a-route-without-auth-stops-the-boot
  (testing "the omission that produced both live findings"
    (is (= ["GET /oops"] (build [["/oops" {:get {:handler ok}}]]))))

  (testing "and it names the offender rather than just failing"
    ;; A vector where most routes are fine and one is not is the realistic
    ;; case — a new route added beside existing ones.
    (is (= ["POST /b"]
           (build [["/a" {:get {:handler ok :auth :public}}]
                   ["/b" {:post {:handler ok}}]]))))

  (testing "a value outside the vocabulary is not a declaration"
    ;; `:auth :yes` reads like a decision and is not one; accepting it would
    ;; make the gate's `:else` branch reachable in production.
    (is (= ["GET /bad"] (build [["/bad" {:get {:handler ok :auth :sure}}]])))))

(deftest every-valid-form-builds
  (testing ":public"
    (is (= :built (build [["/x" {:get {:handler ok :auth :public}}]]))))
  (testing ":authenticated"
    (is (= :built (build [["/x" {:get {:handler ok :auth :authenticated}}]]))))
  (testing "a resource form"
    (is (= :built (build [["/x" {:get {:handler ok
                                       :auth {:action :read
                                              :resource (fn [_] {:room 1})}}}]]))))
  (testing "a resource form MISSING its resolver is not valid"
    (is (vector? (build [["/x" {:get {:handler ok :auth {:action :read}}}]])))))

(deftest a-parent-may-declare-for-its-subtree
  (testing "how /auth/* is declared once for routes a library supplies"
    (is (= :built (build [["/auth" {:auth :public}
                           ["/login"   {:post {:handler ok}}]
                           ["/refresh" {:post {:handler ok}}]]])))))

;; =============================================================================
;; Enforcement
;; =============================================================================

(defn- app
  "A one-route app under the gate, with identity and `can?` stubbed so the
   decision is observable without a system DB or a real token."
  [auth & {:keys [party can] :or {party nil can false}}]
  (let [handler (ring/ring-handler
                  (ring/router [["/r" {:get {:handler ok :auth auth}}]]
                               {:data {:middleware [hauth/auth-middleware]}}))]
    (with-redefs [hauth/party-id (fn [_] party)
                  access/can? (fn [& _] can)]
      (:status (handler {:request-method :get :uri "/r"})))))

(deftest public-needs-nothing
  (is (= 200 (app :public))))

(deftest authenticated-needs-a-principal
  (testing "no principal"
    (is (= 401 (app :authenticated))))
  (testing "a principal, and nothing more is asked"
    (is (= 200 (app :authenticated :party (random-uuid) :can false))
        "`:authenticated` must NOT consult can? — the handler scopes itself")))

(deftest a-resource-form-asks-can?
  (let [a {:action :read :resource (fn [_] {:room (random-uuid)})}]
    (testing "unauthenticated is refused before can? is reached"
      (is (= 401 (app a :can true))))
    (testing "authenticated but not permitted"
      (is (= 403 (app a :party (random-uuid) :can false))))
    (testing "authenticated and permitted"
      (is (= 200 (app a :party (random-uuid) :can true))))))

(deftest a-throwing-resolver-denies
  (testing "deny-by-default has to survive a BROKEN check, not just a false one"
    ;; Same reasoning as `authorize-remote`: a resolver that throws otherwise
    ;; escapes as a 500 that reads like a bug, and the request is served or
    ;; not depending on where the exception lands.
    (let [a {:action :read :resource (fn [_] (throw (ex-info "boom" {})))}]
      (is (= 403 (app a :party (random-uuid) :can true))
          "can? stubbed TRUE — the refusal must come from the resolver failing"))))

(deftest an-undeclared-route-is-refused-at-request-time-too
  (testing "belt to the boot check's braces"
    ;; Reaching this means the validator was bypassed (a router built without
    ;; :validate). Serving the route would be the wrong answer.
    (is (= 500 (app nil :party (random-uuid) :can true)))))

;; =============================================================================
;; The blob cookie
;; =============================================================================
;;
;; `<img src>` and `<a href>` are issued by the BROWSER, so they cannot carry
;; an Authorization header — a cookie is the only credential they can present.
;; Which means this credential travels on requests our code did not write, and
;; its scope has to be enforced where it cannot be argued with.

(def ^:private a-party #uuid "cafe0000-0000-0000-0000-000000000001")

(defn- with-stub-validator
  "Run `f` with the bearer validator replaced by one that accepts exactly
   `token` — the JWT machinery has its own coverage; what is under test is
   WHICH requests are allowed to present a cookie."
  [token f]
  (with-redefs [hauth/validator
                (delay (fn [req]
                         (when (= (str "Bearer " token)
                                  (get-in req [:headers "authorization"]))
                           {:sub (str a-party)})))]
    (f)))

(defn- party-for [method headers]
  (with-stub-validator "good"
    #(hauth/party-id {:request-method method :headers headers :uri "/blobs/x"})))

(deftest the-cookie-authenticates-reads
  (testing "the <img> case: no header, cookie only"
    (is (= a-party (party-for :get {"cookie" (str auth-cfg/blob-cookie-name "=good")}))))
  (testing "alongside other cookies, which is how a browser sends them"
    (is (= a-party (party-for :get {"cookie" (str "other=1; " auth-cfg/blob-cookie-name
                                                  "=good; another=2")})))))

(deftest the-cookie-cannot-authorize-a-write
  ;; `Path=/blobs/` was meant to keep it off the upload POST at `/blobs`, and
  ;; by RFC 6265 it does. MEASURED: curl sends it anyway. Whichever client is
  ;; right, the scope of a credential is not the client's to decide.
  (testing "the same cookie that reads must not write"
    (doseq [m [:post :put :delete :patch]]
      (is (nil? (party-for m {"cookie" (str auth-cfg/blob-cookie-name "=good")}))
          (str m " must not be authenticated by the cookie"))))
  (testing "a write still authenticates with the header"
    (is (= a-party (party-for :post {"authorization" "Bearer good"})))))

(deftest a-bad-cookie-is-not-a-principal
  (testing "an invalid token in a well-formed cookie"
    (is (nil? (party-for :get {"cookie" (str auth-cfg/blob-cookie-name "=forged")}))))
  (testing "a cookie whose name merely ends in ours"
    ;; The regex anchors on a boundary; `evil_simmis_blob=good` must not match.
    (is (nil? (party-for :get {"cookie" (str "evil_" auth-cfg/blob-cookie-name "=good")}))))
  (testing "no cookie at all"
    (is (nil? (party-for :get {})))))

(deftest the-header-still-wins
  (testing "a valid header is used even when a cookie is present"
    (is (= a-party (party-for :get {"authorization" "Bearer good"
                                    "cookie" (str auth-cfg/blob-cookie-name "=forged")})))))
