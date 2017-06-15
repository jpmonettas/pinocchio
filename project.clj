(defproject pinocchio "0.1.0"
  :description "The ghost in the wooden shell"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha16"]
                 [ring "1.6.1"]
                 [com.stuartsierra/component "0.3.2"]
                 [http-kit "2.2.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [opencv/opencv "3.1.0"]
                 [com.taoensso/sente "1.11.0"]
                 [org.clojure/core.async "0.3.443"]
                 [cider/cider-nrepl "0.14.0"]
                 [compojure "1.6.0"]
                 [org.clojure/data.json "0.2.6"]]
  :main ^:skip-aot pinocchio.main
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev/src" "src"]}
             :provided {:dependencies [[opencv/opencv-native "3.1.0"]]}})
