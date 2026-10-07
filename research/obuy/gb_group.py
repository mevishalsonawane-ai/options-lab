"""Run the gb_* strategies (catalog trend_indicator TI-* and mean_reversion MR-*) and the group-level controls.

    flock <scratch>/obuy.lock python3 -I research/obuy/gb_group.py [--name gb_all] [--pool 5] [--B 2000]

The 16 strategies run in chunks (one obuy Lab = one data pass each) so memory stays bounded; each chunk's own outputs
land in <cache>/runs/<name>_c<k>/. Then the controls are recomputed over the WHOLE group (every variant of every
chunk), exactly as lab.py does within one run:
  - random-baseline p per variant with BH / Holm across all group variants;
  - walk-forward random-baseline p per strategy with Holm / BH across the 16 strategies, and the four gates;
  - White's Reality Check / Hansen SPA over the combined (days x variants) daily-P&L matrix;
  - Deflated Sharpe of each strategy's in-sample best with N = all group variants; PBO from the chunk (per strategy);
  - walk-forward per year and Monte Carlo on Rs 5 lakh (1 lot, 1% and 2% risk) of the walk-forward trades.
Outputs: <cache>/runs/<name>/{families.csv, variants.csv, wf_years.csv, mc.csv, oos_trades.csv.gz, summary.json}.
"""
from __future__ import annotations

import argparse
import gc
import json
import os
import pickle
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from obuy import config as C  # noqa: E402  (first: it puts pandas' dependencies on the path under python -I)

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import overfit as OF  # noqa: E402
from obuy import stats as ST  # noqa: E402
from obuy.lab import Gates, Lab  # noqa: E402
from obuy.strategies.registry import all_strategies  # noqa: E402

CHUNKS = [
    ["gb_ti09_adx"],
    ["gb_ti03_ema_cross", "gb_ti04_ema9_twap"],
    ["gb_ti08_heikin_ashi", "gb_ti02_st_ema"],
    ["gb_mr01_5ema", "gb_mr06_or_fade"],
    ["gb_ti01_supertrend", "gb_ti05_vwap_pullback", "gb_mr04_camarilla"],
    ["gb_ti10_confluence", "gb_mr02_bb_alert", "gb_ti06_rsi50"],
    ["gb_ti07_rsi_shift", "gb_mr05_vwap_stretch", "gb_mr03_rsi_reversal"],
]


def run_chunks(name, pool, B):
    S = all_strategies()
    res = []
    for k, chunk in enumerate(CHUNKS):
        path = os.path.join(C.CACHE, "runs", f"{name}_c{k}", "chunk.pkl")
        if os.path.exists(path):
            with open(path, "rb") as f:
                res.append(pickle.load(f))
            continue
        lab = Lab([S[n] for n in chunk], name=f"{name}_c{k}", pool_k=pool, B=B).run()
        keep = dict(V=lab.V, F=lab.F, X=lab.X, days=lab.all_days, vids=lab.vids, sdays=lab.days, spa=lab.spa,
                    oos={s: (rows, oos) for s, (rows, oos) in lab.oos.items()})
        with open(path, "wb") as f:
            pickle.dump(keep, f, protocol=4)
        res.append(keep)
        del lab
        gc.collect()
    return res


