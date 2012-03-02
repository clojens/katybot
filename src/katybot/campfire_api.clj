(ns katybot.campfire-api
  (:require [http.async.client :as httpc]
            [http.async.client.request :as httpr]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:use katybot.utils))

(defprotocol CampfireAPI
  (join      [_ room])
  (listen    [_ room msg-callback])
  (say       [_ room msg])
  (stop-listening [_ room])
  (leave     [_ room])
  (user-info [_ user])
  (room-info [_ room]))

(def ^:dynamic *check-interval* 5000)
(def ^:dynamic *stuck-timeout* 10000)
(def ^:dynamic *headers*   {"Content-Type" "application/json; charset=utf-8"
                            "Accept"       "application/json"})
(def ^:dynamic *user-agent* "Katybot")

(declare room-agent)
(declare finish)
(declare get-json)
(declare post)

(defn campfire-async-api [account token]
  (let [client (httpc/create-client
          ;:proxy {:host "127.0.0.1" :port 8443 :protocol :https}
          :auth  {:user token :password "x" :preemptive true})
        baseurl (format "https://%s.campfirenow.com" account)
        connections (ref {})]
    (reify
      CampfireAPI
      (join [_ room]
        (post (format "%s/room/%s/join.json" baseurl room) client nil)
        (fyi "Joined a room " room))
      (listen [api room msg-callback]
        (dosync
          (stop-listening api room)
          (let [agnt (room-agent api client room msg-callback)]
            (alter connections assoc room agnt)
            agnt)))
      (say       [_ room msg] :nop)
      (stop-listening [_ room]
        (dosync
          (when-let [old-agnt (@connections room)]
            (send old-agnt finish)
            (alter connections dissoc room))))
      (leave     [_ room]
        (post (format "%s/room/%s/leave.json" baseurl room) client nil)
        (fyi "Leaved a room " room))
      (user-info [_ user] :nop)
      (room-info [_ room] :nop))))

(defn touch [state phase]
  (if (= :finished (:phase state))
    state
    (assoc state :phase phase :last-accessed (now))))

(defn callback [msg-callback agnt state baos]
  (btw "[callback] part \"" baos "\"")
  (send agnt touch :listening)
  (let [body (.toString baos "UTF-8")]
    (if (not (str/blank? body))           ; filtering keep-alive " " messages
      (doseq [msg (str/split body #"\r")] ; splitting coerced message bodies
        (msg-callback (json/read-json msg)))))
  [baos :continue])

(defn err-callback [agnt resp thrwbl]
  (omg! "[callback] error\n" resp "\n" thrwbl)
  (send agnt touch :broken)
  thrwbl)

(defn done-callback [agnt resp]
  (omg! "[callback] done\n" resp)
  (send agnt touch :dropped)
  [true :continue])

(defn connect [state]
  (btw "[agnt] connect")
  (let [{:keys [room api client msg-callback]} state
        url (format "https://streaming.campfirenow.com/room/%s/live.json" room)]
    (join api room) ; just in case we were kicked
    (binding [httpr/*default-callbacks* (merge httpr/*default-callbacks* 
              {:completed (partial done-callback *agent*)
               :error     (partial err-callback  *agent*)})]
      (-> state
        (touch :listening)
        (assoc :resp (httpc/request-stream client :get url (partial callback msg-callback *agent*)))))))

(defn disconnect [state]
  (btw "[agnt] disconnect")
  (if (= (:phase state) :listening)
    (httpc/cancel (:resp state)))
  state)

(defn reconnect [state]
  (btw "[agnt] reconnect " (:phase state))
  (-> state
    disconnect
    connect))

(defn finish [state]
  (btw "[agnt] finish")
  (-> state
    disconnect
    (assoc :phase :finished)))

(defn doctor [agnt e]
  (omg! "[doctor] " e)
  (send agnt touch :broken))

(defn watchman [agnt]
  "Check agnt status every second and restart if it stuck"
  (let [{:keys [phase resp last-accessed]} @agnt
        delay (- (now) last-accessed)]
    (btw "[watchman] " phase)
    (cond
      (= phase :finished) nil ; stopping watchman
      (> delay *stuck-timeout*)
        (do (send agnt reconnect) :continue)
      :else :continue)))

(defn logger [room _ agnt old-state new-state]
  (btw "[logger-" room "] " (:phase old-state) " -> " (:phase new-state)))

(defn room-agent [api client room msg-callback]
  (let [agnt (agent {:api    api
                     :client client
                     :room   room
                     :msg-callback msg-callback}
                    :error-handler doctor
                    :clear-actions true)]
    (schedule (partial watchman agnt) *check-interval*)
    (doto agnt
      (add-watch  :logger-watcher (partial logger room))
      (send touch :init)
      (send connect))))

(defn- get-json [url client & query]
  (btw "HTTP GET: " url)
  (let [resp   (httpc/await (httpc/GET client url 
                              :headers *headers*
                              :user-agent *user-agent*
                              :query (apply hash-map query)))
        status (:code (httpc/status resp))
        res    (httpc/string resp)]
    (cond
      (httpc/failed? resp) (throw (httpc/error resp))
      (not= status 200)    (throw (Exception. (str url ": " status res "\n" (httpc/headers resp))))
      :else (json/read-json res))))

(defn- post [url client body]
  (btw "HTTP POST:\n  " url "\n  " body)
  (let [resp (httpc/await (httpc/POST client url
                            :body body
                            :headers *headers*
                            :user-agent *user-agent*))
        status (:code (httpc/status resp))
        res (httpc/string resp)]
    (cond
      (httpc/failed? resp) (throw (httpc/error resp))
      (> status 299)       (throw (Exception. (str url " returned " status res "\n" (httpc/headers resp))))
      :else res)))