"""h35 part 2b: each app level family as a FILTER on Liquidity 15+5's own trades (the 6 app books, h4 port, app fills
and costs, 1 lot), skipping an entry when a level of the family lies AHEAD of the signal close in the trade's direction
within D (1 index-stop unit, or 0.2%). See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h35/cache flock <scratch>/obuy.lock python3 -I research/hunt/h35/filt.py build|pre|hold
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.data import market  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import levels as LV  # noqa: E402

HOLD = pd.Timestamp("2025-10-01").date()
SPLIT = pd.Timestamp("2024-01-01").date()
LOGD = os.path.join(C.SCRATCH, "hunt/h35")
BOOKS = ["liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin", "liq15_MIDCPNIFTY", "liq5_MIDCPNIFTY"]
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "MIDCPNIFTY": 8.0}
HALF = {"BANKNIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042}
FILTERS = [(f, dk) for f in LV.FAMS for dk in ("unit", "pct")]
PATH = os.path.join(C.CACHE, "h35", "liq_levels.parquet")


def build():
    t = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/trades_app.parquet"))
    t = t[t.book.isin(BOOKS)].copy()
    t["day"] = pd.to_datetime(t.day).dt.date
    mk = market()
    rows = []
    for u, g in t.groupby("und"):
        ix = mk.index(u)
        M = ix.mat()
        dl = ix.daily()
        opts = mk.options(u)
        vixd = mk.vix.daily.close
        for d, gd in g.groupby("day"):
            i = ix.pos.get(d)
            if i is None or i < 5:
                continue
            F = LV.fam_levels(mk, u, i, ix, M, dl, opts, vixd)
            for r in gd.itertuples():
                col = int(r.sig_min) - 555
                c = M["c"][i][col]
                rec = dict(cand=r.cand, book=r.book, und=u, day=d, sig_min=r.sig_min, side=r.side, close=c)
                for fam in LV.FAMS:
                    lv = F[fam](col) or []
                    ah = [r.side * (L - c) for L in lv if np.isfinite(L) and r.side * (L - c) > 0]
                    rec["ahead_" + fam] = min(ah) if ah else np.nan
                rows.append(rec)
        mk.release(u)
    A = pd.DataFrame(rows)
    T = t.merge(A, on=["cand", "book", "und", "day", "sig_min", "side"], how="left")
    T["net_real"] = T.net - HALF_spread(T)
    T[["book", "und", "day", "year", "sig_min", "side", "entry", "exit", "qty", "gross", "net", "net_real", "close"] +
      ["ahead_" + f for f in LV.FAMS]].to_parquet(PATH)
    print(len(T), T.groupby("book").size().to_string())


def HALF_spread(T, k=1.0):
    return T.und.map(HALF).astype(float) * k * (T.entry + T.exit) * T.qty


def skip_mask(T, fam, dk):
    D = T.und.map(ISTOP).astype(float) if dk == "unit" else 0.002 * T.close
    a = T["ahead_" + fam]
    return (a < D).fillna(False).values


def evaluate(T, days_n):
    rows = []
    rng = np.random.default_rng(35)
    for fam, dk in FILTERS:
        sk = skip_mask(T, fam, dk)
        x = T.net_real.values
        n_sk = int(sk.sum())
        obs = x[sk].mean() if n_sk else np.nan
        if n_sk:
            null = np.array([x[rng.choice(len(x), n_sk, replace=False)].mean() for _ in range(2000)])
            p = (1 + (null <= obs).sum()) / 2001          # one-sided: skipped trades worse than a random skip
        else:
            p = 1.0
        r = dict(filter=f"{fam}/{dk}", skipped=n_sk, skip_share=n_sk / len(x), skipped_mean=obs, kept_mean=x[~sk].mean(),
                 delta_total=-x[sk].sum(), p_skip=p)
        for nm, lo, hi in (("h1", pd.Timestamp("2000-01-01").date(), SPLIT), ("h2", SPLIT, HOLD), ("hold", HOLD, pd.Timestamp("2100-01-01").date())):
            m = (T.day >= lo) & (T.day < hi)
            if m.sum() == 0:
                continue
            nd = days_n[nm]
            r[f"base_day_{nm}"] = x[m.values].sum() / nd
            r[f"filt_day_{nm}"] = x[m.values & ~sk].sum() / nd
        rows.append(r)
    R = pd.DataFrame(rows).set_index("filter")
    R["q_bh"] = OF.bh(R.p_skip.values)
    return R


def ndays(lo, hi):
    mk = market()
    s = set()
    for u in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"):
        s |= {d for d in mk.index(u).days if lo <= d < hi}
    return len(s)


def main(mode):
    T = pd.read_parquet(PATH)
    T["day"] = pd.to_datetime(T.day).dt.date
    pd.set_option("display.width", 250)
    if mode == "pre":
        P = T[T.day < HOLD].reset_index(drop=True)
        dn = dict(h1=ndays(pd.Timestamp("2000-01-01").date(), SPLIT), h2=ndays(SPLIT, HOLD))
        R = evaluate(P, dn)
        R["keep"] = (R.filt_day_h1 > R.base_day_h1) & (R.filt_day_h2 > R.base_day_h2) & (R.q_bh < 0.10)
        R.to_csv(os.path.join(LOGD, "filt_pre.csv"))
        print(f"pre trades {len(P)}, base net real {P.net_real.sum():,.0f}, app {P.net.sum():,.0f}")
        print(R.round(3).to_string())
        json.dump(dict(keep=list(R.index[R.keep])), open(os.path.join(LOGD, "filt_keep.json"), "w"))
    else:
        keep = json.load(open(os.path.join(LOGD, "filt_keep.json")))["keep"]
        H = T[T.day >= HOLD].reset_index(drop=True)
        dn = dict(hold=ndays(HOLD, pd.Timestamp("2100-01-01").date()))
        R = evaluate(H, dn)
        R = R.loc[keep] if keep else R.iloc[0:0]
        R.to_csv(os.path.join(LOGD, "filt_hold.csv"))
        print(f"hold trades {len(H)}, base Rs/day real {H.net_real.sum() / dn['hold']:,.0f}; kept filters {keep}")
        print(R.round(3).to_string())


if __name__ == "__main__":
    build() if sys.argv[1] == "build" else main(sys.argv[1])
