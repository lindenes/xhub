(ns xhub-team.domain
  (:require [xhub-team.errors :as err]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer [wcar]]
            [xhub-team.infrastructure :as infra]
            [xhub-team.configuration :as conf])
  (:import (java.security MessageDigest)))

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
                                                         {:is-valid (valid-password? password) :error err/password-validate-error}
                                                         {:is-valid (infra/busy-email? email) :error err/busy-email-error}])]
    (if (empty? validation-errors)
      (let [code (rand-nth (range 1000 10000))
            token (.toString (java.util.UUID/randomUUID))]
        (infra/add-session token
                           {:id (.toString (java.util.UUID/randomUUID))
                            :email email
                            :password (sha-256 password)
                            :code code})
        (infra/send-verification-code email code)
        token)
      (throw (ex-info
              "registration validation errors"
              (err/error->aggregate (into [] (map (fn [e] (get e :error)) validation-errors))))))))

(defn confirm-reg [code token]
  (let [user (infra/wcar* (car/get token))]
    (if (= (:code user) code)
      (infra/add-user (:id user)  (:email user) (:password user))
      (throw (ex-info "accept code error" err/accept-code-error)))))

(defn authorization [email password token]
  (if (nil? token)
    (let [hashed_password (sha-256 password)
          user (infra/find-user email hashed_password)
          token (.toString (java.util.UUID/randomUUID))]
      (infra/add-session token user)
      {:user user :token token})
    {:user (infra/redis->user token) :token token}))

(defn like-manga [token manga_id]
  (let [user (infra/redis->user token)
        _ (infra/get-manga-by-id manga_id)]
    (infra/add-like (:id user) manga_id)))

(defn add-manga-comment [manga-id token text]
  (let [user (infra/redis->user token)
        _ (infra/get-manga-by-id manga-id)]
    (infra/add-manga-comment manga-id (:id user) text)))

(defn create-manga [name description manga-group-id token]
  (let [user (infra/redis->user token)]
    (infra/create-manga name description manga-group-id (:id user))))
