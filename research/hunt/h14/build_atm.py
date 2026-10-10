"""h14 data pass: ATM-option minute paths (same expiry series 'near') for every h10 trade (real and random pool), for the
K1 strike split and the X2 delta estimate. Keyed by (und, day, right, ATM strike).

    OBUY_CACHE=<scratch>/hunt/h14/cache flock <scratch>/obuy.lock python3 -I research/hunt/h14/build_atm.py
"""
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h10"))
import cap  # noqa: E402
import numpy as np  # noqa: E402
from cap import sim  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402

OUT = os.path.join(C.CACHE, "h14")


def main():
    os.makedirs(OUT, exist_ok=True)
    P = sim.load()
    tr = sim.base_trades(P["real"][0], cap.EXE)
    pool = sim.base_trades(P["real"][1], cap.EXE, pos=False)
    keys = set()
    for t in (tr, pool):
        for r in t[["und", "day", "side", "strike"]].itertuples(index=False):
            right = "C" if r.side > 0 else "P"
            keys.add((r.und, r.day.date(), right, int(r.strike) + int(r.side) * C.STEP[r.und]))
    print("ATM contracts needed", len(keys), flush=True)
    mk = market()
    res = {}
    for und in sorted({k[0] for k in keys}):
        opts = mk.options(und)
        for k in sorted(x for x in keys if x[0] == und):
            ch = opts.chain(k[1], "near")
            if ch is None:
                continue
            i = ch.kpos(k[3])
            if i < 0:
                continue
            res[k] = np.stack([getattr(ch, f)[k[2]][i] for f in ("o", "h", "l", "c", "v")]).astype(np.float32)
        mk.release(und)
    print("found", len(res), "of", len(keys), flush=True)
    with open(os.path.join(OUT, "atm.pkl"), "wb") as f:
        pickle.dump(res, f, protocol=4)


if __name__ == "__main__":
    main()
