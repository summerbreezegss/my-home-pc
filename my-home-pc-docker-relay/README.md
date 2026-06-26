# MyHomePC Docker Relay

FastAPI service that sends Wake-on-LAN packets, checks PC status, and forwards Shutdown/Restart to [`my-home-pc-agent`](../my-home-pc-agent/) when a device's `capabilities` list permits it. This is the Docker-deployed package — it needs to run as a container on an always-on Docker host on the **same LAN** as the target PC. See [`../docs/architecture.md`](../docs/architecture.md) for why.

## First-time setup

```
copy devices.example.yaml devices.yaml   # then fill in YOUR PC's MAC/IP/broadcast
copy .env.example .env                   # then set a real RELAY_API_TOKEN (and AGENT_SHARED_SECRET if using Shutdown/Restart)
```

`devices.yaml` and `.env` are gitignored — they hold your own machine's details and secret tokens, not meant to be committed.

## Local dev (no Docker)

```
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements-dev.txt
python -m uvicorn app.main:app --reload --port 8675
```

Browse to `http://localhost:8675/docs` for the interactive Swagger UI — useful for manually testing `/wake` and `/status` before the Android app exists.

## Tests

```
python -m pytest
```

## Deploy (Docker, host networking required)

1. Copy the `my-home-pc-docker-relay/` folder (with your filled-in `devices.yaml` and `.env`) to wherever it'll run.
2. Build and run with **host networking** — e.g. via `docker-compose.yml`'s `network_mode: host`, or your platform's equivalent (on Synology, Container Manager → Project, or "Use the same network as Docker Host" when creating a container manually).
3. Confirm the container's network mode actually is host: `docker inspect <container> --format '{{.HostConfig.NetworkMode}}'` should print `host`.
4. If there's a firewall on the Docker host itself (e.g. DSM's Control Panel → Security → Firewall), add an explicit allow rule for the relay's port — host networking bypasses Docker's own NAT, but not the host's own firewall.
5. **After any code or `devices.yaml`/`.env` change, verify with a direct `curl` to the deployed endpoint** (see below) rather than trusting that a "rebuild"/restart in your container platform's UI actually picked it up — on Synology Container Manager specifically, a rebuild can silently keep serving the old image if you only edited one of several changed files, or the running process can hold a stale config value in memory if it isn't restarted after a `.env`/`devices.yaml` edit.

## Testing it works

- From a device on the same LAN: `curl http://<relay-lan-ip>:8675/api/v1/health`
- From a device away from home, over whatever remote-access method you've set up: `curl http://<relay-remote-address>:8675/api/v1/health`
- Wake: `curl -X POST http://<relay-address>:8675/api/v1/devices/pc-main/wake -H "Authorization: Bearer <token>"`
- Shutdown/Restart (only if the device's `capabilities` includes them and `my-home-pc-agent` is running): `curl -X POST http://<relay-address>:8675/api/v1/devices/pc-main/restart -H "Authorization: Bearer <token>"`
