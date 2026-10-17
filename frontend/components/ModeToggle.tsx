"use client";

export type Mode = "simulation" | "live";

export function ModeToggle({ mode, onChange, disabled }: { mode: Mode; onChange: (m: Mode) => void; disabled?: boolean }) {
  return (
    <div className="mode-toggle" role="radiogroup" aria-label="Analysis mode">
      {(["simulation", "live"] as const).map((m) => (
        <button
          key={m}
          role="radio"
          aria-checked={mode === m}
          className={mode === m ? "active" : ""}
          disabled={disabled}
          onClick={() => onChange(m)}
        >
          {m === "simulation" ? "Simulation Mode" : "Live Trial"}
        </button>
      ))}
    </div>
  );
}
