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
                 callback-event]}))

(re-frame/reg-fx
 ::send-http
 (fn [[http-url {:keys [payload]} callback-event]]
   (go (let [response (a/<! (http/post http-url {:json-params payload}))]
         (re-frame/dispatch (conj callback-event (get-in response [:body :data])))))))

(re-frame/reg-event-fx
 ::subscribe
 (fn [{:keys [db]} [_ subscription-id query variables callback-event]]
   {:db (assoc-in db [:re-graph :subscriptions (name subscription-id)] {:callback callback-event})
    ::send-ws [(get-in db [:re-graph :websocket])
               {:id subscription-id
                :type "start"
                :payload {:query (str "subscription " query)
                          :variables variables}}]}))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   (.send websocket (js/JSON.stringify (clj->js payload)))))

(re-frame/reg-event-fx
 ::on-ws-data
 (fn [{:keys [db]} [_ subscription-id payload]]
   (if-let [callback-event (get-in db [:re-graph :subscriptions (name subscription-id) :callback])]
     {:dispatch (conj callback-event (:data payload))}
     (js/console.debug "No callback-event found for subscription" subscription-id))))

(defn- on-ws-message [m]
  (let [data (js/JSON.parse (.-data m))]
    (condp = (.-type data)
      "data"
      (re-frame/dispatch [::on-ws-data (.-id data) (js->clj (.-payload data) :keywordize-keys true)])

      (js/console.debug "Ignoring graphql-ws event" (.-type data)))))

(defn- default-ws-url []
  (let [host-and-port (.-host js/window.location)]
    (str "ws://" host-and-port "/graphql-ws")))

(re-frame/reg-event-db
 ::init
 (fn [{:keys [db]} [_ {:keys [ws-url http-url]}]]
   (let [ws-url (or ws-url (default-ws-url))
         ws (js/WebSocket. ws-url "graphql-ws")]

     (aset ws "on-message" on-ws-message)
     (assoc-in db [:re-graph] {:websocket ws
                               :http-url (or http-url "/graphql")}))))
