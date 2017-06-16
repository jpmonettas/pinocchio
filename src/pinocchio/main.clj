(ns pinocchio.main
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure
             [edn :as edn]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.nrepl.server :as nrepl]
            [com.stuartsierra.component :as comp]
            [pinocchio.components
             [devices-drivers :as dd-cmp]
             [monitor :as monitor-cmp]] 
            [taoensso.timbre :as l])
  (:gen-class))

(clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)

(def system-config nil)
(def server-system nil)
(def nrepl-server nil)

(defn start-nrepl-server []
  (alter-var-root #'nrepl-server
                  (fn [s] (nrepl/start-server :handler cider-nrepl-handler
                                              :port (:nrepl-port system-config)))))

(defn load-config [file]
  (let [env (str/lower-case (or  (System/getenv "ENV") "dev"))
        config-file (or file
                        (io/resource (str "configs/" env ".clj")))]
    (println "Reading config file " config-file)
    (alter-var-root #'system-config
                    (fn [v f] (edn/read-string (slurp f)))
                    config-file)))


(defn create-server-system [system-config]
  (require [(-> system-config :robot-ns)])
  (let [map->RobotCmp (ns-resolve (find-ns (-> system-config :robot-ns)) 'map->RobotCmp)]
   (comp/system-map
    :monitor-cmp (monitor-cmp/map->MonitorCmp {:system-config system-config})
    :devices-drivers-cmp (dd-cmp/create-devices-drivers system-config
                                                        (:devices-drivers system-config))
    :robot-cmp (comp/using (map->RobotCmp {})
                           [:monitor-cmp :devices-drivers-cmp]))))


(defn start-system [config-file]
  (Thread/setDefaultUncaughtExceptionHandler
     (reify
       Thread$UncaughtExceptionHandler
       (uncaughtException [this thread throwable]
         (l/info throwable)
         (l/error (format "Uncaught exception %s on thread %s" throwable thread)))))
  (load-config config-file)
  (l/info "Starting server system")
  (alter-var-root #'server-system (fn [s] (comp/start (create-server-system  system-config))))
  )

(defn stop-system []
  (when server-system
    (l/info "Stopping server system")
    (alter-var-root #'server-system
                    (fn [s] (when s (comp/stop s))))))


(defn -main
  [& args]
  (let [config-file (first args)]

    (start-system config-file)
    (start-nrepl-server)
    (println "Ready")))



;; java -cp "pinocchio-0.1.0-standalone.jar:/home/pi/opencv/opencv-3.1.0/build/bin/opencv-310.jar" -Djava.library.path=/home/pi/opencv/opencv-3.1.0/build/lib/ pinocchio.main
;; java -cp "./:/home/pi/opencv/opencv-3.1.0/build/bin/opencv-310.jar" -Djava.library.path=/home/pi/opencv/opencv-3.1.0/build/lib/ Test
