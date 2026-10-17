"""Thin client for the gateway's token-protected /internal endpoints."""
from __future__ import annotations

import hashlib
import logging
from pathlib import Path
from typing import Any

import requests

log = logging.getLogger(__name__)


class ApiClient:
    def __init__(self, base_url: str, token: str, timeout: float = 30.0):
        self._base = base_url
        self._timeout = timeout
        self._session = requests.Session()
        self._session.headers["X-Worker-Token"] = token

    def update_status(self, job_id: str, status: str, progress: int,
                      message: str | None = None, result: dict[str, Any] | None = None) -> None:
        body: dict[str, Any] = {"status": status, "progress": progress}
        if message is not None:
            body["message"] = message[:2000]
        if result is not None:
            body["result"] = result
        resp = self._session.put(f"{self._base}/internal/jobs/{job_id}/status", json=body, timeout=self._timeout)
        resp.raise_for_status()

    def download_evidence(self, job_id: str, dest: Path) -> str:
        """Stream the evidence to ``dest`` and return its SHA-256 for chain-of-custody verification."""
        digest = hashlib.sha256()
        with self._session.get(f"{self._base}/internal/jobs/{job_id}/evidence",
                               stream=True, timeout=self._timeout) as resp:
            resp.raise_for_status()
            with dest.open("wb") as out:
                for chunk in resp.iter_content(chunk_size=1 << 20):
                    out.write(chunk)
                    digest.update(chunk)
        return digest.hexdigest()
