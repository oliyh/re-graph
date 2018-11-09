(ns re-graph.core-test
  (:require [re-graph.core :as re-graph]
            [re-graph.internals :as internals]
            [re-frame.core :as re-frame]
            [re-frame.db :refer [app-db]]
            [day8.re-frame.test :refer-macros [run-test-sync run-test-async wait-for]]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [devcards.core :refer-macros [deftest]]))

(def on-ws-message @#'internals/on-ws-message)
(def on-open @#'internals/on-open)
(def on-close @#'internals/on-close)
(def insert-http-status @#'internals/insert-http-status)

(re-frame/reg-fx
 ::internals/connect-ws
 (fn [[instance-name & args]]
   ((on-open instance-name ::websocket-connection))))

(defn- prepend-instance-name [instance-name [event-name & args :as event]]
  (if instance-name
    (into [event-name instance-name] args)
    event))

(defn- dispatch-to-instance [instance-name event]
  (re-frame/dispatch (prepend-instance-name instance-name event)))

(defn- init [instance-name opts]
  (re-frame/dispatch [::re-graph/init (merge opts
                                             (when instance-name
                                               {:instance-name instance-name}))]))

(defn- run-subscription-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])
        on-ws-message (on-ws-message (or instance-name :default))]
    (run-test-sync
     (init instance-name {:connection-init-payload nil})

     (let [expected-subscription-payload {:id "my-sub"
                                          :type "start"
                                          :payload {:query "subscription { things { id } }"
                                                    :variables {:some "variable"}}}
           expected-unsubscription-payload {:id "my-sub"
                                            :type "stop"}]

       (testing "Subscriptions can be registered"

         (re-frame/reg-fx
          ::internals/send-ws
          (fn [[ws payload]]
            (is (= ::websocket-connection ws))
            (is (= expected-subscription-payload
                   payload))))

         (dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]])

         (is (= [::on-thing]
                (get-in (db-instance) [:subscriptions "my-sub" :callback])))

         (testing "and deduplicated"
           (re-frame/reg-fx
            ::internals/send-ws
            (fn [_]
              (is false "Should not have sent a websocket message for an existing subscription")))

           (dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]]))

         (testing "messages from the WS are sent to the callback"

           (let [expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]
             (re-frame/reg-event-db
              ::on-thing
              (fn [db [_ payload]]
                (assoc db ::thing payload)))

             (on-ws-message (clj->js {:data (js/JSON.stringify
                                             (clj->js {:type "data"
                                                       :id "my-sub"
                                                       :payload expected-response-payload}))}))

             (is (= expected-response-payload
                    (::thing @app-db)))))

         (testing "and unregistered"
           (re-frame/reg-fx
            ::internals/send-ws
            (fn [[ws payload]]
              (is (= ::websocket-connection ws))
              (is (= expected-unsubscription-payload
                     payload))))

           (dispatch [::re-graph/unsubscribe :my-sub])

           (is (nil? (get-in (db-instance) [:subscriptions "my-sub"])))))))))

(deftest subscription-test
  (run-subscription-test nil))

(deftest named-subscription-test
  (run-subscription-test :service-a))

(defn- run-websocket-lifecycle-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])
        on-open (partial on-open (or instance-name :default))]
    (run-test-sync

     (re-frame/reg-fx
      ::internals/connect-ws
      (constantly nil))

     (let [init-payload {:token "abc"}
           expected-subscription-payload {:id "my-sub"
                                          :type "start"
                                          :payload {:query "subscription { things { id } }"
                                                    :variables {:some "variable"}}}]

       (init instance-name {:connection-init-payload init-payload})

       (testing "messages are queued when websocket isn't ready"

         (dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]])

         (is (= 1 (count (get-in (db-instance) [:websocket :queue]))))

         (testing "and sent when websocket opens"

           (let [ws-messages (atom [])]
             (re-frame/reg-fx
              ::internals/send-ws
              (fn [[ws payload]]
                (swap! ws-messages conj [ws payload])))

             ((on-open ::websocket-connection))

             (testing "the connection init payload is sent first"
               (is (= [::websocket-connection
                       {:type "connection_init"
                        :payload init-payload}]
                      (first @ws-messages))))

             (is (= [::websocket-connection expected-subscription-payload]
                    (second @ws-messages))))

           (is (empty? (get-in (db-instance) [:websocket :queue]))))))

     (testing "when re-graph is destroyed"
       (testing "the subscriptions are cancelled"
         (re-frame/reg-fx
          ::internals/send-ws
          (fn [[ws payload]]
            (is (= ::websocket-connection ws))
            (is (= {:id "my-sub" :type "stop"}
                   payload)))))

       (testing "the websocket is closed"
         (re-frame/reg-fx
          ::internals/disconnect-ws
          (fn [[ws]]
            (is (= ::websocket-connection ws)))))

       (dispatch [::re-graph/destroy])

       (testing "the re-graph state is no more"
         (is (nil? (db-instance))))))))

