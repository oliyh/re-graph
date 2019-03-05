(defproject re-graph "0.1.8"
  :description "GraphQL client for re-frame applications"
  :url "https://github.com/oliyh/re-graph"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :dependencies [[re-frame "0.10.6"]
                 [cljs-http "0.1.45"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.10"]
            [lein-figwheel "0.5.14"]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.9.0"]
                                       [org.clojure/clojurescript "1.10.439"]]}
             :dev {:source-paths ["dev"]
                   :resource-paths ["dev-resources"]
                   :exclusions [[org.clojure/tools.reader]]
                   :dependencies [[org.clojure/tools.reader "1.2.2"]
                                  [figwheel-sidecar "0.5.16"]
                                  [binaryage/devtools "0.9.10"]
                                  [devcards "0.2.4"]
                                  [day8.re-frame/test "0.1.5"]
                                  [lein-doo "0.1.10"]

                                  ;; gh-pages deploy
                                  [leiningen-core "2.8.1"]]
                   :repl-options {:init-ns user
                                  :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}}
  :aliases {"test" ["do" ["clean"] ["test"] ["doo" "phantom" "test" "once"]]
            "build-pages" ["do"
                           ["run" "-m" "pages/build"]
                           ["cljsbuild" "once" "pages"]]
            "deploy-pages" ["run" "-m" "pages/push"]}
  :cljsbuild {:builds [{:id "devcards"
                        :figwheel {:devcards true}
                        :source-paths ["src" "test"]
                        :compiler {:preloads [devtools.preload]
                                   :main "re-graph.all-tests"
                                   :asset-path "js/devcards"
                                   :output-to "dev-resources/public/devcards/js/devcards.js"
                                   :output-dir "dev-resources/public/devcards/js/devcards"
                                   :source-map-timestamp true
                                   :parallel-build true}}

                       {:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "target/unit-test.js"
                                   :main "re-graph.runner"
                                   :optimizations :whitespace
                                   :parallel-build true}}]})
