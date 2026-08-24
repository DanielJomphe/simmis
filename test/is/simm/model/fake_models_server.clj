(ns is.simm.model.fake-models-server
  "Local `/models` fixture. Responses are scoped by path and bearer credential
   so tests can prove that identical URLs still receive two provider requests."
  (:require [jsonista.core :as json])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- response-bytes [models]
  (.getBytes
   (json/write-value-as-string
    {:object "list"
     :data (mapv (fn [id] {:id id :object "model"}) models)})
   StandardCharsets/UTF_8))

(defn- handle! [responses requests ^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))
        authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
        {:keys [status models]
         :or {status 503 models []}}
        (get @responses [path authorization])
        body (response-bytes models)]
    (swap! requests conj {:path path :authorization authorization})
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength body))
    (with-open [out (.getResponseBody exchange)]
      (.write out body))))

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

(defn respond! [{:keys [responses]} path credential models]
  (swap! responses assoc
         [path (str "Bearer " credential)]
         {:status 200 :models models}))

(defn outage! [{:keys [responses]} path credential]
  (swap! responses assoc
         [path (str "Bearer " credential)]
         {:status 503 :models []}))
