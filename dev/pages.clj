(ns pages
  (:require [clojure.java.io :as io]
            [leiningen.core.eval :as eval])
  (:import [java.io File]))

(defn- copy [from to]
  (let [from (io/file from)]
    (if (.isDirectory from)
      (doseq [^File f (file-seq from)]
        (when-not (.isDirectory f)
          (let [to (io/file to (.getName f))]
            (io/make-parents to)
            (io/copy f to))))

      (let [to (io/file to)]
        (when-not (.exists to)
          (io/make-parents to))
        (io/copy from to)))))

(defn build [& args]
  (println "Building gh-pages")
  ;; (copy (io/resource "public/todomvc/css") "dist/css")
  ;; (copy (io/resource "public/css/re-learn.css") "dist/css/re-learn.css")
  )

(defn push [& args]
  (println "Pushing gh-pages")
  (eval/sh-with-exit-code "Couldn't commit" "git" "commit" "dist" "-m" "deploying gh-pages")
  (eval/sh-with-exit-code "Couldn't push subtree" "git" "subtree" "push" "--prefix" "dist" "origin" "gh-pages")
  (eval/sh-with-exit-code "Couldn't push" "git" "push"))
