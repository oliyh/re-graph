(defproject re-graph "0.1.10"
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
                 [cljs-http "0.1.45"]
                 [clj-http "3.9.1"]
                 [stylefruits/gniazdo "1.1.1"]
                 [cheshire "5.8.1"]
                 [org.clojure/tools.logging "0.4.1"]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.9.0"]
                                       [org.clojure/clojurescript "1.10.439"]]}
             :dev      {:source-paths   ["dev"]
                        :resource-paths ["dev-resources"]
                        :exclusions     [[org.clojure/tools.reader]]
                        :dependencies   [[org.clojure/tools.reader "1.2.2"]
                                         [binaryage/devtools "0.9.10"]
                                         [day8.re-frame/test "0.1.5"]
                                         [com.bhauman/figwheel-main "0.2.1"]

                                         ;; gh-pages deploy
                                         [leiningen-core "2.8.1"]]
                        :repl-options   {:init-ns user}}}
  :aliases {"fig"       ["trampoline" "run" "-m" "figwheel.main"]
            "fig:build" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]
            "fig:min"   ["run" "-m" "figwheel.main" "-O" "advanced" "-bo" "dist"]
            "fig:test"  ["run" "-m" "figwheel.main" "-co" "test.cljs.edn" "-m" re-graph.test-runner]
            "test" ["do" ["clean"] ["test"] ["fig:test"]]})
