(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals]))

(defn default-request-fn [_] {})

(defn request-fn
      "Retrieves the request-fn from db (or returns a default creating an 'empty' request)."
      [db]
  (get-in db [:re-graph :request-fn]))

(re-frame/reg-event-fx
 ::mutate
 (fn [{:keys [db]} [_ query variables callback-event :as event]]
   (cond
     (get-in db [:re-graph :websocket :ready?])
     (let [query-id (internals/generate-query-id)]
       {:db (assoc-in db [:re-graph :subscriptions query-id] {:callback callback-event})
        ::internals/send-ws [(get-in db [:re-graph :websocket :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query (str "mutation " query)
                                        :variables variables}}]})

     (get-in db [:re-graph :websocket])
     {:db (update-in db [:re-graph :websocket :queue] conj event)}

     :else
     {::internals/send-http [(get-in db [:re-graph :http-url])
                             {:request ((request-fn db) db)
                              :payload {:query (str "mutation " query)
                                        :variables variables}}
                             (fn [payload]
                               (re-frame/dispatch (conj callback-event payload)))]})))

(defn mutate [query variables callback-fn]
  (re-frame/dispatch [::mutate query variables [::internals/callback callback-fn]]))

(re-frame/reg-event-fx
 ::query
 (fn [{:keys [db]} [_ query variables callback-event :as event]]
   (cond
     (get-in db [:re-graph :websocket :ready?])
     (let [query-id (internals/generate-query-id)]
       {:db (assoc-in db [:re-graph :subscriptions query-id] {:callback callback-event})
        ::internals/send-ws [(get-in db [:re-graph :websocket :connection])
                             {:id query-id
                              :type "start"
                              :payload {:query (str "query " query)
                                        :variables variables}}]})

     (get-in db [:re-graph :websocket])
     {:db (update-in db [:re-graph :websocket :queue] conj event)}

     :else
     {::internals/send-http [(get-in db [:re-graph :http-url])
                             {:request ((request-fn db) db)
                              :payload {:query (str "query " query)
                                        :variables variables}}
                             (fn [payload]
                               (re-frame/dispatch (conj callback-event payload)))]})))

(defn query [query variables callback-fn]
  (re-frame/dispatch [::query query variables [::internals/callback callback-fn]]))

(re-frame/reg-event-fx
 ::subscribe
 (fn [{:keys [db]} [_ subscription-id query variables callback-event :as event]]
   (cond
     (get-in db [:re-graph :websocket :ready?])
     {:db (assoc-in db [:re-graph :subscriptions (name subscription-id)] {:callback callback-event
                                                                          :event event})
      ::internals/send-ws [(get-in db [:re-graph :websocket :connection])
                           {:id (name subscription-id)
                            :type "start"
                            :payload {:query (str "subscription " query)
                                      :variables variables}}]}

     (get-in db [:re-graph :websocket])
     {:db (update-in db [:re-graph :websocket :queue] conj event)}

     :else
     (js/console.error "Websocket is not enabled, subscriptions are not possible. Please check your re-graph configuration"))))

(defn subscribe [subscription-id query variables callback-fn]
  (re-frame/dispatch [::subscribe subscription-id query variables [::internals/callback callback-fn]]))

(re-frame/reg-event-fx
 ::unsubscribe
 (fn [{:keys [db]} [_ subscription-id]]
   (if (get-in db [:re-graph :websocket :ready?])
     {:db (update-in db [:re-graph :subscriptions] dissoc (name subscription-id))
      ::internals/send-ws [(get-in db [:re-graph :websocket :connection])
                           {:id (name subscription-id)
                            :type "stop"}]}

     {:db (update-in db [:re-graph :websocket :queue] conj [::unsubscribe subscription-id])})))

(defn unsubscribe [subscription-id]
  (re-frame/dispatch [::unsubscribe subscription-id]))

(re-frame/reg-event-fx
 ::init
 (fn [{:keys [db]} [_ {:keys [ws-url http-url request-fn ws-reconnect-timeout resume-subscriptions?]
                       :or {ws-url (internals/default-ws-url)
                            request-fn default-request-fn
                            http-url "/graphql"
                            ws-reconnect-timeout 5000
                            resume-subscriptions? true}}]]

   (merge
    {:db (assoc db :re-graph (merge
                              (when ws-url
                                {:websocket {:url ws-url
                                             :ready? false
                                             :queue []
                                             :reconnect-timeout ws-reconnect-timeout
                                             :resume-subscriptions? resume-subscriptions?}})
                              (when http-url
                                {:http-url http-url
                                 :request-fn request-fn})))}
    (when ws-url
      {::internals/connect-ws [ws-url]}))))

(defn init [& [args]]
  (re-frame/dispatch [::init args]))
