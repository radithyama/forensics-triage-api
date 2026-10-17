import type { CreateJobResponse, Job } from "./types";
import mockJob from "./mock-job.json";

declare global {
  interface Window {
    __TRIAGE_CONFIG__?: { apiBase?: string };
  }
}

/** Gateway origin: runtime config from nginx in production, NEXT_PUBLIC_API_BASE for `next dev`. */
function apiBase(): string {
  const runtime = typeof window !== "undefined" ? window.__TRIAGE_CONFIG__?.apiBase : undefined;
  return (runtime || process.env.NEXT_PUBLIC_API_BASE || "").replace(/\/$/, "");
}

/** The access code was missing, wrong or revoked. The UI should ask for it again. */
export class AuthError extends Error {}

/** Too many requests from this IP. */
export class RateLimitError extends Error {
  constructor(public readonly retryAfterSeconds: number | null) {
    super("Rate limit exceeded, try again later");
  }
}

function errorMessage(body: string, status: number): string {
  try {
    const parsed = JSON.parse(body);
    const field = parsed.errors?.[0];
    if (field?.field && field?.defaultMessage) return `${field.field} ${field.defaultMessage}`;
    return parsed.message || parsed.error || `HTTP ${status}`;
  } catch {
    return body || `HTTP ${status}`;
  }
}

async function api<T>(path: string, accessCode: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${apiBase()}${path}`, {
    ...init,
    cache: "no-store",
    headers: { ...(init.headers as Record<string, string>), "X-API-Key": accessCode },
  });
  const text = await res.text();
  if (res.status === 401) throw new AuthError("Access code rejected");
  if (res.status === 429) {
    const retry = Number(res.headers.get("Retry-After"));
    throw new RateLimitError(Number.isFinite(retry) && retry > 0 ? retry : null);
  }
  if (!res.ok) throw new Error(errorMessage(text, res.status));
  return text ? JSON.parse(text) : (undefined as T);
}

/** Validates an access code against the gateway without spending a rate-limit token (unless it's wrong). */
export async function verifyAccessCode(code: string): Promise<void> {
  await api<void>("/api/session", code);
}

export const MIN_UPLOAD_BYTES = 10 * 1024 * 1024;
export const MAX_UPLOAD_BYTES = 6 * 1024 * 1024 * 1024;

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
    // Content-Type and x-goog-content-length-range are part of the signature and must be sent verbatim.
    Object.entries(uploadHeaders).forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) return resolve();
      const reason =
        xhr.status === 400
          ? "the file must be between 10 MB and 6 GB"
          : xhr.status === 403
            ? "the upload link expired (links are valid for 5 minutes)"
            : `HTTP ${xhr.status}`;
      reject(new Error(`Cloud Storage rejected the upload: ${reason}`));
    };
    xhr.onerror = () => reject(new Error("Network error while uploading to Cloud Storage"));
    xhr.onabort = () => reject(new DOMException("Upload cancelled", "AbortError"));
    signal?.addEventListener("abort", () => xhr.abort());
    xhr.send(file);
  });
}

/**
 * Three-step intake: register the job (gateway returns a 5-minute signed URL), upload the evidence
 * directly to Cloud Storage, then ask the gateway to queue it. Large images never pass through Cloud Run.
 */
export async function uploadEvidence(
  file: File,
  plugin: string,
  accessCode: string,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<Job> {
  if (file.size < MIN_UPLOAD_BYTES || file.size > MAX_UPLOAD_BYTES) {
    throw new Error("Memory images must be between 10 MB and 6 GB");
  }
  const created = await api<CreateJobResponse>("/api/jobs", accessCode, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ filename: file.name, sizeBytes: file.size, plugin }),
    signal,
  });
  await putToSignedUrl(created, file, onProgress, signal);
  return api<Job>(`/api/jobs/${encodeURIComponent(created.job.id)}/start`, accessCode, { method: "POST", signal });
}

export function fetchJob(id: string, accessCode: string, signal?: AbortSignal): Promise<Job> {
  return api<Job>(`/api/jobs/${encodeURIComponent(id)}`, accessCode, { signal });
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
