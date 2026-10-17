"""Runs Volatility 3 in an isolated child process and returns its JSON rows.

The CLI is used (rather than the in-process framework API) so that a crashing or
runaway plugin cannot take the worker down, and so we can enforce a hard timeout,
strip the environment, and apply rlimits to the analysis process only.
"""
from __future__ import annotations

import json
import logging
import os
import re
import resource
import subprocess
import threading
from collections.abc import Callable
from pathlib import Path
from typing import Any

from .config import ALLOWED_PLUGINS, Config

log = logging.getLogger(__name__)

# Volatility writes lines like "Progress:   42.10\t\tScanning layer" to stderr, using \r for redraws.
_PROGRESS_RE = re.compile(r"Progress:\s+([0-9.]+)\s*(.*)")


class VolatilityError(RuntimeError):
    pass


def _limit_child() -> None:
    """Executed in the child before exec: own session, no core dumps (they would contain evidence)."""
    os.setsid()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def build_command(cfg: Config, image: Path, plugin: str) -> list[str]:
    if plugin not in ALLOWED_PLUGINS:
        raise VolatilityError(f"Plugin not allowed: {plugin}")
    cmd = [cfg.vol_binary, "-r", "json", "-f", str(image)]
    if cfg.vol_symbol_dirs:
        cmd += ["-s", cfg.vol_symbol_dirs]
    if cfg.vol_cache_dir:
        cmd += ["--cache-path", cfg.vol_cache_dir]
    if cfg.vol_offline:
        cmd.append("--offline")
    cmd.append(plugin)
    return cmd


def run_plugin(cfg: Config, image: Path, plugin: str,
               on_progress: Callable[[float, str], None] | None = None) -> list[dict[str, Any]]:
    cmd = build_command(cfg, image, plugin)
    log.info("Running: %s", " ".join(cmd))
    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "PYTHONPATH") if k in os.environ}
    env["PYTHONUNBUFFERED"] = "1"

    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            stdin=subprocess.DEVNULL, env=env, cwd=str(image.parent),
                            preexec_fn=_limit_child)

    stderr_tail: list[str] = []

    def pump_stderr() -> None:
        assert proc.stderr is not None
        buf = ""
        while chunk := proc.stderr.read1(4096).decode(errors="replace"):  # type: ignore[attr-defined]
            buf += chunk
            *lines, buf = re.split(r"[\r\n]", buf)
            for raw in lines:
                line = raw.strip()
                if not line:
                    continue
                m = _PROGRESS_RE.match(line)
                if m:
                    if on_progress:
                        on_progress(float(m.group(1)), m.group(2).strip())
                # Skip the banner and Volatility's indented troubleshooting hints; keep the real error.
                elif not raw[0].isspace() and not line.startswith("Volatility 3 Framework"):
                    stderr_tail.append(line)
                    del stderr_tail[:-20]

    stdout_chunks: list[bytes] = []

    def pump_stdout() -> None:
        assert proc.stdout is not None
        stdout_chunks.append(proc.stdout.read())

    pumps = [threading.Thread(target=f, daemon=True) for f in (pump_stderr, pump_stdout)]
    for t in pumps:
        t.start()
    try:
        proc.wait(timeout=cfg.vol_timeout)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, 9)
        proc.wait()
        raise VolatilityError(f"Volatility timed out after {cfg.vol_timeout}s")
    finally:
        for t in pumps:
            t.join(timeout=10)
    stdout = b"".join(stdout_chunks)

    if proc.returncode != 0:
        detail = " | ".join(stderr_tail[-3:]) or f"exit code {proc.returncode}"
        if "Unable to validate the plugin requirements" in detail:
            detail += " (not a supported memory image for this plugin, or kernel symbols are unavailable)"
        raise VolatilityError(f"Volatility failed: {detail}")
    try:
        rows = json.loads(stdout)
    except json.JSONDecodeError as e:
        raise VolatilityError(f"Could not parse Volatility JSON output: {e}") from e
    if not isinstance(rows, list):
        raise VolatilityError("Unexpected Volatility output shape")
    return rows
