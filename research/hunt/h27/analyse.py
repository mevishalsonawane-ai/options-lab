"""h27 Part B analysis: pre-registered rules = selections from the both-sides trade table (sim.py).
    python3 -I research/hunt/h27/analyse.py pre            -> variants_pre.csv, printed ranking + promotion
    python3 -I research/hunt/h27/analyse.py hold V1 [V2..]  -> the named variants on the locked holdout (run ONCE)
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy.overfit import bh, spa  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h27"
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0020}
RULES = ["R1_CUEGO", "R2_CUEGO_CONF", "R3_GAPGO_AGREE", "R4_GAPFILL_DISAGREE", "R5_RESID_FADE", "R6_CUE_FADE", "R7_RESID_GO"]
EXITS = ["E1_LIQ", "E2_15_30", "E3_LADDER", "E4_T1015", "E5_T1115", "E6_T1510"]
B = 2000


def signals(P, rule, zcol="z_COMP"):
    """P: panel rows (one per und-day). Returns DataFrame und, day, sig_min, side."""
    z, gap = P[zcol], P.gap
    if rule == "R1_CUEGO":
        m = z.abs() >= 0.5
        return P[m].assign(sig_min=560, side=np.sign(z[m]).astype(int))
    if rule == "R2_CUEGO_CONF":
        m = (z.abs() >= 0.5) & (np.sign(P.c930 - P.open) == np.sign(z))
        return P[m].assign(sig_min=570, side=np.sign(z[m]).astype(int))
    if rule == "R3_GAPGO_AGREE":
        m = (np.sign(gap) == np.sign(z)) & (gap.abs() >= 0.002) & (z.abs() >= 0.5)
        return P[m].assign(sig_min=560, side=np.sign(gap[m]).astype(int))
    if rule == "R4_GAPFILL_DISAGREE":
        m = (np.sign(gap) == -np.sign(z)) & (z != 0) & (gap.abs() >= 0.002)
        return P[m].assign(sig_min=560, side=-np.sign(gap[m]).astype(int))
    if rule == "R5_RESID_FADE":
        m = P.resid_z.abs() >= 1
        return P[m].assign(sig_min=560, side=-np.sign(P.resid_z[m]).astype(int))
    if rule == "R6_CUE_FADE":
        m = z.abs() >= 0.5
        return P[m].assign(sig_min=560, side=-np.sign(z[m]).astype(int))
    if rule == "R7_RESID_GO":
        m = P.resid_z.abs() >= 1
        return P[m].assign(sig_min=560, side=np.sign(P.resid_z[m]).astype(int))
    raise ValueError(rule)


def add_net(T, mult=1.0):
    hs = T.und.map(HS).values * mult
    stop = (T.why == "stop").values
    return (T.gross - T.charges - hs * (T.entry + T.exit) * T.qty - 0.0005 * T.exit * T.qty * stop).values


def both_sides(T):
    """Wide table: one row per (und, day, sig_min, exit_id) with net/gross for side +1 and -1 (both must exist)."""
    keys = ["und", "day", "sig_min", "exit_id"]
    a = T[T.side == 1].set_index(keys)[["net", "stress", "gross", "entry", "exit", "qty"]]
    b = T[T.side == -1].set_index(keys)[["net", "stress", "gross", "entry", "exit", "qty"]]
    return a.join(b, lsuffix="_c", rsuffix="_p", how="inner").reset_index()


def maxdd(x):
    c = np.cumsum(x)
    return float((c - np.maximum.accumulate(np.concatenate([[0], c]))[1:]).min()) if len(x) else 0.0


def evaluate(W, sel, ndays, rng):
    """W: both-sides table for one und+exit; sel: signals (day, sig_min, side). Returns stats dict and per-day series."""
    m = W.merge(sel[["day", "sig_min", "side"]], on=["day", "sig_min"])
    if not len(m):
        return None, None
    pick = lambda col: np.where(m.side.values > 0, m[col + "_c"].values, m[col + "_p"].values)  # noqa: E731
    net, gross, stress = pick("net"), pick("gross"), pick("stress")
    # random side on the same days / minutes / exits
    nc, npp = m.net_c.values, m.net_p.values
    flips = rng.random((B, len(m))) < 0.5
    null = np.where(flips, nc[None, :], npp[None, :]).sum(axis=1)
    p = (1 + int((null >= net.sum()).sum())) / (B + 1)
    m = m.assign(net=net, gross=gross, stress=stress)
    m["year"] = [d.year for d in m.day]
    m["month"] = [f"{d.year}-{d.month:02d}" for d in m.day]
    by_y = m.groupby("year").agg(n=("net", "size"), net=("net", "sum"))
    yrs = by_y[by_y.n >= 10]
    st = dict(n=len(m), hit=float((net > 0).mean()), gross=float(gross.sum()), net=float(net.sum()),
              stress=float(stress.sum()), net_tr=float(net.mean()), net_day=float(net.sum() / ndays),
              gross_day=float(gross.sum() / ndays), stress_day=float(stress.sum() / ndays),
              p_rand=p, rand_mean=float(null.mean() / ndays),
              yrs_pos=f"{int((yrs.net > 0).sum())}/{len(yrs)}", yrs_pos_frac=float((yrs.net > 0).mean()) if len(yrs) else 0.0,
              maxdd=maxdd(m.sort_values("day").net.values), worst_month=float(m.groupby("month").net.sum().min()),
              per_year=json.dumps({int(k): round(float(v)) for k, v in by_y.net.items()}))
    st["lots_for_5k"] = float(5000 / st["net_day"]) if st["net_day"] > 0 else np.inf
    daily = m.groupby("day").net.sum()
    return st, daily


def load(mode):
    T = pd.read_parquet(os.path.join(SCR, f"trades_{mode}.parquet"))
    T["net"] = add_net(T)
    T["stress"] = add_net(T, 1.5)
    P = pd.read_parquet(os.path.join(SCR, "panel.parquet"))
    return T, P


def run(mode, only=None, extra_rules=()):
    T, P = load(mode)
    hold = date(2025, 10, 1)
    P = P[(P.day < hold) if mode == "pre" else (P.day >= hold)]
    rng = np.random.default_rng(27)
    rows, dailies = [], {}
    alldays = sorted(set(T.day))
    for u, Wu in both_sides(T).groupby("und"):
        Pu = P[P.und == u]
        ndays = Wu.day.nunique()
        for rule, zcol in [(r, "z_COMP") for r in RULES] + [(r, z) for r, z, uu in extra_rules if uu == u]:
            sel = signals(Pu, rule, zcol)
            for ex in EXITS:
                vid = f"{rule}{'' if zcol == 'z_COMP' else ':' + zcol}|{u}|{ex}"
                if only and vid not in only:
                    continue
                st, daily = evaluate(Wu[Wu.exit_id == ex], sel, ndays, rng)
                if st is None:
                    continue
                st.update(vid=vid, rule=rule, und=u, exit=ex, ndays=ndays)
                rows.append(st)
                dailies[vid] = daily
    R = pd.DataFrame(rows)
    R["q_rand"] = bh(R.p_rand.values)
    X = pd.DataFrame(dailies).reindex(alldays).fillna(0.0)
    sp = spa(X.values, B=1000)
    R["spa_p_all"] = sp["spa_p"]
    R["promoted"] = (R.net > 0) & (R.q_rand < 0.05) & (R.yrs_pos_frac >= 0.6) & (sp["spa_p"] < 0.10)
    R = R.sort_values("net_day", ascending=False)
    R.to_csv(os.path.join(SCR, f"variants_{mode}.csv"), index=False)
    X.to_parquet(os.path.join(SCR, f"daily_{mode}.parquet"))
    pd.set_option("display.width", 250)
    cols = ["vid", "n", "hit", "gross_day", "net_day", "stress_day", "rand_mean", "p_rand", "q_rand", "yrs_pos",
            "maxdd", "worst_month", "lots_for_5k", "per_year"]
    print(f"variants {len(R)}  SPA p={sp['spa_p']:.3f} RC p={sp['rc_p']:.3f} best={X.columns[sp['best']]}"
          f"  promoted={int(R.promoted.sum())}  net>0: {int((R.net > 0).sum())}  raw p<0.05: {int((R.p_rand < 0.05).sum())}")
    print(R[cols].head(25).round(3).to_string(index=False))
    print("\nby rule (mean net Rs/day over unds x exits):")
    print(R.groupby("rule")[["gross_day", "net_day", "stress_day", "rand_mean", "hit"]].mean().round(2))
    print("\nby und:")
    print(R.groupby("und")[["gross_day", "net_day", "rand_mean", "hit"]].mean().round(2))
    print("\nby exit:")
    print(R.groupby("exit")[["gross_day", "net_day", "rand_mean", "hit"]].mean().round(2))
    return R


if __name__ == "__main__":
    mode = sys.argv[1]
    extra = []
    for a in sys.argv[2:]:
        if a.startswith("cue="):            # cue=RULE:z_X  (single cues that survived Part A)
            r, z, uu = a[4:].split(":")
            extra.append((r, z, uu))
    only = [a for a in sys.argv[2:] if not a.startswith("cue=")] or None
    run(mode, only, extra)
