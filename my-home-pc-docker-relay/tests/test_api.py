from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient


@pytest.fixture
def client(tmp_path, monkeypatch):
    devices_file = tmp_path / "devices.yaml"
    devices_file.write_text(
        yaml.dump(
            {
                "devices": [
                    {
                        "id": "pc-main",
                        "name": "Test PC",
                        "mac": "AA:BB:CC:DD:EE:FF",
                        "lan_ip": "192.0.2.50",
                        "broadcast": "192.0.2.255",
                        "capabilities": ["wake", "status"],
                    }
                ]
            }
        )
    )
    monkeypatch.setenv("RELAY_API_TOKEN", "test-token")

    from app import config

    config.DEVICES_FILE = Path(str(devices_file))
    config.get_settings.cache_clear()
    config.load_devices.cache_clear()

    from app.main import app

    return TestClient(app)


def test_health_no_auth(client):
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_devices_requires_auth(client):
    resp = client.get("/api/v1/devices")
    assert resp.status_code == 401


def test_devices_rejects_wrong_token(client):
    resp = client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"})
    assert resp.status_code == 401


def test_devices_with_valid_token(client):
    resp = client.get("/api/v1/devices", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 200
    devices = resp.json()["devices"]
    assert len(devices) == 1
    assert devices[0]["id"] == "pc-main"


def test_wake_unknown_device_404(client):
    resp = client.post("/api/v1/devices/nope/wake", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 404


def test_wake_sends_packet(client, monkeypatch):
    calls = []
    from app import wol

    monkeypatch.setattr(wol, "send_magic_packet", lambda mac, ip, port: calls.append((mac, ip, port)))

    resp = client.post("/api/v1/devices/pc-main/wake", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 200
    assert resp.json()["result"] == "packet_sent"
    assert len(calls) > 0


def test_status_offline_for_unreachable_ip(client, monkeypatch):
    from app import status

    monkeypatch.setattr(status, "ping_host", lambda ip, timeout_s=1.0: False)

    resp = client.get("/api/v1/devices/pc-main/status", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["online"] is False
    assert body["method"] == "ping"
