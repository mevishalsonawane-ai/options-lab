"""h11 stage 1 (heavy, one data pass): signals -> packs -> every PREREG variant's trades under h10's capacity model.

    OBUY_CACHE=<scratch>/hunt/h11/cache flock <scratch>/obuy.lock python3 -I research/hunt/h11/build.py sig
    OBUY_CACHE=<scratch>/hunt/h11/cache flock <scratch>/obuy.lock python3 -I research/hunt/h11/build.py packs

Writes (all dates; pre.py only reads < 2025-10-01, hold.py reads the holdout once):
  <cache>/h11/raw_signals.pkl, trades1.parquet (1 lot, kappa 0.02), pool1.parquet (random entries, 1 lot),
  daily.parquet (net & gross per day for every variant x lots x kappa), capv.parquet (exit 5-min lots per trade),
  bnplan.parquet (h10 plan daily net/gross, limits none/both, kappa 0.01/0.02/0.04).
"""
from __future__ import annotations

import dataclasses
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h10"))
import cap  # noqa: E402  (imports h7 sim, obuy)
from cap import sim  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, StrikeRule, positions, prepare_many  # noqa: E402
import sig as SG  # noqa: E402

OUT = os.path.join(C.CACHE, "h11")
H4 = os.path.join(C.SCRATCH, "hunt/h4/cache/h4")
ISTOP = {"NIFTY": 15.0, "SENSEX": 50.0}
UNDS = ("NIFTY", "SENSEX")
WINDOW = (9 * 60 + 19, 14 * 60 - 1)
LOTS = [1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500]
KAPPAS = [0.01, 0.02, 0.04]
CAL = pd.read_csv(os.path.join(H4, "daily_real.csv"), index_col=0, parse_dates=True).index
EXE_REAL = Execution(expiry="skip", fills=Fills(mode="liq"), costs=Costs())


class BseCosts(Costs):
    def charge(self, buy, price, qty, dn=None, bse=True):
        return super().charge(buy, price, qty, dn, True)


EXE_NSE = cap.EXE
EXE_BSE = dataclasses.replace(cap.EXE, costs=BseCosts())


def variants():
    """PREREG grid: 16 core (near) + 8 monthly (room 1) = 24 per index."""
    V = []
    for books in ((15, 5), (30, 5)):
        for sc in (1, 2):
            for room in (1, 2):
                for money in (0, 1):
                    V.append(dict(books=books, scale=sc, room=room, money=money, series="near"))
    for books in ((15, 5), (30, 5)):
        for sc in (1, 2):
            for money in (0, 1):
                V.append(dict(books=books, scale=sc, room=1, money=money, series="month"))
    for i, v in enumerate(V):
        v["vid"] = "v%02d_%s_s%d_r%d_%s_%s" % (i, "".join(map(str, v["books"])), v["scale"], v["room"],
                                                "ATM" if v["money"] == 0 else "ITM1", v["series"])
    return V


def stage_sig():
    os.makedirs(OUT, exist_ok=True)
    t0 = time.time()
    raw = SG.raw_signals(market())
    raw.to_pickle(os.path.join(OUT, "raw_signals.pkl"))
    print("raw signals", raw.groupby(["und", "tf"]).size().to_dict(), round(time.time() - t0), flush=True)
    # parity with h4's port (15+5, 1x stop, room 1)
    h4 = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    h4 = h4[h4.und.isin(UNDS)]
    r = raw[raw.tf.isin((15, 5))].copy()
    r = r[r.dist >= r.und.map(ISTOP)]
    k = ["und", "day", "sig_min", "side", "book"]
    a = h4[k].astype(str).agg("|".join, axis=1).sort_values().values
    b = r[k].astype(str).agg("|".join, axis=1).sort_values().values
    print("parity vs h4 liq_ext NIFTY/SENSEX:", len(a), len(b), "identical" if (len(a) == len(b) and (a == b).all()) else "DIFF",
          flush=True)


def union(raw):
    out = []
    for sc in (1, 2):
        s = raw.copy()
        stop = s.und.map(ISTOP).values * sc
        s = s[s.dist.values >= stop].copy()
        stop = s.und.map(ISTOP).values * sc
        s["scale"] = sc
        s["room_stops"] = s.dist.values / stop
        s["idx_stop"] = s.level.values - s.side.values * stop
        s["strike"] = np.nan
        out.append(s)
    return pd.concat(out, ignore_index=True)


