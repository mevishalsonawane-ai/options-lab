"""R14 step 3 (information only, after the holdout read; nothing is chosen from it):
  - the small profits Boss sees: R0's exits by kind (lock rungs, target, stop) - gross, charges, net per trade;
  - random twins: is "cap 1 a side" better than dropping the same number of R0 trades at random? (R12's honesty check);
  - the cap's price: profit the cap gives up on days a later same-side trade would have won.

    python3 -I research/hunt/r14/posthoc.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import data as D  # noqa: E402


def main():
    for part in ("design", "holdout"):
        t = pd.read_csv(os.path.join(D.OUT, f"trades_{part}.csv.gz"))
        for arm in D.ARMS:
            r0 = t[(t.arm == arm) & (t.rule == "R0")].copy()
            r1 = t[(t.arm == arm) & (t.rule == "R1")]
            r0["kind"] = np.where(r0.why == "lock", np.where(r0.lock_at - r0.entry < 5, "lock_breakeven",
                                  np.where(r0.lock_at - r0.entry < 15, "lock_+10", "lock_+20/30")), r0.why)
            g = r0.groupby("kind").agg(n=("net", "size"), gross=("gross", "mean"), charges=("charges", "mean"), net=("net", "mean"),
                                       net_sum=("net", "sum")).round(1)
            print(f"\n== {part} {arm}: R0 exits by kind (per trade, Rs)\n{g.to_string()}")
            nets = r0.net.values
            rng = np.random.default_rng(14)
            n1 = len(r1)
            draws = np.array([nets[rng.choice(len(nets), n1, replace=False)].sum() for _ in range(5000)])
            p = float((draws >= r1.net.sum()).mean())
            print(f"{part} {arm}: cap-1 net {r1.net.sum():,.0f} over {n1} trades; same count of RANDOM R0 trades: mean "
                  f"{draws.mean():,.0f} (5-95%: {np.percentile(draws, 5):,.0f} .. {np.percentile(draws, 95):,.0f}); "
                  f"p(random >= cap) = {p:.3f}")
            later = r0[r0.nth_side > 1]
            print(f"{part} {arm}: trades the cap drops: {len(later)}, gross {later.gross.sum():,.0f}, charges "
                  f"{later.charges.sum():,.0f}, net {later.net.sum():,.0f}; their winners' net {later.net[later.net > 0].sum():,.0f}")


if __name__ == "__main__":
    main()
