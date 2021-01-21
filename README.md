# re-graph

re-graph is a graphql client for Clojure and ClojureScript with bindings for [re-frame](https://github.com/Day8/re-frame) applications.

## Notes

This library behaves like the popular [Apollo client](https://github.com/apollographql/subscriptions-transport-ws)
for graphql and as such is compatible with [lacinia-pedestal](https://github.com/walmartlabs/lacinia-pedestal).

Features include:
* Subscriptions, queries and mutations
* Supports websocket and HTTP transports
* Works with Apollo-compatible servers like lacinia-pedestal
* Queues websocket messages until ready
* Websocket reconnects on disconnect
* Simultaneous connection to multiple GraphQL services
* Handles reauthentication without disruption

## Usage

Add re-graph to your project's dependencies:

[![Clojars Project](https://img.shields.io/clojars/v/re-graph.svg)](https://clojars.org/re-graph)

This will also pull in `re-graph.hato`, a library for using re-graph on the JVM based on
[hato](https://github.com/gnarroway/hato) which requires JDK11.
To use earlier JDKs, exclude `re-graph.hato` and include `re-graph.clj-http-gniazdo`.

If you are only targeting Javascript you do not need either of these libraries.

[![Clojars Project](https://img.shields.io/clojars/v/re-graph.hato.svg)](https://clojars.org/re-graph.hato)
[![Clojars Project](https://img.shields.io/clojars/v/re-graph.clj-http-gniazdo.svg)](https://clojars.org/re-graph.clj-http-gniazdo)

```clj
;; For JDK 11+
[re-graph "x.y.z"]

;; For JDK 10-
[re-graph "x.y.z" :exclusions [re-graph.hato]]
[re-graph.clj-http-gniazdo "x.y.z"]

;; For Javascript only
[re-graph "x.y.z" :exclusions [re-graph.hato]]
```

### Vanilla Clojure/Script

Call the `init` function to bootstrap it and then use `subscribe`, `unsubscribe`, `query` and `mutate` functions:

```clojure
(require '[re-graph.core :as re-graph])

;; initialise re-graph, possibly including configuration options (see below)
(re-graph/init {})

(defn on-thing [{:keys [data errors] :as payload}]
  ;; do things with data
))

;; start a subscription, with responses sent to the callback-fn provided
(re-graph/subscribe :my-subscription-id  ;; this id should uniquely identify this subscription
                    "{ things { id } }"  ;; your graphql query
                    {:some "variable"}   ;; arguments map
                    on-thing)            ;; callback-fn when messages are recieved

;; stop the subscription
(re-graph/unsubscribe :my-subscription-id)

;; perform a query, with the response sent to the callback event provided
(re-graph/query "{ things { id } }"  ;; your graphql query
                 {:some "variable"}  ;; arguments map
                 on-thing)           ;; callback event when response is recieved

;; shut re-graph down when finished
(re-graph/destroy)
```

### re-frame users
Dispatch the `init` event to bootstrap it and then use the `:subscribe`, `:unsubscribe`, `:query` and `:mutate` events:

```clojure
(require '[re-graph.core :as re-graph]
         '[re-frame.core :as re-frame])

;; initialise re-graph, possibly including configuration options (see below)
(re-frame/dispatch [::re-graph/init {}])

(re-frame/reg-event-db
  ::on-thing
  (fn [db [_ {:keys [data errors] :as payload}]]
    ;; do things with data e.g. write it into the re-frame database
    ))

;; start a subscription, with responses sent to the callback event provided
(re-frame/dispatch [::re-graph/subscribe
                    :my-subscription-id  ;; this id should uniquely identify this subscription
                    "{ things { id } }"  ;; your graphql query
                    {:some "variable"}   ;; arguments map
                    [::on-thing]])       ;; callback event when messages are recieved

;; stop the subscription
(re-frame/dispatch [::re-graph/unsubscribe :my-subscription-id])

;; perform a query, with the response sent to the callback event provided
(re-frame/dispatch [::re-graph/query
                    :my-query-id         ;; unique id for this query
                    "{ things { id } }"  ;; your graphql query
                    {:some "variable"}   ;; arguments map
                    [::on-thing]])       ;; callback event when response is recieved

;; shut re-graph down when finished
(re-frame/dispatch [::re-graph/destroy])
```

### Options

Options can be passed to the init event, with the following possibilities:

```clojure
(re-frame/dispatch
  [::re-graph/init
    {:ws {:url                     "wss://foo.io/graphql-ws" ;; override the websocket url (defaults to /graphql-ws, nil to disable)
          :sub-protocol            "graphql-ws"              ;; override the websocket sub-protocol (defaults to "graphql-ws")
          :reconnect-timeout       5000                      ;; attempt reconnect n milliseconds after disconnect (defaults to 5000, nil to disable)
          :resume-subscriptions?   true                      ;; start existing subscriptions again when websocket is reconnected after a disconnect (defaults to true)
          :connection-init-payload {}                        ;; the payload to send in the connection_init message, sent when a websocket connection is made (defaults to {})
          :impl                    {}                        ;; implementation-specific options (see hato for options, defaults to {}, may be a literal or a function that returns the options)
          :supported-operations    #{:subscribe              ;; declare the operations supported via websocket, defaults to all three
                                     :query                  ;;   if queries/mutations must be done via http set this to #{:subscribe} only
                                     :mutate}
         }

     :http {:url    "http://bar.io/graphql"   ;; override the http url (defaults to /graphql)
            :impl   {}                        ;; implementation-specific options (see clj-http or hato for options, defaults to {}, may be a literal or a function that returns the options)
            :supported-operations #{:query    ;; declare the operations supported via http, defaults to :query and :mutate
                                    :mutate}
           }
  }])
```

Either `:ws` or `:http` can be set to nil to disable the WebSocket or HTTP protocols.

### Multiple instances

re-graph now supports multiple instances, allowing you to connect to multiple GraphQL services at the same time.
All function/event signatures now take an optional instance-name as the first argument to let you address them separately:

```clojure
(require '[re-graph.core :as re-graph])

;; initialise re-graph for service A
(re-graph/init :service-a {:ws-url "wss://a.com/graphql-ws})

;; initialise re-graph for service B
(re-graph/init :service-b {:ws-url "wss://b.net/api/graphql-ws})

(defn on-a-thing [{:keys [data errors] :as payload}]
  ;; do things with data from service A
))

;; subscribe to service A, events will be sent to the on-a-thing callback
(re-graph/subscribe :service-a           ;; the instance-name you want to talk to
                    :my-subscription-id  ;; this id should uniquely identify this subscription for this service
                    "{ things { a } }"
                    on-a-thing)

(defn on-b-thing [{:keys [data errors] :as payload}]
  ;; do things with data from service B
))

;; subscribe to service B, events will be sent to the on-b-thing callback
(re-graph/subscribe :service-b           ;; the instance-name you want to talk to
                    :my-subscription-id
                    "{ things { b } }"
                    on-b-thing)

;; stop the subscriptions
(re-graph/unsubscribe :service-a :my-subscription-id)
(re-graph/unsubscribe :service-b :my-subscription-id)
```

## Cookie Management

When using re-graph within a browser, cookies are shared between HTTP and WebSocket connection automatically. There's nothing special that needs to be done.

When using re-graph with Clojure, however, some configuration is necessary to ensure that the same cookie store is used for both HTTP and WebSocket connections.

Before initializing re-graph, create a common HTTP client.

```
(ns user
  (:require
    [hato.client :as hc]
    [re-graph.core :as re-graph]))

(def http-client (hc/build-http-client {:cookie-policy :all}))
```

See the [hato documentation](https://github.com/gnarroway/hato) for all the supported configuration options.

When initializing re-graph, configure both the HTTP and WebSocket connections with this client:

```
(re-graph/init {:http {:impl {:http-client http-client}}
                :ws   {:impl {:http-client http-client}}})
```

In the call, you can provide any supported re-graph or hato options. Be careful though; hato convenience options for the HTTP client will be ignored when using the `:http-client` option.

## Re-initialisation

When initialising re-graph you may have included authorisation tokens e.g.

```clojure
(re-frame/dispatch [::re-graph/init {:http {:url "http://foo.bar/graph-ql"
                                            :impl {:headers {"Authorization" 123}}}
                                     :ws {:connection-init-payload {:token 123}}}])
```

If those tokens expire you can refresh them using `re-init` as follows which allows you to change any parameter provided to re-graph:

```clojure
(re-frame/dispatch [::re-graph/re-init {:http {:impl {:headers {"Authorization" 456}}}
                                        :ws {:connection-init-payload {:token 456}}}])
```

The `connection-init-payload` will be sent again and all future remote calls will contain the updated parameters.

## Development

`cider-jack-in-clj&cljs`

CLJS tests are available at http://localhost:9500/figwheel-extra-main/auto-testing
You will need to run `(re-graph.integration-server/start!)` for the integration tests to pass.

[![CircleCI](https://circleci.com/gh/oliyh/re-graph.svg?style=svg)](https://circleci.com/gh/oliyh/re-graph)

## License

Copyright © 2017 oliyh

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
