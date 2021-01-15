;; This test runner is intended to be run from the command line
(ns re-graph.figwheel-runner
  (:require [re-graph.core-test]
            [re-graph.integration-test]
            [figwheel.main.testing :refer [run-tests-async]]))

(defn -main [& _args]
  (run-tests-async 5000))
