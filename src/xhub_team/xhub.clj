(ns xhub-team.xhub
  (:require [org.httpkit.server :as hk-server]
            [ring.middleware.params :refer [wrap-params]]
            [xhub-team.configuration :as conf]
            [xhub-team.infrastructure :as infra])
  (:use xhub-team.application)
  (:gen-class))

(def my-server (hk-server/run-server app (conf/config :application) ))

(defn -main
  [& args]
  (infra/test-send)
  (println "server start with port" (conf/config :application))
  (hk-server/run-server app (conf/config :application)))

(my-server)
