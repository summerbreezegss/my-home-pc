import os
from functools import lru_cache
from pathlib import Path

import yaml
from dotenv import load_dotenv

from .models import Device

load_dotenv()

DEVICES_FILE = Path(os.environ.get("DEVICES_FILE", Path(__file__).resolve().parent.parent / "devices.yaml"))


class Settings:
    def __init__(self) -> None:
        self.relay_api_token = self._require_env("RELAY_API_TOKEN")
        self.agent_shared_secret = os.environ.get("AGENT_SHARED_SECRET", "")

    @staticmethod
    def _require_env(name: str) -> str:
        value = os.environ.get(name)
        if not value:
            raise RuntimeError(f"Required environment variable {name} is not set")
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()


@lru_cache
def load_devices() -> dict[str, Device]:
    with open(DEVICES_FILE, "r") as f:
        raw = yaml.safe_load(f) or {}
    devices = [Device(**d) for d in raw.get("devices", [])]
    return {d.id: d for d in devices}
