import type { Job } from "./types";
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

/** Uses XHR rather than fetch because fetch cannot report upload progress. */
export function uploadEvidence(
  file: File,
  plugin: string,
  onProgress: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<Job> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const form = new FormData();
    form.append("plugin", plugin);
    form.append("file", file);

    xhr.open("POST", `${API_BASE}/api/jobs`);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve(JSON.parse(xhr.responseText));
      else reject(new Error(errorMessage(xhr.responseText, xhr.status)));
    };
    xhr.onerror = () => reject(new Error("Network error while uploading"));
    xhr.onabort = () => reject(new DOMException("Upload cancelled", "AbortError"));
    signal?.addEventListener("abort", () => xhr.abort());
    xhr.send(form);
  });
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
