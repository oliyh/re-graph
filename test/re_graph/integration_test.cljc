(ns re-graph.integration-test
  (:require [re-graph.core :as re-graph]
            [re-graph.internals :as internals]
            #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [day8.re-frame.test :refer [run-test-async wait-for #?(:clj with-temp-re-frame-state)]]
            [re-frame.core :as re-frame]
            [re-frame.db :as rfdb]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            #?(:clj [re-graph.integration-server :refer [with-server]])))

(stest/instrument)
(s/check-asserts true)

#?(:clj (use-fixtures :once with-server))

(defn register-callback! []
  (re-frame/reg-event-db
   ::callback
   (fn [db [_ response]]
     (assoc db ::response response))))

(deftest async-http-query-test
  (run-test-async
   (re-graph/init {:ws nil
                   :http {:url "http://localhost:8888/graphql"}})
   (register-callback!)

   (testing "async query"
     (re-graph/query {:query "{ pets { id name } }"
                      :variables {}
                      :callback #(re-frame/dispatch [::callback %])})

     (wait-for
      [::callback]

      (is (= {:data
              {:pets
               [{:id "123", :name "Billy"}
                {:id "234", :name "Bob"}
                {:id "345", :name "Beatrice"}]}}
             (::response @rfdb/app-db)))

      (testing "instances, query ids, etc"
        ;; todo
        )

      (testing "http parameters"
        ;; todo
        )))))

(deftest async-http-mutate-test
  (run-test-async
    (re-graph/init {:ws nil
                    :http {:url "http://localhost:8888/graphql"}})
    (register-callback!)

    (testing "async mutate"
      (re-graph/mutate {:query "mutation { createPet(name: \"Zorro\") { id name } }"
                        :variables {}
                        :callback #(re-frame/dispatch [::callback %])})

      (wait-for
       [::callback]
       (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
              (::response @rfdb/app-db)))))))

#?(:clj
   (deftest sync-http-test
     (with-temp-re-frame-state
       (re-graph/init {:ws nil
                       :http {:url "http://localhost:8888/graphql"}})

       (testing "sync query"
         (is (= {:data
                 {:pets
                  [{:id "123", :name "Billy"}
                   {:id "234", :name "Bob"}
                   {:id "345", :name "Beatrice"}]}}
                (re-graph/query-sync {:query "{ pets { id name } }"
                                      :variables {}}))))

       (testing "sync mutate"
         (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
                (re-graph/mutate-sync {:query "mutation { createPet(name: \"Zorro\") { id name } }"
                                       :variables {}}))))

       (testing "error handling"
         (is (= {:errors
                 [{:message "Cannot query field `malformed' on type `Query'.",
                   :locations [{:line 1, :column 9}],
                   :extensions {:type-name "Query"
                                :field-name "malformed"
                                :status 400}}]}
                (re-graph/query-sync {:query "{ malformed }"
                                      :variables {}})))))))

(deftest websocket-query-test
   (run-test-async
    (re-graph/init {:ws {:url "ws://localhost:8888/graphql-ws"}
                    :http nil})

    (wait-for [::re-graph/init]

              (re-frame/reg-event-db
               ::complete
               (fn [db _]
                 db))

              (re-frame/reg-event-fx
               ::callback
               [re-frame/unwrap]
               (fn [{:keys [db]} {:keys [response]}]
                 (let [new-db (update db ::responses conj response)]
                   (merge
                    {:db new-db}
                    (when (<= 5 (count (::responses new-db)))
                      {:dispatch [::complete]})))))

              (testing "subscriptions"
                (re-graph/subscribe {:id :all-pets
                                     :query "MyPets($count: Int) { pets(count: $count) { id name } }"
                                     :variables {:count 5}
                                     :callback #(re-frame/dispatch [::callback {:response %}])})

                (wait-for
                 [::complete]
                 (let [responses (::responses @rfdb/app-db)]
                   (is (every? #(= {:data
                                    {:pets
                                     [{:id "123", :name "Billy"}
                                      {:id "234", :name "Bob"}
                                      {:id "345", :name "Beatrice"}]}}
                                   %)
                               responses))
                   (is (= 5 (count responses)))

                   #_(re-graph/destroy)
                   #_(wait-for [::re-graph/destroy]
                             (println "test complete"))))))))

(deftest websocket-mutation-test
  (run-test-async
   (re-graph/init {:ws {:url "ws://localhost:8888/graphql-ws"}
                   :http nil})

   (wait-for [::re-graph/init]
             (register-callback!)

             (re-frame/reg-fx
              ::internals/disconnect-ws
              (fn [_]
                (re-frame/dispatch [::ws-disconnected])))

             (re-frame/reg-event-fx
              ::ws-disconnected
              (fn [& _args]
                ;; do nothing
                {}))

             (testing "mutations"
               (testing "async mutate"
                 (re-graph/mutate {:query "mutation { createPet(name: \"Zorro\") { id name } }"
                                   :variables {}
                                   :callback #(re-frame/dispatch [::callback %])})

                 (wait-for
                  [::callback]
                  (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
                         (::response @rfdb/app-db)))))))))
