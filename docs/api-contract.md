# Relay API Contract

Base URL: `http://<relay-host>:<port>/api/v1` — `<relay-host>` is whichever of your configured Local/Remote addresses the app resolved to (see `docs/architecture.md`).

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
      "capabilities": ["wake", "status"]
    }
  ]
}
```

`capabilities` is forward-looking — it's designed to gate future per-device features (e.g. `"sleep"`/`"shutdown"`) in the Android UI without an app update, but only `"wake"` and `"status"` are actually implemented today.

## `POST /devices/{id}/wake`

Fire-and-forget — WoL is UDP with no acknowledgement, so success means "packet sent," not "PC is now on." Clients should poll `/status` afterward.

```
Request body: {}

200 OK   { "id": "pc-main", "action": "wake", "result": "packet_sent" }
404      { "error": "device_not_found" }
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
- `agent_reachable`: reserved for a possible future per-device agent (see "What's deliberately not built" in `docs/architecture.md`) — always `null` today.
