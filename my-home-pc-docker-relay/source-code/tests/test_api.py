from pathlib import Path

import httpx
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
                    },
                    {
                        "id": "pc-agent",
                        "name": "Test PC With Agent",
                        "mac": "AA:BB:CC:DD:EE:FF",
                        "lan_ip": "192.0.2.51",
                        "broadcast": "192.0.2.255",
                        "agent_port": 9999,
                        "capabilities": ["wake", "status", "shutdown", "restart"],
                    },
                    {
                        "id": "pc-misconfigured",
                        "name": "Test PC Missing Agent Port",
                        "mac": "AA:BB:CC:DD:EE:FF",
                        "lan_ip": "192.0.2.52",
                        "broadcast": "192.0.2.255",
                        "capabilities": ["wake", "status", "shutdown"],
                    },
                ]
            }
        )
    )
    monkeypatch.setenv("RELAY_API_TOKEN", "test-token")
    monkeypatch.setenv("AGENT_SHARED_SECRET", "test-agent-secret")
    monkeypatch.setenv("RELAY_LAST_SEEN_FILE", str(tmp_path / "last_seen.json"))

    from app import config, ratelimit, status

    config.DEVICES_FILE = Path(str(devices_file))
    config.get_settings.cache_clear()
    config.load_devices.cache_clear()
    ratelimit.reset()  # every test starts with a clean failure ledger
    status._last_seen = None  # force re-read from (this test's) file

    from app.main import app

    return TestClient(app)


def test_health_no_auth(client):
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_health_exposes_nothing_but_status(client):
    # /health is unauthenticated - it must not leak version or any other detail.
    assert client.get("/api/v1/health").json() == {"status": "ok"}


def test_interactive_docs_hidden_by_default(client):
    # Swagger/OpenAPI advertise the API surface unauthenticated - off unless
    # RELAY_ENABLE_DOCS is explicitly set (read once at app import).
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404


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
    assert len(devices) == 3
    assert {d["id"] for d in devices} == {"pc-main", "pc-agent", "pc-misconfigured"}


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


def test_shutdown_rejected_when_capability_not_supported(client):
    # pc-main has no "shutdown" capability configured
    resp = client.post("/api/v1/devices/pc-main/shutdown", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 400
    assert resp.json()["detail"] == "capability_not_supported"


def test_shutdown_forwards_to_agent(client, monkeypatch):
    async def fake_forward_command(device, action):
        assert action == "shutdown"
        return {"result": "shutdown_scheduled"}

    from app import main

    monkeypatch.setattr(main, "forward_command", fake_forward_command)

    resp = client.post("/api/v1/devices/pc-agent/shutdown", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["action"] == "shutdown"
    assert body["result"] == "forwarded"
    assert body["agent_response"] == "shutdown_scheduled"


def test_restart_forwards_to_agent(client, monkeypatch):
    async def fake_forward_command(device, action):
        assert action == "restart"
        return {"result": "restart_scheduled"}

    from app import main

    monkeypatch.setattr(main, "forward_command", fake_forward_command)

    resp = client.post("/api/v1/devices/pc-agent/restart", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 200
    assert resp.json()["agent_response"] == "restart_scheduled"


def test_shutdown_returns_502_when_agent_unreachable(client, monkeypatch):
    async def fake_forward_command(device, action):
        raise httpx.ConnectError("connection refused")

    from app import main

    monkeypatch.setattr(main, "forward_command", fake_forward_command)

    resp = client.post("/api/v1/devices/pc-agent/shutdown", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 502
    assert resp.json()["detail"] == "agent_unreachable"


def test_repeated_auth_failures_lock_out_the_source(client):
    for _ in range(5):
        assert client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"}).status_code == 401
    # Sixth attempt: locked out — and a now-correct token is refused too, so the
    # lockout can't be used as a guessing oracle.
    resp = client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"})
    assert resp.status_code == 429
    assert "Retry-After" in resp.headers
    assert client.get("/api/v1/devices", headers={"Authorization": "Bearer test-token"}).status_code == 429


def test_successful_auth_resets_failure_count(client):
    for _ in range(4):
        client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"})
    assert client.get("/api/v1/devices", headers={"Authorization": "Bearer test-token"}).status_code == 200
    # Counter reset: four more failures are still 401 (not the fifth+ of a running streak).
    for _ in range(4):
        assert client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_auth_rejections_are_logged_with_source(client, caplog):
    with caplog.at_level("WARNING", logger="relay.auth"):
        client.get("/api/v1/devices", headers={"Authorization": "Bearer wrong"})
    assert any("auth rejected" in r.message and "ip=" in r.message for r in caplog.records)


def test_last_seen_survives_restart(client, monkeypatch, tmp_path):
    from app import status

    monkeypatch.setattr(status, "ping_host", lambda ip, timeout_s=1.0: True)
    body = client.get("/api/v1/devices/pc-main/status", headers={"Authorization": "Bearer test-token"}).json()
    assert body["last_seen"] is not None
    recorded = body["last_seen"]

    # Simulate a container restart: in-memory cache gone, file remains.
    status._last_seen = None
    monkeypatch.setattr(status, "ping_host", lambda ip, timeout_s=1.0: False)
    body = client.get("/api/v1/devices/pc-main/status", headers={"Authorization": "Bearer test-token"}).json()
    assert body["online"] is False
    assert body["last_seen"] == recorded


def test_agent_reachable_null_without_agent(client, monkeypatch):
    from app import status

    monkeypatch.setattr(status, "ping_host", lambda ip, timeout_s=1.0: True)
    body = client.get("/api/v1/devices/pc-main/status", headers={"Authorization": "Bearer test-token"}).json()
    assert body["agent_reachable"] is None


def test_agent_reachable_probes_agent_health(client, monkeypatch):
    from app import status

    monkeypatch.setattr(status, "ping_host", lambda ip, timeout_s=1.0: True)
    monkeypatch.setattr(status, "probe_agent", lambda device, timeout_s=1.5: True)
    body = client.get("/api/v1/devices/pc-agent/status", headers={"Authorization": "Bearer test-token"}).json()
    assert body["agent_reachable"] is True

    monkeypatch.setattr(status, "probe_agent", lambda device, timeout_s=1.5: False)
    body = client.get("/api/v1/devices/pc-agent/status", headers={"Authorization": "Bearer test-token"}).json()
    assert body["agent_reachable"] is False


def test_agent_reachable_false_when_host_offline_without_probing(client, monkeypatch):
    from app import status

    def must_not_probe(device, timeout_s=1.5):
        raise AssertionError("probe_agent must not be called for an offline host")

    monkeypatch.setattr(status, "ping_host", lambda ip, timeout_s=1.0: False)
    monkeypatch.setattr(status, "probe_agent", must_not_probe)
    body = client.get("/api/v1/devices/pc-agent/status", headers={"Authorization": "Bearer test-token"}).json()
    assert body["agent_reachable"] is False


def test_shutdown_returns_502_when_agent_port_missing(client):
    # pc-misconfigured grants the "shutdown" capability but has no agent_port set -
    # exercises the real (un-mocked) agent_client.forward_command/AgentNotConfiguredError path
    resp = client.post("/api/v1/devices/pc-misconfigured/shutdown", headers={"Authorization": "Bearer test-token"})
    assert resp.status_code == 502
    assert resp.json()["detail"] == "agent_unreachable"
