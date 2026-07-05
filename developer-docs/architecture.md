# Architecture

## Problem

Turn on a Windows 11 PC from anywhere in the world, even when it's fully shut down (S5), using the MyHomePC app — without being tied to any one specific remote-access technology.

A standard Wake-on-LAN (WoL) magic packet is a LAN-local broadcast — it can't be routed across the internet to a specific powered-off machine. Something that is (a) always on, (b) physically on the same LAN as the PC, and (c) reachable from anywhere is required to bridge the gap.

## Components

```
[MyHomePC App] --HTTPS+token--> [Relay: Docker, host network] --LAN broadcast--> [PC NIC, wakes from S5]
   Kotlin/Compose                  FastAPI (Python)                  |
                                                                      `--LAN HTTP+token--> [PC Agent: Windows Service]
```

- **MyHomePC app** (`my-home-pc-app/`) — Kotlin + Jetpack Compose. The user-facing client. Talks only to the relay, never directly to the PC.
- **Relay** (`my-home-pc-docker-relay/`) — Python/FastAPI, runs in a Docker container on an always-on device on the same LAN as the target PC (a Synology NAS in this project's reference deployment, but any always-on Docker host on that LAN works). The only component capable of sending a WoL broadcast, because it's the only piece that's both always-on and physically on the PC's LAN segment.
- **PC Agent** (`my-home-pc-agent/`) — a small .NET 8 Windows Service that runs on the target PC itself, entirely optional. Only needed for Shutdown/Restart, since powering a PC off or restarting it requires actively running code on that PC — nothing about Wake-on-LAN can do this. LAN-only by design: it never talks to the internet directly, doesn't need its own Tailscale (or other remote-access) presence, and is reached only by the relay over the LAN, gated by its own `AGENT_SHARED_SECRET` (separate from the app-facing `RELAY_API_TOKEN`).

## Reaching the relay from anywhere: Local + Remote, not just one path

The relay is configured with **two possible addresses** — a Local one (typically the relay's LAN IP, reachable only from the same network) and a Remote one (any address reachable from the wider internet — a VPN mesh hostname, a port-forwarded DDNS hostname, whatever you've got). The app tries Local first with a short timeout, and falls back to Remote automatically. This means:

- At home, the app talks to the relay directly over the LAN — fast, and works even if your remote-access method is down or disconnected.
- Away from home, it falls back to whatever Remote address you've configured.
- The app and relay don't care *how* the Remote address is made reachable — that's entirely up to how you've set up your own network. This project's own reference deployment uses Tailscale for that part, but nothing in the app or relay is Tailscale-specific.

See `RelayConnectionResolver` (`my-home-pc-app/app/src/main/java/com/pcwake/app/data/RelayConnectionResolver.kt`) for the fallback logic.

## Data flow per feature

- **Wake**: App → `POST /api/v1/devices/{id}/wake` on the relay → relay builds a 102-byte magic packet and sends it via a `SO_BROADCAST` UDP socket to the LAN broadcast address on ports 9 and 7 → PC NIC, still receiving standby power, wakes the system from S5.
- **Status**: App → `GET /api/v1/devices/{id}/status` → relay pings the PC's LAN IP → reports online/offline.
- **Shutdown / Restart**: App → `POST /api/v1/devices/{id}/shutdown` (or `/restart`) on the relay → relay checks the device's `capabilities` list, then forwards an authenticated request to the PC Agent (`app/agent_client.py`) → the agent runs `shutdown.exe /s /t 5` (or `/r /t 5`) and replies `202` immediately, before the action actually completes. The app shows a confirmation dialog before sending either, and polls `/status` afterward the same way it does for Wake.

Wake-on-LAN is one-directional and unacknowledged (UDP, no response from the PC), so the app polls `/status` after sending a wake to detect when the PC actually comes online. Shutdown/Restart get an HTTP response from the agent, but the app still polls afterward to confirm the PC actually went offline (or came back up, for Restart).

## Why these tech choices

- **Relay: Python + FastAPI.** Glue code (HTTP in, UDP broadcast / ping out), no performance pressure. FastAPI's auto-generated `/docs` (OpenAPI/Swagger UI) doubles as a manual test console during development — gated behind `RELAY_ENABLE_DOCS=true` (off by default) since it advertises the full API surface unauthenticated.
- **Relay networking: Docker host mode.** Sending a raw broadcast from inside a container requires sharing the host's network namespace; bridge/NAT mode blocks or mangles broadcast traffic. Synology DSM (and most Docker hosts) provision a `host` network option for exactly this kind of case.
- **Android: Kotlin + Jetpack Compose.** Google's current recommended approach for new apps.

## Security model

This started as a personal hobby project for one user, not a multi-tenant service — the design favors simplicity over enterprise-grade hardening, but isn't wide open:

- **Transport**: plain HTTP between phone and relay, no extra TLS layer in this project's own code. This is a deliberate simplification — it assumes whatever makes the Remote address reachable from the internet (a VPN mesh, etc.) is already providing real transport encryption between your phone and your home network. If you expose the relay's Remote address via something that *doesn't* already encrypt the connection (e.g. raw port-forwarding with no VPN), add TLS in front of it yourself.
- **What a VPN mesh provider (e.g. Tailscale) can and can't see**: worth being precise here, since "no cloud service" is an overstatement for any VPN-mesh-based setup. A provider like Tailscale operates its own coordination servers to handle device auth and key exchange — that part genuinely is a cloud service you're depending on. The actual data (every relay request/response, every wake/status/shutdown command) travels inside a WireGuard tunnel end-to-end encrypted between your own devices; the provider's servers can't decrypt it. Most of the time that traffic goes peer-to-peer directly between your devices and never touches the provider's infrastructure at all — but if a direct path isn't possible (restrictive NAT, etc.), it can fall back through the provider's relay servers, still fully encrypted. Net effect: the provider can see *that* your devices are online and talking, but not *what* they're saying — and this project's own code never depends on any specific provider either way.
- **App layer**: every relay endpoint except `/health` requires `Authorization: Bearer <RELAY_API_TOKEN>`, compared with a constant-time check (`hmac.compare_digest`) to avoid timing side-channels.
- **Token storage**: the app encrypts the token with a key held in the Android Keystore (AES/GCM, never leaves the device's secure hardware), entered once via the Settings screen — never hardcoded in app source.
- **PC Agent layer**: the agent's `/shutdown` and `/restart` (but not `/health`) require their own `Authorization: Bearer <AGENT_SHARED_SECRET>`, deliberately a *different* secret from `RELAY_API_TOKEN` — a compromised app token alone can't reach the agent, only a compromised relay can. The agent binds all of the PC's network interfaces (`0.0.0.0:<AGENT_PORT>`) — deliberately, so it stays reachable however the relay can route to it — which means it is *not* limited to the LAN interface: if the PC runs a VPN mesh client (e.g. Tailscale), the agent's port is reachable over that interface too. The boundaries that actually protect it are the Windows firewall, the shared secret on the destructive endpoints, and the fact that the PC has no ports forwarded from the internet. Shutdown/Restart were originally deferred over exactly this kind of remote-power-control risk; revisiting that decision didn't relax these defaults (confirmation dialogs in the app, separate secret) — it just decided the tradeoff was acceptable.
- **Relay → agent hop is cleartext HTTP**: the relay forwards shutdown/restart to the agent as plain HTTP on the LAN, with `AGENT_SHARED_SECRET` as a bearer header (`app/agent_client.py`). This is an accepted risk: on a switched home LAN, intercepting that unicast traffic requires an attacker who has already compromised a device on the network (e.g. ARP spoofing) — and the worst that secret grants is shutting down or restarting the PC, not data access. If your LAN's trust level is lower than that (shared building network, untrusted IoT segment without isolation), put the agent behind TLS yourself or firewall its port to only accept the relay host's IP.

## Multi-device extensibility

The relay's device registry (`my-home-pc-docker-relay/docker/devices.yaml`, gitignored — see `my-home-pc-docker-relay/source-code/devices.example.yaml` for the template) is a list from day one, and the REST API is scoped under `/devices/{id}/...`. Adding a second PC is a config-file edit and a container restart, not a schema or app code change — the MyHomePC app's device-list screen already renders a list. A device's `capabilities` list controls which buttons the app shows for it — `shutdown`/`restart` should only be added once `my-home-pc-agent` is actually installed and verified working on that PC, not before.
