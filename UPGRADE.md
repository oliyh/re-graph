# Upgrading from 0.1.x to 0.2.0

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

## Changes

All re-frame events (and all vanilla functions) in re-graph's `core` api now accept a single map argument.

Queries, mutations and subscriptions are closely aligned.

Updating a simple call to `query`:
```clj
(rf/dispatch [::re-graph/query "{ some { thing } }" {:some "variable"} [::my-callback]])
```

Now looks like:

```clj
(rf/dispatch [::re-graph/query {:query "{ some { thing } }"
                                :variables {:some "variable"}
                                :callback [::my-callback]}])
```

Note that it is expected that `::my-callback` also follows the same convention of a single map argument, with `:response` containing the response from the server:

```clj
(rf/reg-event-db
  ::my-callback
  [rf/unwrap]
  (fn [db {:keys [response]}]
    (assoc db :response response)))
```

If you wish to pass additional data to the callback you would have previously done this:


But now you should do this:


If you really need the old style, use ;legacy ;; does this work?


## Deprecated core namespace
