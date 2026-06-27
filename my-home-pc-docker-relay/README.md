# MyHomePC Docker Relay

FastAPI service that sends Wake-on-LAN packets, checks PC status, and forwards Shutdown/Restart to [`my-home-pc-agent`](../my-home-pc-agent/) when a device's `capabilities` list permits it. This is the Docker-deployed package — it needs to run as a container on an always-on Docker host on the **same LAN** as the target PC. See [`../developer-docs/architecture.md`](../developer-docs/architecture.md) for why.

This folder has two parts:
- **`docker/`** (gitignored, built by `publish.ps1`) — the only thing that needs to exist on your NAS: the Dockerfile, compose file, requirements, and app code, plus your own `devices.yaml`/`.env`. Nothing else.
- **`source-code/`** — the actual source, tests, and dev tooling. Only needed on your dev machine.

## First-time setup

1. Build the deployment folder: `cd source-code && powershell -ExecutionPolicy Bypass -File .\publish.ps1`. This produces `../docker/`, including the `devices.example.yaml`/`.env.example` templates sitting right there for convenience — it never touches `docker/devices.yaml` or `docker/.env` if they already exist.
2. In `docker/`, rename/copy the templates already there and fill them in:
   ```
   copy devices.example.yaml devices.yaml   # then fill in YOUR PC's MAC/IP/broadcast
   copy .env.example .env                   # then set a real RELAY_API_TOKEN (and AGENT_SHARED_SECRET if using Shutdown/Restart)
   ```

`devices.yaml` and `.env` are gitignored (the whole `docker/` folder is) — they hold your own machine's details and secret tokens, not meant to be committed.

Re-run `publish.ps1` after any code change in `source-code/app/` — it always refreshes the Dockerfile/compose/requirements/app code in `docker/`, but leaves your `devices.yaml`/`.env` alone.

## Local dev (no Docker)

```
cd source-code
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements-dev.txt
python -m uvicorn app.main:app --reload --port 8675
```

Browse to `http://localhost:8675/docs` for the interactive Swagger UI — useful for manually testing `/wake` and `/status` before the MyHomePC app exists.

## Tests

```
cd source-code
python -m pytest
```

## Deploy (Docker, host networking required)

1. Upload the contents of `docker/` (with your filled-in `devices.yaml` and `.env`) to wherever it'll run — that's the entire upload, nothing from `source-code/` is needed there.
2. Build and run with **host networking** — e.g. via `docker-compose.yml`'s `network_mode: host`, or your platform's equivalent (on Synology, Container Manager → Project, or "Use the same network as Docker Host" when creating a container manually).
3. Confirm the container's network mode actually is host: `docker inspect <container> --format '{{.HostConfig.NetworkMode}}'` should print `host`.
4. If there's a firewall on the Docker host itself (e.g. DSM's Control Panel → Security → Firewall), add an explicit allow rule for the relay's port — host networking bypasses Docker's own NAT, but not the host's own firewall.
5. **After any code or `devices.yaml`/`.env` change, verify with a direct `curl` to the deployed endpoint** (see below) rather than trusting that a "rebuild"/restart in your container platform's UI actually picked it up — on Synology Container Manager specifically, a rebuild can silently keep serving the old image if you only edited one of several changed files, or the running process can hold a stale config value in memory if it isn't restarted after a `.env`/`devices.yaml` edit.

## Testing it works

- From a device on the same LAN: `curl http://<relay-lan-ip>:8675/api/v1/health`
- From a device away from home, over whatever remote-access method you've set up: `curl http://<relay-remote-address>:8675/api/v1/health`
- Wake: `curl -X POST http://<relay-address>:8675/api/v1/devices/pc-main/wake -H "Authorization: Bearer <token>"`
- Shutdown/Restart (only if the device's `capabilities` includes them and `my-home-pc-agent` is running): `curl -X POST http://<relay-address>:8675/api/v1/devices/pc-main/restart -H "Authorization: Bearer <token>"`
