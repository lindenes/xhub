(ns xhub-team.domain
  (:require [xhub-team.errors :as err]
            [clojure.tools.logging :as log]
            [xhub-team.infrastructure :as infra])
  (:import (java.security MessageDigest)))

(def sessions (atom [] ))

(defn add-session
  ([id email password token code]
  (swap!
   sessions
   (fn [sessions]
     (log/info sessions)
     (cons
      {:id id :email email :password password :token token :code code :timer (System/currentTimeMillis)}
      sessions))))
  ([id email password token] (add-session id email password token nil)))


(defn valid-email? [email]
  (boolean (re-matches #"^[A-Za-z0-9+_.-]+@(.+)$" email)))

(defn valid-password? [password]
  (boolean (re-matches #"^(?=.*[0-9])(?=.*[a-zA-Z]).{8,}$" password)))

(defn sha-256 [input]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes input "UTF-8"))
    (let [digest (.digest md)]
      (apply str (map #(format "%02x" %) digest)))))

(defn registration [email password]
  (let [validation-errors (filterv (comp not :is-valid) [{:is-valid (valid-email? email) :error err/email-validate-error}
                           {:is-valid (valid-password? password) :error err/password-validate-error}])]
    (println validation-errors)
    (if (empty? validation-errors)
        (let [code (rand-nth (range 1000 10000))
              token (.toString (java.util.UUID/randomUUID)) ]
          (add-session (.toString (java.util.UUID/randomUUID))  email (sha-256 password) token code)
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

(defn confirm-reg [code token]
  (println code token @sessions)
  (let [user (first (filter (fn [x] (= (:token x) token ))  @sessions) ) ]
    (println user)
    (if (= (:code user) code)

      (infra/add-user (:id user)  (:email user) (:password user))

      (throw (ex-info "accept code error" err/accept-code-error ))))
  )
