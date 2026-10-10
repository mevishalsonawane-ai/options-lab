"""h36 step 1: attach h26's BU15 OI-build-up flag to every Liquidity 15+5 signal (BANKNIFTY, MIDCPNIFTY).

    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h36/flags.py

Reuses h26 unchanged: liqfilter.load_trades() (h4's Liquidity trade list) and feats.opt() (cached panels). The flag
is h26's pre-registered filter "BU15:skip_opposed": skip when the 15-minute OI build-up, signed toward the trade, is
<= -1 (z). Read at the close of the signal minute. Output: scratchpad/hunt/h36/bu15_flags.parquet (small).
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h26"))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import liqfilter as LF  # noqa: E402
import feats as FT  # noqa: E402

OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h36"


def main():
    t = LF.load_trades()
    t = t[t.und.isin(["BANKNIFTY", "MIDCPNIFTY"])].reset_index(drop=True)
    vals = np.full(len(t), np.nan)
    for u in t.und.unique():
        m = (t.und == u).values
        days, D = FT.opt(u)
        A = D["BU15_z"]
        pos = {pd.Timestamp(d): i for i, d in enumerate(days)}
        r = t.loc[m, "day"].map(pos)
        ok = r.notna().values
        idx = np.flatnonzero(m)[ok]
        vals[idx] = A[r[ok].astype(int).values, t.col.values[idx]]
    t["BU15"] = vals * t.side.values
    t["skip_bu15"] = LF.rule_mask(t, "BU15", "opp")
    o = t[["und", "book", "day", "side", "sig_min", "entry_min", "BU15", "skip_bu15", "net"]].rename(columns={"net": "net_h26"})
    o.to_parquet(os.path.join(OUT, "bu15_flags.parquet"))
    for lo, hi, w in ((None, LF.HOLD, "pre"), (LF.HOLD, None, "hold")):
        x = o[(o.day >= lo) if lo is not None else (o.day < hi)]
        print(w, len(x), "has BU15", int(x.BU15.notna().sum()), "skip", int(x.skip_bu15.sum()),
              "skipped net (h26 pricing)", round(float(x.net_h26[x.skip_bu15].sum())), x.groupby("und").skip_bu15.sum().to_dict())


if __name__ == "__main__":
    main()
