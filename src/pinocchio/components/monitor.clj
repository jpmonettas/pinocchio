(ns pinocchio.components.monitor
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as comp]
            [compojure.core :as compojure :refer [GET]]
            [pinocchio.utils.opencv :as cv-utils]
            [org.httpkit.server
             :as
             http-kit-server
             :refer
             [on-close send! with-channel]]
            [clojure.core.async :as async]))

(defprotocol MonitorP
  (publish-video-stream [_ stream-name source-ch])
  (publish-stats-map [_ stats-map]))

(defrecord MonitorCmp [http-server streams system-config])

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

(defn- build-requests-handler [streams]
  (compojure/routes
   (GET "/streams" []
        {:status 200
         :body (->> @streams
                    keys
                    (str/join ",")
                    (format "Streams available [%s]"))})
   
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
           :body "Stream not found."}))))

(extend-type MonitorCmp
  
  comp/Lifecycle
  (start [this]
    (let [streams (atom {})]
     (-> this
         (assoc :streams streams)
         
         (assoc :http-server (http-kit-server/run-server (build-requests-handler streams)
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

  (publish-stats-map [this stats-map]))


