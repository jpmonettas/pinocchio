(ns pinocchio.components.robots.simple-tracker
  (:require [com.stuartsierra.component :as comp]
            [pinocchio.components
             [devices-drivers :as dd-cmp]
             [monitor :as monitor-cmp]]
            [pinocchio.utils.opencv :as cv-utils]
            [clojure.core.async :as async])
  (:import org.opencv.core.Mat))

(defrecord RobotCmp [monitor-cmp devices-drivers-cmp robot-thread speed-ch])

(defn calculate-new-speed [area]
  (cond
    (<= area 30)        0
    (<= 30 area 3000)   1
    (<= 3000 area 7000) 0
    (<= 7000 area)     -1
    ))



(defn robot-thread-step [monitor-cmp camera-1-ch filtered-ch tracked-ch speed-ch]
  (let [cam-frame (async/<!! camera-1-ch)
        filtered-frame (cv-utils/filter-frame-color
                        ;; [90 100 0] [100 130 255] ;; cyan charger with laptop cam
                        [90 200 0] [95 255 255] ;; cyan charger with cel cam
                        cam-frame)]

    ;; for debugging what's filtered
    (async/>!! filtered-ch filtered-frame)
    
    (when-let [{:keys [x1 y1 x2 y2 area]} (first (cv-utils/bounding-boxes filtered-frame))]
      ;; for debugging what's tracked
      (async/>!! tracked-ch (cv-utils/draw-rectangle cam-frame x1 y1 x2 y2))
      (monitor-cmp/publish-stats-map monitor-cmp {:biggest-object-area area})

      ;; write the new speed
      (async/>!! speed-ch (calculate-new-speed area)))))

(extend-type RobotCmp
  
  comp/Lifecycle
  (start [{:keys [devices-drivers-cmp monitor-cmp] :as this}]
    (let [filtered-ch (async/chan (async/sliding-buffer 1))
          tracked-ch (async/chan (async/sliding-buffer 1))
          speed-ch (async/chan (async/sliding-buffer 1))
          robot-thread (let [camera-frames-ch (dd-cmp/camera-frames-ch devices-drivers-cmp :camera1)]
                         (Thread.
                          (fn []
                            (while (not (Thread/interrupted))
                              (robot-thread-step monitor-cmp
                                                 camera-frames-ch
                                                 filtered-ch
                                                 tracked-ch
                                                 speed-ch)))))]
      
      (monitor-cmp/publish-video-stream monitor-cmp "camera1" (dd-cmp/camera-frames-ch devices-drivers-cmp
                                                                                       :camera1))
      
      (monitor-cmp/publish-video-stream monitor-cmp "color-filtered" filtered-ch)
      (monitor-cmp/publish-video-stream monitor-cmp "tracked" tracked-ch)

      (.start robot-thread)
      
      (async/go-loop [old-speed 0]
        (when-let [speed (async/<! speed-ch)]
          (when (not= old-speed speed)
            (monitor-cmp/publish-stats-map monitor-cmp {:current-speed speed})
            (dd-cmp/set-motor-speed devices-drivers-cmp :main-motor speed))
          (recur speed)))
      
      (-> this
          (assoc :robot-thread robot-thread)
          (assoc :speed-ch speed-ch))))
  
  
  (stop [this]
    (when-let [rt (:robot-thread this)] (.interrupt rt))
    (async/close! (:speed-ch this))
    (-> this
        (dissoc :robot-thread))))
