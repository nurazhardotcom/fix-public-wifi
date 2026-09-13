;; fix-public-wifi.cli — argument parsing over BOTH built-in CLI libs.
;; Copyright (C) 2026 nurazhardotcom
;; SPDX-License-Identifier: MIT — see LICENSE.

(ns fix-public-wifi.cli
  "Unified CLI parsing supporting BOTH Babashka built-ins (per user request):
   - clojure.tools.cli/parse-opts (spec-mandated)
   - babashka.cli/parse-opts      (idiomatic bb)
  Strategy: prefer babashka.cli when its spec parses cleanly, else fall back
  to clojure.tools.cli, else a tiny manual parser (clojure.string only).
  All three produce the same {:options {...} :errors [...]} shape."
  (:require [clojure.string :as str]
            [babashka.cli :as bcli]
            [clojure.tools.cli :as tcli]))

(def tools-cli-options
  [["-u" "--probe-url URL" "Probe target (repeatable via comma). Default: Apple 204 + NeverSSL."
    :id :probe-url :default nil]
   ["-t" "--timeout SECS" "Per-request timeout in seconds."
    :id :timeout :default 10 :parse-fn #(Integer/parseInt %) :validate [#(<= 1 % 120) "1-120"]]
   [nil "--portal-url URL" "Skip detection; auth directly against this gateway URL."
    :id :portal-url :default nil]
   [nil "--probe-only" "Only detect; do not POST any auth payload."
    :id :probe-only :default false]
   [nil "--dry-run" "Parse + print payload without sending it."
    :id :dry-run :default false]
   [nil "--no-ping" "Skip ICMP fallback in verification."
    :id :no-ping :default false]
   ["-v" "--verbose" "Verbose logging." :id :verbose :default false]
   ["-h" "--help" "Show help." :id :help :default false]])

(def bb-cli-spec
  {:probe-url {:desc "Probe target" :coerce :string}
   :timeout {:desc "Timeout secs" :coerce :long :default 10}
   :portal-url {:desc "Gateway URL" :coerce :string}
   :probe-only {:desc "Detect only" :coerce :boolean :default false}
   :dry-run {:desc "No POST" :coerce :boolean :default false}
   :no-ping {:desc "Skip ping" :coerce :boolean :default false}
   :verbose {:desc "Verbose" :coerce :boolean :default false :alias :v}
   :help {:desc "Help" :coerce :boolean :default false :alias :h}})

(defn- manual-parse
  "Last-resort parser using only clojure.string. Handles --k=v, --k v, -hv."
  [args]
  (loop [xs args opts {:timeout 10}]
    (if (empty? xs)
      {:options opts :errors nil}
      (let [[a & more] xs]
        (cond
          (#{ "-h" "--help"} a) (recur more (assoc opts :help true))
          (#{ "-v" "--verbose"} a) (recur more (assoc opts :verbose true))
          (#{"--probe-only"} a) (recur more (assoc opts :probe-only true))
          (#{"--dry-run"} a) (recur more (assoc opts :dry-run true))
          (#{"--no-ping"} a) (recur more (assoc opts :no-ping true))
          (str/starts-with? a "--probe-url=")
          (recur more (assoc opts :probe-url (subs a 12)))
          (= a "--probe-url")
          (recur (rest more) (assoc opts :probe-url (first more)))
          (str/starts-with? a "--portal-url=")
          (recur more (assoc opts :portal-url (subs a 13)))
          (= a "--portal-url")
          (recur (rest more) (assoc opts :portal-url (first more)))
          (str/starts-with? a "--timeout=")
          (recur more (assoc opts :timeout (Integer/parseInt (subs a 10))))
          (= a "--timeout")
          (recur (rest more) (assoc opts :timeout (Integer/parseInt (first more))))
          :else (recur more opts))))))

(defn parse-args
  "Returns {:options {...} :errors [...] :backend :babashka.cli|:tools.cli|:manual}."
  [args]
  (let [args (vec (map str args))]
    (try
      (let [opts (bcli/parse-opts args {:spec bb-cli-spec :error-fn (fn [{:keys [msg]}] (throw (ex-info msg {})))})]
        {:options (merge {:timeout 10} opts) :errors nil :backend :babashka.cli})
      (catch Throwable _
        (try
          (let [{:keys [options errors]} (tcli/parse-opts args tools-cli-options)]
            {:options options :errors errors :backend :tools.cli})
          (catch Throwable t2
            (assoc (manual-parse args) :backend :manual :manual-error (ex-message t2))))))))

(def help-text
  (str "fix-public-wifi — detect + auto-auth public Wi-Fi captive portals\n\n"
       "USAGE: bb -m fix-public-wifi.core [OPTS]\n\n"
       (:summary (tcli/parse-opts [] tools-cli-options))
       "\nExit codes: 0 online/success | 1 portal detected but auth failed | 2 network timeout\n"
       "Parsers tried in order: babashka.cli -> clojure.tools.cli -> manual (all built-in).\n"
       "Examples:\n"
       "  bb -m fix-public-wifi.core --verbose            # full run (Changi #ChangiWiFi etc.)\n"
       "  bb -m fix-public-wifi.core --probe-only         # detect only\n"
       "  bb -m fix-public-wifi.core --dry-run --verbose  # show payload, send nothing\n"))
