"""h32 forward test of the PREREG Part B rules (rules.json), with the fixed exit menu, random-entry baseline,
BH / Holm / SPA, walk-forward by year. Holdout is NOT touched unless mode == 'hold'.

python3 -I research/hunt/h32/fwd.py pre            -> fwd_variants.csv, fwd_labels.csv, fwd_trades.parquet
python3 -I research/hunt/h32/fwd.py hold VID [VID]  -> hold_*.csv (run ONCE)
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import DISC_END, HOLD, OUT, UNDS, crash, event, load  # noqa: E402
from obuy import overfit as OV  # noqa: E402
from obuy.data import ddate, market  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, positions, prepare_many  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
R = json.load(open(os.path.join(HERE, "rules.json")))
CUTS = R["cuts"]
HS = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016, "FINNIFTY": 0.0042, "MIDCPNIFTY": 0.0021, "SENSEX": 0.0020}
LOT_NOW = {"NIFTY": 65, "BANKNIFTY": 35, "FINNIFTY": 60, "MIDCPNIFTY": 120, "SENSEX": 20}
EXITS = {
    "X1_ARM": Exits(stop_pct=0.15, time_stop=20, time_gain=0.05),
    "X2_P15": Exits(tgt_pts=15, stop_pts=10, time_stop=30, time_gain=None),
    "X3_P20": Exits(tgt_pts=20, stop_pts=15, time_stop=30, time_gain=None),
    "X4_P2020": Exits(tgt_pts=20, stop_pts=20, time_stop=30, time_gain=None),
    "X5_P25": Exits(tgt_pts=25, stop_pts=15, time_stop=30, time_gain=None),
    "X6_P30": Exits(tgt_pts=30, stop_pts=15, time_stop=30, time_gain=None),
    "X7_PCT": Exits(stop_pct=0.15, tgt_pct=0.30),
    "X8_LAD": Exits(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.30),
    "X9_T15": Exits(time_stop=15, time_gain=None),
    "X10_T30": Exits(time_stop=30, time_gain=None),
}
EXE = Execution(expiry="allow")
F24, F25 = (date(2024, 1, 1) - date(1970, 1, 1)).days, (date(2025, 1, 1) - date(1970, 1, 1)).days
GAP, MAXSIG = 10, 8


def mask(frame, conds):
    m = np.ones(len(frame), bool)
    for f, t in conds:
        if f == "exp":
            m &= frame.exp.values == 1
            continue
        lo = frame.und.map({UNDS.index(u): c[0] for u, c in CUTS[f].items()}).values
        hi = frame.und.map({UNDS.index(u): c[1] for u, c in CUTS[f].items()}).values
        x = frame[f].values
        m &= (x <= lo) if t == "Q1" else (x >= hi)
    return m


def period(dn):
    return np.where(dn < DISC_END, "disc", np.where(dn < F25, "2024", np.where(dn < HOLD, "2025a", "hold")))


def signals(frame, m, seed):
    sub = frame.loc[m, ["und", "day", "s", "typ"]].copy()
    sub["r"] = np.random.default_rng(seed).random(len(sub))
    sub = sub.sort_values(["und", "day", "s", "r"]).drop_duplicates(["und", "day", "s"])
    keep = []
    lastk, lasts, n = None, -99, 0
    for i, (u, dd, s) in enumerate(zip(sub.und.values, sub.day.values, sub.s.values)):
        k = (u, dd)
        if k != lastk:
            lastk, lasts, n = k, -99, 0
        if s >= lasts + GAP and n < MAXSIG:
            keep.append(i)
            lasts, n = s, n + 1
    sub = sub.iloc[keep]
    out = pd.DataFrame(dict(und=[UNDS[u] for u in sub.und], day=[ddate(x) for x in sub.day], sig_min=sub.s.values + 555,
                            side=np.where(sub.typ.values < 2, 1, -1), money=sub.typ.values % 2))
    out["book"] = out.und
    return out


def add_spread(tr, k=1.0):
    hs = tr.und.map(HS).values * k
    return tr.net.values - tr.qty.values * (tr.entry.values + tr.exit.values) * hs


def run(mode, vids=()):
    cols = ["und", "day", "s", "typ", "exp", "up20", "dn15"] + sorted({f for r in R["rules"] for f, _ in r["conds"]})
    df = load(cols=list(dict.fromkeys(cols)))
    df = df[df.day < HOLD] if mode == "pre" else df[df.day >= HOLD]
    df = df.reset_index(drop=True)
    J, K = event(df), crash(df)
    per = period(df.day.values)
    rules = R["rules"] if mode == "pre" else [r for r in R["rules"] if r["name"] in {v.split("|")[0] for v in vids}]
    # ---- label metrics (precision / recall / lift per period and per index)
    lab = []
    strat = df.und.astype(int) * 4 + df.typ.astype(int)
    for p in np.unique(per):
        pm = per == p
        bJ = pd.Series(J[pm]).groupby(strat[pm].values).mean()
        bK = pd.Series(K[pm]).groupby(strat[pm].values).mean()
        eJ, eK = strat.map(bJ).values, strat.map(bK).values
        for r in rules:
            m = mask(df, r["conds"]) & pm
            for u in [None] + list(range(5)):
                mu = m if u is None else m & (df.und.values == u)
                base = pm if u is None else pm & (df.und.values == u)
                if mu.sum() == 0:
                    continue
                lab.append(dict(rule=r["name"], period=p, und="ALL" if u is None else UNDS[u], n=int(mu.sum()),
                                precision=J[mu].mean(), base=J[base].mean(), p_drop=K[mu].mean(), base_drop=K[base].mean(),
                                recall=J[mu].sum() / max(J[base].sum(), 1), liftJ=J[mu].sum() / eJ[mu].sum(),
                                liftK=K[mu].sum() / eK[mu].sum()))
    lab = pd.DataFrame(lab)
    lab.to_csv(os.path.join(OUT, f"{mode}_labels.csv"), index=False)
    print(lab[lab.und == "ALL"].round(4).to_string(), flush=True)
    # ---- engine
    jobs, jm = [], []
    for ri, r in enumerate(rules):
        sg = signals(df, mask(df, r["conds"]), seed=11 + ri)
        print(r["name"], "signals", len(sg), flush=True)
        for money in (0, 1):
            s2 = sg[sg.money == money].drop(columns="money").reset_index(drop=True)
            jobs.append((s2, StrikeRule(money=money), EXE, 3 if mode == "pre" else 0, (9 * 60 + 19, 14 * 60 + 49), False))
            jm.append((r["name"], money))
    packs = prepare_many(jobs)
    market().release()
    print("packs ready, store MB", packs[0][0].store.nbytes() / 1e6, flush=True)
    allt, vrows, pools = [], [], {}
    for r in rules:
        for xn, ex in EXITS.items():
            vid = f"{r['name']}|{xn}"
            if mode == "hold" and vid not in vids:
                continue
            parts, pparts = [], []
            for (pk, pool), (rn, money) in zip(packs, jm):
                if rn != r["name"] or not len(pk):
                    continue
                parts.append(pk.run(ex, EXE))
                if pool is not None and len(pool):
                    pparts.append(pool.run(ex, EXE))
            tr = pd.concat(parts, ignore_index=True)
            tr = positions(tr, one_at_a_time=True, max_per_day=3)
            tr["net_sp"] = add_spread(tr)
            tr["net_st"] = add_spread(tr, 1.5)
            tr["vid"] = vid
            allt.append(tr)
            if pparts:
                pl = pd.concat(pparts, ignore_index=True)
                pl["net"] = add_spread(pl)
                pools[vid] = pl
    T = pd.concat(allt, ignore_index=True)
    T["dn"] = [(d - date(1970, 1, 1)).days for d in T.day]
    T["period"] = period(T.dn.values)
    T.drop(columns=["tag"], errors="ignore").to_parquet(os.path.join(OUT, f"{mode}_trades.parquet"), index=False)
    if pools:
        P = pd.concat([p.assign(vid=v)[["vid", "parent", "net", "day"]] for v, p in pools.items()], ignore_index=True)
        P["dn"] = [(d - date(1970, 1, 1)).days for d in P.day]
        P.drop(columns="day").to_parquet(os.path.join(OUT, f"{mode}_pools.parquet"), index=False)
    return T, pools


def trading_days(lo, hi):
    ds = set()
    for u in UNDS:
        ds |= {d for d in market().index(u).days if lo <= (d - date(1970, 1, 1)).days < hi}
    return sorted(ds)


if __name__ == "__main__":
    mode = sys.argv[1]
    T, pools = run(mode, tuple(sys.argv[2:]))
    print("trades", len(T))
