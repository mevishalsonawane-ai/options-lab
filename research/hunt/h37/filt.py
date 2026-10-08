"""h37 filter study: every h37 signal x params x timeframe as a filter on the app's Liquidity 15+5 trades (h25's trade
file: 5 indices, 1-ITM near, arm exits, app fills + charges; real spread re-applied with h37's spreads).

    OBUY_CACHE=<scratch>/hunt/h37/cache python3 -I research/hunt/h37/filt.py screen     (pre-holdout only)
    OBUY_CACHE=<scratch>/hunt/h37/cache python3 -I research/hunt/h37/filt.py hold       (passers, holdout once)
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import norm  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.overfit import bh  # noqa: E402
import evaluate as EV  # noqa: E402
import sigs  # noqa: E402

LIQ = os.path.join(C.SCRATCH, "hunt/h25/liq_trades.parquet")


def base_trades():
    tr = pd.read_parquet(LIQ)
    hs = tr.und.map(EV.HS).values
    sb = (tr.entry.values + tr.exit.values) * tr.qty.values
    tr["net_real"] = tr.net.values - hs * sb
    tr["net_stress"] = tr.net.values - EV.STRESS * hs * sb
    return tr.reset_index(drop=True)


def states(tr, mk):
    out = {}
    for u, g in tr.groupby("und"):
        ix = mk.index(u)
        cfgs = sigs.all_events(ix, EV.vp_weights(u, ix))
        idx = g.index.values
        tord = g.ord.values.astype(np.int64)
        tcol = (g.sig_min.values - EV.SM0).astype(np.int64)
        tkey = tord * 1000 + tcol
        for c in cfgs:
            if not len(c["ord"]):
                out.setdefault(EV.key_of(c), np.zeros(len(tr), np.int8))
                continue
            key = c["ord"].astype(np.int64) * 1000 + (c["mi"].astype(np.int64) if c["tf"] != "D" else -1)
            o = np.argsort(key, kind="stable")
            key, side, eord, emi = key[o], c["side"][o], c["ord"][o], c["mi"][o]
            pos = np.searchsorted(key, tkey, side="right") - 1
            ok = pos >= 0
            p = np.maximum(pos, 0)
            same = ok & (eord[p] == tord)
            if c["tf"] == "D" or c["sig"][:3] in ("MP_", "VP_", "SQ9"):
                live = same
            elif c["tf"] == 1:
                live = same & (tcol - emi[p] < 15)
            else:
                live = same & (tcol - emi[p] < 3 * int(c["tf"]))
            st = np.where(live, side[p], 0).astype(np.int8)
            k = EV.key_of(c)
            arr = out.setdefault(k, np.zeros(len(tr), np.int8))
            arr[idx] = st
        mk.release(u)
    return out


def screen(hold=False):
    mk = market()
    tr = base_trades()
    st = states(tr, mk)
    pre = tr.ord.values < EV.HOLD
    rows = []
    for base in ("POOL", "BANKNIFTY"):
        bm = np.ones(len(tr), bool) if base == "POOL" else (tr.und.values == "BANKNIFTY")
        for (sig, tf, par), s in st.items():
            for mode in ("KEEP_AGREE", "VETO_OPPOSE"):
                keep = (s == tr.side.values) if mode == "KEEP_AGREE" else (s != -tr.side.values)
                for per, pm in (("pre", pre), ("hold", ~pre)):
                    if per == "hold" and not hold:
                        continue
                    m = bm & pm
                    x = tr.net_real.values[m]
                    k = keep[m]
                    N, nk = len(x), int(k.sum())
                    row = dict(base=base, sig=sig, tf=tf, par=par, mode=mode, per=per, N=N, nk=nk, base_mean=x.mean(),
                               base_tot=x.sum(), kept_mean=x[k].mean() if nk else np.nan, kept_tot=x[k].sum(),
                               kept_stress=tr.net_stress.values[m][k].sum(), kept_gross=tr.gross.values[m][k].sum(),
                               base_gross=tr.gross.values[m].sum())
                    if 5 <= nk < N:
                        var = x.var() / nk * (N - nk) / (N - 1)
                        z = (row["kept_mean"] - x.mean()) / np.sqrt(var) if var > 0 else 0.0
                        row["p"] = float(norm.sf(z))
                    else:
                        row["p"] = 1.0
                    yrs = tr.year.values[m]
                    up = tot = 0
                    for y in np.unique(yrs):
                        ym = yrs == y
                        if (k & ym).sum() >= 5:
                            tot += 1
                            up += x[k & ym].mean() > x[ym].mean()
                    row["yrs_up"], row["yrs"] = up, tot
                    rows.append(row)
    df = pd.DataFrame(rows)
    P = df[df.per == "pre"].copy()
    P["q"] = bh(P.p.values)
    P["pass"] = (P.q < 0.05) & (P.yrs_up * 2 > P.yrs) & (P.kept_tot >= 0.8 * P.base_tot)
    P.to_parquet(os.path.join(EV.OUT, "filt_pre.parquet"))
    pd.set_option("display.width", 220)
    print("filters", len(P), "pass", int(P["pass"].sum()), "min q", round(P.q.min(), 4))
    print("base pre:", P.groupby("base")[["N", "base_tot", "base_mean"]].first().round(1).to_string())
    print(P.sort_values("p").head(20).round(4).to_string())
    if hold:
        H = df[df.per == "hold"].merge(P[["base", "sig", "tf", "par", "mode", "pass", "q", "p"]],
                                       on=["base", "sig", "tf", "par", "mode"], suffixes=("", "_pre"))
        H.to_parquet(os.path.join(EV.OUT, "filt_hold.parquet"))
        print("HOLDOUT base:", H.groupby("base")[["N", "base_tot", "base_mean"]].first().round(1).to_string())
        print(H[H["pass"]].round(4).to_string())


if __name__ == "__main__":
    t0 = time.time()
    screen(sys.argv[1] == "hold")
    print(f"done {time.time() - t0:.0f}s")
