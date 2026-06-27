import logging

import httpx
from fastapi import Depends, FastAPI, HTTPException

from .agent_client import AgentNotConfiguredError, forward_command
from .auth import require_token
from .config import load_devices
from .models import Device, DeviceListResponse, StatusResponse, WakeResponse
from .status import get_device_status
from .wol import wake_device

logger = logging.getLogger("relay.agent_forward")

app = FastAPI(title="Remote PC Wake Relay")


def get_device_or_404(device_id: str) -> Device:
    device = load_devices().get(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device_not_found")
    return device


async def forward_action(device_id: str, action: str) -> dict:
    device = get_device_or_404(device_id)
    if action not in device.capabilities:
        raise HTTPException(status_code=400, detail="capability_not_supported")
    try:
        agent_response = await forward_command(device, action)
    except (httpx.HTTPError, AgentNotConfiguredError) as e:
        logger.error("agent forward failed for device=%s action=%s: %r", device_id, action, e)
        raise HTTPException(status_code=502, detail="agent_unreachable")
    return {
        "id": device.id,
        "action": action,
        "result": "forwarded",
        "agent_response": agent_response.get("result"),
    }


@app.get("/api/v1/health")
def health() -> dict:
    return {"status": "ok", "version": "0.1.0"}


@app.get("/api/v1/devices", response_model=DeviceListResponse, dependencies=[Depends(require_token)])
def list_devices() -> DeviceListResponse:
    return DeviceListResponse(devices=list(load_devices().values()))


@app.post("/api/v1/devices/{device_id}/wake", response_model=WakeResponse, dependencies=[Depends(require_token)])
def wake(device_id: str) -> WakeResponse:
    device = get_device_or_404(device_id)
    wake_device(device)
    return WakeResponse(id=device.id)


@app.get("/api/v1/devices/{device_id}/status", response_model=StatusResponse, dependencies=[Depends(require_token)])
def status(device_id: str) -> StatusResponse:
    device = get_device_or_404(device_id)
    return StatusResponse(**get_device_status(device))


@app.post("/api/v1/devices/{device_id}/shutdown", dependencies=[Depends(require_token)])
async def shutdown(device_id: str) -> dict:
    return await forward_action(device_id, "shutdown")


@app.post("/api/v1/devices/{device_id}/restart", dependencies=[Depends(require_token)])
async def restart(device_id: str) -> dict:
    return await forward_action(device_id, "restart")
