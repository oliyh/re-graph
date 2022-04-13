(ns re-graph.internals-test
  (:require [re-graph.internals :as internals]
            [re-graph.core :as re-graph]
            [day8.re-frame.test :refer [run-test-sync]
             :refer-macros [run-test-sync]]
            [clojure.test :refer [deftest is testing]
             :refer-macros [deftest is testing]]))

(defn- run-options-test
  []
  (run-test-sync

    (testing "WebSocket options"
      (is (nil? (internals/ws-options {:ws nil})))
      (is (nil? (internals/ws-options {:ws {:url nil}})))

      (is (= (merge internals/ws-initial-state internals/ws-default-options) (:ws (internals/ws-options {:ws {}}))))

      (let [test-url "ws://example.org/graphql-ws"
            options  (internals/ws-options {:ws {:url test-url}})]
        (is (= test-url (get-in options [:ws :url])))
        (is (= "graphql-ws" (get-in options [:ws :sub-protocol])))))

    (testing "HTTP options"
      (is (nil? (internals/http-options {:http nil})))
      (is (nil? (internals/http-options {:http {:url nil}})))

      (is (= (merge internals/http-initial-state internals/http-default-options) (:http (internals/http-options {:http {}}))))

      (let [test-url "http://example.org/graphql"
            options  (internals/http-options {:http {:url test-url}})]
        (is (= test-url (get-in options [:http :url])))))))

(deftest options-test
  (run-options-test))

(deftest instance-interceptor-test
  (with-redefs [internals/generate-query-id (constantly "<uuid>")]
    (let [instance (fn [re-graph event]
                     (-> ((:before internals/re-graph-instance) {:coeffects {:db {:re-graph re-graph}
                                                                             :event event
                                                                             :original-event (into [::re-graph/query] event)}})
                         :coeffects
                         (dissoc :db :original-event)))]

      (testing "does nothing when re-graph not initialised"
        (is (= {:event ["{query}" {} [::callback]]}
               (instance nil
                         ["{query}" {} [::callback]]))))

      (testing "does nothing when re-graph is destroyed"
        (is (= {:event ["{query}" {} [::callback]]}
               (instance {internals/default-instance-name {:destroyed? true}}
                         ["{query}" {} [::callback]]))))

      (testing "selects the default instance when no instance name provided"
        (is (= {:event ["<uuid>" "{query}" {} [::callback]]
                :instance-name internals/default-instance-name
                :dispatchable-event [::re-graph/query internals/default-instance-name "<uuid>" "{query}" {} [::callback]]}
               (instance {internals/default-instance-name ::instance}
                         ["{query}" {} [::callback]]))))

      (testing "named instances"
        (testing "selects the named instance when instance name provided"
          (is (= {:event ["<uuid>" "{query}" {} [::callback]]
                  :instance-name ::my-instance
                  :dispatchable-event [::re-graph/query ::my-instance "<uuid>" "{query}" {} [::callback]]}
                 (instance {internals/default-instance-name ::instance
                            ::my-instance ::my-instance}
                           [::my-instance "{query}" {} [::callback]]))))

        (testing "does nothing when instance name provided but does not exist"
          ;; todo improve this using better destructuring
          ;; this should fail because ::my-instance does not exist
          ;; but instead the default instance is selected
          #_(is (= {:event ["<uuid>" "{query}" {} [::callback]]
                  :instance-name ::my-instance
                  :dispatchable-event [::re-graph/query ::my-instance "<uuid>" "{query}" {} [::callback]]}
                 (instance {internals/default-instance-name ::instance}
                           [::my-instance "{query}" {} [::callback]]))))))))
