"""F1C perp simulator: every trade has a stop-loss AND a profit lock (CLAUDE.md crypto rule), vectorised over trades.

Prices are the perp's 1-minute bars (Binance USD-M standing in for Delta India INR perps). A trade enters at the open of
bar i0 (the minute after its signal bar closed) and is walked over bars i0 .. deadline - 1; at bar `deadline` it exits
at the open (time exit: 09:00 IST or the rule's own limit). Inside a bar the order is unknown, so the check is the
cautious one (PREREG section 5):
  1. from the second bar on, a bar that opens through the active level exits at its open (a gap);
  2. a bar whose low (long) / high (short) touches the level exits at the level;
  3. only then does the bar's high (long) / low (short) raise the peak; a new lock applies from the next bar.
A short is the long logic on negated prices. R = the stop distance.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class PerpExit:
    name: str
    k_atr: float              # stop = k x atr14_1h_pct of the entry price (never below MIN_STOP)
    lock: str                 # "step" or "trail"

    def __post_init__(self):
        if not self.k_atr > 0:
            raise ValueError("every crypto trade needs a stop-loss")
        if self.lock not in ("step", "trail"):
            raise ValueError("every crypto trade needs a profit lock (step or trail)")


MIN_STOP = 0.0025             # 0.25% of the price
LOCK_START_R = 1.0            # both locks arm once the peak is +1R
STEP_FIRST = 0.25             # step: +1R locks +0.25R; +2R locks +1R; +nR locks +(n-1)R
TRAIL_KEEP = 0.5              # trail: keep 50% of the open profit, never below +0.25R
EXITS = (PerpExit("s10_step", 1.0, "step"), PerpExit("s10_trail", 1.0, "trail"),
         PerpExit("s15_step", 1.5, "step"), PerpExit("s15_trail", 1.5, "trail"))


def lock_r(peak_r: np.ndarray, lock: str) -> np.ndarray:
    """Locked profit in R for a peak in R (-inf = no lock yet)."""
    pk = np.asarray(peak_r, float)
    out = np.full(pk.shape, -np.inf)
    armed = pk >= LOCK_START_R - 1e-12
    if lock == "step":
        out[armed] = np.where(pk[armed] < 2, STEP_FIRST, np.floor(pk[armed] + 1e-12) - 1)
    else:
        out[armed] = np.maximum(STEP_FIRST, TRAIL_KEEP * pk[armed])
    return out


def walk(o, h, l, c, i0, side, stop_frac, deadline, lock: str, chunk: int = 4000):
    """Exits for many trades. o/h/l/c: the perp's minute arrays; i0, side (+1/-1), stop_frac (fraction of the entry
    price), deadline (exclusive bar index): arrays, one per trade. Returns dict of arrays: entry (the bar-open entry
    price before slippage), exit price, exit index, reason (0 stop, 1 lock, 2 time, 3 data end), peak in R before
    the exit, and the best price in R reached later, up to the deadline (for "winners cut short")."""
    i0 = np.asarray(i0, int)
    side = np.asarray(side, float)
    stop_frac = np.asarray(stop_frac, float)
    deadline = np.asarray(deadline, int)
    n = len(i0)
    res = {k: np.full(n, np.nan) for k in ("entry", "exit", "idx", "reason", "peak_r", "after_r")}
    N = len(o)
    for a in range(0, n, chunk):
        sl = slice(a, min(a + chunk, n))
        I, S, F, D = i0[sl], side[sl], stop_frac[sl], deadline[sl]
        T = int(max(1, (D - I).max() + 1))
        J = I[:, None] + np.arange(T)[None, :]
        inside = J < np.minimum(D, N)[:, None]
        Jc = np.minimum(J, N - 1)
        sg = S[:, None]
        # long view: prices x side; for a short the "high" is -low and the "low" is -high
        O = o[Jc] * sg
        Hh = np.where(sg > 0, h[Jc], -l[Jc])
        Ll = np.where(sg > 0, l[Jc], -h[Jc])
        E = O[:, 0].copy()
        R = np.abs(E) * F
        valid = np.isfinite(E) & (R > 0)
        Hf = np.where(np.isfinite(Hh) & inside, Hh, -np.inf)
        peak_before = np.maximum.accumulate(np.concatenate([E[:, None], Hf[:, :-1]], axis=1), axis=1)
        pk_r = (peak_before - E[:, None]) / R[:, None]
        lvl_r = np.maximum(-1.0, lock_r(pk_r, lock))
        lvl = E[:, None] + lvl_r * R[:, None]
        gap = (O <= lvl) & np.isfinite(O) & inside
        gap[:, 0] = False                                      # the entry bar opened at the entry
        touch = (Ll <= lvl) & np.isfinite(Ll) & inside
        hit = gap | touch
        any_hit = hit.any(axis=1)
        first = np.where(any_hit, hit.argmax(axis=1), T)
        rows = np.arange(len(I))
        fi = np.minimum(first, T - 1)
        px = np.where(gap[rows, fi], O[rows, fi], lvl[rows, fi])
        why = np.where(lvl_r[rows, fi] >= 0, 1.0, 0.0)          # a locked level >= breakeven is a lock exit
        # no hit: exit at the deadline bar's open (or the last finite close if the data ends / the open is missing)
        dl = np.minimum(D, N - 1)
        o_dl = o[dl] * S
        late = ~any_hit
        last_c = np.array([c[min(int(d), N - 1)] for d in D]) * S
        px = np.where(late, np.where(np.isfinite(o_dl) & (D < N), o_dl, last_c), px)
        why = np.where(late, np.where(D < N, 2.0, 3.0), why)
        idx = np.where(late, dl, I + fi)
        peak_exit = np.where(late, peak_before[rows, -1], peak_before[rows, fi])
        # after the exit: the best price reached up to the deadline (Hf beyond the exit bar)
        after = np.where(np.arange(T)[None, :] > fi[:, None], Hf, -np.inf).max(axis=1)
        res["entry"][sl] = np.where(valid, E * S, np.nan)
        res["exit"][sl] = np.where(valid, px * S, np.nan)
        res["idx"][sl] = np.where(valid, idx, np.nan)
        res["reason"][sl] = np.where(valid, why, np.nan)
        res["peak_r"][sl] = np.where(valid, (peak_exit - E) / R, np.nan)
        res["after_r"][sl] = np.where(valid & ~late, (after - px) / R, np.nan)
    return res
