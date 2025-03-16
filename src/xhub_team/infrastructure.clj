(ns xhub-team.infrastructure
  (:require [xhub-team.configuration :as conf]
             [next.jdbc :as jdbc]
            [pg.core :as pg])
  (:import  [com.zaxxer.hikari HikariDataSource HikariConfig]
            [jakarta.mail Session Message Transport Message$RecipientType]
            [jakarta.mail.internet InternetAddress MimeMessage]))

(def session
  (let [props (doto (java.util.Properties.)
                (.put "mail.smtp.host" (:host conf/config->smtp))
                (.put "mail.smtp.auth" (:auth-enable conf/config->smtp))
                (.put "mail.smtp.starttls.enable" (:tls-enable conf/config->smtp)))
        session  (Session/getInstance props
                                      (proxy [jakarta.mail.Authenticator] []
                                        (getPasswordAuthentication []
                                          (jakarta.mail.PasswordAuthentication.
                                           (:email conf/config->smtp)
                                           (:password conf/config->smtp)))))]
    session))

(defn send-message [from to subject body]
  (let [message (MimeMessage. session)]
    (.setFrom message (InternetAddress. from))
    (.setRecipient message Message$RecipientType/TO (InternetAddress. to))
    (.setSubject message subject)
    (.setText message body)
    (Transport/send message)))

;; Пример использования
(defn test-send [] (send-message "info@hentai-hub.online"
            "dodigrams@gmail.com"
            "Test Subject"
            "This is a test email."))

(defn send-verification-code [email code]
  (send-message
   (:email conf/config->smtp)
   email
   "Подтверждение"
   (str "Код подтверждения " code)))

(def datasource
  (let [config (HikariConfig.)
        database-config (:database conf/config)]
    (.setJdbcUrl config (:url database-config))
    (.setUsername config (:user database-config))
    (.setPassword config (:password database-config))

    (.setMaximumPoolSize config 10)
    (.setMinimumIdle config 2)
    (.setIdleTimeout config 30000)
    (.setMaxLifetime config 1800000)

    (HikariDataSource. config)))


(defn get-manga-list []
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select distinct on(m.id) m.id, m.name, m.description, mp.oid
                                        from manga m left join manga_page mp on mp.manga_id = m.id"])]
    (jdbc/execute! stmt))
  )

(defn get-manga-by-id [^java.util.UUID uuid]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select m.id, m.name, m.description, m.created_at, array_agg(mp.oid) from manga m
                                       left join manga_page mp on m.id = mp.manga_id
                                        where m.id = ?
                                        group by m.id, m.name, m.description, m.created_at" uuid])]
    (jdbc/execute! stmt)))

(defn create-manga [^java.util.UUID uuid name description]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["insert into manga (id,name,description) values (?, ?, ?) returning id" uuid name description])]
    (jdbc/execute! stmt))
  )

(defn add-user [email password]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["insert into user (email, password) values (?, ?)" email password])]
    (jdbc/execute! stmt)))
