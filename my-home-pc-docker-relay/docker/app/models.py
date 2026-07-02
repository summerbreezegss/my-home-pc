from typing import Literal, Optional

from pydantic import BaseModel

Capability = Literal["wake", "status", "sleep", "shutdown", "restart"]


class Device(BaseModel):
    id: str
    name: str
    mac: str
    lan_ip: str
    broadcast: str
    wol_ports: list[int] = [9, 7]
    agent_port: Optional[int] = None
    capabilities: list[Capability] = ["wake", "status"]


class AppDeviceView(BaseModel):
    """Minimal device representation sent to the app — no internal network details."""
    id: str
    name: str
    capabilities: list[Capability] = []


class DeviceListResponse(BaseModel):
    devices: list[Device]


class AppDeviceListResponse(BaseModel):
    devices: list[AppDeviceView]


class WakeResponse(BaseModel):
    id: str
    action: Literal["wake"] = "wake"
    result: Literal["packet_sent"] = "packet_sent"


class StatusResponse(BaseModel):
    id: str
    online: bool
    method: Literal["ping", "agent"]
    last_seen: Optional[str] = None
    agent_reachable: Optional[bool] = None
