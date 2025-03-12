(ns xhub-team.application
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.openapi :as openapi]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.dev.pretty :as pretty]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [muuntaja.core :as m]
            [clojure.spec.alpha :as s]
            [xhub-team.infrastructure :as infra]))

;; Спецификация для одного элемента манги
(s/def ::id string?)
(s/def ::name string?)
(s/def ::description (s/nilable string?))
(s/def ::created-at string?)
(s/def ::page-list (s/coll-of string?))
(s/def ::manga (s/keys :req-un [::id ::name ::description]))

;; Спецификация для массива манги
(s/def ::manga-list (s/coll-of ::manga))

(s/def ::full-manga (s/keys :req-un [::id ::name ::description ::created-at ::page-list]))

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

       ["/math"
        {:tags ["math"]}

        ["/plus"
         {:get {:summary "plus with spec query parameters"
                :parameters {:query {:x int? :y int?} }
                :responses {200 {:body {:total int?} }}
                :handler (fn [{{{:keys [x y]} :query} :parameters}]
                           {:status 200
                            :body {:total (+ x y)}})}
          :post {:summary "plus with spec body parameters"
                 :parameters {:body {:x int? :y int?} } ;
                 :responses {200 {:body {:total int?} }}
                 :handler (fn [{{{:keys [x y]} :body} :parameters}]
                            {:status 200
                             :body {:total (+ x y)}})}}]]
       ["/search"
        {:get {:responses {200 {:body ::manga-list }}
               :handler (fn [_] {:status 200
                                 :body (map (fn [manga]
                                              {:id (.toString (:manga/id manga))
                                               :name (:manga/name manga)
                                               :description (:manga/description manga)}) (infra/get-manga-list))})}}]
       ["/manga"
       {:get {:responses {200 {:body ::full-manga}}
              :parameters {:query {:id string?}}
              :handler (fn [{{{:keys [id]} :query} :parameters}]
                        {:status 200
                         :body (let [manga (first (infra/get-manga-by-id (java.util.UUID/fromString id)))]
                                 {:id (.toString (:manga/id manga))
                                  :name (:manga/name manga)
                                  :description (:manga/description manga)
                                  :created-at (.toString (:manga/created_at manga))
                                  :page-list (vec (.getArray (:array_agg manga)))}) })}
        :post {:responses {200 {:body {:id string?}}}
               :parameters {:body {:name string? :description (s/nilable string?) }}
               :handler (fn [{{{:keys [name description]} :body} :parameters}]
                          (println name description)
                          (let [id (java.util.UUID/randomUUID)
                                db-id (-> (infra/create-manga id name description)
                                          first
                                          :manga/id
                                          .toString)]
                             {:status 200
                             :body {:id db-id} })) }}]]

      {
       :exception pretty/exception
       :data {:coercion reitit.coercion.spec/coercion
              :muuntaja m/instance
              :middleware [
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
