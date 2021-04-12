;; borrowed from https://gist.github.com/caskolkm/39d823f5bac7051d3062
(ns re-graph.logging
  (:refer-clojure :exclude [time])
  (:require #?(:clj  [clojure.tools.logging :as log]
               :cljs [goog.log :as glog])))

#?(:cljs
   (def logger
     (glog/getLogger "re-graph" nil)))

#?(:cljs
   (defn log-to-console! []
     (.setCapturing (goog.debug.Console.) true)))

#?(:cljs
   (defn set-level! [level]
     (.setLevel logger level)))

(defn fmt [msgs]
  (apply str (interpose " " (map pr-str msgs))))

(defn info [& s]
  (let [msg (fmt s)]
    #?(:clj  (log/info msg)
       :cljs (glog/info logger msg))))

(defn debug [& s]
  (let [msg (fmt s)]
    #?(:clj  (log/debug msg)
       :cljs (glog/fine logger msg))))

(defn warn [& s]
  (let [msg (fmt s)]
    #?(:clj (log/warn msg)
       :cljs (glog/warning logger msg))))

(defn error [& s]
  (let [msg (fmt s)]
    #?(:clj (log/error msg)
       :cljs (glog/error logger msg))))
