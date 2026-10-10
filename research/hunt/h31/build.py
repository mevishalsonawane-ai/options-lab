"""h31 option pass: for every (index, session d not an expiry day) x contract (M monthly / W weekly, expiry after the
next session) x side (CE / PE), buy 1-ITM at the 15:20 open and price the 8 pre-registered exits on the next session.
Writes <scratch>/hunt/h31/trades.parquet (spread NOT applied; test.py adds hs x (entry + exit) x qty).

    OBUY_CACHE=<scratch>/hunt/h31/cache flock <scratch>/obuy.lock python3 -I research/hunt/h31/build.py
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import _atm  # noqa: E402
from obuy.multiday import contract_expiry  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h31")
E1520 = 15 * 60 + 20 - C.OPEN_M
FILL, COST = Fills("app"), Costs("app")
PTS = (15, 20, 25, 30)


def first(a, lo, hi):
    seg = a[lo:hi]
    ok = np.nonzero(np.isfinite(seg))[0]
    return (lo + ok[0], seg[ok[0]]) if len(ok) else (None, np.nan)


def point_exit(o, h, l, ef, n):
    """o/h/l: next-session option bars; ef: entry fill. -> (raw price, fill price, why)."""
    tg, st = ef + n, ef - n
    opened = False
    for k in range(0, 60):
        if not np.isfinite(o[k]):
            continue
        if not opened:
            opened = True
            if o[k] <= st:
                return o[k], float(FILL.sell(o[k], stop=True)), "stop_open"
            if o[k] >= tg:
                return o[k], float(FILL.sell(o[k])), "tgt_open"
        if l[k] <= st:
            return st, float(FILL.sell(st, stop=True)), "stop"
        if h[k] >= tg:
            return tg, tg, "tgt"
    k, p = first(o, 60, 66)
    if k is None:
        return np.nan, np.nan, "nobar"
    return p, float(FILL.sell(p)), "time"


def main():
    t0 = time.time()
    feat = pd.read_parquet(os.path.join(OUT, "feat.parquet"))
    mk = market()
    rows = []
    lim = int(os.environ.get("H31_LIMIT", "0"))
    for und, F in feat.groupby("und", sort=False):
        if lim:
            F = F[F.day >= pd.Timestamp("2024-01-01").date()].head(lim)
        ix = mk.index(und)
        op = mk.options(und)
        step = C.STEP[und]
        bse = und in C.BSE
        for r in F.itertuples(index=False):
            if r.exp:
                continue
            for K, series in (("M", "month"), ("W", "week")):
                ex = contract_expiry(ix, r.day, series)
                if ex is None or ex <= r.nday:
                    continue
                ch = op.chain(r.day, series)
                if ch is None:
                    continue
                chn = op.chain(r.nday, series)
                if chn is None or chn.series != ch.series:
                    continue
                if K == "W" and ch.series != "WEEK":
                    continue
                for side in (1, -1):
                    strike = int(_atm(r.x, step) - side * step)
                    right = "C" if side > 0 else "P"
                    i, j = ch.kpos(strike), chn.kpos(strike)
                    if i < 0 or j < 0:
                        continue
                    ke, e = first(ch.o[right][i], E1520, E1520 + 4)
                    if ke is None or not e > 0:
                        continue
                    ef = float(FILL.buy(e))
                    o, h, l = (getattr(chn, f)[right][j][:66] for f in ("o", "h", "l"))
                    qty = int(r.lot)
                    base = dict(und=und, day=r.day, nday=r.nday, K=K, side=side, strike=strike, qty=qty, entry=e,
                                efill=ef, dte=(ex - r.nday).days, cbuy=float(COST.charge(True, ef, qty, bse=bse)),
                                last1529=first(ch.c[right][i][::-1], 0, 10)[1])
                    ex_ = {}
                    for nm, col in (("X0916", 1), ("X0930", 15), ("X1015", 60)):
                        k, p = first(o, col, col + 6)
                        ex_[nm] = (p, float(FILL.sell(p)) if np.isfinite(p) else np.nan, "time")
                    against = side * (r.n_c0915 - r.close) < 0
                    ex_["GX"] = ex_["X0916"] if against else ex_["X1015"]
                    for n in PTS:
                        ex_[f"P{n}"] = point_exit(o, h, l, ef, n)
                    for nm, (p, f, why) in ex_.items():
                        if not np.isfinite(p):
                            continue
                        d = dict(base, X=nm, exit=p, xfill=f, why=why,
                                 csell=float(COST.charge(False, f, qty, bse=bse)))
                        rows.append(d)
        mk.release(und)
        print(und, len(rows), f"{time.time() - t0:.0f}s", flush=True)
    tr = pd.DataFrame(rows)
    tr["gross"] = (tr.exit - tr.entry) * tr.qty
    tr["net_app"] = (tr.xfill - tr.efill) * tr.qty - tr.cbuy - tr.csell
    for c in ("entry", "efill", "exit", "xfill", "gross", "net_app", "cbuy", "csell", "last1529"):
        tr[c] = tr[c].astype(np.float32)
    tr.to_parquet(os.path.join(OUT, "trades_test.parquet" if lim else "trades.parquet"))
    print(tr.groupby(["und", "K", "X"]).size().unstack().to_string())
    print(tr.groupby(["und", "K"]).day.agg(["min", "max", "nunique"]).to_string())


if __name__ == "__main__":
    main()
