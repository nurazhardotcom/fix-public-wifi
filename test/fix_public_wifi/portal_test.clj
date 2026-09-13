;; fix-public-wifi portal tests — pure HTML parsing, no network.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.portal-test
  (:require [clojure.test :refer [deftest is]]
            [fix-public-wifi.portal :as po]))

(def changi-html
  "<html><body>
    <form action=\"/auth/accept\" method=\"post\">
      <input type=\"hidden\" name=\"csrf\" value=\"tok-123\"/>
      <input type=\"hidden\" name=\"mac\" value=\"AA:BB:CC\"/>
      <input type=\"checkbox\" name=\"accept_tos\" value=\"\"/>
      <input type=\"submit\" value=\"Connect\"/>
    </form></body></html>")

(deftest extract-action-test
  (is (= "/auth/accept" (po/extract-form-action changi-html)))
  (is (nil? (po/extract-form-action "<html>no form</html>"))))

(deftest extract-inputs-test
  (let [m (po/extract-inputs changi-html)]
    (is (= "tok-123" (get m "csrf")))
    (is (= "AA:BB:CC" (get m "mac")))))

(deftest tos-payload-test
  (let [inputs (po/extract-inputs changi-html)
        csrf (po/extract-csrf changi-html inputs)
        payload (po/build-auth-payload inputs csrf true)]
    (is (= "tok-123" (get payload "csrf")))
    (is (= "on" (get payload "accept_tos")))
    (is (= "on" (get payload "accept")))))

(deftest resolve-url-test
  (is (= "http://auth.example/auth/accept"
         (po/resolve-url "http://auth.example/login?x=1" "/auth/accept")))
  (is (= "https://a.example/x" (po/resolve-url "http://b.example/" "https://a.example/x"))))
