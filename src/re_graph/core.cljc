(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals
             :refer [interceptors]]
            [re-graph.logging :as log]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [re-graph.spec :as spec]))

;; queries and mutations

(re-frame/reg-event-fx
 ::mutate
 (interceptors ::spec/mutate)
 (fn [{:keys [db]} {:keys [id query variables callback]
                    :or {id (internals/generate-id)}
                    :as event-payload}]

   (let [query (str "mutation " (string/replace query #"^mutation\s?" ""))
         websocket-supported? (contains? (get-in db [:ws :supported-operations]) :mutate)]
     (cond
       (or (get-in db [:http :requests id])
           (get-in db [:subscriptions id]))
       {} ;; duplicate in-flight mutation

       (and websocket-supported? (get-in db [:ws :ready?]))
       {:db (assoc-in db [:subscriptions id] {:callback callback})
        ::internals/send-ws [(get-in db [:ws :connection])
                             {:id id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (and websocket-supported? (:ws db))
       {:db (update-in db [:ws :queue] conj [::mutate event-payload])}

       :else
       {:db (assoc-in db [:http :requests id] {:callback callback})
        ::internals/send-http {:url (get-in db [:http :url])
                               :request (get-in db [:http :impl])
                               :payload {:query query
                                         :variables variables}
                               :event (assoc event-payload :id id)}}))))

(defn mutate
  "Execute a GraphQL mutation. See ::spec/mutate for the arguments"
  [opts]
  (re-frame/dispatch [::mutate (update opts :callback (fn [f] [::internals/callback {:callback-fn f}]))]))

(s/fdef mutate :args (s/cat :opts ::spec/mutate))

#?(:clj
   (def
     ^{:doc "Executes a mutation synchronously.
             Options are per `mutate` with an additional optional `:timeout` specified in milliseconds."}
     mutate-sync
     (partial internals/sync-wrapper mutate)))

(re-frame/reg-event-fx
 ::query
 (interceptors ::spec/query)
 (fn [{:keys [db]} {:keys [id query variables callback legacy?]
                    :or {id (internals/generate-id)}
                    :as event-payload}]

   ;; prepend `query` if it is omitted (and not a fragment declaration)
   (let [query (if (re-find #"^(query|fragment)" query)
                 query
                 (str "query " query))
         websocket-supported? (contains? (get-in db [:ws :supported-operations]) :query)]
     (cond
       (or (get-in db [:http :requests id])
           (get-in db [:subscriptions id]))
       {} ;; duplicate in-flight query

       (and websocket-supported? (get-in db [:ws :ready?]))
       {:db (assoc-in db [:subscriptions id] {:callback callback
                                              :legacy? legacy?})
        ::internals/send-ws [(get-in db [:ws :connection])
                             {:id id
                              :type "start"
                              :payload {:query query
                                        :variables variables}}]}

       (and websocket-supported? (:ws db))
       {:db (update-in db [:ws :queue] conj [::query event-payload])}

       :else
       {:db (assoc-in db [:http :requests id] {:callback callback})
        ::internals/send-http {:url (get-in db [:http :url])
                               :request (get-in db [:http :impl])
                               :payload {:query query
                                         :variables variables}
                               :event (assoc event-payload :id id)}}))))

(defn query
  "Execute a GraphQL query. See ::spec/query for the arguments"
  [opts]
  (re-frame/dispatch [::query (update opts :callback (fn [f] [::internals/callback {:callback-fn f}]))]))

(s/fdef query :args (s/cat :opts ::spec/query))

#?(:clj
   (def
     ^{:doc "Executes a query synchronously.
             Options are per `query` with an additional optional `:timeout` specified in milliseconds."}
     query-sync
     (partial internals/sync-wrapper query)))

(re-frame/reg-event-fx
 ::abort
 (interceptors ::spec/abort)
 (fn [{:keys [db]} {:keys [id]}]
   (merge
    {:db (-> db
             (update :subscriptions dissoc id)
             (update-in [:http :requests] dissoc id))}
    (when-let [abort-fn (get-in db [:http :requests id :abort])]
      {::internals/call-abort abort-fn}) )))

(defn abort
  "Abort a pending query or mutation. See ::spec/abort for the arguments"
  [opts]
   (re-frame/dispatch [::abort opts]))

(s/fdef abort :args (s/cat :opts ::spec/abort))

;; subscriptions

(re-frame/reg-event-fx
 ::subscribe
 (interceptors ::spec/subscribe)
 (fn [{:keys [db]} {:keys [id query variables callback instance-id legacy?] :as event}]
   (cond
     (get-in db [:subscriptions (name id) :active?])
     {} ;; duplicate subscription

     (get-in db [:ws :ready?])
     {:db (assoc-in db [:subscriptions (name id)] {:callback callback
                                                                :event [::subscribe event]
                                                                :active? true
                                                                :legacy? legacy?})
      ;; subscription-query-as-data? supports subscription query format for AWS AppSync
      ::internals/send-ws (let [{:keys [create-payload] :or {create-payload identity}} (get db :ws)
                                query {:query     (str "subscription " (string/replace query #"^subscription\s?" ""))
                                       :variables variables}
                                payload (create-payload query)]
                            [(get-in db [:ws :connection])
                             {:id      (name id)
                              :type    "start"
                              :payload payload}])}

     (:ws db)
     {:db (update-in db [:ws :queue] conj [::subscribe event])}

     :else
     (log/error
       (str
        "Error creating subscription " id
        " on instance " instance-id
         ": Websocket is not enabled, subscriptions are not possible. Please check your re-graph configuration")))))

(defn subscribe
  "Create a GraphQL subscription. See ::spec/subscribe for the arguments"
  [opts]
  (re-frame/dispatch [::subscribe (update opts :callback (fn [f] [::internals/callback {:callback-fn f}]))]))

(s/fdef subscribe :args (s/cat :opts ::spec/subscribe))

(re-frame/reg-event-fx
 ::unsubscribe
 (interceptors ::spec/unsubscribe)
 (fn [{:keys [db]} {:keys [id] :as event}]
   (if (get-in db [:ws :ready?])
     {:db (update db :subscriptions dissoc (name id))
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name id)
                            :type "stop"}]}

     {:db (update-in db [:ws :queue] conj [::unsubscribe event])})))

(defn unsubscribe
  "Cancel an existing GraphQL subscription. See ::spec/unsubscribe for the arguments"
  [opts]
  (re-frame/dispatch [::unsubscribe opts]))

(s/fdef unsubscribe :args (s/cat :opts ::spec/unsubscribe))

;; re-graph lifecycle

(re-frame/reg-event-fx
 ::init
 [re-frame/unwrap (internals/assert-spec ::spec/init)]
 (fn [{:keys [db]} {:keys [instance-id]
                    :or {instance-id internals/default-instance-id}
                    :as opts}]
   (let [{:keys [ws] :as opts}
         (merge opts
                (internals/ws-options opts)
                (internals/http-options opts))]
     (merge
      {:db (assoc-in db [:re-graph instance-id] opts)}
      (when ws
        {::internals/connect-ws [instance-id ws]})))))

(defn init
  "Initialise an instance of re-graph. See ::spec/init for the arguments"
  [opts]
  (re-frame/dispatch [::init opts]))

(s/fdef init :args (s/cat :opts ::spec/init))

(re-frame/reg-event-fx
 ::re-init
 [re-frame/unwrap internals/select-instance (internals/assert-spec ::spec/re-init)]
 (fn [{:keys [db]} opts]
   (let [new-db (internals/deep-merge db opts)]
     (merge {:db new-db}
            (when (get-in new-db [:ws :ready?])
              {:dispatch [::internals/connection-init opts]})))))

(defn re-init
  "Re-initialise an instance of re-graph. See ::spec/re-init for the arguments"
  [opts]
  (re-frame/dispatch [::re-init opts]))

(s/fdef re-init :args (s/cat :opts ::spec/re-init))

(re-frame/reg-event-fx
 ::destroy
 (interceptors ::spec/destroy)
 (fn [{:keys [db]} {:keys [instance-id]}]
   (if-let [ids (not-empty (-> db :subscriptions keys))]
     {:dispatch-n (for [id ids]
                    [::unsubscribe {:instance-id instance-id
                                    :id id}])
      :dispatch [::destroy {:instance-id instance-id}]}

     (merge
      {:db (assoc db :destroyed? true)}
      (when-let [ws (get-in db [:ws :connection])]
        {::internals/disconnect-ws [ws]})))))

(defn destroy
  "Destroy an instance of re-graph. See ::spec/destroy for the arguments"
  [opts]
  (re-frame/dispatch [::destroy opts]))

(s/fdef destroy :args (s/cat :opts ::spec/destroy))
