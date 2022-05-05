(ns re-graph.core-deprecated
  "DEPRECATED: Use re-graph.core"
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals
             :refer [default-instance-id]]
            [re-graph.core :as core]
            [re-graph.logging :as log]
            [re-frame.std-interceptors :as rfi]
            [re-frame.interceptor :refer [->interceptor get-coeffect assoc-coeffect]]))

#?(:clj
   (defn sync-wrapper
     "Wraps the given function to allow the GraphQL result to be returned
      synchronously. Will return a GraphQL error response if no response is
      received before the timeout (default 3000ms) expires. Will throw if the
      call returns an exception."
     [f & args]
     (let [timeout  (when (int? (last args)) (last args))
           timeout' (or timeout 3000)
           p        (promise)
           callback (fn [result] (deliver p result))
           args'    (conj (vec (if timeout (butlast args) args))
                          callback)]
       (apply f args')

       ;; explicit timeout to avoid unreliable aborts from underlying implementations
       (let [result (deref p timeout' ::timeout)]
         (if (= ::timeout result)
           {:errors [{:message "re-graph did not receive response from server"
                      :timeout timeout'
                      :args args}]}
           result)))))

(defn- ensure-id [event-name trimmed-event]
  (if (contains? #{::query ::mutate} event-name)
    (if (= 3 (count trimmed-event)) ;; query, variables, callback
      (vec (cons (internals/generate-id) trimmed-event))
      trimmed-event)
    trimmed-event))

(def re-graph-instance
  (->interceptor
   :id ::instance
   :before (fn [ctx]
             (let [re-graph  (:re-graph (get-coeffect ctx :db))
                   event (get-coeffect ctx :event)
                   provided-instance-id (first event)
                   instance-id (if (contains? re-graph provided-instance-id) provided-instance-id default-instance-id)
                   instance (get re-graph instance-id)
                   event-name (first (get-coeffect ctx :original-event))
                   trimmed-event (->> (if (= provided-instance-id instance-id)
                                        (subvec event 1)
                                        event)
                                      (ensure-id event-name))]

               (cond
                 (:destroyed? instance)
                 ctx

                 instance
                 (-> ctx
                     (assoc-coeffect :instance-id instance-id)
                     (assoc-coeffect :dispatchable-event (into [event-name instance-id] trimmed-event))
                     (internals/cons-interceptor (rfi/path :re-graph instance-id))
                     (assoc-coeffect :event trimmed-event))

                 :else
                 (do (log/error "No default instance of re-graph found but no valid instance name was provided. Valid instance names are:" (keys re-graph)
                                "but was provided with" provided-instance-id
                                "handling event" event-name)
                     ctx))))))

(def interceptors
  [re-frame/trim-v re-graph-instance])

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [instance-id]} [id query variables callback]]
   {:dispatch [::core/mutate {:instance-id instance-id
                              :id id
                              :query query
                              :variables variables
                              :callback callback
                              :legacy? true}]}))

(defn mutate
  "Execute a GraphQL mutation. The arguments are:

  [instance-id query-string variables callback]

  If the optional `instance-id` is not provided, the default instance is
  used. The callback function will receive the result of the mutation as its
  sole argument."
  [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::mutate] (conj (vec (butlast args)) [::internals/callback {:callback-fn callback-fn}])))))

#?(:clj
   (def
     ^{:doc "Executes a mutation synchronously. The arguments are:

             [instance-id query-string variables timeout]

             The `instance-id` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     mutate-sync
     (partial sync-wrapper mutate)))

(re-frame/reg-event-fx
 ::query
 interceptors
 (fn [{:keys [instance-id]} [id query variables callback]]
   {:dispatch [::core/query {:instance-id instance-id
                             :id id
                             :query query
                             :variables variables
                             :callback callback
                             :legacy? true}]}))

(defn query
  "Execute a GraphQL query. The arguments are:

  [instance-id query-string variables callback]

  If the optional `instance-id` is not provided, the default instance is
  used. The callback function will receive the result of the query as its
  sole argument."
  [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::query] (conj (vec (butlast args)) [::internals/callback {:callback-fn callback-fn}])))))

#?(:clj
   (def
     ^{:doc "Executes a query synchronously. The arguments are:

             [instance-id query-string variables timeout]

             The `instance-id` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     query-sync
     (partial sync-wrapper query)))

(re-frame/reg-event-fx
 ::abort
 interceptors
 (fn [{:keys [instance-id]} [id]]
   {:dispatch [::core/abort {:instance-id instance-id
                             :id id
                             :legacy? true}]}))

(defn abort
  ([id] (abort default-instance-id id))
  ([instance-id id]
   (re-frame/dispatch [::abort instance-id id])))

(re-frame/reg-event-fx
 ::subscribe
 interceptors
 (fn [{:keys [instance-id]} [id query variables callback]]
   {:dispatch [::core/subscribe {:instance-id instance-id
                                 :id id
                                 :query query
                                 :variables variables
                                 :callback callback
                                 :legacy? true}]}))

(defn subscribe
  ([id query variables callback-fn]
   (subscribe default-instance-id id query variables callback-fn))
  ([instance-id id query variables callback-fn]
   (re-frame/dispatch [::subscribe instance-id id query variables [::internals/callback {:callback-fn callback-fn}]])))

(re-frame/reg-event-fx
 ::unsubscribe
 interceptors
 (fn [{:keys [instance-id]} [id]]
   {:dispatch [::core/unsubscribe {:instance-id instance-id
                                   :id id
                                   :legacy? true}]}))

(defn unsubscribe
  ([id] (unsubscribe default-instance-id id))
  ([instance-id id]
   (re-frame/dispatch [::unsubscribe instance-id id])))

(re-frame/reg-event-fx
 ::re-init
 [re-frame/trim-v re-graph-instance]
 (fn [{:keys [instance-id]} [opts]]
   {:dispatch [::core/re-init (assoc opts :instance-id instance-id
                                     :legacy? true)]}))

(defn re-init
  ([opts] (re-init default-instance-id opts))
  ([instance-id opts]
   (re-frame/dispatch [::re-init instance-id opts])))

(re-frame/reg-event-fx
 ::init
 (fn [_ [_ instance-id opts]]
   (let [[instance-id opts] (cond
                                (and (nil? instance-id) (nil? opts))
                                [default-instance-id {}]

                                (map? instance-id)
                                [default-instance-id instance-id]

                                (nil? instance-id)
                                [default-instance-id opts]

                                :else
                                [instance-id opts])
         ws-options (internals/ws-options opts)
         http-options (internals/http-options opts)]

     {:dispatch [::core/init (merge {:instance-id instance-id
                                     :legacy? true}
                                    opts
                                    ws-options
                                    http-options)]})))

(re-frame/reg-event-fx
 ::destroy
 interceptors
 (fn [{:keys [instance-id]} _]
   {:dispatch [::core/destroy {:instance-id instance-id
                               :legacy? true}]}))

(defn init
  ([opts] (init default-instance-id opts))
  ([instance-id opts]
   (re-frame/dispatch [::init instance-id opts])))

(defn destroy
  ([] (destroy default-instance-id))
  ([instance-id]
   (re-frame/dispatch [::destroy instance-id])))
