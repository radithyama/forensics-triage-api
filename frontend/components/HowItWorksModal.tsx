"use client";

import { useEffect, useRef, type ReactNode } from "react";

const icon = (path: ReactNode) => (
  <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="1.6"
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {path}
  </svg>
);

const STEPS: { title: string; tech: string; body: string; icon: ReactNode }[] = [
  {
    title: "Browser upload",
    tech: "Next.js · signed URL",
    body: "Your browser asks for a one-time, 5-minute upload link and sends the memory image straight to storage. It never passes through our servers.",
    icon: icon(<><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 8h18M12 17v-6M9.5 13.5 12 11l2.5 2.5" /></>),
  },
  {
    title: "Cloud Storage",
    tech: "Google Cloud Storage",
    body: "The file lands in a private bucket. Its fingerprint (hash) is recorded, and anything left behind is deleted automatically after a day.",
    icon: icon(<><ellipse cx="12" cy="6" rx="7" ry="2.5" /><path d="M5 6v12c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5V6M5 12c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5" /></>),
  },
  {
    title: "Pub/Sub event",
    tech: "Google Cloud Pub/Sub",
    body: "A small “new evidence is ready” message is published. It's the doorbell that wakes up the analysis service.",
    icon: icon(<><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0" /></>),
  },
  {
    title: "Cloud Run worker",
    tech: "Volatility 3",
    body: "A worker starts on demand, checks the fingerprint, runs Volatility 3 to list processes, flags anything suspicious, then deletes the evidence.",
    icon: icon(<><rect x="4" y="4" width="16" height="16" rx="2" /><rect x="9" y="9" width="6" height="6" rx="1" /><path d="M9 2v2M15 2v2M9 20v2M15 20v2M2 9h2M2 15h2M20 9h2M20 15h2" /></>),
  },
];

export function HowItWorksModal({ onClose }: { onClose: () => void }) {
  const closeBtn = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    closeBtn.current?.focus();
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      previouslyFocused?.focus();
    };
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal modal--wide card" role="dialog" aria-modal="true" aria-labelledby="how-title">
        <div className="modal__header">
          <div>
            <span className="kicker">Architecture</span>
            <h2 id="how-title">How it works</h2>
          </div>
          <button ref={closeBtn} className="modal__close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <ol className="flow" aria-label="Analysis pipeline">
          {STEPS.map((s, i) => (
            <li key={s.title} className="flow__step">
              <div className="flow__icon">{s.icon}</div>
              <span className="flow__num">Step {i + 1}</span>
              <strong>{s.title}</strong>
              <span className="flow__tech mono">{s.tech}</span>
              <p>{s.body}</p>
            </li>
          ))}
        </ol>

        <section className="zero">
          <h3>Why it costs nothing when nobody is using it</h3>
          <p>
            Nothing here runs all the time. Each piece is a managed service that switches on when there&apos;s work
            and switches off again when there isn&apos;t. That&apos;s called <strong>scaling to zero</strong>.
          </p>
          <ul className="zero__points">
            <li>
              <span className="zero__state zero__state--idle">Idle</span>
              No servers are running, so there&apos;s essentially no bill: just a few cents for storage.
            </li>
            <li>
              <span className="zero__state zero__state--busy">Busy</span>
              Upload a file and a worker spins up within seconds. Several uploads get several workers, up to a safe limit.
            </li>
            <li>
              <span className="zero__state">After</span>
              When the analysis finishes, the worker shuts down and the evidence is deleted.
            </li>
          </ul>
        </section>
      </div>
    </div>
  );
}
