(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals
             :refer [interceptors default-instance-name]]
            [re-graph.logging :as log]
            [clojure.string :as string]))

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [db dispatchable-event instance-name]} [query-id query variables callback-event]]
   (let [query (str "mutation " (string/replace query #"^mutation\s?" ""))
         websocket-supported? (contains? (get-in db [:ws :supported-operations]) :mutate)]
     (cond
       (or (get-in db [:http :requests query-id])
           (get-in db [:subscriptions query-id]))
       {} ;; duplicate in-flight mutation

       (and websocket-supported? (get-in db [:ws :ready?]))
       {:db (assoc-in db [:subscriptions query-id] {:callback callback-event})
        ::internals/send-ws [(get-in db [:ws :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (and websocket-supported? (:ws db))
       {:db (update-in db [:ws :queue] conj dispatchable-event)}

       :else
       {:db (assoc-in db [:http :requests query-id] {:callback callback-event})
        ::internals/send-http [instance-name
                               query-id
                               (get-in db [:http :url])
                               {:request (get-in db [:http :impl])
                                :payload {:query query
                                          :variables variables}}]}))))

(defn mutate
  "Execute a GraphQL mutation. The arguments are:

  [instance-name query-string variables callback]

  If the optional `instance-name` is not provided, the default instance is
  used. The callback function will receive the result of the mutation as its
  sole argument."
  [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::mutate] (conj (vec (butlast args)) [::internals/callback callback-fn])))))

#?(:clj
   (def
     ^{:doc "Executes a mutation synchronously. The arguments are:

             [instance-name query-string variables timeout]

             The `instance-name` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     mutate-sync
     (partial internals/sync-wrapper mutate)))

(re-frame/reg-event-fx
 ::query
 interceptors
 (fn [{:keys [db dispatchable-event instance-name]} [query-id query variables callback-event]]
   (let [query (str "query " (string/replace query #"^query\s?" ""))
         websocket-supported? (contains? (get-in db [:ws :supported-operations]) :query)]
     (cond
       (or (get-in db [:http :requests query-id])
           (get-in db [:subscriptions query-id]))
       {} ;; duplicate in-flight query

       (and websocket-supported? (get-in db [:ws :ready?]))
       {:db (assoc-in db [:subscriptions query-id] {:callback callback-event})
        ::internals/send-ws [(get-in db [:ws :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (and websocket-supported? (:ws db))
       {:db (update-in db [:ws :queue] conj dispatchable-event)}

       :else
       {:db (assoc-in db [:http :requests query-id] {:callback callback-event})
        ::internals/send-http [instance-name
                               query-id
                               (get-in db [:http :url])
                               {:request (get-in db [:http :impl])
                                :payload {:query query
                                          :variables variables}}]}))))

(defn query
  "Execute a GraphQL query. The arguments are:

  [instance-name query-string variables callback]

  If the optional `instance-name` is not provided, the default instance is
  used. The callback function will receive the result of the query as its
  sole argument."
  [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::query] (conj (vec (butlast args)) [::internals/callback callback-fn])))))

#?(:clj
   (def
     ^{:doc "Executes a query synchronously. The arguments are:

             [instance-name query-string variables timeout]

             The `instance-name` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     query-sync
     (partial internals/sync-wrapper query)))

(re-frame/reg-event-fx
 ::abort
 interceptors
 (fn [{:keys [db]} [query-id]]
   (merge
     {:db (-> db
              (update :subscriptions dissoc query-id)
              (update-in [:http :requests] dissoc query-id))}
    (when-let [abort-fn (get-in db [:http :requests query-id :abort])]
      {::internals/call-abort abort-fn}) )))

(defn abort
  ([query-id] (abort default-instance-name query-id))
  ([instance-name query-id]
   (re-frame/dispatch [::abort instance-name query-id])))

(re-frame/reg-event-fx
 ::subscribe
 interceptors
 (fn [{:keys [db instance-name dispatchable-event]} [subscription-id query variables callback-event]]
   (cond
     (get-in db [:subscriptions (name subscription-id) :active?])
     {} ;; duplicate subscription

     (get-in db [:ws :ready?])
     {:db (assoc-in db [:subscriptions (name subscription-id)] {:callback callback-event
                                                                :event dispatchable-event
                                                                :active? true})
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name subscription-id)
                            :type "start"
                            :payload {:query (str "subscription " (string/replace query #"^subscription\s?" ""))
                                      :variables variables}}]}

     (:ws db)
     {:db (update-in db [:ws :queue] conj dispatchable-event)}

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
 (fn [{:keys [db instance-name]} [subscription-id]]
   (if (get-in db [:ws :ready?])
     {:db (update db :subscriptions dissoc (name subscription-id))
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name subscription-id)
                            :type "stop"}]}

     {:db (update-in db [:ws :queue] conj [::unsubscribe instance-name subscription-id])})))

(defn unsubscribe
  ([subscription-id] (unsubscribe default-instance-name subscription-id))
  ([instance-name subscription-id]
   (re-frame/dispatch [::unsubscribe instance-name subscription-id])))

(re-frame/reg-event-fx
 ::re-init
 [re-frame/trim-v internals/re-graph-instance]
 (fn [{:keys [db instance-name]} [opts]]
   (let [new-db (internals/deep-merge db opts)]
     (merge {:db new-db}
            (when (get-in new-db [:ws :ready?])
              {:dispatch [::internals/connection-init instance-name]})))))

(defn re-init
  ([opts] (re-init default-instance-name opts))
  ([instance-name opts]
   (re-frame/dispatch [::re-init instance-name opts])))

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
         ws-options (internals/ws-options opts)
         http-options (internals/http-options opts)]

     (merge
      {:db (assoc-in db [:re-graph instance-name]
                     (merge ws-options http-options))}
      (when ws-options
        {::internals/connect-ws [instance-name ws-options]})))))

(re-frame/reg-event-fx
 ::destroy
 interceptors
 (fn [{:keys [db instance-name]} _]
   (if-let [subscription-ids (not-empty (-> db :subscriptions keys))]
     {:dispatch-n (for [subscription-id subscription-ids]
                    [::unsubscribe instance-name subscription-id])
      :dispatch [::destroy instance-name]}

     (merge
      {:db (assoc db :destroyed? true)}
      (when-let [ws (get-in db [:ws :connection])]
        {::internals/disconnect-ws [ws]})))))

(defn init
  ([opts] (init default-instance-name opts))
  ([instance-name opts]
   (re-frame/dispatch [::init instance-name opts])))

(defn destroy
  ([] (destroy default-instance-name))
  ([instance-name]
   (re-frame/dispatch [::destroy instance-name])))
