"""h42 step 3: run the pre-registered candidate rules (research/hunt/h42/prereg.json, written with PREREG.md) as option
trades, one position at a time per rule, on the PRE-holdout period ('pre', allowed any time) or ONCE on the locked
holdout ('hold', 2025-10-01 ..). Every exit of the grid is reported (+15/20/25/30 targets x -10/15/20 stops x 15/30/60-min
time stops, the Liquidity-arm exit, and the question's own time exit); the pre-registered exit is the headline.
Random-entry baseline: same index, same decision minute, same side, a random day of the same period (B = 2000).
python3 -I research/hunt/h42/holdout.py pre|hold
"""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import EXITS, HERE, HOLD, LOT, OUT, exit_px, load_panel, load_table, pnl  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

B = 2000
CAP = 100_000


def events(q, df):
    uni = df.eval(q["universe"]).fillna(False).astype(bool).values if q["universe"] else np.ones(len(df), bool)
    cond = df.eval(q["cond"]).fillna(False).astype(bool).values
    ev = np.nonzero(uni & cond)[0]
    days = df.day.values[ev]
    s = df[q["entry"]].values[ev].astype(int) if q["entry"] in df.columns else np.full(len(ev), int(q["entry"]))
    su = q["side_used"]
    if su == "CE":
        sd = np.ones(len(ev), int)
    elif su == "PE":
        sd = -np.ones(len(ev), int)
    elif su == "CE+PE":
        sd = np.zeros(len(ev), int)
    else:
        sd = np.sign(df[su[4:]].values[ev]).astype(int)
    ok = (s >= 0) & (s <= 345) & ((sd != 0) | (su == "CE+PE"))
    o = np.lexsort((s[ok], days[ok]))
    return days[ok][o], s[ok][o], sd[ok][o]


def legs_pnl(u, P, ex, hz, d, s, sd):
    """net, gross, held, ok for arrays (sd 0 = both legs)."""
    net = np.zeros(len(d))
    gr = np.zeros(len(d))
    held = np.zeros(len(d))
    ok = np.ones(len(d), bool)
    prem = np.zeros(len(d))
    for leg in (0, 1):
        use = (sd == (1 if leg == 0 else -1)) | (sd == 0)
        if not use.any():
            continue
        E = P["E"][d[use], s[use], leg].astype(float)
        X, st, h = exit_px(P, ex, hz, d[use], s[use], np.full(use.sum(), leg))
        X = X.astype(float)
        g = np.isfinite(E) & np.isfinite(X)
        n_, g_ = pnl(u, np.where(g, E, 1.0), np.where(g, X, 1.0), st)
        idx = np.nonzero(use)[0]
        net[idx] += np.where(g, n_, 0)
        gr[idx] += np.where(g, g_, 0)
        held[idx] = np.maximum(held[idx], np.broadcast_to(h, use.sum()))
        prem[idx] += np.where(g, E, 0) * LOT[u]
        ok[idx[~g]] = False
    return net, gr, held, ok, prem


