# Upgrading from 0.1.x to 0.2.0

- [Rationale](#rationale)
- [Spec and Instrumentation](#spec-and-instrumentation)
- [API changes](#api-changes)
- [Legacy API](#legacy-api)

## Rationale

The signature of re-graph events had become overloaded with two features:
 - Multi instances, where an `instance-name` could be optionally supplied as the first argument to address a particular instance of re-graph
 - Query deduplication and cancellation, where a `query-id` could be optionally supplied as the first argument for queries and mutations

In the case where you were supplying both `instance-name` and `query-id` then these would be given as the first two arguments respectively.

This made destructuring re-graph events difficult and error prone, resulting in hard-to-read error messages when something went wrong.

At the same time, [re-frame](https://github.com/day8/re-frame/issues/644) was discussing the same issue on a more generic level,
namely that positional significance of arguments becomes problematic with large numbers of arguments. To quote their rationale:

> For software systems, the arrow of time and the arrow of entropy are aligned. Changes inevitably happen.
>
> When we use vectors in both these situations we are being "artificially placeful". The elements of the vector have names, but we are pretending they have indexes. That little white lie has some benefits (is terse and minimal) but it also makes the use of these structures a bit fragile WRT to change. Names (truth!) are more flexible and robust than indexes (a white lie).
>
> The benefit of using names accumulate on two time scales:
>
> - within a single execution of the app (as data moves around on the inside of your app)
> - across the maintenance cycle of the app's codebase (as your app's code changes in response to user feedback)

re-frame has added an `unwrap` interceptor to make working with a single map argument easier, and re-graph now follows
this convention with consistently-named keys to remove ambiguity.

## Spec and Instrumentation

The re-graph API is now described by [re-graph.spec](https://github.com/oliyh/re-graph/blob/re-frame-maps/src/re_graph/spec.cljc).
All functions have specs and the implementation re-frame events also have an assertion on the event payload.

This means you can validate your calls to re-graph by turning on instrumentation (for the Clojure/script API) and/or assertions (for re-frame users):

```clj
;; instrumentation
(require '[clojure.spec.test.alpha :as stest])
(stest/instrument)

;; assertions
(require '[clojure.spec.alpha :as s])
(s/check-asserts true)
```

## API changes

All re-frame events (and all vanilla functions) in re-graph's `core` api now accept a single map argument.

Queries, mutations and subscriptions are closely aligned, accepting `:query`, `:variables`, `:callback` and `:id`.

Note that it is expected re-frame callbacks also follow the same convention of a single map argument, with `:response` containing the response from the server.
Vanilla Clojure/script API callback functions remain unchanged.

Examples below show mostly the re-frame events but the Clojure/script API functions take the exact same map arguments.

### Examples

#### re-frame callbacks

A re-frame callback that was originally defined and invoked as follows:
```clj
(rf/reg-event-db
  ::my-callback
  (fn [db [_ response]]
    (assoc db :response response)))

(rf/dispatch [::re-graph/query "{ some { thing } }" {:some "variable"} [::my-callback]])
```
Should now expect the response to be under the `:response` key in a single map argument (destructured here using `unwrap`):
```clj
(rf/reg-event-db
  ::my-callback
  [rf/unwrap]
  (fn [db {:keys [response]}]
    (assoc db :response response)))

(rf/dispatch [::re-graph/query {:query "{ some { thing } }"
                                :variables {:some "variable"}
                                :callback [::my-callback]}])
```

Any partial params supplied like `my-opts` shown here:
```clj
(rf/reg-event-db
  ::my-callback
  (fn [db [_ my-opts response]]
    (assoc db :response response)))

(rf/dispatch [::re-graph/query "{ some { thing } }" {:some "variable"} [::my-callback {:my-opts true}]])
```
Should now be used like this:
```clj
(rf/reg-event-db
  ::my-callback
  [rf/unwrap]
  (fn [db {:keys [my-opts response]}]
    (assoc db :response response)))

(rf/dispatch [::re-graph/query {:query "{ some { thing } }"
                                :variables {:some "variable"}
                                :callback [::my-callback {:my-opts true}]}])
```

#### Simple query

A query with some variables and a callback:
```clj
(rf/dispatch [::re-graph/query "{ some { thing } }" {:some "variable"} [::my-callback]])
```
becomes
```clj
(rf/dispatch [::re-graph/query {:query "{ some { thing } }"
                                :variables {:some "variable"}
                                :callback [::my-callback]}])
```

Or, if you were using the vanilla Clojure/script API:
```clj
(re-graph/query "{ some { thing } }" {:some "variable"} (fn [response] ...))
```
becomes
```clj
(re-graph/query {:query "{ some { thing } }"
                 :variables {:some "variable"}
                 :callback (fn [response] ...)})
```


#### Subscriptions

Subscriptions have always required an id, which could optionally be used for queries and mutations as well. These are now named `:id`:
```clj
(rf/dispatch [::re-graph/subscribe :my-subscription-id "{ some { thing } }" {:some "variable"} [::my-callback]])
```
becomes
```clj
(rf/dispatch [::re-graph/subscribe {:id :my-subscription-id
                                    :query "{ some { thing } }"
                                    :variables {:some "variable"}
                                    :callback [::my-callback]}])
```

And to unsubscribe:
```clj
(rf/dispatch [::re-graph/unsubscribe :my-subscription-id])
```
becomes
```clj
(rf/dispatch [::re-graph/unsubscribe {:id :my-subscription-id}])
```

#### Multiple instances

You can supply `:instance-id` to the `init` (and `re-init`) events and to any subsequent queries:
```clj
(rf/dispatch [::re-graph/init :my-service {:ws {:url "https://my.service"}}])

(rf/dispatch [::re-graph/query :my-service "{ some { thing } }" {:some "variable"} [::my-callback]])
```
becomes
```clj
(rf/dispatch [::re-graph/init {:instance-id :my-service
                               :ws {:url "https://my.service"}}])

(rf/dispatch [::re-graph/query {:instance-id :my-service
                                :query "{ some { thing } }"
                                :variables {:some "variable"}
                                :callback [::my-callback]}])
```

And to destroy:
```clj
(rf/dispatch [::re-graph/destroy :my-service])
```
becomes
```clj
(rf/dispatch [::re-graph/destroy {:instance-id :my-service}])
```

## Legacy API

The original API is available at `re-graph.core-deprecated`. This will be removed in a future release.
