(ns pinocchio.components.monitor
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as comp]
            [compojure.core :as compojure :refer [GET POST]]
            [clojure.data.json :as json]
            [pinocchio.utils.opencv :as cv-utils]
            [ring.middleware
             [keyword-params :refer [wrap-keyword-params]]
             [params :refer [wrap-params]]]
            [org.httpkit.server
             :as
             http-kit-server
             :refer
             [on-close send! with-channel]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            [clojure.core.async :as async]))

(defprotocol MonitorP
  (publish-video-stream [_ stream-name source-ch])
  (publish-stats-map [_ stats-map]))

(defrecord MonitorCmp [http-server streams system-config chsk])

(defn- create-stream-push-thread [http-kit-async-ch origin-ch]
  (async/go-loop [] ;; go loop will last until origin ch gets closed
    (when-let [frame-mat  (async/<! origin-ch)]
      (let [frame-bytes (cv-utils/mat->ext-byte-arr frame-mat :jpeg)]
       (send! http-kit-async-ch (format "--jpgboundary\r\nContent-type: image/jpeg\r\nContent-length: %d\r\n"
                                        (count frame-bytes))
              false)
       (send! http-kit-async-ch "\r\n" false)
       (send! http-kit-async-ch (clojure.java.io/input-stream frame-bytes) false)
       (recur)))))

(defn- build-requests-handler [chsk streams]
  (-> (compojure/routes
       (GET "/streams" []
            {:status 200
             :headers {"Content-type" "application/json"}
             :body (->> @streams
                        keys
                        (json/write-str))})
       
       (GET "/streams/:stream-id" [stream-id :as req]
            (if-let [{:keys [origin-ch stream-ch]} (get @streams stream-id)]
              (if-not stream-ch

                (with-channel req http-kit-async-resp-ch
                  (on-close http-kit-async-resp-ch (fn [status] (swap! streams update stream-id  dissoc :stream-ch)))
                  (send! http-kit-async-resp-ch
                         {:status 200
                          :headers {"Content-type" "multipart/x-mixed-replace; boundary=--jpgboundary"}}
                         false)
                  (swap! streams assoc-in [stream-id :stream-ch] http-kit-async-resp-ch)
                  (create-stream-push-thread http-kit-async-resp-ch origin-ch))

                {:status 403
                 :body "There is already someone here. Only one session allowed."})

              ;; no stream found in our streams map for that stream-id
              {:status 404
               :body "Stream not found."}))

       (GET "/ws" req (do
                        ((:send-fn chsk) :sente/all-users-without-uid
                         [:pinocchio.monitor/streams (->> @streams
                                                          keys
                                                          (map name))])
                        ((:ajax-get-or-ws-handshake-fn chsk) req)))
       (POST "/ws" req ((:ajax-post-fn chsk) req)))
      wrap-keyword-params
      wrap-params))

(extend-type MonitorCmp
  
  comp/Lifecycle
  (start [this]
    (let [streams (atom {})
          stats-agent (agent {})
          chsk (sente/make-channel-socket! (get-sch-adapter) {})]

      ;; periodically send stats reports thru ws
      (async/go-loop []
        (async/<! (async/timeout 500))
        ((:send-fn chsk) :sente/all-users-without-uid [:pinocchio.monitor/new-stats @stats-agent])
        (recur))
      
      (-> this
          (assoc :streams streams)
          (assoc :chsk chsk)
          (assoc :stats-agent stats-agent)
          (assoc :http-server (http-kit-server/run-server (build-requests-handler chsk streams)
                                                          {:port (-> this :system-config :monitor-http-port)})))))
  (stop [this]
    ;; stop httpkit server
    ((-> this :http-server))

    (-> this
        (dissoc :http-server)))

  MonitorP
  (publish-video-stream [{:keys [streams] :as this} stream-id source-ch]
    (if (contains? @streams stream-id)
      (throw (ex-info "There is already a stream under that id" {:stream-id stream-id}))

      (swap! (:streams this)
             assoc stream-id {:origin-ch source-ch
                              :stream-ch nil})))

  (publish-stats-map [{:keys [stats-agent]} stats-map]
    (send stats-agent merge stats-map)))


