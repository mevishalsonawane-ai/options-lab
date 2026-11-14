"""R171 Amendment A (PREREG.md): MEASURE the spread from real 1-minute prices instead of assuming it.

    R171_DATA=<release files> R171_CACHE=<cache> python research/hunt/r171_liquidity_gates/r171measure.py

Reads the R171 trades (r171study.py trades) and the release store. For each R51 / R72 / Liquidity trade, on the traded
minutes of the 30 minutes before the fill: Roll, Abdi-Ranaldo and Corwin-Schultz relative spreads; MEASURED = their
median (floored at one tick). Then: where MEASURED sits against costs.py's roll / quoted / stress law, a refit of the law
on DESIGN trades, and every strategy (and every R171 gate's gain) re-priced with MEASURED and with CS (the wide bound).
A measurement: no test, no verdict. Writes results/measure.json.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r171arms as A  # noqa: E402
import r171data as D  # noqa: E402
import r171study as S  # noqa: E402

from options_lab import costs as OC  # noqa: E402
from options_lab.features import gates as G  # noqa: E402

RESULTS = HERE / "results"
STRATS = ("R51", "R72", "LIQ")
WINDOW = 30
BANDS = ("below roll", "roll-quoted", "quoted-stress", "above stress")
COST_KEYS = ("roll", "quoted", "stress", "measured", "cs_wide")


def estimates(Arr: np.ndarray, e: int) -> dict:
    """Relative Roll / AR / CS on the traded minutes of rows [e - 30, e). Only rows < e are read."""
    sl = G.pre_entry_window(D.N, e, WINDOW)
    w = Arr[sl]
    ok = np.nan_to_num(w[:, D.V], nan=0.0) > 0
    w = w[ok]
    return dict(roll=G.roll_relative(w[:, D.C]), ar=G.abdi_ranaldo_spread(w[:, D.H], w[:, D.L], w[:, D.C]),
                cs=G.corwin_schultz_spread(w[:, D.H], w[:, D.L]))


def raw_terms(Arr: np.ndarray, e: int) -> dict:
    """POST-HOC (added after the first Amendment A look, disclosed): the UNFLOORED moment terms of the window, so they can
    be averaged over many trades before the square root. Roll: cov(dr_t, dr_t-1) of log returns; AR: mean of
    (c_t - eta_t)(c_t - eta_t+1) in logs. Rows < e only."""
    sl = G.pre_entry_window(D.N, e, WINDOW)
    w = Arr[sl]
    w = w[(np.nan_to_num(w[:, D.V], nan=0.0) > 0) & np.isfinite(w[:, D.H]) & np.isfinite(w[:, D.L])
          & np.isfinite(w[:, D.C]) & (w[:, D.L] > 0)]
    if len(w) < G.SPREAD_MIN_BARS:
        return dict(roll=None, ar=None)
    lc = np.log(w[:, D.C])
    dr = np.diff(lc)
    a, b = dr[1:], dr[:-1]
    eta = (np.log(w[:, D.H]) + np.log(w[:, D.L])) / 2.0
    return dict(roll=float(np.mean((a - a.mean()) * (b - b.mean()))),
                ar=float(np.mean((lc[:-1] - eta[:-1]) * (lc[:-1] - eta[1:]))))


def pooled(trades: list) -> dict:
    """POST-HOC: pooled relative spreads per index and premium tercile (terciles cut on these trades): Roll
    2 sqrt(-mean cov), AR sqrt(4 mean term), in points at the group's median premium, beside costs.py's law there."""
    out = {}
    for u in D.OPTION_UNDS:
        tu = [t for t in trades if t.und == u and t.m["raw"]["roll"] is not None]
        if len(tu) < 30:
            continue
        cuts = np.quantile([t.m["premium"] for t in tu], [1 / 3, 2 / 3])
        for name, lo, hi in (("low", -np.inf, cuts[0]), ("mid", cuts[0], cuts[1]), ("high", cuts[1], np.inf)):
            g = [t for t in tu if lo < t.m["premium"] <= hi]
            p = float(np.median([t.m["premium"] for t in g]))
            rc = float(np.mean([t.m["raw"]["roll"] for t in g]))
            ar = float(np.mean([t.m["raw"]["ar"] for t in g]))
            roll_rel = 2 * np.sqrt(-rc) if rc < 0 else 0.0
            ar_rel = np.sqrt(4 * ar) if ar > 0 else 0.0
            out[f"{u} {name}"] = dict(n=len(g), premium=round(p, 1), roll_pts=round(roll_rel * p, 2),
                                      ar_pts=round(ar_rel * p, 2),
                                      cs_pts_median=round(float(np.median([t.m["est"]["cs"] for t in g
                                                                           if t.m["est"]["cs"] is not None])) * p, 2),
                                      **{f"law_{r}_pts": round(OC.spread_per_unit(premium=p, regime=r), 2)
                                         for r in A.REGIMES})
    return out


def rel_net(entry: float, exit_: float, qty: int, d: date, rel: float) -> float:
    """Net of a bought round trip when the full spread is `rel` x price (floored at one tick), half crossed each way."""
    def half(p):
        return max(rel * max(p, 0.0), OC.TICK_SIZE) / 2.0
    buy = entry + half(entry)
    sell = max(exit_ - half(exit_), 0.0)
    dn = np.array([(d - date(1970, 1, 1)).days])
    ch = (A.COSTS.charge(True, np.array([buy]), np.array([qty]), dn)[0]
          + A.COSTS.charge(False, np.array([sell]), np.array([qty]), dn)[0])
    return (sell - buy) * qty - ch


def band(pts: float, p: float) -> str:
    law = {g: OC.spread_per_unit(premium=p, regime=g) for g in A.REGIMES}
    if pts < law["roll"]:
        return BANDS[0]
    if pts < law["quoted"]:
        return BANDS[1]
    if pts < law["stress"]:
        return BANDS[2]
    return BANDS[3]


def attach(T: dict) -> None:
    """Add t.m (the estimates, MEASURED points / share, and the re-priced nets) to every R51 / R72 / LIQ trade."""
    store = D.load()
    byday = {u: {d.day: d for d in store[u]} for u in D.OPTION_UNDS}
    for s in STRATS:
        for t in T[s]:
            Arr = byday[t.und][t.day].opts[(t.strike, t.right)]
            est = estimates(Arr, t.entry_row)
            p = t.feat["premium"] or t.entry_raw
            pts = G.measured_spread_pts(list(est.values()), p)
            rel = pts / p if pts else None
            cs_rel = est["cs"]
            t.m = dict(est=est, premium=p, pts=pts, rel=rel, raw=raw_terms(Arr, t.entry_row))
            t.net = dict(t.net)
            t.net["measured"] = rel_net(t.entry_raw, t.exit_raw, t.lot, t.day, rel) if rel is not None else t.net["quoted"]
            t.net["cs_wide"] = (rel_net(t.entry_raw, t.exit_raw, t.lot, t.day, cs_rel) if cs_rel is not None
                                else t.net["quoted"])


def q(x, qs=(0.25, 0.5, 0.75)):
    x = np.asarray([v for v in x if v is not None], float)
    return [round(float(v), 4) for v in np.quantile(x, qs)] if x.size else None


def describe(trades: list) -> dict:
    out = dict(n=len(trades))
    ms = [t for t in trades if t.m["pts"] is not None]
    out["n_measured"] = len(ms)
    for k in ("roll", "ar", "cs"):
        out[f"{k}_share_q"] = q([t.m["est"][k] for t in trades])
        out[f"{k}_n"] = sum(t.m["est"][k] is not None for t in trades)
    out["measured_pts_q"] = q([t.m["pts"] for t in ms])
    out["measured_share_q"] = q([t.m["rel"] for t in ms])
    out["premium_q"] = q([t.m["premium"] for t in trades])
    for g in A.REGIMES:
        out[f"law_{g}_pts_q"] = q([OC.spread_per_unit(premium=t.m["premium"], regime=g) for t in trades])
    cnt = {b: 0 for b in BANDS}
    for t in ms:
        cnt[band(t.m["pts"], t.m["premium"])] += 1
    out["bands_pct"] = {b: round(100 * v / max(len(ms), 1), 1) for b, v in cnt.items()}
    return out


def refit(design: list, oos: list) -> dict:
    """MEASURED points = a + b x premium on design trades (least squares and median), checked on OOS trades."""
    from sklearn.linear_model import QuantileRegressor
    xd = np.array([t.m["premium"] for t in design if t.m["pts"] is not None])
    yd = np.array([t.m["pts"] for t in design if t.m["pts"] is not None])
    xo = np.array([t.m["premium"] for t in oos if t.m["pts"] is not None])
    yo = np.array([t.m["pts"] for t in oos if t.m["pts"] is not None])
    b, a = np.polyfit(xd, yd, 1)
    qr = QuantileRegressor(quantile=0.5, alpha=0.0, solver="highs").fit(xd.reshape(-1, 1), yd)
    laws = {"costs.py roll": lambda x: np.array([OC.spread_per_unit(premium=v, regime="roll") for v in x]),
            "costs.py quoted": lambda x: np.array([OC.spread_per_unit(premium=v, regime="quoted") for v in x]),
            "refit OLS": lambda x: a + b * x,
            "refit median": lambda x: qr.intercept_ + qr.coef_[0] * x}
    mae = {k: round(float(np.median(np.abs(f(xo) - yo))), 3) for k, f in laws.items()}
    bias = {k: round(float(np.median(f(xo) - yo)), 3) for k, f in laws.items()}
    return dict(n_design=int(xd.size), n_oos=int(xo.size), ols=dict(a=round(float(a), 4), b=round(float(b), 6)),
                median=dict(a=round(float(qr.intercept_), 4), b=round(float(qr.coef_[0]), 6)),
                costs_law=dict(a=OC.SPREAD_LAW_INTERCEPT, b=OC.SPREAD_LAW_SLOPE, quoted_scale=OC.REGIME_SCALE["quoted"]),
                oos_median_abs_error_pts=mae, oos_median_bias_pts=bias)


def priced(trades: list) -> dict:
    return {k: S.stats(trades, k) for k in COST_KEYS}


def main():
    T = S.load_trades()
    attach(T)
    out = dict(window_minutes=WINDOW, strategies={}, refit=None)
    allD, allO = [], []
    for s in STRATS:
        tr = T[s]
        des = [t for t in tr if S.in_period(t, "design")]
        oos = [t for t in tr if S.in_period(t, "oos")]
        allD += des
        allO += oos
        row = dict(design=dict(describe=describe(des), priced=priced(des)),
                   oos=dict(describe=describe(oos), priced=priced(oos)), by_index={}, gates={})
        for u in D.OPTION_UNDS:
            ou = [t for t in oos if t.und == u]
            row["by_index"][u] = dict(describe=describe(ou), priced={k: S.stats(ou, k)["per_trade"] for k in COST_KEYS})
        for g in S.GATES:
            kept = [t for t in oos if S.gate_pass(g, t)]
            row["gates"][g] = {k: (None if not kept else round(S.stats(kept, k)["per_trade"] - S.stats(oos, k)["per_trade"], 2))
                               for k in ("quoted", "measured", "cs_wide")}
        out["strategies"][s] = row
        print(s, row["oos"]["describe"]["bands_pct"], {k: v["per_trade"] for k, v in row["oos"]["priced"].items()}, flush=True)
    out["refit"] = refit(allD, allO)
    out["pooled_posthoc"] = dict(design=pooled(allD), oos=pooled(allO))
    out["all_oos_bands_pct"] = describe(allO)["bands_pct"]
    print(out["refit"], flush=True)
    (RESULTS / "measure.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
