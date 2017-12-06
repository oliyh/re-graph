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
 (fn [{:keys [db]} [_ subscription-id query variables callback-event :as event]]
   (if (get-in db [:re-graph :websocket :ready?])
     {:db (assoc-in db [:re-graph :subscriptions (name subscription-id)] {:callback callback-event})
      ::send-ws [(get-in db [:re-graph :websocket :connection])
                 {:id (name subscription-id)
                  :type "start"
                  :payload {:query (str "subscription " query)
                            :variables variables}}]}

     {:db (update-in db [:re-graph :websocket :queue] conj event)})))

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
 (fn [{:keys [db]} [_ ws]]
   (merge
    {:db (update-in db [:re-graph :websocket]
                    assoc
                    :connection ws
                    :ready? true
                    :queue [])}
    (when-let [queue (not-empty (get-in db [:re-graph :websocket :queue]))]
      {:dispatch-n queue}))))

(re-frame/reg-event-fx
 ::on-ws-close
 (fn [{:keys [db]}]
   {:db (assoc-in db [:re-graph :websocket :ready?] false)
    :dispatch-later [{:ms (get-in db [:re-graph :websocket :reconnect-timeout])
                      :dispatch [::reconnect-ws]}]}))

(defn- on-ws-message [m]
  (let [data (js/JSON.parse (.-data m))]
    (condp = (.-type data)
      "data"
      (re-frame/dispatch [::on-ws-data (.-id data) (js->clj (.-payload data) :keywordize-keys true)])

      (js/console.debug "Ignoring graphql-ws event" (.-type data)))))

(defn- on-open [ws]
  (fn [e]
    (re-frame/dispatch [::on-ws-open ws])))

(defn- on-close [e]
  (re-frame/dispatch [::on-ws-close]))

(re-frame/reg-event-fx
 ::reconnect-ws
 (fn [{:keys [db]}]
   (when-not (get-in db [:re-graph :websocket :ready?])
     {::connect-ws [(get-in db [:re-graph :websocket :url])]})))

(re-frame/reg-fx
 ::connect-ws
 (fn [[ws-url]]
   (let [ws (js/WebSocket. ws-url "graphql-ws")]
     (aset ws "onmessage" on-ws-message)
     (aset ws "onopen" (on-open ws))
     (aset ws "onclose" on-close))))

(defn- default-ws-url []
  (let [host-and-port (.-host js/window.location)
        ssl? (re-find #"^https" (.-origin js/window.location))]
    (str (if ssl? "wss" "ws") "://" host-and-port "/graphql-ws")))

(re-frame/reg-event-fx
 ::init
 (fn [{:keys [db]} [_ {:keys [ws-url http-url ws-reconnect-timeout]
                       :or {ws-url (default-ws-url)
                            ws-reconnect-timeout 5000}}]]

   {:db (assoc db :re-graph {:websocket {:url ws-url
                                         :ready? false
                                         :queue []
                                         :reconnect-timeout ws-reconnect-timeout}
                             :http-url (or http-url "/graphql")})
    ::connect-ws [ws-url]}))
