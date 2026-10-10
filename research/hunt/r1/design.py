"""R1 design run (data before 1 Oct 2025 only) and the frozen holdout picks.

python3 -I research/hunt/r1/design.py            -> scratchpad/hunt/r1/{design_variants.csv, design_daily_<U>.npz, frozen.json}
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib as L  # noqa: E402
import signals as S  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

NRAND = 10
UNDS = ["NIFTY", "BANKNIFTY"]


def twins(req, seed):
    rng = np.random.default_rng(seed)
    r = req.loc[req.index.repeat(NRAND)].copy()
    r["rid"] = np.repeat(req.index.values, NRAND)
    span = (r.hi - r.lo + 1).values
    sr = r.lo.values + (rng.random(len(r)) * span).astype(int)
    r["x"] = np.where(r.x.values >= 0, np.minimum(sr + (r.x.values - r.s.values), L.SQ), -1)
    r["s"] = sr
    r["side"] = rng.integers(0, 2, len(r))
    return r


def engine_dedup(P, req):
    key = ["di", "s", "side", "x", "liq"]
    u = req[key].drop_duplicates().reset_index(drop=True)
    u["sp"] = np.nan
    u["tp"] = np.nan
    res = L.run_engine(P, u)
    u = pd.concat([u, res], axis=1)
    return req.merge(u, on=key, how="left", suffixes=("", "_e"))


def summarize(trades, rand, sessions_by_idea, P, period_mask):
    rows = []
    daily = {}
    days_idx = np.nonzero(period_mask)[0]
    for var, g in trades.groupby("var"):
        g = g[np.isfinite(g.net)]
        idea = var.split("|")[0]
        el = sessions_by_idea[idea] & period_mask
        nses = int(el.sum())
        # straddle: one 'trade' = one day's pair
        per_trade = g.groupby("di").net.sum() if "STRAD" in var else g.set_index("di").net
        dd = np.zeros(P["nd"])
        np.add.at(dd, g.di.values, g.net.values)
        dser = dd[el]
        m, t = L.cl_t(per_trade.values, per_trade.index.values)
        rr = rand[rand["var"] == var]
        rr = rr[np.isfinite(rr.net)]
        if "STRAD" in var:
            rr = rr.groupby(["di", "rep"]).net.sum().reset_index()
        rm = rr.net.mean() if len(rr) else np.nan
        # Welch, trade-level, real mean vs random-twin mean
        if len(rr) > 2 and len(per_trade) > 2:
            se = np.sqrt(per_trade.var(ddof=1) / len(per_trade) + rr.net.var(ddof=1) / len(rr))
            zr = (per_trade.mean() - rm) / se
        else:
            zr = np.nan
        rows.append(dict(var=var, idea=idea, trades=len(per_trade), sessions=nses,
                         rs_day=dser.sum() / max(nses, 1), rs_trade=m, t=t, p=L.p1(t),
                         win=float((per_trade > 0).mean()) if len(per_trade) else np.nan,
                         gross_trade=float(g.gross.sum() / max(len(per_trade), 1)),
                         rand_trade=rm, z_rand=zr, p_rand=L.p1(zr), maxdd=L.maxdd(dser), total=dser.sum()))
        daily[var] = dd
    return pd.DataFrame(rows), daily


def run_period(u, period, seed=11):
    P = L.load_index(u)
    if period == "design":
        mask = P["ok"] & ~P["hold"]
    else:
        mask = P["ok"] & P["hold"] & P["inrange"]
    days_ok = np.nonzero(mask)[0]
    t0 = time.time()
    req, elig = S.build_all(P, days_ok)
    req = req[req.di.isin(days_ok)].reset_index(drop=True)
    print(u, period, "requests", len(req), "variants", req["var"].nunique(), f"{time.time() - t0:.0f}s", flush=True)
    return P, mask, req, elig


def main():
    allrows, alld = [], {}
    for u in UNDS:
        P, mask, req, elig = run_period(u, "design")
        tw = twins(req, seed=hash(u) % 1000)
        t0 = time.time()
        real = engine_dedup(P, req)
        tw["rep"] = np.arange(len(tw)) % NRAND
        rnd = engine_dedup(P, tw)
        print(u, "engine", len(real), len(rnd), f"{time.time() - t0:.0f}s", flush=True)
        df, daily = summarize(real, rnd, elig, P, mask)
        df.insert(0, "und", u)
        allrows.append(df)
        real.to_parquet(os.path.join(L.OUT, f"design_trades_{u}.parquet"))
        dm = mask
        np.savez_compressed(os.path.join(L.OUT, f"design_daily_{u}.npz"), vars=np.array(list(daily)),
                            M=np.array([daily[k][dm] for k in daily], np.float32))
        alld[u] = (daily, dm)
        L.D.market().release()
    res = pd.concat(allrows, ignore_index=True)
    res["q_bh"] = L.bh(res.p.values)
    res["q_bh_rand"] = L.bh(res.p_rand.values)
    # White reality check (max t, stationary bootstrap) per index over its variants' daily P&L
    for u in UNDS:
        z = np.load(os.path.join(L.OUT, f"design_daily_{u}.npz"))
        M = z["M"].astype(np.float64).T          # days x vars
        n = M.shape[0]
        mu = M.mean(0)
        sd = M.std(0, ddof=1) + 1e-9
        tst = mu / (sd / np.sqrt(n))
        idx = L.stationary_boot_idx(n, 1000, 5, seed=3)
        mx = np.empty(1000)
        for b in range(1000):
            Mb = M[idx[b]]
            mb = Mb.mean(0) - mu
            mx[b] = np.max(mb / (sd / np.sqrt(n)))
        rc = np.array([(mx >= t).mean() for t in tst])
        vv = list(z["vars"])
        res.loc[res.und == u, "rc_p"] = res.loc[res.und == u, "var"].map(dict(zip(vv, rc)))
        res.loc[res.und == u, "t_daily"] = res.loc[res.und == u, "var"].map(dict(zip(vv, tst)))
    res = res.sort_values(["und", "idea", "t_daily"], ascending=[True, True, False])
    res.to_csv(os.path.join(L.OUT, "design_variants.csv"), index=False)
    picks = res.groupby(["und", "idea"]).head(1)
    frozen = {"written": time.strftime("%Y-%m-%d %H:%M:%S"),
              "picks": picks[["und", "idea", "var"]].to_dict("records")}
    with open(os.path.join(L.OUT, "frozen.json"), "w") as f:
        json.dump(frozen, f, indent=1)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 500)
    print(picks[["und", "var", "trades", "rs_day", "rs_trade", "t", "q_bh", "win", "rand_trade", "p_rand", "maxdd",
                 "rc_p"]].round(3).to_string())
    print("variants", len(res), "positive rs_day", int((res.rs_day > 0).sum()), "min q", res.q_bh.min(),
          "min rc_p", res.rc_p.min())


if __name__ == "__main__":
    main()
