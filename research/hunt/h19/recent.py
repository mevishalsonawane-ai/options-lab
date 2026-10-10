"""h19 interim: trade-by-trade replay of 28 Sep - 6 Oct 2026, per-arm per-day totals, combined intraday MTM curve.

    python3 -I research/hunt/h19/recent.py
"""
from __future__ import annotations

import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h19")
BOSS = {"2026-09-28": (2, -56), "2026-09-29": (2, 589), "2026-09-30": (12, -6014), "2026-10-01": (38, 5032),
        "2026-10-05": (48, 4955), "2026-10-06": (40, -4551)}


def hm(m):
    return f"{int(m) // 60:02d}:{int(m) % 60:02d}"


def mtm(day_tr, paths, last=C.W):
    """Combined day P&L at each minute's close (realised net + open marked at the close, buy charges paid)."""
    v = np.zeros(C.W)
    for t in day_tr.itertuples():
        i0, i1 = t.entry_min - C.OPEN_M, t.exit_min - C.OPEN_M
        c = pd.Series(paths[t.tid][0]).ffill().fillna(t.entry).values
        seg = (c[:i1 - i0] - t.entry) * t.lot - t.buy_chg
        v[i0:i1] += seg
        v[i1:] += t.net
    return v


def main():
    tr = pd.read_parquet(os.path.join(OUT, "trades.parquet"))
    with open(os.path.join(OUT, "paths.pkl"), "rb") as f:
        paths = pickle.load(f)
    r = tr[tr.day >= "2026-09-28"].sort_values(["day", "entry_min", "arm"])
    lines = []
    for d, g in r.groupby("day"):
        ds = str(d.date())
        lines.append(f"\n### {ds}  (Boss: {BOSS.get(ds)})")
        lines.append("| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |")
        lines.append("|---|---|---|---|---|---|---|---|")
        for t in g.itertuples():
            lines.append(f"| {hm(t.entry_min)} | {hm(t.exit_min)} | {t.book} | {t.und} {t.strike} {'CE' if t.side > 0 else 'PE'} x{t.lot} |"
                         f" {t.entry:.2f} | {t.exit:.2f} | {t.why} | {t.net:+,.0f} |")
        v = mtm(g, paths)
        pk = int(np.argmax(v))
        lines.append(f"\nper arm: {g.groupby('arm').net.agg(['count', 'sum']).round(0).to_dict('index')}")
        lines.append(f"day: {len(g)} trades, net {g.net.sum():+,.0f}, gross {g.gross.sum():+,.0f}; MTM peak {v[pk]:+,.0f} at "
                     f"{hm(pk + C.OPEN_M)}, trough {v.min():+,.0f} at {hm(int(np.argmin(v)) + C.OPEN_M)}")
        # hourly curve
        lines.append("curve: " + ", ".join(f"{hm(m)} {v[m - C.OPEN_M]:+,.0f}" for m in range(600, 930, 30)))
    print("\n".join(lines))
    tab = r.pivot_table(index="day", columns="arm", values="net", aggfunc="sum").fillna(0).round(0)
    tab["total"] = tab.sum(axis=1)
    tab["n"] = r.groupby("day").size()
    print(tab.to_string())


if __name__ == "__main__":
    main()
