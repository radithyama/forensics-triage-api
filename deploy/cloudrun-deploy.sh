#!/usr/bin/env bash
# Serverless deployment: GCS + Pub/Sub + three Cloud Run services, all scale-to-zero, no key files.
# Idempotent: safe to re-run for new releases.
# Usage: PROJECT_ID=my-project [REGION=asia-southeast2] ./deploy/cloudrun-deploy.sh
set -euo pipefail

: "${PROJECT_ID:?Set PROJECT_ID}"
REGION="${REGION:-asia-southeast2}"
REPO="${REPO:-forensics}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"
BUCKET="${BUCKET:-${PROJECT_ID}-forensics-evidence}"
TOPIC=forensic-jobs
DLQ=forensic-jobs-dead-letter
SUB=forensic-jobs-push
REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}"
cd "$(dirname "$0")/.."

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
sa() { echo "$1@${PROJECT_ID}.iam.gserviceaccount.com"; }
BUILD_SA="$(sa forensics-builder)"; GW_SA="$(sa triage-gateway)"; WK_SA="$(sa triage-worker)"
FE_SA="$(sa triage-frontend)"; PUSH_SA="$(sa triage-pubsub-push)"
PUBSUB_AGENT="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"
quiet() { "$@" --format=none >/dev/null; }

echo "==> ${PROJECT_ID} / ${REGION}, images ${REGISTRY}:${TAG}"
gcloud config set project "${PROJECT_ID}" >/dev/null
gcloud services enable run.googleapis.com pubsub.googleapis.com storage.googleapis.com \
  iamcredentials.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com

# ---------- Service accounts (one per workload; none have keys) ----------
for name in forensics-builder triage-gateway triage-worker triage-frontend triage-pubsub-push; do
  gcloud iam service-accounts describe "$(sa "$name")" >/dev/null 2>&1 || gcloud iam service-accounts create "$name"
done
sleep 5

# ---------- Storage: private bucket, no soft-delete, objects expire after 1 day ----------
if ! gcloud storage buckets describe "gs://${BUCKET}" >/dev/null 2>&1; then
  gcloud storage buckets create "gs://${BUCKET}" --location="${REGION}" \
    --uniform-bucket-level-access --public-access-prevention --soft-delete-duration=0
fi
TMP="$(mktemp -d)"
echo '{"rule":[{"action":{"type":"Delete"},"condition":{"age":1}}]}' > "${TMP}/lifecycle.json"
gcloud storage buckets update "gs://${BUCKET}" --lifecycle-file="${TMP}/lifecycle.json"

# ---------- Artifact Registry + Cloud Build (dedicated least-privilege build SA) ----------
gcloud artifacts repositories describe "${REPO}" --location="${REGION}" >/dev/null 2>&1 ||
  gcloud artifacts repositories create "${REPO}" --repository-format=docker --location="${REGION}"
quiet gcloud projects add-iam-policy-binding "${PROJECT_ID}" --member="serviceAccount:${BUILD_SA}" --role=roles/logging.logWriter --condition=None
gcloud storage buckets create "gs://${PROJECT_ID}_cloudbuild" --location=US 2>/dev/null || true
quiet gcloud storage buckets add-iam-policy-binding "gs://${PROJECT_ID}_cloudbuild" --member="serviceAccount:${BUILD_SA}" --role=roles/storage.objectViewer
quiet gcloud artifacts repositories add-iam-policy-binding "${REPO}" --location="${REGION}" --member="serviceAccount:${BUILD_SA}" --role=roles/artifactregistry.writer
gcloud builds submit --config cloudbuild.yaml --service-account="projects/${PROJECT_ID}/serviceAccounts/${BUILD_SA}" \
  --substitutions "_REGISTRY=${REGISTRY},_TAG=${TAG}" .

# ---------- Pub/Sub topics ----------
gcloud pubsub topics describe "${TOPIC}" >/dev/null 2>&1 || gcloud pubsub topics create "${TOPIC}"
gcloud pubsub topics describe "${DLQ}" >/dev/null 2>&1 || gcloud pubsub topics create "${DLQ}"

