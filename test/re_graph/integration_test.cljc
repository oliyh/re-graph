(ns re-graph.integration-test
  (:require [re-graph.core :as re-graph]
   #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
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
       )))

(deftest websocket-test)
