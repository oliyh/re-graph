(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals :refer [interceptors default-instance-name destroyed-instance]]
            [re-graph.logging :as log]
            [clojure.string :as string]))

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [db dispatchable-event instance-name]} [query-id query variables callback-event :as event]]
   (let [query (str "mutation " (string/replace query #"^mutation\s?" ""))]
     (cond
       (or (get-in db [:http-requests query-id])
           (get-in db [:subscriptions query-id]))
       {} ;; duplicate in-flight mutation

       (get-in db [:websocket :ready?])
       {:db (assoc-in db [:subscriptions query-id] {:callback callback-event})
        ::internals/send-ws [(get-in db [:websocket :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (:websocket db)
       {:db (update-in db [:websocket :queue] conj dispatchable-event)}

       :else
       {:db (assoc-in db [:http-requests query-id] {:callback callback-event})
        ::internals/send-http [instance-name
                               query-id
                               (:http-url db)
                               {:request (:http-parameters db)
                                :payload {:query query
                                          :variables variables}}]}))))

#?(:clj
   (defn ^:private sync-wrapper
     "Wraps the given function to allow the GraphQL result to be returned
      synchronously. Will return a GraphQL error response if the request times
      out. Will throw if the call returns an exception."
     [f timeout & args]
     (let [p        (promise)
           callback (fn [result] (deliver p result))
           args'    (conj (vec args) callback)]
       (apply f args')

       ;; explicit timeout to avoid unreliable aborts from underlying implementations
       (let [result (deref p timeout :timeout)]
         (if (= :timeout result)
           {:errors [{:status 500, :message "GraphQL request timed out", :args args}]}
           result)))))

(defn mutate [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::mutate] (conj (vec (butlast args)) [::internals/callback callback-fn])))))

#?(:clj
   (def mutate-sync (partial sync-wrapper mutate 3000)))    ;; 3s timeout

(re-frame/reg-event-fx
 ::query
 interceptors
 (fn [{:keys [db dispatchable-event instance-name]} [query-id query variables callback-event :as event]]
   (let [query (str "query " (string/replace query #"^query\s?" ""))]
     (cond
       (or (get-in db [:http-requests query-id])
           (get-in db [:subscriptions query-id]))
       {} ;; duplicate in-flight query

       (get-in db [:websocket :ready?])
       {:db (assoc-in db [:subscriptions query-id] {:callback callback-event})
        ::internals/send-ws [(get-in db [:websocket :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (get-in db [:websocket])
       {:db (update-in db [:websocket :queue] conj dispatchable-event)}

       :else
       {:db (assoc-in db [:http-requests query-id] {:callback callback-event})
        ::internals/send-http [instance-name
                               query-id
                               (:http-url db)
                               {:request (:http-parameters db)
                                :payload {:query query
                                          :variables variables}}]}))))

(defn query [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::query] (conj (vec (butlast args)) [::internals/callback callback-fn])))))

#?(:clj
   (def query-sync (partial sync-wrapper query 3000)))      ;; 3s timeout

(re-frame/reg-event-fx
 ::abort
 interceptors
 (fn [{:keys [db]} [query-id]]
   (merge
    {:db (-> db
             (update :subscriptions dissoc query-id)
             (update :http-requests dissoc query-id))}
    (when-let [abort-fn (get-in db [:http-requests query-id :abort])]
      {::internals/call-abort abort-fn}) )))

(defn abort
  ([query-id] (abort default-instance-name query-id))
  ([instance-name query-id]
   (re-frame/dispatch [::abort instance-name query-id])))

(re-frame/reg-event-fx
 ::subscribe
 interceptors
 (fn [{:keys [db instance-name dispatchable-event] :as cofx} [subscription-id query variables callback-event :as event]]
   (cond
     (get-in db [:subscriptions (name subscription-id) :active?])
     {} ;; duplicate subscription

     (get-in db [:websocket :ready?])
     {:db (assoc-in db [:subscriptions (name subscription-id)] {:callback callback-event
                                                                :event dispatchable-event
                                                                :active? true})
      ::internals/send-ws [(get-in db [:websocket :connection])
                           {:id (name subscription-id)
                            :type "start"
                            :payload {:query (str "subscription " (string/replace query #"^subscription\s?" ""))
                                      :variables variables}}]}

     (:websocket db)
     {:db (update-in db [:websocket :queue] conj dispatchable-event)}

     :else
     (log/error
       (str
        "Error creating subscription " subscription-id
        " on instance " instance-name
         ": Websocket is not enabled, subscriptions are not possible. Please check your re-graph configuration")))))

(defn subscribe
  ([subscription-id query variables callback-fn] (subscribe default-instance-name subscription-id query variables callback-fn))
  ([instance-name subscription-id query variables callback-fn]
   (re-frame/dispatch [::subscribe instance-name subscription-id query variables [::internals/callback callback-fn]])))

(re-frame/reg-event-fx
 ::unsubscribe
 interceptors
 (fn [{:keys [db instance-name]} [subscription-id :as event]]
   (if (get-in db [:websocket :ready?])
     {:db (update db :subscriptions dissoc (name subscription-id))
      ::internals/send-ws [(get-in db [:websocket :connection])
                           {:id (name subscription-id)
                            :type "stop"}]}

     {:db (update-in db [:websocket :queue] conj [::unsubscribe instance-name subscription-id])})))

(defn unsubscribe
  ([subscription-id] (unsubscribe default-instance-name subscription-id))
  ([instance-name subscription-id]
   (re-frame/dispatch [::unsubscribe instance-name subscription-id])))

(re-frame/reg-event-fx
 ::init
 (fn [{:keys [db]} [_ instance-name opts]]
   (let [[instance-name opts] (cond
                                (and (nil? instance-name) (nil? opts))
                                [default-instance-name {}]

                                (map? instance-name)
                                [default-instance-name instance-name]

                                (nil? instance-name)
                                [default-instance-name opts]

                                :else
                                [instance-name opts])
         {:keys [ws-url http-url http-parameters ws-reconnect-timeout resume-subscriptions? connection-init-payload]
          :or {ws-url (internals/default-ws-url)
               http-parameters {}
               http-url "/graphql"
               ws-reconnect-timeout 5000
               connection-init-payload {}
               resume-subscriptions? true}} opts]

     (merge
      {:db (assoc-in db [:re-graph instance-name]
                     (merge
                      (when ws-url
                        {:websocket {:url ws-url
                                     :ready? false
                                     :connection-init-payload connection-init-payload
                                     :queue []
                                     :reconnect-timeout ws-reconnect-timeout
                                     :resume-subscriptions? resume-subscriptions?}})
                      (when http-url
                        {:http-url http-url
                         :http-parameters http-parameters})))}
      (when ws-url
        {::internals/connect-ws [instance-name ws-url]})))))

(re-frame/reg-event-fx
 ::destroy
 interceptors
 (fn [{:keys [db instance-name]} _]
   (if-let [subscription-ids (not-empty (-> db :subscriptions keys))]
     {:dispatch-n (for [subscription-id subscription-ids]
                    [::unsubscribe instance-name subscription-id])
      :dispatch [::destroy instance-name]}

     (merge
      {:db destroyed-instance}
      (when-let [ws (get-in db [:websocket :connection])]
        {::internals/disconnect-ws [ws]})))))

(defn init
  ([opts] (init default-instance-name opts))
  ([instance-name opts]
   (re-frame/dispatch [::init instance-name opts])))

(defn destroy
  ([] (destroy default-instance-name))
  ([instance-name]
   (re-frame/dispatch [::destroy instance-name])))
