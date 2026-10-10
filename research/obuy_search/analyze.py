"""OBUY search - multiple-testing correction, Deflated Sharpe, random-entry permutation test and walk-forward summary,
on the IN-SAMPLE search output only. Writes the survivor list (and, if none, the illustrative top-10) for holdout.py.

    python3 -I research/obuy_search/analyze.py <scratchpad>
"""
from __future__ import annotations

import json
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
from scipy.special import ndtr, ndtri  # noqa: E402
from scipy.stats import kurtosis, skew  # noqa: E402

from common import WINDOWS, monte_carlo, rule_trades  # noqa: E402

SP = sys.argv[1]
OBS = os.path.join(SP, "obs")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY"]
Q = 0.10
PERM = 2000
rng = np.random.default_rng(7)


def main():
    s = np.load(os.path.join(OBS, "search.npz"), allow_pickle=True)
    T, MU, N, RID = s["T"], s["MU"], s["N"], s["RID"]
    rules, combos, exits = s["rules"], s["combos"], list(s["exits"])
    NC, NE, D = int(s["NC"]), int(s["NE"]), int(s["D"])
    data = {u: np.load(os.path.join(OBS, f"is_{u}.npz")) for u in UNDS}
    cal = np.unique(np.concatenate([data[u]["days"] for u in UNDS]))
    ok = np.isfinite(T)
    n_eval = int(ok.sum())
    Tv = T[ok].astype(np.float64)
    out = dict(n_generated=int(s["n_generated"]), n_rules=int(s["n_rules"]), n_eval_rules=int(s["n_eval_rules"]),
               n_eval=n_eval, D=D)
    # ---- distribution
    out["t_quantiles"] = {q: float(np.quantile(Tv, q)) for q in (0.5, 0.9, 0.99, 0.999)}
    out["t_max"] = float(Tv.max())
    out["frac_pos_mean"] = float((Tv > 0).mean())
    out["n_t_gt_2"] = int((Tv > 2).sum()); out["n_t_gt_3"] = int((Tv > 3).sum()); out["n_t_gt_4"] = int((Tv > 4).sum())
    out["expected_t_gt_2_null"] = float(n_eval * ndtr(-2)); out["expected_t_gt_3_null"] = float(n_eval * ndtr(-3))
    # ---- Benjamini-Hochberg (one-sided)
    p = ndtr(-Tv)
    order = np.argsort(p)
    ps = p[order]
    thr = Q * np.arange(1, n_eval + 1) / n_eval
    passed = np.nonzero(ps <= thr)[0]
    n_bh = int(passed.max() + 1) if len(passed) else 0
    out["bh_n_pass"] = n_bh
    out["bh_t_needed"] = float(-ndtri(Q / n_eval))
    # ---- Reality check / SPA
    rc, spa = s["rc_max"], s["spa_max"]
    out["rc_p"] = float((rc >= Tv.max()).mean())
    out["spa_p"] = float((spa >= max(Tv.max(), 0)).mean())
    out["spa_crit90"] = float(np.quantile(spa, 0.90)); out["rc_crit90"] = float(np.quantile(rc, 0.90))
    out["n_over_spa90"] = int((Tv > out["spa_crit90"]).sum())
    # ---- DSR inputs
    sr = Tv / np.sqrt(D)
    V = sr.var()
    g = 0.5772156649
    sr0 = np.sqrt(V) * ((1 - g) * ndtri(1 - 1 / n_eval) + g * ndtri(1 - 1 / (n_eval * np.e)))
    out["dsr_sr0_daily"] = float(sr0)
    out["dsr_sr0_as_t"] = float(sr0 * np.sqrt(D))

    # ---- candidates: best column per entry rule, ranked by t
    flat_ok = np.nonzero(ok)[0]
    best_by_rule = {}
    top = flat_ok[np.argsort(-T[flat_ok])[:20000]]
    for f in top:
        r = int(RID[f // NC]); col = int(f % NC)
        if r not in best_by_rule:
            best_by_rule[r] = (float(T[f]), col, f)
        if len(best_by_rule) >= 40:
            break

    def describe(r, col):
        u, dr, ci, w = rules[r]
        return dict(und=u, side="CE" if int(dr) == 0 else "PE", dr=int(dr), atoms=str(combos[int(ci)]).split(","),
                    window=w, strike="ATM" if col // NE == 0 else "ITM1", strike_k=int(col // NE), exit_k=int(col % NE),
                    exit=exits[col % NE])

    def evaluate(r, col, tval):
        d = describe(r, col)
        z = data[d["und"]]
        days, slot, pnl, prem = rule_trades(z, d["atoms"], d["dr"], d["window"], d["strike_k"], d["exit_k"])
        daily = np.zeros(D)
        daily[np.searchsorted(cal, z["days"][days])] = pnl
        srd = daily.mean() / daily.std()
        sk, ku = skew(daily), kurtosis(daily, fisher=False)
        dsr = float(ndtr((srd - sr0) * np.sqrt(D - 1) / np.sqrt(max(1 - sk * srd + (ku - 1) / 4 * srd ** 2, 1e-9))))
        # permutation: random entries, same index / side / window / strike / exit / trade count
        a, b = WINDOWS[d["window"]]
        P = z["pnl"][:, a:b, d["dr"], d["strike_k"], d["exit_k"]]
        valid = ~np.isnan(P)
        vd = np.nonzero(valid.any(1))[0]
        n = len(pnl)
        perm = np.empty(PERM)
        for k in range(PERM):
            dd = rng.choice(vd, size=min(n, len(vd)), replace=False)
            vv = valid[dd]
            # a random valid slot per day
            rnd = rng.random(vv.shape) * vv
            sl = rnd.argmax(1)
            perm[k] = P[dd, sl].mean()
        pp = float((1 + (perm >= pnl.mean()).sum()) / (PERM + 1))
        yrs = z["days"][days].astype("datetime64[Y]").astype(int) + 1970
        by_year = {int(y): [int((yrs == y).sum()), float(pnl[yrs == y].sum())] for y in np.unique(yrs)}
        d.update(t=tval, n=int(n), mean=float(pnl.mean()), win=float((pnl > 0).mean()), total=float(pnl.sum()),
                 dsr=dsr, perm_p=pp, perm_mean=float(perm.mean()), by_year=by_year, med_prem=float(np.median(prem)),
                 p_one=float(ndtr(-tval)))
        return d

    cands = sorted(best_by_rule.items(), key=lambda kv: -kv[1][0])
    bh_cut_t = float(-ndtri(ps[n_bh - 1])) if n_bh else np.inf
    evald = []
    for r, (tval, col, f) in cands[:40]:
        d = evaluate(r, col, tval)
        d["bh_pass"] = bool(tval >= bh_cut_t)
        d["survivor"] = bool(d["bh_pass"] and d["dsr"] >= 0.95 and d["perm_p"] <= 0.01)
        evald.append(d)
    survivors = [d for d in evald if d["survivor"]][:10]
    out["survivors"] = survivors
    out["top40"] = evald
    out["holdout_set"] = survivors if survivors else evald[:10]
    out["holdout_set_kind"] = "survivors" if survivors else "illustrative top-10 (NOT survivors)"
    # ---- in-sample Monte Carlo for the holdout set
    for d in out["holdout_set"]:
        z = data[d["und"]]
        days, slot, pnl, prem = rule_trades(z, d["atoms"], d["dr"], d["window"], d["strike_k"], d["exit_k"])
        und_days = len(z["days"])
        daily = np.zeros(und_days)
        lot_now = z["lot"][-1]
        daily[days] = pnl * lot_now / z["lot"][days]
        d["mc_is"] = monte_carlo(daily, rng)
    # ---- walk-forward: the top-20 by train t each fold, their next-year result
    wf = s["wf"]
    wfo = {}
    for y in sorted(set(wf[:, 0].astype(int))):
        w = wf[wf[:, 0] == y]
        wfo[int(y)] = dict(train_t_min=float(w[:, 1].min()), train_t_max=float(w[:, 1].max()), test_trades=float(w[:, 3].sum()),
                      test_total=float(w[:, 2].sum()), test_mean_per_trade=float(w[:, 2].sum() / max(w[:, 3].sum(), 1)),
                      n_pos=int((w[:, 2] > 0).sum()), n=len(w),
                      best_test_total=float(w[0, 2]), best_test_n=float(w[0, 3]))
    out["walk_forward"] = wfo
    # ---- broad picture: average per-trade P&L of all evaluated strategies; by exit
    out["mean_per_trade_all"] = float(np.nanmean(MU[ok]))
    with open(os.path.join(OBS, "analysis.json"), "w") as fh:
        json.dump(out, fh, indent=1, default=float)
    print(json.dumps({k: v for k, v in out.items() if k not in ("top40", "holdout_set", "survivors")}, indent=1, default=float))
    for d in evald[:15]:
        print(f"t={d['t']:.2f} {d['und']} {d['side']} {'+'.join(d['atoms'])} {d['window']} {d['strike']} {d['exit']} "
              f"n={d['n']} mean={d['mean']:.0f} dsr={d['dsr']:.3f} perm_p={d['perm_p']:.4f} bh={d['bh_pass']}")


if __name__ == "__main__":
    main()