# ---------- IAM: least privilege, scoped to individual resources ----------
quiet gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" --member="serviceAccount:${GW_SA}" --role=roles/storage.objectUser
quiet gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" --member="serviceAccount:${WK_SA}" --role=roles/storage.objectUser
quiet gcloud pubsub topics add-iam-policy-binding "${TOPIC}" --member="serviceAccount:${GW_SA}" --role=roles/pubsub.publisher
# V4 signed URLs without a key file: the gateway signs via IAM signBlob on its own identity.
quiet gcloud iam service-accounts add-iam-policy-binding "${GW_SA}" --member="serviceAccount:${GW_SA}" --role=roles/iam.serviceAccountTokenCreator
quiet gcloud pubsub topics add-iam-policy-binding "${DLQ}" --member="serviceAccount:${PUBSUB_AGENT}" --role=roles/pubsub.publisher

# ---------- Cloud Run: worker (private), gateway + frontend (public) ----------
gcloud run deploy forensic-worker --image="${REGISTRY}/forensic-worker:${TAG}" --region="${REGION}" \
  --no-allow-unauthenticated --service-account="${WK_SA}" \
  --execution-environment=gen2 --cpu=2 --memory=8Gi --concurrency=1 --timeout=600 \
  --min-instances=0 --max-instances=3 \
  --set-env-vars="EVIDENCE_BUCKET=${BUCKET},VOL_TIMEOUT_SECONDS=540" --quiet
WORKER_URL="$(gcloud run services describe forensic-worker --region="${REGION}" --format='value(status.url)')"
quiet gcloud run services add-iam-policy-binding forensic-worker --region="${REGION}" --member="serviceAccount:${PUSH_SA}" --role=roles/run.invoker

FRONTEND_URL="https://frontend-${PROJECT_NUMBER}.${REGION}.run.app"
gcloud run deploy api-gateway --image="${REGISTRY}/api-gateway:${TAG}" --region="${REGION}" \
  --allow-unauthenticated --service-account="${GW_SA}" \
  --cpu=1 --memory=1Gi --cpu-boost --concurrency=80 --timeout=60 --min-instances=0 --max-instances=3 \
  --set-env-vars="GOOGLE_CLOUD_PROJECT=${PROJECT_ID},EVIDENCE_BUCKET=${BUCKET},JOBS_TOPIC=${TOPIC},MAX_UPLOAD_SIZE=6GB,ALLOWED_ORIGINS=${FRONTEND_URL}" --quiet
GATEWAY_HOST="$(gcloud run services describe api-gateway --region="${REGION}" --format='value(status.url)' | sed 's#https://##')"

gcloud run deploy frontend --image="${REGISTRY}/frontend:${TAG}" --region="${REGION}" \
  --allow-unauthenticated --service-account="${FE_SA}" --port=8080 \
  --cpu=1 --memory=256Mi --concurrency=200 --min-instances=0 --max-instances=3 \
  --set-env-vars="API_UPSTREAM_URL=https://${GATEWAY_HOST},API_UPSTREAM_HOST=${GATEWAY_HOST}" --quiet

# ---------- Push subscription: OIDC-authenticated, ack window covers one full analysis ----------
if ! gcloud pubsub subscriptions describe "${SUB}" >/dev/null 2>&1; then
  gcloud pubsub subscriptions create "${SUB}" --topic="${TOPIC}" \
    --push-endpoint="${WORKER_URL}/" --push-auth-service-account="${PUSH_SA}" --push-auth-token-audience="${WORKER_URL}" \
    --ack-deadline=600 --min-retry-delay=15s --max-retry-delay=600s \
    --dead-letter-topic="${DLQ}" --max-delivery-attempts=5 --expiration-period=never --message-retention-duration=1d
  gcloud pubsub subscriptions create "${DLQ}-sub" --topic="${DLQ}" --message-retention-duration=7d --expiration-period=never
fi
quiet gcloud pubsub subscriptions add-iam-policy-binding "${SUB}" --member="serviceAccount:${PUBSUB_AGENT}" --role=roles/pubsub.subscriber

# ---------- Browser uploads go straight to GCS: allow PUT from the frontend's origins ----------
LEGACY_URL="$(gcloud run services describe frontend --region="${REGION}" --format='value(status.url)')"
echo "[{\"origin\":[\"${FRONTEND_URL}\",\"${LEGACY_URL}\"],\"method\":[\"PUT\"],\"responseHeader\":[\"Content-Type\"],\"maxAgeSeconds\":3600}]" > "${TMP}/cors.json"
gcloud storage buckets update "gs://${BUCKET}" --cors-file="${TMP}/cors.json"

echo
echo "Frontend: ${FRONTEND_URL}"
