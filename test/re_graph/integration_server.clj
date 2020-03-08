(ns re-graph.integration-server
  (:require [io.pedestal.http :as server]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]))

(defn- resolve-pets [context args parent]
  [{:id 123 :name "Billy"}
   {:id 234 :name "Bob"}
   {:id 345 :name "Beatrice"}])

(defn- create-pet [context args parent]
  (assoc args :id 999))

(defn compile-schema []
  (schema/compile
   {:objects {:Pet {:fields {:id {:type 'String}
                             :name {:type 'String}}}}
    :queries {:pets
              {:type '(list :Pet)
               :resolve resolve-pets}}

    :mutations {:createPet {:type :Pet
                            :args {:name {:type '(non-null String)}}
                            :resolve create-pet}}}))

(def lacinia-opts {:graphiql true})

(def service (lacinia/service-map (fn [] (compile-schema)) lacinia-opts))

(def runnable-service (server/create-server service))

(defn start! []
  (server/start runnable-service))

(defn stop! []
  (server/stop runnable-service))
