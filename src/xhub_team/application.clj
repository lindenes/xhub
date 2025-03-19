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
    (log/info request)
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
          (assoc-in [:headers "Access-Control-Allow-Methods"] "*")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "*")))))

(defn error->response [error]
  (let [data (ex-data error)
        error-data (:error-data data)]
    (log/error data)
    (let [error-map (cond
                      (contains? data :spec)
                      {:status 400 :body app-errors/request-format-error }

                      :else
                      (condp = (:error_code (first error-data))
                        2
                        {:status 400 :body error-data }

                        3
                        {:status 400 :body error-data}

                        4
                        {:status 400 :body error-data}

                        {:status 500 :body "Unexpected server error"}) )]
         (-> error-map
             (assoc :body (json/write-str (:body error-map)))
             (assoc-in [:headers "Content-Type"] "application/json")))))

(defn error-wrapper [handler]
  (fn [request]
    (try (handler request)
         (catch Exception e (error->response e)) )
    ))


;; Спецификация для одного элемента манги
(s/def ::id string?)
(s/def ::name string?)
(s/def ::description (s/nilable string?))
(s/def ::preview_id (s/nilable string?))
(s/def ::created_at string?)
(s/def ::page_list (s/coll-of string?))
(s/def ::manga (s/keys :req-un [::id ::name ::description ::preview_id]))

;; Спецификация для массива манги
(s/def ::manga_list (s/coll-of ::manga))

(s/def ::full_manga (s/keys :req-un [::id ::name ::description ::created_at ::page_list]))

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

     ["/user"
      {
       :tags [:user]
       :post {:responses {200 {:body nil?}}
              :parameters {:body {:email string? :password string?}}
              :handler (fn [{{{:keys [email password]} :body} :parameters}]
                         (let [token (domain/registration email password)]
                            {:status 200 :headers {"Token" token} }))}}]
     ["/accept"
      {:post {:responses {200 {:body nil}}
              :parameters {:body {:code int?} :headers {:token string?} }
              :handler (fn [req]
                         (let [code (-> req :parameters :body :code)
                               token (get (:headers req) "token" )]
                           (domain/confirm-reg code token)
                           {:status 200})
                         )}}]

     ["/search"
      {:post {:responses {200 {:body ::manga_list}}
              :parameters {:body {:limit pos-int? :offset pos-int?}}
              :handler (fn [{{{:keys [limit offset]} :body} :parameters}]
                         {:status 200
                          :body (map (fn [manga]
                                       {:id (.toString (:manga/id manga))
                                        :name (:manga/name manga)
                                        :description (:manga/description manga)
                                        :preview_id (:manga_page/oid manga)}) (infra/get-manga-list))})}}]
     ["/manga"
      {:tags [:manga]
       :get {:responses {200 {:body ::full_manga}}
             :parameters {:query {:id string?}}
             :handler (fn [{{{:keys [id]} :query} :parameters}]
                        {:status 200
                         :body (let [manga (first (infra/get-manga-by-id (java.util.UUID/fromString id)))]
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

    {:exception pretty/exception
     :data {:coercion reitit.coercion.spec/coercion
            :muuntaja m/instance
            :middleware [error-wrapper
                         cors-middleware
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
