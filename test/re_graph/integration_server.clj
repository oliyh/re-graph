(ns re-graph.integration-server
  (:require [io.pedestal.http :as server]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]))

(defn- resolve-pets [_context _args _parent]
  [{:id 123 :name "Billy"}
   {:id 234 :name "Bob"}
   {:id 345 :name "Beatrice"}])

(defn- create-pet [_context args _parent]
  (assoc args :id 999))

(defn- stream-pets [_context args source-stream]
  (let [{:keys [count]} args
        runnable ^Runnable (fn []
                             (dotimes [i count]
                               (source-stream {:i i})))
        streamer (Thread. runnable "stream-pets-thread")]
    (.start streamer)
    #(.stop streamer)))

(defn compile-schema []
  (schema/compile
   {:objects {:Pet {:fields {:id {:type 'String}
                             :name {:type 'String}}}}
    :queries {:pets
              {:type '(list :Pet)
               :resolve resolve-pets}}

    :mutations {:createPet {:type :Pet
                            :args {:name {:type '(non-null String)}}
                            :resolve create-pet}}

    :subscriptions
    {:pets
     {:type '(list :Pet)
      :stream stream-pets
      :resolve resolve-pets
      :args {:count {:type 'Int :default 5}}}}}))

(def lacinia-opts {:graphiql true
                   :subscriptions true})

(def service
  (-> (lacinia/service-map (fn [] (compile-schema)) lacinia-opts)
      (assoc ::server/allowed-origins {:creds true
                                       :allowed-origins (constantly true)})))

(def runnable-service (server/create-server service))

(defn start! []
  (server/start runnable-service))

(defn stop! []
  (server/stop runnable-service))

(defn with-server [f]
  (start!)
  (try (f)
       (finally (stop!))))
