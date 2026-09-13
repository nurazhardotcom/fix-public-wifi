;; fix-public-wifi test runner.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.test-runner
  (:require [clojure.test :as t]
            fix-public-wifi.probe-test
            fix-public-wifi.portal-test
            fix-public-wifi.cli-test))

(defn run []
  (let [{:keys [fail error] :as res}
        (t/run-tests 'fix-public-wifi.probe-test
                     'fix-public-wifi.portal-test
                     'fix-public-wifi.cli-test)]
    (println res)
    (System/exit (if (zero? (+ fail error)) 0 1))))
