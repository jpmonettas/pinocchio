(ns pinocchio.components.devices-drivers
  (:require [com.stuartsierra.component :as comp]
            [clojure.core.async :as async]
            [taoensso.timbre :as l]
            [pinocchio.utils.opencv :as cv-utils])
  (:import [org.opencv.videoio VideoCapture]
           org.opencv.core.Mat))

(defprotocol DevicesDriversP
  (cameras [_])
  (camera-frames-ch [_ cam-id])
  (set-motor-speed [_ speed]))

(defrecord DevicesDriversCmp [cams])


(defn- create-camera [cam-id id-or-url]
  (let [cam-ch (async/chan (async/sliding-buffer 1))
        cam-mult (async/mult cam-ch)
        vc (VideoCapture. id-or-url)
        frame-read-thread (Thread. (fn []
                                     (while (not (Thread/interrupted))
                                       (if (.isOpened vc)
                                         (let [frame (Mat.)]
                                           (Thread/sleep 10)
                                           (if (.read vc frame)
                                             (async/>!! cam-ch (cv-utils/pyr-down frame))
                                             (l/error (str "Can't read a frame from video capture " cam-id " " id-or-url))))

                                        (do
                                          (l/error (str "Video capture isn't open " cam-id " " id-or-url ". Reopenning in 1 sec."))
                                          (Thread/sleep 1000)
                                          (l/info "Reopenning " (.open vc id-or-url))))
                                       )))]
    
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
  
  (set-motor-speed [_ speed]
    (l/info "Change speed to " speed))
  
  )

(defn create-devices-drivers [devices-drivers-config]
  (map->DevicesDriversCmp {:cams (reduce-kv
                                  (fn [r cam-id id-or-url]
                                    (assoc r cam-id (create-camera cam-id id-or-url)))
                                  {}
                                  (:cameras devices-drivers-config))}))

