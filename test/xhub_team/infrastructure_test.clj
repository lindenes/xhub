(ns xhub-team.infrastructure-test
  (:require [midje.sweet :refer :all]
            [next.jdbc :as jdbc]
            [xhub-team.infrastructure :as infra]
            [clojure.string :as str])
  (:import [org.testcontainers.containers PostgreSQLContainer]
           [com.zaxxer.hikari HikariDataSource HikariConfig]))

(def pg-container
  (let [container (doto (PostgreSQLContainer. "postgres:16")
                    (.start))]
    {:jdbc-url (.getJdbcUrl container)
     :username (.getUsername container)
     :password (.getPassword container)}))

(def datasource
  (let [config (HikariConfig.)]
    (.setJdbcUrl config (:jdbc-url pg-container))
    (.setUsername config (:username pg-container))
    (.setPassword config (:password pg-container))

    (.setMaximumPoolSize config 10)
    (.setMinimumIdle config 2)
    (.setIdleTimeout config 30000)
    (.setMaxLifetime config 1800000)

    (HikariDataSource. config)))

(def sql-migrate
  (let [sql (slurp "test_resources/migrate.sql")
        sql-commands (str/split sql #";")]
    (doseq [item sql-commands]
      (jdbc/execute! datasource [item]))))

(facts "Тесты комментариев"
       (with-redefs [infra/datasource datasource]
         (do
           (infra/add-manga-comment "3396a57d-38c6-4bc3-ac50-6f8555c915dc" #uuid "a705baad-b3da-437d-8032-92b14afe87f2", "Тестовый коментарий")
           (let [comments (infra/get-manga-comments "3396a57d-38c6-4bc3-ac50-6f8555c915dc")
                 find-comment (some #(= (:comment/content %) "Тестовый коментарий") comments)]
             (fact "Проверка на создание комментария для 3396a57d-38c6-4bc3-ac50-6f8555c915dc"
                   find-comment => true)))
         (let [comment (infra/get-manga-comments "3396a57d-38c6-4bc3-ac50-6f8555c915dc")]
           (fact "Проверка структуры ответа на получение коментария"
                (every? #(contains? (first comment) %) [:comment/id :comment/content :user/id :user/login] ) => true))))
