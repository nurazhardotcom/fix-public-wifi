;; fix-public-wifi.probe — unencrypted captive-portal probing.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.probe
  "Unencrypted probe execution + captive-portal detection.

  Why plain HTTP only: public Wi-Fi gateways (e.g. Changi Airport #ChangiWiFi)
  hijack port 80 and 302-redirect it to their login page. Probing https://
  first triggers ERR_CERT_COMMON_NAME_INVALID / HSTS blocks because the
  gateway serves its own cert for a foreign hostname. Probing
  http://neverssl.com or http://captive.apple.com/generate_204 avoids TLS
  entirely, so a 302 + Location header IS the signal."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]))

(def default-probe-urls
  ["http://captive.apple.com/generate_204"
   "http://neverssl.com"])

(def portal-markers
  ["captive" "portal" "login" "authenticate" "accept terms" "terms of use"
   "aup" "wifilogin" "hotspot" "cisco ise" "aruba" "ruckus" "changi"])

(defn parse-query-params
  "Pure fn: \"a=1&b=2\" (or full URL) -> {\"a\" \"1\" ...}. Keeps session/mac/zone tokens."
  [s]
  (let [q (if (and s (str/includes? (str s) "?"))
            (second (str/split (str s) #"\?" 2))
            (str s))]
    (if (or (nil? q) (str/blank? q))
      {}
      (into {}
            (comp (map #(str/split % #"=" 2))
                  (map (fn [[k v]] [(java.net.URLDecoder/decode (or k "") "UTF-8")
                                    (java.net.URLDecoder/decode (or v "") "UTF-8")])))
            (str/split q #"&")))))

(defn extract-location
  "Case-insensitive Location header lookup (http-client lowercases, but be safe)."
  [headers]
  (or (get headers "location")
      (get headers "Location")
      (some (fn [[k v]] (when (= "location" (str/lower-case (str k))) v)) headers)))

(defn redirect-status? [status]
  (contains? #{301 302 303 307 308} status))

(defn portal-body?
  "Heuristic: 200 OK whose HTML smells like a login/TOS page."
  [body]
  (when (string? body)
    (let [b (str/lower-case (subs body 0 (min 8000 (count body))))]
      (boolean (some #(str/includes? b %) portal-markers)))))

(defn classify-response
  "Pure fn: {:status :headers :body :url} -> detection map.
   Returns {:portal? bool :reason kw :location url-or-nil :params {...}}."
  [{:keys [status headers body url]}]
  (let [loc (extract-location (or headers {}))]
    (cond
      ;; Apple probe: 204 with tiny 'Success' body = open internet.
      (and (= 204 status) (nil? loc))
      {:portal? false :reason :generate-204-clean :location nil :params {} :url url}

      ;; Any redirect with a Location = gateway interception (Cisco ISE / Aruba / Ruckus / Changi).
      (and (redirect-status? status) loc)
      {:portal? true :reason :http-redirect :location loc
       :params (parse-query-params loc) :url url}

      ;; 200 from neverssl.com that is NOT the real NeverSSL page = hijacked.
      (and (= 200 status) (some? body)
           (not (str/includes? (str body) "NeverSSL")))
      (if (portal-body? body)
        {:portal? true :reason :hijacked-200-portal-keywords :location loc
         :params (if loc (parse-query-params loc) {}) :url url}
        {:portal? true :reason :hijacked-200-unexpected :location loc
         :params (if loc (parse-query-params loc) {}) :url url})

      ;; 200 that IS NeverSSL, or 204 -> open.
      :else
      {:portal? false :reason :clean-200 :location nil :params {} :url url})))

(defn- no-redirect-client [timeout-ms]
  (http/client {:follow-redirects :never
                :connect-timeout timeout-ms}))

(defn probe-once
  "Single unencrypted GET with redirects DISABLED so we can read Location.
   Returns response map or {:error ...}. Never throws on HTTP status."
  [url timeout-ms]
  (try
    (let [client (no-redirect-client timeout-ms)
          resp (http/get url {:client client
                              :timeout timeout-ms
                              :throw false
                              :headers {"User-Agent" "fix-public-wifi/bb"
                                        "Cache-Control" "no-cache"
                                        "Pragma" "no-cache"}})]
      {:status (:status resp)
       :headers (:headers resp)
       :body (:body resp)
       :url url})
    (catch Exception e
      {:error :request-failed
       :url url
       :message (ex-message e)
       :cause (str (type e))})))

(defn probe!
  "Try each URL in order. Returns:
   {:online? true ...} | {:online? false :portal detection} | {:online? nil :timeout ...}."
  [{:keys [urls timeout-ms verbose] :or {urls default-probe-urls timeout-ms 10000}}]
  (loop [[u & rest] urls
         last-err nil]
    (if (nil? u)
      {:online? nil
       :timeout? true
       :reason :all-probes-failed
       :message (or (:message last-err) "all probe targets failed (no network?)")}
      (do
        (when verbose (println (str "[probe] GET " u)))
        (let [resp (probe-once u timeout-ms)]
          (cond
            (:error resp)
            (do (when verbose (println (str "[probe] error: " (:message resp))))
                (if (empty? rest)
                  {:online? nil :timeout? true :reason :network-timeout
                   :message (:message resp) :url u}
                  (recur rest resp)))

            :else
            (let [c (classify-response resp)]
              (when verbose
                (println (str "[probe] " u " -> " (:status resp)
                              (if (:portal? c) " PORTAL" " OPEN")
                              " (" (name (:reason c)) ")")))
              (if (:portal? c)
                {:online? false :timeout? false :detection c :raw resp}
                (if (empty? rest)
                  {:online? true :timeout? false :detection c :raw resp}
                  ;; Apple 204 is authoritative; neverssl 200 confirms.
                  {:online? true :timeout? false :detection c :raw resp})))))))))
