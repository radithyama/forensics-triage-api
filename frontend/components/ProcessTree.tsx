"use client";

import { useMemo, useState, type ReactNode } from "react";
import type { Finding, ProcessNode } from "@/lib/types";

interface Props {
  roots: ProcessNode[];
  findings: Finding[];
  selected: number | null;
  onSelect: (pid: number) => void;
}

const SEV_RANK = { high: 0, medium: 1, low: 2 } as const;

function matches(n: ProcessNode, q: string): boolean {
  return n.name.toLowerCase().includes(q) || String(n.pid) === q || n.children.some((c) => matches(c, q));
}

export function ProcessTree({ roots, findings, selected, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());

  const worst = useMemo(() => {
    const m = new Map<number, Finding["severity"]>();
    for (const f of findings) {
      const cur = m.get(f.pid);
      if (!cur || SEV_RANK[f.severity] < SEV_RANK[cur]) m.set(f.pid, f.severity);
    }
    return m;
  }, [findings]);

  const q = query.trim().toLowerCase();
  const toggle = (pid: number) =>
    setCollapsed((s) => {
      const next = new Set(s);
      next.has(pid) ? next.delete(pid) : next.add(pid);
      return next;
    });

  const render = (n: ProcessNode, depth: number): ReactNode => {
    if (q && !matches(n, q)) return null;
    const open = q ? true : !collapsed.has(n.pid);
    const sev = worst.get(n.pid);
    return (
      <li key={n.pid}>
        <div
          className={`tree__row${selected === n.pid ? " tree__row--selected" : ""}${n.exitTime ? " tree__row--exited" : ""}`}
          style={{ paddingLeft: depth * 18 + 8 }}
          onClick={() => onSelect(n.pid)}
        >
          {n.children.length > 0 ? (
            <button
              className="tree__caret"
              aria-label={open ? "Collapse" : "Expand"}
              onClick={(e) => {
                e.stopPropagation();
                toggle(n.pid);
              }}
            >
              {open ? "▾" : "▸"}
            </button>
          ) : (
            <span className="tree__caret tree__caret--leaf">·</span>
          )}
          <span className="tree__name mono">{n.name}</span>
          <span className="tree__pid mono">{n.pid}</span>
          {sev && <span className={`badge badge--${sev}`}>{sev}</span>}
          {n.wow64 && <span className="badge badge--muted">wow64</span>}
          {n.exitTime && <span className="badge badge--muted">exited</span>}
        </div>
        {open && n.children.length > 0 && <ul>{n.children.map((c) => render(c, depth + 1))}</ul>}
      </li>
    );
  };

  return (
    <div className="tree">
      <input
        className="tree__search"
        placeholder="Filter by name or PID…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <ul className="tree__list">{roots.map((r) => render(r, 0))}</ul>
    </div>
  );
}
