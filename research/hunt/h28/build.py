"""h28 Part B data pass (heavy): the option-buying trade table. Every option-era session x index x entry time
(09:20, 10:15, 12:00, 14:00) x side (CE, PE), 1-ITM nearest expiry, expiry days allowed, app fills + app charges,
1 lot (lot as of the date), under each of the 6 pre-registered exit sets. See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h28/cache flock <scratch>/obuy.lock python3 -I research/hunt/h28/build.py

Writes <scratch>/hunt/h28/opt_trades.parquet (one row per und/day/T/side/exit; spread NOT yet applied: the analysis adds
hs x (entry + exit) x qty) and day_mom.parquet (the 'mom' side per und/day/T). The holdout rows are written too, but
nothing reads them before the final, once-only holdout step (test.py --holdout).
"""
from __future__ import annotations

import os
import sys
import time

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, prepare_many  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h28")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
TIMES = {"0920": 9 * 60 + 20, "1015": 10 * 60 + 15, "1200": 12 * 60, "1400": 14 * 60}
SQ = 15 * 60 + 10
EXITS = {
    "E1_liq": Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ),
    "E2_s15t30": Exits(stop_pct=0.15, tgt_pct=0.30, sq_off=SQ),
    "E3_s30t60": Exits(stop_pct=0.30, tgt_pct=0.60, sq_off=SQ),
    "E4_lock": Exits(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.15, sq_off=SQ),
    "E5_t60": Exits(time_stop=60, time_gain=None, sq_off=SQ),
    "E6_close": Exits(sq_off=SQ),
}
RULE = StrikeRule(1, series="near")
EXE = Execution(expiry="allow")


def main():
    t0 = time.time()
    mk = market()
    allt, moms = [], []
    for und in UNDS:
        ix = mk.index(und)
        M = ix.mat()
        sig, mom = [], []
        for p, d in enumerate(ix.days):
            o0 = M["o"][p, 0] if np.isfinite(M["o"][p, 0]) else np.nan
            if not np.isfinite(o0):
                ok = np.nonzero(np.isfinite(M["o"][p]))[0]
                if not len(ok):
                    continue
                o0 = M["o"][p, ok[0]]
            for tn, tm in TIMES.items():
                sm = tm - 1
                col = sm - C.OPEN_M
                c = M["c"][p, :col + 1]
                c = c[np.isfinite(c)]
                if not len(c):
                    continue
                mom.append(dict(und=und, day=d, T=tn, mom=int(np.sign(c[-1] - o0)), ret_to_T=float(c[-1] / o0 - 1)))
                for side in (1, -1):
                    sig.append(dict(und=und, day=d, sig_min=sm, side=side, book=f"{und}_{d}_{tn}_{side}", tag=tn))
        sig = pd.DataFrame(sig)
        print(und, "signals", len(sig), f"{time.time() - t0:.0f}s", flush=True)
        (pk, _), = prepare_many([(sig, RULE, EXE, 0, None, False)])
        print(und, "packed", len(pk), f"store {pk.store.nbytes() / 1e6:.0f} MB", f"{time.time() - t0:.0f}s", flush=True)
        for xn, ex in EXITS.items():
            tr = pk.run(ex, EXE)
            tr = tr[["und", "day", "sig_min", "side", "strike", "lot", "qty", "tag", "entry_min", "exit_min", "entry",
                     "exit", "why", "gross", "charges", "net"]].copy()
            tr["exit_set"] = xn
            tr = tr.rename(columns={"tag": "T"})
            allt.append(tr)
        moms.append(pd.DataFrame(mom))
        mk.release(und)
        del pk
    tr = pd.concat(allt, ignore_index=True)
    for c in ("entry", "exit", "gross", "charges", "net"):
        tr[c] = tr[c].astype(np.float32)
    tr.to_parquet(os.path.join(OUT, "opt_trades.parquet"))
    pd.concat(moms, ignore_index=True).to_parquet(os.path.join(OUT, "day_mom.parquet"))
    print("rows", len(tr), f"{time.time() - t0:.0f}s")
    print(tr.groupby(["und", "exit_set"]).size().unstack().to_string())


if __name__ == "__main__":
    main()
