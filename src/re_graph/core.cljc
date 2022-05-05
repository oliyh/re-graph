(ns re-graph.core
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals
             :refer [interceptors]]
            [re-graph.logging :as log]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [re-graph.spec :as spec]))

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [db]} {:keys [id query variables callback]
                    :or {id (internals/generate-id)}
                    :as event-payload}]

   (s/assert ::spec/mutate event-payload)

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
     ^{:doc "Executes a mutation synchronously. The arguments are:

             [instance-id query-string variables timeout]

             The `instance-id` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     mutate-sync
     (partial internals/sync-wrapper mutate)))

(re-frame/reg-event-fx
 ::query
 interceptors
 (fn [{:keys [db]} {:keys [id query variables callback legacy?]
                    :or {id (internals/generate-id)}
                    :as event-payload}]

   (s/assert ::spec/query event-payload)

   (let [query (str "query " (string/replace query #"^query\s?" ""))
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
     ^{:doc "Executes a query synchronously. The arguments are:

             [instance-id query-string variables timeout]

             The `instance-id` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     query-sync
     (partial internals/sync-wrapper query)))

(re-frame/reg-event-fx
 ::abort
 interceptors
 (fn [{:keys [db]} [id]]
   (merge
     {:db (-> db
              (update :subscriptions dissoc id)
              (update-in [:http :requests] dissoc id))}
    (when-let [abort-fn (get-in db [:http :requests id :abort])]
      {::internals/call-abort abort-fn}) )))

(defn abort [opts]
   (re-frame/dispatch [::abort opts]))

(re-frame/reg-event-fx
 ::subscribe
 interceptors
 (fn [{:keys [db]} {:keys [id query variables callback instance-id legacy?] :as event}]
   (cond
     (get-in db [:subscriptions (name id) :active?])
     {} ;; duplicate subscription

     (get-in db [:ws :ready?])
     {:db (assoc-in db [:subscriptions (name id)] {:callback callback
                                                                :event [::subscribe event]
                                                                :active? true
                                                                :legacy? legacy?})
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name id)
                            :type "start"
                            :payload {:query (str "subscription " (string/replace query #"^subscription\s?" ""))
                                      :variables variables}}]}

     (:ws db)
     {:db (update-in db [:ws :queue] conj [::subscribe event])}

     :else
     (log/error
       (str
        "Error creating subscription " id
        " on instance " instance-id
         ": Websocket is not enabled, subscriptions are not possible. Please check your re-graph configuration")))))

(defn subscribe [opts]
  (re-frame/dispatch [::subscribe (update opts :callback (fn [f] [::internals/callback {:callback-fn f}]))]))

(re-frame/reg-event-fx
 ::unsubscribe
 interceptors
 (fn [{:keys [db]} {:keys [id] :as event}]
   (if (get-in db [:ws :ready?])
     {:db (update db :subscriptions dissoc (name id))
      ::internals/send-ws [(get-in db [:ws :connection])
                           {:id (name id)
                            :type "stop"}]}

     {:db (update-in db [:ws :queue] conj [::unsubscribe event])})))

(defn unsubscribe [opts]
  (re-frame/dispatch [::unsubscribe opts]))

(re-frame/reg-event-fx
 ::re-init
 [re-frame/unwrap internals/select-instance]
 (fn [{:keys [db]} opts]
   (let [new-db (internals/deep-merge db opts)]
     (merge {:db new-db}
            (when (get-in new-db [:ws :ready?])
              {:dispatch [::internals/connection-init opts]})))))

(defn re-init [opts]
  (re-frame/dispatch [::re-init opts]))

(re-frame/reg-event-fx
 ::init
 [re-frame/unwrap]
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

(re-frame/reg-event-fx
 ::destroy
 interceptors
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

(defn init [opts]
  (re-frame/dispatch [::init opts]))

(defn destroy [opts]
  (re-frame/dispatch [::destroy opts]))
