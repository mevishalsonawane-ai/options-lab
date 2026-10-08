"""h32 event study (DISCOVERY data only: first day .. 2023-12-31; base rates on all pre-holdout data).

python3 -I research/hunt/h32/es.py   -> <scratch>/hunt/h32/es_*.csv + es.log
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (DISC_END, FEATS, HOLD, OUT, TYPN, UNDS, crash, event, load)  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)
pd.set_option("display.max_rows", 200)


def log(*a):
    print(*a, flush=True)


df = load()
df = df[df.day < HOLD].reset_index(drop=True)
df["year"] = pd.to_datetime(df.day, unit="D").dt.year
log("rows pre-holdout", len(df), df.groupby("und").day.nunique().to_dict())

# ---------------------------------------------------------------- 1. base rates (all pre-holdout rows)
rows = []
for up in (15, 20, 25, 30):
    for win in (5, 15, 30):
        for dn in (10, 15):
            J = event(df, up, win, dn)
            K = crash(df, up, win, dn)
            g = pd.DataFrame({"und": df.und, "J": J, "K": K}).groupby("und").mean()
            for u, r in g.iterrows():
                rows.append(dict(und=UNDS[u], up=up, win=win, dn=dn, p_jump=r.J, p_drop=r.K))
br = pd.DataFrame(rows)
br.to_csv(os.path.join(OUT, "es_base.csv"), index=False)
log(br.pivot_table(index=["up", "win", "dn"], columns="und", values="p_jump").round(4))
log("median premium by und/typ:\n", df.groupby(["und", "typ"]).E.median().unstack().round(0))
log("events/day J20/15/-15 by und & year:\n",
    pd.DataFrame({"und": df.und, "y": df.year, "J": event(df), "d": df.day}).groupby(["und", "y"]).apply(
        lambda g: g.J.sum() / g.d.nunique()).unstack().round(1))

# ---------------------------------------------------------------- 2. event study on discovery
d = df[df.day < DISC_END].reset_index(drop=True)
log("discovery rows", len(d))
J = event(d)
K = crash(d)
key = d.und.astype(np.int64) * 10 ** 8 + d.day.astype(np.int64) * 10 + d.typ.astype(np.int64)
order = np.lexsort((d.s.values, key.values))
d, J, K, key = d.iloc[order].reset_index(drop=True), J[order], K[order], key.values[order]


def onset(flag):
    """first decision of a run: no event at the same contract-type in the previous 10 minutes (5 decisions)."""
    out = flag.copy()
    s = d.s.values
    for lag in range(1, 6):
        prev = np.roll(flag, lag) & (np.roll(key, lag) == key) & (s - np.roll(s, lag) <= 10)
        out &= ~prev
    return out


Jo, Ko = onset(J), onset(K)
log("onsets jump", Jo.sum(), "drop", Ko.sum(), "rows with event", J.sum())
d["sb"] = d.s // 30
d["q"] = (d.year.astype(np.int64) * 4 + pd.to_datetime(d.day, unit="D").dt.quarter).astype(np.int64)
res = []
for f in FEATS:
    x = d[f]
    if f == "s":
        tm = None
    else:
        tm = x.groupby([d.und, d.typ, d.sb, d.q]).rank(pct=True).values
    dm = x.groupby([d.und, d.day, d.typ]).rank(pct=True).values
    r = dict(feat=f)
    for nm, m in (("jump", Jo), ("drop", Ko), ("jall", J), ("dall", K)):
        for kind, pr in (("t", tm), ("d", dm)):
            if pr is None:
                r[f"{nm}_{kind}"] = np.nan
                continue
            v = pr[m & ~np.isnan(pr)]
            r[f"{nm}_{kind}"] = v.mean()
            r[f"{nm}_{kind}_se"] = v.std() / np.sqrt(max(1, len(np.unique(d.day.values[m & ~np.isnan(pr)]))))
    ctl = ~J & ~K
    r["med_jump"] = np.nanmedian(x[Jo])
    r["med_drop"] = np.nanmedian(x[Ko])
    r["med_ctl"] = np.nanmedian(x[ctl])
    res.append(r)
res = pd.DataFrame(res)
res["dir_t"] = res.jump_t - res.drop_t       # > 0: seen more before jumps than before drops (directional)
res["z_t"] = (res.jump_t - 0.5) / res.jump_t_se
res["dir_all"] = res.jall_t - res.dall_t
res["z_all"] = (res.jall_t - 0.5) / res.jall_t_se
res.to_csv(os.path.join(OUT, "es_feats.csv"), index=False)
log(res[["feat", "jump_t", "jump_d", "drop_t", "drop_d", "dir_t", "jall_t", "jall_d", "dall_t", "dall_d", "dir_all", "z_all", "med_jump", "med_drop", "med_ctl"]]
    .sort_values("jump_t").round(3).to_string())

# per year stability of time-matched precursor strength (anchored: discovery + later pre-holdout years, label only)
yr = []
for y in sorted(df.year.unique()):
    dy = df[df.year == y]
    Jy = event(dy)
    sb, q = dy.s // 30, dy.year
    for f in FEATS:
        if f == "s":
            continue
        pr = dy[f].groupby([dy.und, dy.typ, sb]).rank(pct=True).values
        ok = Jy & ~np.isnan(pr)
        yr.append(dict(year=y, feat=f, jump_t=pr[ok].mean()))
yr = pd.DataFrame(yr).pivot(index="feat", columns="year", values="jump_t")
yr.to_csv(os.path.join(OUT, "es_years.csv"))
log("time-matched mean percentile of features at ALL jump rows, by year:\n", yr.round(3).to_string())

# clock: event rate by half hour and by und (discovery)
clk = pd.DataFrame({"und": d.und, "hh": (d.s + 555) // 30 * 30, "J": J, "K": K}).groupby(["hh"]).agg(J=("J", "mean"), K=("K", "mean"))
clk.index = [f"{h // 60:02d}:{h % 60:02d}" for h in clk.index]
log("clock (discovery):\n", clk.round(4))
ex = pd.DataFrame({"exp": d.exp, "dte": d.dte.clip(upper=5), "J": J, "K": K}).groupby("dte").mean()
log("days to expiry:\n", ex.round(4))

# same-minute ties (target and stop both inside one 1-minute high/low bar; counted as the stop)
for up, dn in ((15, 10), (20, 15), (25, 15), (30, 15)):
    u = df[f"up{up}"].values.astype(np.int16)
    dd = df[f"dn{dn}"].values.astype(np.int16)
    tie = (u == dd) & (u < 30)
    log(f"ties +{up}/-{dn} within 30 min: {tie.sum()} of {((u < 30) | (dd < 30)).sum()} resolved rows ({tie.mean() * 100:.2f}% of all rows)")

# how fast: minutes from entry to +15 and +20 among Jump(20,15,15) rows (discovery), and the gap between them
u15 = d.up15.values[J].astype(float) + 1
u20 = d.up20.values[J].astype(float) + 1
log("time to +15 (min) quartiles", np.percentile(u15, [25, 50, 75]), " time to +20", np.percentile(u20, [25, 50, 75]),
    " gap +15 -> +20", np.percentile(u20 - u15, [25, 50, 75]))
for u in range(5):
    m = d.und.values[J] == u
    if m.any():
        log(UNDS[u], "median min to +20:", np.median(u20[m]), "share within 5 min:", (u20[m] <= 5).mean().round(3))
