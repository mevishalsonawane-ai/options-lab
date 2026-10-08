"""h31 extras for the report (after the PRE tests and the single holdout read; chooses nothing):
per-year / per-month detail, losing-month bootstrap, lots and capital, breadth look-ahead check.

    python3 -I research/hunt/h31/extra.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import test as T  # noqa: E402

pd.set_option("display.width", 220)


def detail(w, vid, lo, hi, sess):
    U, K, X, R, F = vid.split("|")
    W = w[(w.day >= lo) & (w.day < hi)]
    t = T.pick(W, R, F, K, X, U)
    t["ym"] = [f"{d.year}-{d.month:02d}" for d in t.day]
    mon = t.groupby("ym").net.sum()
    rng = np.random.default_rng(7)
    # bootstrap a month: resample the variant's daily nets (21 sessions, trading on the share of sessions it trades)
    per_sess = np.zeros(sess)
    per_sess[:len(t)] = t.net.values
    sims = rng.choice(per_sess, size=(20000, 21)).sum(axis=1)
    yr = t.groupby([d.year for d in t.day]).net.agg(["size", "sum"])
    return dict(vid=vid, n=len(t), net=t.net.sum(), gross=t.gross.sum(), per_day=t.net.sum() / sess,
                worst_night=t.net.min(), best_night=t.net.max(), mdd=T.mdd(t.net.values),
                lose_month_obs=(mon < 0).mean(), lose_month_boot=(sims < 0).mean(), worst_month=mon.min(),
                prem_med=t.prem.median(), prem_max=t.prem.max()), yr, mon


def main():
    f, w = T.load()
    out = []
    lines = []
    for vid in ("NIFTY|W|GX|R17|F0", "NIFTY|W|X1015|R04|F0", "NIFTY|W|X1015|R17|F0", "BANKNIFTY|M|P15|R17|F0",
                "NIFTY|W|X0916|R01|F0", "NIFTY|M|X0916|R01|F0"):
        U = vid.split("|")[0]
        for nm, lo, hi in (("PRE", pd.Timestamp("2000-01-01").date(), T.HOLD), ("HOLD", T.HOLD, pd.Timestamp("2100-01-01").date())):
            W = w[(w.day >= lo) & (w.day < hi) & (w.und == U)]
            sess = W.day.nunique()
            d, yr, mon = detail(w, vid, lo, hi, sess)
            d["period"] = nm
            d["sessions"] = sess
            out.append(d)
            lines.append(f"{vid} {nm} per year:\n{yr.round(0).to_string()}")
            if nm == "HOLD":
                lines.append(f"{vid} HOLD per month:\n{mon.round(0).to_string()}")
    D = pd.DataFrame(out)
    D["lots_5k"] = np.where(D.per_day > 0, np.ceil(5000 / D.per_day), np.nan)
    D["prem_at_lots"] = D.lots_5k * D.prem_med
    D["mdd_at_lots"] = D.lots_5k * D.mdd
    lines.insert(0, D.round(3).to_string())
    # breadth look-ahead check: R17 with the 15:19 breadth vs the daily-close breadth, on days that have both
    g = f[f.BR_min.notna()].copy()
    for col in ("BR_min", "BR_daily"):
        pass
    br_daily = g.BR.copy()
    lines.append(f"breadth rows with minute data: {len(g)} (from {g.day.min()})")
    # trades by year x R17 vs R04 per index (PRE + HOLD), the trend of the edge
    rows = []
    for R in ("R03", "R04", "R17"):
        for U in T.UNDS:
            for K in T.KS:
                t = T.pick(w, R, "F0", K, "GX", U)
                for y, g2 in t.groupby([d.year for d in t.day]):
                    rows.append(dict(R=R, und=U, K=K, year=y, n=len(g2), net=g2.net.sum(), cf=g2.cf_mu.sum()))
    Y = pd.DataFrame(rows)
    Yp = Y.groupby(["R", "year"])[["n", "net", "cf"]].sum()
    lines.append("GX exit, all indices and contracts, net and coin-flip by year (2025 includes the holdout from Oct):\n" + Yp.round(0).to_string())
    Yu = Y[Y.R == "R04"].pivot_table(index=["und", "K"], columns="year", values="net", aggfunc="sum")
    lines.append("R04 strong close, GX, net by index/contract/year:\n" + Yu.round(0).to_string())
    s = "\n\n".join(lines)
    open(os.path.join(T.OUT, "extra.log"), "w").write(s)
    print(s)


if __name__ == "__main__":
    main()
