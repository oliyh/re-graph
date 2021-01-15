(ns re-graph.core-test
  (:require [re-graph.core :as re-graph]
            [re-graph.internals :as internals :refer [default-instance-name]]
            [re-frame.core :as re-frame]
            [re-frame.db :refer [app-db]]
            [day8.re-frame.test :refer [run-test-sync run-test-async wait-for]
             :refer-macros [run-test-sync run-test-async wait-for]]
            [clojure.test :refer [deftest is testing]
             :refer-macros [deftest is testing]]
            #?@(:clj [[cheshire.core :as json]
                      [hato.client :as hato]
                      [clj-http.client :as clj-http]])))

(def on-ws-message @#'internals/on-ws-message)
(def on-open @#'internals/on-open)
(def on-close @#'internals/on-close)
(def insert-http-status @#'internals/insert-http-status)

(defn- data->message [d]
  #?(:cljs (clj->js {:data (js/JSON.stringify (clj->js d))})
     :clj (json/encode d)))

(defn- install-websocket-stub! []
  (re-frame/reg-fx
   ::internals/connect-ws
   (fn [[instance-name _options]]
     ((on-open instance-name ::websocket-connection)))))

(defn- prepend-instance-name [instance-name [event-name & args :as event]]
  (if instance-name
    (into [event-name instance-name] args)
    event))

(defn- dispatch-to-instance [instance-name event]
  (re-frame/dispatch (prepend-instance-name instance-name event)))

(defn- init [instance-name opts]
  (if (nil? instance-name)
    (re-frame/dispatch [::re-graph/init opts])
    (re-frame/dispatch [::re-graph/init instance-name opts])))

(defn- run-subscription-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name default-instance-name)])
        on-ws-message (on-ws-message (or instance-name default-instance-name))]
    (run-test-sync
     (install-websocket-stub!)
     (init instance-name {:ws {:url "ws://socket.rocket"
                               :connection-init-payload nil}})

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

             (on-ws-message (data->message {:type "data"
                                            :id "my-sub"
                                            :payload expected-response-payload}))

             (is (= expected-response-payload
                    (::thing @app-db)))))

         (testing "errors from the WS are sent to the callback"

           (let [expected-response-payload {:errors {:message "Something went wrong"}}]
             (re-frame/reg-event-db
              ::on-thing
              (fn [db [_ payload]]
                (assoc db ::thing payload)))

             (on-ws-message (data->message {:type "error"
                                            :id "my-sub"
                                            :payload (:errors expected-response-payload)}))

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
        db-instance #(get-in @app-db [:re-graph (or instance-name default-instance-name)])
        on-open (partial on-open (or instance-name default-instance-name))]
    (run-test-sync

     (re-frame/reg-fx
      ::internals/connect-ws
      (constantly nil))

     (let [init-payload {:token "abc"}
           expected-subscription-payload {:id "my-sub"
                                          :type "start"
                                          :payload {:query "subscription { things { id } }"
                                                    :variables {:some "variable"}}}]

       (init instance-name {:ws {:url                     "ws://socket.rocket"
                                 :connection-init-payload init-payload}})

       (testing "messages are queued when websocket isn't ready"

         (dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {:some "variable"} [::on-thing]])
         (dispatch [::re-graph/query "{ more_things { id } }" {:some "other-variable"} [::on-thing]])

         (is (= 2 (count (get-in (db-instance) [:ws :queue]))))

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
                    (second @ws-messages)))

             (is (= [::websocket-connection {:type "start",
                                             :payload
                                             {:query "query { more_things { id } }",
                                              :variables {:some "other-variable"}}}]
                    ((juxt first (comp #(dissoc % :id) second)) (last @ws-messages)))))

           (is (empty? (get-in (db-instance) [:ws :queue]))))))

     (testing "when re-graph is destroyed"
       (testing "the subscriptions are cancelled"
         (re-frame/reg-fx
          ::internals/send-ws
          (fn [[ws payload]]
            (is (= ::websocket-connection ws))
            (is (or (= {:id "my-sub" :type "stop"}
                       payload)
                    (= {:type "stop"}
                       (dissoc payload :id)))))))

       (testing "the websocket is closed"
         (re-frame/reg-fx
          ::internals/disconnect-ws
          (fn [[ws]]
            (is (= ::websocket-connection ws)))))

       (dispatch [::re-graph/destroy])

       (testing "the re-graph state is set to destroyed"
         (is (:destroyed? (db-instance))))))))

