"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { AuthError, RateLimitError, verifyAccessCode } from "@/lib/api";

interface Props {
  /** Shown above the field, e.g. why we're asking again. */
  notice?: string;
  onAccepted: (code: string) => void;
  onSimulationOnly: () => void;
}

export function AccessCodeModal({ notice, onAccepted, onSimulationOnly }: Props) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => input.current?.focus(), []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const value = code.trim();
    if (!value) return;
    setChecking(true);
    setError(null);
    try {
      await verifyAccessCode(value);
      onAccepted(value);
    } catch (err) {
      setError(
        err instanceof AuthError
          ? "That access code isn't valid."
          : err instanceof RateLimitError
            ? "Rate limit exceeded, try again later."
            : "Couldn't reach the API. Please try again.",
      );
    } finally {
      setChecking(false);
    }
  }

  return (
    <div className="modal-backdrop">
      <form className="modal card" role="dialog" aria-modal="true" aria-labelledby="access-title" onSubmit={submit}>
        <h2 id="access-title">Recruiter Access Code</h2>
        <p className="muted">
          Live analysis runs real infrastructure, so it&apos;s gated behind an access code. Simulation Mode is open
          to everyone.
        </p>
        {notice && <p className="modal__notice">{notice}</p>}
        <input
          ref={input}
          className="modal__input mono"
          type="password"
          autoComplete="off"
          spellCheck={false}
          placeholder="Enter access code"
          aria-label="Access code"
          aria-invalid={!!error}
          value={code}
          onChange={(e) => setCode(e.target.value)}
          disabled={checking}
        />
        {error && <p className="modal__error" role="alert">{error}</p>}
        <div className="modal__actions">
          <button type="button" className="btn btn--ghost" onClick={onSimulationOnly} disabled={checking}>
            Use Simulation Mode
          </button>
          <button type="submit" className="btn btn--primary" disabled={checking || !code.trim()}>
            {checking ? "Checking…" : "Unlock"}
          </button>
        </div>
      </form>
    </div>
  );
}
