(ns xhub-team.infrastructure
  (:require [xhub-team.configuration :as conf]
            [next.jdbc :as jdbc]
            [clojure.data.json :as json]
            [taoensso.carmine :as car :refer [wcar]]
            [next.jdbc.sql :as sql]
            [xhub-team.errors :as err]
            [clojure.string :as clj-str]
            [reitit.ring.middleware.parameters :as parameters]
            [org.httpkit.client :as hk-client])
  (:import  [com.zaxxer.hikari HikariDataSource HikariConfig]
            [org.postgresql.util PGobject]
            [java.util UUID]
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

(defn redis->user [token]
  (let [user (wcar* (car/get token))]
    (when (nil? user) (throw (ex-info "not found user by token" err/user-not-auth)))
    user))

(defn add-session [token user]
  (wcar* (car/set token user :ex 1800)))

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
  [^PGobject v]
  (let [type  (.getType v)
        value (.getValue v)]
    (if (#{"jsonb" "json"} type)
      (some-> value (json/read-str :key-fn keyword) (with-meta {:pgtype type}))
      value)))

(defn write-insert-columns [columns]
  (loop [remaining-columns columns
         acc "("]
    (if (= (count remaining-columns) 1)
      (str acc (first remaining-columns) ") ")
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

(defn insert-sql-request
  ([parameters]
   (with-open [conn (jdbc/get-connection datasource)
               stmt (jdbc/prepare conn (into [(build-insert-sql-request parameters)] (:values parameters)))]
     (jdbc/execute! stmt)))
  ([parameters tr]
   (jdbc/execute! tr (into [(build-insert-sql-request parameters)] (:values parameters)))))

(defn insert-transcation-sql [parameters-list]
  (jdbc/with-transaction [tr datasource]
    (mapv #(jdbc/execute! tr (into [(build-insert-sql-request %1)] (:values %1)))
          parameters-list)))

(defn write-select-columns [columns]
  (loop [acc ""
         remaining-columns columns]
    (if (= (count remaining-columns) 1)
      (str acc (first remaining-columns))
      (recur (str acc (first remaining-columns) ",") (rest remaining-columns)))))

(defn build-select-sql-request [parameters]
  (let [columns (write-select-columns (:columns parameters))
        table-name (:table-name parameters)]
    (str "select " columns " from " table-name)))

(defn select-sql-request [parameters]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn [(build-select-sql-request parameters)])]
    (jdbc/execute! stmt)))

(defn generate-tag-filter [tags]
  (if (empty? tags)
    nil
    (str "exists ( select 1 from manga_tag mt where mt.manga_id = m.id and mt.tag_id in ("
         (clojure.string/join "," (repeat (count tags) "?"))
         "))")))

(defn generate-order-by [value]
  (condp = value
    0
    "order by m.created_at asc"
    1
    "order by m.created_at desc"
    nil))

(defn build-filters [filters user-id]
  (let [filter-list (remove nil?
                            [(when (:tags filters) (generate-tag-filter (:tags filters)))
                             (when (:name filters) "m.name ILIKE ?")
                             (when (and (true? (:liked-manga filters)) (not-empty user-id))
                               "m.id in (select manga_id from manga_like where user_id = cast(? as uuid))")
                             (when (:author filters) "m.author_id = cast (? as uuid)")
                             (when (:order-by filters) (generate-order-by (:order-by filters)))])]
    (if (empty? filter-list)
      ""
      (str "where " (clj-str/join " and " filter-list)))))

(defn get-manga-list [filters user-id]
  (let [sql (str "WITH filtered_manga AS (
                    SELECT m.*
                    FROM manga m "
                 (when (or (:name filters) (:tags filters) (:liked-manga filters) (:author filters)) (build-filters filters user-id))
                 (generate-order-by (:order-by filters))
                 " LIMIT ? OFFSET ?)"
                 "SELECT
                    fm.*,
                    (SELECT id FROM manga_page WHERE manga_id = fm.id LIMIT 1) as preview_id,
                    (SELECT COUNT(*) FROM manga_like WHERE manga_id = fm.id) as like_count,
                    EXISTS (SELECT 1 FROM manga_like WHERE manga_id = fm.id) as liked
                  FROM
                    filtered_manga fm;")
        params (remove nil? (vec (concat (:tags filters) [(:name filters)
                                                          (when (and (true? (:liked-manga filters)) (not-empty user-id)) user-id)
                                                          (:author filters)
                                                          (:limit filters)
                                                          (:offset filters)])))]
    (jdbc/execute! datasource (into [sql] params))))

(get-manga-list {:limit 5 :offset 0 :order-by 1} nil)

(defn get-manga-by-id [id]
  (let [uuid (try (UUID/fromString id)
                  (catch Exception e
                    (throw (ex-info (.getMessage e) err/is-not-uuid-error))))]
    (with-open [conn (jdbc/get-connection datasource)
                stmt (jdbc/prepare conn ["select m.id, m.name, m.description, m.created_at, array_agg(mp.id) as pages,
                                         m.manga_group_id, m.author_id,
                                         (select login from \"user\" where id = m.author_id) as author_login,
                                         COALESCE( (
                                            SELECT json_agg(json_build_object('id', m2.id,
                                            'name', m2.name,
                                            'preview_id', (select id from manga_page where manga_id = m2.id limit 1)))
                                            FROM manga_group mg
                                            JOIN manga m2 ON m2.manga_group_id = m.manga_group_id
                                            WHERE mg.id = m.manga_group_id AND m2.id != m.id
                                         ), '[]'::json ) AS manga_group
                                        from manga m
                                        left join manga_page mp on m.id = mp.manga_id
                                        where m.id = ?
                                        group by m.id" uuid])]
      (let [result (jdbc/execute! stmt)
            manga (first result)]
        (when (nil? manga) (throw (ex-info "not found manga in database" err/not_found_manga_by_id_error)))
        {:id (.toString (:manga/id manga))
         :name (:manga/name manga)
         :description (:manga/description manga)
         :manga_group_id (some-> manga :manga/manga_group_id str)
         :created_at (.toString (:manga/created_at manga))
         :manga_group (<-pgobject (:manga_group manga))
         :author_id (str (:manga/author_id manga))
         :author_login (:author_login manga)
         :page_list (->> (.getArray (:pages manga)) (filter some?) (mapv str))}))))

(defn create-manga [name description manga_group_id author-id]
  (->  (insert-sql-request {:table-name "manga"
                            :columns ["id", "name" "description" "manga_group_id" "author_id"]
                            :values [(java.util.UUID/randomUUID)
                                     name
                                     description
                                     (when (not (nil? manga_group_id)) (java.util.UUID/fromString manga_group_id))
                                     (java.util.UUID/fromString author-id)]
                            :return ["id"]})
       first
       :manga/id
       .toString))

(defn add-user [id email password]
  (insert-sql-request {:table-name  "\"user\""
                       :columns ["id" "email" "password"]
                       :values [id email password]}))

(defn find-user [email password]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select u.id, u.email, u.password, u.is_prime, u.created_at from \"user\" u where u.email = ? and u.password = ?" email password])]

    (let [user (first (jdbc/execute! stmt))]
      (when-not user
        (throw (ex-info "User not found in database" err/not_found_user_error)))
      {:id (.toString (:user/id user))
       :email (:user/email user)
       :password (:user/password user)
       :is_prime (:user/is_prime user)
       :created_at (:user/created_at user)})))

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
              stmt (jdbc/prepare conn ["select u.id, u.email, u.password, u.is_prime, u.created_at from \"user\" u where u.id = cast(? as uuid)" id])]
    (jdbc/execute! stmt)))

