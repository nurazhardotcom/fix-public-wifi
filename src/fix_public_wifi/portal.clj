;; fix-public-wifi.portal — landing-page parse + TOS-accept POST.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.portal
  "Automated handshake: fetch portal HTML, extract form action + hidden
  inputs + CSRF + TOS toggles, POST an accept payload.
  Zero-dependency: regex parsing only (no jsoup/selmer needed for forms)."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; URL helpers (pure)
;; ---------------------------------------------------------------------------

(defn resolve-url
  "Resolve possibly-relative form action against the portal page URL."
  [page-url action]
  (cond
    (or (nil? action) (str/blank? action)) page-url
    (or (str/starts-with? action "http://")
        (str/starts-with? action "https://")) action
    (str/starts-with? action "/")
    (let [u (java.net.URI. page-url)]
      (str (.getScheme u) "://" (.getAuthority u) action))
    :else
    (let [base (if (str/ends-with? page-url "/") page-url (str page-url "/"))]
      ;; strip query from base for relative resolution
      (str (first (str/split base #"\?")) action))))

;; ---------------------------------------------------------------------------
;; HTML extraction (pure, regex-only)
;; ---------------------------------------------------------------------------

(defn extract-form-action
  "First <form ... action=\"...\"> in HTML, or nil."
  [html]
  (when (string? html)
    (second (re-find #"(?i)<form\b[^>]*?\baction\s*=\s*[\"']([^\"']+)[\"']" html))))

(defn extract-inputs
  "All <input name=... value=...> pairs -> {name value}. Handles any attr order."
  [html]
  (if (not (string? html))
    {}
    (let [tags (re-seq #"(?i)<input\b[^>]*>" html)]
      (into {}
            (comp (map (fn [tag]
                         (let [n (second (re-find #"(?i)\bname\s*=\s*[\"']([^\"']+)[\"']" tag))
                               v (second (re-find #"(?i)\bvalue\s*=\s*[\"']([^\"']*)[\"']" tag))
                               t (second (re-find #"(?i)\btype\s*=\s*[\"']([^\"']+)[\"']" tag))]
                           (when n {:name n :value (or v "") :type (str/lower-case (or t "text"))}))))
                  (filter some?)
                  (map (fn [{:keys [name value]}] [name value])))
            tags))))

(def csrf-keys
  #{"csrf" "csrftoken" "_csrf" "authenticity_token" "__requestverificationtoken"
    "csrf_token" "token" "sessiontoken" "session_token"})

(defn extract-csrf
  "Subset of inputs that look like CSRF/session tokens. Also checks <meta name=csrf-token>."
  [html inputs]
  (let [from-inputs (into {} (filter (fn [[k _]] (contains? csrf-keys (str/lower-case k))) inputs))
        meta-tok (when (string? html)
                   (or (second (re-find #"(?i)<meta\b[^>]*name\s*=\s*[\"']csrf-token[\"'][^>]*content\s*=\s*[\"']([^\"']+)[\"']" html))
                       (second (re-find #"(?i)<meta\b[^>]*content\s*=\s*[\"']([^\"']+)[\"'][^>]*name\s*=\s*[\"']csrf-token[\"']" html))))]
    (cond-> from-inputs
      meta-tok (assoc "_csrf_meta" meta-tok))))

(def tos-pattern #"(?i)agree|accept|tos|aup|terms|consent|privacy|acknowledge")

(defn tos-field-names
  "Input names that look like TOS/accept toggles."
  [inputs]
  (filter #(re-find tos-pattern (str %)) (keys inputs)))

(defn build-auth-payload
  "Merge hidden inputs + CSRF + forced TOS-accept values.
   Extra TOS checkboxes found in the form are set to \"on\"/accepted."
  [inputs csrf extra-accept?]
  (let [base (merge inputs csrf)
        tos-names (tos-field-names inputs)
        forced (into {} (map (fn [n] [n "on"]) tos-names))]
    (cond-> (merge base forced)
      extra-accept? (assoc "accept" "on"
                           "agree" "on"
                           "tos_accepted" "true"))))

;; ---------------------------------------------------------------------------
;; Network
;; ---------------------------------------------------------------------------

(defn parse-set-cookies
  "Fold Set-Cookie headers into a single Cookie header value."
  [headers]
  (let [raw (or (get headers "set-cookie") (get headers "Set-Cookie"))]
    (when raw
      (let [coll (if (sequential? raw) raw [raw])]
        (str/join "; " (map #(first (str/split % #";")) coll))))))

(defn fetch-portal!
  "GET the portal landing page (plain HTTP preserved). Returns {:html :cookies :final-url :status}."
  [portal-url timeout-ms]
  (let [client (http/client {:follow-redirects :normal :connect-timeout timeout-ms})
        resp (http/get portal-url {:client client :timeout timeout-ms :throw false
                                   :headers {"User-Agent" "fix-public-wifi/bb"
                                             "Accept" "text/html,*/*"}})
        headers (:headers resp)]
    {:status (:status resp)
     :html (:body resp)
     :cookies (parse-set-cookies headers)
     :final-url portal-url
     :headers headers}))

(defn submit-auth!
  "POST form params to the resolved action. Returns {:status :body :ok?}."
  [{:keys [action-url payload cookies timeout-ms]}]
  (try
    (let [client (http/client {:follow-redirects :normal :connect-timeout timeout-ms})
          resp (http/post action-url
                           (cond-> {:client client
                                    :timeout timeout-ms
                                    :throw false
                                    :form-params payload
                                    :headers {"User-Agent" "fix-public-wifi/bb"
                                              "Referer" action-url
                                              "Origin" action-url}}
                             cookies (assoc-in [:headers "Cookie"] cookies)))]
      {:status (:status resp) :body (:body resp)
       :ok? (contains? #{200 201 202 203 204 302 303 307} (:status resp))})
    (catch Exception e
      {:status nil :body nil :ok? false :error (ex-message e)})))

(defn auto-auth!
  "Full handshake against portal-url. dry-run? only prints what WOULD be sent.
   Returns {:ok? :action-url :payload :submit ...}."
  [portal-url {:keys [timeout-ms dry-run? verbose] :or {timeout-ms 10000}}]
  (when verbose (println (str "[auth] fetching portal page: " portal-url)))
  (let [{:keys [html cookies status]} (fetch-portal! portal-url timeout-ms)]
    (when verbose (println (str "[auth] landing status=" status " html-bytes=" (count (or html "")))))
    (if (or (nil? html) (str/blank? (str html)))
      {:ok? false :reason :empty-landing-page}
      (let [action (extract-form-action html)
            inputs (extract-inputs html)
            csrf (extract-csrf html inputs)
            payload (build-auth-payload inputs csrf true)
            action-url (resolve-url portal-url action)]
        (when verbose
          (println (str "[auth] form action=" (pr-str action) " -> " action-url))
          (println (str "[auth] inputs=" (pr-str (keys inputs))))
          (println (str "[auth] csrf=" (pr-str (keys csrf))))
          (println (str "[auth] tos-fields=" (pr-str (vec (tos-field-names inputs))))))
        (if dry-run?
          {:ok? true :dry-run? true :action-url action-url :payload payload :reason :dry-run}
          (do
            (when verbose (println (str "[auth] POST " action-url " " (count payload) " fields")))
            (let [res (submit-auth! {:action-url action-url :payload payload
                                     :cookies cookies :timeout-ms timeout-ms})]
              (assoc res :action-url action-url :payload (keys payload)))))))))
