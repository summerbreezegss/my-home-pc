# Relay API Contract

Base URL: `http://<relay-host>:<port>/api/v1` — `<relay-host>` is whichever of your configured Local/Remote addresses the app resolved to (see `developer-docs/architecture.md`).

Auth: every endpoint except `/health` requires `Authorization: Bearer <RELAY_API_TOKEN>`. Missing/invalid token → `401`.

## `GET /health`

No auth. Liveness check.

```
200 OK
{ "status": "ok", "version": "0.1.0" }
```

## `GET /devices`

List registered devices (from `my-home-pc-docker-relay/devices.yaml`).

```
200 OK
{
  "devices": [
    {
      "id": "pc-main",
      "name": "My Desktop",
      "mac": "AA:BB:CC:DD:EE:FF",
      "lan_ip": "192.168.1.50",
      "broadcast": "192.168.1.255",
      "agent_port": 8585,
      "capabilities": ["wake", "status", "shutdown", "restart"]
    }
  ]
}
```

`capabilities` gates which actions the Android UI shows for a device. `"shutdown"`/`"restart"` should only be listed once `my-home-pc-agent` is installed and verified on that PC — see its README. `"sleep"` is a recognized value in the schema but has no agent route built yet.

## `POST /devices/{id}/wake`

Fire-and-forget — WoL is UDP with no acknowledgement, so success means "packet sent," not "PC is now on." Clients should poll `/status` afterward.

```
Request body: {}

200 OK   { "id": "pc-main", "action": "wake", "result": "packet_sent" }
404      { "detail": "device_not_found" }
```

## `GET /devices/{id}/status`

```
200 OK
{
  "id": "pc-main",
  "online": true,
  "method": "ping",
  "last_seen": "2026-06-24T08:31:02Z",
  "agent_reachable": null
}
```

- `method`: currently always `"ping"`.
- `agent_reachable`: reserved for a future health-check pass-through — always `null` today.

## `POST /devices/{id}/shutdown` / `POST /devices/{id}/restart`

Forwards an authenticated request to the device's PC Agent (see "PC Agent API Contract" below). Requires the device to list `"shutdown"`/`"restart"` in its `capabilities`.

```
Request body: {}

200 OK   { "id": "pc-main", "action": "shutdown", "result": "forwarded", "agent_response": "shutdown_scheduled" }
400      { "detail": "capability_not_supported" }
404      { "detail": "device_not_found" }
502      { "detail": "agent_unreachable" }
```

`502 agent_unreachable` covers both a genuinely unreachable agent (network/firewall/service down) and the agent rejecting the relay's `AGENT_SHARED_SECRET` — the relay logs the underlying `httpx` exception (see `app/main.py`) to tell the two apart when debugging, but the response shape doesn't distinguish them on purpose, to avoid leaking auth details to the app layer.

# PC Agent API Contract

Base URL: `http://<pc-lan-ip>:<agent_port>` — reached only by the relay over the LAN, never directly by the app.

Auth: `/shutdown` and `/restart` require `Authorization: Bearer <AGENT_SHARED_SECRET>` (a secret separate from `RELAY_API_TOKEN`). `/health` has no auth.

## `GET /health`

```
200 OK
{ "status": "ok", "hostname": "DESKTOP-ARJAY" }
```

## `POST /shutdown`

```
202 Accepted   { "result": "shutdown_scheduled", "delay_seconds": 5 }
401 Unauthorized
```

Schedules `shutdown.exe /s /t 5` and returns immediately — the 5-second delay gives the HTTP response time to actually get sent before the OS starts tearing things down.

## `POST /restart`

```
202 Accepted   { "result": "restart_scheduled", "delay_seconds": 5 }
401 Unauthorized
```

Schedules `shutdown.exe /r /t 5`, same reasoning as `/shutdown`.
