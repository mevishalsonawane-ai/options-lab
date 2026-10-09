"""R4 optional (information only): placebo-level test for the top level ideas (round numbers, square-of-9 0.125 grid,
previous-day-range Fibonacci) on XAUUSD minutes 2015-2023 (the gold proxy available locally; MCX gold tracks it).
python3 -I xau.py -> scratchpad/hunt/r4/placebo_xau.csv
"""
from __future__ import annotations

import glob
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib4 as L4  # noqa: E402
import placebo as PL  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

RAW = os.path.join(L4.L.C.SCRATCH, "hunt", "r3", "raw")


def main():
    df = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(os.path.join(RAW, "xauH_20*.parquet")))])
    df["day"] = df.ts.dt.date
    days = sorted(df.day.unique())
    g = {d: x for d, x in df.groupby("day")}
    rng = np.random.default_rng(42)
    rows = []
    prev = None
    for d in days:
        x = g[d]
        if len(x) < 600:
            prev = None
            continue
        h, l, c = x.high.values.astype(float), x.low.values.astype(float), x.close.values.astype(float)
        if prev is not None:
            ph, pl, pc = prev
            R = ph - pl
            fams = {}
            for nm, G in (("RND10", 10.0), ("RND50", 50.0), ("RND100", 100.0)):
                gg = np.arange(math.floor(pc * 0.95 / G) * G, pc * 1.05 + G, G)
                fams[nm] = (gg, [gg + rng.uniform(0, G) for _ in range(PL.ND)])
            base = math.floor(math.sqrt(pc)) - 3
            i = np.arange(49)
            fams["SQ9_125"] = ((base + 0.125 * i) ** 2, [(base + 0.125 * i + rng.uniform(0, .125)) ** 2 for _ in range(PL.ND)])
            rr = np.array([0.236, 0.382, 0.5, 0.618, 0.786])
            fams["PDRFIB"] = (pl + rr * R, [pl + (rr + rng.choice([-1, 1]) * rng.uniform(.015, .06)) * R for _ in range(PL.ND)])
            a, b = 30, len(c) - 61
            for fam, (cl, pls) in fams.items():
                for k, lev in enumerate([cl] + pls):
                    lv, j, sg = PL.events(h, l, c, lev, a, b)
                    for dd in (0.001, 0.0025):
                        r = PL.outcome(h, l, lv, j, sg, dd)
                        ok = np.isfinite(r)
                        rows.append((fam, dd, d, k - 1, int(ok.sum()), float(np.nansum(r)), d.year >= 2023))
        prev = (h.max(), l.min(), c[-1])
    ev = pd.DataFrame(rows, columns=["fam", "d", "di", "draw", "n", "rev", "late"])
    res = []
    for (late, fam, dd), gq in ev.groupby(["late", "fam", "d"]):
        rc, rp, n, df_, p, ci = PL.boot_diff(gq, "rev", "n")
        res.append(dict(period="2023" if late else "2015-2022", fam=fam, d=dd, claimed=rc, placebo=rp, n_claimed=n,
                        diff=df_, ci_lo=ci[0], ci_hi=ci[1], p=p))
    out = pd.DataFrame(res)
    out.to_csv(os.path.join(L4.OUT, "placebo_xau.csv"), index=False)
    pd.set_option("display.width", 200)
    print(out.round(4).to_string())


if __name__ == "__main__":
    main()
