"""Regenerates frontend/lib/mock-job.json (Simulation Mode data) using the real triage engine.

Run from forensic-worker/:  python -m scripts.generate_mock_job
"""
import json
from pathlib import Path

from worker import triage

T = "2026-10-14T{h:02d}:{m:02d}:{s:02d}+00:00"


def r(pid, ppid, name, th, sess, h, m, s=0, wow=False, exit=None):
    return {"PID": pid, "PPID": ppid, "ImageFileName": name, "Offset(V)": 0xFFFFA48E00000000 + pid * 0x1000,
            "Threads": th, "Handles": None, "SessionId": sess, "Wow64": wow,
            "CreateTime": T.format(h=h, m=m, s=s), "ExitTime": exit, "File output": "Disabled", "__children": []}


ROWS = [
    r(4, 0, "System", 162, None, 8, 1), r(108, 4, "Registry", 4, None, 8, 1, 1), r(372, 4, "smss.exe", 2, None, 8, 1, 2),
    r(484, 476, "csrss.exe", 11, 0, 8, 1, 5), r(560, 476, "wininit.exe", 1, 0, 8, 1, 5),
    r(572, 552, "csrss.exe", 13, 1, 8, 1, 5), r(648, 552, "winlogon.exe", 3, 1, 8, 1, 6),
    r(700, 560, "services.exe", 7, 0, 8, 1, 6), r(720, 560, "lsass.exe", 9, 0, 8, 1, 6),
    r(836, 700, "svchost.exe", 14, 0, 8, 1, 8), r(860, 560, "fontdrvhost.ex", 5, 0, 8, 1, 8),
    r(944, 700, "svchost.exe", 11, 0, 8, 1, 8), r(1004, 648, "dwm.exe", 15, 1, 8, 1, 9),
    r(1180, 700, "svchost.exe", 22, 0, 8, 1, 10), r(1412, 700, "spoolsv.exe", 8, 0, 8, 1, 14),
    r(2032, 700, "MsMpEng.exe", 30, 0, 8, 1, 20), r(2864, 700, "svchost.exe", 6, 1, 8, 2),
    r(3120, 3064, "explorer.exe", 71, 1, 8, 2, 30), r(3388, 3120, "OneDrive.exe", 24, 1, 8, 3, wow=True),
    r(4120, 3120, "chrome.exe", 38, 1, 8, 15), r(4188, 4120, "chrome.exe", 12, 1, 8, 15, 2),
    r(5012, 3120, "WINWORD.EXE", 21, 1, 9, 12), r(5240, 5012, "powershell.exe", 14, 1, 9, 13, 41),
    r(5300, 5240, "conhost.exe", 4, 1, 9, 13, 41), r(5388, 5240, "rundll32.exe", 3, 1, 9, 13, 58),
    r(5520, 3120, "scvhost.exe", 6, 1, 9, 14, 12), r(5604, 1180, "lsass.exe", 4, 0, 9, 14, 30),
    r(6012, 3120, "notepad.exe", 1, 1, 9, 40), r(6200, 836, "WmiPrvSE.exe", 8, 0, 9, 41, exit=T.format(h=9, m=42, s=3)),
]

if __name__ == "__main__":
    result = triage.analyse("windows.pslist.PsList", ROWS)
    sha = "9f2c4d7a1be03c58e6a1f0d4b2c7e9a8513f6d20ab4c8e17d9f3b6a05c2e4d81"
    result["evidenceSha256"] = sha
    result["durationSeconds"] = 47.3
    job = {"id": "00000000-0000-4000-8000-000000000000", "filename": "WIN10-WKSTN-042.raw", "sizeBytes": 4294967296,
           "sha256": sha, "plugin": "windows.pslist.PsList", "status": "COMPLETED", "progress": 100,
           "message": "Analysis complete", "createdAt": "2026-10-17T09:00:00Z", "updatedAt": "2026-10-17T09:00:47Z",
           "result": result}
    out = Path(__file__).resolve().parents[2] / "frontend" / "lib" / "mock-job.json"
    out.write_text(json.dumps(job, indent=2) + "\n")
    print(f"Wrote {out.name}: {result['summary']}")
    for f in result["findings"]:
        print(f"  {f['severity']:<6} {f['rule']:<20} pid={f['pid']:<5} {f['detail']}")
