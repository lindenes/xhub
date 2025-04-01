(ns xhub-team.configuration
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(def config
  (aero/read-config
   (io/resource "application.edn")))

(def config->smtp (:smtp config))

(def config->redis (:redis config))
