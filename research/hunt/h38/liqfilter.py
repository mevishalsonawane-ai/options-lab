"""h38 Part C: futures / basis features as FILTERS on the app's Liquidity 15+5 trades (PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/liqfilter.py pre
    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/liqfilter.py hold <filter name> [...]
    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/liqfilter.py partB

Trades: h4's Liquidity 15+5 (liq_bnfin + liq_ext), 1 lot, 1-ITM nearest; net = bar-print gross - app charges - the h24
half-spread x (entry + exit). Each feature is read at the close of the signal minute (daily ones: previous session) and
signed toward the trade. Evaluation = h26's (random skip of the same count, BH).
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
from obuy.data import market  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import feats as FT  # noqa: E402
from obuy.costs import Costs  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
SPLIT = pd.Timestamp("2024-01-01")
OUTD = os.path.join(C.SCRATCH, "hunt", "h38")
pd.set_option("display.width", 250)
HS = {"BANKNIFTY": .0016, "NIFTY": .0016, "MIDCPNIFTY": .0021, "FINNIFTY": .0042, "SENSEX": .0016}


def load_trades():
    """h4 Liquidity 15+5 trades, net = gross - app charges - h24 half-spread x (entry + exit)  (same as h26)."""
    t = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/trades_gross.parquet"))
    t = t[t.comp.isin(["liq_bnfin", "liq_ext"])].copy()
    t["day"] = pd.to_datetime(t.day)
    cs = Costs()
    bse = (t.und == "SENSEX").values
    ch = np.zeros(len(t))
    for b in (False, True):
        m = bse == b
        ch[m] = cs.charge(True, t.entry.values[m], t.qty.values[m], bse=b) + cs.charge(False, t.exit.values[m], t.qty.values[m], bse=b)
    spread = t.und.map(HS).values * (t.entry.values + t.exit.values) * t.qty.values
    t["gross_p"] = t.gross.values
    t["net"] = t.gross.values - ch - spread
    t["net15"] = t.net - 0.5 * spread
    t["col"] = t.sig_min - C.OPEN_M
    return t.reset_index(drop=True)


def ndays_fn(cal):
    def f(tt):
        lo, hi = tt.day.min(), tt.day.max()
        return max(int(((cal >= lo) & (cal <= hi)).sum()), 1)
    return f

# name -> (source, key, kind); kind z: |x| >= 1, s: state (+-1)
FEATS_A = {"BASIS5": ("syn", "db5_z", "z"), "BASIS15": ("syn", "db15_z", "z"), "BASIS30": ("syn", "db30_z", "z"),
           "BASISopen": ("syn", "dbopen_z", "z"), "DBU": ("daily", "DBU_all", "s"), "DVOL": ("daily", "DVOL_1.5", "s"),
           "DDIS": ("daily", "DDIS_agree", "s"), "DBAS": ("daily", "dbas_z", "z")}
FEATS_B = {"FBU15": ("state", ("FBU", (15, "all")), "s"), "FBUopen": ("state", ("FBU", ("open", "all")), "s"),
           "FVOL": ("state", ("FVOL", 3.0), "s"), "FBAS": ("fut", "fbas15_z", "z"), "FDIS": ("state", ("FDIS", 1.0), "s")}


def attach(t, FEATS):
    for nm, (src, key, kind) in FEATS.items():
        vals = np.full(len(t), np.nan)
        for u in t.und.unique():
            m = (t.und == u).values
            idx = np.flatnonzero(m)
            try:
                if src == "daily":
                    if u not in FT.UNDS_DAILY:
                        continue
                    S = FT.daily(u)[key]
                    S.index = pd.to_datetime(S.index)
                    vals[idx] = t.day.iloc[idx].map(S).values
                    continue
                if src == "syn":
                    days, D = FT.syn(u)
                    A = D[key]
                elif src == "fut":
                    if u not in FT.UNDS_B:
                        continue
                    days, D = FT.fut(u)
                    A = D[key]
                else:
                    if u not in FT.UNDS_B:
                        continue
                    days, A = FT.state(key[0], u, key[1])
            except FileNotFoundError:
                continue
            pos = {pd.Timestamp(d): i for i, d in enumerate(days)}
            r = t.day.iloc[idx].map(pos)
            ok = r.notna().values
            vals[idx[ok]] = A[r[ok].astype(int).values, t.col.values[idx[ok]]]
        t[nm] = vals * t.side.values
    return t


def evaluate(t, FEATS, ndays, B=2000, seed=11, only=None):
    rng = np.random.default_rng(seed)
    rows = []
    for nm, (_, _, kind) in FEATS.items():
        tt = t[t[nm].notna().values]
        for sense in ("opp", "agree"):
            fname = f"{nm}:{'skip_opposed' if sense == 'opp' else 'skip_agreeing'}"
            if only is not None and fname not in only:
                continue
            x = tt[nm].values
            sk = (x <= -1.0) if sense == "opp" else (x >= 1.0)
            m = int(sk.sum())
            sk_net = tt.net.values[sk].sum()
            if 0 < m < len(tt):
                draws = np.array([tt.net.values[rng.choice(len(tt), m, replace=False)].sum() for _ in range(B)])
                p = (1 + (draws <= sk_net).sum()) / (B + 1)
            else:
                p = 1.0
            nd = ndays(tt) if len(tt) else 1
            rows.append(dict(filter=fname, n=len(tt), skipped=m, base_day=tt.net.sum() / nd, d_day=-sk_net / nd,
                             d_day15=-tt.net15.values[sk].sum() / nd, d_gross_day=-tt.gross_p.values[sk].sum() / nd,
                             skipped_avg=tt.net.values[sk].mean() if m else np.nan, p=p))
    R = pd.DataFrame(rows)
    R["bh"] = OF.bh(R.p.values)
    return R


def main(mode, names=()):
    t = load_trades()
    cal = pd.Series(pd.to_datetime(market().index("NIFTY").days))
    nd = ndays_fn(cal)
    if mode == "partB":
        t = t[t.day >= pd.Timestamp(FT.B_START)].reset_index(drop=True)
        t = attach(t, FEATS_B)
        print("Liquidity trades in the futures-minute window:", len(t), t.groupby("und").size().to_dict())
        R = evaluate(t, FEATS_B, nd)
        print(R.round(3).to_string())
        R.to_csv(os.path.join(OUTD, "liqfilter_partB.csv"), index=False)
        return
    t = attach(t, FEATS_A)
    if mode == "pre":
        tp = t[t.day < HOLD]
        print("Liquidity trades pre-holdout:", len(tp), tp.groupby("und").size().to_dict())
        print(f"unfiltered net Rs/day {tp.net.sum() / nd(tp):.0f}, gross {tp.gross_p.sum() / nd(tp):.0f}")
        R = evaluate(tp, FEATS_A, nd)
        halves = []
        for lo, hi in ((pd.Timestamp("2000-01-01"), SPLIT), (SPLIT, HOLD)):
            x = evaluate(tp[(tp.day >= lo) & (tp.day < hi)], FEATS_A, nd, B=200)
            halves.append(x.set_index("filter").d_day)
        R["d_day_h1"] = halves[0].reindex(R["filter"]).values
        R["d_day_h2"] = halves[1].reindex(R["filter"]).values
        R["adopt"] = (R.bh < 0.10) & (R.d_day > 0) & (R.d_day15 > 0) & (R.d_day_h1 > 0) & (R.d_day_h2 > 0)
        print(R.round(3).to_string())
        R.to_csv(os.path.join(OUTD, "liqfilter_pre.csv"), index=False)
    else:
        th = t[t.day >= HOLD]
        print("Liquidity trades holdout:", len(th), th.groupby("und").size().to_dict())
        print(f"unfiltered net Rs/day {th.net.sum() / nd(th):.0f}, gross {th.gross_p.sum() / nd(th):.0f}")
        R = evaluate(th, FEATS_A, nd, only=set(names))
        print(R.round(3).to_string())
        R.to_csv(os.path.join(OUTD, "liqfilter_hold.csv"), index=False)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
