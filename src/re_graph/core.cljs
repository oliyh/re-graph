(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [cljs-http.client :as http]
            [cljs.core.async :as a])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(re-frame/reg-event-fx
 ::query
 (fn [{:keys [db]} [_ query variables callback-event]]
   {::send-http [(get-in db [:re-graph :http-url])
                 {:payload {:query (str "query " query)
                            :variables variables}}
                 (fn [payload]
                   (re-frame/dispatch (conj callback-event (:data payload))))]}))

(re-frame/reg-fx
 ::send-http
 (fn [[http-url {:keys [payload]} callback-fn]]
   (go (let [response (a/<! (http/post http-url {:json-params payload}))]
         (callback-fn (:body response))))))

(re-frame/reg-event-fx
 ::subscribe
 (fn [{:keys [db]} [_ subscription-id query variables callback-event]]
   (if (get-in db [:re-graph :websocket :ready?])
     {:db (assoc-in db [:re-graph :subscriptions (name subscription-id)] {:callback callback-event})
      ::send-ws [(get-in db [:re-graph :websocket :connection])
                 {:id (name subscription-id)
                  :type "start"
                  :payload {:query (str "subscription " query)
                            :variables variables}}]}

     {:db (update-in db [:re-graph :websocket :queue] conj [::subscribe subscription-id query variables callback-event])})))

(re-frame/reg-event-fx
 ::unsubscribe
 (fn [{:keys [db]} [_ subscription-id]]
   (if (get-in db [:re-graph :websocket :ready?])
     {:db (update-in db [:re-graph :subscriptions] dissoc (name subscription-id))
      ::send-ws [(get-in db [:re-graph :websocket :connection])
                 {:id (name subscription-id)
                  :type "stop"}]}

     {:db (update-in db [:re-graph :websocket :queue] conj [::unsubscribe subscription-id])})))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   (.send websocket (js/JSON.stringify (clj->js payload)))))

(re-frame/reg-event-fx
 ::on-ws-data
 (fn [{:keys [db]} [_ subscription-id payload]]
   (if-let [callback-event (get-in db [:re-graph :subscriptions (name subscription-id) :callback])]
     {:db db
      :dispatch (conj callback-event (:data payload))}
     (js/console.debug "No callback-event found for subscription" subscription-id))))

(re-frame/reg-event-fx
 ::on-ws-open
 (fn [{:keys [db]}]
   {:db (-> db
            (assoc-in [:re-graph :websocket :ready?] true)
            (assoc-in [:re-graph :websocket :queue] []))
    :dispatch-n (get-in db [:re-graph :websocket :queue])}))

(re-frame/reg-event-fx
 ::on-ws-close
 (fn [{:keys [db]}]
   {:db (assoc-in db [:re-graph :websocket :ready?] false)}))

(defn- on-ws-message [m]
  (let [data (js/JSON.parse (.-data m))]
    (condp = (.-type data)
      "data"
      (re-frame/dispatch [::on-ws-data (.-id data) (js->clj (.-payload data) :keywordize-keys true)])

      (js/console.debug "Ignoring graphql-ws event" (.-type data)))))

(defn- on-open [e]
  (re-frame/dispatch [::on-ws-open]))

(defn- on-close [e]
  (re-frame/dispatch [::on-ws-close]))

(defn- default-ws-url []
  (let [host-and-port (.-host js/window.location)]
    (str "ws://" host-and-port "/graphql-ws")))

(re-frame/reg-event-db
 ::init
 (fn [db [_ {:keys [ws-url http-url]}]]
   (let [ws-url (or ws-url (default-ws-url))
         ws (js/WebSocket. ws-url "graphql-ws")]

     (aset ws "onmessage" on-ws-message)
     (aset ws "onopen" on-open)
     (aset ws "onclose" on-close)
     (assoc db :re-graph {:websocket {:connection ws
                                      :ready? false
                                      :queue []}
                          :http-url (or http-url "/graphql")}))))
