(ns xhub-team.xhub
  (:require [org.httpkit.server :as hk-server]
            [ring.middleware.params :refer [wrap-params]]
            [xhub-team.configuration :as conf]
            [xhub-team.domain :as domain]
            [xhub-team.infrastructure :as infra])
  (:use xhub-team.application)
  (:gen-class))

(def my-server (hk-server/run-server app (conf/config :application) ))

(defn -main
  [& args]
  (domain/background-task)
  (println "server start with port" (conf/config :application))
  (hk-server/run-server app (conf/config :application)))

(my-server)
