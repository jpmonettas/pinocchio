(ns pinocchio.components.devices-drivers
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as comp]
            [pinocchio.utils.opencv :as cv-utils]
            [taoensso.timbre :as l])
  (:import [com.pi4j.io.i2c I2CBus I2CFactory]
           org.opencv.core.Mat
           org.opencv.videoio.VideoCapture))

(defprotocol DevicesDriversP
  (cameras [_])
  (camera-frames-ch [_ cam-id])
  (set-motor-speed [_ motor-id speed]))

(defrecord DevicesDriversCmp [system-config cams arduino-i2c-device])


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
      (l/info "Starting " cam " camera thread")
      (.start (:frame-read-thread cam)))

    (cond-> this

      (-> this :system-config :i2c-enable?)
      (assoc :arduino-i2c-device (-> (I2CFactory/getInstance I2CBus/BUS_1)
                                     ;; 0x9 arduino address
                                     (.getDevice 16r9)))))
  
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
  
  (set-motor-speed [{:keys [arduino-i2c-device system-config]} motor-id speed]
    (l/info (format "Changed speed of %s to %s" motor-id speed))
    (when (:i2c-enable? system-config)
     (if arduino-i2c-device
       (.write arduino-i2c-device (byte-array [ ;; first byte is device id
                                               (get-in system-config [:devices-drivers :motors motor-id])
                                               
                                               ;; first bit represents direction
                                               ;; - 1 backwards - 0 forward
                                               ;; last 7 represents speed
                                               (byte speed)]))
       (l/error "Arduino i2c device is null")))))

(defn create-devices-drivers [system-config devices-drivers-config]
  (map->DevicesDriversCmp {:cams (reduce-kv
                                  (fn [r cam-id id-or-url]
                                    (assoc r cam-id (create-camera cam-id id-or-url)))
                                  {}
                                  (:cameras devices-drivers-config))
                           :system-config system-config}))

