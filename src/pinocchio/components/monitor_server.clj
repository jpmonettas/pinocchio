(ns pinocchio.components.monitor-server
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as comp]
            [compojure.core :as compojure :refer [GET POST]]
            [clojure.data.json :as json]
            [pinocchio.utils.opencv :as cv-utils]
            [pinocchio.components.monitor :as monitor-cmp]
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

(defrecord MonitorServerCmp [http-server system-config chsk monitor-cmp])

(defn- create-stream-push-thread [monitor-cmp stream-id http-kit-async-ch]
  (let [origin-ch (monitor-cmp/get-stream-ch monitor-cmp stream-id)]
   (async/go-loop [proceed? true] ;; go loop will last until origin ch gets closed or dst ch gets closed
     (when proceed?
       (when-let [frame-mat  (async/<! origin-ch)]
         (let [frame-bytes (cv-utils/mat->ext-byte-arr frame-mat :jpeg)
               sent-ok? (and (send! http-kit-async-ch (format "--jpgboundary\r\nContent-type: image/jpeg\r\nContent-length: %d\r\n"
                                                              (count frame-bytes))
                                    false)
                             (send! http-kit-async-ch "\r\n" false)
                             (send! http-kit-async-ch (clojure.java.io/input-stream frame-bytes) false))]
           
           (monitor-cmp/inc-stats-counter! monitor-cmp stream-id)
           (recur sent-ok?)))))))

(defn- build-requests-handler [monitor-cmp chsk]
  (-> (compojure/routes
       
       (GET "/streams/:stream-id" [stream-id :as req]
            (if (monitor-cmp/get-stream-ch monitor-cmp stream-id)
              (with-channel req http-kit-async-resp-ch
                (send! http-kit-async-resp-ch
                       {:status 200
                        :headers {"Content-type" "multipart/x-mixed-replace; boundary=--jpgboundary"}}
                       false)
                (create-stream-push-thread monitor-cmp stream-id http-kit-async-resp-ch))

              ;; no stream found in our streams map for that stream-id
              {:status 404
               :body "Stream not found."}))

       (GET "/ws" req (do
                        ((:send-fn chsk) :sente/all-users-without-uid
                         [:pinocchio.monitor/streams (monitor-cmp/get-streams-names monitor-cmp)])
                        ((:ajax-get-or-ws-handshake-fn chsk) req)))
       (POST "/ws" req ((:ajax-post-fn chsk) req)))
      wrap-keyword-params
      wrap-params))

(extend-type MonitorServerCmp
  
  comp/Lifecycle
  (start [this]
    (let [chsk (sente/make-channel-socket! (get-sch-adapter) {})]

      ;; periodically send stats reports thru ws
      (async/go-loop []
        (async/<! (async/timeout 500))
        ((:send-fn chsk) :sente/all-users-without-uid [:pinocchio.monitor/new-stats
                                                       (monitor-cmp/get-stats (:monitor-cmp this))])
        (recur))
      
      (-> this
          (assoc :chsk chsk)
          (assoc :http-server (http-kit-server/run-server (build-requests-handler (:monitor-cmp this) chsk)
                                                          {:port (-> this :system-config :monitor-http-port)})))))
  (stop [this]
    ;; stop httpkit server
    ((-> this :http-server))

    (-> this
        (dissoc :http-server))))
