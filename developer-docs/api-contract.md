# Relay API Contract

Base URL: `http://<relay-host>:<port>/api/v1` — `<relay-host>` is whichever of your configured Local/Remote addresses the app resolved to (see `developer-docs/architecture.md`).

Auth: every endpoint except `/health` requires `Authorization: Bearer <RELAY_API_TOKEN>`. Missing/invalid token → `401`.

Throttling: after 5 consecutive auth failures from one source IP (tunable via `RELAY_AUTH_MAX_FAILURES` / `RELAY_AUTH_LOCKOUT_SECONDS`), that IP gets `429` with a `Retry-After` header until the lockout window (default 5 minutes) passes — even if it then presents a correct token. Every rejection is logged with timestamp, source IP, and path as the audit trail.

## `GET /health`

No auth. Liveness check — deliberately reports nothing but liveness (no version or other
details), since anything here is readable by whoever can reach the port.

```
200 OK
{ "status": "ok" }
```

FastAPI's interactive docs (`/docs`, `/redoc`, `/openapi.json`) are disabled unless the relay
is started with `RELAY_ENABLE_DOCS=true` — they expose the full API surface unauthenticated.

## `GET /devices`

List registered devices (from `my-home-pc-docker-relay/docker/devices.yaml`).

```
200 OK
{
  "devices": [
    {
      "id": "pc-main",
      "name": "My Desktop",
      "capabilities": ["wake", "status", "shutdown", "restart"]
    }
  ]
}
```

Deliberately app-facing fields only — MAC, LAN IP, broadcast, and agent port are internal network details that stay inside the relay (`devices.yaml`).

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
- `last_seen`: last time the device answered a ping, ISO-8601 UTC; persisted on the relay so it survives container restarts. `null` only if the device has never been seen.
- `agent_reachable`: `null` when the device has no `agent_port` configured; otherwise the result of probing the agent's `/health`. A device that fails ping reports `false` without a probe.

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

Auth: `/shutdown` and `/restart` require `Authorization: Bearer <AGENT_SHARED_SECRET>` (a secret separate from `RELAY_API_TOKEN`). `/health` has no auth. The agent refuses to start (and refuses to authorize) while `AGENT_SHARED_SECRET` is unset or still the shipped placeholder value.

## `GET /health`

No hostname or other machine details — this endpoint is unauthenticated.

```
200 OK
{ "status": "ok" }
```

Throttling: same scheme as the relay — 5 consecutive bad-secret attempts from one source IP lock that IP out with `429` for 5 minutes, correct secret or not, and every rejection is logged (timestamp + source IP + path) to the Windows Application event log.

## `POST /shutdown`

```
202 Accepted   { "result": "shutdown_scheduled", "delay_seconds": 5 }
401 Unauthorized
429 Too Many Requests
```

Schedules `shutdown.exe /s /t 5` and returns immediately — the 5-second delay gives the HTTP response time to actually get sent before the OS starts tearing things down.

## `POST /restart`

```
202 Accepted   { "result": "restart_scheduled", "delay_seconds": 5 }
401 Unauthorized
429 Too Many Requests
```

Schedules `shutdown.exe /r /t 5`, same reasoning as `/shutdown`.
