(ns re-graph.integration-test
  (:require [re-graph.core :as re-graph]
            [re-graph.internals :as internals]
            #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [day8.re-frame.test :refer [run-test-async wait-for #?(:clj run-test-sync)]]
            [re-frame.core :as re-frame]
            [re-frame.db :as rfdb]
            #?(:clj [re-graph.integration-server :refer [with-server]])))

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
     (re-graph/query "{ pets { id name } }" {} #(re-frame/dispatch [::callback %]))

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
      (re-graph/mutate "mutation { createPet(name: \"Zorro\") { id name } }" {} #(re-frame/dispatch [::callback %]))

      (wait-for
       [::callback]
       (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
              (::response @rfdb/app-db)))))))

#?(:clj
   (deftest sync-http-test
     (run-test-sync
      (re-graph/init {:ws nil
                      :http {:url "http://localhost:8888/graphql"}})

      (testing "sync query"
        (is (= {:data
                {:pets
                 [{:id "123", :name "Billy"}
                  {:id "234", :name "Bob"}
                  {:id "345", :name "Beatrice"}]}}
               (re-graph/query-sync "{ pets { id name } }" {}))))

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
               (re-graph/query-sync "{ malformed }" {})))))))

(deftest websocket-query-test
   (run-test-async
    (re-graph/init {:ws {:url "ws://localhost:8888/graphql-ws"}
                    :http nil})

    (re-frame/reg-fx
     ::internals/disconnect-ws
     (fn [_]
       (re-frame/dispatch [::ws-disconnected])))

    (re-frame/reg-event-fx
     ::ws-disconnected
     (fn [& _args]
       ;; do nothing
       {}))

    (re-frame/reg-event-db
     ::complete
     (fn [db _]
       db))

    (re-frame/reg-event-fx
     ::callback
     (fn [{:keys [db]} [_ response]]
       (let [new-db (update db ::responses conj response)]
         (merge
          {:db new-db}
          (when (<= 5 (count (::responses new-db)))
            {:dispatch [::complete]})))))

    (testing "subscriptions"
      (re-graph/subscribe :all-pets "MyPets($count: Int) { pets(count: $count) { id name } }" {:count 5}
                          #(re-frame/dispatch [::callback %]))

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
         (is (= 5 (count responses))))))))

(deftest websocket-mutation-test
  (run-test-async
   (re-graph/init {:ws {:url "ws://localhost:8888/graphql-ws"}
                   :http nil})
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
       (re-graph/mutate "mutation { createPet(name: \"Zorro\") { id name } }" {} #(re-frame/dispatch [::callback %]))

       (wait-for
        [::callback]
        (is (= {:data {:createPet {:id "999", :name "Zorro"}}}
               (::response @rfdb/app-db))))))))
