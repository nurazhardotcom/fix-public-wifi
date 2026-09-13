;; fix-public-wifi cli tests.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.cli-test
  (:require [clojure.test :refer [deftest is]]
            [fix-public-wifi.cli :as cli]
            [fix-public-wifi.core :as core]))

(deftest parse-both-backends-test
  (let [{:keys [options]} (cli/parse-args ["--probe-only" "--timeout" "5"])]
    (is (true? (:probe-only options)))
    (is (= 5 (long (:timeout options))))))

(deftest help-flag-test
  (let [{:keys [options]} (cli/parse-args ["--help"])]
    (is (true? (:help options)))))

(deftest run-flow-offline-shape-test
  ;; run-flow with an unroutable probe host should return exit 2 (timeout), no throw.
  (let [res (core/run-flow {:probe-urls ["http://10.255.255.1/"]
                            :timeout-ms 800
                            :probe-only? true})]
    (is (contains? #{1 2} (:exit res)))))
