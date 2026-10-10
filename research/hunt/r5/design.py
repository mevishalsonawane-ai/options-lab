"""R5: the guide's Pine strategy, design and holdout (PREREG.md).  python3 -I design.py [design|holdout]

design  -> scratchpad/hunt/r5/{design_A.csv, design_B.csv, design_diag.csv, design_trades_*.parquet} + research
           frozen.json (research/hunt/r5/results/)
holdout -> ONCE (marker file) -> holdout_A.csv, holdout_B.csv
"""
from __future__ import annotations

import json
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "r4"))
import lib4 as L4  # noqa: E402
import signals4 as S4  # noqa: E402
import pine5 as P5  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

L = L4.L
OUT = os.path.join(L.C.SCRATCH, "hunt", "r5")
RES = os.path.join(HERE, "results")
os.makedirs(OUT, exist_ok=True)
os.makedirs(RES, exist_ok=True)
UNDS = ["NIFTY", "BANKNIFTY"]
TFS = (15, 30, 60)
CFGS = ("AS_WRITTEN", "PRAG", "CONFL")
DEFAULT = (10, 1.0, 15)


def grid(tf):
    if tf in (15, 30):
        g = [(p, m, c) for p in (8, 10) for m in (0.9, 1.1) for c in (12, 15)]
    else:
        g = [(p, m, c) for p in (10, 12) for m in (1.0, 1.3) for c in (15, 20)]
    return [DEFAULT] + [x for x in g if x != DEFAULT]      # 1h grid already contains the default


def sname(st):
    return f"p{st[0]}m{st[1]}c{st[2]}"


def a_stats(T, tw):
    n = len(T)
    if n == 0:
        return dict(A_trades=0)
    net = T.net.values
    eq = np.cumsum(net)
    dd = float((np.maximum.accumulate(np.r_[0, eq])[1:] - eq).max())
    sd = net.std(ddof=1) if n > 1 else np.nan
    t = net.mean() / (sd / math.sqrt(n)) if n > 1 and sd > 0 else np.nan
    if len(tw) > 1 and n > 1:
        se = math.sqrt(net.var(ddof=1) / n + tw.var(ddof=1) / len(tw))
        z = (net.mean() - tw.mean()) / se if se > 0 else np.nan
    else:
        z = np.nan
    return dict(A_trades=n, A_win=float((net > 0).mean()), A_gross_pts=float(T.gross.mean()),
                A_net_pts=float(net.mean()), A_total_pts=float(net.sum()), A_maxdd_pts=dd, A_t=t,
                A_p=L.p1(t) if n >= 30 else 1.0, A_twin_pts=float(tw.mean()) if len(tw) else np.nan,
                A_p_rand=L.p1(z) if n >= 30 else 1.0,
                A_imm=float(T.why.str.startswith("IMM").mean()), A_overnight=float((T.xdpos != T.edpos).mean()),
                A_lag_bars=float(T.lag.mean()))


def nonoverlap(tr, maxday=3):
    """one option position at a time per variant; a new entry (at minute s+1) may start at the previous exit minute."""
    tr = tr[np.isfinite(tr.net)].sort_values(["var", "di", "s"])
    keep = np.zeros(len(tr), bool)
    v, d, s, ex = tr["var"].values, tr.di.values, tr.s.values, tr.ex.values
    last, busy, cnt = None, -1, 0
    for i in range(len(tr)):
        k = (v[i], d[i])
        if k != last:
            last, busy, cnt = k, -1, 0
        if s[i] + 1 >= busy and cnt < maxday:
            keep[i] = True
            busy = ex[i]
            cnt += 1
    return tr[keep].reset_index(drop=True)


