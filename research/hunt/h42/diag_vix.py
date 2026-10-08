"""h42 post-holdout diagnostics of the two VIX-drop rules (descriptive only; nothing is re-selected)."""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import HERE, HOLD, OUT, load_panel, load_table  # noqa: E402
from holdout import events, legs_pnl  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import data as D  # noqa: E402


def trades(q, ex, period):
    u = q["und"]
    P = load_panel(u)
    df = load_table(q["table"], u)
    df = df[(df.day >= HOLD) if period == "hold" else (df.day < HOLD)].reset_index(drop=True)
    days, s, sd = events(q, df)
    rowmap = {int(dd): i for i, dd in enumerate(P["days"])}
    d = np.array([rowmap.get(int(x), -1) for x in days])
    k = d >= 0
    d, s, sd, days = d[k], s[k], sd[k], days[k]
    net, gr, held, ok, prem = legs_pnl(u, P, ex, q["horizon"], d, s, sd)
    take = np.zeros(len(d), bool)
    last, free = -1, -1
    for i in range(len(d)):
        if not ok[i]:
            continue
        if days[i] != last:
            last, free = days[i], -1
        if s[i] >= free:
            take[i] = True
            free = s[i] + held[i] + 1
    return pd.DataFrame(dict(day=days[take], s=s[take], net=net[take], gross=gr[take], held=held[take], prem=prem[take]))


spec = json.load(open(os.path.join(HERE, "prereg.json")))
Q = {r["id"]: r for r in spec["rules"]}
for qid in ("Q0721", "Q0737"):
    q = Q[qid]
    for per in ("pre", "hold"):
        T = trades(q, "liq", per)
        T["year"] = pd.to_datetime(T.day, unit="D").dt.year
        print(f"\n{qid} {q['und']} {per}: trades {len(T)}, net/trade {T.net.mean():.0f}, gross/trade {T.gross.mean():.0f}")
        print("  by year net Rs:", T.groupby("year").net.agg(["count", "sum", "mean"]).round(0).to_dict("index"))
        print("  entry time: share before 10:00 %.2f, 10-12 %.2f, after 12 %.2f; median entry minute %s" % (
            (T.s < 45).mean(), ((T.s >= 45) & (T.s < 165)).mean(), (T.s >= 165).mean(), int(T.s.median())))
        srt = T.net.sort_values(ascending=False)
        print(f"  top-5 trades {srt.head(5).sum():.0f} of total {T.net.sum():.0f}; top-10 {srt.head(10).sum():.0f}; "
              f"win {(T.net > 0).mean():.2f}; held to 15:10 share {(T.held > 200).mean():.2f}")
        if per == "hold":
            T.to_csv(os.path.join(OUT, f"diag_{qid}_hold.csv"), index=False)

# VIX timestamp alignment: corr(VIX 1-min change at t, NIFTY 1-min return at t+k)
mk = D.market()
vm = mk.vix.minutes()
z = np.load(os.path.join(OUT, "c_NIFTY.npz"))
cs = {k: [] for k in range(-3, 4)}
for i, dn in enumerate(z["days"]):
    if dn >= HOLD or not z["has"][i]:
        continue
    d = D.ddate(int(dn))
    if d not in vm:
        continue
    v = pd.Series(vm[d]).ffill().values
    c = pd.Series(z["ic"][i]).ffill().values
    dv = np.diff(np.log(v))[20:350]
    dc = np.diff(np.log(c))
    for k in cs:
        a = dc[20 + k:350 + k]
        m = np.isfinite(dv) & np.isfinite(a)
        if m.sum() > 50:
            cs[k].append(np.corrcoef(dv[m], a[m])[0, 1])
print("\ncorr(VIX change at t, NIFTY return at t+k), pre-holdout day-average:", {k: round(float(np.nanmean(x)), 3) for k, x in cs.items()})
