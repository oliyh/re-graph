(ns re-graph.internals
  (:require [re-frame.core :as re-frame]
            [re-frame.interceptor :refer [->interceptor get-coeffect update-coeffect get-effect assoc-effect]]
            [re-frame.std-interceptors :as rfi]
            [re-graph.logging :as log]
            [re-frame.interop :refer [empty-queue]]
            [clojure.spec.alpha :as s]
            [re-graph.spec :as spec]
            #?@(:cljs [[cljs-http.client :as http]
                       [cljs-http.core :as http-core]]
                :clj  [[re-graph.interop :as interop]])
            #?(:cljs [clojure.core.async :as a])
            #?(:clj [cheshire.core :as json]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  #?(:clj (:import [java.util UUID])))

(def default-instance-id ::default)

(defn cons-interceptor [ctx interceptor]
  (update ctx :queue #(into (into empty-queue [interceptor]) %)))

(defn- encode [obj]
  #?(:cljs (js/JSON.stringify (clj->js obj))
     :clj (json/encode obj)))

(defn- message->data [m]
  #?(:cljs (-> (aget m "data")
               (js/JSON.parse)
               (js->clj :keywordize-keys true))
     :clj (json/decode m keyword)))

(defn generate-id []
  #?(:cljs (.substr (.toString (Math/random) 36) 2 8)
     :clj (str (UUID/randomUUID))))

(defn deep-merge [a b]
  (merge-with
   (fn [a b]
     (if (every? map? [a b])
       (deep-merge a b)
       b))
   a b))

(defn- build-impl [impl]
  (if (fn? impl)
    (impl)
    impl))

(def instantiate-impl
  (->interceptor
   :id ::instantiate-impl
   :before (fn [ctx]
             (let [db (get-coeffect ctx :db)
                   http-impl (get-in db [:http :impl])
                   ws-impl (get-in db [:ws :impl])]
               (-> (assoc ctx
                          ::http-impl http-impl
                          ::ws-impl ws-impl)
                   (update-coeffect :db (fn [db]
                                          (cond-> db
                                            http-impl (update-in [:http :impl] build-impl)
                                            ws-impl (update-in [:ws :impl] build-impl)))))))
   :after (fn [ctx]
            (let [{::keys [http-impl ws-impl]} ctx
                  db-effect (get-effect ctx :db)]
              (cond-> (dissoc ctx ::http-impl ::ws-impl)
                db-effect (assoc-effect :db (cond-> db-effect
                                              http-impl (assoc-in [:http :impl] http-impl)
                                              ws-impl (assoc-in [:ws :impl] ws-impl))))))))

(def select-instance
  (->interceptor
   :id ::select-instance
   :before (fn [ctx]
             (let [re-graph (:re-graph (get-coeffect ctx :db))
                   instance-id (:instance-id (get-coeffect ctx :event) default-instance-id)
                   instance (get re-graph instance-id)]
               (if instance
                 (-> ctx
                     (update-coeffect :event assoc :instance-id instance-id)
                     (cons-interceptor (rfi/path :re-graph instance-id)))
                 (do (log/error "No re-graph instance found for instance-id" instance-id " - have you initialised re-graph properly?"
                                "Handling event" (get-coeffect ctx :original-event))
                     ctx))))))

(defn assert-spec [spec]
  (->interceptor
   :id ::assert-spec
   :before (fn [ctx]
             (s/assert spec (get-coeffect ctx :event))
             ctx)))

