(ns pinocchio.components.monitor
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as comp]))

(defprotocol MonitorP
  (publish-video-stream [_ stream-name source-ch])
  (add-to-stats-map [_ stats-map])
  (inc-stats-counter! [_ key])
  (get-stats [_])
  (get-stream-ch [_ id])
  (get-streams-names [_]))

(defrecord MonitorCmp [streams stats-agent system-config])


(extend-type MonitorCmp

  comp/Lifecycle
  (start [this]
    (let [streams (atom {})
          stats-agent (agent {})]

      (-> this
          (assoc :streams streams)
          (assoc :stats-agent stats-agent))))

  (stop [this]
    this)

  MonitorP
  (publish-video-stream [{:keys [streams] :as this} stream-id source-ch]
    (if (contains? @streams stream-id)
      (throw (ex-info "There is already a stream under that id" {:stream-id stream-id}))

      (swap! (:streams this) assoc stream-id source-ch)))

  (add-to-stats-map [{:keys [stats-agent]} stats-map]
    (send stats-agent merge stats-map))

  (inc-stats-counter! [{:keys [stats-agent]} key]
    (send stats-agent update-in [:frames-counts key] (fnil inc 0)))

  (get-stats [{:keys [stats-agent]}]
    @stats-agent)

  (get-stream-ch [{:keys [streams]} id]
    (get @streams id))

  (get-streams-names [{:keys [streams]}]
    (keys @streams)))