(deftest websocket-lifecycle-test
  (run-websocket-lifecycle-test nil))

(deftest named-websocket-lifecycle-test
  (run-websocket-lifecycle-test :service-a))

(defn- run-websocket-reconnection-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name default-instance-name)])
        on-close (on-close (or instance-name default-instance-name))]
    (run-test-async
     (install-websocket-stub!)

     (re-frame/reg-fx
      :dispatch-later
      (fn [[{:keys [dispatch]}]]
        (re-frame/dispatch dispatch)))

     (testing "websocket reconnects when disconnected"
       (init instance-name {:ws {:url                     "ws://socket.rocket"
                                 :connection-init-payload {:token "abc"}
                                 :reconnect-timeout       0}})

       (wait-for
        [::internals/on-ws-open]
        (is (get-in (db-instance) [:ws :ready?]))

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
           (is (false? (get-in (db-instance) [:ws :ready?])))

           (testing "websocket is reconnected"
             (wait-for [::internals/on-ws-open]
                       (is (get-in (db-instance) [:ws :ready?]))

                       (testing "subscriptions are resumed"
                         (wait-for
                          [(fn [event]
                             (= (prepend-instance-name (or instance-name default-instance-name) subscription-registration) event))]
                          ;; 2 connection_init
                          ;; 2 subscription
                          (is (= 4 @sent-msgs)))))))))))))

(deftest websocket-reconnection-test
  (run-websocket-reconnection-test nil))

(deftest named-websocket-reconnection-test
  (run-websocket-reconnection-test :service-a))

(defn- run-websocket-query-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)
        db-instance #(get-in @app-db [:re-graph (or instance-name default-instance-name)])
        on-ws-message (on-ws-message (or instance-name default-instance-name))]
    (with-redefs [internals/generate-query-id (constantly "random-query-id")]
      (run-test-sync
       (install-websocket-stub!)

       (init instance-name {:ws {:url "ws://socket.rocket"
                                 :connection-init-payload nil}})

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

              (on-ws-message (data->message {:type "data"
                                             :id (:id payload)
                                             :payload expected-response-payload}))))

           (re-frame/reg-event-db
            ::on-thing
            (fn [db [_ payload]]
              (assoc db ::thing payload)))

           (dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

           (testing "responses are sent to the callback"
             (is (= expected-response-payload
                    (::thing @app-db))))

           (on-ws-message (data->message {:type "complete"
                                          :id "random-query-id"}))

           (testing "the callback is removed afterwards"
             (is (nil? (get-in (db-instance) [:subscriptions "random-query-id"]))))))))))

(deftest websocket-query-test
  (run-websocket-query-test nil))

(deftest named-websocket-query-test
  (run-websocket-query-test :service-a))

(deftest prefer-http-query-test
  (run-test-sync
   (install-websocket-stub!)

   (re-frame/dispatch [::re-graph/init {:ws {:url "ws://socket.rocket"
                                             :connection-init-payload nil
                                             :supported-operations #{:subscribe}}
                                        :http {:url "http://foo.bar/graph-ql"}}])

   (testing "Queries are sent via http because the websocket doesn't support them"
     (let [http-called? (atom false)]
       (re-frame/reg-fx
        ::internals/send-http
        (fn [_]
          (reset! http-called? true)))

       (re-frame/dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

       (is @http-called?)))))

(defn- dispatch-response [[instance-name query-id] payload]
  (re-frame/dispatch [::internals/http-complete instance-name query-id payload]))

(defn- run-http-query-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)]
    (run-test-sync
     (let [expected-http-url "http://foo.bar/graph-ql"]
       (init instance-name {:http {:url expected-http-url}
                            :ws   nil})

       (let [expected-query-payload {:query "query { things { id } }"
                                     :variables {:some "variable"}}
             expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]

         (testing "Queries can be made"

           (re-frame/reg-fx
            ::internals/send-http
            (fn [[_ _ http-url {:keys [payload]} :as fx-args]]
              (is (= expected-query-payload
                     payload))

              (is (= expected-http-url http-url))

              (dispatch-response fx-args expected-response-payload)))

           (re-frame/reg-event-db
            ::on-thing
            (fn [db [_ payload]]
              (assoc db ::thing payload)))

           (dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

           (testing "responses are sent to the callback"
             (is (= expected-response-payload
                    (::thing @app-db)))))

         (testing "In flight queries are deduplicated"
           (let [id :abc-123]
             (re-frame/reg-fx
              ::internals/send-http
              (fn [[_ query-id]]
                (is (= id query-id))))

             (dispatch [::re-graph/query id "{ things { id } }" {:some "variable"} [::on-thing]])

             (re-frame/reg-fx
              ::internals/send-http
              (fn [_]
                (is false "Should not have sent an http request for a duplicate in-flight query id")))

             (dispatch [::re-graph/query id "{ things { id } }" {:some "variable"} [::on-thing]]))))))))

