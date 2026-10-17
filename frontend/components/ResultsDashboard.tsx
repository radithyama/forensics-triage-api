"use client";

import { useMemo, useState } from "react";
import type { Job, ProcessNode } from "@/lib/types";
import { explainFinding } from "@/lib/findingExplanations";
import { InfoTip } from "./InfoTip";
import { ProcessTree } from "./ProcessTree";
import { formatBytes } from "./UploadPanel";

function index(roots: ProcessNode[]) {
  const m = new Map<number, ProcessNode>();
  const walk = (ns: ProcessNode[]) => ns.forEach((n) => (m.set(n.pid, n), walk(n.children)));
  walk(roots);
  return m;
}

const hex = (v: number | null) => (v == null ? "—" : `0x${v.toString(16)}`);
const ts = (v: string | null) => (v ? v.replace("T", " ").replace("+00:00", " UTC") : "—");

export function ResultsDashboard({ job }: { job: Job }) {
  const result = job.result!;
  const roots = result.processTree ?? [];
  const findings = result.findings ?? [];
  const byPid = useMemo(() => index(roots), [roots]);
  const [selected, setSelected] = useState<number | null>(findings[0]?.pid ?? null);
  const node = selected != null ? byPid.get(selected) : undefined;
  const nodeFindings = findings.filter((f) => f.pid === selected);

  const stats: [string, string | number][] = result.rows
    ? [["Rows", result.summary.rowCount ?? result.rows.length]]
    : [
        ["Processes", result.summary.processCount ?? 0],
        ["Findings", result.summary.findingCount ?? 0],
        ["High severity", result.summary.highSeverity ?? 0],
        ["Duration", result.durationSeconds != null ? `${result.durationSeconds}s` : "—"],
      ];

  return (
    <section className="results">
      <div className="evidence card">
        <div>
          <span className="kicker">Evidence</span>
          <strong className="mono">{job.filename}</strong>
          <span className="muted">
            {formatBytes(job.sizeBytes)} · {result.plugin}
          </span>
        </div>
        <div className="evidence__hash">
          <span className="kicker kicker--with-tip">
            SHA-256 (verified by worker)
            <InfoTip label="What is this hash?" title="Fingerprint of the evidence" align="end">
              A SHA-256 hash is a cryptographic fingerprint of the exact bytes of the memory image. Changing even a
              single bit produces a completely different value. Recording it on upload and re-checking it before
              analysis proves the evidence wasn&apos;t altered along the way. That&apos;s the integrity, or chain of
              custody, that makes digital evidence trustworthy.
            </InfoTip>
          </span>
          <code>{result.evidenceSha256 ?? job.sha256 ?? "—"}</code>
        </div>
      </div>

      <div className="stats">
        {stats.map(([label, value]) => (
          <div key={label} className={`stat card${label === "High severity" && Number(value) > 0 ? " stat--alert" : ""}`}>
            <span className="stat__value">{value}</span>
            <span className="stat__label">{label}</span>
          </div>
        ))}
      </div>

      {result.rows ? (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr>{Object.keys(result.rows[0] ?? {}).map((k) => <th key={k}>{k}</th>)}</tr>
            </thead>
            <tbody>
              {result.rows.slice(0, 500).map((r, i) => (
                <tr key={i}>{Object.values(r).map((v, j) => <td key={j} className="mono">{String(v ?? "—")}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="grid">
          <div className="card">
            <h3>Process tree</h3>
            <ProcessTree roots={roots} findings={findings} selected={selected} onSelect={setSelected} />
          </div>

          <div className="side">
            <div className="card">
              <h3>Triage findings</h3>
              {findings.length === 0 ? (
                <p className="muted">No anomalies matched the triage rules.</p>
              ) : (
                <ul className="findings">
                  {findings.map((f, i) => {
                    const why = explainFinding(f);
                    return (
                    <li
                      key={i}
                      className={`finding${f.pid === selected ? " finding--selected" : ""}`}
                      onClick={() => setSelected(f.pid)}
                    >
                      <span className="finding__sev">
                        <span className={`badge badge--${f.severity}`}>{f.severity}</span>
                        <InfoTip label={`Why ${f.process} is flagged`} title={why.title}>
                          {why.body}
                        </InfoTip>
                      </span>
                      <div>
                        <strong className="mono">
                          {f.process} <span className="muted">({f.pid})</span>
                        </strong>
                        <span>{f.detail}</span>
                        <code className="finding__rule">{f.rule}</code>
                      </div>
                    </li>
                    );
                  })}
                </ul>
              )}
            </div>

            {node && (
              <div className="card">
                <h3>
                  <span className="mono">{node.name}</span> <span className="muted">PID {node.pid}</span>
                </h3>
                <dl className="details">
                  <dt>Parent PID</dt><dd className="mono">{node.ppid}{byPid.get(node.ppid) ? ` (${byPid.get(node.ppid)!.name})` : " (not in listing)"}</dd>
                  <dt>EPROCESS</dt><dd className="mono">{hex(node.offset)}</dd>
                  <dt>Threads</dt><dd className="mono">{node.threads ?? "—"}</dd>
                  <dt>Session</dt><dd className="mono">{node.sessionId ?? "—"}</dd>
                  <dt>Wow64</dt><dd className="mono">{node.wow64 ? "yes" : "no"}</dd>
                  <dt>Created</dt><dd className="mono">{ts(node.createTime)}</dd>
                  <dt>Exited</dt><dd className="mono">{ts(node.exitTime)}</dd>
                  <dt>Children</dt><dd className="mono">{node.children.length}</dd>
                </dl>
                {nodeFindings.length > 0 && (
                  <p className="details__flag">{nodeFindings.map((f) => f.detail).join(" · ")}</p>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
