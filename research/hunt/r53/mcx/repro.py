"""Reproduction checks of the MCX rules on the old studies' own windows (diagnostic only, never a decision input).

These windows overlap R53's final test, so this refuses to run before the one final read is recorded
(results/final_read_mcx.json).   python -m research.hunt.r53.mcx.repro  -> results/mcx_repro.json
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C
from research.hunt.r53.mcx import rules as R

# (label, rule class, variant, params override, window start, window end, old study's number)
CHECKS = [
    ("M3 EVE NG option design", R.MA1, "orig", {}, "2025-08-05", "2026-07-07", "197 tr, +Rs 47/tr"),
    ("M3 EVE NG option holdout", R.MA1, "orig", {}, "2026-07-08", "2026-10-08", "55 tr, +Rs 93/tr"),
    ("M3 EVE GOLDM future design", R.MA1b, "orig", {}, "2025-08-05", "2026-07-07", "192 tr, +Rs 313/tr"),
    ("M3 EVE GOLDM future holdout", R.MA1b, "orig", {}, "2026-07-08", "2026-10-08", "53 tr, +Rs 143/tr"),
    ("M3 EVE CRUDEOIL option design", R.MA1c, "orig", {}, "2025-08-05", "2026-07-07", "203 tr, -Rs 633/tr"),
    ("M3 EVE CRUDEOIL option holdout", R.MA1c, "orig", {}, "2026-07-08", "2026-10-08", "60 tr, +Rs 303/tr"),
    ("M3 INORB GOLDM option design", R.MA8, "orig", {}, "2025-08-05", "2026-07-07", "68 tr, +Rs 110/tr"),
    ("M3 INORB GOLDM option holdout", R.MA8, "orig", {}, "2026-07-08", "2026-10-08", "58 tr, -Rs 67/tr"),
    ("M3 EVT EIA crude design", R.MA9, "orig", {}, "2025-08-05", "2026-07-07", "41 tr, -Rs 361/tr"),
    ("M3 EVT EIA crude holdout", R.MA9, "orig", {}, "2026-07-08", "2026-10-08", "14 tr, +Rs 645/tr"),
    ("R2 C2 silver holdout (app rule)", R.MA2, "orig", {}, "2025-10-01", "2026-10-06", "166 tr, +Rs 1,189/tr"),
    ("R2 C1 NG future holdout", R.MA3Fut, "orig", {}, "2025-10-01", "2026-10-06", "233 tr, +Rs 346/tr"),
    ("R2 C1 NG option holdout", R.MA3Opt, "orig", {}, "2025-10-01", "2026-10-06", "208 tr, +Rs 181/tr"),
    ("R2 C3 GOLDTEN holdout", R.MA4Gold, "orig", {}, "2025-10-01", "2026-10-06", "123 tr, +Rs 126/tr"),
    ("R2 C3 NG option holdout", R.MA4NG, "orig", {}, "2025-10-01", "2026-10-06", "116 tr, +Rs 262/tr"),
    ("R2 C3 CRUDEOILM holdout (R2 exit: close)", R.MA4Crude, "orig", {"exit": "xeod"}, "2025-10-01", "2026-10-06",
     "141 tr, +Rs 274/tr"),
    ("R2 C4 XAU lead holdout", R.MA5, "orig", {}, "2025-10-01", "2026-10-06", "128 tr, +Rs 228/tr"),
    ("R3 GOLDM 5m ATM AM, Oct 2025 - 8 Oct 2026", R.MA6, "orig", {}, "2025-10-01", "2026-10-08", "222 tr, -Rs 274/tr"),
    ("R6 SILVERMIC swing (both sides) holdout", R.MA7, "both", {}, "2025-10-01", "2026-10-08", "+Rs 439/day; longs +Rs 833/tr"),
]


def main():
    if not C.final_lock_path("mcx").exists():
        raise SystemExit("refused: the MCX final read has not been recorded yet")
    ctx = R.Ctx()
    out = []
    for label, cls, v, over, a, b, old in CHECKS:
        r = cls()
        P = dict(r.variants[v], **over)
        m = ctx.m(r.sym)
        days = [d for d in m.days if pd.Timestamp(a) <= pd.Timestamp(d) <= pd.Timestamp(b)]
        tr = r.run(ctx, P, days)
        net = np.array([t["net"] for t in tr])
        longs = [t for t in tr if t["side"] > 0]
        row = dict(check=label, window=f"{a} - {b}", old=old, trades=len(tr),
                   net_per_trade=float(net.mean()) if len(net) else None, net=float(net.sum()),
                   net_per_day=float(net.sum() / len(days)) if days else None,
                   gross_per_trade=float(np.mean([t["gross"] for t in tr])) if tr else None,
                   longs_net_per_trade=float(np.mean([t["net"] for t in longs])) if longs else None)
        out.append(row)
        print(row, flush=True)
    C.save("mcx_repro.json", out)


if __name__ == "__main__":
    main()
