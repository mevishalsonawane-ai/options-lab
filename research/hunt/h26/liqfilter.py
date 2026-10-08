"""h26: volume / OI features as FILTERS on the app's Liquidity 15+5 trades (PREREG.md, 24 pre-registered filters).

    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/liqfilter.py pre
    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/liqfilter.py hold <filter name> [...]

Trades: h4's Liquidity 15+5 (liq_bnfin = BANKNIFTY/FINNIFTY validated port, liq_ext = NIFTY/SENSEX/MIDCPNIFTY), 1 lot,
1-ITM nearest, prices = bar prints (trades_gross), net = gross - app charges - measured half-spread x (entry + exit).
A feature is read at the close of the signal minute (column sig_min - 555), i.e. known before the next-minute entry.
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
from obuy.costs import Costs  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import feats as FT  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
SPLIT = pd.Timestamp("2024-01-01")
HS = {"BANKNIFTY": .0016, "NIFTY": .0016, "MIDCPNIFTY": .0021, "FINNIFTY": .0042, "SENSEX": .0016}
OUTD = os.path.join(C.SCRATCH, "hunt", "h26")
pd.set_option("display.width", 250)

# feature name -> (source, column, kind); kind z: threshold 1, b: bounded threshold 0.3, s: state (+-1)
FEATS = {"BU15": ("opt", "BU15_z", "z"), "BUopen": ("opt", "BUopen_z", "z"), "dOIpc2": ("opt", "dOIpc2_z", "z"),
         "dOIpc5": ("opt", "dOIpc5_z", "z"), "WALL": ("state", ("WALL", 1), "s"), "VSPIKE": ("state", ("VSPIKE", 3.0), "s"),
         "VIMB": ("opt", "VIMB_z", "z"), "PFLOW": ("opt", "PFLOW_z", "z"), "HVSURGE": ("cons", "HVSURGE", "b"),
         "VWB": ("cons", "VWB", "b"), "VWAP": ("cons", "VWAPSH", "b"), "LEADLAG": ("cons", "B3_z", "z")}


def load_trades():
    t = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/trades_gross.parquet"))
    t = t[t.comp.isin(["liq_bnfin", "liq_ext"])].copy()
    t["day"] = pd.to_datetime(t.day)
    cs = Costs()
    bse = (t.und == "SENSEX").values
    ch = np.zeros(len(t))
    for b in (False, True):
        m = bse == b
        ch[m] = cs.charge(True, t.entry.values[m], t.qty.values[m], bse=b) + cs.charge(False, t.exit.values[m], t.qty.values[m], bse=b)
    hs = t.und.map(HS).values
    spread = hs * (t.entry.values + t.exit.values) * t.qty.values
    t["gross_p"] = t.gross.values
    t["net"] = t.gross.values - ch - spread
    t["net15"] = t.net - 0.5 * spread
    t["col"] = t.sig_min - C.OPEN_M
    return t.reset_index(drop=True)


def attach(t):
    for nm, (src, col, kind) in FEATS.items():
        vals = np.full(len(t), np.nan)
        for u in t.und.unique():
            m = (t.und == u).values
            if src == "cons" and u not in ("BANKNIFTY", "NIFTY"):
                continue
            if src == "opt":
                days, D = FT.opt(u)
                A = D[col]
            elif src == "cons":
                days, D = FT.cons(u)
                A = D[col] - (0.5 if col == "VWAPSH" else 0.0)
            else:
                days, A = FT.state(col[0], u, col[1])
            pos = {pd.Timestamp(d): i for i, d in enumerate(days)}
            r = t.loc[m, "day"].map(pos)
            ok = r.notna().values
            idx = np.flatnonzero(m)[ok]
            vals[idx] = A[r[ok].astype(int).values, t.col.values[idx]]
        t[nm] = vals * t.side.values          # signed toward the trade
    return t


def rule_mask(t, nm, sense):
    kind = FEATS[nm][2]
    th = {"z": 1.0, "b": 0.3, "s": 1.0}[kind]
    x = t[nm].values
    with np.errstate(invalid="ignore"):
        return (x <= -th) if sense == "opp" else (x >= th)        # True = SKIP


def evaluate(t, ndays, B=2000, seed=11, only=None):
    rng = np.random.default_rng(seed)
    rows = []
    for nm in FEATS:
        has = t[nm].notna().values
        tt = t[has]
        for sense in ("opp", "agree"):
            fname = f"{nm}:{'skip_opposed' if sense == 'opp' else 'skip_agreeing'}"
            if only is not None and fname not in only:
                continue
            sk = rule_mask(tt, nm, sense)
            m = int(sk.sum())
            sk_net = tt.net.values[sk].sum()
            # random skip of the same count among trades that have the feature
            if m > 0 and m < len(tt):
                draws = np.array([tt.net.values[rng.choice(len(tt), m, replace=False)].sum() for _ in range(B)])
                p = (1 + (draws <= sk_net).sum()) / (B + 1)
            else:
                p = 1.0
            nd = ndays(tt)
            rows.append(dict(filter=f"{nm}:{'skip_opposed' if sense == 'opp' else 'skip_agreeing'}", n=len(tt), skipped=m,
                             base_day=tt.net.sum() / nd, d_day=-sk_net / nd, d_day15=-tt.net15.values[sk].sum() / nd,
                             d_gross_day=-tt.gross_p.values[sk].sum() / nd, skipped_avg=tt.net.values[sk].mean() if m else np.nan,
                             p=p))
    R = pd.DataFrame(rows)
    R["bh"] = OF.bh(R.p.values)
    return R


def ndays_fn(cal):
    def f(tt):
        lo, hi = tt.day.min(), tt.day.max()
        return max(int(((cal >= lo) & (cal <= hi)).sum()), 1)
    return f


def main(mode, names=()):
    t = attach(load_trades())
    cal = pd.Series(pd.to_datetime(market().index("NIFTY").days))
    if mode == "pre":
        tp = t[t.day < HOLD]
        print("Liquidity trades pre-holdout:", len(tp), tp.groupby("und").size().to_dict())
        print(f"unfiltered net Rs/day {tp.net.sum() / ndays_fn(cal)(tp):.0f}, gross {tp.gross_p.sum() / ndays_fn(cal)(tp):.0f}")
        R = evaluate(tp, ndays_fn(cal))
        halves = []
        for lo, hi in ((pd.Timestamp("2000-01-01"), SPLIT), (SPLIT, HOLD)):
            x = evaluate(tp[(tp.day >= lo) & (tp.day < hi)], ndays_fn(cal), B=200)
            halves.append(x.set_index("filter").d_day)
        R["d_day_h1"] = halves[0].reindex(R["filter"]).values
        R["d_day_h2"] = halves[1].reindex(R["filter"]).values
        cons = R["filter"].str.split(":").str[0].isin(["HVSURGE", "VWB", "VWAP", "LEADLAG"])
        R["adopt"] = (R.bh < 0.10) & (R.d_day > 0) & (R.d_day15 > 0) & \
            (cons | ((R.d_day_h1 > 0) & (R.d_day_h2 > 0)))
        print(R.round(3).to_string())
        R.to_csv(os.path.join(OUTD, "liqfilter_pre.csv"), index=False)
    else:
        th = t[t.day >= HOLD]
        print("Liquidity trades holdout:", len(th), th.groupby("und").size().to_dict())
        R = evaluate(th, ndays_fn(cal), only=set(names))
        print(R.round(3).to_string())
        R.to_csv(os.path.join(OUTD, "liqfilter_hold.csv"), index=False)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
