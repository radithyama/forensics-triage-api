"use client";

import { useEffect, useRef, useState } from "react";
import { ModeToggle, type Mode } from "@/components/ModeToggle";
import { ProgressBar } from "@/components/ProgressBar";
import { ResultsDashboard } from "@/components/ResultsDashboard";
import { UploadPanel } from "@/components/UploadPanel";
import { fetchJob, simulateJob, uploadEvidence } from "@/lib/api";
import type { Job } from "@/lib/types";

type Phase = { kind: "idle" } | { kind: "uploading"; fraction: number } | { kind: "job"; job: Job } | { kind: "error"; message: string };

const POLL_MS = 1500;

export default function Home() {
  const [mode, setMode] = useState<Mode>("simulation");
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const abort = useRef<AbortController | null>(null);

  useEffect(() => () => abort.current?.abort(), []);

  const busy = phase.kind === "uploading" || (phase.kind === "job" && !["COMPLETED", "FAILED"].includes(phase.job.status));

  function reset() {
    abort.current?.abort();
    abort.current = null;
    setPhase({ kind: "idle" });
  }

  async function run(work: (signal: AbortSignal) => Promise<void>) {
    abort.current?.abort();
    const ctrl = new AbortController();
    abort.current = ctrl;
    try {
      await work(ctrl.signal);
    } catch (e) {
      if ((e as Error).name !== "AbortError") setPhase({ kind: "error", message: (e as Error).message });
    }
  }

  const startSimulation = () =>
    run(async (signal) => {
      await simulateJob((job) => setPhase({ kind: "job", job }), signal);
    });

  const startLive = (file: File, plugin: string) =>
    run(async (signal) => {
      setPhase({ kind: "uploading", fraction: 0 });
      let job = await uploadEvidence(file, plugin, (fraction) => setPhase({ kind: "uploading", fraction }), signal);
      setPhase({ kind: "job", job });
      while (job.status !== "COMPLETED" && job.status !== "FAILED") {
        await new Promise((r) => setTimeout(r, POLL_MS));
        if (signal.aborted) return;
        job = await fetchJob(job.id, signal);
        setPhase({ kind: "job", job });
      }
    });

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <h1>Memory Forensics Triage</h1>
          <p className="muted">Volatility 3 process analysis with automated anomaly triage</p>
        </div>
        <ModeToggle
          mode={mode}
          disabled={busy}
          onChange={(m) => {
            reset();
            setMode(m);
          }}
        />
      </header>

      <section className="card intake">
        {mode === "simulation" ? (
          <div className="sim">
            <div>
              <h2>Simulation Mode</h2>
              <p className="muted">
                Replays a pre-recorded <span className="mono">windows.pslist.PsList</span> triage of a 4 GB Windows 10
                workstation image. No upload, no backend required.
              </p>
            </div>
            <button className="btn btn--primary" onClick={startSimulation} disabled={busy}>
              Run simulated analysis
            </button>
          </div>
        ) : (
          <>
            <h2>Live Trial</h2>
            <UploadPanel disabled={busy} onSubmit={startLive} />
          </>
        )}

        {phase.kind === "uploading" && <ProgressBar phase="uploading" percent={phase.fraction * 100} />}
        {phase.kind === "job" && <ProgressBar phase={phase.job.status} percent={phase.job.progress} message={phase.job.message} />}
        {phase.kind === "error" && <div className="alert">{phase.message}</div>}
        {(phase.kind === "error" || (phase.kind === "job" && !busy)) && (
          <button className="btn btn--ghost" onClick={reset}>
            New analysis
          </button>
        )}
      </section>

      {phase.kind === "job" && phase.job.status === "COMPLETED" && phase.job.result && <ResultsDashboard job={phase.job} />}
    </main>
  );
}