def bn_signals():
    a = pd.read_pickle(os.path.join(H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    return pd.concat([a, b[b.und == "MIDCPNIFTY"]], ignore_index=True)


def tr_cols(b, o):
    t = b.tr[["cand", "und", "book", "day", "entry_min", "exit_min", "lot", "why", "entry"]].copy()
    if "parent" in b.tr:
        t["parent"] = b.tr.parent.values
    for col in o.columns:
        t[col] = o[col].values
    return t


def dser(t, col):
    return t.groupby("day")[col].sum().reindex(CAL, fill_value=0.0)


def stage_packs():
    U = union(pd.read_pickle(os.path.join(OUT, "raw_signals.pkl")))
    U = U.reset_index(drop=True)
    print("union signals", len(U), U.groupby(["und", "scale"]).size().to_dict(), flush=True)
    combos = [(0, "near"), (1, "near"), (0, "month"), (1, "month")]
    jobs = [(U, StrikeRule(money=m, series=s), EXE_REAL, 5, WINDOW, False) for m, s in combos]
    jobs.append((bn_signals(), StrikeRule(money=1), EXE_REAL, 0, WINDOW, False))
    t0 = time.time()
    packs = prepare_many(jobs)
    print("packs", round(time.time() - t0), {k: v.shape for k, v in packs[0][0].store.arr.items()}, flush=True)
    attrs = U[["tf", "scale", "room_stops"]]
    T1, P1, CV, D = [], [], [], {}
    for (money, series), (pk, pool) in zip(combos, packs[:4]):
        tr = sim.base_trades(pk, cap.EXE, pos=False)
        tr = tr.join(attrs, on="cand")
        tp = sim.base_trades(pool, cap.EXE, pos=False)
        for v in variants():
            if (v["money"], v["series"]) != (money, series):
                continue
            for und in UNDS:
                cap.EXE = EXE_BSE if und == "SENSEX" else EXE_NSE
                m = (tr.und == und) & tr.tf.isin(v["books"]) & (tr.scale == v["scale"]) & (tr.room_stops >= v["room"] - 1e-9)
                tv = positions(tr[m].copy(), one_at_a_time=True)
                key = f"{v['vid']}|{und}"
                b = cap.Book(pk, tv)
                o = cap.simulate(b, np.ones(len(tv)), 0.0, cap.CAP, 0.02)
                t = tr_cols(b, o).assign(key=key)
                t["exit_v5"] = b.V5[np.arange(len(tv)), tv.xcol.values]
                t["entry_v5"] = b.V5[np.arange(len(tv)), tv.c0.values]
                T1.append(t)
                tpv = tp[tp.parent.isin(tv.cand.values)]
                bp = cap.Book(pool, tpv)
                op = cap.simulate(bp, np.ones(len(tpv)), 0.0, cap.CAP, 0.02)
                P1.append(tr_cols(bp, op)[["parent", "day", "net", "gross"]].assign(key=key))
                for k in KAPPAS:
                    for n in LOTS:
                        o = cap.simulate(b, np.full(len(tv), float(n)), 0.0, cap.CAP, k)
                        t = tr_cols(b, o)
                        D[f"{key}|{n}|{k}|net"] = dser(t, "net").values
                        D[f"{key}|{n}|{k}|gross"] = dser(t, "gross").values
                        D[f"{key}|{n}|{k}|prem"] = t.groupby("day").prem.max().reindex(CAL, fill_value=0.0).values
                print(time.strftime("%H:%M:%S"), key, len(tv), "trades", flush=True)
        cap.EXE = EXE_NSE
    pd.concat(T1, ignore_index=True).to_parquet(os.path.join(OUT, "trades1.parquet"))
    pd.concat(P1, ignore_index=True).to_parquet(os.path.join(OUT, "pool1.parquet"))
    pd.DataFrame(D, index=CAL).to_parquet(os.path.join(OUT, "daily.parquet"))
    # h10 BANKNIFTY plan (flat BN 26 / FIN 3 / MIDCP 11), limits none / both, kappa 0.01/0.02/0.04
    pk = packs[4][0]
    tr = sim.base_trades(pk, cap.EXE)
    B = {u: cap.Book(pk, tr[tr.und == u]) for u in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")}
    N = dict(BANKNIFTY=26, FINNIFTY=3, MIDCPNIFTY=11)
    BP = {}
    for k in KAPPAS:
        t = pd.concat([tr_cols(B[u], cap.simulate(B[u], np.full(len(B[u].tr), float(N[u])), 0.0, cap.CAP, k))
                       for u in N], ignore_index=True)
        for lim, (ld, lm) in (("none", (None, None)), ("both", (75000.0, 250000.0))):
            keep = cap.gate(t, ld, lm)
            for col in ("net", "gross"):
                BP[f"{lim}|{k}|{col}"] = dser(t[keep], col).values
        for u in N:
            BP[f"{u}|{k}|net"] = dser(t[t.und == u], "net").values
    BPd = pd.DataFrame(BP, index=CAL)
    BPd.to_parquet(os.path.join(OUT, "bnplan.parquet"))
    reg = BPd[(BPd.index >= "2024-12-01") & (BPd.index < "2025-10-01")]
    print("h10 plan check, regime-pre net/day (h10: 4928.43):", round(reg["none|0.02|net"].mean(), 2), flush=True)
    print("done", round(time.time() - t0), flush=True)


if __name__ == "__main__":
    {"sig": stage_sig, "packs": stage_packs}[sys.argv[1]]()
