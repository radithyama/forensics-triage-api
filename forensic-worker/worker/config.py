"""Runtime configuration, read once from the environment."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ALLOWED_PLUGINS = frozenset({
    "windows.pslist.PsList",
    "windows.pstree.PsTree",
    "windows.cmdline.CmdLine",
    "windows.netscan.NetScan",
    "windows.malfind.Malfind",
    "linux.pslist.PsList",
})


def _bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Config:
    redis_host: str = field(default_factory=lambda: os.getenv("REDIS_HOST", "localhost"))
    redis_port: int = field(default_factory=lambda: int(os.getenv("REDIS_PORT", "6379")))
    redis_password: str | None = field(default_factory=lambda: os.getenv("REDIS_PASSWORD") or None)
    queue_key: str = field(default_factory=lambda: os.getenv("QUEUE_KEY", "forensics:jobs"))
    api_base_url: str = field(default_factory=lambda: os.getenv("API_BASE_URL", "http://localhost:8080").rstrip("/"))
    worker_token: str = field(default_factory=lambda: os.getenv("WORKER_TOKEN", "change-me-in-production"))
    work_dir: Path = field(default_factory=lambda: Path(os.getenv("WORK_DIR", "/work")))
    heartbeat_file: Path = field(default_factory=lambda: Path(os.getenv("HEARTBEAT_FILE", "/tmp/worker-heartbeat")))
    vol_binary: str = field(default_factory=lambda: os.getenv("VOL_BINARY", "vol"))
    vol_timeout: int = field(default_factory=lambda: int(os.getenv("VOL_TIMEOUT_SECONDS", "1800")))
    vol_symbol_dirs: str | None = field(default_factory=lambda: os.getenv("VOL_SYMBOL_DIRS") or None)
    vol_cache_dir: str | None = field(default_factory=lambda: os.getenv("VOL_CACHE_DIR") or None)
    vol_offline: bool = field(default_factory=lambda: _bool("VOL_OFFLINE", False))

    @property
    def processing_key(self) -> str:
        return f"{self.queue_key}:processing"