(deftest http-query-test
  (run-http-query-test nil))

(deftest named-http-query-test
  (run-http-query-test :service-a))

(defn- run-http-query-error-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)]
    (run-test-sync
     (let [mock-response (atom {})
           query "{ things { id } }"
           variables {:some "variable"}]
       (init instance-name {:http {:url "http://foo.bar/graph-ql"}
                            :ws   nil})

       (re-frame/reg-fx
        ::internals/send-http
        (fn [fx-args]
          (let [response @mock-response
                {:keys [status error-code]} response]
            (dispatch-response fx-args (if (= :no-error error-code)
                                         (:body response)
                                         (insert-http-status (:body response) status))))))

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

       (testing "Query error with valid graphql error response, insert status only if not present"
         (reset! mock-response {:status 400
                                :body {:errors [{:message "Bad field \"bad1\".",
                                                 :locations [{:line 2, :column 0}]}
                                                {:message "Unknown argument \"limit\"."
                                                 :locations [{:line 2, :column 0}]
                                                 :extensions {:errcode 999
                                                              :status 500}}]}
                                :error-code :http-error})
         (let [expected-response-payload {:errors [{:message "Bad field \"bad1\"."
                                                    :locations [{:line 2, :column 0}]
                                                    :extensions {:status 400}}
                                                   {:message "Unknown argument \"limit\"."
                                                    :locations [{:line 2, :column 0}]
                                                    :extensions {:errcode 999
                                                                 :status 500}}]}]
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

#?(:clj
   (deftest clj-http-query-error-test
     (let [instance-name nil
           dispatch (partial dispatch-to-instance instance-name)]
       (run-test-sync
        (let [query                "{ things { id } }"
              variables            {:some "variable"}
              http-url             "http://foo.bar/graph-ql"
              http-server-response (fn [_url & [_opts respond _raise]]
                                     (respond {:status 400, :body {:errors [{:message "OK"
                                                                             :extensions {:status 404}}]}}))]
          (init instance-name {:http {:url http-url}
                               :ws   nil})

          (re-frame/reg-event-db
           ::on-thing
           (fn [db [_ payload]]
             (assoc db ::thing payload)))

          (testing "http error returns correct response"
            (with-redefs [hato/post http-server-response
                          clj-http/post http-server-response]
              (let [expected-response-payload {:errors [{:message    "OK",
                                                         :extensions {:status 404}}]}]
                (dispatch [::re-graph/query query variables [::on-thing]])
                (is (= expected-response-payload
                       (::thing @app-db)))))))))))

(deftest http-query-error-test
  (run-http-query-error-test nil))

(deftest named-http-query-error-test
  (run-http-query-error-test :service-a))

(defn- run-http-mutation-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)]
    (run-test-sync
     (let [expected-http-url "http://foo.bar/graph-ql"]
       (init instance-name {:http {:url expected-http-url}
                            :ws   nil})

       (let [mutation (str "signin($login:String!,$password:String!){"
                           "signin(login:$login,password:$password){id}}")
             params {:login "alice" :password "secret"}
             expected-query-payload {:query (str "mutation " mutation)
                                     :variables params}
             expected-response-payload {:data {:id 1}}]

         (testing "Mutations can be made"

           (re-frame/reg-fx
            ::internals/send-http
            (fn [[_ _ http-url {:keys [payload]} :as fx-args]]
              (is (= expected-query-payload payload))
              (is (= expected-http-url http-url))
              (dispatch-response fx-args expected-response-payload)))

           (re-frame/reg-event-db
            ::on-mutate
            (fn [db [_ payload]]
              (assoc db ::mutation payload)))

           (dispatch [::re-graph/mutate mutation params [::on-mutate]])

           (testing "responses are sent to the callback"
             (is (= expected-response-payload
                    (::mutation @app-db)))))

         (testing "In flight mutations are deduplicated"
           (let [id :abc-123]
             (re-frame/reg-fx
              ::internals/send-http
              (fn [[_ query-id]]
                (is (= id query-id))))

             (dispatch [::re-graph/mutate id mutation params [::on-thing]])

             (re-frame/reg-fx
              ::internals/send-http
              (fn [_]
                (is false "Should not have sent an http request for a duplicate in-flight mutation id")))

             (dispatch [::re-graph/mutate id mutation params [::on-thing]]))))))))

