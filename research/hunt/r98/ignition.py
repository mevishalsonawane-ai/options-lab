"""R98g (PREREG_G.md): order-flow ignition entries (volume burst + lift-off + up-ticks), every expiry 2021-26.

    python3 -I research/hunt/r98/ignition.py     # -> results/ignition.md, results/ignition.json
"""
from __future__ import annotations

import json
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1]))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
from hero2 import md_table, pct, rs, ticket  # noqa: E402
from obuy.overfit import spa  # noqa: E402

RES = HERE / "results"
T11, TLAST = R.minute(11, 0), R.minute(14, 55)
BS, RS_ = (3.0, 5.0), (1.3, 1.5)
EXITS = (("hold", "B_hold"), ("-20% stop", "B_stop20"), ("-50% stop", "B_stop50"), ("trail 50% from 2x", "E2_arm2x_g0.5_s0.5"))


def triggers(day):
    """{(B, R): (t, strike, side, burst)} first ignition of the day for each (B, R)."""
    sp = L.spot(day)
    best = {}
    ts = np.arange(T11, TLAST + 1)
    for (k, s), a in day.opts.items():
        c = a[:, R.C]
        cf = L.ffill(c, 3)
        v = np.nan_to_num(a[:, R.V])
        cv = np.cumsum(v)
        cmin = np.fmin.accumulate(np.where(np.isnan(c[T11:]), np.inf, c[T11:]))     # lowest close since 11:00
        for t in ts:
            lo = cmin[t - T11]
            p = c[t]
            if not (1.0 <= lo <= 10.0) or np.isnan(p) or p > 15.0 or np.isnan(sp[t]) or not L.otm(k, s, sp[t]):
                continue
            if v[t - 2:t + 1].sum() <= 0:
                continue
            v5 = cv[t] - cv[t - 5]
            n = t - T11 + 1
            avg5 = (cv[t] - cv[T11 - 1]) / (n / 5.0)
            if avg5 <= 0:
                continue
            burst = v5 / avg5
            dc = np.diff(cf[t - 5:t + 1])
            vv = v[t - 4:t + 1]
            ok = ~np.isnan(dc)
            up = vv[ok][dc[ok] > 0].sum() / v5 if v5 > 0 else 0.0
            if up < 0.6:
                continue
            for B in BS:
                for Rr in RS_:
                    if burst >= B and p >= Rr * lo:
                        cur = best.get((B, Rr))
                        if cur is None or t < cur[0] or (t == cur[0] and burst > cur[3]):
                            best[(B, Rr)] = (int(t), float(k), s, float(burst))
    return best


def run_und(und):
    out = []
    for day in R.load(und):
        trg = triggers(day)
        if not trg:
            continue
        em, _ = L.straddle(day)
        for (B, Rr), (t, k, s, burst) in trg.items():
            x = ticket(day, k, s, t, em[t] if not np.isnan(em[t]) else np.nan, "IGN", dict(B=B, R=Rr, burst=burst))
            if x:
                out.append(x)
    print(und, len(out), flush=True)
    return out


def main():
    with Pool(4) as p:
        parts = p.map(run_und, R.UNDS)
    df = pd.DataFrame([r for part in parts for r in part])
    df["yr"] = pd.to_datetime(df.day).dt.year
    df.to_parquet(R.OUT / "ignition.parquet")
    dates = np.array(sorted(pd.read_parquet(R.OUT / "days.parquet").day.unique()))
    dpos = {d: i for i, d in enumerate(dates)}
    rows, cols, names = [], [], []
    for B in BS:
        for Rr in RS_:
            g = df[(df.B == B) & (df.R == Rr)]
            for en, c in EXITS:
                v = g[c].values
                by = g.groupby(["und", "day"])[c].sum().sort_values(ascending=False)
                yrs = g.groupby("yr")[c].sum()
                h1, h2 = g[g.yr <= 2023][c], g[g.yr >= 2024][c]
                rows.append({"burst": f"≥ {B:g}×", "lift-off": f"≥ {Rr:g}× low", "exit": en, "tickets": len(v),
                             "win": pct((v > 0).mean(), 1), "mean Rs": rs(v.mean()),
                             "w/o 5 best days": rs((v.sum() - by.head(5).sum()) / len(v)),
                             "years > 0": f"{int((yrs > 0).sum())}/{len(yrs)}", "2021-23 mean": rs(h1.mean()),
                             "2024-26 mean": rs(h2.mean()), "hit 10x": pct((g.best_mult >= 10).mean(), 1),
                             "median entry Rs": f"{g.entry.median():.2f}",
                             "_mean": v.mean(), "_wo5": (v.sum() - by.head(5).sum()) / len(v), "_h1": h1.mean(), "_h2": h2.mean()})
                cols.append(np.bincount(g.day.map(dpos).values, weights=v, minlength=len(dates)))
                names.append((B, Rr, en))
    X = np.stack(cols, axis=1)
    sp = spa(X, B=2000, mean_block=5.0, seed=98)
    k = sp["best"]
    best = rows[k]
    passed = sp["spa_p"] < 0.05 and best["_wo5"] > 0 and best["_h1"] > 0 and best["_h2"] > 0
    t = pd.DataFrame(rows).drop(columns=["_mean", "_wo5", "_h1", "_h2"])
    x = df[(df.und == "NIFTY") & (df.day == pd.Timestamp("2026-10-13").date())]
    oct13 = [{"burst / lift": f"{r.B:g}× / {r.R:g}×", "time": f"{(555 + int(r.t)) // 60:02d}:{(555 + int(r.t)) % 60:02d}",
              "option": f"{int(r.strike)} {'CE' if r.side == 'C' else 'PE'}", "entry": f"{r.entry:.2f}",
              **{en: rs(r[c]) for en, c in EXITS}} for _, r in x.iterrows()]
    S = dict(spa=sp, best=names[k], passed=bool(passed), table=t.to_dict(orient="records"), oct13=oct13)
    md = ["# R98g: order-flow ignition entries (generated by ignition.py)\n",
          f"- Hansen SPA over the 16 variants: **p = {sp['spa_p']:.3f}** (White RC p = {sp['rc_p']:.3f}); best: "
          f"burst ≥ {names[k][0]:g}×, lift-off ≥ {names[k][1]:g}×, {names[k][2]}.",
          f"- **Passes: {'YES' if passed else 'no'}.**", "", md_table(t), "",
          "## 13 Oct 2026, NIFTY\n", md_table(pd.DataFrame(oct13)) if oct13 else "No ignition on 13 Oct.", ""]
    RES.mkdir(exist_ok=True)
    (RES / "ignition.md").write_text("\n".join(md) + "\n")
    (RES / "ignition.json").write_text(json.dumps(S, indent=1, default=str))
    print("\n".join(md))


if __name__ == "__main__":
    main()
