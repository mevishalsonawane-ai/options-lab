"""R130 Amendment 3 (PREREG_WR.md): can the win rate reach 65% while still making money? Walk-forward pick.

    python research/hunt/r130_sweep_mss_fvg/r130wr.py design   # 32 versions + the walk-forward -> results/design_wr.json
    python research/hunt/r130_sweep_mss_fvg/r130wr.py seen     # the version picked on all design, read once on
                                                               # Apr-Oct 2026 ("already seen") -> results/seen_wr.json

Base: W1 (wick-only), 3-bar swing liquidity, all day, 1-ITM, no profit lock, a trend filter. Factors: S minimum stop,
G minimum target distance, X stop placement (FVG / beyond the sweep), T trend (15-min / 15-min and 60-min). Signals on
the index (wicks); the option is what is bought.
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r130 as R  # noqa: E402
import r130enh as E  # noqa: E402

K, D3 = R.K, R.D3
SEED = 1303
TWINS = R.TWINS
FAMILY = 138
S_LV = (0.0, 0.001)
G_LV = (0.0, 0.001, 0.0015, 0.002)
X_LV = ("fvg", "sweep")
T_LV = (1, 2)
VERSIONS = list(itertools.product(range(2), range(4), range(2), range(2)))
STEPS = [("2023", (None, 2022)), ("2024", (None, 2023)), ("2025", (None, 2024)), ("2026", (None, 2025))]


def vname(v) -> str:
    return "WR-S{}G{}X{}T{}".format(*v)


def parse(nm: str) -> tuple:
    return tuple(int(c) for c in nm[4::2])


def describe(v) -> str:
    s, g, x, t = v
    return ", ".join([f"stop >= {S_LV[s]:.2%}" if s else "any stop", f"target >= {G_LV[g]:.2%}" if g else "any target",
                      "stop beyond the sweep" if x else "stop at the FVG",
                      "15-min and 60-min trend" if t else "15-min trend"])


def run_index(args) -> dict:
    und, part, only = args
    lo, hi = K.DESIGN if part == "design" else K.HOLDOUT
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    t15, t60 = E.trend_grid(g, 15), E.trend_grid(g, 60)
    trends = {0: lambda d, r: int(t15[d, r]),
              1: lambda d, r: int(t15[d, r]) if t15[d, r] == t60[d, r] else 0}
    sig, meta = {}, {}
    for v in (only or VERSIONS):
        s, gg, x, t = v
        cfg = dict(R.WICK_VARIANTS["W1"], stop=X_LV[x])
        sig[v], meta[v] = R.scan(g, cfg, blocked, keep, trend=trends[t], min_stop_pct=S_LV[s], min_tgt_pct=G_LV[gg])
    by_day = {}
    for v, T_ in sig.items():
        for tr in T_:
            by_day.setdefault(tr["d"], []).append((v, tr))
    rows = []
    for d in sorted(by_day):
        day = g.days[d]
        src = Mo if day in wexp else W
        opts = src.day(day, g.c[d])
        if opts is None:
            continue
        H, L_, O = g.h[d], g.l[d], g.o[d]
        for v, tr in by_day[d]:
            sd, r0, xr = tr["side"], tr["entry"], tr["exit"]
            cp = "C" if sd > 0 else "P"
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                continue
            res = E.option_locks(opts.contracts.get((cp, E.strike(ref_c, step, sd, 1))), r0, xr, tr["why"], lot,
                                 day, und)
            if res is None:
                continue
            mh, ml, mo = (H, L_, O) if sd > 0 else (-L_, -H, -O)
            tw = []
            for _ in range(TWINS):
                r1 = int(rng.integers(R.FIRST_TWIN, R.LAST_ENTRY + 1))
                ref1, c1 = mo[r1], D3._last(g.c[d][:r1])
                x = None
                if np.isfinite(ref1) and np.isfinite(c1):
                    e1 = R.exit_on(mh, ml, mo, r1, ref1 - tr["stop_pts"], ref1 + tr["tgt_pts"], K.K3.EXIT_LAST)
                    if e1 is not None:
                        x = E.option_locks(opts.contracts.get((cp, E.strike(c1, step, sd, 1))), r1, e1[0], e1[1],
                                           lot, day, und)
                tw.append(np.nan if x is None else x[0][0])
            rows.append(dict(tr, version=vname(v), und=und, net=res[0][0], netx2=res[0][1], twins=tw))
    print(f"R130wr {part} {und}: {len(rows)} version-trades", flush=True)
    return dict(rows=rows, skips={vname(v): m["skips"] for v, m in meta.items()})


def pick(T: pd.DataFrame, names: list) -> str:
    """PREREG_WR section 3: >= 50 trades, avg net > 0 and win >= 65% -> the highest total; else >= 50 trades and
    avg net > 0 -> the highest win rate; else the highest total."""
    st = {}
    for nm in names:
        x = T.net[T.version == nm]
        st[nm] = (len(x), float(x.mean()) if len(x) else -1e9, float((x > 0).mean()) if len(x) else 0.0,
                  float(x.sum()) if len(x) else -1e18)
    a = [nm for nm, s in st.items() if s[0] >= 50 and s[1] > 0 and s[2] >= 0.65]
    if a:
        return max(a, key=lambda nm: st[nm][3])
    b = [nm for nm, s in st.items() if s[0] >= 50 and s[1] > 0]
    if b:
        return max(b, key=lambda nm: st[nm][2])
    return max(st, key=lambda nm: st[nm][3])


def walk_forward(T: pd.DataFrame, names: list) -> dict:
    yr = np.array([d.year for d in T.day])
    steps, oos = [], []
    for test, (_, last_train) in STEPS:
        nm = pick(T[yr <= last_train], names)
        X = T[(yr == int(test)) & (T.version == nm)]
        steps.append(dict(test=test, picked=nm, label=describe(parse(nm)), n=int(len(X)), total=float(X.net.sum()),
                          win=float((X.net > 0).mean()) if len(X) else None,
                          avg=float(X.net.mean()) if len(X) else None))
        oos.append(X)
    O = pd.concat(oos).sort_values(["day", "und", "entry"]) if oos else T.iloc[:0]
    m = R.money(O) if len(O) else dict(n=0)
    m["years_positive"] = sum(1 for s in steps if s["total"] > 0)
    m["gates"] = dict(n=m.get("n", 0) >= 150, win=m.get("win", 0) >= 0.65,
                      net_pos=m.get("avg_net", -1) > 0 and m.get("avg_net_x2", -1) > 0, years=m["years_positive"] >= 3)
    m["passes"] = all(m["gates"].values())
    return dict(steps=steps, oos=m)


def run(part: str):
    from multiprocessing import Pool
    only = None
    names = [vname(v) for v in VERSIONS]
    if part == "seen":
        if (R.RESULTS / "seen_wr.json").exists():
            raise R.HoldoutError("refused: already checked (results/seen_wr.json)")
        picked = json.loads((R.RESULTS / "design_wr.json").read_text())["picked_all_design"]
        only = [parse(picked)]
        names = [picked]
        R.holdout_lock("start", suffix="_wr")
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part, only) for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    out = dict(part=part, versions={}, skips={u: x["skips"] for u, x in zip(K.UNDS, res)})
    for nm in names:
        s = E.summarize(T[T.version == nm] if len(T) else T, part == "design")
        s["label"] = describe(parse(nm))
        out["versions"][nm] = s
    if part == "design":
        prior = [json.loads((R.RESULTS / f).read_text()) for f in ("design.json", "design_wick.json", "design_enh.json")]
        prior_p = [v.get("p_twins", 1.0) for v in prior[0]["variants"].values()] + \
                  [v.get("p_twins", 1.0) for v in prior[1]["variants"].values()] + \
                  [v.get("p_normal", 1.0) for v in prior[2]["versions"].values()]
        ps = [out["versions"][nm].get("p_normal", 1.0) for nm in names]
        for nm, q in zip(names, R.R3.bh(ps + prior_p)[:len(ps)]):
            out["versions"][nm]["q"] = q
        out["walk_forward"] = walk_forward(T, names)
        out["picked_all_design"] = pick(T, names)
        out["bh_family"] = FAMILY
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    (R.RESULTS / ("design_wr.json" if part == "design" else "seen_wr.json")).write_text(
        json.dumps(out, indent=1, default=str))
    R.OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(R.OUT / f"trades_wr_{part}.parquet")
    if part == "seen":
        R.holdout_lock("done", suffix="_wr")
    if part == "design":
        print(json.dumps(out["walk_forward"], indent=1, default=str)[:3000])
        print("picked on all design:", out["picked_all_design"])
    for nm in names[:1] if part == "seen" else []:
        print(nm, {k: out["versions"][nm].get(k) for k in ("n", "win", "avg_net", "total")})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
