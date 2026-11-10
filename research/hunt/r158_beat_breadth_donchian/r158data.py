"""R158 labels (PREREG section 1): for every R156 example (moment x side), the net result and exit row of buying the 1-ITM
option at the next minute with Breadth Donchian's exits XL, priced by R106's engine as the arm's own trades.

    python research/hunt/r158_beat_breadth_donchian/r158data.py build [UND ...]     # -> $R158_OUT/xl_<UND>.npz
    R158_POST=1 python research/hunt/r158_beat_breadth_donchian/r158data.py build   # -> xl_<UND>_post.npz
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np

import r158cfg as K
import r156data as DT6

K4 = K.K4


def day_labels(und: str, D, rows_s, rows_side, M: dict) -> tuple:
    """(net, exit row) for one session's examples (decision rows s, sides 1 call / 0 put): NaN / -1 on an expiry day,
    with no contract or no fill."""
    n = len(rows_s)
    net, xr = np.full(n, np.nan, np.float32), np.full(n, -1, np.int16)
    if D.expiry:
        return net, xr
    E, X, ex = M["E"], M["X"], M["A"]._xl()
    cands, keep = [], []
    for i, (s, sd) in enumerate(zip(rows_s, rows_side)):
        side = "C" if sd == 1 else "P"
        k = E.strike_for(D, und, side, int(s), K4.ITM)
        if k is not None:
            cands.append(dict(side=side, strike=k, er=int(s) + 1))
            keep.append(i)
    for i, t in zip(keep, X.price(und, D, cands, ex, None, "own")):
        if t is not None:
            net[i], xr[i] = t["net"], t["xr"]
    return net, xr


def build(und: str) -> dict:
    import r155study as S5
    M = S5.mods()
    e6 = DT6.load(und, K.POST)
    lo, hi = (K4.POST_START, K4.POST_END) if K.POST else (K4.EARLY_START, K4.FINAL_END)
    days = dict(M["DATA"].sessions(und, lo, hi))
    n = len(e6["day"])
    net, xr = np.full(n, np.nan, np.float32), np.full(n, -1, np.int16)
    cut = np.concatenate([[0], np.flatnonzero(np.diff(e6["day"]) != 0) + 1, [n]])
    for a, b in zip(cut[:-1], cut[1:]):
        D = days[date.fromordinal(int(e6["day"][a]))]
        net[a:b], xr[a:b] = day_labels(und, D, e6["s"][a:b], e6["side"][a:b], M)
    K.OUT.mkdir(parents=True, exist_ok=True)
    np.savez(K.OUT / f"xl_{und}{'_post' if K.POST else ''}.npz", net=net, xr=xr)
    return dict(und=und, examples=n, labelled=round(float(np.isfinite(net).mean()), 4))


def load(und: str, post: bool = False) -> dict:
    z = np.load(K.OUT / f"xl_{und}{'_post' if post else ''}.npz")
    return {k: z[k] for k in z.files}


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
    else:
        raise SystemExit(__doc__)
