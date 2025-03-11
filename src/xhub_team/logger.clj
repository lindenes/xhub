(ns xhub-team.logger
  (:import [org.apache.logging.log4j LogManager]))

(def logger (LogManager/getLogger "http-kit-logger"))

(defn log-request [req]
  (.info logger (str "Request: " (:uri req) (:request-method req))))
