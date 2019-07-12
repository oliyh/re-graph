;; This test runner is intended to be run from the command line
(ns re-graph.test-runner
  (:require
   [re-graph.all-tests]
   [figwheel.main.testing :refer [run-tests-async]]))

(defn -main [& args]
  (run-tests-async 5000))
