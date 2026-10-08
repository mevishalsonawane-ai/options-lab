"""h46 version (c): signals on the near-month FUTURES chart (futures candles, EMA9, futures-volume VWAP), trades on the
index options. Futures minutes exist only 2026-07-29 .. 2026-10-06 = INSIDE the locked holdout -> descriptive only.

    flock <scratch>/obuy.lock python3 -I research/hunt/h46/fut.py
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import sim  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402

FD = os.path.join(C.SCRATCH, "hunt/h38/data")


def run(u):
    mk = market()
    ix = mk.index(u)
    f = pd.read_parquet(os.path.join(FD, f"futmin_{u}.parquet"))
    f["day"] = pd.to_datetime(f.day).dt.date
    f["expiry"] = pd.to_datetime(f.expiry).dt.date
    near = f.groupby("day").expiry.min()
    f = f[f.expiry.values == near.reindex(f.day).values]
    days = [d for d in sorted(f.day.unique()) if d in ix.pos]
    D = len(days)
    di = {d: i for i, d in enumerate(days)}
    M = {k: np.full((D, C.W), np.nan) for k in ("o", "h", "l", "c")}
    V = np.zeros((D, C.W))
    f = f[f.day.isin(di)]
    r = f.day.map(di).values
    col = f.col.values.astype(np.int64)
    ok = (col >= 0) & (col < C.W)
    for k, s in (("o", "open"), ("h", "high"), ("l", "low"), ("c", "close")):
        M[k][r[ok], col[ok]] = f[s].values[ok]
    V[r[ok], col[ok]] = f.volume.values[ok]
    exps = np.array([ix.d[d]["exp"] for d in days])
    lots = np.array([ix.lot(d) for d in days])
    df = sim.gen(u, days, M, {"FUT": V}, exps)
    orc = sim.Oracle(mk, u, days, np.ones(D, bool))
    IM = ix.mat()
    spot = IM["c"][[ix.pos[days[i]] for i in df.dpos.values], df.sig.values]
    parts = []
    for money in (0, 1):
        e, x, g, ch, sp, net = sim.price_trades(orc, u, df.dpos.values, spot, df.side.values, money, df.ent.values,
                                                df.ext.values, lots[df.dpos.values], u in C.BSE)
        parts.append(df.assign(money=money, e=e, x=x, gross=g, chg=ch, spr=sp, net=net, lot=lots[df.dpos.values]))
    tr = pd.concat(parts, ignore_index=True)
    tr = tr[np.isfinite(tr.net)].reset_index(drop=True)
    tr["day"] = [days[i] for i in tr.dpos.values]
    tr["und"] = u
    tr.drop(columns=["dpos"]).to_parquet(os.path.join(sim.OUT, f"trades_{u}_fut.parquet"), compression="zstd")
    print(u, "fut days", D, "trades", len(tr), flush=True)
    return tr, days, exps


if __name__ == "__main__":
    rows = []
    for u in ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]:
        tr, days, exps = run(u)
        for key, t in tr.groupby(["tf", "rej", "xm", "money"]):
            for mx in (3, 5, 99):
                for ex in ("skip", "allow"):
                    v = t[(t.k < mx) & ((ex == "allow") | ~t.exp)]
                    nd = len(days) if ex == "allow" else int((~exps).sum())
                    rows.append(dict(und=u, tf=key[0], rej=key[1], xm=key[2], money=key[3], mx=mx, ex=ex, n=len(v),
                                     days=nd, win=(v.net > 0).mean(), gross_t=v.gross.mean(), net_t=v.net.mean(),
                                     gross_d=v.gross.sum() / nd, net_d=v.net.sum() / nd))
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(sim.OUT, "fut_variants.csv"), index=False)
    pd.set_option("display.width", 250)
    print("variants", len(R), "net>0", int((R.net_d > 0).sum()), "gross>0", int((R.gross_d > 0).sum()))
    print(R.groupby(["und"])[["win", "gross_t", "net_t", "net_d"]].mean().round(1).to_string())
    print(R.groupby(["tf"])[["win", "gross_t", "net_t", "net_d"]].mean().round(1).to_string())
    pm = R[(R.tf == 5) & (R.rej == "R1") & (R.xm == "CLOSE") & (R.money == 0) & (R.mx == 99) & (R.ex == "skip")]
    print(pm.to_string())
