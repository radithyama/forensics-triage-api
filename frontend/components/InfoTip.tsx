"use client";

import { useId, useState, type ReactNode } from "react";

interface Props {
  /** Accessible name for the trigger, e.g. "Why this matters". */
  label: string;
  title?: string;
  children: ReactNode;
  /** Which edge of the trigger the bubble lines up with; use "end" near the right side of a card. */
  align?: "start" | "end";
}

/**
 * Small ⓘ trigger with a tooltip. It opens on hover and on keyboard focus, and toggles on tap, so it
 * also works on touch screens. Escape closes it. The bubble is linked via aria-describedby.
 */
export function InfoTip({ label, title, children, align = "start" }: Props) {
  const id = useId();
  const [pinned, setPinned] = useState(false);

  return (
    <span className={`infotip infotip--${align}${pinned ? " infotip--open" : ""}`} onMouseLeave={() => setPinned(false)}>
      <button
        type="button"
        className="infotip__trigger"
        aria-label={label}
        aria-describedby={id}
        aria-expanded={pinned}
        onClick={(e) => {
          e.stopPropagation(); // don't also select the finding row underneath
          setPinned((p) => !p);
        }}
        onKeyDown={(e) => e.key === "Escape" && setPinned(false)}
        onBlur={() => setPinned(false)}
      >
        ⓘ
      </button>
      <span role="tooltip" id={id} className="infotip__bubble">
        {title && <strong className="infotip__title">{title}</strong>}
        {children}
      </span>
    </span>
  );
}
