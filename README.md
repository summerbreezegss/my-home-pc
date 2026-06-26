# Remote PC Wake-Up

Turn on a home Windows PC from anywhere in the world using an Android app and a small relay service — no port forwarding, no proprietary cloud service, and not locked to any one remote-access technology.

See [`docs/architecture.md`](docs/architecture.md) for how the pieces fit together and why, and [`docs/api-contract.md`](docs/api-contract.md) for the REST API.

## Components

| Path | What | Runs on |
|---|---|---|
| [`relay/`](relay/) | FastAPI service that sends Wake-on-LAN packets and checks PC status | Any always-on Docker host on the target PC's LAN (a Synology NAS in this project's own setup) |
| [`android-app/`](android-app/) | Kotlin + Compose app | Your phone |

The Android app needs **no code changes** to adopt for your own setup — all configuration (relay address, port, token) happens at runtime through the app's Settings screen, stored locally and encrypted on your phone. Only the relay needs per-deployment setup, since it's the piece that has to know about your specific PC.

## Setting this up for your own PC

1. [`docs/windows-wol-setup-checklist.md`](docs/windows-wol-setup-checklist.md) — BIOS + Windows config on the target PC. Do this first and confirm Wake-on-LAN works with some existing tool (even a phone app with a WoL feature) before touching the relay — it's the highest-risk, hardware-dependent step, and isolating it first means you're not debugging your own code and a BIOS setting at the same time.
2. Copy `relay/devices.example.yaml` to `relay/devices.yaml` and `relay/.env.example` to `relay/.env`, and fill in your own PC's MAC/IP and a generated token. Deploy `relay/` to your Docker host (see [`relay/README.md`](relay/README.md)) with **host networking** — required for the Wake-on-LAN broadcast to leave the container.
3. Decide how you'll reach the relay when away from home (a VPN mesh like Tailscale/WireGuard, or your own port-forwarding/DDNS setup) and confirm it works before involving the app.
4. Build/install `android-app/` (`cd android-app && ./gradlew installDebug` with a device connected, or open in Android Studio) and configure it via the Settings screen: a Local host (your relay's LAN IP) and a Remote host (whatever you set up in step 3), same port and token either way.

Full phased build/verification notes from this project's own development are in `docs/`.
