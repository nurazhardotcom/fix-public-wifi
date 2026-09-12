# Changi Airport walkthrough (#ChangiWiFi)

Concrete instance of the archify sequence [`docs/archify/changi.sequence.json`](archify/changi.sequence.json). Open [`docs/archify/index.html`](archify/index.html) for the diagram.

## Context

- Network: `#ChangiWiFi` (open, portal-gated). Gateway family varies by terminal (Cisco ISE / Aruba style).
- Client: laptop with `bb` (Babashka) + this repo. No browser needed.
- Principle (from archify problem diagram): **never probe `https://` first** — the gateway's cert won't match, HSTS pins burn you. Probe plain HTTP and read the `302 Location`.

## Transcript (what the tool does)

```
$ bb -m fix-public-wifi.core --verbose
[cli] parser=babashka.cli
[probe] GET http://captive.apple.com/generate_204
[probe] http://captive.apple.com/generate_204 -> 302 PORTAL (http-redirect)
[auth] fetching portal page: http://auth.changi/login?mac=AA:BB&ip=10.20.30.40&session=abc123&zone=t2
[auth] landing status=200 html-bytes=4120
[auth] form action="/auth/accept" -> http://auth.changi/auth/accept
[auth] inputs=("csrf" "mac" "accept_tos")
[auth] csrf=("csrf")
[auth] tos-fields=["accept_tos"]
[auth] POST http://auth.changi/auth/accept 5 fields
[verify] re-probing for internet access...
[probe] GET http://captive.apple.com/generate_204
[probe] http://captive.apple.com/generate_204 -> 204 OPEN (generate-204-clean)
authenticated + verified
```

## Failure modes

- Portal HTML has no `<form>` (JS-only portal): tool reports `auth failed` (exit 1) and prints the landing status/bytes — finish in browser once, then re-run to verify (exit 0).
- No route / airplane mode: all probes fail → ping `1.1.1.1` fails → exit 2.