(defn interceptors
  ([spec] (into (interceptors) [(assert-spec spec)]))
  ([] [re-frame/unwrap select-instance instantiate-impl]))

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
  (let [f (fn [errors] (mapv (fn [error] (update-in error [:extensions :status] #(or % status))) errors))
        default-errors {:errors [{:message "The HTTP call failed."
                                  :extensions {:status status}}]}]
    (cond
      (valid-graphql-errors? response) (update response :errors f)
      (map? response) (merge response default-errors)
      :else default-errors)))

(re-frame/reg-event-fx
 ::http-complete
 (interceptors)
 (fn [{:keys [db]} {:keys [legacy? id response]}]
   (let [callback (get-in db [:http :requests id :callback])]
     {:db       (-> db
                    (update :subscriptions dissoc id)
                    (update-in [:http :requests] dissoc id))
      :dispatch (if (and legacy? ;; enforce legacy behaviour for deprecated api
                         (not= ::callback (first callback)))
                  (conj callback response)
                  (update callback 1 assoc :response response))})))

(re-frame/reg-fx
 ::call-abort
 (fn [abort-fn]
   (abort-fn)))

(re-frame/reg-event-db
 ::register-abort
 (interceptors)
 (fn [db {:keys [id abort-fn]}]
   (assoc-in db [:http :requests id :abort] abort-fn)))

(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307})

(re-frame/reg-fx
 ::send-http
 (fn [{:keys [event url request payload]}]
   #?(:cljs (let [response-chan (http/post url (assoc request :json-params payload))]
              (re-frame/dispatch [::register-abort (assoc event :abort-fn #(http-core/abort! response-chan))])

              (go (let [{:keys [status body error-code]} (a/<! response-chan)]
                    (re-frame/dispatch [::http-complete
                                        (assoc event :response (if (= :no-error error-code)
                                                                 body
                                                                 (insert-http-status body status)))]))))

      :clj (let [future (interop/send-http url
                                           request
                                           (encode payload)
                                           (fn [{:keys [status body]}]
                                             (re-frame/dispatch [::http-complete
                                                                 (assoc event :response (if (unexceptional-status? status)
                                                                                          body
                                                                                          (insert-http-status body status)))]))
                                           (fn [exception]
                                             (let [{:keys [status body]} (ex-data exception)]
                                               (re-frame/dispatch [::http-complete (assoc event :response (insert-http-status body status))]))))]
             (re-frame/dispatch [::register-abort (assoc event :abort-fn #(.cancel future))])))))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   (log/debug "Send ws" websocket payload)
   #?(:cljs (.send websocket (encode payload))
      :clj (interop/send-ws websocket (encode payload)))))

(re-frame/reg-fx
 ::call-callback
 (fn [[callback-fn payload]]
   (callback-fn payload)))

(re-frame/reg-event-fx
 ::callback
 [re-frame/unwrap]
 (fn [_ {:keys [callback-fn response]}]
   {::call-callback [callback-fn response]}))

(re-frame/reg-event-fx
 ::on-ws-data
 (interceptors)
 (fn [{:keys [db]} {:keys [id payload]}]
   (let [subscription (get-in db [:subscriptions (name id)])]
     (if-let [callback (:callback subscription)]
       (if (and (:legacy? subscription)
                (not= ::callback (first callback)))
         {:dispatch (conj callback payload)}
         {:dispatch (update callback 1 assoc :response payload)})
       (log/warn "No callback found for subscription" id)))))

(re-frame/reg-event-db
 ::on-ws-complete
 (interceptors)
 (fn [db {:keys [id]}]
   (update-in db [:subscriptions] dissoc (name id))))

(re-frame/reg-event-fx
 ::connection-init
 (interceptors)
 (fn [{:keys [db]} _]
    (let [ws (get-in db [:ws :connection])
          payload (get-in db [:ws :connection-init-payload])]
      (when payload
        {::send-ws [ws {:type "connection_init"
                        :payload payload}]}))))

(re-frame/reg-event-fx
 ::on-ws-open
 (interceptors)
 (fn [{:keys [db]} {:keys [instance-id websocket]}]
   (merge
    {:db (update db :ws
                    assoc
                    :connection websocket
                    :ready? true
                    :queue [])}
    (let [resume? (get-in db [:ws :resume-subscriptions?])
          subscriptions (when resume? (->> db :subscriptions vals (map :event)))
          queue (get-in db [:ws :queue])
          to-send (concat [[::connection-init {:instance-id instance-id}]]
                          subscriptions
                          queue)]
      {:dispatch-n (vec to-send)}))))

(defn- deactivate-subscriptions [subscriptions]
  (reduce-kv (fn [subs sub-id sub]
               (assoc subs sub-id (assoc sub :active? false)))
             {}
             subscriptions))

(re-frame/reg-event-fx
 ::on-ws-close
 (interceptors)
 (fn [{:keys [db]} {:keys [instance-id]}]
   (merge
    {:db (let [new-db (-> db
                          (assoc-in [:ws :ready?] false)
                          (update :subscriptions deactivate-subscriptions))]
           new-db)}
    (when-let [reconnect-timeout (get-in db [:ws :reconnect-timeout])]
      {:dispatch-later [{:ms reconnect-timeout
                         :dispatch [::reconnect-ws {:instance-id instance-id}]}]}))))

(defn- on-ws-message [instance-id]
  (fn [m]
    (try
      (let [{:keys [type id payload]} (message->data m)]
        (condp = type
          "data"
          (re-frame/dispatch [::on-ws-data {:instance-id instance-id
                                            :id          id
                                            :payload     payload}])

          "complete"
          (re-frame/dispatch [::on-ws-complete {:instance-id instance-id
                                                :id          id}])

          "error"
          (re-frame/dispatch [::on-ws-data {:instance-id instance-id
                                            :id          id
                                            :payload     {:errors payload}}])

          (log/debug "Ignoring graphql-ws event " instance-id " - " type)))
      (catch #?(:clj Exception :cljs js/Object) e
        (log/error e "Failed to handle graphql-ws event " instance-id " - " m)))))

(defn- on-open
  ([instance-id]
   (fn [websocket]
     ((on-open instance-id websocket))))
  ([instance-id websocket]
   (fn []
     (log/info "opened ws" instance-id websocket)
     (re-frame/dispatch [::on-ws-open {:instance-id instance-id
                                       :websocket websocket}]))))

(defn- on-close [instance-id]
  (fn [& _args]
    (re-frame/dispatch [::on-ws-close {:instance-id instance-id}])))

(defn- on-error [instance-id]
  (fn [e]
    (log/warn "GraphQL websocket error" instance-id e)))

(re-frame/reg-event-fx
 ::reconnect-ws
 (interceptors)
 (fn [{:keys [db]} {:keys [instance-id]}]
   (when-not (get-in db [:ws :ready?])
     {::connect-ws [instance-id (:ws db)]})))

(re-frame/reg-fx
  ::connect-ws
  (fn [[instance-id {:keys [url sub-protocol #?(:clj impl)]}]]
    #?(:cljs (let [ws (cond
                       (nil? sub-protocol)
                       (js/WebSocket. url)
                       :else ;; non-nil sub protocol
                       (js/WebSocket. url sub-protocol))]
              (aset ws "onmessage" (on-ws-message instance-id))
              (aset ws "onopen" (on-open instance-id ws))
              (aset ws "onclose" (on-close instance-id))
              (aset ws "onerror" (on-error instance-id)))
       :clj  (interop/create-ws url (merge (build-impl impl)
                                           {:on-open      (on-open instance-id)
                                            :on-message   (on-ws-message instance-id)
                                            :on-close     (on-close instance-id)
                                            :on-error     (on-error instance-id)
                                            :subprotocols [sub-protocol]})))))

(re-frame/reg-fx
 ::disconnect-ws
 (fn [[ws]]
   #?(:cljs (.close ws)
      :clj (interop/close-ws ws))))

(defn default-url
  [protocol path]
  #?(:cljs
          (when (and (exists? js/window) (exists? (.-location js/window)))
            (let [host-and-port (.-host js/window.location)
                  ssl? (re-find #"^https" (.-origin js/window.location))]
              (str protocol (if ssl? "s" "") "://" host-and-port "/" path)))
     :clj
          (str protocol "://localhost/" path)))

(def ws-default-options
  {:url (default-url "ws" "graphql-ws")
   :sub-protocol "graphql-ws"
   :reconnect-timeout 5000
   :resume-subscriptions? true
   :connection-init-payload {}
   :impl {}
   :supported-operations #{:subscribe :mutate :query}})

(def ws-initial-state
  {:ready? false
   :queue []
   :connection nil})

(defn ws-options
  [{:keys [ws] :or {ws {}} :as _options}]
  (when ws
    (let [{:keys [url] :as ws-options} (merge ws-default-options ws ws-initial-state)]
      (when url
        {:ws ws-options}))))

(def http-default-options
  {:url (default-url "http" "graphql")
   :supported-operations #{:mutate :query}
   :impl {}})

(def http-initial-state
  {:requests {}})

(defn http-options
  [{:keys [http] :or {http {}}}]
  (when http
    (let [{:keys [url] :as http-options} (merge http-default-options http http-initial-state)]
      (when url
        {:http http-options}))))

#?(:clj
   (defn sync-wrapper
     "Wraps the given function to allow the GraphQL result to be returned
      synchronously. Will return a GraphQL error response if no response is
      received before the timeout (default 3000ms) expires. Will throw if the
      call returns an exception."
     [f {:keys [timeout]
         :or {timeout 3000}
         :as opts}]
     (let [p        (promise)
           callback (fn [result] (deliver p result))]
       (f (assoc opts :callback callback))

       ;; explicit timeout to avoid unreliable aborts from underlying implementations
       (let [result (deref p timeout ::timeout)]
         (if (= ::timeout result)
           {:errors [{:message "re-graph did not receive response from server"
                      :opts opts}]}
           result)))))

#?(:clj
   (s/fdef sync-wrapper :args (s/cat :fn fn? :opts ::spec/sync-operation)))
