import socket
import time

from .models import Device


def build_magic_packet(mac: str) -> bytes:
    mac_bytes = bytes.fromhex(mac.replace(":", "").replace("-", ""))
    if len(mac_bytes) != 6:
        raise ValueError(f"MAC must be 6 bytes, got {len(mac_bytes)}: {mac!r}")
    return b"\xff" * 6 + mac_bytes * 16


def send_magic_packet(mac: str, broadcast_ip: str, port: int) -> None:
    packet = build_magic_packet(mac)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.sendto(packet, (broadcast_ip, port))


def wake_device(device: Device, attempts: int = 3, delay_s: float = 0.5) -> None:
    for attempt in range(attempts):
        for port in device.wol_ports:
            send_magic_packet(device.mac, device.broadcast, port)
        if attempt < attempts - 1:
            time.sleep(delay_s)
