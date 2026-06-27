import pytest

from app.wol import build_magic_packet


def test_build_magic_packet_length():
    packet = build_magic_packet("AA:BB:CC:DD:EE:FF")
    assert len(packet) == 102


def test_build_magic_packet_format():
    packet = build_magic_packet("AA:BB:CC:DD:EE:FF")
    mac_bytes = bytes.fromhex("AABBCCDDEEFF")
    assert packet[:6] == b"\xff" * 6
    assert packet[6:12] == mac_bytes
    assert packet.count(mac_bytes) == 16


def test_build_magic_packet_accepts_dash_separated():
    packet = build_magic_packet("AA-BB-CC-DD-EE-FF")
    assert len(packet) == 102


def test_build_magic_packet_rejects_invalid_mac():
    with pytest.raises(ValueError):
        build_magic_packet("not-a-mac")
