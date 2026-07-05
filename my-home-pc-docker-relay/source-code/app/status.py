import json
import logging
import os
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import httpx

from .models import Device

logger = logging.getLogger("relay.status")

# In-memory cache of device_id -> ISO timestamp, backed by a tiny JSON file so it
# survives container restarts. None = not loaded from disk yet.
_last_seen: dict[str, str] | None = None


def _last_seen_file() -> Path:
    # Resolved per call (not at import) so tests can point it at a temp dir. The default
    # sits in data/, which docker-compose.yml volume-mounts — anywhere else inside the
    # container dies with it, which is exactly the bug this file exists to fix.
    return Path(os.environ.get(
        "RELAY_LAST_SEEN_FILE",
        Path(__file__).resolve().parent.parent / "data" / "last_seen.json",
    ))


def _get_last_seen() -> dict[str, str]:
    global _last_seen
    if _last_seen is None:
        try:
            with open(_last_seen_file(), "r") as f:
                raw = json.load(f)
            _last_seen = {k: v for k, v in raw.items() if isinstance(k, str) and isinstance(v, str)}
        except FileNotFoundError:
            _last_seen = {}
        except (OSError, ValueError) as e:
            logger.warning("could not read %s (%r) — starting with empty last_seen", _last_seen_file(), e)
            _last_seen = {}
    return _last_seen


def _save_last_seen(data: dict[str, str]) -> None:
    # Atomic replace so a crash mid-write can't leave a truncated file; failures are
    # logged but never break the status endpoint itself.
    target = _last_seen_file()
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_path = tempfile.mkstemp(dir=target.parent, suffix=".tmp")
        with os.fdopen(fd, "w") as f:
            json.dump(data, f)
        os.replace(tmp_path, target)
    except OSError as e:
        logger.warning("could not persist last_seen to %s: %r", target, e)


def ping_host(ip: str, timeout_s: float = 1.0) -> bool:
    try:
        result = subprocess.run(
            ["ping", "-c", "1", "-W", str(int(timeout_s)), ip],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=timeout_s + 1,
        )
        return result.returncode == 0
    except (subprocess.TimeoutExpired, OSError):
        return False


def probe_agent(device: Device, timeout_s: float = 1.5) -> bool:
    try:
        response = httpx.get(f"http://{device.lan_ip}:{device.agent_port}/health", timeout=timeout_s)
        return response.status_code == 200
    except httpx.HTTPError:
        return False


def get_device_status(device: Device) -> dict:
    online = ping_host(device.lan_ip)
    last_seen = _get_last_seen()
    if online:
        last_seen[device.id] = datetime.now(timezone.utc).isoformat()
        _save_last_seen(last_seen)

    if device.agent_port is None:
        # No agent configured for this device — "reachable" doesn't apply.
        agent_reachable = None
    elif not online:
        # Host doesn't answer pings; don't stack an HTTP timeout on top.
        agent_reachable = False
    else:
        agent_reachable = probe_agent(device)

    return {
        "id": device.id,
        "online": online,
        "method": "ping",
        "last_seen": last_seen.get(device.id),
        "agent_reachable": agent_reachable,
    }
