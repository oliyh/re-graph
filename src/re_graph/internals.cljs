(ns re-graph.internals
  (:require [re-frame.core :as re-frame]
            [cljs-http.client :as http]
            [cljs.core.async :as a])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(re-frame/reg-fx
 ::send-http
 (fn [[http-url {:keys [payload]} callback-fn]]
   (go (let [response (a/<! (http/post http-url {:json-params payload}))]
         (callback-fn (:body response))))))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   (.send websocket (js/JSON.stringify (clj->js payload)))))

(re-frame/reg-event-fx
 ::on-ws-data
 (fn [{:keys [db]} [_ subscription-id payload]]
   (if-let [callback-event (get-in db [:re-graph :subscriptions (name subscription-id) :callback])]
     {:dispatch (conj callback-event payload)}
     (js/console.debug "No callback-event found for subscription" subscription-id))))

(re-frame/reg-event-db
 ::on-ws-complete
 (fn [db [_ subscription-id]]
   (update-in db [:re-graph :subscriptions] dissoc (name subscription-id))))

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
   (merge
    {:db (assoc-in db [:re-graph :websocket :ready?] false)}
    (when-let [reconnect-timeout (get-in db [:re-graph :websocket :reconnect-timeout])]
      {:dispatch-later [{:ms reconnect-timeout
                         :dispatch [::reconnect-ws]}]}))))

(defn- on-ws-message [m]
  (let [data (js/JSON.parse (.-data m))]
    (condp = (.-type data)
      "data"
      (re-frame/dispatch [::on-ws-data (.-id data) (js->clj (.-payload data) :keywordize-keys true)])

      "complete"
      (re-frame/dispatch [::on-ws-complete (.-id data)])

      "error"
      (js/console.warn (str "GraphQL error for " (.-id data) ": " (.. data -payload -message)))

      (js/console.debug "Ignoring graphql-ws event" (.-type data)))))

(defn- on-open [ws]
  (fn [e]
    (re-frame/dispatch [::on-ws-open ws])))

(defn- on-close [e]
  (re-frame/dispatch [::on-ws-close]))

(defn- on-error [e]
  (js/console.warn "GraphQL websocket error" e))

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
     (aset ws "onclose" on-close)
     (aset ws "onerror" on-error))))

(defn default-ws-url []
  (let [host-and-port (.-host js/window.location)
        ssl? (re-find #"^https" (.-origin js/window.location))]
    (str (if ssl? "wss" "ws") "://" host-and-port "/graphql-ws")))

(defn generate-query-id []
  (.substr (.toString (js/Math.random) 36) 2 8))
