(ns re-graph.spec
  (:require [clojure.spec.alpha :as s]))

;; primitives

(s/def ::id some?)

(s/def ::instance-id ::id)
(s/def ::query-id ::id)

(s/def :payload/query string?)
(s/def ::variables map?)
(s/def ::callback (s/or :event vector? :fn fn?))

;; queries and mutations

(s/def ::query (s/keys :req-un [:payload/query
                                ::callback]
                       :opt-un [::variables
                                ::id
                                ::instance-id]))

(s/def ::mutate ::query)

(s/def ::abort (s/keys :req-un [::id]
                       :opt-un [::instance-id]))

;; subscriptions

(s/def ::subscribe (s/keys :req-un [:payload/query
                                    ::id
                                    ::callback]
                           :opt-un [::variables
                                    ::instance-id]))

(s/def ::unsubscribe (s/keys :req-un [::id]
                             :opt-un [::instance-id]))

;; re-graph lifecycle

(s/def ::url string?)
(s/def ::sub-protocol string?)
(s/def ::reconnect-timeout int?)
(s/def ::resume-subscriptions? boolean?)
(s/def ::connection-init-payload map?)
(s/def ::supported-operations #{:query :mutate :subscribe})
(s/def ::impl map?)

(s/def ::ws (s/keys :opt-un [::url
                             ::sub-protocol
                             ::reconnect-timeout
                             ::resume-subscriptions?
                             ::connection-init-payload
                             ::supported-operations
                             ::impl]))

(s/def ::http (s/keys :opt-un [::url
                               ::supported-operations
                               ::impl]))

(s/def ::init (s/keys :opt-un [::ws
                               ::http
                               ::instance-id]))

(s/def ::re-init ::init)

(s/def ::destroy (s/keys :opt-un [::instance-id]))
