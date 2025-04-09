(ns xhub-team.application
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.openapi :as openapi]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [clojure.tools.logging :as log]
            [reitit.dev.pretty :as pretty]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [muuntaja.core :as m]
            [clojure.spec.alpha :as s]
            [xhub-team.infrastructure :as infra]
            [xhub-team.errors :as app-errors]
            [clojure.data.json :as json]
            [xhub-team.errors :as err]
            [xhub-team.domain :as domain]))

(defn cors-middleware [handler]
  (fn [request]
    (log/info "-----------------------------------------")
    (log/info (:uri request) (:request-method request) (:headers request))
    (log/debug (:uri request) (:request-method request) (:headers request) (slurp (:body request)))
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
          (assoc-in [:headers "Access-Control-Allow-Methods"] "*")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "*")
          (assoc-in [:headers "Access-Control-Expose-Headers"] "token")))))

(defn error->response [error]
  (let [data (ex-data error)
        error-data (:error-data data)]
    (log/error data)
    (let [error-map (cond
                      (contains? data :spec)
                      {:status 400 :body app-errors/request-format-error}

                      :else
                      (condp = (:error_code (first error-data))
                        2
                        {:status 400 :body error-data}

                        3
                        {:status 400 :body error-data}

                        4
                        {:status 400 :body error-data}

                        5
                        {:status 404 :body error-data}

                        6
                        {:status 404 :body error-data}

                        7
                        {:status 401 :body error-data}

                        8
                        {:status 400 :body error-data}

                        {:status 500 :body "Unexpected server error"}))]
      (-> error-map
          (assoc :body (json/write-str (:body error-map)))
          (assoc-in [:headers "Content-Type"] "application/json")))))

(defn error-wrapper [handler]
  (fn [request]
    (try (handler request)
         (catch Exception e (error->response e)))))

(defn token-wrapper [handler]
  (fn [request]
    (let [token (get (:headers request) "token")]
      (do
        (when token (infra/update-session-time token))
        (handler request)))))

;; Спецификация для одного элемента манги
(s/def ::id string?)
(s/def ::name string?)
(s/def ::description (s/nilable string?))
(s/def ::preview_id (s/nilable string?))
(s/def ::created_at string?)
(s/def ::page_list (s/coll-of string?))
(s/def ::like_count int?)
(s/def ::manga (s/keys :req-un [::id ::name ::description ::preview_id ::like_count]))

;; Спецификация для массива манги
(s/def ::manga_list (s/coll-of ::manga))

(s/def ::full_manga (s/keys :req-un [::id ::name ::description ::created_at ::page_list]))

(s/def ::limit nat-int?)
(s/def ::offset nat-int?)
(s/def ::name (s/nilable string?))
(s/def ::order_by (s/nilable int?))
(s/def ::tags (s/nilable (s/coll-of int?)))

(s/def ::email (s/nilable string?))
(s/def ::password (s/nilable string?))
(s/def ::token (s/nilable string?))

(def app
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "my-api"}}
             :handler (swagger/create-swagger-handler)}}]
     ["/openapi.json"
      {:get {:no-doc true
             :openapi {:info {:title "my-api"
                              :description "openapi3-docs with reitit-http"
                              :version "0.0.1"}}
             :handler (openapi/create-openapi-handler)}}]
     ["/like"
      {:post {:responses {200 {:body nil?}}
              :parameters {:body {:manga_id string?} :headers {:token string?}}
              :handler (fn [req]
                         (let [manga-id (-> req :parameters :body :manga_id)
                               token (get (:headers req) "token")]
                           (domain/like-manga token manga-id)
                           {:status 200}))}}]
     ["/user"
      {:tags [:user]
       :post {:responses {200 {:body nil?}}
              :parameters {:body {:email string? :password string?}}
              :handler (fn [{{{:keys [email password]} :body} :parameters}]
                         (let [token (domain/registration email password)]
                           {:status 200 :headers {"Token" token}}))}}]
     ["/accept"
      {:post {:responses {200 {:body nil}}
              :parameters {:body {:code int?} :headers {:token string?}}
              :handler (fn [req]
                         (let [code (-> req :parameters :body :code)
                               token (get (:headers req) "token")]
                           (domain/confirm-reg code token)
                           {:status 200}))}}]
     ["/auth"
      {:post {:responses {200 {:body {:email string? :is_author boolean? :is_prime boolean?}}}
              :parameters {:body (s/nilable (s/keys :opt-un [::email ::password])) :headers (s/keys :opt-un [::token])}
              :handler (fn [req]
                         (let [body (-> req :parameters :body)
                               header_token  (get (:headers req) "token")
                               user_data (domain/authorization (:email body) (:password body) header_token)
                               user (:user user_data)
                               token (if (nil? (:token user_data))
                                       header_token
                                       (:token user_data))]
                           {:status 200
                            :body {:email (:user/email user) :is_author (:user/is_author user) :is_prime (:user/is_prime user)}
                            :headers {"Token" token}}))}}]

     ["/search"
      {:post {:responses {200 {:body ::manga_list}}
              :parameters {:body (s/keys :req-un [::limit
                                                  ::offset]
                                         :opt-un [::name
                                                  ::order_by
                                                  ::tags])}
              :handler (fn [{{{:keys [limit offset name order-by tags]} :body} :parameters}]
                         {:status 200
                          :body (map (fn [manga]
                                       {:id (.toString (:manga/id manga))
                                        :name (:manga/name manga)
                                        :description (:manga/description manga)
                                        :preview_id (when (:manga_page/id manga) (.toString (:manga_page/id manga)))
                                        :like_count (:like_count manga)})
                                     (infra/get-manga-list
                                      {:limit limit
                                       :offset offset
                                       :name (when name (str "%" name "%"))
                                       :order-by order-by
                                       :tags tags}))})}}]
     ["/manga"
      {:tags [:manga]
       :get {:responses {200 {:body ::full_manga}}
             :parameters {:query {:id string?}}
             :handler (fn [{{{:keys [id]} :query} :parameters}]
                        {:status 200
                         :body (let [manga (first (infra/get-manga-by-id id))]
                                 {:id (.toString (:manga/id manga))
                                  :name (:manga/name manga)
                                  :description (:manga/description manga)
                                  :created_at (.toString (:manga/created_at manga))
                                  :page_list (vec (filter some? (.getArray (:array_agg manga))))})})}

       :post {:responses {200 {:body {:id string?}}}
              :parameters {:body {:name string? :description (s/nilable string?)}}
              :handler (fn [{{{:keys [name description]} :body} :parameters}]
                         (let [id (java.util.UUID/randomUUID)
                               db-id (-> (infra/create-manga id name description)
                                         first
                                         :manga/id
                                         .toString)]
                           {:status 200
                            :body {:id db-id}}))}}]]

    {:data {:coercion reitit.coercion.spec/coercion
            :muuntaja m/instance
            :middleware [cors-middleware
                         error-wrapper
                         token-wrapper
                         swagger/swagger-feature
                         parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         muuntaja/format-request-middleware
                         coercion/coerce-response-middleware
                         coercion/coerce-request-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/"
      :config {:validatorUrl nil
               :urls [{:name "swagger" :url "swagger.json"}
                      {:name "openapi" :url "openapi.json"}]
               :urls.primaryName "openapi"
               :operationsSorter "alpha"}})
    (ring/create-default-handler))))
