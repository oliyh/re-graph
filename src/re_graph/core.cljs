(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals :refer [interceptors default-instance-name]]
            [re-frame.std-interceptors :as rfi]
            [clojure.string :as string]))

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [db dispatchable-event]} [query variables callback-event :as event]]
   (let [query (str "mutation " (string/replace query #"^mutation\s?" ""))]
     (cond
       (get-in db [:websocket :ready?])
       (let [query-id (internals/generate-query-id)]
         {:db (assoc-in db [:subscriptions query-id] {:callback callback-event})
          ::internals/send-ws [(get-in db [:websocket :connection])
                               {:id query-id
                                :type "start"
                                :payload {:query query
                                          :variables variables}}]})

       (:websocket db)
       {:db (update-in db [:websocket :queue] conj dispatchable-event)}

       :else
       {::internals/send-http [(:http-url db)
                               {:request (:http-parameters db)
                                :payload {:query query
                                          :variables variables}}
                               (fn [payload]
                                 (re-frame/dispatch (conj callback-event payload)))]}))))

(defn mutate
  ([query variables callback-fn] (query default-instance-name variables callback-fn))
  ([instance-name query variables callback-fn]
   (re-frame/dispatch [::mutate query variables [::internals/callback callback-fn]])))

(re-frame/reg-event-fx
 ::query
 interceptors
 (fn [{:keys [db]} [query variables callback-event :as event]]
   (let [query (str "query " (string/replace query #"^query\s?" ""))]
     (cond
       (get-in db [:websocket :ready?])
       (let [query-id (internals/generate-query-id)]
         {:db (assoc-in db [:subscriptions query-id] {:callback callback-event})
          ::internals/send-ws [(get-in db [:websocket :connection])
                               {:id query-id
                                :type "start"
                                :payload {:query query
                                          :variables variables}}]})

       (get-in db [:websocket])
       {:db (update-in db [:websocket :queue] conj event)}

       :else
       {::internals/send-http [(:http-url db)
                               {:request (:http-parameters db)
                                :payload {:query query
                                          :variables variables}}
                               (fn [payload]
                                 (re-frame/dispatch (conj callback-event payload)))]}))))

(defn query
  ([query variables callback-fn] (query default-instance-name query variables callback-fn))
  ([instance-name query variables callback-fn]
   (re-frame/dispatch [::query query variables [::internals/callback callback-fn]])))

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
     (js/console.error
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
 (fn [{:keys [db]} [_ instance-name {:keys [ws-url http-url http-parameters ws-reconnect-timeout resume-subscriptions? connection-init-payload]
                       :or {ws-url (internals/default-ws-url)
                            http-parameters {}
                            http-url "/graphql"
                            ws-reconnect-timeout 5000
                            connection-init-payload {}
                            resume-subscriptions? true}}]]
   (let [instance-name (or instance-name default-instance-name)]
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
 (fn [{:keys [db instance-name]}]
   (if-let [subscription-ids (not-empty (-> db :subscriptions keys))]
     {:dispatch-n (for [subscription-id subscription-ids]
                    [::unsubscribe instance-name subscription-id])
      :dispatch [::destroy instance-name]}

     (merge
      {:db nil}
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
