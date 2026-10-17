import type { CreateJobResponse, Job } from "./types";
import mockJob from "./mock-job.json";

/** Empty in production (same-origin via nginx); set NEXT_PUBLIC_API_BASE=http://localhost:8080 for `next dev`. */
const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "";

function errorMessage(body: string, status: number): string {
  try {
    const parsed = JSON.parse(body);
    return parsed.message || parsed.error || `HTTP ${status}`;
  } catch {
    return body || `HTTP ${status}`;
  }
}

async function postJson<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(errorMessage(text, res.status));
  return JSON.parse(text);
}

/** PUT straight to the GCS signed URL. XHR (not fetch) because fetch cannot report upload progress. */
function putToSignedUrl(
  { uploadUrl, uploadMethod, uploadHeaders }: CreateJobResponse,
  file: File,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(uploadMethod, uploadUrl);
    Object.entries(uploadHeaders).forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () =>
      xhr.status >= 200 && xhr.status < 300
        ? resolve()
        : reject(new Error(`Upload to Cloud Storage failed (HTTP ${xhr.status})`));
    xhr.onerror = () => reject(new Error("Network error while uploading to Cloud Storage"));
    xhr.onabort = () => reject(new DOMException("Upload cancelled", "AbortError"));
    signal?.addEventListener("abort", () => xhr.abort());
    xhr.send(file);
  });
}

/**
 * Three-step intake: register the job (gateway returns a signed URL), upload the evidence directly to
 * Cloud Storage, then ask the gateway to queue it. Large images never pass through Cloud Run.
 */
export async function uploadEvidence(
  file: File,
  plugin: string,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<Job> {
  const created = await postJson<CreateJobResponse>(
    "/api/jobs",
    { filename: file.name, sizeBytes: file.size, plugin },
    signal,
  );
  await putToSignedUrl(created, file, onProgress, signal);
  return postJson<Job>(`/api/jobs/${encodeURIComponent(created.job.id)}/start`, undefined, signal);
}

export async function fetchJob(id: string, signal?: AbortSignal): Promise<Job> {
  const res = await fetch(`${API_BASE}/api/jobs/${encodeURIComponent(id)}`, { signal, cache: "no-store" });
  if (!res.ok) throw new Error(await errorMessage(await res.text(), res.status));
  return res.json();
}

const SIM_STAGES: [number, string][] = [
  [5, "Fetching evidence"],
  [15, "Evidence verified (sha256 9f2c4d7a1be0…), running windows.pslist.PsList"],
  [28, "Scanning memory_layer for kernel"],
  [46, "PDB scanning finished"],
  [63, "Reading Symbol layer"],
  [81, "Walking ActiveProcessLinks"],
  [92, "Building process tree"],
];

/** Plays back a canned analysis so the dashboard can be demoed without any backend. */
export function simulateJob(onUpdate: (job: Job) => void, signal: AbortSignal): Promise<Job> {
  const base = mockJob as Job;
  const job = (progress: number, message: string, status: Job["status"]): Job => ({
    ...base,
    status,
    progress,
    message,
    result: undefined,
  });
  return new Promise((resolve, reject) => {
    let i = 0;
    onUpdate(job(0, "Queued for analysis", "QUEUED"));
    const timer = setInterval(() => {
      if (i < SIM_STAGES.length) {
        const [p, m] = SIM_STAGES[i++];
        onUpdate(job(p, m, "RUNNING"));
        return;
      }
      clearInterval(timer);
      onUpdate(base);
      resolve(base);
    }, 650);
    signal.addEventListener("abort", () => {
      clearInterval(timer);
      reject(new DOMException("Simulation cancelled", "AbortError"));
    });
  });
}