(deftest http-mutation-test
  (run-http-mutation-test nil))

(deftest named-http-mutation-test
  (run-http-mutation-test :service-a))

(defn- run-http-parameters-test [instance-name]
  (let [dispatch (partial dispatch-to-instance instance-name)]
    (run-test-sync
     (let [expected-http-url "http://foo.bar/graph-ql"
           expected-request {:with-credentials? false}]
       (init instance-name {:http {:url  expected-http-url
                                   :impl (constantly expected-request)}
                            :ws   nil})
       (testing "Request can be specified"
         (re-frame/reg-fx
          ::internals/send-http
          (fn [[_ _ _http-url {:keys [request]}]]
            (is (= expected-request
                   request))))
         (dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])
         (dispatch [::re-graph/mutate "don't care" {:some "variable"} [::on-thing]]))))))


(deftest http-parameters-test
  (run-http-parameters-test nil))

(deftest named-http-parameters-test
  (run-http-parameters-test :service-a))

(defn- run-non-re-frame-test [instance-name]
  (let [db-instance #(get-in @app-db [:re-graph (or instance-name default-instance-name)])
        on-ws-message (on-ws-message (or instance-name default-instance-name))
        init (if instance-name (partial re-graph/init instance-name) re-graph/init)
        subscribe (if instance-name (partial re-graph/subscribe instance-name) re-graph/subscribe)
        unsubscribe (if instance-name (partial re-graph/unsubscribe instance-name) re-graph/unsubscribe)
        query (if instance-name (partial re-graph/query instance-name) re-graph/query)
        mutate (if instance-name (partial re-graph/mutate instance-name) re-graph/mutate)]

    (testing "can call normal functions instead of needing re-frame"

      (testing "using a websocket"
        (run-test-sync
         (install-websocket-stub!)

         (init {:ws {:url "ws://socket.rocket"
                     :connection-init-payload nil}})
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
             (on-ws-message (data->message {:type "data"
                                            :id "my-sub"
                                            :payload expected-response-payload}))

             (is @callback-called?))

           (testing "and unregistered"
             (re-frame/reg-fx
              ::internals/send-ws
              (fn [[ws payload]]
                (is (= ::websocket-connection ws))
                (is (= expected-unsubscription-payload
                       payload))))

             (unsubscribe :my-sub)

             (is (nil? (get-in (db-instance) [:subscriptions "my-sub"])))))))

      (testing "using http"
        (testing "queries"
          (run-test-sync
           (let [expected-http-url "http://foo.bar/graph-ql"
                 expected-query-payload {:query "query { things { id } }"
                                         :variables {:some "variable"}}
                 expected-response-payload {:data {:things [{:id 1} {:id 2}]}}
                 callback-called? (atom false)
                 callback-fn (fn [payload]
                               (reset! callback-called? true)
                               (is (= expected-response-payload payload)))]

             (init {:http {:url expected-http-url}
                    :ws   nil})

             (re-frame/reg-fx
              ::internals/send-http
              (fn [[_ _ http-url {:keys [payload]} :as fx-args]]
                (is (= expected-query-payload
                       payload))

                (is (= expected-http-url http-url))

                (dispatch-response fx-args expected-response-payload)))

             (query "{ things { id } }" {:some "variable"} callback-fn)

             (testing "responses are sent to the callback"
               (is @callback-called?)))))

        (testing "mutations"
          (run-test-sync
           (let [expected-http-url "http://foo.bar/graph-ql"
                 expected-query-payload {:query "mutation { things { id } }"
                                         :variables {:some "variable"}}
                 expected-response-payload {:data {:things [{:id 1} {:id 2}]}}
                 callback-called? (atom false)
                 callback-fn (fn [payload]
                               (reset! callback-called? true)
                               (is (= expected-response-payload payload)))]

             (init {:http {:url expected-http-url}
                    :ws   nil})

             (re-frame/reg-fx
              ::internals/send-http
              (fn [[_ _ http-url {:keys [payload]} :as fx-args]]
                (is (= expected-query-payload
                       payload))

                (is (= expected-http-url http-url))

                (dispatch-response fx-args expected-response-payload)))

             (mutate "{ things { id } }" {:some "variable"} callback-fn)

             (testing "responses are sent to the callback"
               (is @callback-called?)))))))))

