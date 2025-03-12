(ns xhub-team.infrastructure
  (:require [xhub-team.configuration :as conf]
             [next.jdbc :as jdbc]
            [pg.core :as pg])
  (:import  [com.zaxxer.hikari HikariDataSource HikariConfig]))

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
              stmt (jdbc/prepare conn ["select * from manga"])]
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
