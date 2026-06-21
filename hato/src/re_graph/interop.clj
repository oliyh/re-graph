(ns re-graph.interop
  (:require [hato.client :as hato-http]
            [hato.websocket :as hato-ws]))

;; Reuse a single HttpClient across requests. java.net.http.HttpClient owns an
;; executor, and on JDK 21+ an unreferenced client can be closed (shutting down
;; that executor) before an in-flight async callback runs, yielding a
;; RejectedExecutionException. A long-lived client keeps the executor alive and
;; also reuses the connection pool.
(defonce ^:private http-client
  (hato-http/build-http-client {:connect-timeout 10000}))

(defn create-ws [url {:keys [on-message on-error] :as callbacks}]
  (hato-ws/websocket url (assoc callbacks
                           :http-client http-client
                           ;; See `java.net.http.WebSocket/request` docs for more details on `last?`
                           :on-message (let [text-buffer (atom (StringBuilder.))]
                                         (fn [_ws message last?]
                                           (locking text-buffer
                                             (let [^StringBuilder sb @text-buffer]
                                               (.append sb (str message))
                                               (when last?
                                                 (on-message (str sb))
                                                 (reset! text-buffer (StringBuilder.)))))))
                           :on-error (fn [_ws error]
                                       (on-error error)))))

(defn send-ws [instance payload]
  (hato-ws/send! instance payload))

(defn close-ws [instance]
  (hato-ws/close! instance))

(defn send-http [url request payload on-success on-error]
  (hato-http/post url
                  (-> request
                      (update :headers merge {"Content-Type" "application/json"
                                              "Accept" "application/json"})
                      (merge {:body payload
                              :http-client http-client
                              :as :json
                              :coerce :always
                              :async? true
                              :throw-exceptions false}))
                  on-success
                  on-error))
