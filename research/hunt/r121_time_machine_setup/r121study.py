"""R121 study (PREREG.md): the enhanced time machine as a pre-entry gate, design first, then the final read once.

    python research/hunt/r121_time_machine_setup/r121study.py replay     # every entry's R, both variants (cache)
    python research/hunt/r121_time_machine_setup/r121study.py design     # -> results/design.json
    python research/hunt/r121_time_machine_setup/r121study.py final      # ONCE -> results/final.json, report.md

Trade lists: R120's ($R120_OUT/trades/<ARM>.pkl, built by r120arms.py). 28 Oct 2026.
"""
from __future__ import annotations

import json
import math
import pickle
import re
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
R120 = HERE.parent / "r120_time_machine_gate"
sys.path.insert(0, str(R120))
sys.path.insert(0, str(HERE))
import r120cfg as K  # noqa: E402
import r120lib as L  # noqa: E402
import r121lib as X  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
CACHE = K.OUT.parent / "r121"
ARMS = ("R72", "R51", "R52", "LIQ", "SOLO", "HERO", "ORB_ORB", "ORB_FRESH", "ORB_SWEEP", "ORB_FADE", "VIXDIV",
        "PINE_ST_EMA_5M", "PINE_ST_EMA_15M", "PINE_MATRIX_5M")
VARIANTS = {"V_SETUP": X.setup_exits, "V_EXITS": X.real_exits}
RULE = "SKIP_AGAINST"
M_R121 = 2 * len(ARMS)
ENTRIES_LAST_DAY = "2026-10-28"     # every logged family to 28 Oct, R120 included; R121's own never counted
R_EDGES = (-math.inf, -0.4, -0.2, -0.1, -0.05, 0.0, 0.05, 0.1, 0.2, 0.4, math.inf)


