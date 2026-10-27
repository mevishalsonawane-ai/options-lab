"""R117 study (PREREG.md): quant risk tools on the paper book, all on top of the app's CAP2.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                       # R101's clean stores (to 13 Oct)
    R51_CLEAN=1 python research/hunt/r117_quant_risk/r117study.py design         # thresholds + design / walk-forward
    R51_CLEAN=1 python research/hunt/r117_quant_risk/r117study.py final          # the ONE final read (refuses a second)
    python research/hunt/r117_quant_risk/r117study.py decide                     # BH + gates -> results/decision.json

The arms and the book come from R108 (r108study.load / candidates, r108book.walk_day); CAP2 must reproduce R108's
published CAP2 in every period before anything else is reported. The rules, flags, measures and gates are r117lib (pure).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np
import pandas as pd

import r117cfg as K
import r108book as B
import r108study as S8
import r117lib as L

NAMES = (K.BASE,) + K.TESTS


# ------------------------------------------------------------------------------------------------ inputs
def lake():
    return S8.mods()["r101cfg"].LAKE


def vix_rows(lo, hi) -> dict:
    """{day: [(row, open)]} of India VIX's 1-minute bars (row 0 = 09:15 IST)."""
    out = defaultdict(list)
    for y in range(lo.year, hi.year + 1):
        p = lake() / f"index_1m_INDIAVIX_{y}.parquet"
        df = pd.read_parquet(p, columns=["ts", "open"])
        ts = pd.to_datetime(df["ts"])
        if ts.dt.tz is None:
            ts = ts.dt.tz_localize("Asia/Kolkata")
        ts = ts.dt.tz_convert("Asia/Kolkata")
        df = df.assign(d=ts.dt.date, row=(ts.dt.hour * 60 + ts.dt.minute) - (9 * 60 + 15))
        df = df[(df["row"] >= 0) & (df["row"] <= 10)]
        for d, r, o in zip(df["d"], df["row"], df["open"]):
            if lo <= d <= hi:
                out[d].append((int(r), float(o)))
    return dict(out)


def nifty_closes() -> dict:
    df = pd.read_parquet(lake() / "index_daily.parquet", columns=["symbol", "date", "close"])
    df = df[df["symbol"] == "NIFTY"]
    return {pd.Timestamp(d).date(): float(c) for d, c in zip(df["date"], df["close"])}


def thresholds(sessions: list) -> dict:
    """V67 / R67 from the DESIGN sessions only (PREREG section 2)."""
    des = [d for d in sessions if K.in_period(d, "design")]
    vo = L.vix_open(vix_rows(des[0], des[-1]), K.VIX_LATEST_FIRST_ROW)
    rv = L.realised_vol(nifty_closes(), des, K.RV_WINDOW)
    return dict(V67=L.percentile([vo[d] for d in des if d in vo], K.PCTL),
                R67=L.percentile([rv[d] for d in des if d in rv], K.PCTL),
                vix_days=sum(1 for d in des if d in vo), rv_days=sum(1 for d in des if d in rv), design_sessions=len(des))


def day_inputs(sessions: list, th: dict, alone: dict) -> dict:
    vo = L.vix_open(vix_rows(sessions[0], sessions[-1]), K.VIX_LATEST_FIRST_ROW)
    rv = L.realised_vol(nifty_closes(), sessions, K.RV_WINDOW)
    return dict(vix_high=L.flags_high(vo, th["V67"]), rv_high=L.flags_high(rv, th["R67"]),
                paused=L.arm_paused(alone, sessions, K.ARM_WINDOW), vix=vo, rv=rv)


# ------------------------------------------------------------------------------------------------ views
def real(walk: dict, period: str) -> list:
    return B.trades_of({d: walk[d] for d in walk if K.in_period(d, period)})


def view(walk: dict, sessions: list, period: str) -> dict:
    sess = [d for d in sessions if K.in_period(d, period)]
    tr = real(walk, period)
    s = B.summary(tr, sess, K.ACCOUNT_MONTH, K.ACCOUNT_DD)
    s.update({f"t_{k}": v for k, v in L.trade_stats([t["net"] for t in tr]).items()})
    s["per_arm"] = {a: dict(n=sum(1 for t in tr if t["arm"] == a), net=round(sum(t["net"] for t in tr if t["arm"] == a)))
                    for a in K.ARMS}
    s["refused"] = sum(1 for d in sess for x in walk.get(d, []) if x["out"] in (B.WB_RULE, B.DROP))
    return s


def part_a(walk: dict, sessions: list, period: str, rng) -> dict:
    sess = [d for d in sessions if K.in_period(d, period)]
    tr = real(walk, period)
    v = B.daily_vector(tr, sess)
    arms = {a: B.daily_vector([t for t in tr if t["arm"] == a], sess) for a in K.ARMS}
    out = dict(trades=L.trade_stats([t["net"] for t in tr]), daily=L.daily_stats(v), arm_corr=L.corr_matrix(arms),
               per_arm={a: L.trade_stats([t["net"] for t in tr if t["arm"] == a]) for a in K.ARMS})
    out["p_dd40k_120"] = round(L.prob_drawdown(v, K.ACCOUNT_DD, K.BOOT_HORIZON, K.BOOT_PATHS, K.BOOT_BLOCK, rng), 4)
    return out