(deftest websocket-lifecycle-test
  (run-websocket-lifecycle-test nil))

(deftest named-websocket-lifecycle-test
  (run-websocket-lifecycle-test :service-a))

#_(defn- run-websocket-reconnection-test [instance-name]
  )

(deftest websocket-reconnection-test
  (let [instance-name nil
        dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])
        on-close (on-close (or instance-name :default))]
    (run-test-async
     (testing "websocket reconnects when disconnected"
       (init instance-name {:connection-init-payload {:token "abc"}
                            :ws-reconnect-timeout 0})

       (wait-for
        [::internals/on-ws-open]
        (is (get-in (db-instance) [:websocket :ready?]))

        ;; create a subscription and wait for it to be sent
        (let [subscription-registration [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]]
              sent-msgs (atom 0)]
          (re-frame/reg-fx
           ::internals/send-ws
           (fn [[ws payload]]
             (is (= ::websocket-connection ws))
             (is (or
                  (= "connection_init" (:type payload))
                  (= {:id "my-sub"
                      :type "start"
                      :payload {:query "subscription { things { id } }"
                                :variables {:some "variable"}}}
                     payload)))
             (swap! sent-msgs inc)))

          (dispatch subscription-registration)

          (on-close)
          (wait-for
           [::internals/on-ws-close]
           (is (false? (get-in (db-instance) [:websocket :ready?])))

           (testing "websocket is reconnected"
             (wait-for [::internals/on-ws-open]
                       (is (get-in (db-instance) [:websocket :ready?]))

                       (testing "subscriptions are resumed"
                         (wait-for
                          [(fn [event]
                             (= (prepend-instance-name (or instance-name :default) subscription-registration) event))]
                          ;; 2 connection_init
                          ;; 2 subscription
                          (is (= 4 @sent-msgs))))))))))))
#_  (run-websocket-reconnection-test nil))

(defn- run-websocket-query-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])
        on-ws-message (on-ws-message (or instance-name :default))]
    (with-redefs [internals/generate-query-id (constantly "random-query-id")]
      (run-test-sync
       (init instance-name {:connection-init-payload nil})

       (let [expected-query-payload {:id "random-query-id"
                                     :type "start"
                                     :payload {:query "query { things { id } }"
                                               :variables {:some "variable"}}}
             expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]

         (testing "Queries can be made"

           (re-frame/reg-fx
            ::internals/send-ws
            (fn [[ws payload]]
              (is (= ::websocket-connection ws))

              (is (= expected-query-payload
                     payload))

              (on-ws-message (clj->js {:data (js/JSON.stringify
                                              (clj->js {:type "data"
                                                        :id (:id payload)
                                                        :payload expected-response-payload}))}))))

           (re-frame/reg-event-db
            ::on-thing
            (fn [db [_ payload]]
              (assoc db ::thing payload)))

           (dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

           (testing "responses are sent to the callback"
             (is (= expected-response-payload
                    (::thing @app-db))))

           (on-ws-message (clj->js {:data (js/JSON.stringify
                                           (clj->js {:type "complete"
                                                     :id "random-query-id"}))}))

           (testing "the callback is removed afterwards"
             (is (nil? (get-in (db-instance) [:subscriptions "random-query-id"]))))))))))

(deftest websocket-query-test
  (run-websocket-query-test nil))

(deftest named-websocket-query-test
  (run-websocket-query-test :service-a))

