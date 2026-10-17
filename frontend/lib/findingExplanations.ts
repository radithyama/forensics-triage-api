import type { Finding } from "./types";

export interface Explanation {
  title: string;
  body: string;
}

/**
 * Plain-language context for each triage rule, written for visitors who aren't forensic analysts.
 * Rule names match the worker's triage engine (forensic-worker/worker/triage.py).
 */
const BY_RULE: Record<string, Explanation> = {
  "masquerading-name": {
    title: "Look-alike process name",
    body:
      "Malware often names itself after a trusted Windows process with one letter changed (scvhost.exe " +
      "instead of svchost.exe), hoping a quick glance at Task Manager won't notice.",
  },
  "unexpected-parent": {
    title: "Wrong parent process",
    body:
      "Core Windows processes are always started by the same parent. One launched by something else was " +
      "probably started by an attacker or injected code, not by Windows itself.",
  },
  "duplicate-singleton": {
    title: "Duplicate system process",
    body:
      "Some Windows processes should only ever run once. A second copy is often an impostor hiding in " +
      "plain sight under a trusted name.",
  },
  "suspicious-child": {
    title: "Document app launched a shell",
    body:
      "Office documents and browsers rarely need to start command shells or scripting tools. When they do, " +
      "it usually means a malicious document or web page ran code on the machine.",
  },
  "lolbin-chain": {
    title: "Chained system tools (living off the land)",
    body:
      "Attackers like to chain built-in Windows tools such as PowerShell, rundll32 and certutil, because " +
      "they are trusted and already installed. One spawning another is a classic sign of a staged attack.",
  },
  "orphan-process": {
    title: "Missing parent",
    body:
      "The process that started this one no longer exists. That's often harmless (launchers exit all the " +
      "time), but it's also how some malware covers its tracks, so it's worth a second look.",
  },
};

/** Sharper explanations for the most recognisable patterns, keyed by rule + process (+ parent). */
function specific(f: Finding): Explanation | null {
  const proc = f.process.toLowerCase();
  const detail = f.detail.toLowerCase();

  if (f.rule === "suspicious-child" && /powershell|pwsh|cmd\.exe|wscript|cscript|mshta/.test(proc) && /winword|excel|powerpnt|outlook/.test(detail)) {
    return {
      title: "Likely malicious macro",
      body:
        `${f.process} launched by an Office application is one of the most common signs of a malicious ` +
        "macro. The victim opens a booby-trapped document, the macro runs, and it starts a shell to " +
        "download or run the attacker's payload.",
    };
  }
  if (proc === "lsass.exe" && (f.rule === "duplicate-singleton" || f.rule === "unexpected-parent")) {
    return {
      title: "Possible credential dumping",
      body:
        "lsass.exe holds the passwords and login tokens of everyone signed in to the machine, which makes it " +
        "a top target. A second or wrongly-parented lsass.exe suggests an impostor built to capture or " +
        "dump those credentials (tools like Mimikatz go after this process).",
    };
  }
  // Match on what it imitates ("Name resembles svchost.exe"), since the fake name is by definition misspelled.
  if (f.rule === "masquerading-name" && detail.includes("svchost.exe")) {
    return {
      title: "Fake svchost.exe",
      body:
        "svchost.exe is the most-imitated name on Windows: dozens of genuine copies run at once, so one more " +
        "rarely stands out. A misspelled variant is a strong sign of malware trying to blend in.",
    };
  }
  if (f.rule === "lolbin-chain" && proc === "rundll32.exe") {
    return {
      title: "Script loading a DLL",
      body:
        "A scripting tool starting rundll32.exe is a common way to run a malicious DLL while appearing to " +
        "use a trusted Windows component, often the step where the real payload starts running.",
    };
  }
  return null;
}

export function explainFinding(f: Finding): Explanation {
  return (
    specific(f) ??
    BY_RULE[f.rule] ?? {
      title: "Anomaly",
      body: "This pattern doesn't match how a normal Windows system behaves, so an analyst should take a closer look.",
    }
  );
}
