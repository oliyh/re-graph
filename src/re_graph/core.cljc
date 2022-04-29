(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals
             :refer [interceptors default-instance-name]]
            [re-graph.logging :as log]
            [clojure.string :as string]))

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [db]} {:keys [query-id query variables callback-event]
                    :or {query-id (internals/generate-query-id)}
                    :as event-payload}]

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
       {:db (update-in db [:ws :queue] conj [::mutate event-payload])}

       :else
       {:db (assoc-in db [:http :requests query-id] {:callback callback-event})
        ::internals/send-http {:url (get-in db [:http :url])
                               :request (get-in db [:http :impl])
                               :payload {:query query
                                         :variables variables}
                               :event (assoc event-payload :query-id query-id)}}))))

(defn mutate
  "Execute a GraphQL mutation. The arguments are:

  [instance-name query-string variables callback]

  If the optional `instance-name` is not provided, the default instance is
  used. The callback function will receive the result of the mutation as its
  sole argument."
  [opts]
  (re-frame/dispatch [::mutate (update opts :callback-event (fn [f] [::internals/callback {:callback-fn f}]))]))

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
 (fn [{:keys [db]} {:keys [query-id query variables callback-event legacy?]
                    :or {query-id (internals/generate-query-id)}
                    :as event-payload}]
   (let [query (str "query " (string/replace query #"^query\s?" ""))
         websocket-supported? (contains? (get-in db [:ws :supported-operations]) :query)]
     (cond
       (or (get-in db [:http :requests query-id])
           (get-in db [:subscriptions query-id]))
       {} ;; duplicate in-flight query

       (and websocket-supported? (get-in db [:ws :ready?]))
       {:db (assoc-in db [:subscriptions query-id] {:callback callback-event
                                                    :legacy? legacy?})
        ::internals/send-ws [(get-in db [:ws :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (and websocket-supported? (:ws db))
       {:db (update-in db [:ws :queue] conj [::query event-payload])}

       :else
       {:db (assoc-in db [:http :requests query-id] {:callback callback-event})
        ::internals/send-http {:url (get-in db [:http :url])
                               :request (get-in db [:http :impl])
                               :payload {:query query
                                         :variables variables}
                               :event (assoc event-payload :query-id query-id)}}))))

(defn query
  "Execute a GraphQL query. The arguments are:

  [instance-name query-string variables callback]

  If the optional `instance-name` is not provided, the default instance is
  used. The callback function will receive the result of the query as its
  sole argument."
  [opts]
  (re-frame/dispatch [::query (update opts :callback-event (fn [f] [::internals/callback {:callback-fn f}]))]))

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

(defn abort [opts]
   (re-frame/dispatch [::abort opts]))

(re-frame/reg-event-fx
 ::subscribe
 interceptors
 (fn [{:keys [db]} {:keys [subscription-id query variables callback-event instance-name legacy?] :as event}]
   (cond
     (get-in db [:subscriptions (name subscription-id) :active?])
     {} ;; duplicate subscription

     (get-in db [:ws :ready?])
     {:db (assoc-in db [:subscriptions (name subscription-id)] {:callback callback-event
                                                                :event [::subscribe event]
                                                                :active? true
                                                                :legacy? legacy?})
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name subscription-id)
                            :type "start"
                            :payload {:query (str "subscription " (string/replace query #"^subscription\s?" ""))
                                      :variables variables}}]}

     (:ws db)
     {:db (update-in db [:ws :queue] conj [::subscribe event])}

     :else
     (log/error
       (str
        "Error creating subscription " subscription-id
        " on instance " instance-name
         ": Websocket is not enabled, subscriptions are not possible. Please check your re-graph configuration")))))

(defn subscribe [opts]
  (re-frame/dispatch [::subscribe (update opts :callback-event (fn [f] [::internals/callback {:callback-fn f}]))]))

(re-frame/reg-event-fx
 ::unsubscribe
 interceptors
 (fn [{:keys [db]} {:keys [subscription-id] :as event}]
   (if (get-in db [:ws :ready?])
     {:db (update db :subscriptions dissoc (name subscription-id))
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name subscription-id)
                            :type "stop"}]}

     {:db (update-in db [:ws :queue] conj [::unsubscribe event])})))

(defn unsubscribe [opts]
  (re-frame/dispatch [::unsubscribe opts]))

(re-frame/reg-event-fx
 ::re-init
 [re-frame/unwrap internals/select-instance]
 (fn [{:keys [db]} opts]
   (let [new-db (internals/deep-merge db opts)]
     (merge {:db new-db}
            (when (get-in new-db [:ws :ready?])
              {:dispatch [::internals/connection-init opts]})))))

(defn re-init [opts]
  (re-frame/dispatch [::re-init opts]))

(re-frame/reg-event-fx
 ::init
 [re-frame/unwrap]
 (fn [{:keys [db]} {:keys [instance-name]
                    :or {instance-name internals/default-instance-name}
                    :as opts}]
   (let [{:keys [ws] :as opts}
         (merge opts
                (internals/ws-options opts)
                (internals/http-options opts))]
     (merge
      {:db (assoc-in db [:re-graph instance-name] opts)}
      (when ws
        {::internals/connect-ws [instance-name ws]})))))

(re-frame/reg-event-fx
 ::destroy
 interceptors
 (fn [{:keys [db]} {:keys [instance-name]}]
   (if-let [subscription-ids (not-empty (-> db :subscriptions keys))]
     {:dispatch-n (for [subscription-id subscription-ids]
                    [::unsubscribe {:instance-name instance-name
                                    :subscription-id subscription-id}])
      :dispatch [::destroy {:instance-name instance-name}]}

     (merge
      {:db (assoc db :destroyed? true)}
      (when-let [ws (get-in db [:ws :connection])]
        {::internals/disconnect-ws [ws]})))))

(defn init [opts]
  (re-frame/dispatch [::init opts]))

(defn destroy [opts]
  (re-frame/dispatch [::destroy opts]))
