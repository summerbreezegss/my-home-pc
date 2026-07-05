# MyHomePC Docker Relay

FastAPI service that sends Wake-on-LAN packets, checks PC status, and forwards Shutdown/Restart to [`my-home-pc-agent`](../my-home-pc-agent/) when a device's `capabilities` list permits it. This is the Docker-deployed package — it needs to run as a container on an always-on Docker host on the **same LAN** as the target PC. See [`../developer-docs/architecture.md`](../developer-docs/architecture.md) for why.

This folder has two parts:
- **`docker/`** — committed to the repo, and the only thing you need to grab to deploy: the Dockerfile, compose file, requirements, app code, and `devices.example.yaml`/`.env.example` templates. Clone or download this folder straight from GitHub and copy it to your NAS — no build step required.
- **`source-code/`** — the same app code plus tests and dev tooling, for actually working on the project. `docker/`'s copy of the Dockerfile/compose/requirements/app code is kept in sync with this via `publish.ps1` (see below) — only needed if you're changing the code, not if you're just deploying it as-is.

## First-time setup

In `docker/`, copy the templates already there and fill them in:
```
copy devices.example.yaml devices.yaml   # then fill in YOUR PC's MAC/IP/broadcast
copy .env.example .env                   # then set a real RELAY_API_TOKEN (and AGENT_SHARED_SECRET if using Shutdown/Restart)
```

`devices.yaml` and `.env` are gitignored — they hold your own machine's details and secret tokens, never meant to be committed. Everything else in `docker/` is tracked normally.

If you're editing the code in `source-code/app/`, run `cd source-code && powershell -ExecutionPolicy Bypass -File .\publish.ps1` afterward to refresh `docker/`'s copy of the Dockerfile/compose/requirements/app code before committing or redeploying — it never touches `docker/devices.yaml` or `docker/.env`.

## Local dev (no Docker)

```
cd source-code
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements-dev.txt
python -m uvicorn app.main:app --reload --port 8675
```

For the interactive Swagger UI at `http://localhost:8675/docs` — useful for manually testing `/wake` and `/status` — set `RELAY_ENABLE_DOCS=true` first (in your shell or in a local `.env`). It's off by default because it advertises the full API surface, unauthenticated, to anyone who can reach the port.

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
