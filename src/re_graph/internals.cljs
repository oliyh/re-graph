(ns re-graph.internals
  (:require [re-frame.core :as re-frame]
            [re-frame.interceptor :refer [->interceptor get-coeffect assoc-coeffect update-coeffect enqueue]]
            [re-frame.std-interceptors :as rfi]
            [re-frame.interop :refer [empty-queue]]
            [cljs-http.client :as http]
            [cljs.core.async :as a])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- cons-interceptor [ctx interceptor]
  (update ctx :queue #(into (into empty-queue [interceptor]) %)))

(def re-graph-instance
  (->interceptor
   :id ::instance
   :before (fn [ctx]
             (let [re-graph  (:re-graph (get-coeffect ctx :db))
                   provided-instance-name (first (get-coeffect ctx :event))
                   instance-name (if (contains? re-graph provided-instance-name) provided-instance-name :default)
                   instance (get re-graph instance-name)]
               (if instance
                 (cond-> ctx
                   :always (assoc-coeffect :instance instance)
                   :always (assoc-coeffect :instance-name instance-name)
                   :always (cons-interceptor (rfi/path :re-graph instance-name))
                   (= provided-instance-name instance-name) (update-coeffect :event subvec 1))

                 (do (js/console.error "No default instance of re-graph found but no valid instance name was provided. Valid instance names are:" (keys re-graph)
                                       "but was provided with" provided-instance-name
                                       "handling event" (first (get-coeffect ctx ::rfi/untrimmed-event)))
                     ctx))))))

(def interceptors
  [re-frame/trim-v re-graph-instance])

(defn- valid-graphql-errors?
  "Validates that response has a valid GraphQL errors map"
  [response]
  (and (map? response)
       (vector? (:errors response))
       (seq (:errors response))
       (every? map? (:errors response))))

(defn- insert-http-status
  "Inserts the HTTP status into the response for 3 conditions:
   1. Response contains a valid GraphQL errors map: update the map with HTTP status
   2. Response is a map but does not contain a valid errors map: merge in default errors
   3. Response is anything else: return default errors map"
  [response status]
  (let [f (fn [errors] (mapv #(assoc-in % [:extensions :status] status) errors))
        default-errors {:errors [{:message "The HTTP call failed."
                                  :extensions {:status status}}]}]
    (cond
      (valid-graphql-errors? response) (update response :errors f)
      (map? response) (merge response default-errors)
      :else default-errors)))

(re-frame/reg-fx
 ::send-http
 (fn [[http-url {:keys [request payload]} callback-fn]]
   (go (let [response (a/<! (http/post http-url (assoc request :json-params payload)))
             {:keys [status error-code]} response]
         (if (= :no-error error-code)
           (callback-fn (:body response))
           (callback-fn (insert-http-status (:body response) status)))))))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   (.send websocket (js/JSON.stringify (clj->js payload)))))

(re-frame/reg-fx
 ::call-callback
 (fn [[callback-fn payload]]
   (callback-fn payload)))

(re-frame/reg-event-fx
 ::callback
 interceptors
 (fn [_ [callback-fn payload]]
   {::call-callback [callback-fn payload]}))

(re-frame/reg-event-fx
 ::on-ws-data
 interceptors
 (fn [{:keys [db] :as cofx} [subscription-id payload :as event]]
   (js/console.log "ws data cofx" cofx event)
   (if-let [callback-event (get-in db [:subscriptions (name subscription-id) :callback])]
     {:dispatch (conj callback-event payload)}
     (js/console.debug "No callback-event found for subscription" subscription-id))))

(re-frame/reg-event-db
 ::on-ws-complete
 interceptors
 (fn [db [subscription-id]]
   (update-in db [:subscriptions] dissoc (name subscription-id))))

(re-frame/reg-event-fx
 ::connection-init
 interceptors
  (fn [{:keys [db]} _]
    (let [ws (get-in db [:websocket :connection])
          payload (get-in db [:websocket :connection-init-payload])]
      (when payload
        {::send-ws [ws {:type "connection_init"
                        :payload payload}]}))))

(re-frame/reg-event-fx
 ::on-ws-open
 interceptors
 (fn [{:keys [db instance-name]} [ws]]
   (merge
    {:db (update db :websocket
                    assoc
                    :connection ws
                    :ready? true
                    :queue [])}

    (let [resume? (get-in db [:websocket :resume-subscriptions?])
          subscriptions (when resume? (->> db :subscriptions vals (map :event)))
          queue (get-in db [:websocket :queue])
          to-send (concat [[::connection-init instance-name]] subscriptions queue)]
      (js/console.log "To send:" to-send)
      {:dispatch-n to-send}))))

(defn- deactivate-subscriptions [subscriptions]
  (reduce-kv (fn [subs sub-id sub]
               (assoc subs sub-id (assoc sub :active? false)))
             {}
             subscriptions))

(re-frame/reg-event-fx
 ::on-ws-close
 interceptors
 (fn [{:keys [db instance-name]}]
   (merge
    {:db (-> db
             (assoc-in [:websocket :ready?] false)
             (update :subscriptions deactivate-subscriptions))}
    (when-let [reconnect-timeout (get-in db [:websocket :reconnect-timeout])]
      {:dispatch-later [{:ms reconnect-timeout
                         :dispatch [::reconnect-ws instance-name]}]}))))

(defn- on-ws-message [instance-name]
  (fn [m]
    (let [data (js/JSON.parse (aget m "data"))]
      (condp = (aget data "type")
        "data"
        (re-frame/dispatch [::on-ws-data instance-name (aget data "id") (js->clj (aget data "payload") :keywordize-keys true)])

        "complete"
        (re-frame/dispatch [::on-ws-complete instance-name (aget data "id")])

        "error"
        (js/console.warn (str "GraphQL error for " instance-name " - " (aget data "id") ": " (aget data "payload" "message")))

        (js/console.debug "Ignoring graphql-ws event " instance-name " - " (aget data "type"))))))

(defn- on-open [instance-name ws]
  (fn [e]
    (re-frame/dispatch [::on-ws-open instance-name ws])))

(defn- on-close [instance-name]
  (fn [e]
    (re-frame/dispatch [::on-ws-close instance-name])))

(defn- on-error [instance-name]
  (fn [e]
    (js/console.warn "GraphQL websocket error" instance-name e)))

(re-frame/reg-event-fx
 ::reconnect-ws
 interceptors
 (fn [{:keys [db instance-name]}]
   (when-not (get-in db [:websocket :ready?])
     {::connect-ws [instance-name (get-in db [:websocket :url])]})))

(re-frame/reg-fx
 ::connect-ws
 (fn [[instance-name ws-url]]
   (let [ws (js/WebSocket. ws-url "graphql-ws")]
     (aset ws "onmessage" (on-ws-message instance-name))
     (aset ws "onopen" (on-open instance-name ws))
     (aset ws "onclose" (on-close instance-name))
     (aset ws "onerror" (on-error instance-name)))))

(re-frame/reg-fx
 ::disconnect-ws
 (fn [[ws]]
   (.close ws)))

(defn default-ws-url []
  (when (exists? (.-location js/window))
    (let [host-and-port (.-host js/window.location)
          ssl? (re-find #"^https" (.-origin js/window.location))]
      (str (if ssl? "wss" "ws") "://" host-and-port "/graphql-ws"))))

(defn generate-query-id []
  (.substr (.toString (js/Math.random) 36) 2 8))
