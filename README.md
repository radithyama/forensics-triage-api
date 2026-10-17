# Automated Memory Forensics Triage API

Upload a memory image, run a Volatility 3 plugin against it in an isolated worker, and get back an
interactive process tree with automated anomaly triage (masquerading names, unexpected parents,
duplicate singletons such as a second `lsass.exe`, Office → LOLBin chains, orphans).

Fully serverless on Google Cloud: three Cloud Run services, one GCS bucket and Pub/Sub. Every
component scales to zero, so an idle deployment costs close to nothing.

```
                 ① POST /api/jobs ─────────► api-gateway (Cloud Run, public)
 browser ──► frontend (Cloud Run, nginx)        │  returns a V4 signed URL, writes jobs/<id>.json
    │            ③ POST /api/jobs/<id>/start ──►│  pins object generation + MD5, publishes
    │                                           ▼
    └─② PUT evidence ──► GCS bucket ◄──── Pub/Sub topic ──push (OIDC)──► forensic-worker (Cloud Run, private)
                         evidence/<id>/…                                  │ downloads to /tmp, verifies MD5,
                         jobs/<id>.json ◄─────────────────────────────────┘ runs Volatility, writes result,
                         (1-day lifecycle)                                    deletes evidence
```

| Service | Stack | Role |
|---|---|---|
| `frontend/` | Next.js 16 static export, nginx | Upload UI, progress, process tree, findings. **Simulation Mode** replays bundled data; **Live Trial** uploads for real. nginx proxies `/api` to the gateway. |
| `api-gateway/` | Java 21, Spring Boot 4 WebFlux, GCS + Pub/Sub clients | Job registration, signed upload URLs, start/queue, status reads. |
| `forensic-worker/` | Python 3.12, Flask + gunicorn, Volatility 3 | Pub/Sub push endpoint; one analysis per instance. |

## API

```bash
# 1. Register the job; get a signed URL
curl -X POST $HOST/api/jobs -H 'content-type: application/json' \
     -d '{"filename":"WIN10.raw","sizeBytes":4294967296,"plugin":"windows.pslist.PsList"}'
# 2. Upload straight to Cloud Storage (exact Content-Type is part of the signature)
curl -X PUT -H 'Content-Type: application/octet-stream' --data-binary @WIN10.raw "$UPLOAD_URL"
# 3. Queue it, then poll
curl -X POST $HOST/api/jobs/<id>/start
curl $HOST/api/jobs/<id>
```

Statuses: `AWAITING_UPLOAD → QUEUED → RUNNING (progress 0–100) → COMPLETED | FAILED`. Allowed plugins:
`windows.pslist.PsList`, `windows.pstree.PsTree`, `windows.cmdline.CmdLine`, `windows.netscan.NetScan`,
`windows.malfind.Malfind`, `linux.pslist.PsList`.

**Why signed URLs?** Cloud Run caps HTTP/1 request bodies at 32 MiB, and memory images are gigabytes.
The browser uploads directly to GCS, so evidence never passes through a container.

## Deploy

```bash
PROJECT_ID=my-project ./deploy/cloudrun-deploy.sh
```

The script is idempotent. It creates the bucket (1-day lifecycle, no soft-delete, public access
prevention), the Pub/Sub topic, dead-letter topic and OIDC push subscription, and one service
account per workload with resource-scoped roles. It builds the images with Cloud Build, deploys the
three services and sets bucket CORS for the frontend origin.

## Develop

Tests: `mvn test` (gateway), `pytest` (worker), `npm run lint && npm run build` (frontend).
`NEXT_PUBLIC_API_BASE=https://<gateway-url> npm run dev` runs the UI against a deployed gateway.
Simulation Mode needs no backend at all.

## Security design

- **No key files.** Each service runs as its own service account. The gateway signs upload URLs through IAM `signBlob` on its own identity.
- **Private worker.** It is deployed with `--no-allow-unauthenticated`; only the push subscription's service account holds `run.invoker`, and Pub/Sub attaches an OIDC token to every push.
- **Least privilege.** Roles are bound to individual resources: the bucket (`storage.objectUser`), the topic (`pubsub.publisher`) and the worker service (`run.invoker`). Nothing is granted project-wide except the build SA's log writer.
- **Chain of custody.** Starting a job pins the exact GCS object generation and its server-side MD5. The worker downloads that generation, re-computes MD5 (and SHA-256 for the report) and refuses to analyse on mismatch.
- **Evidence hygiene.** The worker deletes evidence from GCS and `/tmp` as soon as a job finishes. The bucket lifecycle deletes anything older than a day, and soft-delete is off, so deleted dumps are really gone.
- **Isolation.** Volatility runs as a child process with a stripped environment, its own session, a hard timeout and core dumps disabled, inside a non-root container.
- **At-least-once safety.** The worker is idempotent across Pub/Sub redeliveries. Malformed messages are acknowledged, and repeated failures go to a dead-letter topic after 5 attempts.

## Limits and known gaps

- **Evidence size depends on worker memory.** `/tmp` on Cloud Run is in memory, so the worker (8 GiB) handles images up to about 6 GB; larger images fail with a clear message. Raise `--memory` (up to 32 GiB) for bigger images. The gateway's `MAX_UPLOAD_SIZE` is 6 GB to match.
- **Analysis time limit.** Each analysis must finish within 540 s, which keeps it inside Pub/Sub's 600 s push acknowledgement window.
- **No authentication on the public UI.** Put the frontend and gateway behind IAP or another login layer before uploading real case evidence.
- **Symbols are downloaded at runtime.** Volatility fetches Windows PDB symbols from Microsoft on demand. Build the worker with `--build-arg VOL_SYMBOLS=windows` to bake them in.
