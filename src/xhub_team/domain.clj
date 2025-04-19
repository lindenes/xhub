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
        (infra/add-session (.toString (java.util.UUID/randomUUID)) email (sha-256 password) token false false false code)
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
          user (list (infra/find-user email hashed_password))
          token (.toString (java.util.UUID/randomUUID))]
      (if (nil? user)
        (throw (ex-info "not found user in database" err/not_found_user_error))
        (do
          (infra/add-session (:user user) (:email user) (:password user) token (:is_author user) (:is_prime user) (:is_admin user))
          {:user user :token token})))
    (let [user  (infra/wcar* (car/get token))]
      (if (nil? user)
        (throw (ex-info "not found user in session store" err/not_found_user_error))
        {:user user :token token}))))

(defn redis->user [token]
  (let [user (infra/wcar* (car/get token))]
    (when (nil? user) (throw (ex-info "not found user by token" err/user-not-auth)))
    user))

(defn db->manga [manga-id]
  (let [manga (infra/get-manga-by-id manga-id)]
    (when (nil? manga) (throw (ex-info "not found manga by id" err/not_found_manga_by_id_error)))
    manga))

(defn like-manga [token manga_id]
  (let [user (redis->user token)
        _ (db->manga manga_id)]
    (infra/add-like (:id user) manga_id)))

(defn add-manga-comment [manga-id token text]
  (let [user (redis->user token)
        _ (db->manga manga-id)]
    (infra/add-manga-comment manga-id (:id user) text)))
