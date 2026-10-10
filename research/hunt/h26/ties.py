"""h26: how often did the premium stop AND the premium target both trade inside the same 1-minute bar?

The engine (obuy/engine.py, intrabar=True, the default used for every h26 exit) checks resting premium stops / targets
minute by minute on the option's own 1-minute HIGH / LOW; if both are touched in one bar the STOP is assumed first
(conservative). This counts those ties among exits that have both a fixed stop and a fixed target
(X1, X2, X7..X17; the ladder exit X3 is left out because its stop moves).

    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/ties.py <run name> [...]
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.costs import floor_tick  # noqa: E402
from obuy.data import market  # noqa: E402
from run import EXITS  # noqa: E402

FIXED = [1, 2] + list(range(7, 18))


def main(runs):
    mk = market()
    rows = []
    for name in runs:
        T = pd.read_csv(os.path.join(C.CACHE, "runs", name, "trades.csv.gz"), parse_dates=["day"])
        T["xi"] = T.vid.str.rsplit("|x", n=1).str[1].astype(int)
        T = T[T.xi.isin(FIXED) & T.why.isin(["stop", "target"])].copy()
        stop = np.full(len(T), np.nan)
        tgt = np.full(len(T), np.nan)
        for xi in FIXED:
            m = (T.xi == xi).values
            ex = EXITS[xi]
            e = T.entry.values[m]
            s = np.full(m.sum(), np.nan)
            if ex.stop_pct:
                s = floor_tick(e * (1 - ex.stop_pct))
            if ex.stop_pts:
                s = np.fmax(s, floor_tick(e - ex.stop_pts))
            t = np.full(m.sum(), np.inf)
            if ex.tgt_pts:
                t = e + ex.tgt_pts
            if ex.tgt_pct:
                t = np.fmin(t, e * (1 + ex.tgt_pct))
            stop[m], tgt[m] = s, t
        T["stop_lv"], T["tgt_lv"] = stop, tgt
        H = np.full(len(T), np.nan)
        L = np.full(len(T), np.nan)
        for (u, d), g in T.groupby(["und", "day"]):
            ch = mk.options(u).chain(d.date(), "near")
            if ch is None:
                continue
            for i, r in zip(g.index, g.itertuples()):
                k = ch.kpos(int(r.strike))
                col = int(r.exit_min) - C.OPEN_M
                if k < 0 or not (0 <= col < C.W):
                    continue
                H[T.index.get_loc(i)] = ch.h[r.right][k, col]
                L[T.index.get_loc(i)] = ch.l[r.right][k, col]
        tie = (L <= T.stop_lv.values) & (H >= T.tgt_lv.values)
        rows.append(dict(run=name, stop_or_target_exits=len(T), checked=int(np.isfinite(H).sum()), ties=int(tie.sum()),
                         tie_pct=100 * tie.sum() / max(np.isfinite(H).sum(), 1)))
        mk.release()
    R = pd.DataFrame(rows)
    print(R.to_string(index=False))
    print("TOTAL", int(R.stop_or_target_exits.sum()), int(R.checked.sum()), int(R.ties.sum()),
          f"{100 * R.ties.sum() / max(R.checked.sum(), 1):.2f}%")


if __name__ == "__main__":
    main(sys.argv[1:])
