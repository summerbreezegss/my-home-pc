# Relay

FastAPI service that sends Wake-on-LAN packets and checks PC status. Needs to run on an always-on Docker host on the **same LAN** as the target PC — see [`../docs/architecture.md`](../docs/architecture.md) for why.

## First-time setup

```
copy devices.example.yaml devices.yaml   # then fill in YOUR PC's MAC/IP/broadcast
copy .env.example .env                   # then set a real RELAY_API_TOKEN
```

`devices.yaml` and `.env` are gitignored — they hold your own machine's details and a secret token, not meant to be committed.

## Local dev (no Docker)

```
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements-dev.txt
python -m uvicorn app.main:app --reload --port 8080
```

Browse to `http://localhost:8080/docs` for the interactive Swagger UI — useful for manually testing `/wake` and `/status` before the Android app exists.

## Tests

```
python -m pytest
```

## Deploy (Docker, host networking required)

1. Copy the `relay/` folder (with your filled-in `devices.yaml` and `.env`) to wherever it'll run.
2. Build and run with **host networking** — e.g. via `docker-compose.yml`'s `network_mode: host`, or your platform's equivalent (on Synology, Container Manager → Project, or "Use the same network as Docker Host" when creating a container manually).
3. Confirm the container's network mode actually is host: `docker inspect <container> --format '{{.HostConfig.NetworkMode}}'` should print `host`.
4. If there's a firewall on the Docker host itself (e.g. DSM's Control Panel → Security → Firewall), add an explicit allow rule for the relay's port — host networking bypasses Docker's own NAT, but not the host's own firewall.

## Testing it works

- From a device on the same LAN: `curl http://<relay-lan-ip>:8080/api/v1/health`
- From a device away from home, over whatever remote-access method you've set up: `curl http://<relay-remote-address>:8080/api/v1/health`
- Wake: `curl -X POST http://<relay-address>:8080/api/v1/devices/pc-main/wake -H "Authorization: Bearer <token>"`
