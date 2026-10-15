"""R53 gold twins (PREREG section 3), 500 per tested version, p = common.twin_p.

random   the study's exit code can take any entry: each candidate entry (a bar inside the rule's own entry window, on any
         day of the period) is traded once with the rule's own stop / target / lock / time exits; a twin draws as many
         candidates at random (with replacement) as the rule has trades in the period and sums their nets. Buys only,
         like the rules. Twin trades may overlap each other (the rules' one-position-at-a-time is not imposed).
hold     the study's exits depend on the signal's own state (TAS's tracker line, the liquidity levels, the Supertrend):
         each twin trade is a random minute inside the rule's entry window, on a random day of the same period, held
         exactly as long as the matched real trade, bought at the minute's ask (mid + 0.15) and sold at the bid of the
         first minute at or after the hold ends (mid - 0.15), $7 a lot, and the swap where the study charged it.
"""
from __future__ import annotations

import zlib

import numpy as np
import pandas as pd

from .. import common as C
from . import rules as R

N_TWINS = C.N_TWINS


def rng_for(*key) -> np.random.Generator:
    return np.random.default_rng([C.SEED, zlib.crc32("|".join(map(str, key)).encode())])


def in_part_mask(ts, part: str) -> np.ndarray:
    d = pd.DatetimeIndex(ts).normalize()
    lo, hi = (C.DESIGN if part == "design" else C.FINAL)
    return np.asarray((d >= pd.Timestamp(lo)) & (d <= pd.Timestamp(hi)))


def random_twins(cands, n_trades: int, part: str, rng, n=N_TWINS) -> np.ndarray:
    """cands: [(entry_ts, net)] over the whole data. Returns n twin nets for the period."""
    if not cands or n_trades == 0:
        return np.zeros(n)
    ts = np.array([c[0] for c in cands], dtype="datetime64[ns]")
    net = np.array([c[1] for c in cands], dtype=float)
    net = net[in_part_mask(ts, part)]
    if net.size == 0:
        return np.zeros(n)
    pick = rng.integers(0, net.size, size=(n, n_trades))
    return net[pick].sum(axis=1)


class Minutes:
    """Weekday mid minutes (gold_session_strats.load) as arrays for the hold-matched twins."""

    def __init__(self, mid: pd.DataFrame):
        self.t = mid.index.values.astype("datetime64[m]")
        self.o = mid.open.values.astype(float)
        idx = mid.index
        self.mod = (idx.hour * 60 + idx.minute).values
        self.dow = idx.dayofweek.values


def hold_twins(M: Minutes, holds_min, part: str, window, swap: bool, rng, n=N_TWINS) -> np.ndarray:
    """holds_min: the real trades' holding times (minutes). window(mod, dow) -> bool mask of allowed entry minutes."""
    holds = np.asarray(holds_min, dtype=np.int64)
    if holds.size == 0:
        return np.zeros(n)
    ok = np.flatnonzero(in_part_mask(M.t, part) & np.asarray(window(M.mod, M.dow)))
    if ok.size == 0:
        return np.zeros(n)
    i0 = ok[rng.integers(0, ok.size, size=(n, holds.size))]
    t1 = M.t[i0] + holds[None, :].astype("timedelta64[m]")
    i1 = np.minimum(np.searchsorted(M.t, t1, side="left"), len(M.t) - 1)
    pnl = 100 * ((M.o[i1] - R.H) - (M.o[i0] + R.H) - R.COMM)
    if swap:
        pnl = pnl - 100 * R.SWAP * R.nights_vec(M.t[i0], M.t[i1])
    return pnl.sum(axis=1)
