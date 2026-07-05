# MyHomePC PC Agent

A tiny .NET 8 Windows Service that runs **on the target PC itself** and accepts authenticated Shutdown/Restart commands forwarded by the relay. This only exists because powering a PC off or restarting it requires actively running code on that PC — nothing about Wake-on-LAN can do this, which is also why this is the one component that's entirely optional: if you only want the core Wake feature, you never need to install this at all. See the [root README](../README.md) for how this fits into the whole system and why it's built this way.

It never makes outbound connections — the relay reaches *it* over your home network, the same way it reaches the PC for Wake-on-LAN and status checks. Note it listens on all of the PC's network interfaces (not just the LAN one), so the Windows firewall and the shared secret are what actually bound who can reach it — see `developer-docs/architecture.md`'s security model.

This folder has two parts:
- **`Installer/`** — the only thing end users need. Share just this folder.
- **`source-code/`** — the actual source, only needed if you're building or editing this project.

## API

- `GET /health` — no auth — `{ "status": "ok" }`
- `POST /shutdown` — `Authorization: Bearer <AGENT_SHARED_SECRET>` — schedules `shutdown /s /t 5`
- `POST /restart` — same auth — schedules `shutdown /r /t 5`

Both return `202 Accepted` immediately (the action is scheduled, not yet complete — by the time it would complete, the process answering the request is gone).

## Installing as a Windows Service

1. Get the `Installer` folder — either build it (`cd source-code && powershell -ExecutionPolicy Bypass -File .\publish.ps1`, no admin rights needed for this part) or download it from this repo's Releases page if a build has been published.
2. Double-click `Install PcAgent.exe` inside it. Windows will prompt for admin via UAC — accept it.
3. It'll ask for a shared secret: press Enter to auto-generate a strong one, or type your own. Either way, it prints the secret at the end — copy that into the relay's `.env` as `AGENT_SHARED_SECRET` (must match exactly).
4. That's it — it copies the files to `C:\Program Files\PcAgent`, registers the service (auto-restart on crash), opens the firewall port, starts it, and drops an `Uninstall PcAgent.exe` into that same folder for later.

To remove it later: double-click the `Uninstall PcAgent.exe` inside `C:\Program Files\PcAgent\`. It stops the service, removes the firewall rule, and deletes the entire `C:\Program Files\PcAgent` folder — including itself.

Re-running `Install PcAgent.exe` (e.g. after a code change — re-run `publish.ps1` first) replaces the existing install and generates a **new** secret by default — type the old one in again at the prompt if you want to keep using the same value without also re-editing the relay's `.env`.

## Local testing (without installing as a service)

The service refuses to start while `AGENT_SHARED_SECRET` is unset or still the placeholder
shipped in `appsettings.json`, so give it a throwaway value for the test run (an environment
variable works — no need to edit the file):

```
cd source-code/PcAgent
$env:AGENT_SHARED_SECRET = "local-test-secret"
dotnet run --no-launch-profile
```

Then from another terminal:
```
curl http://localhost:8585/health
curl -X POST http://localhost:8585/shutdown -H "Authorization: Bearer wrong"   # expect 401
```

Don't send a request with the *correct* secret unless you actually want this PC to shut down right now.

## Verifying the service

```powershell
Get-Service PcAgent
curl http://localhost:8585/health
```

To confirm crash auto-restart works: find the process in Task Manager and kill it, then check `Get-Service PcAgent` comes back to `Running` within about a minute.

## Source layout

```
my-home-pc-agent/
├── Installer/              # build output (gitignored) - share just this with end users
└── source-code/
    ├── PcAgent.sln
    ├── publish.ps1          # builds everything into ../Installer
    ├── PcAgent/             # the Windows Service itself
    └── PcAgentSetup/        # the installer
        └── Uninstaller/     # the uninstaller it bundles in and copies alongside the service
```
