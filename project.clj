(defproject re-graph "0.1.16-SNAPSHOT"
  :description "GraphQL client for re-frame applications"
  :url "https://github.com/oliyh/re-graph"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[fundingcircle/lein-modules "0.3.15"]]
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["modules" "change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["install"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["modules" "change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :dependencies [[re-frame "1.1.2"]
                 [cljs-http "0.1.45"]
                 [org.clojure/tools.logging "0.4.1"]
                 [cheshire "5.8.1"]
                 [re-graph.hato :version]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.9.0"]
                                       [org.clojure/clojurescript "1.10.439"]]}
             :dev      {:source-paths   ["dev" "hato/src"]
                        :resource-paths ["dev-resources"]
                        :exclusions     [[org.clojure/tools.reader]]
                        :dependencies   [[org.clojure/tools.reader "1.2.2"]
                                         [binaryage/devtools "0.9.10"]
                                         [day8.re-frame/test "0.1.5"]
                                         [com.bhauman/figwheel-main "0.2.1" :exclusions [org.eclipse.jetty.websocket/websocket-server
                                                                                         org.eclipse.jetty.websocket/websocket-servlet]]
                                         [clj-http-fake "1.0.3"]

                                         ;; integration test
                                         [org.eclipse.jetty.websocket/websocket-client "9.4.26.v20200117"]
                                         [io.pedestal/pedestal.service "0.5.7"]
                                         [io.pedestal/pedestal.jetty "0.5.7"]
                                         [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                                         [org.slf4j/jul-to-slf4j "1.7.26"]
                                         [org.slf4j/jcl-over-slf4j "1.7.26"]
                                         [org.slf4j/log4j-over-slf4j "1.7.26"]
                                         [com.walmartlabs/lacinia-pedestal "0.13.0-alpha-1"]
                                         [io.pedestal/pedestal.service-tools "0.5.7"]

                                         ;; hato
                                         [hato "0.5.0"]

                                         ;; clj-http-gniazdo
                                         [clj-http "3.9.1"]
                                         [stylefruits/gniazdo "1.1.3"]]
                        :repl-options   {:init-ns user}}
             :clj-http-gniazdo {:source-paths ["clj-http-gniazdo/src"]}}
  :aliases {"fig"       ["trampoline" "run" "-m" "figwheel.main"]
            "fig:build" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]
            "fig:min"   ["run" "-m" "figwheel.main" "-O" "advanced" "-bo" "dist"]
            "fig:test"  ["test-cljs"]
            "test-cljs" ["run" "-m" "re-graph.test-runner"]
            "test" ["do" ["clean"] ["test"] ["with-profile" "+clj-http-gniazdo" "test"] ["test-cljs"]]
            "install" ["do" ["modules" "install"] ["install"]]
            "deploy" ["do" ["install"] ["deploy" "clojars"] ["modules" "deploy" "clojars"]]})
