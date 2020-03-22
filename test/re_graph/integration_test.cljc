(ns re-graph.integration-test
  (:require [re-graph.core :as re-graph]
            #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :refer [run-test-sync run-test-async wait-for]
             :refer-macros [run-test-sync]]
            #?(:clj [re-graph.integration-server :refer [start! stop!]])))

#?(:clj
   (defn- with-server [f]
     (start!)
     (try (f)
          (finally (stop!)))))

#?(:clj (use-fixtures :once with-server))

;; todo can we reuse these tests in cljs?

#?(:clj
   (deftest http-test
     (run-test-sync
      (re-graph/init {:ws-url nil
                      :http-url "http://localhost:8888/graphql"})

      (testing "async query"
        (let [response (promise)]
          (re-graph/query "{ pets { id name } }" {} #(deliver response %))

          (is (= {:data
                  {:pets
                   [{:id "123", :name "Billy"}
                    {:id "234", :name "Bob"}
                    {:id "345", :name "Beatrice"}]}}
                 (deref response 1000 ::timeout)))))

      (testing "sync query"
        (is (= {:data
                {:pets
                 [{:id "123", :name "Billy"}
                  {:id "234", :name "Bob"}
                  {:id "345", :name "Beatrice"}]}}
               (re-graph/query-sync "{ pets { id name } }" {}))))

      (testing "async mutate"
        (let [response (promise)]
          (re-graph/mutate "mutation { createPet(name: \"Zorro\") { id name } }" {} #(deliver response %))

          (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
                 (deref response 1000 ::timeout)))))

      (testing "sync mutate"
        (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
               (re-graph/mutate-sync "mutation { createPet(name: \"Zorro\") { id name } }" {}))))

      (testing "error handling"
        (is (= {:errors
                [{:message "Cannot query field `malformed' on type `QueryRoot'.",
                  :locations [{:line 1, :column 9}],
                  :extensions {:type "QueryRoot"
                               :field "malformed"
                               :status 400}}]}
               (re-graph/query-sync "{ malformed }" {}))))

      (testing "instances, query ids, etc"
        ;; todo
        )

      (testing "http parameters"
        ;; todo
        ))))

#?(:clj
   (deftest websocket-test
     (run-test-sync
      (re-graph/init {:ws-url "ws://localhost:8888/graphql-ws"
                      :http-url nil})

      (testing "subscriptions"
        (let [responses (atom [])
              responded? (promise)]
          (re-graph/subscribe :all-pets "MyPets($count: Int) { pets(count: $count) { id name } }" {:count 5}
                              (fn [response]
                                (when (<= 5 (count (swap! responses conj response)))
                                  (deliver responded? true))))

          (is (deref responded? 5000 ::timed-out))
          (is (every? #(= {:data
                           {:pets
                            [{:id "123", :name "Billy"}
                             {:id "234", :name "Bob"}
                             {:id "345", :name "Beatrice"}]}}
                          %)
                      @responses))
          (is (= 5 (count @responses)))

          (re-graph/unsubscribe :all-pets)))

      (testing "mutations"
        (testing "async mutate"
          (let [response (promise)]
            (re-graph/mutate "mutation { createPet(name: \"Zorro\") { id name } }" {} #(deliver response %))

            (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
                   (deref response 1000 ::timeout)))))))))