(deftest non-re-frame-test
  (run-non-re-frame-test nil))

(deftest named-non-re-frame-test
  (run-non-re-frame-test :service-a))

(deftest venia-compatibility-test
  (run-test-sync
   (let [expected-http-url "http://foo.bar/graph-ql"]
     (re-graph/init {:http {:url expected-http-url}
                     :ws   nil})

     (let [expected-query-payload {:query "query { things { id } }"
                                   :variables {:some "variable"}}
           expected-response-payload {:data {:things [{:id 1} {:id 2}]}}]

       (testing "Ignores 'query' at the start of the query"

         (re-frame/reg-fx
          ::internals/send-http
          (fn [[_ _ http-url {:keys [payload]} :as fx-args]]
            (is (= expected-query-payload
                   payload))

            (is (= expected-http-url http-url))
            (dispatch-response fx-args expected-response-payload)))

         (re-frame/reg-event-db
          ::on-thing
          (fn [db [_ payload]]
            (assoc db ::thing payload)))

         (re-frame/dispatch [::re-graph/query "query { things { id } }" {:some "variable"} [::on-thing]])

         (testing "responses are sent to the callback"
           (is (= expected-response-payload
                  (::thing @app-db)))))))))

(deftest multi-instance-test
  (run-test-sync

   (re-frame/reg-fx
    ::internals/connect-ws
    (fn [[instance-name _options]]
      ((on-open instance-name (keyword (str (name instance-name) "-connection"))))))

   (init :service-a {:ws {:url                     "ws://socket.rocket"
                          :connection-init-payload nil}})
   (init :service-b {:ws {:url "ws://socket.rocket"
                          :connection-init-payload nil}})

   (let [expected-subscription-payload-a {:id "a-sub"
                                          :type "start"
                                          :payload {:query "subscription { things { a } }"
                                                    :variables {:some "a"}}}
         expected-unsubscription-payload-a {:id "a-sub"
                                            :type "stop"}

         expected-subscription-payload-b {:id "b-sub"
                                          :type "start"
                                          :payload {:query "subscription { things { b } }"
                                                    :variables {:some "b"}}}
         expected-unsubscription-payload-b {:id "b-sub"
                                            :type "stop"}]

       (testing "Subscriptions can be registered"

         (re-frame/reg-fx
          ::internals/send-ws
          (fn [[ws payload]]
            (condp = ws
              :service-a-connection
              (is (= expected-subscription-payload-a payload))

              :service-b-connection
              (is (= expected-subscription-payload-b payload)))))

         (re-frame/dispatch [::re-graph/subscribe :service-a :a-sub "{ things { a } }" {:some "a"} [::on-a-thing]])
         (re-frame/dispatch [::re-graph/subscribe :service-b :b-sub "{ things { b } }" {:some "b"} [::on-b-thing]])

         (is (= [::on-a-thing]
                (get-in @app-db [:re-graph :service-a :subscriptions "a-sub" :callback])))

         (is (= [::on-b-thing]
                (get-in @app-db [:re-graph :service-b :subscriptions "b-sub" :callback])))

         (testing "and deduplicated"
           (re-frame/reg-fx
            ::internals/send-ws
            (fn [_]
              (is false "Should not have sent a websocket message for an existing subscription")))

           (re-frame/dispatch [::re-graph/subscribe :service-a :a-sub "{ things { a } }" {:some "a"} [::on-a-thing]])
           (re-frame/dispatch [::re-graph/subscribe :service-b :b-sub "{ things { b } }" {:some "b"} [::on-b-thing]]))

         (testing "messages from the WS are sent to the callback"

           (let [expected-response-payload-a {:data {:things [{:a 1} {:a 2}]}}
                 expected-response-payload-b {:data {:things [{:b 1}]}}]
             (re-frame/reg-event-db
              ::on-a-thing
              (fn [db [_ payload]]
                (assoc db ::a-thing payload)))

             (re-frame/reg-event-db
              ::on-b-thing
              (fn [db [_ payload]]
                (assoc db ::b-thing payload)))

             ((on-ws-message :service-a) (data->message {:type "data"
                                                         :id "a-sub"
                                                         :payload expected-response-payload-a}))

             ((on-ws-message :service-b) (data->message {:type "data"
                                                         :id "b-sub"
                                                         :payload expected-response-payload-b}))

             (is (= expected-response-payload-a
                    (::a-thing @app-db)))

             (is (= expected-response-payload-b
                    (::b-thing @app-db)))))

         (testing "and unregistered"
           (re-frame/reg-fx
            ::internals/send-ws
            (fn [[ws payload]]
              (condp = ws
                :service-a-connection
                (is (= expected-unsubscription-payload-a payload))

                :service-b-connection
                (is (= expected-unsubscription-payload-b payload)))))

           (re-frame/dispatch [::re-graph/unsubscribe :service-a :a-sub])
           (re-frame/dispatch [::re-graph/unsubscribe :service-b :b-sub])

           (is (nil? (get-in @app-db [:re-graph :service-a :subscriptions "a-sub"])))
           (is (nil? (get-in @app-db [:re-graph :service-b :subscriptions "b-sub"]))))))))


