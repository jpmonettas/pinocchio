(ns pinocchio.components.robots.simple-tracker
  (:require [com.stuartsierra.component :as comp]
            [pinocchio.components
             [devices-drivers :as dd-cmp]
             [monitor :as monitor-cmp]]
            [pinocchio.utils.opencv :as cv-utils]
            [clojure.core.async :as async])
  (:import org.opencv.core.Mat))

(defrecord RobotCmp [monitor-cmp devices-drivers-cmp])



(defn create-robot-thread [cam-ch filtered-ch tracked-ch speed-ch]
  (Thread.
   (fn []
     (while (not (Thread/interrupted))
       (let [cam-frame (async/<!! cam-ch)
             filtered-frame (cv-utils/filter-frame-color [10 120 100] [20 200 200] cam-frame)]
         (async/>!! filtered-ch filtered-frame)
         
         (when-let [{:keys [x1 y1 x2 y2]} (first (cv-utils/bounding-boxes filtered-frame))]
           (async/>!! tracked-ch
                      (cv-utils/draw-rectangle cam-frame x1 y1 x2 y2)))
         (Thread/sleep 50))))))

(extend-type RobotCmp
  
  comp/Lifecycle
  (start [{:keys [devices-drivers-cmp monitor-cmp robot-thread] :as this}]
    (let [filtered-ch (async/chan (async/sliding-buffer 1))
          tracked-ch (async/chan (async/sliding-buffer 1))
          robot-thread (create-robot-thread (dd-cmp/camera-frames-ch devices-drivers-cmp :laptop-cam)
                                            filtered-ch
                                            tracked-ch
                                            nil)]
      
      (monitor-cmp/publish-video-stream monitor-cmp "camera1" (dd-cmp/camera-frames-ch devices-drivers-cmp
                                                                                       :laptop-cam))
      
      (monitor-cmp/publish-video-stream monitor-cmp "color-filtered" filtered-ch)
      (monitor-cmp/publish-video-stream monitor-cmp "tracked" tracked-ch)
      (.start robot-thread)
      (-> this
          (assoc :robot-thread robot-thread))))
  
  (stop [this]
    (.interrupt (:robot-thread this))
    (-> this
        (dissoc :robot-thread))))
