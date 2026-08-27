(ns is.simm.model.fake-models-server
  "Local model-list fixture. Responses are scoped by path and bearer credential
   so tests can prove that identical URLs still receive two provider requests.

   Fixture credentials are literals invented here. No test reads a real
   provider key, and none is ever logged or asserted on."
  (:require [jsonista.core :as json])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn openai-models-body
  "The OpenAI Models-API envelope: what OpenAI documents, and what Fireworks'
   inference base is observed to answer."
  [models]
  {:object "list"
   :data (mapv (fn [id] {:id id :object "model"}) models)})

(defn- response-bytes [body]
  (.getBytes (json/write-value-as-string body) StandardCharsets/UTF_8))

(defn- handle! [responses requests ^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))
        authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
        {:keys [status body]
         :or {status 503 body (openai-models-body [])}}
        (get @responses [path authorization])
        payload (response-bytes body)]
    (swap! requests conj {:path path :authorization authorization})
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength payload))
    (with-open [out (.getResponseBody exchange)]
      (.write out payload))))

(defn with-server [f]
  (let [responses (atom {})
        requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (handle! responses requests exchange))))
    (.start server)
    (try
      (f {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
          :responses responses
          :requests requests})
      (finally
        (.stop server 0)))))

(defn respond-with!
  "Answer `path` with an exact status and body for this credential. Tests that
   need a foreign schema, a truncated page, or a rejection use this directly."
  [{:keys [responses]} path credential status body]
  (swap! responses assoc
         [path (str "Bearer " credential)]
         {:status status :body body}))

(defn respond!
  "A successful model list in the contract shape this code parses."
  [fixture path credential models]
  (respond-with! fixture path credential 200 (openai-models-body models)))

(defn outage!
  "A temporary provider failure: the endpoint answers, badly, and recovery is a
   matter of waiting."
  [fixture path credential]
  (respond-with! fixture path credential 503 (openai-models-body [])))

(defn reject-credential!
  "The provider answered and refused the key. The body mirrors what Fireworks
   returns for an invalid key; it carries no credential value."
  [fixture path credential]
  (respond-with! fixture path credential 401
                 {:error {:message "The API key you provided is invalid."
                          :code "UNAUTHORIZED"
                          :type "error"}}))
