"""h30 step 1: cut the premium charts. For each underlying-day: ATM / 1-ITM / 1-OTM CE and PE of the nearest expiry,
strike fixed from the index 09:15 open. Saves dense (N, 375) float32 o/h/l/c/v + meta + prior-day high.

python3 -I research/hunt/h30/build.py NIFTY BANKNIFTY ...
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h30")


def build(und):
    mk = market()
    ix = mk.index(und)
    opts = mk.options(und)
    M = ix.mat()
    step = C.STEP[und]
    rows, meta = [], []
    prev = None   # (day, series, exp, {(right,strike): high})
    for p, d in enumerate(ix.days):
        xd = ix.d[d]
        ch = opts.chain(d, "near")
        if ch is None:
            prev = None
            continue
        # day highs of every contract today (for tomorrow's PDH)
        highs = {}
        for r in ("C", "P"):
            hh = np.nanmax(np.where(np.isnan(ch.h[r]), -np.inf, ch.h[r]), axis=1)
            for k, v in zip(ch.K, hh):
                if np.isfinite(v):
                    highs[(r, int(k))] = float(v)
        o0 = M["o"][p][0]
        if not np.isfinite(o0):
            ok = np.nonzero(~np.isnan(M["o"][p]))[0]
            o0 = M["o"][p][ok[0]] if len(ok) else np.nan
        same = prev is not None and prev[1] == ch.series and not prev[2]
        if np.isfinite(o0) and not xd["exp"]:
            atm = int(np.floor(o0 / step + 0.5) * step)
            lot = ix.lot(d, "data")
            for money in (-1, 0, 1):
                for right, sgn in (("C", 1), ("P", -1)):
                    k = atm - sgn * money * step
                    i = ch.kpos(k)
                    if i < 0:
                        continue
                    o = ch.o[right][i]
                    if np.sum(~np.isnan(o)) < 200:
                        continue
                    pdh = prev[3].get((right, k), np.nan) if same else np.nan
                    rows.append(np.stack([ch.o[right][i], ch.h[right][i], ch.l[right][i], ch.c[right][i],
                                          ch.v[right][i]]).astype(np.float32))
                    meta.append(dict(und=und, day=d, money=money, right=sgn, strike=k, lot=lot, exp=bool(xd["exp"]),
                                     series=ch.series, pdh=pdh, spot0=float(o0)))
        prev = (d, ch.series, bool(xd["exp"]), highs)
    mk.release(und)
    A = np.stack(rows)
    meta = pd.DataFrame(meta)
    # opposite-right partner with the same moneyness label, same day
    key = {(r.day, r.money, r.right): i for i, r in enumerate(meta.itertuples())}
    meta["pair"] = [key.get((r.day, r.money, -r.right), -1) for r in meta.itertuples()]
    os.makedirs(OUT, exist_ok=True)
    np.save(os.path.join(OUT, f"paths_{und}.npy"), A)
    meta.to_pickle(os.path.join(OUT, f"meta_{und}.pkl"))
    print(und, A.shape, meta.day.min(), meta.day.max(), "exp", meta.exp.sum(), "pdh ok", meta.pdh.notna().mean().round(3),
          flush=True)


if __name__ == "__main__":
    for u in sys.argv[1:]:
        build(u)
