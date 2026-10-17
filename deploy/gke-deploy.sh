#!/usr/bin/env bash
# End-to-end GKE deployment: cluster, images, secrets, manifests, external IP.
# Usage: PROJECT_ID=my-project [REGION=asia-southeast2] [ZONE=asia-southeast2-a] ./deploy/gke-deploy.sh
set -euo pipefail

: "${PROJECT_ID:?Set PROJECT_ID}"
REGION="${REGION:-asia-southeast2}"
ZONE="${ZONE:-${REGION}-a}"
CLUSTER="${CLUSTER:-forensics-cluster}"
REPO="${REPO:-forensics}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"
REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}"
cd "$(dirname "$0")/.."

echo "==> Project ${PROJECT_ID}, zone ${ZONE}, images ${REGISTRY}:${TAG}"
gcloud config set project "${PROJECT_ID}"
gcloud services enable container.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com

if ! gcloud artifacts repositories describe "${REPO}" --location "${REGION}" >/dev/null 2>&1; then
  gcloud artifacts repositories create "${REPO}" --repository-format=docker --location "${REGION}"
fi

if ! gcloud container clusters describe "${CLUSTER}" --zone "${ZONE}" >/dev/null 2>&1; then
  # Dataplane V2 enforces the NetworkPolicies in k8s/networkpolicy.yaml.
  gcloud container clusters create "${CLUSTER}" \
    --zone "${ZONE}" --num-nodes 2 --machine-type e2-standard-2 \
    --disk-type pd-balanced --disk-size 100 \
    --enable-dataplane-v2 --workload-pool "${PROJECT_ID}.svc.id.goog" \
    --shielded-secure-boot --shielded-integrity-monitoring \
    --release-channel regular
fi
gcloud container clusters get-credentials "${CLUSTER}" --zone "${ZONE}"

gcloud builds submit --config cloudbuild.yaml --substitutions "_REGISTRY=${REGISTRY},_TAG=${TAG}" .

# Render manifests with the pushed image URLs.
RENDERED="$(mktemp -d)"
for f in k8s/*.yaml; do
  sed -e "s#IMAGE_REGISTRY#${REGISTRY}#g" -e "s#IMAGE_TAG#${TAG}#g" "$f" > "${RENDERED}/$(basename "$f")"
done

kubectl apply -f "${RENDERED}/00-namespace.yaml"
# Secrets are generated in-cluster once and never written to the repo.
if ! kubectl -n forensics get secret triage-secrets >/dev/null 2>&1; then
  kubectl -n forensics create secret generic triage-secrets \
    --from-literal=worker-token="$(openssl rand -hex 32)" \
    --from-literal=redis-password="$(openssl rand -hex 32)"
fi
kubectl apply -f "${RENDERED}/"
kubectl -n forensics rollout status deploy --timeout=10m

echo "==> Waiting for the frontend LoadBalancer IP"
for _ in $(seq 60); do
  IP="$(kubectl -n forensics get svc frontend -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
  [[ -n "${IP}" ]] && break
  sleep 5
done
echo "Frontend: http://${IP:?LoadBalancer IP not assigned yet; run: kubectl -n forensics get svc frontend}"
