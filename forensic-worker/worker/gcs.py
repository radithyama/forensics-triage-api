"""GCS access: job documents (jobs/<id>.json, shared with the gateway) and evidence objects."""
from __future__ import annotations

import base64
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from google.cloud import storage


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class JobStore:
    def __init__(self, client: storage.Client, bucket: str):
        self._bucket = client.bucket(bucket)

    def load(self, job_id: str) -> dict[str, Any] | None:
        blob = self._bucket.get_blob(f"jobs/{job_id}.json")
        return None if blob is None else json.loads(blob.download_as_bytes())

    def update(self, job_id: str, **fields: Any) -> dict[str, Any]:
        """Read-modify-write. After the gateway queues a job, the worker is the document's only writer."""
        job = self.load(job_id) or {"id": job_id}
        job.update({k: v for k, v in fields.items() if v is not None})
        job["updatedAt"] = now_iso()
        blob = self._bucket.blob(f"jobs/{job_id}.json")
        blob.upload_from_string(json.dumps(job), content_type="application/json")
        return job


class EvidenceStore:
    def __init__(self, client: storage.Client):
        self._client = client

    def size(self, bucket: str, name: str, generation: int) -> int | None:
        blob = self._client.bucket(bucket).get_blob(name, generation=generation)
        return None if blob is None else blob.size

    def download(self, bucket: str, name: str, generation: int, dest: Path) -> tuple[str, str]:
        """Download the pinned generation and return (sha256 hex, md5 base64) computed locally."""
        blob = self._client.bucket(bucket).blob(name, generation=generation)
        blob.download_to_filename(str(dest), checksum="md5")
        sha, md5 = hashlib.sha256(), hashlib.md5()
        with dest.open("rb") as f:
            while chunk := f.read(8 << 20):
                sha.update(chunk)
                md5.update(chunk)
        return sha.hexdigest(), base64.b64encode(md5.digest()).decode()

    def delete(self, bucket: str, name: str, generation: int) -> None:
        self._client.bucket(bucket).blob(name, generation=generation).delete()