def run_period(u, period, only=None, seed=11):
    P = L.load_index(u)
    days = P["days"]
    if period == "design":
        mask = P["ok"] & ~P["hold"]
        dsel = np.array([d < L.HOLD0 for d in days])            # bars after 30 Sep 2025 are not even simulated
    else:
        mask = P["ok"] & P["hold"] & P["inrange"]
        dsel = np.array([d <= L.HOLD1 for d in days])
    R = L4.SReq(P)
    Arows, diags, alltr = [], [], []
    for tf in TFS:
        B0 = S4.mk_bars(P, tf)
        keep = dsel[B0["dpos"]]
        B = {k: v[keep] for k, v in B0.items() if isinstance(v, np.ndarray)}
        for st in grid(tf):
            for cfg in CFGS:
                base = f"{cfg}|tf{tf}|{sname(st)}"
                if only is not None and not any(o.startswith(base + "|") for o in only):
                    continue
                T, dg = P5.simulate(B, st[0], st[1], st[2], cfg)
                if len(T) and period != "design":
                    T = T[np.array([L.HOLD0 <= days[d] <= L.HOLD1 for d in T.edpos.values], bool)]
                tw = P5.twins_points(B, T, seed=seed) if len(T) else np.array([])
                row = dict(und=u, cfg=cfg, tf=tf, setting=sname(st), primary=st == DEFAULT)
                row.update(a_stats(T, tw))
                Arows.append(row)
                dg.update(und=u, cfg=cfg, tf=tf, setting=sname(st))
                diags.append(dg)
                if len(T):
                    T = T.assign(base=base)
                    alltr.append(T)
                for r in T.itertuples():
                    s, di = int(r.ecol), int(r.edpos)
                    if not (L4.W0 <= s <= L4.W1) or not P["ok"][di]:
                        continue
                    side = 0 if r.side > 0 else 1
                    x = L4.index_exit(P, di, s, side, r.sl, r.tp)
                    if r.why == "REV" and r.xdpos == di:
                        xr = int(r.xcol) + 1
                        x = xr if x < 0 else min(x, xr)
                    for ex in ("NAT", "OPT"):
                        var = f"{base}|{ex}"
                        if only is not None and var not in only:
                            continue
                        if ex == "NAT":
                            R.add(var, di, s, side, x=x, idx_exit=x >= 0)
                        else:
                            R.add(var, di, s, side, sp=0.25, tp=0.50)
    A = pd.DataFrame(Arows)
    Dg = pd.DataFrame(diags)
    TR = pd.concat(alltr, ignore_index=True) if alltr else pd.DataFrame()
    req = R.frame()
    print(u, period, "option requests", len(req), flush=True)
    if len(req) == 0:
        return P, mask, A, Dg, TR, pd.DataFrame(), pd.DataFrame(), {}
    real = L4.run(P, req)
    real = nonoverlap(real)
    c = P["c"]
    sg = np.where(real.side.values == 0, 1.0, -1.0)
    ex = np.clip(real.ex.values.astype(int) - 1, 0, 374)
    real["idx_pts"] = sg * (c[real.di.values, ex] - c[real.di.values, real.s.values])
    tw = L4.twins(real, seed)
    rnd = L4.run(P, tw)
    df, daily = L4.summarize(real, rnd, P, mask)
    ip = real.groupby("var").idx_pts.mean()
    df["idx_pts_trade"] = df["var"].map(ip)
    df.insert(0, "und", u)
    L.D.market().release()
    return P, mask, A, Dg, TR, real, df, daily