def combine(res, name, B):
    g = Gates()
    out = os.path.join(C.CACHE, "runs", name)
    os.makedirs(out, exist_ok=True)
    V = pd.concat([r["V"] for r in res])
    V["p_rand_bh_group"] = OF.bh(V.p_rand.values)
    V["p_rand_holm_group"] = OF.holm(V.p_rand.values)
    days = sorted({d for r in res for d in r["days"]})
    dpos = {d: i for i, d in enumerate(days)}
    vids = [v for r in res for v in r["vids"]]
    X = np.zeros((len(days), len(vids)))
    j = 0
    for r in res:
        rows = [dpos[d] for d in r["days"]]
        X[np.ix_(rows, range(j, j + len(r["vids"])))] = r["X"]
        j += len(r["vids"])
    spa = OF.spa(X, B=1000)
    sd = X.std(axis=0, ddof=1)
    srs = np.where(sd > 0, X.mean(axis=0) / np.where(sd > 0, sd, 1), np.nan)
    F = pd.concat([r["F"] for r in res])
    F["p_rand_holm"] = OF.holm(F.p_rand.fillna(1.0).values)
    F["p_rand_bh"] = OF.bh(F.p_rand.fillna(1.0).values)
    F["G1_wf_net"] = F.wf_net > 0
    F["G2_beats_random"] = F.p_rand_holm < g.alpha
    F["G3_years"] = F.wf_years_pos > F.wf_years / 2
    F["G4_drawdown"] = (F.wf_dd >= -g.max_dd_frac * ST.CAPITAL) & (F.mc_p_dd50.fillna(1.0) <= g.max_p_dd50)
    F["promoted"] = F.G1_wf_net & F.G2_beats_random & F.G3_years & F.G4_drawdown
    wfy, mcs, oos_all = [], [], []
    for s in F.index:
        fv = [v for v in vids if v.startswith(s + "|")]
        best = V.loc[fv].net.idxmax()
        fsr = srs[[vids.index(v) for v in fv]]
        fsr = fsr[np.isfinite(fsr)]
        var_sr = max(fsr.var(ddof=1) if len(fsr) >= 5 else 0.0, 1.0 / len(X))
        F.loc[s, "best_dsr_group"] = OF.dsr(X[:, vids.index(best)], srs, n_trials=len(vids), var_sr=var_sr)["dsr"]
        bv = V.loc[best]
        for c in ("trades", "per_year", "pf", "win", "max_dd", "years_pos", "years", "p_rand", "p_rand_bh_group", "sharpe"):
            F.loc[s, f"best_{c}"] = bv[c]
        F.loc[s, "best_label"] = f"{bv.rule} {bv.exits} {bv.sig}"
        sdays = next(r["sdays"][s] for r in res if s in r["sdays"])
        rows, oos = next(r["oos"][s] for r in res if s in r["oos"])
        for w in rows:
            wfy.append(dict(strategy=s, **w))
        if len(oos):
            y0 = min(w["year"] for w in rows)
            dd = [d for d in sdays if d.year >= y0]
            mc = ST.monte_carlo(oos, dd)
            for k in ("1 lot", "1% risk", "2% risk"):
                if k in mc:
                    mcs.append(dict(strategy=s, sizing=k, trades_per_year=mc["trades_per_year"], **mc[k]))
            oos_all.append(oos.assign(strategy=s))
    F = F.sort_values(["promoted", "wf_net"], ascending=False)
    V.to_csv(os.path.join(out, "variants.csv"))
    F.to_csv(os.path.join(out, "families.csv"))
    pd.DataFrame(wfy).to_csv(os.path.join(out, "wf_years.csv"), index=False)
    pd.DataFrame(mcs).to_csv(os.path.join(out, "mc.csv"), index=False)
    if oos_all:
        pd.concat(oos_all, ignore_index=True).to_csv(os.path.join(out, "oos_trades.csv.gz"), index=False, compression="gzip")
    summ = dict(n_variants=len(vids), n_days=len(days), first=str(days[0]), last=str(days[-1]), rc_p=spa["rc_p"],
                spa_p=spa["spa_p"], min_bh_group=float(V.p_rand_bh_group.min()), n_raw_p05=int((V.p_rand < 0.05).sum()),
                n_bh_p05=int((V.p_rand_bh_group < 0.05).sum()), promoted=[s for s in F.index if F.loc[s, "promoted"]],
                chunk_spa=[r["spa"] for r in res])
    with open(os.path.join(out, "summary.json"), "w") as f:
        json.dump(summ, f, indent=1, default=float)
    print(json.dumps(summ, indent=1, default=float))
    cols = ["n_variants", "wf_trades", "wf_net", "wf_years_pos", "wf_years", "wf_dd", "p_rand", "p_rand_holm", "p_rand_bh",
            "mc_p_dd50", "best_net", "best_dsr_group", "pbo", "promoted"]
    print(F[cols].to_string())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="gb_all")
    ap.add_argument("--pool", type=int, default=5)
    ap.add_argument("--B", type=int, default=2000)
    a = ap.parse_args()
    res = run_chunks(a.name, a.pool, a.B)
    combine(res, a.name, a.B)


if __name__ == "__main__":
    main()
