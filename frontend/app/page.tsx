"use client";

import { useEffect, useRef, useState } from "react";
import { AccessCodeModal } from "@/components/AccessCodeModal";
import { HowItWorksModal } from "@/components/HowItWorksModal";
import { ModeToggle, type Mode } from "@/components/ModeToggle";
import { ProgressBar } from "@/components/ProgressBar";
import { ResultsDashboard } from "@/components/ResultsDashboard";
import { UploadPanel } from "@/components/UploadPanel";
import { clearAccessCode, loadAccessCode, saveAccessCode } from "@/lib/accessCode";
import { AuthError, RateLimitError, fetchJob, simulateJob, uploadEvidence } from "@/lib/api";
import type { Job } from "@/lib/types";

type Phase =
  | { kind: "idle" }
  | { kind: "uploading"; fraction: number }
  | { kind: "job"; job: Job }
  | { kind: "error"; message: string; info?: boolean };

const POLL_MS = 1500;

function rateLimitMessage(e: RateLimitError) {
  const mins = e.retryAfterSeconds ? Math.ceil(e.retryAfterSeconds / 60) : null;
  return `Rate limit exceeded, try again later${mins ? ` (in about ${mins} minute${mins === 1 ? "" : "s"})` : ""}.`;
}

export default function Home() {
  const [mode, setMode] = useState<Mode>("simulation");
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const [accessCode, setAccessCode] = useState<string | null>(null);
  // undefined = not decided yet (avoids flashing the modal before sessionStorage is read)
  const [modal, setModal] = useState<{ notice?: string } | null | undefined>(undefined);
  const [showHow, setShowHow] = useState(false);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    const stored = loadAccessCode();
    setAccessCode(stored);
    setModal(stored ? null : {});
    return () => abort.current?.abort();
  }, []);

  const busy = phase.kind === "uploading" || (phase.kind === "job" && !["COMPLETED", "FAILED"].includes(phase.job.status));

  function reset() {
    abort.current?.abort();
    abort.current = null;
    setPhase({ kind: "idle" });
  }

  function requireCode(notice?: string) {
    clearAccessCode();
    setAccessCode(null);
    setModal({ notice });
  }

  async function run(work: (signal: AbortSignal) => Promise<void>) {
    abort.current?.abort();
    const ctrl = new AbortController();
    abort.current = ctrl;
    try {
      await work(ctrl.signal);
    } catch (e) {
      if ((e as Error).name === "AbortError") return;
      if (e instanceof AuthError) {
        setPhase({ kind: "idle" });
        requireCode("Your access code was rejected. Please enter it again.");
      } else if (e instanceof RateLimitError) {
        setPhase({ kind: "error", message: rateLimitMessage(e), info: true });
      } else {
        setPhase({ kind: "error", message: (e as Error).message });
      }
    }
  }

  const startSimulation = () =>
    run(async (signal) => {
      await simulateJob((job) => setPhase({ kind: "job", job }), signal);
    });

  const startLive = (file: File, plugin: string) => {
    if (!accessCode) return requireCode();
    return run(async (signal) => {
      setPhase({ kind: "uploading", fraction: 0 });
      let job = await uploadEvidence(file, plugin, accessCode, (fraction) => setPhase({ kind: "uploading", fraction }), signal);
      setPhase({ kind: "job", job });
      while (job.status !== "COMPLETED" && job.status !== "FAILED") {
        // Cloud Run scales the worker from zero, so the first poll may take a few seconds to move.
        await new Promise((r) => setTimeout(r, POLL_MS));
        if (signal.aborted) return;
        job = await fetchJob(job.id, accessCode, signal);
        setPhase({ kind: "job", job });
      }
    });
  };

  return (
    <main className="shell">
      {modal && (
        <AccessCodeModal
          notice={modal.notice}
          onAccepted={(code) => {
            saveAccessCode(code);
            setAccessCode(code);
            setModal(null);
          }}
          onSimulationOnly={() => {
            setModal(null);
            reset();
            setMode("simulation");
          }}
        />
      )}

      {showHow && <HowItWorksModal onClose={() => setShowHow(false)} />}

      <header className="topbar">
        <div>
          <h1>Memory Forensics Triage</h1>
          <p className="muted">Volatility 3 process analysis with automated anomaly triage</p>
        </div>
        <div className="topbar__right">
          <span className="access-chip">
            {accessCode ? "Access code active" : "Live Trial locked"}
            <button onClick={() => (accessCode ? requireCode() : setModal({}))}>{accessCode ? "Change" : "Unlock"}</button>
          </span>
          <button className="btn-link" onClick={() => setShowHow(true)}>
            How it works
          </button>
          <ModeToggle
            mode={mode}
            disabled={busy}
            onChange={(m) => {
              reset();
              setMode(m);
              if (m === "live" && !accessCode) setModal({});
            }}
          />
        </div>
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
            <UploadPanel disabled={busy || !accessCode} onSubmit={startLive} />
          </>
        )}

        {phase.kind === "uploading" && <ProgressBar phase="uploading" percent={phase.fraction * 100} />}
        {phase.kind === "job" && <ProgressBar phase={phase.job.status} percent={phase.job.progress} message={phase.job.message} />}
        {phase.kind === "error" && <div className={`alert${phase.info ? " alert--info" : ""}`}>{phase.message}</div>}
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
