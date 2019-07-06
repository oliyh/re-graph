(ns re-graph.internals
  (:require [re-frame.core :as re-frame]
            [re-frame.interceptor :refer [->interceptor get-coeffect assoc-coeffect update-coeffect enqueue]]
            [re-frame.std-interceptors :as rfi]
            [re-frame.interop :refer [empty-queue]]
            #?(:cljs [cljs-http.client :as http]
               :clj  [clj-http.client :as http])
            #?(:cljs [clojure.core.async :as a]
               :clj [clojure.core.async :refer [go] :as a])
            #?(:clj [gniazdo.core :as ws])
            #?(:clj [cheshire.core :as json]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  #?(:clj (:import [java.util UUID])))

(def default-instance-name ::default)

(def destroyed-instance ::destroyed-instance)

(defn- cons-interceptor [ctx interceptor]
  (update ctx :queue #(into (into empty-queue [interceptor]) %)))

(def log #?(:cljs js/console.error
            :clj println))

(defn- encode [obj]
  #?(:cljs (js/JSON.stringify (clj->js obj))
     :cljs (json/encode obj)))

(defn- message->data [m]
  #?(:cljs (-> (aget m "data")
               (js/JSON.parse s)
               (js->clj obj :keywordize-keys true))
     :clj (json/decode m keyword)))

(def re-graph-instance
  (->interceptor
   :id ::instance
   :before (fn [ctx]
             (let [re-graph  (:re-graph (get-coeffect ctx :db))
                   event (get-coeffect ctx :event)
                   provided-instance-name (first event)
                   instance-name (if (contains? re-graph provided-instance-name) provided-instance-name default-instance-name)
                   instance (get re-graph instance-name)
                   event-name (first (get-coeffect ctx ::rfi/untrimmed-event))
                   trimmed-event (if (= provided-instance-name instance-name) (subvec event 1) event)]
               (cond
                 (= instance ::destroyed-instance)
                 ctx

                 instance
                 (-> ctx
                     (assoc-coeffect :instance instance)
                     (assoc-coeffect :instance-name instance-name)
                     (assoc-coeffect :dispatchable-event (into [event-name instance-name] trimmed-event))
                     (cons-interceptor (rfi/path :re-graph instance-name))
                     (assoc-coeffect :event trimmed-event))

                 :default
                 (do (log "No default instance of re-graph found but no valid instance name was provided. Valid instance names are:" (keys re-graph)
                          "but was provided with" provided-instance-name
                          "handling event" event-name)
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
   (.send websocket (encode payload))))

(re-frame/reg-fx
 ::call-callback
 (fn [[callback-fn payload]]
   (callback-fn payload)))

(re-frame/reg-event-fx
 ::callback
 (fn [_ [_ callback-fn payload]]
   {::call-callback [callback-fn payload]}))

(re-frame/reg-event-fx
 ::on-ws-data
 interceptors
 (fn [{:keys [db] :as cofx} [subscription-id payload :as event]]
   (if-let [callback-event (get-in db [:subscriptions (name subscription-id) :callback])]
     {:dispatch (conj callback-event payload)}
     (log "No callback-event found for subscription" subscription-id))))

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
      {:dispatch-n to-send}))))

(defn- deactivate-subscriptions [subscriptions]
  (reduce-kv (fn [subs sub-id sub]
               (assoc subs sub-id (assoc sub :active? false)))
             {}
             subscriptions))

(re-frame/reg-event-fx
 ::on-ws-close
 interceptors
 (fn [{:keys [db instance-name]} _]
   (merge
    {:db (let [new-db (-> db
                          (assoc-in [:websocket :ready?] false)
                          (update :subscriptions deactivate-subscriptions))]
           new-db)}
    (when-let [reconnect-timeout (get-in db [:websocket :reconnect-timeout])]
      {:dispatch-later [{:ms reconnect-timeout
                         :dispatch [::reconnect-ws instance-name]}]}))))

(defn- on-ws-message [instance-name]
  (fn [m]
    (let [{:keys [type id payload] :as data} (message->data m)]
      (condp = type
        "data"
        (re-frame/dispatch [::on-ws-data instance-name id payload])

        "complete"
        (re-frame/dispatch [::on-ws-complete instance-name id])

        "error"
        (re-frame/dispatch [::on-ws-data instance-name id {:errors payload}])

        (log "Ignoring graphql-ws event " instance-name " - " type)))))

(defn- on-open
  ([instance-name]
   (fn [ws]
     ((on-open instance-name ws))))
  ([instance-name ws]
   (fn []
     (re-frame/dispatch [::on-ws-open instance-name ws]))))

(defn- on-close [instance-name]
  (fn [& args]
    (re-frame/dispatch [::on-ws-close instance-name])))

(defn- on-error [instance-name]
  (fn [e]
    (log "GraphQL websocket error" instance-name e)))

(re-frame/reg-event-fx
 ::reconnect-ws
 interceptors
 (fn [{:keys [db instance-name]} _]
   (when-not (get-in db [:websocket :ready?])
     {::connect-ws [instance-name (get-in db [:websocket :url])]})))

(re-frame/reg-fx
 ::connect-ws
 (fn [[instance-name ws-url]]
   #?(:cljs (let [ws (js/WebSocket. url name)]
              (aset ws "onmessage" (on-ws-message instance-name))
              (aset ws "onopen" (on-open instance-name ws))
              (aset ws "onclose" (on-close instance-name))
              (aset ws "onerror" (on-error instance-name)))
      :clj (ws/connect ws-url
             :on-receive (on-ws-message instance-name)
             :on-open (on-open instance-name)
             :on-close (on-close instance-name)
             :on-error (on-error instance-name)))))

(re-frame/reg-fx
 ::disconnect-ws
 (fn [[ws]]
   #?(:cljs (.close ws)
      :clj (ws/close ws))))

(defn default-ws-url []
  #?(:cljs
     (when (exists? (.-location js/window))
       (let [host-and-port (.-host js/window.location)
             ssl? (re-find #"^https" (.-origin js/window.location))]
         (str (if ssl? "wss" "ws") "://" host-and-port "/graphql-ws")))
     :clj nil))

(defn generate-query-id []
  #?(:cljs (.substr (.toString (Math/random) 36) 2 8)
     :clj (str (UUID/randomUUID))))
