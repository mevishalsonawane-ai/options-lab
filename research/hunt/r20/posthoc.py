"""R20 post hoc (NOT a test; written after the one holdout read, 13 Oct 2026): the plain ORB's net by the range's width,
and what each width rule changed on the days it touched. Numbers for the write-up's "what we learned" only.

    IRAALGO_CACHE=<cache> python3 research/hunt/r20/posthoc.py   # -> results/posthoc.json
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r20lib as L  # noqa: E402
import r20run as R  # noqa: E402


def w45(c) -> float:
    h, lo = L.opening_range(c.bh, c.bl, L.K_1000)
    return h - lo


def width_table(ctxs, res, idx):
    out = {}
    for i in idx:
        c = ctxs[i]
        h, lo = L.opening_range(c.bh, c.bl, L.K_1000)
        b = R.wbin((h - lo) / c.atr)
        t = out.setdefault(b, dict(days=0, trades=0, net=0.0))
        t["days"] += 1
        t["trades"] += len(res[i][0])
        t["net"] += sum(x["net"] for x in res[i][0])
    for t in out.values():
        t["per_day"] = t["net"] / t["days"]
    return dict(sorted(out.items()))


def main():
    out = {}
    for und in ("BANKNIFTY", "NIFTY"):
        ctxs = R.contexts(und)
        des, hol, _ = R.split(ctxs, und)
        res = {v.name: R.run_variant(v, ctxs) for v in L.VARIANTS}
        u = dict(atr_median_pts={p: float(np.median([ctxs[i].atr for i in ix])) for p, ix in (("design", des), ("holdout", hol))},
                 w45_ge_600={p: sum(1 for i in ix if w45(ctxs[i]) >= 600)
                             for p, ix in (("design", des), ("holdout", hol))},
                 v0_by_width={p: width_table(ctxs, res["V0"], ix) for p, ix in (("design", des), ("holdout", hol))},
                 changed_days={})
        for n in ("B0.5", "B0.75", "B1.0", "D"):
            for p, ix in (("design", des), ("holdout", hol)):
                wide = [i for i in ix if res[n][i][1] is not None and res[n][i][1].wide]
                v0 = sum(x["net"] for i in wide for x in res["V0"][i][0])
                vn = sum(x["net"] for i in wide for x in res[n][i][0])
                u["changed_days"].setdefault(n, {})[p] = dict(days=len(wide), v0_net=v0, variant_net=vn)
        out[und] = u
    with open(os.path.join(R.RESULTS, "posthoc.json"), "w") as f:
        json.dump(out, f, indent=1)
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
