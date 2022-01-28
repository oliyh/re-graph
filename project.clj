(defproject re-graph "0.1.17-SNAPSHOT"
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
  :dependencies [[re-frame "1.2.0"]
                 [cljs-http "0.1.46"]
                 [org.clojure/tools.logging "1.2.4"]
                 [cheshire "5.10.2"]
                 [re-graph.hato :version]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.10.3"]
                                       [org.clojure/clojurescript "1.11.4"]]}
             :dev      {:source-paths   ["dev"
                                         "hato/src"
                                         ;;"clj-http-gniazdo/src"
                                         ]
                        :resource-paths ["dev-resources" "target"]
                        :clean-targets ^{:protect false} ["target"]
                        :dependencies   [[org.clojure/tools.reader "1.3.6"]
                                         [binaryage/devtools "1.0.4"]
                                         [day8.re-frame/test "0.1.5"]
                                         [com.bhauman/figwheel-main "0.2.15" :exclusions [org.eclipse.jetty.websocket/websocket-server
                                                                                          org.eclipse.jetty.websocket/websocket-servlet]]

                                         ;; integration test
                                         [org.eclipse.jetty.websocket/websocket-client "9.4.44.v20210927"]
                                         [io.pedestal/pedestal.service "0.5.10"]
                                         [org.eclipse.jetty/jetty-server "9.4.44.v20210927"]
                                         [org.eclipse.jetty/jetty-servlet "9.4.44.v20210927"]
                                         [org.eclipse.jetty/jetty-alpn-server "9.4.44.v20210927"]
                                         [org.eclipse.jetty.http2/http2-server "9.4.44.v20210927"]
                                         [org.eclipse.jetty.websocket/websocket-api "9.4.44.v20210927"]
                                         [org.eclipse.jetty.websocket/websocket-servlet "9.4.44.v20210927"]
                                         [org.eclipse.jetty.websocket/websocket-server "9.4.44.v20210927"]
                                         [io.pedestal/pedestal.jetty "0.5.10"
                                          :exclusions [org.eclipse.jetty.http2/http2-server
                                                       org.eclipse.jetty.websocket/websocket-api
                                                       org.eclipse.jetty.websocket/websocket-server
                                                       org.eclipse.jetty.websocket/websocket-servlet
                                                       org.eclipse.jetty/jetty-alpn-server
                                                       org.eclipse.jetty/jetty-server
                                                       org.eclipse.jetty/jetty-servlet]]
                                         [ch.qos.logback/logback-classic "1.2.10" :exclusions [org.slf4j/slf4j-api]]
                                         [org.slf4j/jul-to-slf4j "1.7.35"]
                                         [org.slf4j/jcl-over-slf4j "1.7.35"]
                                         [org.slf4j/log4j-over-slf4j "1.7.35"]
                                         [com.walmartlabs/lacinia-pedestal "1.1"]
                                         [io.aviso/pretty "1.1.1"] ;; should be a transitive dependency from lacinia but for some reason doesn't work
                                         [io.pedestal/pedestal.service-tools "0.5.10"]

                                         ;; hato
                                         [hato "0.8.2"]

                                         ;; clj-http-gniazdo
                                         [clj-http "3.12.3"]
                                         [stylefruits/gniazdo "1.2.0"]]
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
