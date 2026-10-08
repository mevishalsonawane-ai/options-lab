"""h25 filter study: every signal x timeframe as a filter on the app's Liquidity 15+5 trades (5 indices).

    OBUY_CACHE=<scratch>/hunt/h25/cache flock <scratch>/obuy.lock python3 -I research/hunt/h25/filt.py build
    OBUY_CACHE=<scratch>/hunt/h25/cache python3 -I research/hunt/h25/filt.py screen     (pre-holdout only)
    OBUY_CACHE=<scratch>/hunt/h25/cache python3 -I research/hunt/h25/filt.py hold       (passers, holdout once)

Base trades: h4's Liquidity signals (BN/FIN validated port; NIFTY/SENSEX/MIDCP h4 port), 1-ITM near, arm exits, app
fills + charges + the measured half-spread on entry and exit, one position at a time per book.
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
from obuy.engine import Execution, StrikeRule, positions, prepare_many  # noqa: E402
from obuy.strategies.liquidity import ARM_EXITS  # noqa: E402
import evaluate as EV  # noqa: E402
import sigs  # noqa: E402

OUT = EV.OUT
H4 = os.path.join(C.SCRATCH, "hunt/h4/cache/h4")
SHORT_LOOK = 2          # patterns: event on the last 3 closed bars


def build():
    a = pd.read_pickle(os.path.join(H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    sig = pd.concat([a, b], ignore_index=True)
    sig["day"] = pd.to_datetime(sig["day"]).dt.date
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(sig, StrikeRule(money=1), exe, 0, None, False)])
    tr = pk.run(ARM_EXITS, exe)
    tr = positions(tr, one_at_a_time=True).reset_index(drop=True)
    hs = tr.und.map(EV.HS).values
    sb = (tr.entry.values + tr.exit.values) * tr.qty.values
    tr["net_real"] = tr.net.values - hs * sb
    tr["net_stress"] = tr.net.values - EV.STRESS * hs * sb
    tr["ord"] = [d.toordinal() for d in tr.day]
    keep = ["und", "book", "day", "ord", "year", "sig_min", "entry_min", "exit_min", "side", "strike", "qty", "entry",
            "exit", "why", "gross", "charges", "net", "net_real", "net_stress"]
    tr[keep].to_parquet(os.path.join(OUT, "liq_trades.parquet"))
    print(tr.groupby("und").agg(n=("net", "size"), net=("net", "sum"), net_real=("net_real", "sum")))


def states(tr, mk):
    """{(tf, sig): state array over trades} (direction of the latest event, as known at the trade's sig_min)."""
    out = {}
    for u, g in tr.groupby("und"):
        ix = mk.index(u)
        allord = np.array([d.toordinal() for d in ix.days])
        idx = g.index.values
        for tf in EV.TFS:
            B = sigs.bars(ix.mat(), tf)
            S = sigs.compute(B)
            key = allord[B["dpos"]].astype(np.int64) * 1000 + B["col_end"]
            tkey = g.ord.values.astype(np.int64) * 1000 + (g.sig_min.values - EV.SM0)
            pos = np.searchsorted(key, tkey, side="right") - 1
            for name, s in S.items():
                evi = np.where(s != 0, np.arange(len(s)), -1)
                last = np.maximum.accumulate(evi)
                le = np.where(pos >= 0, last[np.maximum(pos, 0)], -1)
                st = np.where(le >= 0, s[np.maximum(le, 0)], 0).astype(np.int8)
                if sigs.family_of(name) in ("candlestick", "chart"):
                    st = np.where(pos - le <= SHORT_LOOK, st, 0).astype(np.int8)
                arr = out.setdefault((tf, name), np.zeros(len(tr), np.int8))
                arr[idx] = st
        mk.release(u)
    return out


def screen(hold=False):
    mk = market()
    tr = pd.read_parquet(os.path.join(OUT, "liq_trades.parquet"))
    st = states(tr, mk)
    pre = (tr.ord.values < EV.HOLD)
    rows = []
    for base in ("POOL", "BANKNIFTY"):
        bm = np.ones(len(tr), bool) if base == "POOL" else (tr.und.values == "BANKNIFTY")
        for (tf, name), s in st.items():
            for mode in ("KEEP_AGREE", "VETO_OPPOSE"):
                keep = (s == tr.side.values) if mode == "KEEP_AGREE" else (s != -tr.side.values)
                for per, pm in (("pre", pre), ("hold", ~pre)):
                    if per == "hold" and not hold:
                        continue
                    m = bm & pm
                    x = tr.net_real.values[m]
                    k = keep[m]
                    N, nk = len(x), int(k.sum())
                    row = dict(base=base, tf=tf, sig=name, fam=sigs.family_of(name), mode=mode, per=per, N=N, nk=nk,
                               base_mean=x.mean(), base_tot=x.sum(), kept_mean=x[k].mean() if nk else np.nan,
                               kept_tot=x[k].sum(), kept_stress=tr.net_stress.values[m][k].sum(),
                               kept_gross=tr.gross.values[m][k].sum())
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
    pre_df = df[df.per == "pre"].copy()
    pre_df["q"] = EV_bh(pre_df.p.values)
    pre_df["pass"] = (pre_df.q < 0.05) & (pre_df.yrs_up * 2 > pre_df.yrs) & (pre_df.kept_tot >= 0.8 * pre_df.base_tot)
    pre_df.to_parquet(os.path.join(OUT, "filt_pre.parquet"))
    print("filters", len(pre_df), "pass", int(pre_df["pass"].sum()), "min q", pre_df.q.min())
    print(pre_df.sort_values("p").head(20).to_string())
    if hold:
        h = df[df.per == "hold"].merge(pre_df[["base", "tf", "sig", "mode", "pass", "q"]], on=["base", "tf", "sig", "mode"])
        h.to_parquet(os.path.join(OUT, "filt_hold.parquet"))
        print(h[h["pass"]].to_string())


def EV_bh(p):
    from obuy.overfit import bh
    return bh(np.asarray(p))


if __name__ == "__main__":
    t0 = time.time()
    cmd = sys.argv[1]
    if cmd == "build":
        build()
    elif cmd == "screen":
        screen(False)
    elif cmd == "hold":
        screen(True)
    print(f"done {time.time() - t0:.0f}s")
