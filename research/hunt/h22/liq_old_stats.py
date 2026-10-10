"""h22: the long-run record of Liquidity 15+5 under its 1-Oct-morning rules (liq_old.py), with the honest protocol:
pre-holdout (to 2025-09-30) vs the locked holdout (2025-10-01 .. 2026-10-06), per year, Rs/day, worst day/month, max
drawdown, block-bootstrap P(losing month) and P(mean <= 0), and a random-entry baseline with identical exits
(same day and book, random minute 09:20-14:30 and random side, the real signal's target distance and exit_at delay).
Nothing here was tuned: the rules are the app's as they stood on 1 Oct.

    flock <scratch>/obuy.lock python3 -I research/hunt/h22/liq_old_stats.py
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h19"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
sys.path.insert(0, HERE)

import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
import sim as H19  # noqa: E402
import comps  # noqa: E402
import liq_old as LO  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h22")
HOLD = date(2025, 10, 1)
NDRAW = 30


def block_boot(daily, B=5000, block=10, seed=11):
    x = np.asarray(daily, float); n = len(x)
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(n / block))
    st = rng.integers(0, n - block + 1, (B, nb))
    idx = (st[:, :, None] + np.arange(block)).reshape(B, -1)[:, :n]
    return x[idx]


def describe(tr, days):
    d = tr.groupby("day").net.sum().reindex(days, fill_value=0.0)
    eq = d.cumsum().values
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())
    mo = d.groupby([x.strftime("%Y-%m") for x in pd.to_datetime(d.index)]).sum()
    bs = block_boot(d.values)
    # P(losing month): 21-day blocks drawn from the bootstrap paths
    m21 = bs[:, : (len(d) // 21) * 21].reshape(len(bs), -1, 21).sum(2)
    return dict(trades=int(len(tr)), days=len(days), net=round(float(tr.net.sum())), gross=round(float(tr.gross.sum())),
                charges=round(float(tr.charges.sum())), rs_day=round(float(d.mean())),
                t=round(float(d.mean() / (d.std(ddof=1) / np.sqrt(len(d)))), 2), win_trades=round(float((tr.net > 0).mean()), 3),
                worst_day=round(float(d.min())), best_day=round(float(d.max())), worst_month=round(float(mo.min())),
                green_months=f"{int((mo > 0).sum())}/{len(mo)}", max_dd=round(dd),
                p_mean_le0=round(float((bs.mean(1) <= 0).mean()), 4), p_losing_month=round(float((m21 < 0).mean()), 3))


def main():
    mk = market()
    for u in ("BANKNIFTY", "FINNIFTY"):
        H19.supplement(mk, u)
    comps.WIN_TO = 14 * 60 + 30
    sig = comps.liq_ext_signals(mk, unds=("BANKNIFTY", "FINNIFTY"), room=0.0)
    sig["book"] = sig.book.str.replace("_BANKNIFTY", "_BN").str.replace("_FINNIFTY", "_FIN")
    ixc, closes = {}, {}
    for u in ("BANKNIFTY", "FINNIFTY"):
        ix = mk.index(u)
        for d in ix.days:
            x = ix.d[d]
            a = [None] * C.W; c = np.full(C.W, np.nan)
            for m, hh, ll, cc in zip(x["m"], x["h"], x["l"], x["c"]):
                j = int(m) - C.OPEN_M
                if 0 <= j < C.W:
                    a[j] = (float(hh), float(ll)); c[j] = float(cc)
            ixc[(u, d)] = a; closes[(u, d)] = pd.Series(c).ffill().values
    real = LO.run(mk, sig, ixc)
    print("real", len(real), real.net.sum(), flush=True)
    days = sorted(d for d in mk.index("BANKNIFTY").days if d >= min(real.day))
    pre_days = [d for d in days if d < HOLD]; hold_days = [d for d in days if d >= HOLD]
    res = {"pre": describe(real[real.day < HOLD], pre_days), "holdout": describe(real[real.day >= HOLD], hold_days),
           "all": describe(real, days)}
    res["per_year"] = real.assign(y=[d.year for d in real.day]).groupby("y").net.sum().round().to_dict()
    # random entries with identical exits
    rng = np.random.default_rng(22)
    sig_d = sig.copy(); sig_d["dd"] = [pd.Timestamp(x).date() for x in sig_d.day]
    rnd_pre, rnd_hold = [], []
    for b in range(NDRAW):
        print("draw", b, flush=True)
        r = sig_d.copy()
        g = rng.integers(565, 871, len(r))
        side = rng.choice([-1, 1], len(r))
        ref = np.array([closes[(u, d)][gg - 1 - C.OPEN_M] for u, d, gg in zip(r.und, r.dd, g)])
        tdist = (r.idx_target - r.ref_spot).abs().values
        xdel = (r.exit_at - r.gate).values
        r["gate"] = g; r["sig_min"] = g - 1; r["side"] = side; r["ref_spot"] = ref
        r["idx_target"] = np.where(np.isnan(tdist), np.nan, ref + side * tdist)
        r["exit_at"] = np.where(np.isnan(xdel), np.nan, g + xdel)
        t = LO.run(mk, r.drop(columns=["dd"]), ixc)
        rnd_pre.append(float(t[t.day < HOLD].net.sum())); rnd_hold.append(float(t[t.day >= HOLD].net.sum()))
    res["random"] = dict(draws=NDRAW, pre_mean=round(float(np.mean(rnd_pre))), hold_mean=round(float(np.mean(rnd_hold))),
                         p_pre=round(float(np.mean(np.array(rnd_pre) >= res["pre"]["net"])), 3),
                         p_hold=round(float(np.mean(np.array(rnd_hold) >= res["holdout"]["net"])), 3))
    print(json.dumps(res, indent=1, default=str))
    json.dump(res, open(os.path.join(HERE, "liq_old_stats.json"), "w"), indent=1, default=str)


if __name__ == "__main__":
    main()