def run(mode):
    spec = json.load(open(os.path.join(HERE, "prereg.json")))
    out, daily_all = [], {}
    rng = np.random.default_rng(42)
    for q in spec["rules"]:
        u = q["und"]
        P = load_panel(u)
        z = np.load(os.path.join(OUT, f"c_{u}.npz"))
        has = z["has"] == 1
        per = (P["days"] >= HOLD) if mode == "hold" else (P["days"] < HOLD)
        prow = np.nonzero(per & has)[0]
        ndays = len(prow)
        df = load_table(q["table"], u)
        df = df[(df.day >= HOLD) if mode == "hold" else (df.day < HOLD)].reset_index(drop=True)
        days, s, sd = events(q, df)
        rowmap = {int(dd): i for i, dd in enumerate(P["days"])}
        d = np.array([rowmap.get(int(x), -1) for x in days])
        keep = d >= 0
        d, s, sd, days = d[keep], s[keep], sd[keep], days[keep]
        for ex in EXITS:
            net, gr, held, ok, prem = legs_pnl(u, P, ex, q["horizon"], d, s, sd)
            # one position at a time
            take = np.zeros(len(d), bool)
            last_day, free_at = -1, -1
            for i in range(len(d)):
                if not ok[i]:
                    continue
                if days[i] != last_day:
                    last_day, free_at = days[i], -1
                if s[i] >= free_at:
                    take[i] = True
                    free_at = s[i] + held[i] + 1
            n = int(take.sum())
            if n == 0:
                out.append(dict(id=q["id"], und=u, exit=ex, n=0))
                continue
            tot, totg = net[take].sum(), gr[take].sum()
            dser = pd.Series(net[take]).groupby(days[take]).sum()
            full = dser.reindex(P["days"][prow], fill_value=0.0)
            eq = full.cumsum().values
            mdd = float((eq - np.maximum.accumulate(np.concatenate([[0], eq]))[1:]).min())
            mser = full.groupby(pd.to_datetime(full.index, unit="D").to_period("M")).sum()
            # matched random baseline: same index, s, side; random day of the same period with a valid entry
            Er = P["E"][prow]
            rmeans = np.zeros(B)
            ts, tsd = s[take], sd[take]
            draws = []
            for i in range(n):
                cand = prow[np.isfinite(Er[:, ts[i], 0 if tsd[i] >= 0 else 1])] if tsd[i] != 0 else \
                    prow[np.isfinite(Er[:, ts[i], 0]) & np.isfinite(Er[:, ts[i], 1])]
                draws.append(rng.choice(cand, B) if len(cand) else np.full(B, prow[0]))
            Dr = np.array(draws)                                                    # n x B
            rn, _, _, rok, _ = legs_pnl(u, P, ex, q["horizon"], Dr.ravel(), np.repeat(ts, B), np.repeat(tsd, B))
            rn = np.where(rok, rn, np.nan).reshape(n, B)
            rmeans = np.nanmean(rn, axis=0)
            pr = (1 + np.sum(rmeans >= net[take].mean())) / (B + 1)
            rpd = tot / ndays
            prem_med = float(np.median(prem[take]))
            lots_cap = int(CAP // prem_med) if prem_med > 0 else 0
            out.append(dict(id=q["id"], und=u, exit=ex, prereg=ex == q["exit"], n=n, days=ndays, net_total=tot,
                            gross_total=totg, rs_day_net=rpd, rs_day_gross=totg / ndays, per_trade_net=net[take].mean(),
                            win=float((net[take] > 0).mean()), max_dd=mdd, worst_day=float(full.min()),
                            worst_month=float(mser.min()), months_losing=f"{int((mser < 0).sum())}/{len(mser)}",
                            rand_per_trade=float(np.nanmean(rmeans)), p_vs_random=pr,
                            lots_for_5000=(int(np.ceil(5000 / rpd)) if rpd > 0 else None), prem_per_lot=prem_med,
                            lots_at_1lakh=lots_cap, rs_day_at_1lakh=rpd * lots_cap))
            if ex == q["exit"]:
                daily_all[q["id"]] = full
    R = pd.DataFrame(out)
    R.to_csv(os.path.join(HERE, f"holdout_{mode}.csv" if mode == "hold" else "prehold_rules.csv"), index=False)
    if daily_all:
        tot = pd.concat(daily_all, axis=1).fillna(0).sum(1)
        print(f"basket of {len(daily_all)} rules, pre-registered exits: Rs/day {tot.mean():.0f}, total {tot.sum():.0f}")
    pd.set_option("display.width", 250)
    print(R[R.prereg == True][["id", "und", "exit", "n", "rs_day_net", "rs_day_gross", "per_trade_net", "win", "max_dd",  # noqa: E712
                               "p_vs_random", "lots_for_5000", "lots_at_1lakh", "rs_day_at_1lakh"]].to_string(index=False))


if __name__ == "__main__":
    run(sys.argv[1])
