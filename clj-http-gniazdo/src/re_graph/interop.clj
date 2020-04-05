(ns re-graph.interop
  (:require [clj-http.client :as clj-http]
            [gniazdo.core :as gniazdo]))

(defn send-ws [instance payload]
  (gniazdo/send-msg instance payload))

(defn create-ws [url {:keys [on-open on-message on-close on-error sub-protocols]}]
  (let [ws (apply gniazdo/connect url
                  (into [:on-receive on-message
                         :on-close on-close
                         :on-error on-error]
                        (when sub-protocols
                          [:subprotocols sub-protocols])))]
    (on-open ws)))

(defn close-ws [instance]
  (gniazdo/close instance))

(defn send-http [url request payload on-success on-error]
  (clj-http/post url
             (-> request
                 (update :headers merge {"Content-Type" "application/json"
                                         "Accept" "application/json"})
                 (merge {:body payload
                         :as :json
                         :coerce :always
                         :async? true
                         :throw-exceptions false
                         :throw-entire-message? true}))
             on-success
             on-error))
