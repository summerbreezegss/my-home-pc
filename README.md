# MyHomePC

Wake up, restart, or shut down my home Windows PC from anywhere in the world — right from my phone. That was the whole goal, and once it's set up, that's really all it is: open the app, tap a button, done.

I started this because I travel and kept wanting to turn my PC on remotely without buying extra hardware for it. I looked at smart plugs and ESP32-based solutions first and ruled them out — they're physical things I'd have to buy, wire up, and maintain, for a problem that's really just software. I also didn't want to hand a vendor's cloud app direct access to my home network just to press a power button. So I built this around two things I cared about more than convenience features: **simplicity** and **privacy**. The PC-side install for Restart/Shutdown is a single double-click, not a command line — and the relay and PC agent that actually carry out every command are code I run myself, on hardware I already own, so no company's server ever needs to be trusted with my home network.

The one piece I bring from outside is a secure connection method of my own choosing. I already had Tailscale (a VPN mesh) running on my NAS, PC, and phone, so that's what I used — but I deliberately didn't bake Tailscale into the app or relay's code. Everywhere the app talks about reaching your PC remotely, it just says "Local" or "Remote," never "Tailscale." I wanted the option to switch VPN providers later, or for someone else using this to bring their own, without that being a rewrite.

## Why this instead of a typical Wake-on-LAN app

Most Wake-on-LAN apps are happy to stop at "turn it on." I wanted that too, but I also wanted Restart and Shutdown, and looking at how other apps handle that, it's almost always treated as a power-user feature — router port-forwarding rules, dynamic DNS, or writing your own SSH commands to unlock a "Pro" tier. I went the other way: the parts a normal person actually touches are designed to need zero technical background.

| | Typical Wake-on-LAN apps | MyHomePC |
|---|---|---|
| Setting up remote access | Often means configuring port-forwarding on your router yourself — router-specific menus, port numbers, sometimes dynamic DNS too, and an open inbound port on your home network either way | A VPN mesh app you already trust (I use Tailscale) — no router configuration, no open inbound ports, ever |
| Restart / Shutdown | Rarely offered at all; where it exists, it's often an advanced/"Pro" feature that expects you to write your own SSH commands | Built in from the start. The one-time PC-side setup is a single double-click — no command line, no config files to hand-edit |
| Who can read your commands | Depends on the app and its vendor | End-to-end encrypted between your own devices; the relay and agent that actually carry out commands are code you run yourself, not a vendor's server |
| Getting it right the first time | Usually trial-and-error with router settings | A short guided checklist gets the one genuinely technical step (BIOS/Windows Wake-on-LAN settings) right once, and you're done |

A decision I went back and forth on: I originally left Restart/Shutdown out entirely, on purpose — "I don't want a way for anyone to turn my machine off remotely" was the reasoning, since I already had Moonlight for that when I actually needed it. I came back to it later because I wanted the feature set to be complete, not because I'd changed my mind about the risk. So I kept every safety decision from the original plan rather than loosening anything: a confirmation dialog before either action fires, a separate secret for the PC agent that's distinct from the app's own token (so a leaked app token alone still can't reach it), and the agent itself never listens on anything but the LAN — it has no presence on the internet or on Tailscale at all, full stop.

I also thought about just adding Shutdown/Restart endpoints straight onto the relay, since it already runs on my NAS. That doesn't work, though — the relay lives on the NAS, not the PC, and shutting a machine down requires code actually running on that machine. That's the entire reason the PC Agent exists as its own small Windows Service: it's the one piece of this project that has to live where the PC itself lives.

The short version: this isn't a power-user tool with a friendly coat of paint over it — the day-to-day experience was designed from the start to need no technical knowledge at all (see [Getting started](#getting-started) below), while still being honest about what's actually happening underneath (see [`developer-docs/architecture.md`](developer-docs/architecture.md#security-model) for the full technical reasoning, including exactly what your chosen VPN provider can and can't see).

## Getting started

### 1. Confirm Wake-on-LAN works on the target PC

Follow [`developer-docs/windows-wol-setup-checklist.md`](developer-docs/windows-wol-setup-checklist.md) (BIOS settings, Windows power options, NIC driver settings). Confirm it actually works with any existing tool before touching the rest of this project — it's the one genuinely hardware-dependent step, and isolating it first means you're never debugging your own code and a BIOS setting at the same time.

### 2. Deploy the relay

The relay is the always-on bridge between your phone and your PC's LAN. It needs to run on a device that's always on and on the same network as the PC — a NAS is the natural choice if you have one (it's what I use).

1. Copy `my-home-pc-docker-relay/devices.example.yaml` → `my-home-pc-docker-relay/devices.yaml`, and `.env.example` → `.env`. Fill in your PC's MAC/IP and a generated token.
2. Deploy `my-home-pc-docker-relay/` to your Docker host with **host networking** enabled (required for the Wake-on-LAN broadcast to leave the container) — see [`my-home-pc-docker-relay/README.md`](my-home-pc-docker-relay/README.md) for exact steps.

### 3. Set up remote access to the relay

Decide how you'll reach the relay from outside your home network — a VPN mesh (Tailscale/WireGuard) is what I use and recommend, for the reasons above. Confirm it works (e.g. `curl` the relay's `/health` endpoint from your phone's cellular connection) before moving on.

### 4. Install the MyHomePC app

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

See [`developer-docs/architecture.md`](developer-docs/architecture.md) for how the pieces fit together and why, and [`developer-docs/api-contract.md`](developer-docs/api-contract.md) for the full REST API.

| Path | What | Runs on |
|---|---|---|
| [`my-home-pc-docker-relay/`](my-home-pc-docker-relay/) | FastAPI service that sends Wake-on-LAN packets, checks PC status, and forwards Shutdown/Restart to the PC Agent | Any always-on Docker host on the target PC's LAN |
| [`my-home-pc-agent/`](my-home-pc-agent/) | .NET 8 Windows Service (plus a self-contained installer/uninstaller) that performs Shutdown/Restart — optional | The target PC itself |
| [`my-home-pc-app/`](my-home-pc-app/) | The MyHomePC app — Kotlin + Jetpack Compose | Your phone |

The MyHomePC app needs **no code changes** to adapt to your own setup — all configuration (relay address, port, token) happens at runtime through its Settings screen. Only the relay needs per-deployment setup, since it's the piece that has to know about your specific PC.

Full phased build/verification notes from this project's own development are in `developer-docs/`.
