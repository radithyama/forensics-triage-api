export type JobStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";

export interface ProcessNode {
  pid: number;
  ppid: number;
  name: string;
  offset: number | null;
  threads: number | null;
  handles: number | null;
  sessionId: number | null;
  wow64: boolean | null;
  createTime: string | null;
  exitTime: string | null;
  children: ProcessNode[];
}

export interface Finding {
  severity: "high" | "medium" | "low";
  rule: string;
  pid: number;
  process: string;
  detail: string;
}

export interface TriageResult {
  plugin: string;
  summary: {
    processCount?: number;
    rootCount?: number;
    findingCount?: number;
    highSeverity?: number;
    rowCount?: number;
  };
  findings?: Finding[];
  processTree?: ProcessNode[];
  rows?: Record<string, unknown>[];
  evidenceSha256?: string;
  durationSeconds?: number;
}

export interface Job {
  id: string;
  filename: string;
  sizeBytes: number;
  sha256: string;
  plugin: string;
  status: JobStatus;
  progress: number;
  message?: string;
  createdAt: string;
  updatedAt: string;
  result?: TriageResult;
}

export const PLUGINS = [
  "windows.pslist.PsList",
  "windows.pstree.PsTree",
  "windows.cmdline.CmdLine",
  "windows.netscan.NetScan",
  "windows.malfind.Malfind",
  "linux.pslist.PsList",
] as const;
