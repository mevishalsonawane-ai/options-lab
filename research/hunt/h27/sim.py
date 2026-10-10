"""h27 Part B: option paths for EVERY day, BOTH sides, entries at 09:21 and 09:31 (1-ITM, nearest expiry), run through
the six pre-registered exits with raw prices (gross) and dated app charges. Rules and random-side baselines are then
pure selections from this table (analyse.py), so the random baseline uses exactly the same days, minutes and exits.

    OBUY_CACHE=<scratch>/hunt/h27/cache flock <scratch>/obuy.lock python3 -I research/hunt/h27/sim.py pre|hold
"""
from __future__ import annotations

import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy.costs import Costs, Fills  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, prepare_many  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h27"
HOLD = date(2025, 10, 1)
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
EXITS = {
    "E1_LIQ": Exits(stop_pct=0.15, time_stop=20, time_gain=0.05),
    "E2_15_30": Exits(stop_pct=0.15, tgt_pct=0.30),
    "E3_LADDER": Exits(stop_pct=0.15, tgt_pct=0.40, ladder=LADDER, ladder_ref_pct=0.40),
    "E4_T1015": Exits(stop_pct=0.30, sq_off=10 * 60 + 15),
    "E5_T1115": Exits(stop_pct=0.30, sq_off=11 * 60 + 15),
    "E6_T1510": Exits(stop_pct=0.30),
}
EXE = Execution(fills=Fills(mode="flat", pts=0.0), costs=Costs(mode="dated"), expiry="allow", min_premium=5.0)


def main(mode):
    from obuy.data import market
    mk = market()
    rows = []
    for u in UNDS:
        ix = mk.index(u)
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            if (mode == "pre") != (d < HOLD):
                continue
            for sm in (9 * 60 + 20, 9 * 60 + 30):
                for s in (1, -1):
                    rows.append(dict(und=u, day=d, sig_min=sm, side=s, book=f"{u}_{sm}_{s}"))
    sig = pd.DataFrame(rows)
    print("candidates", len(sig), sig.groupby("und").size().to_dict(), flush=True)
    out = []
    for u in UNDS:                      # one underlying at a time keeps memory low
        s = sig[sig.und == u]
        (pk, _), = prepare_many([(s, StrikeRule(money=1, series="near"), EXE, 0, None, False)])
        mk.release()
        print(u, "pack", len(pk), flush=True)
        for en, ex in EXITS.items():
            t = pk.run(ex, EXE)
            t["exit_id"] = en
            out.append(t[["und", "day", "sig_min", "side", "strike", "lot", "qty", "entry", "exit", "why", "gross",
                          "charges", "entry_min", "exit_min", "exit_id"]])
        del pk
    T = pd.concat(out, ignore_index=True)
    T.to_parquet(os.path.join(SCR, f"trades_{mode}.parquet"))
    print("saved", len(T), T.groupby(["und", "exit_id"]).size().unstack().to_string())


if __name__ == "__main__":
    main(sys.argv[1])
