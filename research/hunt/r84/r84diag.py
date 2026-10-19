"""R84 diagnostics on DESIGN only (no final read): why fewer positions per signal lose the edge.

    python research/hunt/r84/r84diag.py      # -> results/diag.json
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r84rules as P  # noqa: E402
import r84study as St  # noqa: E402


def run() -> dict:
    lo, hi = St.R75C.START, St.R75C.DESIGN_END
    dc = St.day_cands(lo, hi)
    k0, sigs = St.book(dc, St.variant_chooser("K0"))
    sh = {(c["day"], c["er"], c["side"]): c["shock"] for d in dc for c in dc[d]}
    sn = P.signal_nets(k0)                                     # keyed (day, sig, side); sig == er for C
    by_n, by_shock = defaultdict(list), defaultdict(list)
    for k, v in sn.items():
        by_n[sigs.get(k, 0)].append(v)
        s = sh.get(k)
        b = "unknown" if s is None else ("<=2" if s <= 2 else "2-5" if s <= 5 else "5-10" if s <= 10 else ">10")
        by_shock[b].append(v)

    def row(x):
        x = np.array(x)
        return dict(signals=len(x), net=round(float(x.sum())), mean=round(float(x.mean())), win=round(float((x > 0).mean()), 3),
                    worst=round(float(x.min())), best=round(float(x.max())))
    vals = np.array(list(sn.values()))
    # rank of each position inside its signal by break size, and by the fixed order: net per rank
    rank_size, rank_order = defaultdict(float), defaultdict(float)
    cnt = defaultdict(int)
    tk = {(t["und"], t["day"], t["er"], t["side"]) for t in k0}
    for d in dc:
        g = defaultdict(list)
        for c in dc[d]:
            if (c["und"], d, c["er"], c["side"]) in tk:
                g[(c["er"], c["side"])].append(c)
        for el in g.values():
            for i, c in enumerate(P.by_strength(el)):
                rank_size[i + 1] += c["res"]["net"]
                cnt[i + 1] += 1
            for i, c in enumerate(P.by_order(el)):
                rank_order[i + 1] += c["res"]["net"]
    out = dict(period=f"{lo} - {hi} (design)", signals=len(sn),
               signal_net_pct=dict(zip(("p5", "p25", "p50", "p75", "p95"), np.percentile(vals, [5, 25, 50, 75, 95]).round(0).tolist())),
               signals_losing_9700_or_more=int((vals <= -9700).sum()), signals_losing_5000_or_more=int((vals <= -5000).sum()),
               signals_making_9700_or_more=int((vals >= 9700).sum()),
               by_positions={str(k): row(v) for k, v in sorted(by_n.items())},
               by_shock={k: row(v) for k, v in sorted(by_shock.items())},
               net_by_size_rank={str(k): dict(trades=cnt[k], net=round(rank_size[k])) for k in sorted(rank_size)},
               net_by_order_rank={str(k): round(rank_order[k]) for k in sorted(rank_order)},
               net_by_index={u: round(sum(t["net"] for t in k0 if t["und"] == u)) for u in P.ORDER})
    (HERE / "results" / "diag.json").write_text(json.dumps(out, indent=1) + "\n")
    print(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    run()
