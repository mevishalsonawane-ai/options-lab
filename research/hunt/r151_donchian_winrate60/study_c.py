"""R151 Amendment C: the R72 logistic picker (R72-20-4-M2, score >= 0.4656) over Nov 2024 - Oct 2026.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/study_c.py run
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.argv = [sys.argv[0], "design"]
import numpy as np  # noqa: E402

import study as T  # noqa: E402

S, K = T.S, T.K
PID, ARM, THR = "R72-20-4-M2", "R72", 0.4656
SEED = 1513
WF0 = T.FOLDS[0][0]


def period_of(day) -> str:
    if day < WF0:
        return "insample_nov24_mar25"
    if day <= K.DESIGN_END:
        return "wf_apr25_mar26"
    return "final_apr_oct26"


def run() -> dict:
    S.mods()["C"].open_final()
    d = json.loads((T.RESULTS / "design.json").read_text())
    mdl, params = pickle.loads((T.CACHE / "models.pkl").read_bytes())[PID]
    us = T.units(ARM, tuple(params), K.START, K.FINAL_END)
    fx = T.feats_of_units(us)
    trades = S.walk(ARM, us)
    des = [t for t in trades if t["day"] <= K.DESIGN_END]
    keys = [T.cand_key(k[0], k[1], c["er"], c["side"]) for k, cs, _, _ in us for c in cs]
    frozen = dict(zip(keys, mdl.predict_proba(T.matrix(keys, fx))[:, 1]))
    oof = T.oof_scores(ARM, [u for u in us if u[0][1] <= K.DESIGN_END], des, fx, "M2")
    sc = {k: (oof.get(k, -1.0) if WF0 <= k[1] <= K.DESIGN_END else float(frozen[k])) for k in keys}
    kept = T.kept_walk(ARM, us, sc, THR)
    out = {"_meta": dict(pid=PID, thr=THR, written=datetime.now(timezone.utc).isoformat(),
                         note="Nov 2024 - Mar 2025 scores are in-sample (frozen model trained on them): not counted")}
    for p in ("insample_nov24_mar25", "wf_apr25_mar26", "final_apr_oct26"):
        out[p] = {"picker": T.summ([t for t in kept if period_of(t["day"]) == p]),
                  "arm": T.summ([t for t in trades if period_of(t["day"]) == p])}
    oos = ("wf_apr25_mar26", "final_apr_oct26")
    out["apr25_oct26"] = {"picker": T.summ([t for t in kept if period_of(t["day"]) in oos]),
                          "arm": T.summ([t for t in trades if period_of(t["day"]) in oos])}
    out["nov24_oct26_incl_insample"] = {"picker": T.summ(kept), "arm": T.summ(trades)}
    # the one new read: Apr - Oct 2026, gate + twin
    lo, hi = K.FINAL_START, K.FINAL_END
    us_f = [u for u in us if lo <= u[0][1] <= hi]
    m, a = out["final_apr_oct26"]["picker"], out["final_apr_oct26"]["arm"]
    nkeep = sum(1 for k, cs, _, _ in us_f for c in cs if sc[T.cand_key(k[0], k[1], c["er"], c["side"])] >= THR)
    p = T.twin_win_p(ARM, us_f, nkeep, m.get("win", 0.0), lo, hi, np.random.default_rng(SEED)) if m["trades"] else 1.0
    fails = []
    if m["trades"] < 60:
        fails.append("< 60 trades")
    if m.get("win", 0) < 0.50:
        fails.append(f"win {m.get('win', 0):.3f} < 0.50")
    if not m.get("net", 0) > 0:
        fails.append("net <= 0")
    if m.get("per_trade", -1e9) < a["per_trade"]:
        fails.append("per trade below the arm's")
    if p > 0.05:
        fails.append(f"twin p {p:.3f} > 0.05")
    out["gate"] = {"twin_win_p": p, "kept_candidates": nkeep, "pass": not fails, "fails": fails}
    curve = []
    for c in d[ARM]["pickers"][PID]["curve"]:
        mm = T.summ([t for t in T.kept_walk(ARM, us_f, sc, c["thr"]) if lo <= t["day"] <= hi])
        mm["thr"] = c["thr"]
        curve.append(mm)
    out["final_curve"] = curve
    (T.RESULTS / "c_r72_picker.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    r = run()
    for k, v in r.items():
        if k not in ("_meta", "final_curve"):
            print(k, v)
