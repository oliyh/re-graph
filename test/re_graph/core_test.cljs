(ns re-graph.core-test
  (:require [re-graph.core :as re-graph]
            [re-frame.core :as re-frame]
            [day8.re-frame.test :refer-macros [run-test-sync]]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [devcards.core :refer-macros [deftest]]))

(deftest query-test
  (run-test-sync
   (re-frame/dispatch [::re-graph/init])

   (re-frame/dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {} [::on-thing]])))

(deftest subscription-test
  (run-test-sync
   (re-frame/dispatch [::re-graph/init])

   (re-frame/dispatch [::re-graph/subscribe :my-sub "{ things { id } }" {} [::on-thing]])))