(deftest reinit-ws-test []
  (run-test-sync
   (install-websocket-stub!)

   (testing "websocket connection payload is sent"
     (let [last-ws-message (atom nil)]

       (re-frame/reg-fx
        ::internals/send-ws
        (fn [[ws payload]]
          (is (= ::websocket-connection ws))
          (reset! last-ws-message payload)))

       (re-frame/dispatch [::re-graph/init {:ws {:url "ws://socket.rocket"
                                                 :connection-init-payload {:auth-token 123}}}])

       (is (= {:type "connection_init"
               :payload {:auth-token 123}}
              @last-ws-message))

       (testing "updated when re-inited"
         (re-frame/dispatch [::re-graph/re-init {:ws {:connection-init-payload {:auth-token 234}}}] )

         (is (= {:type "connection_init"
                 :payload {:auth-token 234}}
                @last-ws-message)))))))

(deftest re-init-http-test []
  (run-test-sync

   (testing "http headers are sent"

     (let [last-http-message (atom nil)]
       (re-frame/reg-fx
        ::internals/send-http
        (fn [[_ _ _http-url {:keys [request]} :as fx-args]]
          (reset! last-http-message request)
          (dispatch-response fx-args {})))

       (re-frame/dispatch [::re-graph/init {:http {:url "http://foo.bar/graph-ql"
                                                   :impl {:headers {"Authorization" 123}}}
                                            :ws nil}])

       (re-frame/dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

       (is (= {:headers {"Authorization" 123}}
              @last-http-message))

       (testing "and can be updated"
         (re-frame/dispatch [::re-graph/re-init {:http {:impl {:headers {"Authorization" 234}}}}])
         (re-frame/dispatch [::re-graph/query "{ things { id } }" {:some "variable"} [::on-thing]])

         (is (= {:headers {"Authorization" 234}}
                @last-http-message)))))))
