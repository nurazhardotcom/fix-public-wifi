# fix-public-wifi

Babashka/Clojure CLI that detects public Wi-Fi captive portals over **plain HTTP**, auto-accepts the TOS/login form, and verifies access. Zero external deps. `AGPL-3.0-only`.

## Problem → solution (archify diagrams)

Visual explanation lives in [`docs/archify/`](docs/archify/) — typed JSON IR rendered to a single self-contained HTML file, following the method of [tt-a1i/archify](https://github.com/tt-a1i/archify) (typed IR → deterministic, verifiable diagrams; no Mermaid auto-layout guessing):

- `problem.architecture.json` — **Architecture:** why `https://`-first probing fails (`ERR_CERT_COMMON_NAME_INVALID` / HSTS) and why plain-HTTP `302 + Location` is the real signal.
- `solution.workflow.json` — **Workflow:** probe → classify → parse gateway params → fetch + POST TOS-accept → verify.
- `changi.sequence.json` — **Sequence:** concrete Changi Airport walkthrough (below).
- [`index.html`](docs/archify/index.html) — open offline, dark/light (`T`), no network needed.

## Worked example: Changi Airport (#ChangiWiFi, Terminal 2)

You land at SIN, join `#ChangiWiFi`, and have no route yet:

1. `bb -m fix-public-wifi.core --verbose`
2. CLI GETs `http://captive.apple.com/generate_204` (no TLS, redirects disabled).
3. Gateway hijacks port 80 → `302 Location: http://auth.changi/login?mac=AA:BB&ip=10.20.30.40&session=abc123&zone=t2`.
4. CLI GETs that landing page, extracts `<form action="/auth/accept">`, hidden `csrf=tok-123`, and the `accept_tos` checkbox.
5. CLI POSTs `{csrf, mac, accept_tos=on}` with the gateway cookie.
6. CLI re-probes `http://neverssl.com` → clean `200 NeverSSL` → **exit 0**. Still hijacked → **exit 1**. No route at all → ping `1.1.1.1` fails → **exit 2**.

Safe preview on any network (sends nothing):

```sh
bb -m fix-public-wifi.core --dry-run --verbose
bb -m fix-public-wifi.core --probe-only
```

## Usage

```sh
bb -m fix-public-wifi.core --help
bb -m fix-public-wifi.core --verbose
bb -m fix-public-wifi.core --probe-url http://neverssl.com --timeout 8
bb --portal-url "http://auth.example/login?session=x" --dry-run
```

Exit codes: `0` online/verified · `1` portal detected but auth failed · `2` network timeout.

CLI parsing supports **both** built-ins (`babashka.cli` preferred, `clojure.tools.cli` fallback, manual last resort) — see `src/fix_public_wifi/cli.clj`.

## Layout

```
bb.edn  src/fix_public_wifi/{core,probe,portal,verify,cli}.clj
test/fix_public_wifi/{probe,portal,cli}_test.clj  docs/archify/  LICENSE (AGPL-3.0)
```

## License

AGPL-3.0-only. See [LICENSE](LICENSE). Copyright (C) 2026 nurazhardotcom.
