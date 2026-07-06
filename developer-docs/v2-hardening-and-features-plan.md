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

Status 2026-07-05: all items implemented and committed. Relay tests pass (25/25, incl. seven
new). Both lockouts verified against the *live* deployments: the reinstalled agent via
Postman (user) and the redeployed NAS relay via wrong-token curl probes (5×401 then 429).
Verification detour: the first agent lockout test saw only 401s because the installed service
was still the pre-Phase-2 build — same stale-deploy lesson as ever; rebuilt installer fixed it.
Lighter remaining checks (not re-confirmed explicitly): `last_seen` surviving a container
restart and `agent_reachable: true` via an authenticated `curl`. Server components shipped as
zips on the v0.2 GitHub release (see below).

- [x] **Rate limiting + 401 logging (relay)** — 5 consecutive failures per source IP → 429 +
  `Retry-After` for 5 min (correct token included, so the lockout isn't a guessing oracle);
  tunable via `RELAY_AUTH_MAX_FAILURES`/`RELAY_AUTH_LOCKOUT_SECONDS`; every rejection logged
  with timestamp + source IP + path (new `app/ratelimit.py`, dependency-free, in-memory).
- [x] **Rate limiting + 401 logging (agent)** — same scheme in `Program.cs`; rejections land in
  the Windows Application event log (timestamped) via the service's default logging.
- [x] **Persist `last_seen`** — atomic-write JSON at `data/last_seen.json`, volume-mounted in
  docker-compose so it survives rebuilds; corrupt/missing file degrades to empty, never crashes.
- [x] **`agent_reachable` pass-through** — probes agent `/health` (1.5 s timeout) when the device
  has an `agent_port`; `null` without one; `false` without probing when ping already failed.
  API contract updated (plus corrected the stale `/devices` example that still showed internal
  fields).

## Phase 3 — App: SSID-gated Local probe

Status 2026-07-05: COMPLETE — implemented and live-verified on the S24 Ultra (full test
sequence: permission flow, trust prompt, Local/Remote switching via the Settings toggles,
silent Remote fallback on mobile data, fresh prompt + "Not now" on a second network).
Design changed from the original "user picks home SSID(s) in Settings" to an OS-style
**trust-on-first-encounter** flow (user's call during scoping): on an unrecognized Wi-Fi
network the app prompts "Trust this network?"; decisions accumulate in an encrypted
SSID→trusted store, reviewable/reversible via a Trusted Networks section in Settings.
Unknown/untrusted/undecidable (no permission, location off, mobile data) all silently use
Remote, so no state can break the app. Debug + R8 release builds both compile; unit tests
cover the trust-map codec and SSID normalization (reflection-free Gson, no new keep rules).

**Post-release fix, same day**: the originally published v0.3 had a real gap, found via a
Lenovo Legion Y700 tablet upgrade test. Two bugs, both in `NetworkTrustGate`: (1) an
unguarded `WifiNetworkMonitor.currentSsid()` call meant any exception during SSID lookup
escaped `evaluate()` entirely, skipped the trust-dialog assignment, and surfaced one level up
as a misleading "Can't reach relay" error instead of failing safe like every other unreadable-
SSID case — fixed by wrapping the lookup in its own try/catch (logs to `Log.e`, falls back to
`Unavailable`). (2) Once that was fixed, the real root cause surfaced: the tablet's app-level
location permission was granted, but its system-wide Location toggle was off — Android
redacts the SSID either way, and the app had no way to tell these two cases apart, so it just
silently used Remote with zero explanation. Added a new `LocationServicesOff` state (distinct
from a plain missing-permission `Unavailable`) plus a hint in Settings → Trusted Networks
with a button that deep-links straight to the system Location screen, re-checked automatically
on resume. Live-verified on the same tablet after the fix.

A `/code-review` pass on that fix (high effort, 8 finder angles + verification) caught that it
was itself incomplete: the new `isLocationServicesEnabled()` call was left unguarded — the
same bug class one line below it, in the exact function this fix was patching — reachable via
an uncaught crash in `DeviceDetailScreen` (no outer try/catch there at all) or a repeat of the
misleading "Can't reach relay" message via Settings' Test Connection. Also fixed in the same
pass: `catch (e: Exception)` was swallowing `CancellationException`, breaking structured
concurrency; the Location-off check ran before confirming the device was even on Wi-Fi, so a
mobile-data user got sent to "turn on Location" for a fix that wouldn't help them (now gated
behind a new `WifiNetworkMonitor.isConnectedToWifi()` check); `LocationServicesOff` had no
device-list-level surfacing despite its own doc comment saying it's "worth surfacing" (added a
matching dismissible dialog there, session-scoped since it's live system state, not a
per-network decision); Settings' hint could show even with no Local Host configured; the
"Open Settings" deep link had no `ActivityNotFoundException` guard; and the per-screen
location-check logic was duplicated between `NetworkTrustGate` and `SettingsViewModel` instead
of the latter just reusing the former's `evaluate()` result. All findings fixed and re-verified
(unit tests + both build variants) before this replaced the original v0.3 release/tag, rather
than shipping as v0.4, since the as-published v0.3 had a real, user-facing gap.

