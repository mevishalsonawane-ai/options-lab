"""R25 Step 4: the 10 biggest jackpot days (design; holdout days added after the read) - what each rule did, and what
was knowable when the run started.

    python3 research/hunt/r25/case.py      # -> results/case.json, results/case.md
"""
from __future__ import annotations

import json

import pandas as pd

import r25cfg as K

IDS = ["H0", "L1", "L2", "L3", "L4", "V4", "T1", "T2", "T3"]
SHOW = ["mv15", "brk15", "or_brk", "dist_em", "dist_rng", "maxpain", "oi_ch30", "opp_oi_ch30", "st_low", "vix", "trend_open"]


def hm(t: int) -> str:
    m = 9 * 60 + 15 + int(t) + 1
    return f"{m // 60:02d}:{m % 60:02d}"


def main():
    c = json.loads((K.RESULTS / "census.json").read_text())
    d = json.loads((K.RESULTS / "design.json").read_text())
    ho = json.loads((K.RESULTS / "holdout.json").read_text()) if (K.RESULTS / "holdout.json").exists() else {}
    an = pd.read_parquet(K.OUT / "anatomy_design.parquet")
    an["day"] = an.day.astype(str)
    out = []
    for j in c["top10_days"]:
        rows = an[(an.und == j["und"]) & (an.day == j["day"]) & (an.strike == j["strike"]) & (an.side == j["side"])].sort_values("t")
        first = rows.iloc[0] if len(rows) else None
        trades = {i: [t for t in d[i]["trades_list"] if t["und"] == j["und"] and t["day"] == j["day"]] for i in IDS}
        # the decision rows of THIS jackpot strike that later did >= 10x: the earliest one, with its features
        jack = rows[rows.jackpot] if len(rows) else rows
        e = jack.iloc[0] if len(jack) else first
        out.append(dict(j, knowable_at=(hm(e.t) if e is not None else None),
                        features=({k: (None if pd.isna(e[k]) else round(float(e[k]), 3)) for k in SHOW} if e is not None else {}),
                        rules={i: [(t["side"], t["strike"], t["fill"], t["entry"], round(t["net"]), t["exits"][-1][3] if t["exits"] else "") for t in v]
                               for i, v in trades.items()}))
    (K.RESULTS / "case.json").write_text(json.dumps(out, indent=1, default=str))
    L = ["# R25 Step 4: the 10 biggest design jackpot days", "",
         "Best multiple reached before 15:05 by any Rs 1-10 unit (high-based; 2 Feb 2024 SENSEX is a disorderly-print day).",
         "Each rule column: side strike @fill time entry → net Rs (last exit). Empty = no trade that day.", "",
         "| day | index | jackpot | rules that traded | first 5-min decision where the jackpot strike was in band and later did ≥ 10× | state then |",
         "|---|---|---|---|---|---|"]
    for o in out:
        rr = "; ".join(f"{i}: " + ", ".join(f"{'CE' if s > 0 else 'PE'} {k:g} @{f} {e} → {n:+,}" for s, k, f, e, n, _ in v)
                       for i, v in o["rules"].items() if v) or "none"
        f = o["features"]
        st = ", ".join(f"{k} {v}" for k, v in f.items() if v is not None)
        L.append(f"| {o['day']} | {o['und']} | {o['strike']:g} {o['side']}E {o['ref']} → {o['peak']:.1f} ({o['mult']}×) from "
                 f"{o['start']}, peak {o['peak_at']}, {o['m1505']}× at 15:05 | {rr} | {o['knowable_at']} | {st} |")
    (K.RESULTS / "case.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