(defn- run-http-query-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)]
    (run-test-sync
     (let [expected-http-url "http://foo.bar/graph-ql"]
       (init instance-name {:http-url expected-http-url
                            :ws-url nil})

       (let [expected-query-payload {:query "query { things { id } }"
                                     :variables {:some "variable"}}
             expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]

         (testing "Queries can be made"

           (re-frame/reg-fx
            ::internals/send-http
            (fn [[http-url {:keys [payload]} callback-fn]]
              (is (= expected-query-payload
                     payload))

              (is (= expected-http-url http-url))

              (callback-fn expected-response-payload)))

           (re-frame/reg-event-db
            ::on-thing
            (fn [db [_ payload]]
              (assoc db ::thing payload)))

           (dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

           (testing "responses are sent to the callback"
             (is (= expected-response-payload
                    (::thing @app-db))))))))))

(deftest http-query-test
  (run-http-query-test nil))

(deftest named-http-query-test
  (run-http-query-test :service-a))

(defn- run-http-query-error-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])]
    (run-test-sync
     (let [mock-response (atom {})
           query "{ things { id } }"
           variables {:some "variable"}]
       (init instance-name {:http-url "http://foo.bar/graph-ql"
                            :ws-url nil})

       (re-frame/reg-fx
        ::internals/send-http
        (fn [[_ _ callback-fn]]
          (let [response @mock-response
                {:keys [status error-code]} response]
            (if (= :no-error error-code)
              (callback-fn (:body response))
              (callback-fn (insert-http-status (:body response) status))))))

       (re-frame/reg-event-db
        ::on-thing
        (fn [db [_ payload]]
          (assoc db ::thing payload)))

       (testing "Query error with invalid graphql response (string body)"
         (reset! mock-response {:status 403
                                :body "Access Token is invalid"
                                :error-code :http-error})
         (let [expected-response-payload {:errors [{:message "The HTTP call failed.",
                                                    :extensions {:status 403}}]}]
           (dispatch [::re-graph/query query variables [::on-thing]])
           (is (= expected-response-payload
                  (::thing @app-db)))))

       (testing "Query error with invalid graphql response (map body)"
         (reset! mock-response {:status 403
                                :body {:data nil
                                       :errors nil}
                                :error-code :http-error})
         (let [expected-response-payload {:data nil
                                          :errors [{:message "The HTTP call failed.",
                                                    :extensions {:status 403}}]}]
           (dispatch [::re-graph/query query variables [::on-thing]])
           (is (= expected-response-payload
                  (::thing @app-db)))))

       (testing "Query error with valid graphql error response"
         (reset! mock-response {:status 400
                                :body {:errors [{:message "Bad field \"bad1\".",
                                                 :locations [{:line 2, :column 0}]}
                                                {:message "Unknown argument \"limit\"."
                                                 :locations [{:line 2, :column 0}]
                                                 :extensions {:errcode 999}}]}
                                :error-code :http-error})
         (let [expected-response-payload {:errors [{:message "Bad field \"bad1\"."
                                                    :locations [{:line 2, :column 0}]
                                                    :extensions {:status 400}}
                                                   {:message "Unknown argument \"limit\"."
                                                    :locations [{:line 2, :column 0}]
                                                    :extensions {:errcode 999
                                                                 :status 400}}]}]
           (dispatch [::re-graph/query query variables [::on-thing]])
           (is (= expected-response-payload
                  (::thing @app-db)))))

       (testing "No query error, body unchanged"
         (let [expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]
           (reset! mock-response {:status 200
                                  :body expected-response-payload
                                  :error-code :no-error})
           (dispatch [::re-graph/query query variables [::on-thing]])
           (is (= expected-response-payload
                  (::thing @app-db)))))))))

(deftest http-query-error-test
  (run-http-query-error-test nil))

(deftest named-http-query-error-test
  (run-http-query-error-test :service-a))

(defn- run-http-mutation-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])]
    (run-test-sync
     (let [expected-http-url "http://foo.bar/graph-ql"]
       (init instance-name {:http-url expected-http-url
                            :ws-url nil})

       (let [mutation (str "signin($login:String!,$password:String!){"
                           "signin(login:$login,password:$password){id}}")
             params {:login "alice" :password "secret"}
             expected-query-payload {:query (str "mutation " mutation)
                                     :variables params}
             expected-response-payload {:data {:id 1}}]

         (testing "Mutations can be made"

           (re-frame/reg-fx
            ::internals/send-http
            (fn [[http-url {:keys [payload]} callback-fn]]
              (is (= expected-query-payload payload))
              (is (= expected-http-url http-url))
              (callback-fn expected-response-payload)))

           (re-frame/reg-event-db
            ::on-mutate
            (fn [db [_ payload]]
              (assoc db ::mutation payload)))

           (dispatch [::re-graph/mutate mutation params [::on-mutate]])

           (testing "responses are sent to the callback"
             (is (= expected-response-payload
                    (::mutation @app-db))))))))))