(defn get-manga-comments [manga-id]
  (with-open [conn (jdbc/get-connection datasource)
              stmt (jdbc/prepare conn ["select c.id, c.manga_id, c.\"content\", c.created_at, u.login, u.id from \"comment\" c
                                       inner join \"user\" u on u.id = c.user_id
                                       where c.manga_id = cast(? as uuid)" manga-id])]
    (map
     (fn [row]
       {:id (:comment/id row)
        :manga_id (:comment/manga_id row)
        :content (:comment/content row)
        :created_at (:comment/created_at row)
        :user_login (:user/login row)
        :user_id (:user/id row)})
     (jdbc/execute! stmt))))

(defn add-manga-comment [manga-id user-id text]
  (insert-sql-request {:table-name "\"comment\""
                       :columns ["id", "manga_id", "user_id" "content"]
                       :values [(java.util.UUID/randomUUID) (java.util.UUID/fromString manga-id) user-id text]}))

(defn generate-in [field count]
  (when (> count 0)
    (loop [acc (str " where " field " in (")
           iter count]
      (if (= iter 1)
        (str acc "?)")
        (recur (str acc "?,") (- iter 1))))))

(defn create-manga-group [name manga-id-list]
  (jdbc/with-transaction [tr datasource]
    (let [return-id (insert-sql-request {:table-name "manga_group"
                                         :columns ["id" "name"]
                                         :values [(java.util.UUID/randomUUID) name]
                                         :return ["id"]} tr)
          id (:manga_group/id (first return-id))]
      (jdbc/execute! tr (into
                         [(str "update manga set manga_group_id = ?" (generate-in "id" (count manga-id-list))) id]
                         (map #(java.util.UUID/fromString %) manga-id-list)))
      (.toString id))))

(defn get-tag-list []
  (mapv
   (fn [row]
     {:id (:tag/id row)
      :name (:tag/name row)})
   (select-sql-request {:table-name "tag"
                        :columns ["id" "name"]})))

(defn check-privileges [token manga-id]
  (let [manga-id->uuid (try (java.util.UUID/fromString manga-id)
                            (catch Exception e (throw (ex-info (.getMessage e) err/is-not-uuid-error))))
        user (wcar* (car/get token))
        author-id (-> (with-open [conn (jdbc/get-connection datasource)
                                  stmt (jdbc/prepare conn ["select author_id from manga where id = ?" manga-id->uuid])]
                        (jdbc/execute! stmt))
                      first
                      :manga/author_id
                      str)]
    (when (nil? user) (throw (ex-info "not found user in storage" err/user-not-auth)))
    (= (:id user) author-id)))

(defn delete-manga [manga-id token]
  (let [{:keys [status headers body error] :as response}
        @(hk-client/delete (str
                            (:manga-stream-url (conf/config :application))
                            "/manga?manga_id=" manga-id
                            "&with_manga=true") {:headers {"Token" token}})]
    (when (or (not (= status 200)) (not (nil? error)))
      (throw (ex-info "manga stream request error" err/request-to-manga-stream-exception)))))
