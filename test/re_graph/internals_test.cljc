(ns re-graph.internals-test
  (:require [re-graph.internals :as internals]
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
