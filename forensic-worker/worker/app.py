"""HTTP entrypoint for the Pub/Sub push subscription (served by gunicorn as ``worker.app:create_app()``).

Cloud Run enforces IAM (only the push subscription's service account holds run.invoker), so every
request reaching this handler has already been authenticated by Google's front end.
"""
from __future__ import annotations

import base64
import json
import logging

from flask import Flask, request
from google.cloud import storage

from .config import Config
from .gcs import EvidenceStore, JobStore
from .processor import Processor

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
log = logging.getLogger("forensic-worker")


def create_app(processor: Processor | None = None) -> Flask:
    app = Flask(__name__)
    if processor is None:
        cfg = Config()
        cfg.work_dir.mkdir(parents=True, exist_ok=True)
        client = storage.Client()
        processor = Processor(cfg, JobStore(client, cfg.bucket), EvidenceStore(client))

    @app.post("/")
    def pubsub_push():
        envelope = request.get_json(silent=True)
        if not isinstance(envelope, dict) or "message" not in envelope:
            return "Expected a Pub/Sub push envelope", 400
        message = envelope["message"]
        try:
            payload = json.loads(base64.b64decode(message.get("data", "")))
            payload["jobId"]
        except (ValueError, KeyError, TypeError):
            # Poison message: acknowledge (2xx) so Pub/Sub stops redelivering it.
            log.error("Discarding malformed message %s", message.get("messageId"))
            return "", 204
        outcome = processor.handle(payload)  # transient errors raise -> 500 -> Pub/Sub retries with backoff
        log.info("message=%s job=%s outcome=%s", message.get("messageId"), payload["jobId"], outcome)
        return "", 204

    @app.get("/healthz")
    def healthz():
        return "ok", 200

    return app

