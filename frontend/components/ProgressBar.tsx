"use client";

import type { JobStatus } from "@/lib/types";

interface Props {
  phase: "uploading" | JobStatus;
  percent: number;
  message?: string;
}

const LABEL: Record<Props["phase"], string> = {
  uploading: "Uploading evidence to Cloud Storage",
  AWAITING_UPLOAD: "Waiting for upload",
  QUEUED: "Queued",
  RUNNING: "Analysing",
  COMPLETED: "Complete",
  FAILED: "Failed",
};

export function ProgressBar({ phase, percent, message }: Props) {
  const pct = Math.max(0, Math.min(100, Math.round(percent)));
  return (
    <div className={`progress progress--${phase.toLowerCase()}`}>
      <div className="progress__head">
        <span className="progress__label">{LABEL[phase]}</span>
        <span className="progress__pct">{pct}%</span>
      </div>
      <div className="progress__track" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
        <div className="progress__fill" style={{ width: `${pct}%` }} />
      </div>
      {message && <p className="progress__msg">{message}</p>}
    </div>
  );
}
