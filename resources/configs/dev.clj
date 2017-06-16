{:nrepl-port 1234
 :monitor-http-port 8181
 :i2c-enable? true
 :devices-drivers {:cameras {
                             ;; :laptop-cam 0
                             :camera1 0 ;; "http://192.168.1.9:8080/video?hack=hack.mjpg"
                             }
                   :motors {:main-motor    2r00000001
                            :turning-motor 2r00000010}}
 :robot-ns pinocchio.components.robots.simple-tracker}

