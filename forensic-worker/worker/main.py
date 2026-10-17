"""Forensic worker: consumes jobs from Redis, runs Volatility, reports back to the gateway."""
from __future__ import annotations

import json
import logging
import shutil
import signal
import time
from pathlib import Path

import redis

from . import triage
from .api_client import ApiClient
from .config import ALLOWED_PLUGINS, Config
from .volatility_runner import VolatilityError, run_plugin

log = logging.getLogger("forensic-worker")

_running = True


def _stop(signum, _frame) -> None:
    global _running
    log.info("Received signal %s, finishing current job then exiting", signum)
    _running = False


def process_job(cfg: Config, api: ApiClient, job: dict) -> None:
    job_id, plugin, expected_sha = job["jobId"], job.get("plugin", "windows.pslist.PsList"), job.get("sha256")
    if plugin not in ALLOWED_PLUGINS:
        api.update_status(job_id, "FAILED", 100, f"Plugin not allowed: {plugin}")
        return

    job_dir = cfg.work_dir / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    image = job_dir / "evidence.img"
    started = time.monotonic()
    try:
        api.update_status(job_id, "RUNNING", 5, "Fetching evidence")
        actual_sha = api.download_evidence(job_id, image)
        if expected_sha and actual_sha != expected_sha:
            raise VolatilityError("Evidence hash mismatch: chain of custody broken, refusing to analyse")

        api.update_status(job_id, "RUNNING", 15, f"Evidence verified (sha256 {actual_sha[:12]}…), running {plugin}")

        last_report = [0.0]

        def on_progress(pct: float, stage: str) -> None:
            # Map Volatility's 0-100 onto 15-90 and throttle updates to one every 2s.
            now = time.monotonic()
            if now - last_report[0] < 2:
                return
            last_report[0] = now
            try:
                api.update_status(job_id, "RUNNING", 15 + int(pct * 0.75), stage or "Analysing")
            except Exception as e:  # progress is best-effort
                log.debug("Progress update failed: %s", e)

        rows = run_plugin(cfg, image, plugin, on_progress)
        api.update_status(job_id, "RUNNING", 92, "Building process tree")
        result = triage.analyse(plugin, rows)
        result["evidenceSha256"] = actual_sha
        result["durationSeconds"] = round(time.monotonic() - started, 2)
        api.update_status(job_id, "COMPLETED", 100, "Analysis complete", result)
        log.info("Job %s completed in %.1fs", job_id, result["durationSeconds"])
    except Exception as e:
        log.exception("Job %s failed", job_id)
        try:
            api.update_status(job_id, "FAILED", 100, str(e))
        except Exception:
            log.exception("Could not report failure for job %s", job_id)
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = Config()
    cfg.work_dir.mkdir(parents=True, exist_ok=True)
    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    r = redis.Redis(host=cfg.redis_host, port=cfg.redis_port, password=cfg.redis_password,
                    decode_responses=True, socket_keepalive=True, health_check_interval=30)
    api = ApiClient(cfg.api_base_url, cfg.worker_token)
    log.info("Worker started; queue=%s api=%s", cfg.queue_key, cfg.api_base_url)

    while _running:
        Path(cfg.heartbeat_file).touch()
        try:
            # Atomically move the job to a processing list so a crash mid-job leaves it recoverable.
            raw = r.blmove(cfg.queue_key, cfg.processing_key, timeout=5, src="RIGHT", dest="LEFT")
        except redis.RedisError as e:
            log.warning("Redis unavailable (%s); retrying in 3s", e)
            time.sleep(3)
            continue
        if raw is None:
            continue
        try:
            process_job(cfg, api, json.loads(raw))
        except (json.JSONDecodeError, KeyError) as e:
            log.error("Discarding malformed job message %r: %s", raw[:200], e)
        finally:
            r.lrem(cfg.processing_key, 1, raw)


if __name__ == "__main__":
    main()
