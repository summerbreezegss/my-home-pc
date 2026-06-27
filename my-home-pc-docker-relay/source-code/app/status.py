import subprocess
from datetime import datetime, timezone

from .models import Device

_last_seen: dict[str, str] = {}


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


def get_device_status(device: Device) -> dict:
    online = ping_host(device.lan_ip)
    if online:
        _last_seen[device.id] = datetime.now(timezone.utc).isoformat()
    return {
        "id": device.id,
        "online": online,
        "method": "ping",
        "last_seen": _last_seen.get(device.id),
        "agent_reachable": None,
    }
