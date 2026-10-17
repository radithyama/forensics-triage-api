# Automated Memory Forensics Triage API

Upload a memory image, run a Volatility 3 plugin against it in an isolated worker, and get back an
interactive process tree with automated anomaly triage (masquerading names, unexpected parents,
duplicate singletons such as a second `lsass.exe`, Office → LOLBin chains, orphans).

Fully serverless on Google Cloud: three Cloud Run services, one GCS bucket and Pub/Sub. Every
component scales to zero, so an idle deployment costs close to nothing.

```
   static UI ◄── frontend (Cloud Run, nginx)
 browser ──① POST /api/jobs  (X-API-Key) ────► api-gateway (Cloud Run, public; CORS, key, rate limit)
    │      ③ POST /api/jobs/<id>/start ─────►│  signed URL (5 min, 10 MB–6 GB), writes jobs/<id>.json
    │                                           │  pins object generation + MD5, publishes
    │                                           ▼
    └─② PUT evidence ──► GCS bucket ◄──── Pub/Sub topic ──push (OIDC)──► forensic-worker (Cloud Run, private)
                         evidence/<id>/…                                  │ downloads to /tmp, verifies MD5,
                         jobs/<id>.json ◄─────────────────────────────────┘ runs Volatility, writes result,
                         (1-day lifecycle)                                    deletes evidence
```

| Service | Stack | Role |
|---|---|---|
| `frontend/` | Next.js 16 static export, nginx | Upload UI, progress, process tree, findings. **Simulation Mode** replays bundled data; **Live Trial** needs the access code and uploads for real. nginx serves a runtime `/config.js` with the gateway URL. |
| `api-gateway/` | Java 21, Spring Boot 4 WebFlux, GCS + Pub/Sub clients | Job registration, signed upload URLs, start/queue, status reads. |
| `forensic-worker/` | Python 3.12, Flask + gunicorn, Volatility 3 | Pub/Sub push endpoint; one analysis per instance. |

## API

Every `/api/**` call needs the access code in `X-API-Key` (get it with
`gcloud secrets versions access latest --secret=triage-api-key`).

```bash
H="X-API-Key: $ACCESS_CODE"
# 1. Register the job; get a 5-minute signed URL plus the exact headers it was signed with
curl -X POST $GATEWAY/api/jobs -H "$H" -H 'content-type: application/json' \
     -d '{"filename":"WIN10.raw","sizeBytes":4294967296,"plugin":"windows.pslist.PsList"}'
#    optional: "md5":"<base64 MD5>" binds Content-MD5 into the signature
# 2. Upload straight to Cloud Storage, sending every header from uploadHeaders verbatim
curl -X PUT -H 'Content-Type: application/octet-stream' \
     -H 'x-goog-content-length-range: 10485760,6442450944' --data-binary @WIN10.raw "$UPLOAD_URL"
# 3. Queue it, then poll
curl -X POST $GATEWAY/api/jobs/<id>/start -H "$H"
curl $GATEWAY/api/jobs/<id> -H "$H"
```

`GET /api/session` returns 204 for a valid code (the UI uses it to check codes).

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
prevention), the access-code secret, the Pub/Sub topic, dead-letter topic and OIDC push subscription,
and one service account per workload with resource-scoped roles. It builds the images with Cloud
Build, deploys the three services and sets CORS on both the gateway and the bucket for the frontend
origins. `cloudbuild.web.yaml` rebuilds just the frontend and gateway.

## Develop

Tests: `mvn test` (gateway), `pytest` (worker), `npm run lint && npm run build` (frontend).
`NEXT_PUBLIC_API_BASE=http://localhost:8080 npm run dev` runs the UI against a local gateway started
with `API_KEY=<any 24+ chars> ALLOWED_ORIGINS=http://localhost:3000`.
Simulation Mode needs no backend at all.

## Abuse and Denial-of-Wallet controls

All of these run inside the existing services, with no load balancer or other fixed-cost infrastructure.

| Control | Where | Behaviour |
|---|---|---|
| Access code | Gateway `ApiKeyFilter` | `X-API-Key` required on all `/api/**`. Loaded once from Secret Manager at startup (fails closed if missing or weak), compared in constant time. Missing or wrong → **401**. |
| Rate limit | Gateway `RateLimitFilter` | Token bucket, **10 per hour per client IP** on `POST /api/jobs` and `/start`. **Wrong access codes spend the same tokens**, so brute force is capped too. Over the limit → **429** with `Retry-After`. Status polling isn't limited. |
| Client IP | `ClientIp` | Last `X-Forwarded-For` hop, which Google's front end appends. Spoofed earlier hops are ignored (verified live). |
| Instance cap | Cloud Run | Gateway `max-instances=1` keeps the in-memory limiter global. Worker `max-instances=3` and concurrency 1 bound worst-case spend. |
| Strict CORS | Gateway | Only the two frontend origins, only GET/POST with `Content-Type` and `X-API-Key`. Any other Origin → **403**. |
| Upload policy | V4 signed URL | Signed `x-goog-content-length-range: 10 MB–6 GB` and `Content-Type: application/octet-stream`; optional `Content-MD5`; **5-minute** expiry (longer TTLs are refused at startup). GCS itself rejects violations (`EntityTooSmall` / `EntityTooLarge` / 403). |
| Input validation | `CreateJobRequest` | Filenames must be plain names with a memory-image extension (traversal, separators, hidden files and odd characters are rejected, not sanitized). Plugin shape, base64 MD5 and size range are checked; request bodies are capped at 16 KB. |
| Error hygiene | `SafeErrorAttributes` | 5xx responses never echo library exception text (bucket names, URLs). |
| CSP | Frontend nginx | `connect-src` allows only the gateway origin and `storage.googleapis.com`. |

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
- **The access code is shared, not per-user.** Rotate it by adding a new secret version and redeploying the gateway (it reads the key at startup). For real case evidence, use per-user identity (for example IAP) instead.
- **Rate-limit state is in memory.** A gateway restart or new revision resets the buckets. That is acceptable for spam control, but it isn't an audit log.
- **Symbols are downloaded at runtime.** Volatility fetches Windows PDB symbols from Microsoft on demand. Build the worker with `--build-arg VOL_SYMBOLS=windows` to bake them in.
