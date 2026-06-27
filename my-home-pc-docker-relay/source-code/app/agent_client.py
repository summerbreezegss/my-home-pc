import httpx

from .config import get_settings
from .models import Device


class AgentNotConfiguredError(Exception):
    pass


async def forward_command(device: Device, action: str) -> dict:
    if device.agent_port is None:
        raise AgentNotConfiguredError(f"Device {device.id} has no agent_port configured")

    settings = get_settings()
    url = f"http://{device.lan_ip}:{device.agent_port}/{action}"
    headers = {"Authorization": f"Bearer {settings.agent_shared_secret}"}

    async with httpx.AsyncClient(timeout=5.0) as client:
        response = await client.post(url, headers=headers)
        response.raise_for_status()
        return response.json()