- [x] **Known-network check for the Local probe** — the app previously sent the bearer token to
  whatever answered `/health` at the configured Local IP, on any network. Now every Local probe
  (device list, device detail, and Settings' Test Connection — all three call sites) is gated by
  `NetworkTrustGate`: only networks the user explicitly trusted allow it. Runtime location
  permission (Android's requirement for reading the SSID) is requested with a rationale dialog,
  only once a Local Host is configured; declining is remembered and reversible in Settings.

## Phase 4 — Features

Scope revised 2026-07-06 (user decision, mockups approved via artifact before build): biometric
confirm and the `sleep` capability are **dropped** — not deferred within this phase, out of v2
entirely unless re-requested. What remains is app-only (no relay or agent changes, nothing to
redeploy on the NAS or PC). A "Default Device" setting was added to the scope because the
tile/widget fire a single device with a single tap, and with more than one device configured
something has to resolve which device that is — without guessing.

**Status 2026-07-06: COMPLETE — implemented and live-verified on the S24 Ultra.** All three
items built, unit tests pass, debug and R8 release builds both compile.

**Widget bugs found and fixed during live verification**: the freshly-built widget failed to
add at all ("Couldn't add widget") — root cause was `widget_wake.xml`'s divider using a plain
`<View>`, which RemoteViews' filtered inflater rejects (only `@RemoteView`-annotated classes
inflate; `android.view.View` isn't one). Fixed by switching the divider to a 1dp `ImageView`
with a background color. The first retest still failed identically — turned out to be a
stale sideloaded debug APK built minutes *before* the fix (same versionCode, so the failure
looked like a real regression); `aapt2 dump xmltree` on both APKs proved the divider element
differed between them. Rebuilding and reinstalling resolved it.

Once the widget rendered, three further state-tracking bugs surfaced during live wake testing:
(1) the background status-polling worker re-rendered the widget on its very first 5-second
poll, before the PC had finished booting, stomping "Wake sent…" with "Offline · tap to wake"
— looked exactly like the tap had failed; (2) the worker's failure/timeout exit paths never
re-rendered the widget at all, so a failed or timed-out wake left it stuck on "Wake sent…"
indefinitely; (3) auto-selecting a default device from the Settings screen updated the stored
value but not the widget, unlike the equivalent path in the device list screen. Fixed by
adding a persisted "waking" flag (`DeviceStateCache.isWaking/setWaking/clearWaking`, set the
moment a wake is requested rather than only reflecting the instant of the tap) that the
widget's render function checks ahead of the cached online/offline state, and by re-rendering
on every worker exit path (success, failure, and timeout) instead of only the "still polling"
case. Live-verified: the widget now holds "Wake sent…" through the full boot window on both
the wake tap and the "Open" button, and the QS tile's wake tap behaves identically since it
shares the same underlying watch path.

- [x] **Notify when PC is up/down** — after a Wake/Restart/Shutdown is sent, a WorkManager job
  keeps polling `/status` in the background and posts a notification on the transition to the
  target state, so the result arrives even with the app closed. Same 12-attempt window as the
  in-app poll; silent give-up on timeout.
- [x] **Default Device setting** — Settings gains a radio list of the relay's devices. With
  exactly one device it's selected automatically; with several, nothing is selected until the
  user picks (the tile/widget show a not-configured state rather than guessing). Stored as
  `defaultDeviceId` in `SettingsRepository`; read only by the tile and widget — device list and
  detail screens are unaffected.
- [x] **Quick Settings tile / home-screen widget** — both target the Default Device. The widget
  has two tap targets: **Wake** fires the wake without opening the app (result arrives via the
  item-1 notification), **Open** launches straight into that device's detail screen. The QS tile
  is single-tap Wake only, icon state from the last-known online/offline status. With no default
  set, both surfaces open Settings → Default Device instead.

Dropped from this phase (2026-07-06):

- ~~**Biometric confirm for shutdown/restart**~~ — cut at scoping; the existing confirm dialogs
  stay as the only gate.
- ~~**`sleep` capability**~~ — cut at scoping; would have been the only item touching all three
  components. `capabilities` gating in `devices.yaml` remains ready for it if it returns.

## Deferred / accepted risks

- Full TLS with pinned certs (would structurally fix the Local-probe issue and remove
  `usesCleartextTraffic`) — revisit if the SSID check proves annoying in practice.
- Relay→agent hop stays cleartext HTTP on the LAN (documented in Phase 1).
- Agent keeps binding all interfaces (documented in Phase 1).
