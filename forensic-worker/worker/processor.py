"""Runs one triage job end to end. Safe to call repeatedly for the same message (Pub/Sub is at-least-once)."""
from __future__ import annotations

import logging
import shutil
import time
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any

from . import triage
from .config import ALLOWED_PLUGINS, Config
from .gcs import EvidenceStore, JobStore
from .volatility_runner import VolatilityError, run_plugin

log = logging.getLogger(__name__)

Runner = Callable[..., list[dict[str, Any]]]


def memory_limit_bytes() -> int | None:
    """Container memory limit (cgroup v2, then v1), or None if unlimited/unknown."""
    for path in ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"):
        try:
            raw = Path(path).read_text().strip()
        except OSError:
            continue
        if raw.isdigit() and int(raw) < (1 << 60):
            return int(raw)
    return None


def _age_seconds(iso: str | None) -> float:
    if not iso:
        return float("inf")
    return time.time() - datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()


class Processor:
    def __init__(self, cfg: Config, jobs: JobStore, evidence: EvidenceStore,
                 runner: Runner = run_plugin, mem_limit: Callable[[], int | None] = memory_limit_bytes):
        self.cfg, self.jobs, self.evidence, self.runner, self.mem_limit = cfg, jobs, evidence, runner, mem_limit

    def handle(self, msg: dict[str, Any]) -> str:
        """Returns a short outcome label. Raises only for transient errors that deserve a Pub/Sub retry."""
        job_id = msg["jobId"]
        job = self.jobs.load(job_id)
        if job is None:
            return "unknown-job"
        status = job.get("status")
        if status in ("COMPLETED", "FAILED"):
            return "duplicate"
        if status == "RUNNING" and _age_seconds(job.get("updatedAt")) < self.cfg.stale_after_seconds:
            return "in-progress"  # redelivery while another instance is still working on it

        try:
            result = self._analyse(job_id, msg)
            self.jobs.update(job_id, status="COMPLETED", progress=100, message="Analysis complete",
                             sha256=result["evidenceSha256"], result=result)
            log.info("Job %s completed in %.1fs", job_id, result["durationSeconds"])
            outcome = "completed"
        except Exception as e:
            log.exception("Job %s failed", job_id)
            self.jobs.update(job_id, status="FAILED", progress=100, message=str(e)[:2000])
            outcome = "failed"
        # Raw memory should not outlive the analysis; the bucket lifecycle rule is only the backstop.
        try:
            self.evidence.delete(msg["bucket"], msg["object"], msg["generation"])
        except Exception as e:
            log.warning("Could not delete evidence for %s: %s", job_id, e)
        return outcome

    def _analyse(self, job_id: str, msg: dict[str, Any]) -> dict[str, Any]:
        plugin = msg.get("plugin", "windows.pslist.PsList")
        if plugin not in ALLOWED_PLUGINS:
            raise VolatilityError(f"Plugin not allowed: {plugin}")
        bucket, name, generation = msg["bucket"], msg["object"], int(msg["generation"])

        size = self.evidence.size(bucket, name, generation)
        if size is None:
            raise VolatilityError("Evidence object no longer exists (expired or replaced)")
        limit = self.mem_limit()
        if limit is not None and size + self.cfg.memory_headroom_bytes > limit:
            raise VolatilityError(
                f"Evidence is {size / 2**30:.1f} GiB but this worker has {limit / 2**30:.1f} GiB of memory; "
                "raise the worker's --memory to analyse it")

        job_dir = self.cfg.work_dir / job_id
        job_dir.mkdir(parents=True, exist_ok=True)
        image = job_dir / "evidence.img"
        started = time.monotonic()
        try:
            self.jobs.update(job_id, status="RUNNING", progress=5, message="Fetching evidence from Cloud Storage")
            sha256, md5 = self.evidence.download(bucket, name, generation, image)
            if msg.get("md5") and md5 != msg["md5"]:
                raise VolatilityError("Evidence hash mismatch: chain of custody broken, refusing to analyse")
            self.jobs.update(job_id, status="RUNNING", progress=15,
                             message=f"Evidence verified (sha256 {sha256[:12]}…), running {plugin}")

            last = [0.0]

            def on_progress(pct: float, stage: str) -> None:
                # Map Volatility's 0-100 onto 15-90; throttle so we write the job doc at most every 5s.
                if time.monotonic() - last[0] < 5:
                    return
                last[0] = time.monotonic()
                try:
                    self.jobs.update(job_id, status="RUNNING", progress=15 + int(pct * 0.75), message=stage or "Analysing")
                except Exception as e:  # progress is best-effort
                    log.debug("Progress update failed: %s", e)

            rows = self.runner(self.cfg, image, plugin, on_progress)
            self.jobs.update(job_id, status="RUNNING", progress=92, message="Building process tree")
            result = triage.analyse(plugin, rows)
            result["evidenceSha256"] = sha256
            result["durationSeconds"] = round(time.monotonic() - started, 2)
            return result
        finally:
            shutil.rmtree(job_dir, ignore_errors=True)
