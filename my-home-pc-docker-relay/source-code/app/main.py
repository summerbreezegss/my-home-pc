import logging
import os

import httpx
from fastapi import Depends, FastAPI, HTTPException

from .agent_client import AgentNotConfiguredError, forward_command
from .auth import require_token
from .config import load_devices
from .models import AppDeviceListResponse, AppDeviceView, Device, StatusResponse, WakeResponse
from .status import get_device_status
from .wol import wake_device

logger = logging.getLogger("relay.agent_forward")

# Swagger UI (/docs, /redoc) and the OpenAPI schema advertise the full API surface to anyone
# who can reach the port, unauthenticated — useful as a manual test console during development,
# free reconnaissance in production. Off unless explicitly re-enabled via the environment.
# (Read here at import time is safe: importing .config above already ran load_dotenv().)
_docs_enabled = os.environ.get("RELAY_ENABLE_DOCS", "").strip().lower() in ("1", "true", "yes")

app = FastAPI(
    title="Remote PC Wake Relay",
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
)


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
    # Unauthenticated liveness check only — no version (or anything else) here, since
    # anything this endpoint returns is readable by whoever can reach the port.
    return {"status": "ok"}


@app.get("/api/v1/devices", response_model=AppDeviceListResponse, dependencies=[Depends(require_token)])
def list_devices() -> AppDeviceListResponse:
    return AppDeviceListResponse(
        devices=[AppDeviceView(id=d.id, name=d.name, capabilities=d.capabilities)
                 for d in load_devices().values()]
    )


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
