"""R192 study (PREREG.md section 8). From the repo root, after `r192build.py real`:

    python3 -I research/hunt/r192_long_history/r192study.py calib    # fit `a` on eras A + B entry premiums
    python3 -I research/hunt/r192_long_history/r192build.py syn      # synthetic legs with that `a`
    python3 -I research/hunt/r192_long_history/r192study.py calib2   # synthetic vs real net per hold type (A+B fit, C check)
    python3 -I research/hunt/r192_long_history/r192study.py k4       # K4's filter, chosen on 3 Apr 2017 - 31 Dec 2018 only
    python3 -I research/hunt/r192_long_history/r192study.py run      # every idea, every era, twins, BH, gate
    python3 -I research/hunt/r192_long_history/r192study.py report   # summary.md, report.md, decision.json
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r192cfg as K  # noqa: E402

import numpy as np  # noqa: E402

import r192lib as G  # noqa: E402

CAL = K.RESULTS / "calibration.json"
K4J = K.RESULTS / "k4_choice.json"
RUNJ = K.RESULTS / "r192.json"


def rows_real() -> list:
    sys.path.insert(0, str(K.HUNT / "r51"))
    return pickle.loads((K.OUT / "rows_real.pkl").read_bytes())


def rows_all() -> list:
    sys.path.insert(0, str(K.HUNT / "r51"))
    return pickle.loads((K.OUT / "rows.pkl").read_bytes())


# ================================================================================================ calibration
def _arrays(cal: list, w):
    S = np.array([c[0] for c in cal])
    Kx = np.array([c[1] for c in cal])
    T = np.array([float(G.clock_T(c[2], c[3], c[4], None if w is None else (c[5], w))) for c in cal])
    sg = np.array([c[6] for c in cal])
    cp = np.array([c[7] for c in cal])
    real = np.array([c[8] for c in cal])
    return S, Kx, T, sg, cp, real


def _syn(S, Kx, T, sig, cp):
    return np.maximum(np.where(cp > 0, G.b76(S, Kx, T, sig, 1), G.b76(S, Kx, T, sig, -1)), K.TICK)


def fit_a(cal: list, w=None) -> tuple:
    """Least squares on log premium over the grid. cal: [(S, K, day, row, expiry, n_between, sigma_base, cp, real)]."""
    S, Kx, T, sg, cp, real = _arrays(cal, w)
    best = None
    for a in K.A_GRID:
        sse = float(np.mean((np.log(_syn(S, Kx, T, a * sg, cp)) - np.log(real)) ** 2))
        if best is None or sse < best[1]:
            best = (a, sse)
    return best


def premium_errors(cal: list, a: float, w=None) -> dict:
    S, Kx, T, sg, cp, real = _arrays(cal, w)
    syn = _syn(S, Kx, T, a * sg, cp)
    rel = syn / real - 1.0
    return dict(n=int(len(rel)), median_rel_error=float(np.median(rel)), median_abs_rel_error=float(np.median(np.abs(rel))),
                mean_abs_rel_error=float(np.mean(np.abs(rel))), mean_real=float(real.mean()), mean_syn=float(syn.mean()))


W_GRID = tuple(round(0.05 * i, 2) for i in range(21))


def calib():
    """Amendment 1: choose w (and a for it) on eras A + B only."""
    import r192build as B
    rows = rows_real()
    cal = {"AB": [], "C": []}
    for r in rows:
        if r["era"] not in K.REAL_ERAS:
            continue
        g = "C" if r["era"] == "C" else "AB"
        for t, p in r["pools"].items():
            for s in (1, -1):
                x = p[s].get("real")
                if x is not None and x.get("cal") is not None and x["e_raw"] > 0:
                    cal[g].append(tuple(x["cal"]) + (x["e_raw"],))
    W = B.World(with_store=True)
    ab = [r for r in rows if r["era"] in ("A", "B")]
    grid = {}
    for w in W_GRID:
        a, sse = fit_a(cal["AB"], w)
        err = {}
        for t in B.TIMINGS_ALL:
            e = []
            for r in ab:
                for s in (1, -1):
                    x = r["pools"][t][s].get("real")
                    if x is None:
                        continue
                    y = W.syn_leg(r["i"], t, s, a, w)
                    if y is not None:
                        e.append(y["net"] - x["net"])
            err[t] = float(np.mean(e)) if e else 0.0
        grid[str(w)] = dict(a=a, rmse_log=float(np.sqrt(sse)), mean_net_error=err,
                            objective=float(sum(v * v for v in err.values())))
        print(w, grid[str(w)], flush=True)
    wbest = min(W_GRID, key=lambda w: grid[str(w)]["objective"])
    a = grid[str(wbest)]["a"]
    out = dict(a=a, w=wbest, clock="trading-time (amendment 1)",
               fit="w: minimum sum over hold types of the squared mean net error, era A + B pool trades; a: least squares "
                   "on log entry premium (every timing, both sides) for that w",
               premium_AB=premium_errors(cal["AB"], a, wbest), premium_C=premium_errors(cal["C"], a, wbest), grid=grid,
               written=datetime.now(timezone.utc).isoformat(timespec="seconds"))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    CAL.write_text(json.dumps(out, indent=1))
    print("chosen w", wbest, "a", a)


def calib2():
    rows = rows_all()
    out = json.loads(CAL.read_text())
    net = {}
    for key in ("syn", "syn_cal"):
        for grp, eras in (("AB", ("A", "B")), ("C", ("C",))):
            for t in ("ON", "CC1", "CC2", "D2", "G5", "G6"):
                rv, sv = [], []
                for r in rows:
                    if r["era"] not in eras or t not in r["pools"]:
                        continue
                    for s in (1, -1):
                        x, y = r["pools"][t][s].get("real"), r["pools"][t][s].get(key)
                        if x is not None and y is not None:
                            rv.append(x["net"])
                            sv.append(y["net"])
                rv, sv = np.array(rv), np.array(sv)
                if not len(rv):
                    continue
                net[f"{key}:{grp}:{t}"] = dict(n=int(len(rv)), mean_real=float(rv.mean()), mean_syn=float(sv.mean()),
                                               mean_error=float((sv - rv).mean()), mae=float(np.abs(sv - rv).mean()),
                                               corr=float(np.corrcoef(rv, sv)[0, 1]) if len(rv) > 2 else None,
                                               sign_agree=float(np.mean(np.sign(rv) == np.sign(sv))))
    out["net"] = net
    CAL.write_text(json.dumps(out, indent=1))
    for k, v in net.items():
        print(k, {kk: round(vv, 3) if isinstance(vv, float) else vv for kk, vv in v.items()})


# ================================================================================================ trades
def leg_of(r: dict, idea: str, side: int, syn_key: str = "syn"):
    t = K.TIMING[idea]
    p = r["pools"].get(t)
    if p is None:
        return None
    return p[side].get(syn_key if r["era"] in K.SYN_ERAS else "real")


def k4_side(r: dict, f: str) -> int:
    s = r["sides"]["K4"]
    return s if s != 0 and G.k4_allows(f, s, r["ctx"]) else 0


def k4_choose(rows: list) -> dict:
    lo, hi = K.K4_SEL
    res = {}
    for f in K.K4_FILTERS:
        nets = []
        for r in rows:
            if not (lo <= r["day"] <= hi):
                continue
            s = k4_side(r, f)
            if s == 0:
                continue
            x = r["pools"].get("ON", {}).get(s, {}).get("syn")
            if x is not None:
                nets.append(x["net"])
        res[f] = dict(trades=len(nets), net=float(np.sum(nets)) if nets else 0.0,
                      per_trade=float(np.mean(nets)) if nets else 0.0)
    best = "F0"
    for f in K.K4_FILTERS[1:]:
        if res[f]["trades"] >= K.K4_MIN_SEL and res[f]["per_trade"] > res[best]["per_trade"]:
            best = f
    return dict(choice=best, window=[str(lo), str(hi)], menu=res, metric="synthetic net per trade, 1 lot, >= 60 trades")


def k4():
    out = k4_choose(rows_all())
    out["written"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    K4J.write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1))


def idea_trades(rows: list, idea: str, k4f: str = "F0", syn_key: str = "syn") -> list:
    out = []
    for r in rows:
        if idea == "K4":
            if r["era"] == "S" and not (K.K4_S[0] <= r["day"] <= K.K4_S[1]):
                continue
            if r["era"] == "S0":
                continue
            s = k4_side(r, k4f)
        else:
            if r["era"] == "S0" and idea not in K.DAILY_OK:
                continue
            s = r["sides"][idea]
        if s == 0:
            continue
        x = leg_of(r, idea, s, syn_key)
        if x is None:
            continue
        out.append(dict(day=r["day"], era=r["era"], side=s, net=x["net"], stress=x["stress"], prem=x["prem"],
                        exit_how=x["exit_how"], why=x["why"]))
    return out


def twin_pools(rows: list, idea: str) -> dict:
    t = K.TIMING[idea]
    pools = {}
    for e in K.REAL_ERAS:
        pc, pp = [], []
        for r in rows:
            if r["era"] != e or t not in r["pools"]:
                continue
            a, b = r["pools"][t][1].get("real"), r["pools"][t][-1].get("real")
            if a is not None and b is not None:
                pc.append(a["net"])
                pp.append(b["net"])
        pools[e] = (np.array(pc), np.array(pp))
    return pools


def run():
    rows = rows_all()
    k4f = json.loads(K4J.read_text())["choice"]
    rng = np.random.default_rng(K.SEED)
    res = {}
    for idea in K.IDEAS:
        tr = idea_trades(rows, idea, k4f)
        eras = {e: G.stats([t for t in tr if t["era"] == e]) for e in K.ERAS}
        stress = {e: G.stats([t for t in tr if t["era"] == e], "stress") for e in K.REAL_ERAS}
        trc = idea_trades(rows, idea, k4f, "syn_cal")
        eras_cal = {e: G.stats([t for t in trc if t["era"] == e]) for e in K.SYN_ERAS}
        real = [t for t in tr if t["era"] in K.REAL_ERAS]
        counts = {e: eras[e]["trades"] for e in K.REAL_ERAS}
        pools = twin_pools(rows, idea)
        twins = G.pooled_twins(pools, counts, rng)
        rule_net = float(sum(t["net"] for t in real))
        pe, pn = G.twin_p(rule_net, twins)
        why = {}
        for t in real:
            why[t["why"]] = why.get(t["why"], 0) + 1
        res[idea] = dict(name=NAME(idea, k4f), timing=K.TIMING[idea], eras=eras, stress=stress, eras_calendar_clock=eras_cal,
                         pooled_real=G.stats(real), pooled_net=rule_net, twin_mean=float(twins.mean()),
                         twin_sd=float(twins.std(ddof=1)), p_emp=pe, p_norm=pn, exits=why,
                         need=list(("S0",) if idea in K.DAILY_OK else ()) + ["S", "A", "B", "C"],
                         calls=int(sum(1 for t in tr if t["side"] > 0)), puts=int(sum(1 for t in tr if t["side"] < 0)),
                         pool_days={e: int(len(pools[e][0])) for e in K.REAL_ERAS})
    ids = list(res)
    q = G.bh_total([res[i]["p_norm"] for i in ids], K.M_TOTAL)
    for i, qq in zip(ids, q):
        res[i]["q"] = float(qq)
        res[i]["fails"] = G.gate(res[i]["eras"], tuple(res[i]["need"]), res[i]["p_emp"], float(qq))
        res[i]["reliable"] = not res[i]["fails"]
        res[i]["breach"] = {e: G.account_breach(res[i]["eras"][e]) for e in K.REAL_ERAS}
        alt = dict(res[i]["eras"], **res[i]["eras_calendar_clock"])
        res[i]["fails_calendar_clock"] = G.gate(alt, tuple(res[i]["need"]), res[i]["p_emp"], float(qq))
    out = dict(k4_filter=k4f, m_total=K.M_TOTAL, twins=K.TWINS, seed=K.SEED, ideas=res,
               written=datetime.now(timezone.utc).isoformat(timespec="seconds"))
    RUNJ.write_text(json.dumps(out, indent=1, default=str))
    for i in ids:
        r = res[i]
        print(i, "RELIABLE" if r["reliable"] else "no", {e: (r["eras"][e]["trades"], round(r["eras"][e]["net"])) for e in K.ERAS},
              round(r["p_emp"], 4), round(r["q"], 3), flush=True)


def NAME(idea: str, k4f: str) -> str:
    return K.NAMES[idea] + (f" [filter {k4f}]" if idea == "K4" else "")


def report():
    import r192report
    r192report.write()


if __name__ == "__main__":
    {"calib": calib, "calib2": calib2, "k4": k4, "run": run, "report": report}.get(
        (sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
