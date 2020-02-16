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

## Usage

Add re-graph to your project's dependencies:

[![Clojars Project](https://img.shields.io/clojars/v/re-graph.svg)](https://clojars.org/re-graph)

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
                    "{ things { id } }"  ;; your graphql query
                    {:some "variable"}   ;; arguments map
                    [::on-thing]])       ;; callback event when response is recieved
```

### Options

Options can be passed to the init event, with the following possibilities:

```clojure
(re-frame/dispatch
  [::re-graph/init
    {:ws-url                  "wss://foo.io/graphql-ws" ;; override the websocket url (defaults to /graphql-ws, nil to disable)
     :ws-sub-protocol         "graphql-ws"              ;; override the websocket sub-protocol
     :ws-reconnect-timeout    2000                      ;; attempt reconnect n milliseconds after disconnect (default 5000, nil to disable)
     :resume-subscriptions?   true                      ;; start existing subscriptions again when websocket is reconnected after a disconnect
     :connection-init-payload {}                        ;; the payload to send in the connection_init message, sent when a websocket connection is made

     :http-url                "http://bar.io/graphql"   ;; override the http url (defaults to /graphql)
     :http-parameters         {:with-credentials? false ;; any parameters to be merged with the request, see cljs-http for options
                               :oauth-token "Secret"}
  }])
```

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

## Development

`cider-jack-in-clj&cljs`

CLJS tests are available at http://localhost:9500/figwheel-extra-main/auto-testing

[![CircleCI](https://circleci.com/gh/oliyh/re-graph.svg?style=svg)](https://circleci.com/gh/oliyh/re-graph)

## License

Copyright © 2017 oliyh

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
