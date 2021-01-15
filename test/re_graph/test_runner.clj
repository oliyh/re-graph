(ns re-graph.test-runner
  (:require [figwheel.main :as fig]
            [re-graph.integration-server :refer [with-server]]))

(defn- run-tests []
  (with-server
    #(fig/-main "-co" "test.cljs.edn" "-m" "re-graph.figwheel-runner")))

(defn -main [& _args]
  (run-tests))
