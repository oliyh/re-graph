(ns re-graph.core-deprecated
  "DEPRECATED: Use re-graph.core"
  (:require [re-frame.core :as re-frame]
            [re-graph.internals :as internals
             :refer [default-instance-name]]
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

(defn- ensure-query-id [event-name trimmed-event]
  (if (contains? #{::query ::mutate} event-name)
    (if (= 3 (count trimmed-event)) ;; query, variables, callback-event
      (vec (cons (internals/generate-query-id) trimmed-event))
      trimmed-event)
    trimmed-event))

(def re-graph-instance
  (->interceptor
   :id ::instance
   :before (fn [ctx]
             (let [re-graph  (:re-graph (get-coeffect ctx :db))
                   event (get-coeffect ctx :event)
                   provided-instance-name (first event)
                   instance-name (if (contains? re-graph provided-instance-name) provided-instance-name default-instance-name)
                   instance (get re-graph instance-name)
                   event-name (first (get-coeffect ctx :original-event))
                   trimmed-event (->> (if (= provided-instance-name instance-name)
                                        (subvec event 1)
                                        event)
                                      (ensure-query-id event-name))]

               (cond
                 (:destroyed? instance)
                 ctx

                 instance
                 (-> ctx
                     (assoc-coeffect :instance-name instance-name)
                     (assoc-coeffect :dispatchable-event (into [event-name instance-name] trimmed-event))
                     (internals/cons-interceptor (rfi/path :re-graph instance-name))
                     (assoc-coeffect :event trimmed-event))

                 :else
                 (do (log/error "No default instance of re-graph found but no valid instance name was provided. Valid instance names are:" (keys re-graph)
                                "but was provided with" provided-instance-name
                                "handling event" event-name)
                     ctx))))))

(def interceptors
  [re-frame/trim-v re-graph-instance])

(re-frame/reg-event-fx
 ::mutate
 interceptors
 (fn [{:keys [instance-name]} [query-id query variables callback-event]]
   {:dispatch [::core/mutate {:instance-name instance-name
                              :query-id query-id
                              :query query
                              :variables variables
                              :callback-event callback-event
                              :legacy? true}]}))

(defn mutate
  "Execute a GraphQL mutation. The arguments are:

  [instance-name query-string variables callback]

  If the optional `instance-name` is not provided, the default instance is
  used. The callback function will receive the result of the mutation as its
  sole argument."
  [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::mutate] (conj (vec (butlast args)) [::internals/callback {:callback-fn callback-fn}])))))

#?(:clj
   (def
     ^{:doc "Executes a mutation synchronously. The arguments are:

             [instance-name query-string variables timeout]

             The `instance-name` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     mutate-sync
     (partial sync-wrapper mutate)))

(re-frame/reg-event-fx
 ::query
 interceptors
 (fn [{:keys [instance-name]} [query-id query variables callback-event]]
   {:dispatch [::core/query {:instance-name instance-name
                             :query-id query-id
                             :query query
                             :variables variables
                             :callback-event callback-event
                             :legacy? true}]}))

(defn query
  "Execute a GraphQL query. The arguments are:

  [instance-name query-string variables callback]

  If the optional `instance-name` is not provided, the default instance is
  used. The callback function will receive the result of the query as its
  sole argument."
  [& args]
  (let [callback-fn (last args)]
    (re-frame/dispatch (into [::query] (conj (vec (butlast args)) [::internals/callback {:callback-fn callback-fn}])))))

#?(:clj
   (def
     ^{:doc "Executes a query synchronously. The arguments are:

             [instance-name query-string variables timeout]

             The `instance-name` and `timeout` are optional. The `timeout` is
             specified in milliseconds."}
     query-sync
     (partial sync-wrapper query)))

(re-frame/reg-event-fx
 ::abort
 interceptors
 (fn [{:keys [instance-name]} [query-id]]
   {:dispatch [::core/abort {:instance-name instance-name
                             :query-id query-id
                             :legacy? true}]}))

(defn abort
  ([query-id] (abort default-instance-name query-id))
  ([instance-name query-id]
   (re-frame/dispatch [::abort instance-name query-id])))

(re-frame/reg-event-fx
 ::subscribe
 interceptors
 (fn [{:keys [instance-name]} [subscription-id query variables callback-event]]
   {:dispatch [::core/subscribe {:instance-name instance-name
                                 :subscription-id subscription-id
                                 :query query
                                 :variables variables
                                 :callback-event callback-event
                                 :legacy? true}]}))

(defn subscribe
  ([subscription-id query variables callback-fn]
   (subscribe default-instance-name subscription-id query variables callback-fn))
  ([instance-name subscription-id query variables callback-fn]
   (re-frame/dispatch [::subscribe instance-name subscription-id query variables [::internals/callback {:callback-fn callback-fn}]])))

(re-frame/reg-event-fx
 ::unsubscribe
 interceptors
 (fn [{:keys [instance-name]} [subscription-id]]
   {:dispatch [::core/unsubscribe {:instance-name instance-name
                                   :subscription-id subscription-id
                                   :legacy? true}]}))

(defn unsubscribe
  ([subscription-id] (unsubscribe default-instance-name subscription-id))
  ([instance-name subscription-id]
   (re-frame/dispatch [::unsubscribe instance-name subscription-id])))

(re-frame/reg-event-fx
 ::re-init
 [re-frame/trim-v re-graph-instance]
 (fn [{:keys [instance-name]} [opts]]
   {:dispatch [::core/re-init (assoc opts :instance-name instance-name
                                     :legacy? true)]}))

(defn re-init
  ([opts] (re-init default-instance-name opts))
  ([instance-name opts]
   (re-frame/dispatch [::re-init instance-name opts])))

(re-frame/reg-event-fx
 ::init
 (fn [_ [_ instance-name opts]]
   (let [[instance-name opts] (cond
                                (and (nil? instance-name) (nil? opts))
                                [default-instance-name {}]

                                (map? instance-name)
                                [default-instance-name instance-name]

                                (nil? instance-name)
                                [default-instance-name opts]

                                :else
                                [instance-name opts])
         ws-options (internals/ws-options opts)
         http-options (internals/http-options opts)]

     {:dispatch [::core/init (merge {:instance-name instance-name
                                     :legacy? true}
                                    opts
                                    ws-options
                                    http-options)]})))

(re-frame/reg-event-fx
 ::destroy
 interceptors
 (fn [{:keys [instance-name]} _]
   {:dispatch [::core/destroy {:instance-name instance-name
                               :legacy? true}]}))

(defn init
  ([opts] (init default-instance-name opts))
  ([instance-name opts]
   (re-frame/dispatch [::init instance-name opts])))

(defn destroy
  ([] (destroy default-instance-name))
  ([instance-name]
   (re-frame/dispatch [::destroy instance-name])))
