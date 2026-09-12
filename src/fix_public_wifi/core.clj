;; fix-public-wifi.core — CLI entry point, exit codes 0/1/2.
;; Copyright (C) 2026 nurazhardotcom
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU Affero General Public License for more details.
;; You should have received a copy of the GNU Affero General Public License
;; along with this program. If not, see <https://www.gnu.org/licenses/>.

(ns fix-public-wifi.core
  "Orchestrates probe -> auth -> verify.
  Exit codes: 0 success/online, 1 portal auth failed, 2 network timeout."
  (:require [fix-public-wifi.probe :as probe]
            [fix-public-wifi.portal :as portal]
            [fix-public-wifi.verify :as verify]
            [fix-public-wifi.cli :as cli]
            [clojure.string :as str])
  (:gen-class))

(def exit-ok 0)
(def exit-auth-failed 1)
(def exit-timeout 2)

(defn- split-urls [s]
  (when s
    (if (str/includes? s ",")
      (mapv str/trim (str/split s #","))
      [(str/trim s)])))

(defn run-flow
  "Testable core (no System/exit). Returns {:exit n :message ...}."
  [{:keys [probe-urls timeout-ms portal-url probe-only? dry-run? no-ping? verbose?]}]
  (let [timeout-ms (or timeout-ms 10000)]
    ;; 1. probe (or skip if explicit portal-url)
    (if portal-url
      (if probe-only?
        {:exit exit-ok :message (str "portal-url given with --probe-only; nothing to do: " portal-url)}
        (let [auth (portal/auto-auth! portal-url {:timeout-ms timeout-ms
                                                  :dry-run? dry-run?
                                                  :verbose verbose?})]
          (if dry-run?
            {:exit exit-ok :message "dry-run OK" :auth auth}
            (let [v (verify/verify! {:urls probe-urls :timeout-ms timeout-ms
                                     :verbose verbose? :ping? (not no-ping?)})]
              (if (:online? v)
                {:exit exit-ok :message "authenticated + verified" :auth auth :verify v}
                {:exit exit-auth-failed :message "auth submitted but still captive" :auth auth :verify v})))))
      ;; normal: probe first
      (let [p (probe/probe! {:urls probe-urls :timeout-ms timeout-ms :verbose verbose?})]
        (cond
          (:online? p)
          {:exit exit-ok :message "already online (no portal)" :probe p}

          (:timeout? p)
          {:exit exit-timeout :message (str "network timeout: " (:message p)) :probe p}

          probe-only?
          {:exit exit-auth-failed
           :message (str "portal detected: " (get-in p [:detection :location]))
           :probe p}

          :else
          (let [loc (get-in p [:detection :location])]
            (if (nil? loc)
              {:exit exit-auth-failed :message "portal detected but no Location to auth against" :probe p}
              (let [auth (portal/auto-auth! loc {:timeout-ms timeout-ms
                                                 :dry-run? dry-run?
                                                 :verbose verbose?})]
                (cond
                  (and dry-run? (:ok? auth))
                  {:exit exit-ok :message "dry-run OK" :probe p :auth auth}

                  (not (:ok? auth))
                  {:exit exit-auth-failed
                   :message (str "auth failed: " (or (:reason auth) (:error auth)))
                   :probe p :auth auth}

                  :else
                  (let [v (verify/verify! {:urls probe-urls :timeout-ms timeout-ms
                                           :verbose verbose? :ping? (not no-ping?)})]
                    (if (:online? v)
                      {:exit exit-ok :message "authenticated + verified" :probe p :auth auth :verify v}
                      {:exit exit-auth-failed
                       :message "auth submitted but still captive"
                       :probe p :auth auth :verify v})))))))))))

(defn -main
  [& args]
  (let [{:keys [options errors backend]} (cli/parse-args args)
        {:keys [probe-url timeout portal-url probe-only dry-run no-ping verbose help]} options
        exit-code
        (cond
          :else
          (do
            (when verbose (println (str "[cli] parser=" (name (or backend :unknown)))))
            (cond
              help (do (println cli/help-text) exit-ok)
              errors (do (doseq [e errors] (println "error:" e)) (println cli/help-text) exit-timeout)
              :else
              (let [urls (or (split-urls probe-url) probe/default-probe-urls)
                    res (run-flow {:probe-urls urls
                                   :timeout-ms (* 1000 (or timeout 10))
                                   :portal-url portal-url
                                   :probe-only? (boolean probe-only)
                                   :dry-run? (boolean dry-run)
                                   :no-ping? (boolean no-ping)
                                   :verbose? (boolean verbose)})]
                (println (:message res))
                (when verbose (println (str "[done] exit=" (:exit res))))
                (:exit res)))))]
    ;; Propagate spec exit codes (0/1/2) to the OS. run-flow stays pure for tests.
    (System/exit (if (integer? exit-code) exit-code 0))))

;; Babashka: (bb -m fix-public-wifi.core) calls -main; ensure exit code propagates.
(when (= *file* (System/getProperty "babashka.file"))
  (let [code (apply -main *command-line-args*)]
    (System/exit (if (integer? code) code 0))))
