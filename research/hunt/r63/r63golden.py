"""Golden vectors for the app's Kotlin port (android/engine .../trend/TrendTurn.kt): synthetic minutes (a seeded random
walk over 4 sessions with gaps, a missing bar and a missing session start; NOT market data) and every rule's act times
against a call and a put, from r63rules.py.

    python research/hunt/r63/r63golden.py     # writes results/golden.json and the engine's test copy
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r63rules as RR  # noqa: E402

ENGINE_COPY = HERE.parents[2] / "android" / "engine" / "src" / "test" / "resources" / "trend" / "r63_golden.json"


def minutes(seed: int = 63, n_days: int = 4):
    rng = np.random.default_rng(seed)
    days = [date(2025, 6, 2) + timedelta(days=i) for i in range(n_days)]
    idx = []
    px = 24000.0
    for i, d in enumerate(days):
        a = np.full((4, 375), np.nan)
        drift = (1 if i % 2 == 0 else -1) * 0.6
        for r in range(375):
            o = px
            px = px + drift + rng.normal(0, 6)
            hi, lo = max(o, px) + abs(rng.normal(0, 2)), min(o, px) - abs(rng.normal(0, 2))
            a[:, r] = (round(o, 2), round(hi, 2), round(lo, 2), round(px, 2))
        if i == 1:
            a[:, 40:45] = np.nan            # a whole 5-minute bar missing
            a[:, 200] = np.nan              # one minute missing
        if i == 2:
            a[:, :3] = np.nan               # a late start
        if i == 3:
            a[0, 100] = np.nan              # an open missing: it falls back to the close
        idx.append(a)
    return days, idx


def main():
    days, idx = minutes()
    rows = []
    for d, a in zip(days, idx):
        for r in range(375):
            if np.isfinite(a[3, r]) or np.isfinite(a[1, r]):
                t = datetime.combine(d, datetime.min.time()) + timedelta(minutes=9 * 60 + 15 + r)
                rows.append([t.isoformat(), *[None if not np.isfinite(x) else float(x) for x in a[:, r]]])
    out = dict(note="synthetic minutes (seeded random walk), not market data; r63rules.py act times", minutes=rows, turns={})
    for rule in RR.RULES:
        t = RR.turn_rows(rule, days, idx)
        for s in ("C", "P"):
            acts = []
            for d in days:
                for x, f in zip(t[d][s], t[d][s + "f"]):
                    at = datetime.combine(d, datetime.min.time()) + timedelta(minutes=9 * 60 + 15 + x)
                    fa = (datetime.combine(d, datetime.min.time()) + timedelta(minutes=9 * 60 + 15 + f)).isoformat() if f >= 0 else None
                    acts.append([at.isoformat(), fa])
            out["turns"][f"{rule}{s}"] = acts
    js = json.dumps(out, separators=(",", ":"))
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / "golden.json").write_text(js)
    ENGINE_COPY.parent.mkdir(parents=True, exist_ok=True)
    ENGINE_COPY.write_text(js)
    print({k: len(v) for k, v in out["turns"].items()})


if __name__ == "__main__":
    main()
