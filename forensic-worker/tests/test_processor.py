import base64
import json
from datetime import datetime, timedelta, timezone

import pytest

from worker.app import create_app
from worker.config import Config
from worker.processor import Processor

GOOD_MD5 = "goodmd5=="
MSG = {"jobId": "job-1", "plugin": "windows.pslist.PsList", "bucket": "b",
       "object": "evidence/job-1/evidence.img", "generation": 42, "md5": GOOD_MD5}
ROWS = [{"PID": 4, "PPID": 0, "ImageFileName": "System", "__children": []},
        {"PID": 700, "PPID": 4, "ImageFileName": "lsass.exe", "__children": []}]


class FakeJobs:
    def __init__(self, job):
        self.job = job
        self.history = []

    def load(self, job_id):
        return dict(self.job) if self.job else None

    def update(self, job_id, **fields):
        self.job.update({k: v for k, v in fields.items() if v is not None})
        self.job["updatedAt"] = datetime.now(timezone.utc).isoformat()
        self.history.append(fields.get("status"))
        return self.job


class FakeEvidence:
    def __init__(self, size=1024, md5=GOOD_MD5):
        self._size, self._md5 = size, md5
        self.deleted = []

    def size(self, bucket, name, generation):
        return self._size

    def download(self, bucket, name, generation, dest):
        dest.write_bytes(b"\0" * 16)
        return "ab" * 32, self._md5

    def delete(self, bucket, name, generation):
        self.deleted.append((name, generation))


@pytest.fixture
def cfg(tmp_path):
    return Config(bucket="b", work_dir=tmp_path)


def make(cfg, status="QUEUED", evidence=None, runner=None, mem=None, updated=None):
    jobs = FakeJobs({"id": "job-1", "status": status, "updatedAt": updated})
    ev = evidence or FakeEvidence()
    proc = Processor(cfg, jobs, ev, runner=runner or (lambda *a: ROWS), mem_limit=lambda: mem)
    return proc, jobs, ev


def test_happy_path_completes_and_deletes_evidence(cfg):
    proc, jobs, ev = make(cfg)
    assert proc.handle(MSG) == "completed"
    assert jobs.job["status"] == "COMPLETED"
    assert jobs.job["sha256"] == "ab" * 32
    assert jobs.job["result"]["summary"]["processCount"] == 2
    assert ev.deleted == [("evidence/job-1/evidence.img", 42)]
    assert not any(cfg.work_dir.iterdir()), "local copy must be removed"


def test_md5_mismatch_fails_closed(cfg):
    proc, jobs, _ = make(cfg, evidence=FakeEvidence(md5="tampered=="))
    assert proc.handle(MSG) == "failed"
    assert "chain of custody" in jobs.job["message"]


def test_redelivery_of_finished_job_is_acked_without_rerun(cfg):
    calls = []
    proc, jobs, _ = make(cfg, status="COMPLETED", runner=lambda *a: calls.append(1) or ROWS)
    assert proc.handle(MSG) == "duplicate"
    assert calls == []


def test_fresh_running_job_is_skipped_but_stale_one_is_retried(cfg):
    fresh = datetime.now(timezone.utc).isoformat()
    stale = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
    assert make(cfg, status="RUNNING", updated=fresh)[0].handle(MSG) == "in-progress"
    assert make(cfg, status="RUNNING", updated=stale)[0].handle(MSG) == "completed"


def test_evidence_larger_than_memory_fails_with_clear_message(cfg):
    proc, jobs, _ = make(cfg, evidence=FakeEvidence(size=6 << 30), mem=4 << 30)
    assert proc.handle(MSG) == "failed"
    assert "raise the worker's --memory" in jobs.job["message"]


def test_push_endpoint_acks_valid_and_poison_messages(cfg):
    proc, jobs, _ = make(cfg)
    client = create_app(proc).test_client()
    data = base64.b64encode(json.dumps(MSG).encode()).decode()
    assert client.post("/", json={"message": {"data": data, "messageId": "1"}}).status_code == 204
    assert jobs.job["status"] == "COMPLETED"
    assert client.post("/", json={"message": {"data": "bm90IGpzb24="}}).status_code == 204
    assert client.post("/", json={"hello": "world"}).status_code == 400


def test_transient_store_error_returns_500_for_retry(cfg):
    proc, jobs, _ = make(cfg)
    jobs.load = lambda job_id: (_ for _ in ()).throw(ConnectionError("gcs down"))
    app = create_app(proc)
    app.config["PROPAGATE_EXCEPTIONS"] = False
    data = base64.b64encode(json.dumps(MSG).encode()).decode()
    assert app.test_client().post("/", json={"message": {"data": data}}).status_code == 500