def minute_bars(und: str) -> dict:
    """{date: dict(minute, open, high, low, close)} of the index's 1-minute bars, 09:15-15:29 (the phone's window)."""
    frames = [pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"])
              for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet"))]
    df = pd.concat(frames).drop_duplicates("ts", keep="last").sort_values("ts")
    ts = df["ts"].dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df = df.assign(d=ts.dt.date, m=ts.dt.hour * 60 + ts.dt.minute)
    df = df[(df.m >= 555) & (df.m < 930) & np.isfinite(df.close)]
    out = {}
    for d, g in df.groupby("d", sort=True):
        c = g.close.to_numpy(float)
        fill = lambda a: np.where(np.isfinite(a), a, c)  # noqa: E731
        out[d] = dict(minute=g.m.to_numpy(int), open=fill(g.open.to_numpy(float)), high=fill(g.high.to_numpy(float)),
                      low=fill(g.low.to_numpy(float)), close=c)
    return out


def replay() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    bars = {}
    for arm in ARMS:
        with open(K.TRADES / f"{arm}.pkl", "rb") as f:
            tr = pickle.load(f)
        for t in tr:
            if t["und"] not in bars:
                bars[t["und"]] = minute_bars(t["und"])
            day_bars = bars[t["und"]].get(t["day"])
            for v, fn in VARIANTS.items():
                t["r_" + v] = X.replay_r(day_bars, t, fn)
        with open(CACHE / f"{arm}.r.pkl", "wb") as f:
            pickle.dump(tr, f)
        print(arm, len(tr), {v: sum(t["r_" + v] is not None for t in tr) for v in VARIANTS}, flush=True)
    with open(CACHE / "sessions.pkl", "wb") as f:
        pickle.dump({u: sorted(b) for u, b in bars.items()}, f)


def with_verdicts(arm: str, sessions: dict) -> list:
    with open(CACHE / f"{arm}.r.pkl", "rb") as f:
        tr = pickle.load(f)
    by_und = defaultdict(list)
    for t in tr:
        by_und[t["und"]].append(t)
    for v in VARIANTS:
        for u, ts in by_und.items():
            entries = [(t["day"], t["r_" + v]) for t in ts]
            for t in ts:
                if t["period"] not in ("design", "final"):
                    continue
                vd = X.verdict_of(X.sample_before(entries, t["day"], sessions[u]))
                t["v_" + v], t["n_" + v], t["mean_" + v] = vd.verdict, vd.n, vd.mean
    return [t for t in tr if t["period"] in ("design", "final")]


def part(tr, per):
    if per == "wf":
        return [t for t in tr if t["period"] == "design" and K.in_wf(t["day"])]
    return [t for t in tr if t["period"] == per]


def cell(tr, v, seed):
    keep = [t["v_" + v] != "against" for t in tr]
    base, after = L.metrics(tr), L.metrics(tr, keep)
    tw = L.twins(np.array([t["net"] for t in tr]), after["skipped"], seed=seed)
    return dict(base=base, rule=after, p=round(L.twin_p(after["net"], tw), 4),
                twin_p95=round(float(np.percentile(tw, 95))) if len(tr) else 0)


def by_verdict(tr, v):
    g = defaultdict(list)
    for t in tr:
        g[t["v_" + v]].append(t["net"])
    return {k: dict(n=len(x), win=round(float(np.mean([y > 0 for y in x])), 3), mean=round(float(np.mean(x)), 1))
            for k, x in sorted(g.items())}


def calib(tr, v):
    pts = [(t["mean_" + v], t["net"] > 0) for t in tr if t["mean_" + v] == t["mean_" + v]]
    if len(pts) < 3:
        return dict(n=len(pts))
    m = np.array([a for a, _ in pts])
    y = np.array([b for _, b in pts], float)
    bins = []
    for lo, hi in zip(R_EDGES[:-1], R_EDGES[1:]):
        s = (m >= lo) & (m < hi)
        if s.any():
            bins.append(dict(bin=f"{lo:+.2f}..{hi:+.2f} R", n=int(s.sum()), actual=round(float(y[s].mean()), 3)))
    corr = float(np.corrcoef(m, y)[0, 1]) if m.std() > 0 and y.std() > 0 else None
    return dict(n=len(pts), corr=None if corr is None else round(corr, 4), bins=bins)


def count_prior() -> int:
    import yaml
    fam, var = defaultdict(int), defaultdict(int)
    for f in sorted(K.ENTRIES.glob("*.yaml")):
        y = yaml.safe_load(f.read_text()) or {}
        if str(y.get("date", "")) > ENTRIES_LAST_DAY or str(y.get("id", "")).startswith("r121"):
            continue
        i = str(y.get("id", ""))
        mm = re.match(r"r(\d+)", i)
        key = ("R" + mm.group(1)) if mm else re.split(r"[-_]", i)[0]
        fam[key] += 1
        try:
            n = int(y.get("variants_tried") or 1)
        except (TypeError, ValueError):
            n = 1
        var[key] += max(n, 1)
    return sum(max(fam[k], var[k]) for k in fam)


def run(phase: str) -> dict:
    with open(CACHE / "sessions.pkl", "rb") as f:
        sessions = pickle.load(f)
    if phase == "final":
        if FINAL_FLAG.exists():
            raise SystemExit("R121's final has been read once already")
        res = json.loads((RESULTS / "design.json").read_text())
        FINAL_FLAG.write_text("R121 final read once, 28 Oct 2026\n")
    else:
        res = dict(arms={})
    pers = ("design", "wf") if phase == "design" else ("final",)
    for arm in ARMS:
        tr = with_verdicts(arm, sessions)
        a = res["arms"].setdefault(arm, dict(name=K.ARM_NAME[arm], cells={}, verdicts={}, calibration={}))
        for i, v in enumerate(VARIANTS):
            for per in pers:
                x = part(tr, per)
                a["cells"].setdefault(v, {})[per] = cell(x, v, K.SEED + 1000 + 10 * i + (100 if per == "final" else 0))
                a["verdicts"].setdefault(v, {})[per] = by_verdict(x, v)
            if phase == "final":
                a["calibration"][v] = dict(design=calib(part(tr, "design"), v), final=calib(part(tr, "final"), v))
    if phase == "final":
        m_prior = count_prior()
        m_all = m_prior + M_R121
        keys = [(a, v) for a in res["arms"] for v in VARIANTS]
        ps = [res["arms"][a]["cells"][v]["final"]["p"] for a, v in keys]
        qa, qo = L.bh(ps, m_all), L.bh(ps, M_R121)
        tests = {}
        for (a, v), q1, q2 in zip(keys, qa, qo):
            c = res["arms"][a]["cells"][v]
            g = L.gates({p: c[p]["rule"] for p in ("design", "wf", "final")},
                        {p: c[p]["base"] for p in ("design", "wf", "final")}, c["final"]["p"], float(q1))
            tests[f"{a}|{v}"] = dict(arm=a, variant=v, p=c["final"]["p"], q_all=round(float(q1), 4),
                                     q_r121=round(float(q2), 4), gates=g)
        pooled = {}
        for v in VARIANTS:
            allt = []
            for arm in ARMS:
                allt += with_verdicts(arm, sessions)
            pooled[v] = calib(allt, v)
        res.update(tests=tests, m_prior=m_prior, m_all=m_all, m_r121=M_R121, pooled_calibration=pooled,
                   passed=[k for k, t in tests.items() if t["gates"]["passed"]])
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{phase}.json").write_text(json.dumps(res, indent=1, default=str))
    return res


if __name__ == "__main__":
    cmd = (sys.argv[1:] or ["help"])[0]
    if cmd == "replay":
        replay()
    elif cmd in ("design", "final"):
        r = run(cmd)
        for a, x in r["arms"].items():
            for v, c in x["cells"].items():
                print(a, v, {p: (c[p]["base"]["net"], c[p]["rule"]["net"], c[p]["rule"]["skipped"], c[p]["p"])
                             for p in c})
        if cmd == "final":
            print("passed:", r["passed"], "m_all", r["m_all"])
    else:
        print(__doc__)
