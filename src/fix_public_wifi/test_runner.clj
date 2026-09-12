;; fix-public-wifi test runner.
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