(deftest http-mutation-test
  (run-http-mutation-test nil))

(deftest named-http-mutation-test
  (run-http-mutation-test :service-a))

(defn- run-http-parameters-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name :default)])]
    (run-test-sync
     (let [expected-http-url "http://foo.bar/graph-ql"
           expected-request {:with-credentials? false}]
       (init instance-name {:http-url expected-http-url
                            :http-parameters expected-request
                            :ws-url nil})
       (testing "Request can be specified"
         (re-frame/reg-fx
          ::internals/send-http
          (fn [[http-url {:keys [request payload]} callback-fn]]
            (is (= expected-request
                   request))))
         (dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])
         (dispatch [::re-graph/mutate "don't care" {:some "variable"} [::on-thing]]))))))


(deftest http-parameters-test
  (run-http-parameters-test nil))

(deftest named-http-parameters-test
  (run-http-parameters-test :service-a))

(defn- run-non-re-frame-test [instance-name]
  (let [db-instance #(get-in @app-db [:re-graph (or instance-name :default)])
        on-ws-message (on-ws-message (or instance-name :default))
        init (if instance-name (partial re-graph/init instance-name) re-graph/init)
        subscribe (if instance-name (partial re-graph/subscribe instance-name) re-graph/subscribe)
        unsubscribe (if instance-name (partial re-graph/unsubscribe instance-name) re-graph/unsubscribe)]
    (testing "can call normal functions instead of needing re-frame"
      (run-test-sync
       (init {:connection-init-payload nil})

       (let [expected-subscription-payload {:id "my-sub"
                                            :type "start"
                                            :payload {:query "subscription { things { id } }"
                                                      :variables {:some "variable"}}}
             expected-unsubscription-payload {:id "my-sub"
                                              :type "stop"}
             expected-response-payload {:data {:things [{:id 1} {:id 2}]}}
             callback-called? (atom false)
             callback-fn (fn [payload]
                           (reset! callback-called? true)
                           (is (= expected-response-payload payload)))]

         (re-frame/reg-fx
          ::internals/send-ws
          (fn [[ws payload]]
            (is (= ::websocket-connection ws))
            (is (= expected-subscription-payload
                   payload))))

         (subscribe :my-sub "{ things { id } }" {:some "variable"} callback-fn)

         (is (= [::internals/callback callback-fn]
                (get-in (db-instance) [:subscriptions "my-sub" :callback])))

         (testing "messages from the WS are sent to the callback-fn"
           (on-ws-message (clj->js {:data (js/JSON.stringify
                                           (clj->js {:type "data"
                                                     :id "my-sub"
                                                     :payload expected-response-payload}))}))

           (is @callback-called?))

         (testing "and unregistered"
           (re-frame/reg-fx
            ::internals/send-ws
            (fn [[ws payload]]
              (is (= ::websocket-connection ws))
              (is (= expected-unsubscription-payload
                     payload))))

           (unsubscribe :my-sub)

           (is (nil? (get-in (db-instance) [:subscriptions "my-sub"])))))))))

(deftest non-re-frame-test
  (run-non-re-frame-test nil))

(deftest named-non-re-frame-test
  (run-non-re-frame-test :service-a))

(deftest venia-compatibility-test
  (run-test-sync
   (let [expected-http-url "http://foo.bar/graph-ql"]
     (re-graph/init {:http-url expected-http-url
                     :ws-url nil})

     (let [expected-query-payload {:query "query { things { id } }"
                                   :variables {:some "variable"}}
           expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]

       (testing "Ignores 'query' at the start of the query"

         (re-frame/reg-fx
          ::internals/send-http
          (fn [[http-url {:keys [payload]} callback-fn]]
            (is (= expected-query-payload
                   payload))

            (is (= expected-http-url http-url))

            (callback-fn expected-response-payload)))

         (re-frame/reg-event-db
          ::on-thing
          (fn [db [_ payload]]
            (assoc db ::thing payload)))

         (re-frame/dispatch [::re-graph/query "query { things { id } }" {:some "variable"} [::on-thing]])

         (testing "responses are sent to the callback"
           (is (= expected-response-payload
                  (::thing @app-db)))))))))
