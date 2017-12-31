# re-graph

re-graph is a graphql client for ClojureScript with bindings for [re-frame](https://github.com/Day8/re-frame) applications.

## Notes

This library behaves like the popular [Apollo client](https://github.com/apollographql/subscriptions-transport-ws)
for graphql and as such is compatible with [lacinia-pedestal](https://github.com/walmartlabs/lacinia-pedestal).

Features include:
* Subscriptions, queries and mutations
* Supports websocket and HTTP transports
* Works with Apollo-compatible servers like lacinia-pedestal
* Queues websocket messages until ready
* Websocket reconnects on disconnect

## Usage

Add re-graph to your project's dependencies:

[![Clojars Project](https://img.shields.io/clojars/v/re-graph.svg)](https://clojars.org/re-graph)

### Vanilla ClojureScript

Call the `init` function to bootstrap it and then use `subscribe`, `unsubscribe`, `query` and `mutate` functions:

```clojure
(require [re-graph.core :as re-graph])

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
(require [re-graph.core :as re-graph]
         [re-frame.core :as re-frame])

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
    {:ws-url               "wss://foo.io/graphql-ws" ;; override the websocket url (defaults to /graphql-ws, nil to disable)
     :http-url             "http://bar.io/graphql"   ;; override the http url (defaults to /graphql)
     :ws-reconnect-timeout 2000                      ;; attempt reconnect n milliseconds after disconnect (default 5000, nil to disable)
  }])
```

## Development

```clojure
user> (dev)
dev> (start)
;; visit http://localhost:3449/devcards/index.html
dev> (cljs)
cljs.user>
```

[![CircleCI](https://circleci.com/gh/oliyh/re-graph.svg?style=svg)](https://circleci.com/gh/oliyh/re-graph)

## License

Copyright © 2017 oliyh

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
