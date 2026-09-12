;; fix-public-wifi tests — pure fns only, no network.
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

(ns fix-public-wifi.probe-test
  (:require [clojure.test :refer [deftest is testing]]
            [fix-public-wifi.probe :as p]))

(deftest parse-query-params-test
  (is (= {"mac" "aa:bb" "ip" "10.0.0.5"} (p/parse-query-params "mac=aa%3Abb&ip=10.0.0.5")))
  (is (= {"zone" "9"} (p/parse-query-params "https://portal.example/login?zone=9")))
  (is (= {} (p/parse-query-params nil)))
  (is (= {} (p/parse-query-params ""))))

(deftest classify-apple-204-test
  (let [c (p/classify-response {:status 204 :headers {} :body "" :url "http://captive.apple.com/generate_204"})]
    (is (false? (:portal? c)))))

(deftest classify-redirect-test
  ;; Changi-style: hijack -> 302 to airport auth gateway with session tokens
  (let [loc "http://auth.changiairport.example/login?mac=AA:BB&ip=10.20.30.40&session=abc123&zone=changi-t2"
        c (p/classify-response {:status 302 :headers {"location" loc} :body "" :url "http://neverssl.com"})]
    (is (true? (:portal? c)))
    (is (= :http-redirect (:reason c)))
    (is (= "abc123" (get (:params c) "session")))
    (is (= "changi-t2" (get (:params c) "zone")))))

(deftest classify-hijacked-200-test
  (let [c (p/classify-response {:status 200 :headers {} :body "<html>Accept Terms to login to WiFi</html>" :url "http://neverssl.com"})]
    (is (true? (:portal? c)))))

(deftest classify-clean-neverssl-test
  (let [c (p/classify-response {:status 200 :headers {} :body "<html>NeverSSL - connecting ...</html>" :url "http://neverssl.com"})]
    (is (false? (:portal? c)))))
