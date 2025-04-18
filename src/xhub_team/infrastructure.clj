(ns xhub-team.infrastructure
  (:require [xhub-team.configuration :as conf]
            [next.jdbc :as jdbc]
            [clojure.data.json :as json]
            [taoensso.carmine :as car :refer [wcar]]
            [next.jdbc.sql :as sql])
  (:import  [com.zaxxer.hikari HikariDataSource HikariConfig]
            [org.postgresql.util PGobject]
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

(defn send-verification-code [email code]
  (send-message
   (:email conf/config->smtp)
   email
   "Подтверждение"
   (str "Код подтверждения " code)))

(defonce my-conn-pool (car/connection-pool {}))
(def     my-conn-spec conf/config->redis)
(def     my-wcar-opts {:pool my-conn-pool, :spec my-conn-spec})

(defmacro wcar* [& body] `(car/wcar my-wcar-opts ~@body))

(defn add-session
  ([id email password token is_author is_prime is_admin code]
   (wcar*
    (car/set token {:id id :email email :password password :token token :is_author is_author :is_prime is_prime :is_admin is_admin :code code} :ex 1800)))
  ([id email password token is_author is_prime is_admin] (add-session id email password token is_author is_prime is_admin nil))
  ([id email password token] (add-session id email password token  false false false nil)))

(defn update-session-time [token]
  (let [token->user (wcar* (car/get token))]
    (when token->user
      (wcar* (car/set token token->user :ex 1800)))))


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

(defn <-pgobject
  "Transform PGobject containing `json` or `jsonb` value to Clojure data."
  [v]
  (when (not (nil? v))
    (let [type  (.getType v)
        value (.getValue v)]
    (if (#{"jsonb" "json"} type)
      (some-> value (json/read-str :key-fn keyword) (with-meta {:pgtype type}))
      value))))

(defn write-insert-columns [columns]
  (loop [remaining-columns columns
         acc "("]
    (if (= (count remaining-columns) 1)
      (str acc (first remaining-columns) ") " )
      (recur (rest remaining-columns) (str acc (first remaining-columns) ",")))))

(defn write-values [values-length]
  (loop [acc "values ("
         iter values-length]
    (if (= 1 iter)
      (str acc "?)")
      (recur (str acc "?,") (- iter 1)))))

(defn write-returning [columns]
  (when (not-empty columns)
    (loop [acc "returning "
           remaining-columns columns]
      (if (= 1 (count  remaining-columns))
        (str acc (first remaining-columns))
        (recur (str acc (first remaining-columns) ",") (rest remaining-columns))))))

(defn build-insert-sql-request [parameters]
  (let [init-str (str "insert into " (:table-name parameters) " ")
        columns (write-insert-columns (:columns parameters))
        values (write-values (count (:values parameters)))
        returning (write-returning (:return parameters))]
    (str init-str columns values returning)))

(defn insert-sql-request [parameters]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn (into [(build-insert-sql-request parameters)] (:values parameters)))]
    (jdbc/execute! stmt)))

(defn generate-tag-filter [tags]
  (loop [acc "mg.tag_id in ("
         remaining tags]
    (if (= 1 (count remaining))
      (str acc "?" ")")
      (recur (str acc "?,") (rest remaining)))))

(defn generate-order-by [value]
  (condp = value
    0
    "order by created_at asc"
    nil))

(defn build-filters [filters]
  (let [filter-list (remove nil?
                            [(when (:tags filters) (generate-tag-filter (:tags filters)))
                             (when (:name filters) "manga.name ILIKE ?")
                             (when (:order_by filters) (generate-order-by (:order_by filters)))])]
    (loop [acc "where "
           remaining filter-list]
      (if (= 1 (count remaining))
        (str acc (first remaining))
        (recur (str acc (first remaining) " and ") (rest remaining))))))

(defn get-manga-list [filters]
  (let [sql (str "with manga_list as (SELECT DISTINCT ON(manga.id) manga.id, manga.name, manga.description, mp.id, (select count(*) from manga_like ml where ml.manga_id = manga.id) as like_count
                 FROM manga manga
                 left join manga_page mp ON mp.manga_id = manga.id
                 left join manga_tag mg on mg.manga_id = manga.id "
                 (when (or (:name filters) (:tags filters)) (build-filters filters))
                 ")"
                 " select * from manga_list "
                 (generate-order-by (:order_by filters))
                 "limit ? offset ?")
        params (remove nil? (vec (concat (:tags filters) [(:name filters) (:limit filters) (:offset filters)])))]
    (jdbc/execute! datasource (into [sql] params))))

(defn get-manga-by-id [uuid]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select m.id, m.name, m.description, m.created_at, array_agg(mp.id),
                                         (
                                            SELECT json_agg(json_build_object('id', mc.manga_id, 'name', m2.name, 'preview_id', (select id from manga_page where manga_id = mc.manga_id limit 1)))
                                            FROM manga_connection mc
                                            JOIN manga m2 ON m2.id = mc.manga_id
                                            WHERE mc.connection_id = (
                                              SELECT connection_id
                                              FROM manga_connection
                                              WHERE manga_id = m.id
                                            )
                                            AND mc.manga_id != m.id
                                        ) AS connections
                                        from manga m
                                        left join manga_page mp on m.id = mp.manga_id
                                        where m.id = cast(? as uuid)
                                        group by m.id" uuid])]
    (map #(assoc %1 :connections (<-pgobject (:connections %1))) (jdbc/execute! stmt))))

(defn create-manga [name description]
  (insert-sql-request {:table-name "manga"
                       :columns ["id", "name" "description"]
                       :values [(java.util.UUID/randomUUID) name description]
                       :return ["id"]}))

(defn add-user [id email password]
  (insert-sql-request {:table-name  "\"user\""
                       :columns ["id" "email" "password"]
                       :values [id email password]}))

(defn find-user [email password]
  (first (with-open [conn (jdbc/get-connection datasource)
                     stmt (jdbc/prepare conn ["select u.id, u.email, u.password, u.is_author, u.is_prime, u.created_at from \"user\" u where u.email = ? and u.password = ?" email password])]
           (jdbc/execute! stmt))))

(defn busy-email? [email]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select 1 from \"user\" where email = ? " email])]
    (empty? (jdbc/execute! stmt))))

(defn add-like [user-id manga-id]
  (insert-sql-request {:table-name "manga_like"
                       :columns ["user_id" "manga_id"]
                       :values [user-id (java.util.UUID/fromString manga-id)]}))

(defn get-user [id]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select u.id, u.email, u.password, u.is_author, u.is_prime, u.created_at from \"user\" u where u.id = cast(? as uuid)" id])]
    (jdbc/execute! stmt)))

(defn get-manga-comments [manga-id]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select c.id, c.manga_id, c.\"content\", c.created_at, u.login, u.id from \"comment\" c
                                       inner join \"user\" u on u.id = c.user_id
                                       where c.manga_id = cast(? as uuid)" manga-id])]
    (jdbc/execute! stmt)))

(defn add-manga-comment [manga-id user-id text]
  (insert-sql-request {:table-name "\"comment\""
                       :columns ["id", "manga_id", "user_id" "content"]
                       :values [(java.util.UUID/randomUUID) (java.util.UUID/fromString manga-id) user-id text]}))
