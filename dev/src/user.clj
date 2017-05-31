(ns user
  (:require [pinocchio.main :as main]
            [clojure.tools.namespace.repl :refer [refresh]]))


(defn start-system! []
  (main/start-system nil))

(defn stop-system! []
  (main/stop-system))

(defn restart! []
  (stop-system!)
  (refresh :after 'user/start-system!))


