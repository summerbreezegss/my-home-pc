# MyHomePC PC Agent

A tiny .NET 8 Windows Service that runs **on the target PC itself** and accepts authenticated Shutdown/Restart commands forwarded by the relay. This only exists because powering a PC off or restarting it requires actively running code on that PC — nothing about Wake-on-LAN can do this, which is also why this is the one component that's entirely optional: if you only want the core Wake feature, you never need to install this at all.

LAN-only by design — it doesn't talk to the internet or Tailscale itself; the relay reaches it over your home network, the same way it reaches the PC for Wake-on-LAN and status checks.

## API

- `GET /health` — no auth — `{ "status": "ok", "hostname": "..." }`
- `POST /shutdown` — `Authorization: Bearer <AGENT_SHARED_SECRET>` — schedules `shutdown /s /t 5`
- `POST /restart` — same auth — schedules `shutdown /r /t 5`

Both return `202 Accepted` immediately (the action is scheduled, not yet complete — by the time it would complete, the process answering the request is gone).

## Local testing (before installing as a service)

```
cd PcAgent
dotnet run --no-launch-profile
```

Then from another terminal:
```
curl http://localhost:8585/health
curl -X POST http://localhost:8585/shutdown -H "Authorization: Bearer wrong"   # expect 401
```

Don't send a request with the *correct* secret unless you actually want this PC to shut down right now.

## Installing as a Windows Service

1. Open PowerShell **as Administrator**.
2. Run `install\install-service.ps1`. This publishes a self-contained `PcAgent.exe` to `C:\Program Files\PcAgent` and registers it as a service (set to auto-restart on crash), but does **not** start it yet.
3. Edit `C:\Program Files\PcAgent\appsettings.json` and set `AGENT_SHARED_SECRET` to the **same value** as the relay's `AGENT_SHARED_SECRET` (in the relay's `.env`).
4. Start it: `Start-Service PcAgent`.

To remove it later: run `install\uninstall-service.ps1` as Administrator.

**Note**: re-running `install-service.ps1` (e.g. after a code change) republishes the exe and will overwrite `appsettings.json` back to the placeholder secret — re-edit it after any republish. After that initial edit, though, changing the secret again later just needs the file saved — `appsettings.json` is watched for changes and reloaded automatically, no service restart required.

## Verifying the service

```powershell
Get-Service PcAgent
curl http://localhost:8585/health
```

To confirm crash auto-restart works: find the process in Task Manager and kill it, then check `Get-Service PcAgent` comes back to `Running` within about a minute.
