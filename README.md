# Automated Memory Forensics Triage API

Upload a memory image, run a Volatility 3 plugin against it in an isolated worker, and get back an
interactive process tree with automated anomaly triage (masquerading names, unexpected parents,
duplicate singletons such as a second `lsass.exe`, Office → LOLBin chains, orphans).

```
 browser ──► frontend (nginx + Next.js static) ──/api──► api-gateway (Spring Boot WebFlux)
                                                            │  streams evidence to disk, SHA-256
                                                            │  job state + queue in Redis
                                                            ▼
                                     redis ◄──BLMOVE── forensic-worker (Python + Volatility 3)
                                                            │  GET /internal/jobs/{id}/evidence
                                                            └─ PUT /internal/jobs/{id}/status
```

| Service | Stack | Role |
|---|---|---|
| `frontend/` | Next.js 16 (static export), nginx | Upload UI, progress, process tree, findings. **Simulation Mode** replays bundled data; **Live Trial** uploads for real. |
| `api-gateway/` | Java 21, Spring Boot 4 WebFlux, Redis reactive | `POST /api/jobs`, `GET /api/jobs/{id}`; token-protected `/internal/**` for the worker. |
| `forensic-worker/` | Python 3.12, Volatility 3 | Consumes the queue, verifies the evidence hash, runs the plugin in a sandboxed subprocess, builds the tree and findings. |

## API

```bash
curl -F file=@WIN10.raw -F plugin=windows.pslist.PsList http://HOST/api/jobs   # 202 + job (id, sha256, status)
curl http://HOST/api/jobs/<id>                                                # status, progress, result
```

Statuses: `QUEUED → RUNNING (progress 0–100) → COMPLETED | FAILED`. Allowed plugins: `windows.pslist.PsList`,
`windows.pstree.PsTree`, `windows.cmdline.CmdLine`, `windows.netscan.NetScan`, `windows.malfind.Malfind`, `linux.pslist.PsList`.

## Run locally

```bash
cp .env.example .env        # then set real random values
docker compose up --build   # http://localhost:3000
```

Without Docker: run Redis, then `mvn spring-boot:run` in `api-gateway/`, `python -m worker.main` in
`forensic-worker/`, and `NEXT_PUBLIC_API_BASE=http://localhost:8080 npm run dev` in `frontend/`.

Tests: `mvn test` (gateway), `pytest` (worker), `npm run lint && npm run build` (frontend).

## Deploy to GKE

```bash
PROJECT_ID=my-project ./deploy/gke-deploy.sh
```

This enables the APIs, creates an Artifact Registry repo and a 2-node zonal `forensics-cluster`
(Dataplane V2), builds all images with Cloud Build (`cloudbuild.yaml`), generates in-cluster secrets,
renders `k8s/` with the image URLs, applies everything and prints the frontend's external IP.

## Security design

- **Chain of custody**: the gateway hashes evidence on intake; the worker re-hashes after download and refuses to analyse on mismatch. The hash is shown in the UI.
- **Evidence hygiene**: on-disk names are server-generated UUIDs (no path traversal); evidence is deleted from both gateway and worker as soon as a job finishes; core dumps are disabled for the analysis process.
- **Isolation**: Volatility runs as a child process with a stripped environment, its own session and a hard timeout. Containers run non-root with read-only root filesystems, all capabilities dropped and `RuntimeDefault` seccomp. The namespace enforces the *restricted* Pod Security Standard.
- **Network**: default-deny NetworkPolicies; Redis is reachable only from the gateway and workers; `/internal/**` requires a shared worker token (constant-time compare) and is never proxied by nginx.
- **Input control**: a plugin allow-list is enforced in both gateway and worker.

## Known limitations

- The gateway keeps evidence on its pod's `emptyDir`, so it runs as **one replica**. To scale it out, store evidence in a GCS bucket (signed URLs for the worker) instead of local disk.
- The public frontend has **no authentication**. Put it behind Identity-Aware Proxy or an auth layer before uploading real case evidence.
- Volatility downloads Windows PDB symbols from Microsoft on demand. Build the worker with `--build-arg VOL_SYMBOLS=windows` to bake the symbol pack in for offline analysis.
