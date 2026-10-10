"""h27 Part A: index-level screen of the pre-09:15 cues (PREREG.md). Pre-holdout days only (< 2025-10-01).
    python3 -I research/hunt/h27/part_a.py   -> scratchpad/hunt/h27/part_a.csv and a printed summary."""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")

from datetime import date  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats  # noqa: E402

from obuy.overfit import bh  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h27"
HOLD = date(2025, 10, 1)
CUES = ["SPX", "NDX", "SPXOC", "ADR", "INDARES", "ASIA", "CRUDE", "USDINR", "DXY", "US10Y", "GOLD", "USVIX", "INVIX",
        "FII", "COMP"]
HZ = ["1015", "1115", "1510"]


def residuals(df):
    """gap - beta x z_COMP with beta from an anchored walk-forward OLS on prior days (>= 120), scaled by the trailing
    250-day std of the residual."""
    df = df.sort_values("day").reset_index(drop=True)
    x, y = df.z_COMP.values, df.gap.values
    res = np.full(len(df), np.nan)
    for i in range(len(df)):
        ok = np.isfinite(x[:i]) & np.isfinite(y[:i])
        if ok.sum() < 120 or not np.isfinite(x[i]):
            continue
        xx, yy = x[:i][ok], y[:i][ok]
        b = (xx * yy).sum() / (xx * xx).sum()
        res[i] = y[i] - b * x[i]
    df["resid"] = res
    df["resid_sd"] = pd.Series(res).rolling(250, min_periods=60).std().shift(1).values
    df["resid_z"] = df.resid / df.resid_sd
    return df


def main(holdout=False):
    F = pd.read_parquet(os.path.join(SCR, "feat.parquet"))
    oc = pd.read_parquet(os.path.join(SCR, "outcomes.parquet"))
    df = oc.merge(F.reset_index(), on="day")
    df = pd.concat([residuals(g) for _, g in df.groupby("und")], ignore_index=True)
    df.to_parquet(os.path.join(SCR, "panel.parquet"))
    pre = df[(df.day >= HOLD) if holdout else (df.day < HOLD)]
    rows = []
    for u, g in pre.groupby("und"):
        for c in CUES:
            z = g["z_" + c]
            ok0 = z.notna() & g.gap.notna()
            align = np.corrcoef(z[ok0], g.gap[ok0])[0, 1] if ok0.sum() > 30 else np.nan
            for h in HZ:
                ok = ok0 & g["r" + h].notna()
                if ok.sum() < 60:
                    continue
                r, p = stats.pearsonr(z[ok], g["r" + h][ok])
                hit = float((np.sign(z[ok]) == np.sign(g["r" + h][ok])).mean())
                rows.append(dict(test="cue", und=u, cue=c, hz=h, n=int(ok.sum()), corr=r, p=p, hit=hit, corr_gap=align))
        for h in HZ:
            ok = g.resid_z.notna() & g["r" + h].notna()
            r, p = stats.pearsonr(g.resid_z[ok], g["r" + h][ok])
            hit = float((np.sign(-g.resid_z[ok]) == np.sign(g["r" + h][ok])).mean())
            rows.append(dict(test="resid", und=u, cue="RESID(gap-beta*COMP)", hz=h, n=int(ok.sum()), corr=r, p=p,
                             hit=hit, corr_gap=np.nan))
    R = pd.DataFrame(rows)
    R["q"] = bh(R.p.values)
    tag = "hold" if holdout else "pre"
    R.to_csv(os.path.join(SCR, f"part_a_{tag}.csv"), index=False)
    pd.set_option("display.width", 200)
    print("tests", len(R), "BH q<0.05:", int((R.q < 0.05).sum()), " raw p<0.05:", int((R.p < 0.05).sum()))
    print(R.sort_values("p").head(20).round(4).to_string(index=False))
    print("\nalignment corr(cue z, gap) by und (NIFTY rows):")
    print(R[(R.test == "cue") & (R.hz == "1015")].pivot(index="cue", columns="und", values="corr_gap").round(2))
    print("\ncorr(cue, r1510):")
    print(R[(R.hz == "1510")].pivot(index="cue", columns="und", values="corr").round(3))
    # the gap itself (known at 09:15): does the opening gap continue or fill?
    print("\ngap -> later move (corr):")
    for u, g in pre.groupby("und"):
        print(u, [round(float(g[["gap", "r" + h]].dropna().corr().iloc[0, 1]), 3) for h in HZ],
              "r930:", round(float(g[["gap", "r930"]].dropna().corr().iloc[0, 1]), 3))


if __name__ == "__main__":
    main(holdout=len(sys.argv) > 1 and sys.argv[1] == "holdout")
