# MyHomePC

Wake, restart, or shut down your home Windows PC from anywhere in the world, straight from your phone — designed to be simple enough for non-technical users, while keeping every command self-hosted and end-to-end encrypted.

## What this is

This started from a simple problem. I travel a lot, and there are plenty of times I want my home PC available while I'm away. Streaming games to my mobile devices or handhelds instead of lugging a gaming rig with me, doing some quick dev work, grabbing a file I only ever saved locally and forgot to sync, or kicking off something hardware-intensive (a render, a build, a big download) on a machine that's actually fast enough for it.

Quick answer is to remote access my PC. Easy if it's local just install Moonlight/Artemis coupled with Sunshine or Apollo and we're good to go. But Remote is tricky, probably a VPN Mesh App (e.g. Tailscale, NordVPN Meshnet, etc.) would help then remote access my PC. But what if the PC is turned off? Wake-on-LAN magic packets only work on local network. A VPN Mesh can only reach a PC that's already running, so a fully shut-down PC wont show it as part of the network at all. I could just leave my PC on 24/7, but that means burning power around the clock for a machine that's actually doing something useful only a few hours a week.

This is exactly the gap this project intends to bridge. Let the PC actually shut down when nobody's using it, and wake it back up the moment it's needed.

There are many apps already that does this features but I intend to make this more secure and WAY SIMPLER to configure.

The day-to-day experience is meant to be effortless, even though making that possible takes a bit more effort up front (sorry :p):

- **MyHomePC app** is a normal app: open it, tap Wake/Restart/Shutdown, done — it detects whether you're on Local or Remote automatically, so you never have to think about which one you're on. Pick a default PC once, and you get a home-screen widget and a Quick Settings tile for one-tap Wake without even opening the app, plus a notification when it comes online.
- **PC Agent** (only needed for Restart/Shutdown) installs with a single double-click — no command line, no coding. See [Getting started](#getting-started) below.
- **Docker Relay** is the one genuinely technical piece — a one-time setup, editing a couple of config files and deploying it on a Docker host (see [Getting started](#getting-started) below). It's also where the real security benefit lives: no port-forwarding, no open inbound ports, token-gated access whether you're on Wi-Fi or across the world. Once it's running, you never touch it again, and adding a second PC later is a one-line config change, not a rewrite.

## Why this instead of a typical Wake-on-LAN app

Most Wake-on-LAN apps are happy to stop at "turn it on." Look past that, and remote access or anything beyond a basic wake, like Restart/Shutdown — often turns into its own technical project: router port-forwarding rules, dynamic DNS, or writing SSH commands by hand to unlock a "Pro" feature. MyHomePC is built the other way around — the parts a normal person actually touches are designed to need zero technical background:

| | Typical Wake-on-LAN apps | MyHomePC |
|---|---|---|
| Setting up remote access | Often means configuring port-forwarding on your router yourself — router-specific menus, port numbers, sometimes dynamic DNS too, and an open inbound port on your home network either way | A VPN mesh app you already trust (Tailscale, NordVPN's Meshnet, ZeroTier, whatever you've got) — no router configuration, no open inbound ports, ever |
| Restart / Shutdown | Rarely offered at all; where it exists, it's often an advanced/"Pro" feature that expects you to write your own SSH commands | Built in from the start. The one-time PC-side setup is a single double-click — no command line, no config files to hand-edit |
| Who can read your commands | Depends on the app and its vendor | End-to-end encrypted between your own devices; the relay and agent that actually carry out commands are code you run yourself, not a vendor's server |
| Getting it right the first time | Usually trial-and-error with router settings | A short guided checklist gets the one genuinely technical step (BIOS/Windows Wake-on-LAN settings) right once, and you're done |

The short version: this isn't a power-user tool with a friendly coat of paint over it — the day-to-day experience was designed from the start to need no technical knowledge at all (see [Getting started](#getting-started) below), while still being honest about what's actually happening underneath (see [`developer-docs/architecture.md`](developer-docs/architecture.md#security-model) for the full technical reasoning, including exactly what your chosen VPN provider can and can't see).

## Getting started

### 1. Confirm Wake-on-LAN works on the target PC

Follow [`developer-docs/windows-wol-setup-checklist.md`](developer-docs/windows-wol-setup-checklist.md) (BIOS settings, Windows power options, NIC driver settings). Confirm it actually works with any existing tool before touching the rest of this project — it's the one genuinely hardware-dependent step, and isolating it first means you're never debugging your own code and a BIOS setting at the same time.

### 2. Deploy the relay

The relay is the always-on bridge between your phone and your PC's LAN. It needs to run on a device that's always on and on the same network as the PC — a NAS is the natural choice if you have one.

1. Grab `my-home-pc-docker-relay/docker/` — it's committed to the repo as-is, containing just the Dockerfile, compose file, requirements, app code, and the `devices.example.yaml`/`.env.example` templates. No build step — just copy that folder to wherever it'll run.
2. In `my-home-pc-docker-relay/docker/`, rename/copy `devices.example.yaml` → `devices.yaml` and `.env.example` → `.env` (both already sitting right there). Fill in your PC's MAC/IP and a generated token.
3. Deploy `my-home-pc-docker-relay/docker/` to your Docker host with **host networking** enabled (required for the Wake-on-LAN broadcast to leave the container) — see [`my-home-pc-docker-relay/README.md`](my-home-pc-docker-relay/README.md) for exact steps.

### 3. Set up remote access to the relay

Decide how you'll reach the relay from outside your home network — any VPN mesh app works (Tailscale, NordVPN's Meshnet, ZeroTier, etc.); this project's own setup uses Tailscale, for the reasons above. Confirm it works (e.g. `curl` the relay's `/health` endpoint from your phone's cellular connection) before moving on.

### 4. Install the MyHomePC app

Build/install `my-home-pc-app/` (`cd my-home-pc-app && ./gradlew installDebug` with a device connected, or open the project in Android Studio). On first launch, open Settings and enter: a **Local** host (your relay's LAN IP) and a **Remote** host (whatever you set up in step 3) — same port and token either way. Test Connection will confirm which path it's using.

If you set a Local host, the app will ask two one-time questions when you're on your home Wi-Fi: permission to check which Wi-Fi network you're on (Android reports this through the location permission — the app never reads your actual location), and whether to trust that network. The Local shortcut only runs on networks you've trusted; everywhere else the app quietly uses Remote. You can review trusted networks any time in Settings.

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

See [`developer-docs/architecture.md`](developer-docs/architecture.md) for how the pieces fit together and why, and [`developer-docs/api-contract.md`](developer-docs/api-contract.md) for the full REST API.

| Path | What | Runs on |
|---|---|---|
| [`my-home-pc-docker-relay/`](my-home-pc-docker-relay/) | FastAPI service that sends Wake-on-LAN packets, checks PC status, and forwards Shutdown/Restart to the PC Agent | Any always-on Docker host on the target PC's LAN |
| [`my-home-pc-agent/`](my-home-pc-agent/) | .NET 8 Windows Service (plus a self-contained installer/uninstaller) that performs Shutdown/Restart — optional | The target PC itself |
| [`my-home-pc-app/`](my-home-pc-app/) | The MyHomePC app — Kotlin + Jetpack Compose | Your phone |

The MyHomePC app needs **no code changes** to adapt to your own setup — all configuration (relay address, port, token) happens at runtime through its Settings screen. Only the relay needs per-deployment setup, since it's the piece that has to know about your specific PC.

Full phased build/verification notes from this project's own development are in `developer-docs/`.
