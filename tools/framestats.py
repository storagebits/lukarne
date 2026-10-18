#!/usr/bin/env python3
"""Breaks slow frames down by phase from `dumpsys gfxinfo <pkg> framestats` (stdin). Times in ms."""
import sys
rows, header = [], None
for line in sys.stdin:
    line = line.strip()
    if line.startswith("Flags,IntendedVsync"):
        header = line.rstrip(",").split(",")
        continue
    if header and line and line[0].isdigit() and "," in line:
        vals = line.rstrip(",").split(",")
        if len(vals) >= len(header):
            rows.append(dict(zip(header, map(int, vals[:len(header)]))))
    elif header and line.startswith("---PROFILEDATA---") and rows:
        header = None
def ms(a, b): return (b - a) / 1e6
slow = []
for r in rows:
    if r["Flags"] != 0:
        continue
    total = ms(r["IntendedVsync"], r["FrameCompleted"])
    phases = {
        "input": ms(r["HandleInputStart"], r["AnimationStart"]),
        "anim/compose": ms(r["AnimationStart"], r["PerformTraversalsStart"]),
        "layout": ms(r["PerformTraversalsStart"], r["DrawStart"]),
        "draw": ms(r["DrawStart"], r["SyncQueued"]),
        "sync+gpu": ms(r["SyncQueued"], r["FrameCompleted"]),
    }
    if total > 16.7:
        slow.append((total, phases))
print(f"{len(rows)} frames, {len(slow)} over 16.7 ms")
for total, p in sorted(slow, key=lambda x: -x[0])[:12]:
    print(f"  {total:6.1f}  " + "  ".join(f"{k}={v:5.1f}" for k, v in p.items()))
