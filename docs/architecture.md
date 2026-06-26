# Architecture

## Problem

Turn on a Windows 11 PC from anywhere in the world, even when it's fully shut down (S5), using an Android app — without being tied to any one specific remote-access technology.

A standard Wake-on-LAN (WoL) magic packet is a LAN-local broadcast — it can't be routed across the internet to a specific powered-off machine. Something that is (a) always on, (b) physically on the same LAN as the PC, and (c) reachable from anywhere is required to bridge the gap.

## Components

```
[Android App] --HTTPS+token--> [Relay: Docker, host network] --LAN broadcast--> [PC NIC, wakes from S5]
   Kotlin/Compose                  FastAPI (Python)
```

- **Android app** (`my-home-pc-app/`) — Kotlin + Jetpack Compose. The user-facing client. Talks only to the relay, never directly to the PC.
- **Relay** (`my-home-pc-docker-relay/`) — Python/FastAPI, runs in a Docker container on an always-on device on the same LAN as the target PC (a Synology NAS in this project's reference deployment, but any always-on Docker host on that LAN works). The only component capable of sending a WoL broadcast, because it's the only piece that's both always-on and physically on the PC's LAN segment.

## Reaching the relay from anywhere: Local + Remote, not just one path

The relay is configured with **two possible addresses** — a Local one (typically the relay's LAN IP, reachable only from the same network) and a Remote one (any address reachable from the wider internet — a VPN mesh hostname, a port-forwarded DDNS hostname, whatever you've got). The app tries Local first with a short timeout, and falls back to Remote automatically. This means:

- At home, the app talks to the relay directly over the LAN — fast, and works even if your remote-access method is down or disconnected.
- Away from home, it falls back to whatever Remote address you've configured.
- The app and relay don't care *how* the Remote address is made reachable — that's entirely up to how you've set up your own network. This project's own reference deployment uses Tailscale for that part, but nothing in the app or relay is Tailscale-specific.

See `RelayConnectionResolver` (`my-home-pc-app/app/src/main/java/com/pcwake/app/data/RelayConnectionResolver.kt`) for the fallback logic.

## Data flow per feature

- **Wake**: App → `POST /api/v1/devices/{id}/wake` on the relay → relay builds a 102-byte magic packet and sends it via a `SO_BROADCAST` UDP socket to the LAN broadcast address on ports 9 and 7 → PC NIC, still receiving standby power, wakes the system from S5.
- **Status**: App → `GET /api/v1/devices/{id}/status` → relay pings the PC's LAN IP → reports online/offline.

Wake-on-LAN is one-directional and unacknowledged (UDP, no response from the PC), so the app polls `/status` after sending a wake to detect when the PC actually comes online.

## Why these tech choices

- **Relay: Python + FastAPI.** Glue code (HTTP in, UDP broadcast / ping out), no performance pressure. FastAPI's auto-generated `/docs` (OpenAPI/Swagger UI) doubles as a manual test console during development.
- **Relay networking: Docker host mode.** Sending a raw broadcast from inside a container requires sharing the host's network namespace; bridge/NAT mode blocks or mangles broadcast traffic. Synology DSM (and most Docker hosts) provision a `host` network option for exactly this kind of case.
- **Android: Kotlin + Jetpack Compose.** Google's current recommended approach for new apps.

## Security model

This started as a personal hobby project for one user, not a multi-tenant service — the design favors simplicity over enterprise-grade hardening, but isn't wide open:

- **Transport**: plain HTTP between phone and relay, no extra TLS layer in this project's own code. This is a deliberate simplification — it assumes whatever makes the Remote address reachable from the internet (a VPN mesh, etc.) is already providing real transport encryption between your phone and your home network. If you expose the relay's Remote address via something that *doesn't* already encrypt the connection (e.g. raw port-forwarding with no VPN), add TLS in front of it yourself.
- **App layer**: every relay endpoint except `/health` requires `Authorization: Bearer <RELAY_API_TOKEN>`, compared with a constant-time check (`hmac.compare_digest`) to avoid timing side-channels.
- **Token storage**: the app stores the token in `EncryptedSharedPreferences` (Android Keystore-backed), entered once via the Settings screen — never hardcoded in app source.

## Multi-device extensibility

The relay's device registry (`my-home-pc-docker-relay/devices.yaml`, gitignored — see `my-home-pc-docker-relay/devices.example.yaml` for the template) is a list from day one, and the REST API is scoped under `/devices/{id}/...`. Adding a second PC is a config-file edit and a container restart, not a schema or app code change — the Android app's device-list screen already renders a list.

## What's deliberately not built

Remote sleep/shutdown was considered and explicitly **not built** — it would require a second always-running agent on the target PC itself, and the project owner decided the added remote-shutdown attack surface wasn't worth it for a feature that's already covered by existing remote-desktop tools. If you want this, the natural extension point is a small authenticated HTTP listener on the PC that the relay forwards `sleep`/`shutdown` requests to, gated by a `capabilities` list per device (the `Device` model already has a `capabilities` field for exactly this kind of per-device feature flag).
