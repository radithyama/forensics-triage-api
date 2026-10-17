from worker import triage


def row(pid, ppid, name, exit_time=None):
    return {"PID": pid, "PPID": ppid, "ImageFileName": name, "Offset(V)": pid * 4096,
            "Threads": 1, "Handles": 10, "SessionId": 0, "Wow64": False,
            "CreateTime": "2026-01-01T00:00:00+00:00", "ExitTime": exit_time, "__children": []}


CLEAN = [row(4, 0, "System"), row(300, 4, "smss.exe"), row(400, 300, "wininit.exe"),
         row(500, 400, "services.exe"), row(510, 400, "lsass.exe"), row(600, 500, "svchost.exe")]


def test_builds_nested_tree():
    roots, nodes = triage.build_tree(CLEAN)
    assert [r["pid"] for r in roots] == [4]
    wininit = roots[0]["children"][0]["children"][0]
    assert wininit["name"] == "wininit.exe"
    assert [c["pid"] for c in wininit["children"]] == [500, 510]
    assert len(nodes) == 6


def test_clean_system_has_no_findings():
    assert triage.find_anomalies(triage.build_tree(CLEAN)[1]) == []


def test_flags_classic_injection_patterns():
    rows = CLEAN + [row(700, 600, "lsass.exe"), row(800, 4, "scvhost.exe"),
                    row(900, 850, "winword.exe"), row(901, 900, "powershell.exe"),
                    row(902, 901, "rundll32.exe"), row(1000, 990, "explorer.exe")]
    rules = {(f["rule"], f["pid"]) for f in triage.analyse("windows.pslist.PsList", rows)["findings"]}
    assert ("unexpected-parent", 700) in rules
    assert ("duplicate-singleton", 700) in rules
    assert ("masquerading-name", 800) in rules
    assert ("suspicious-child", 901) in rules
    assert ("lolbin-chain", 902) in rules
    assert ("orphan-process", 900) in rules
    assert ("orphan-process", 1000) not in rules  # explorer's userinit parent exits by design


def test_pid_cycles_do_not_hang_or_drop_processes():
    roots, nodes = triage.build_tree([row(10, 20, "a.exe"), row(20, 10, "b.exe"), row(30, 30, "self.exe")])
    seen = []

    def walk(ns):
        for n in ns:
            seen.append(n["pid"])
            walk(n["children"])

    walk(roots)
    assert sorted(seen) == [10, 20, 30]


def test_pstree_nested_input_is_flattened():
    nested = row(4, 0, "System")
    nested["__children"] = [row(300, 4, "smss.exe")]
    assert triage.analyse("windows.pstree.PsTree", [nested])["summary"]["processCount"] == 2


def test_non_process_plugin_returns_table():
    out = triage.analyse("windows.netscan.NetScan", [{"Proto": "TCPv4", "LocalAddr": "0.0.0.0", "__children": []}])
    assert out["rows"] == [{"Proto": "TCPv4", "LocalAddr": "0.0.0.0"}]
