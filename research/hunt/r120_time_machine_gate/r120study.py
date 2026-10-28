"""R120 study (PREREG.md sections 3-5): verdicts per trade, then design, then the final read once.

    python research/hunt/r120_time_machine_gate/r120study.py verdicts <ARM ...>   # the app's check before each entry
    python research/hunt/r120_time_machine_gate/r120study.py design               # design + walk-forward -> results/design.json
    python research/hunt/r120_time_machine_gate/r120study.py final                # the final, ONCE -> results/final.json

The verdict of each trade uses only the underlying's sessions dated before the trade's day (r120lib.before + tm.usable).
28 Oct 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r120cfg as K  # noqa: E402
import r120lib as L  # noqa: E402

_SESS: dict = {}


def sessions(und: str, bm: int) -> dict:
    key = (und, bm)
    if key not in _SESS:
        frames = [pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"])
                  for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet"))]
        if not frames:
            raise FileNotFoundError(f"no index_1m_{und}_*.parquet in {K.LAKE}")
        df = pd.concat(frames).drop_duplicates("ts", keep="last").sort_values("ts")
        _SESS[key] = L.sessions_from_frame(df, bm)
    return _SESS[key]


def _one(t: dict) -> dict:
    o = L.verdict(t, sessions(t["und"], int(t["bar_minutes"])))
    return dict(verdict=o.verdict, used=o.used, sessions=o.sessions, target_first=o.target_first,
                stop_first=o.stop_first, neither=o.neither, break_even=o.break_even, decided_win=o.decided_win_rate,
                target_pct=o.target_pct, why=o.why)


def verdicts(arm: str) -> None:
    with open(K.TRADES / f"{arm}.pkl", "rb") as f:
        trades = pickle.load(f)
    trades = [t for t in trades if t["period"] in ("design", "final")]
    trades.sort(key=lambda t: (t["und"], t["day"], t["entry_ts"]))
    with Pool(4) as p:
        res = p.map(_one, trades, chunksize=64)
    for t, r in zip(trades, res):
        t.update(r)
    with open(K.TRADES / f"{arm}.v.pkl", "wb") as f:
        pickle.dump(trades, f)
    print(arm, len(trades), dict(Counter(t["verdict"] for t in trades)), flush=True)


def load_v(arm: str) -> list | None:
    p = K.TRADES / f"{arm}.v.pkl"
    if not p.exists():
        return None
    with open(p, "rb") as f:
        return pickle.load(f)


def by_verdict(tr: list) -> dict:
    g = defaultdict(list)
    for t in tr:
        g[t["verdict"]].append(t["net"])
    return {v: dict(n=len(x), win=round(float(np.mean([y > 0 for y in x])), 3), mean=round(float(np.mean(x)), 1),
                    net=round(float(np.sum(x))))
            for v, x in sorted(g.items())}


def part(tr: list, per: str) -> list:
    if per == "wf":
        return [t for t in tr if t["period"] == "design" and K.in_wf(t["day"])]
    return [t for t in tr if t["period"] == per]


def cell(tr: list, rule: str, seed: int, twins: bool) -> dict:
    v = [t["verdict"] for t in tr]
    keep = L.keep_mask(v, rule)
    base, after = L.metrics(tr), L.metrics(tr, keep)
    out = dict(base=base, rule=after)
    if twins:
        tw = L.twins(np.array([t["net"] for t in tr]), after["skipped"], seed=seed)
        out.update(p=round(L.twin_p(after["net"], tw), 4), twin_p95=round(float(np.percentile(tw, 95))),
                   twin_median=round(float(np.median(tw))))
    return out


def calib(tr: list, base_rate: float | None) -> dict:
    return L.calibration([t["decided_win"] for t in tr], [t["net"] > 0 for t in tr], base_rate)


def design() -> dict:
    res = dict(arms={}, untested={})
    for arm in K.ARMS:
        tr = load_v(arm)
        if tr is None:
            res["untested"][arm] = "no trade list rebuilt"
            continue
        a = dict(name=K.ARM_NAME[arm], verdicts={}, cells={}, calibration={})
        for per in ("design", "wf"):
            x = part(tr, per)
            a["verdicts"][per] = by_verdict(x)
            for i, rule in enumerate(K.RULES):
                a["cells"].setdefault(rule, {})[per] = cell(x, rule, K.SEED + i, twins=True)
        d = part(tr, "design")
        br = float(np.mean([t["net"] > 0 for t in d])) if d else None
        a["design_win_rate"] = br
        a["calibration"]["design"] = calib(d, br)
        res["arms"][arm] = a
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(res, indent=1, default=str))
    return res


def final() -> dict:
    if K.FINAL_FLAG.exists():
        raise SystemExit("R120's final has been read once already (results/FINAL_READ)")
    if not (K.RESULTS / "design.json").exists():
        raise SystemExit("run design first")
    K.FINAL_FLAG.write_text("R120 final read once, 28 Oct 2026\n")
    des = json.loads((K.RESULTS / "design.json").read_text())
    m_prior = L.count_prior()
    m_all = m_prior + K.M_R120
    rows, pooled_pred, pooled_won = {}, [], []
    for arm, a in des["arms"].items():
        tr = load_v(arm)
        x = part(tr, "final")
        a["verdicts"]["final"] = by_verdict(x)
        for i, rule in enumerate(K.RULES):
            a["cells"][rule]["final"] = cell(x, rule, K.SEED + 100 + i, twins=True)
        a["calibration"]["final"] = calib(x, a["design_win_rate"])
        for t in part(tr, "design") + x:
            pooled_pred.append(t["decided_win"])
            pooled_won.append(t["net"] > 0)
        for rule in K.RULES:
            rows[(arm, rule)] = a["cells"][rule]
    keys = list(rows)
    ps = [rows[k]["final"]["p"] for k in keys]
    q_all = L.bh(ps, m_all)
    q_own = L.bh(ps, K.M_R120)
    tests = {}
    for k, qa, qo in zip(keys, q_all, q_own):
        c = rows[k]
        g = L.gates({p: c[p]["rule"] for p in ("design", "wf", "final")},
                    {p: c[p]["base"] for p in ("design", "wf", "final")}, c["final"]["p"], float(qa))
        tests[f"{k[0]}|{k[1]}"] = dict(arm=k[0], rule=k[1], q_all=round(float(qa), 4), q_r120=round(float(qo), 4),
                                      p=c["final"]["p"], gates=g)
    des.update(tests=tests, m_prior=m_prior, m_all=m_all, m_r120=K.M_R120,
               pooled_calibration=L.calibration(pooled_pred, pooled_won,
                                                float(np.mean(pooled_won)) if pooled_won else None),
               passed=[k for k, v in tests.items() if v["gates"]["passed"]])
    (K.RESULTS / "final.json").write_text(json.dumps(des, indent=1, default=str))
    return des


if __name__ == "__main__":
    cmd = sys.argv[1:] or ["help"]
    if cmd[0] == "verdicts":
        for a in cmd[1:]:
            verdicts(a)
    elif cmd[0] == "design":
        r = design()
        print(json.dumps({a: {ru: {p: (c[p]["base"]["net"], c[p]["rule"]["net"], c[p]["rule"]["skipped"], c[p].get("p"))
                                    for p in c} for ru, c in v["cells"].items()} for a, v in r["arms"].items()}, indent=1))
    elif cmd[0] == "final":
        r = final()
        print("passed:", r["passed"], "m_all", r["m_all"])
    else:
        print(__doc__)
