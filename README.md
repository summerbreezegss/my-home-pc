# MyHomePC

Wake, restart, or shut down your home Windows PC from anywhere in the world, straight from your phone — no port forwarding, no third-party cloud relay, and not locked to any one remote-access technology.

## What this is

This started from a simple, personal problem: traveling away from home and wanting to turn on (and now restart/shut down) a desktop PC, without buying new hardware (smart plugs, ESP32 boards) and without trusting a vendor's cloud service with access to a home network. The answer was to reuse infrastructure already in place — a Synology NAS that's always on, and a personal VPN mesh (Tailscale) already connecting that NAS, the PC, and a phone — and build the smallest possible system on top of it.

The day-to-day experience is meant to be effortless even though the underlying system has a few moving parts:

- **The Android app** is a normal app: open it, tap Wake/Restart/Shutdown, done. No Tailscale-specific knowledge needed — the app just calls it "Local" or "Remote" and figures out which one to use automatically.
- **The PC Agent** (only needed for Restart/Shutdown) installs with a single double-click — no command line, no manually editing config files. See [Getting started](#getting-started) below.
- Adding a second PC later is a one-line config change, not a rewrite.

## Why this instead of a typical Wake-on-LAN app

Most Wake-on-LAN apps (WolOn, and the various "Wake on LAN" / "Wake PC" apps for iOS) work great on your home Wi-Fi, but remote (over-the-internet) use generally means one of two things, by their own documentation: configure your router to **forward a port from the public internet straight to your LAN**, or set up your own VPN. MyHomePC always takes the second path — and goes further with it:

| | Typical Wake-on-LAN apps | MyHomePC |
|---|---|---|
| Reaching your PC remotely | Often requires forwarding a UDP/TCP port from your router's public IP into your LAN | Always over a VPN mesh you control (Tailscale in this project's setup) — **no inbound port is ever opened on your router** |
| Checking if the PC is online | Since the wake packet itself can't be acknowledged, some apps need a *second* forwarded port just for a connectivity check | The relay checks status itself, from inside your LAN — your PC is never directly reachable from the internet, not even over the VPN |
| Restart / Shutdown | Rarely offered; where it exists (e.g. as a paid/"Pro" SSH feature) it's often general remote command execution | A small purpose-built agent that exposes exactly two actions — shutdown, restart — behind its own separate secret, distinct from the app's relay token. Nothing resembling general remote code execution exists anywhere in this system |
| Trust model | A closed-source app you install and trust | Fully self-hosted and source-available: the relay and agent are code you can read, run, and audit yourself, on your own NAS and PC — no vendor server ever sees your network |

The short version: the security posture isn't an afterthought bolted onto a wake button — having zero open inbound ports and a narrowly-scoped, separately-authenticated power-control agent were both deliberate design decisions from the start (see [`docs/architecture.md`](docs/architecture.md#security-model) for the full reasoning).

## Getting started

### 1. Confirm Wake-on-LAN works on the target PC

Follow [`docs/windows-wol-setup-checklist.md`](docs/windows-wol-setup-checklist.md) (BIOS settings, Windows power options, NIC driver settings). Confirm it actually works with any existing tool before touching the rest of this project — it's the one genuinely hardware-dependent step, and isolating it first means you're never debugging your own code and a BIOS setting at the same time.

### 2. Deploy the relay

The relay is the always-on bridge between your phone and your PC's LAN. It needs to run on a device that's always on and on the same network as the PC — a NAS is the natural choice if you have one.

1. Copy `my-home-pc-docker-relay/devices.example.yaml` → `my-home-pc-docker-relay/devices.yaml`, and `.env.example` → `.env`. Fill in your PC's MAC/IP and a generated token.
2. Deploy `my-home-pc-docker-relay/` to your Docker host with **host networking** enabled (required for the Wake-on-LAN broadcast to leave the container) — see [`my-home-pc-docker-relay/README.md`](my-home-pc-docker-relay/README.md) for exact steps.

### 3. Set up remote access to the relay

Decide how you'll reach the relay from outside your home network — a VPN mesh (Tailscale/WireGuard) is what this project itself uses and recommends, for the reasons above. Confirm it works (e.g. `curl` the relay's `/health` endpoint from your phone's cellular connection) before moving on.

### 4. Install the Android app

Build/install `my-home-pc-app/` (`cd my-home-pc-app && ./gradlew installDebug` with a device connected, or open the project in Android Studio). On first launch, open Settings and enter: a **Local** host (your relay's LAN IP) and a **Remote** host (whatever you set up in step 3) — same port and token either way. Test Connection will confirm which path it's using.

This alone gives you full remote Wake + status checking. **Stop here if that's all you want** — everything below is only for Restart/Shutdown.

### 5. (Optional) Install the PC Agent, for Restart/Shutdown

This is the one step that's genuinely meant to be installer-simple:

1. On the target PC, get the `Installer` folder (build it yourself from `my-home-pc-agent/source-code/` with `publish.ps1`, or grab it from this repo's Releases page once published).
2. Double-click `Install PcAgent.exe` inside it. Windows will ask for admin permission via UAC — accept it.
3. It asks for a shared secret — press Enter to auto-generate one, or type your own. It prints the secret at the end.
4. Copy that secret into the relay's `.env` as `AGENT_SHARED_SECRET` (must match exactly), then restart the relay.
5. Add `agent_port: 8585` and `"shutdown"`, `"restart"` to that PC's `capabilities` list in `devices.yaml`.

That's the entire install — no PowerShell, no manually edited config files beyond the relay's own `.env`/`devices.yaml`. To remove it later, double-click the `Uninstall PcAgent.exe` that the installer leaves behind inside `C:\Program Files\PcAgent\` — it removes the service, the firewall rule, and the install folder itself, including that same uninstaller.

See [`my-home-pc-agent/README.md`](my-home-pc-agent/README.md) for the full detail on this component specifically.

## Components & architecture (for developers)

See [`docs/architecture.md`](docs/architecture.md) for how the pieces fit together and why, and [`docs/api-contract.md`](docs/api-contract.md) for the full REST API.

| Path | What | Runs on |
|---|---|---|
| [`my-home-pc-docker-relay/`](my-home-pc-docker-relay/) | FastAPI service that sends Wake-on-LAN packets, checks PC status, and forwards Shutdown/Restart to the PC Agent | Any always-on Docker host on the target PC's LAN |
| [`my-home-pc-agent/`](my-home-pc-agent/) | .NET 8 Windows Service (plus a self-contained installer/uninstaller) that performs Shutdown/Restart — optional | The target PC itself |
| [`my-home-pc-app/`](my-home-pc-app/) | Kotlin + Jetpack Compose Android app | Your phone |

The Android app needs **no code changes** to adapt to your own setup — all configuration (relay address, port, token) happens at runtime through its Settings screen. Only the relay needs per-deployment setup, since it's the piece that has to know about your specific PC.

Full phased build/verification notes from this project's own development are in `docs/`.
