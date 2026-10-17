"""Turns flat Volatility process listings into a process tree plus triage findings."""
from __future__ import annotations

from typing import Any

# Expected parent for core Windows processes (lower-case image names, as truncated by EPROCESS).
_EXPECTED_PARENTS = {
    "smss.exe": {"system"},
    "wininit.exe": {"smss.exe"},
    "services.exe": {"wininit.exe"},
    "lsass.exe": {"wininit.exe"},
    "svchost.exe": {"services.exe", "msmpeng.exe"},
}
_SINGLETONS = {"lsass.exe", "services.exe", "wininit.exe", "lsaiso.exe"}
_LOLBINS = {"cmd.exe", "powershell.exe", "pwsh.exe", "wscript.exe", "cscript.exe",
            "mshta.exe", "rundll32.exe", "regsvr32.exe", "certutil.exe", "bitsadmin.exe"}
_DOC_APPS = {"winword.exe", "excel.exe", "powerpnt.exe", "outlook.exe", "acrord32.exe",
             "msedge.exe", "chrome.exe", "firefox.exe", "iexplore.exe"}
# Their launchers (smss instances, userinit) exit by design, so a missing parent is normal.
_EXPECTED_ORPHANS = {"system", "registry", "csrss.exe", "wininit.exe", "winlogon.exe", "explorer.exe"}
_LOOKALIKES = {"scvhost.exe": "svchost.exe", "svch0st.exe": "svchost.exe", "svhost.exe": "svchost.exe",
               "lsas.exe": "lsass.exe", "lssass.exe": "lsass.exe", "isass.exe": "lsass.exe",
               "csrs.exe": "csrss.exe", "explorer.exe.exe": "explorer.exe", "taskmgr.exe.exe": "taskmgr.exe"}


def _flatten(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """PsTree nests rows under __children; PsList is flat. Normalise both to a flat list."""
    out: list[dict[str, Any]] = []
    stack = list(rows)
    while stack:
        row = stack.pop()
        stack.extend(row.get("__children") or [])
        out.append(row)
    return out


def _node(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "pid": row.get("PID"),
        "ppid": row.get("PPID"),
        "name": row.get("ImageFileName") or row.get("COMM") or "?",
        "offset": row.get("Offset(V)"),
        "threads": row.get("Threads"),
        "handles": row.get("Handles"),
        "sessionId": row.get("SessionId"),
        "wow64": row.get("Wow64"),
        "createTime": row.get("CreateTime"),
        "exitTime": row.get("ExitTime"),
        "children": [],
    }


def build_tree(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[int, dict[str, Any]]]:
    nodes: dict[int, dict[str, Any]] = {}
    for row in _flatten(rows):
        n = _node(row)
        if isinstance(n["pid"], int):
            nodes.setdefault(n["pid"], n)

    roots: list[dict[str, Any]] = []
    for pid, n in nodes.items():
        parent = nodes.get(n["ppid"])
        # PID reuse can create cycles or self-parenting; attach those at the root instead.
        if parent is None or parent is n or _is_ancestor(n, parent, nodes):
            roots.append(n)
        else:
            parent["children"].append(n)

    def sort(ns: list[dict[str, Any]]) -> None:
        ns.sort(key=lambda x: x["pid"])
        for c in ns:
            sort(c["children"])

    sort(roots)
    return roots, nodes


def _is_ancestor(node: dict[str, Any], candidate: dict[str, Any], nodes: dict[int, dict[str, Any]]) -> bool:
    """True if ``node`` is already an ancestor of ``candidate`` (attaching would form a cycle)."""
    seen: set[int] = set()
    cur = candidate
    while cur is not None and cur["pid"] not in seen:
        if cur is node:
            return True
        seen.add(cur["pid"])
        cur = nodes.get(cur["ppid"])
    return False


def find_anomalies(nodes: dict[int, dict[str, Any]]) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []

    def add(severity: str, rule: str, node: dict[str, Any], detail: str) -> None:
        findings.append({"severity": severity, "rule": rule, "pid": node["pid"],
                         "process": node["name"], "detail": detail})

    counts: dict[str, int] = {}
    for n in nodes.values():
        if not n.get("exitTime"):
            counts[n["name"].lower()] = counts.get(n["name"].lower(), 0) + 1

    for n in nodes.values():
        name = n["name"].lower()
        parent = nodes.get(n["ppid"])
        pname = parent["name"].lower() if parent else None

        if name in _LOOKALIKES:
            add("high", "masquerading-name", n, f"Name resembles {_LOOKALIKES[name]}")
        expected = _EXPECTED_PARENTS.get(name)
        if expected and pname is not None and pname not in expected:
            add("high", "unexpected-parent", n, f"Parent is {parent['name']} (expected {', '.join(sorted(expected))})")
        if name in _SINGLETONS and counts.get(name, 0) > 1:
            add("high", "duplicate-singleton", n, f"{counts[name]} live instances of {n['name']}")
        if name in _LOLBINS and pname in _DOC_APPS:
            add("medium", "suspicious-child", n, f"{n['name']} spawned by {parent['name']}")
        elif name in _LOLBINS and pname in _LOLBINS and name != "conhost.exe":
            add("medium", "lolbin-chain", n, f"{n['name']} spawned by {parent['name']}")
        if parent is None and n["ppid"] not in (0, None) and name not in _EXPECTED_ORPHANS:
            add("low", "orphan-process", n, f"Parent PID {n['ppid']} not present in listing")

    order = {"high": 0, "medium": 1, "low": 2}
    findings.sort(key=lambda f: (order[f["severity"]], f["pid"]))
    return findings


def analyse(plugin: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    flat = _flatten(rows)
    if not any("PPID" in r for r in flat):
        # Not a process listing (e.g. NetScan, Malfind): return the rows as a table.
        table = [{k: v for k, v in r.items() if k != "__children"} for r in flat]
        return {"plugin": plugin, "summary": {"rowCount": len(table)}, "rows": table}

    roots, nodes = build_tree(rows)
    findings = find_anomalies(nodes)
    return {
        "plugin": plugin,
        "summary": {
            "processCount": len(nodes),
            "rootCount": len(roots),
            "findingCount": len(findings),
            "highSeverity": sum(1 for f in findings if f["severity"] == "high"),
        },
        "findings": findings,
        "processTree": roots,
    }