def twin(walk: dict, base: dict, sessions: list, period: str, rng) -> dict:
    sess = [d for d in sessions if K.in_period(d, period)]
    b = L.by_day(real(base, period))
    r = L.by_day(real(walk, period))
    k = L.twin_counts(r, b, sess)
    T = B.twin_matrix(b, k, sess, K.TWINS, rng)
    tr = B.matrix_r_dd(T)
    actual = B.summary(real(walk, period), sess)["r_dd"]
    return dict(p=round(B.twin_p(actual, tr), 6), actual_r_dd=actual, median_r_dd=round(float(np.median(tr)), 4),
                median_net=round(float(np.median(T.sum(axis=1)))), removed=int(sum(k.values())))


def parity(views: dict, phase: str):
    """CAP2 must be R108's published CAP2 (trades and net) in every period of this phase."""
    pub = json.loads((K.R108 / "results" / ("design.json" if phase == "design" else "final.json")).read_text())
    for per in (("design", "wf") if phase == "design" else ("final",)):
        a, b = views[per], pub[per]["CAP2"]
        assert (a["trades"], a["net"]) == (b["trades"], b["net"]), ("CAP2 != R108", per, a["trades"], a["net"], b["trades"], b["net"])
        print("parity ok", per, a["trades"], a["net"], flush=True)


# ------------------------------------------------------------------------------------------------ run
def evaluate(phase: str) -> dict:
    units, alone, sessions = S8.load(phase)
    dc = S8.candidates(units)
    if phase == "design":
        th = thresholds(sessions)
        K.THRESHOLDS.write_text(json.dumps(th, indent=1))
    else:
        if not K.THRESHOLDS.exists():
            raise SystemExit("run the design phase first (thresholds)")
        th = json.loads(K.THRESHOLDS.read_text())
    di = day_inputs(sessions, th, alone)
    W = {n: L.walk_period(sessions, dc, n, vix_high=di["vix_high"], rv_high=di["rv_high"], paused=di["paused"],
                          eq_window=K.EQ_WINDOW) for n in NAMES}
    periods = ("design", "wf") if phase == "design" else ("final",)
    rng = np.random.default_rng(K.SEED + (0 if phase == "design" else 1))
    out = {"_meta": dict(phase=phase, sessions=len(sessions), twins=K.TWINS, seed=K.SEED, n_prior=K.N_PRIOR,
                         thresholds=th)}
    for per in periods:
        res = {n: view(W[n], sessions, per) for n in NAMES}
        if per in ("design", "wf") or phase == "final":
            pass
        res["_part_a"] = part_a(W["CAP2"], sessions, per, rng)
        sess = [d for d in sessions if K.in_period(d, per)]
        res["_days"] = dict(vix_high=sum(1 for d in sess if di["vix_high"].get(d)), rv_high=sum(1 for d in sess if di["rv_high"].get(d)),
                            vix_known=sum(1 for d in sess if d in di["vix"]), rv_known=sum(1 for d in sess if d in di["rv"]),
                            sessions=len(sess),
                            paused_arm_days={a: sum(1 for d in sess if a in di["paused"].get(d, ())) for a in K.ARMS})
        for n in K.TESTS:
            res[n]["twin"] = twin(W[n], W["CAP2"], sessions, per, rng)
            res[n]["changed_vs_cap2"] = B.changed_vs(real(W[n], per), real(W["CAP2"], per))
            print(per, n, res[n]["net"], res[n]["trades"], res[n]["win"], res[n]["max_dd"], res[n]["twin"]["p"], flush=True)
        out[per] = res
    parity({p: out[p]["CAP2"] for p in periods}, phase)
    K.OUT.mkdir(parents=True, exist_ok=True)
    save = {n: B.trades_of(w, outs=(B.REAL, B.WB_RULE, B.WB_HELD, B.DROP)) for n, w in W.items()}
    (K.OUT / f"{phase}.pkl").write_bytes(pickle.dumps(save, protocol=pickle.HIGHEST_PROTOCOL))
    return S8._clean(out)


def run(phase: str):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R117 final already read once: " + K.FINAL_FLAG.read_text())
        if not K.THRESHOLDS.exists():
            raise SystemExit("run the design phase first (thresholds)")
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        S8.mods()["S"].mods()["C"].open_final()
    out = evaluate(phase)
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    ps = [f["final"][t]["twin"]["p"] for t in K.TESTS]
    q_all = B.bh(ps, len(ps) + K.N_PRIOR)
    q_own = B.bh(ps)
    out = {}
    for t, qa, qo in zip(K.TESTS, q_all, q_own):
        per = {p: ((d if p != "final" else f)[p][t], (d if p != "final" else f)[p]["CAP2"]) for p in K.PERIODS}
        g = L.gates(per, qa, f["final"][t]["changed_vs_cap2"], K.PERIODS, K.GATE_Q, K.MIN_CHANGED)
        g["q_r117"] = round(float(qo), 4)
        g["twin_p"] = f["final"][t]["twin"]["p"]
        out[t] = g
    out["_m"] = dict(r117=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
