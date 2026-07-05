# v2 Plan — Security Hardening + Features

Scoped 2026-07-05 from a security/improvement review of all three components. Each phase gates on
explicit sign-off and live on-device verification before the next begins. No work below has started
unless its checkbox is ticked.

## Review context

The trust model (VPN mesh provides transport encryption, bearer token gates the app layer) stays as-is.
These items close gaps *within* that model. Descoped by decision: changing the agent's `0.0.0.0` binding
(local + remote reachability is intended — docs get corrected instead), and encrypting the relay→agent
LAN hop (documented as an accepted risk instead).

## Phase 1 — Paper cuts (small, independent, no new attack surface)

**Status 2026-07-05: COMPLETE — implemented and live-verified on all three components.**
Relay tests pass (18/18, incl. two new tests). Live-verified: relay redeployed on the NAS, agent
rebuilt/reinstalled on the PC, Settings validation tested on-device, and the signed R8 release
build verified on-device (Restart end-to-end: Keystore token read, Retrofit, Gson reflection).
Verification detour worth remembering: the first on-device "R8 test" turned out to have run the
*debug* build — no release signing existed, and v0.1's published APK was hash-identical to
`app-debug.apk`. Fixed by creating a real release keystore (outside the repo, wired via gitignored
`keystore.properties`) and re-testing the properly signed R8 APK. Both lessons (verify the release
artifact itself; no real hostnames/IPs as UI placeholders — the Settings screen had leaked the
developer's LAN IP and MagicDNS name into distributed APKs) are now in the workspace
`CODING_STANDARDS.md`. Shipped as GitHub release v0.2.

- [x] **Docs: correct the agent-binding claim** — `architecture.md` says the agent "never listens on
  anything but the LAN interface"; `Program.cs` binds `0.0.0.0`. Fixed the doc to match reality
  (all interfaces, incl. e.g. a Tailscale interface if present; firewall is the boundary), plus the
  same claim in `my-home-pc-agent/README.md`.
- [x] **Docs: document the cleartext relay→agent hop** — paragraph in `architecture.md`: the LAN hop
  carrying `AGENT_SHARED_SECRET` is plain HTTP; what that assumes about the LAN. Zero code.
- [x] **Agent: reject the placeholder secret** — `changeme-must-match-relay-env` was non-empty and passed
  the old startup check. Now rejected at startup *and* in `IsAuthorized` (config hot-reload could
  reintroduce it while running). Local `dotnet run` testing now needs `$env:AGENT_SHARED_SECRET` set
  (README updated).
- [x] **Relay: gate Swagger behind `RELAY_ENABLE_DOCS`** — `/docs` + `/redoc` + `/openapi.json` off by
  default, env flag re-enables for debugging sessions. `/health` no longer reports the version.
- [x] **Agent: drop hostname from `/health`** — unauthenticated endpoint shouldn't leak the machine name.
- [x] **App: enable R8 in release build** — `optimization { enable = true }` plus the AGP 9-required
  `android.r8.gradual.support=true` in `gradle.properties`; keep rules for the Gson-reflected API
  models live in `app/src/main/keepRules/gson.keep` (AGP 9's replacement for `proguardFiles`).
  Verified live 2026-07-05 on the signed R8 build (Keystore/reflection paths OK — see status
  note above for the signing setup this required).
- [x] **App: Settings input validation** — host fields are sanitized (trim, strip scheme, trailing
  slash), port must be 1–65535, token is trimmed; friendly error shown and Save no longer navigates
  away on invalid input. (The old behavior surfaced as a confusing Retrofit "Invalid URL" failure
  message, not a crash — the resolver calls were already inside try/catch.)

## Phase 2 — Relay/agent hardening

- [ ] **Rate limiting + 401 logging (relay)** — throttle repeated auth failures, log each rejection
  (timestamp + source) for an audit trail.
- [ ] **Rate limiting + 401 logging (agent)** — same treatment for `AGENT_SHARED_SECRET` guesses.
- [ ] **Persist `last_seen`** — survives container restarts (tiny file) instead of resetting to null.
- [ ] **`agent_reachable` pass-through** — status endpoint actually checks agent `/health` instead of
  always returning null (already reserved in the API contract).

## Phase 3 — App: SSID-gated Local probe

- [ ] **Known-network check for the Local probe** — the app currently sends the bearer token to whatever
  answers `/health` at the configured Local IP, on any network. Fix: user picks home SSID(s) in
  Settings; the Local probe only runs when connected to one. Requires runtime location permission —
  the permission UX gets its own on-device test cycle, hence its own phase.

## Phase 4 — Features

Order within the phase:

- [ ] **Biometric confirm for shutdown/restart** — `BiometricPrompt` on top of the existing dialog.
- [ ] **Notify when PC is up/down** — WorkManager polls `/status` after wake/shutdown, posts a
  notification on the transition.
- [ ] **Quick Settings tile / widget** — one-tap Wake without opening the app.
- [ ] **`sleep` capability** — last because it's the only feature touching all three components
  (new agent route + relay capability + app button) and requires redeploying the agent to the PC.

## Deferred / accepted risks

- Full TLS with pinned certs (would structurally fix the Local-probe issue and remove
  `usesCleartextTraffic`) — revisit if the SSID check proves annoying in practice.
- Relay→agent hop stays cleartext HTTP on the LAN (documented in Phase 1).
- Agent keeps binding all interfaces (documented in Phase 1).
