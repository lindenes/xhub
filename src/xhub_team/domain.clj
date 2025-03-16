(ns xhub-team.domain
  (:require [xhub-team.errors :as err]
            [clojure.tools.logging :as log]
            [xhub-team.infrastructure :as infra]))

(def sessions (atom [] ))

(defn add-session
  ([email password token code]
  (swap!
   sessions
   (fn [sessions]
     (log/info sessions)
     (cons
      {:email email :password password :token token :code code :timer (System/currentTimeMillis)}
      sessions))))
  ([email password token] (add-session email password token nil)))


(defn valid-email? [email]
  (boolean (re-matches #"^[A-Za-z0-9+_.-]+@(.+)$" email)))

(defn valid-password? [password]
  (boolean (re-matches #"^(?=.*[0-9])(?=.*[a-zA-Z]).{8,}$" password)))

(defn registration [email password]
  (let [validation-errors (filterv (comp not :is-valid) [{:is-valid (valid-email? email) :error err/email-validate-error}
                           {:is-valid (valid-password? password) :error err/password-validate-error}])]
    (println validation-errors)
    (if (empty? validation-errors)
        (let [code (rand-int 9999)
              token (.toString (java.util.UUID/randomUUID)) ]
          (add-session email password token code)
          (infra/send-verification-code email code)
          token
          )
        (throw (ex-info
                "registration validation errors"
                (err/error->aggregate (into [] (map (fn [e] (get e :error)) validation-errors)))))
      )
    )
  )

(defn background-task []
  (future
    (loop []
      (log/info "Start clear sessions")
      (log/info sessions)
      (swap!
       sessions
       (fn [sessions]
         (filter
          (fn [session] (> (- (System/currentTimeMillis) (:timer session) ) 1800000) )
          sessions)
         ))
      (log/info sessions)
      (Thread/sleep (* 30 60 1000))
      (recur))))
