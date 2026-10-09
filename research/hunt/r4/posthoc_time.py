"""R4 POST-HOC (labelled so in the report): the pre-registered time placebo can shift a Fibonacci-time-zone mark back
onto its own anchor pivot, which inflates the placebo hit rate. Here the anchor pivot is EXCLUDED from the pivots a
mark (claimed or placebo) may hit. python3 -I posthoc_time.py -> scratchpad/hunt/r4/posthoc_time.csv
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib4 as L4  # noqa: E402
import signals4 as S4  # noqa: E402
import placebo as PL  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

L = L4.L


def main():
    res = []
    for period in ("design", "holdout"):
        for u in L4.UNDS:
            P = L.load_index(u)
            mask = (P["ok"] & ~P["hold"]) if period == "design" else (P["ok"] & P["hold"] & P["inrange"])
            okd = mask
            rng = np.random.default_rng(17)
            B = S4.mk_bars(P, 5)
            pb, pp, pt, pcf = S4.piv(B, 2)
            pv = {}
            for i, t in enumerate(pb):
                pv.setdefault(int(B["dpos"][t]), []).append((i, int(B["col_end"][t]) - 4, int(B["col_end"][t])))
            rows = []
            for jx in range(len(pb)):
                t0 = pb[jx]
                di = int(B["dpos"][t0])
                if not okd[di]:
                    continue
                for k in (5, 8, 13, 21, 34, 55):
                    t = t0 + k
                    if t < len(B["c"]) and B["dpos"][t] == di and t > pcf[jx]:
                        m = int(B["col_end"][t])
                        others = [(a, b) for i, a, b in pv.get(di, []) if i != jx]
                        hit = lambda mm: int(any(a - 5 <= mm <= b + 5 for a, b in others))  # noqa: E731
                        rows.append((di, -1, hit(m)))
                        for r in range(PL.ND):
                            mm = m + rng.choice([-1, 1]) * rng.integers(10, 31)
                            if 5 <= mm <= 370:
                                rows.append((di, r, hit(mm)))
            g = pd.DataFrame(rows, columns=["di", "draw", "hit"])
            g["one"] = 1
            rc, rp, n, df, p, ci = PL.boot_diff(g, "hit", "one")
            res.append(dict(und=u, period=period, fam="FIB_TZ (anchor excluded)", claimed=rc, placebo=rp, n=n, diff=df,
                            p=p))
            L.D.market().release()
    out = pd.DataFrame(res)
    out.to_csv(os.path.join(L4.OUT, "posthoc_time.csv"), index=False)
    print(out.round(4).to_string())


if __name__ == "__main__":
    main()
