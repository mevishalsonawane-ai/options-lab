"""R57 monthly retrain (offline; PREREG section 8). A scheduled job could run it after the lake gains a month:

    python research/hunt/r57/r57data.py build                      # refresh the day stores from the new lake
    python research/hunt/r57/r57retrain.py --variant A2 --deployed research/hunt/r57/results/models/A2.json \\
        --out <dir for the candidate model>

It labels every session from 20 Nov 2024 to the lake's last, runs the variant's purged walk-forward over the latest
6 whole months (monthly refits, 1-session embargo), and applies the same gates before a new model may replace the
deployed one: net > 0 after costs, random-entry twin p < 0.05, 40+ trades, net without its 5 best trades > 0, and the
exported JSON scoring exactly like the fitted model (r57eval parity < 1e-9). Pass: the new <variant>.json and its
golden vectors are written to --out and the exit code is 0. Fail: nothing is written, the deployed model stays, exit
code 1. Never on the phone, never on the order or tick path: the phone only loads the JSON at start-up.

Only after the study's one final read (results/FINAL_READ) may it label sessions after 31 Mar 2026.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np

import r57cfg as K
import r57data
import r57setups as S
import r57study as R

RECENT_MONTHS = 6


def recent_months(last: date, n: int = RECENT_MONTHS) -> list:
    """The n whole months before `last`'s month, plus `last`'s own month (it may be partial), oldest first."""
    y, m = last.year, last.month
    out = []
    for _ in range(n):
        out.append((y, m))
        y, m = (y - 1, 12) if m == 1 else (y, m - 1)
    return out[::-1]


def gates(st: dict, p) -> list:
    fails = []
    if st["net"] <= 0:
        fails.append("net <= 0")
    if p is None or p >= K.GATE_P:
        fails.append("p >= 0.05")
    if st["trades"] < K.GATE_MIN_TRADES:
        fails.append("fewer than 40 trades")
    if st["trades"] and st.get("ex_top5", 0) <= 0:
        fails.append("top-5 >= net")
    return fails


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="A2", choices=sorted(R.VARIANTS))
    ap.add_argument("--deployed", type=Path, help="the model JSON now in use (kept on a fail)")
    ap.add_argument("--out", type=Path, required=True)
    a_ = ap.parse_args(argv)
    if not K.FINAL_FLAG.exists():
        print("R57 retrain: the study's final read has not happened; refusing to label final-test sessions.")
        return 1
    S.open_final()
    for u in K.UNDS:
        r57data.load(u)
    cal = R.calendar()
    last = max(cal)
    a, b = R.load_or_build(f"retrain_{last}", K.START, last)
    v = R.VARIANTS[a_.variant]
    months = recent_months(last)
    tr = (R.run_a(v, a, months, cal) if v["fam"] == "A" else R.run_b(v, b, months, cal))[0]
    st = R.stats(tr)
    p, _ = R.twin_p(tr, v["fam"])
    fails = gates(st, p)
    report = dict(variant=a_.variant, months=[f"{y}-{m:02d}" for y, m in months], last=str(last), stats=st, p=p,
                  deployed=str(a_.deployed) if a_.deployed else None)
    if fails:
        report["verdict"] = "KEEP the deployed model: " + "; ".join(fails)
        print(json.dumps(report, default=str))
        return 1
    gaps = R.export_models([a_.variant], a, b, cal, out_dir=a_.out)
    if gaps[a_.variant] >= 1e-9:
        for f in (a_.out / f"{a_.variant}.json", a_.out / f"{a_.variant}_golden.json"):
            f.unlink(missing_ok=True)
        report["verdict"] = f"KEEP the deployed model: JSON parity gap {gaps[a_.variant]:.2e}"
        print(json.dumps(report, default=str))
        return 1
    report["verdict"] = f"REPLACE: new model written to {a_.out}"
    print(json.dumps(report, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