def design():
    As, Ds, Bs = [], [], []
    for u in UNDS:
        t0 = time.time()
        P, mask, A, Dg, TR, real, df, daily = run_period(u, "design", seed=abs(hash(u)) % 1000)
        if len(df):
            rc, tst = L4.reality_check(daily)
            df["rc_p"] = df["var"].map(rc)
            df["t_daily"] = df["var"].map(tst)
        As.append(A)
        Ds.append(Dg)
        Bs.append(df)
        if len(TR):
            TR.drop(columns=[], errors="ignore").to_parquet(os.path.join(OUT, f"design_idx_trades_{u}.parquet"))
        if len(real):
            real.to_parquet(os.path.join(OUT, f"design_opt_trades_{u}.parquet"))
        print(u, "done", f"{time.time() - t0:.0f}s", flush=True)
    A = pd.concat(As, ignore_index=True)
    Dg = pd.concat(Ds, ignore_index=True)
    Bd = pd.concat(Bs, ignore_index=True)
    Bd["q_bh"] = L.bh(Bd.p.values)
    Bd["q_bh_rand"] = L.bh(Bd.p_rand.values)
    A["A_q_bh"] = L.bh(A.A_p.values) if "A_p" in A else np.nan
    A.to_csv(os.path.join(RES, "design_A.csv"), index=False)
    Dg.to_csv(os.path.join(RES, "design_diag.csv"), index=False)
    Bd.to_csv(os.path.join(RES, "design_B.csv"), index=False)
    # frozen confirmatory set
    prim = [f"{c}|tf{tf}|{sname(DEFAULT)}|NAT" for c in CFGS for tf in TFS]
    picks = []
    for u in UNDS:
        for v in prim:
            picks.append(dict(und=u, var=v, why="primary"))
        b = Bd[Bd.und == u].copy()
        if len(b):
            b["cfg"] = b["var"].str.split("|").str[0]
            b["tf"] = b["var"].str.split("|").str[1]
            b["ex"] = b["var"].str.split("|").str[3]
            for (cfg, tf, ex), g in b.groupby(["cfg", "tf", "ex"]):
                g = g[np.isfinite(g.t_daily)]
                if len(g):
                    picks.append(dict(und=u, var=g.sort_values("t_daily").iloc[-1]["var"], why="design_best"))
    fr = dict(written=time.strftime("%Y-%m-%d %H:%M:%S"), n_option_variants=int(len(Bd)), picks=picks)
    json.dump(fr, open(os.path.join(RES, "frozen.json"), "w"), indent=1)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 500)
    print(A.round(2).to_string())
    print(Bd[["und", "var", "trades", "rs_day", "rs_trade", "win", "idx_pts_trade", "rand_trade", "p_rand", "maxdd",
              "q_bh", "rc_p"]].round(3).to_string())


def holdout():
    marker = os.path.join(RES, "holdout_opened.txt")
    if os.path.exists(marker):
        sys.exit("holdout already opened once: " + open(marker).read())
    open(marker, "w").write(pd.Timestamp.now().isoformat())
    fr = json.load(open(os.path.join(RES, "frozen.json")))
    As, Bs, Ds = [], [], []
    for u in UNDS:
        P, mask, A, Dg, TR, real, df, daily = run_period(u, "holdout", seed=99)
        As.append(A)
        Bs.append(df)
        Ds.append(Dg)
        if len(real):
            real.to_parquet(os.path.join(OUT, f"holdout_opt_trades_{u}.parquet"))
        if len(TR):
            TR.to_parquet(os.path.join(OUT, f"holdout_idx_trades_{u}.parquet"))
    A = pd.concat(As, ignore_index=True)
    Bh = pd.concat(Bs, ignore_index=True)
    A.to_csv(os.path.join(RES, "holdout_A.csv"), index=False)
    pd.concat(Ds, ignore_index=True).to_csv(os.path.join(RES, "holdout_diag.csv"), index=False)
    pk = pd.DataFrame(fr["picks"])
    Bh["confirmatory"] = [((pk.und == r.und) & (pk["var"] == r.var)).any() for r in Bh.itertuples()]
    Bh.to_csv(os.path.join(RES, "holdout_B.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 500)
    print(A.round(2).to_string())
    print(Bh[["und", "var", "confirmatory", "trades", "rs_day", "rs_trade", "win", "idx_pts_trade", "rand_trade",
              "p_rand", "maxdd"]].round(3).to_string())


if __name__ == "__main__":
    {"design": design, "holdout": holdout}[sys.argv[1] if len(sys.argv) > 1 else "design"]()
