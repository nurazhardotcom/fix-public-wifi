;; fix-public-wifi.verify — post-auth connectivity check.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.verify
  "Network-access verification: re-probe plain-HTTP targets, fall back to
  ICMP ping of 1.1.1.1 (Cloudflare, anycast, rarely blocked)."
  (:require [fix-public-wifi.probe :as probe]
            [babashka.process :as proc]
            [clojure.string :as str]))

(defn ping-host
  "Ping once with a short deadline. Returns {:ok? bool :output str}."
  [host timeout-sec]
  (try
    (let [res (proc/sh ["ping" "-c1" (str "-W" timeout-sec) host])]
      {:ok? (zero? (:exit res)) :output (str (:out res) (:err res))})
    (catch Exception e
      {:ok? false :output (ex-message e)})))

(defn verify!
  "Re-probe HTTP(S-free) targets, then ping 1.1.1.1.
   Returns {:online? bool :method :http-reprobe | :icmp-ping}."
  [{:keys [urls timeout-ms verbose ping?] :or {urls probe/default-probe-urls
                                               timeout-ms 10000 ping? true}}]
  (when verbose (println "[verify] re-probing for internet access..."))
  (let [r (probe/probe! {:urls urls :timeout-ms timeout-ms :verbose verbose})]
    (cond
      (:online? r) {:online? true :method :http-reprobe :detail r}
      (not ping?) {:online? false :method :http-reprobe :detail r}
      :else
      (do
        (when verbose (println "[verify] HTTP still captive; pinging 1.1.1.1..."))
        (let [p (ping-host "1.1.1.1" 3)]
          (when verbose (println (str "[verify] ping 1.1.1.1 -> " (if (:ok? p) "OK" "FAIL"))))
          {:online? (:ok? p) :method :icmp-ping :detail (assoc r :ping p)})))))
