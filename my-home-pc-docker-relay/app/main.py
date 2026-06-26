from fastapi import Depends, FastAPI, HTTPException

from .auth import require_token
from .config import load_devices
from .models import Device, DeviceListResponse, StatusResponse, WakeResponse
from .status import get_device_status
from .wol import wake_device

app = FastAPI(title="Remote PC Wake Relay")


def get_device_or_404(device_id: str) -> Device:
    device = load_devices().get(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device_not_found")
    return device


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
