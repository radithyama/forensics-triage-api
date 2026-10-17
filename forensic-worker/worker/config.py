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
    bucket: str = field(default_factory=lambda: os.getenv("EVIDENCE_BUCKET", ""))
    # On Cloud Run /tmp is an in-memory filesystem that counts against the instance memory limit.
    work_dir: Path = field(default_factory=lambda: Path(os.getenv("WORK_DIR", "/tmp/work")))
    vol_binary: str = field(default_factory=lambda: os.getenv("VOL_BINARY", "vol"))
    # Must stay below the Pub/Sub ack deadline (600s) so every analysis finishes inside one push.
    vol_timeout: int = field(default_factory=lambda: int(os.getenv("VOL_TIMEOUT_SECONDS", "540")))
    vol_symbol_dirs: str | None = field(default_factory=lambda: os.getenv("VOL_SYMBOL_DIRS") or None)
    vol_cache_dir: str | None = field(default_factory=lambda: os.getenv("VOL_CACHE_DIR") or None)
    vol_offline: bool = field(default_factory=lambda: _bool("VOL_OFFLINE", False))
    # A RUNNING job not updated for this long is treated as abandoned (crashed instance) and retried.
    stale_after_seconds: int = field(default_factory=lambda: int(os.getenv("STALE_AFTER_SECONDS", "300")))
    # Memory reserved for Volatility itself on top of the evidence held in /tmp.
    memory_headroom_bytes: int = field(default_factory=lambda: int(os.getenv("MEMORY_HEADROOM_BYTES", str(1536 << 20))))
