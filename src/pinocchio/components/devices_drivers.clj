(ns pinocchio.components.devices-drivers
  (:require [com.stuartsierra.component :as comp]
            [clojure.core.async :as async])
  (:import [org.opencv.highgui Highgui VideoCapture]
           org.opencv.core.Mat))

(defprotocol DevicesDriversP
  (cameras [_])
  (camera-frames-ch [_ cam-id])
  (set-motor-speed [_]))

(defrecord DevicesDriversCmp [cams])


(defn- create-camera [cam-id id-or-url]
  (let [cam-ch (async/chan (async/sliding-buffer 1))
        cam-mult (async/mult cam-ch)
        vc (if (integer? id-or-url)
             (VideoCapture. id-or-url)
             ;; else it's a string so open the url
             (let [vc (VideoCapture.)]
               (.open vc id-or-url)
               vc))
        frame-read-thread (Thread. (fn []
                                     (while (and (not (Thread/interrupted))
                                                 (.isOpened vc))
                                       (let [frame (Mat.)]
                                         (when (.read vc frame)
                                           (async/>!! cam-ch frame)))
                                       (Thread/sleep 10))))]
    
    {:video-capture vc
     :cam-mult cam-mult
     :frame-read-thread frame-read-thread}))

(extend-type DevicesDriversCmp
  comp/Lifecycle
  (start [this]

    ;; Start all cameras frame read threads 
    (doseq [cam (vals (:cams this))]
      (.start (:frame-read-thread cam)))
    this)
  
  (stop [this]

    ;; Stop all frame read threads and release all video captures
    (doseq [cam (vals (:cams this))]
      (.interrupt (:frame-read-thread cam))
      (.release (:video-capture cam)))

    (-> this
        (dissoc :cams)))

  DevicesDriversP
  
  (cameras [this]
    (-> this :cams keys))
  
  (camera-frames-ch [this cam-id]
    (let [ch (async/chan (async/sliding-buffer 1)
                         (map (fn [^Mat m]
                                (.clone m))))]
      (async/tap (get-in this [:cams cam-id :cam-mult])
                 ch)
      ch))
  
  (set-motor-speed [_])
  )

(defn create-devices-drivers [devices-drivers-config]
  (map->DevicesDriversCmp {:cams (reduce-kv
                                  (fn [r cam-id id-or-url]
                                    (assoc r cam-id (create-camera cam-id id-or-url)))
                                  {}
                                  (:cameras devices-drivers-config))}))

