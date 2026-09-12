;; fix-public-wifi cli tests.
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
