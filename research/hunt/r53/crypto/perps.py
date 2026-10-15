"""R53 crypto perps: F1C's perp exits (f1c/perp.py walk, PerpExit: stop k x atr14_1h_pct, step or trailing lock from
+1R) and RC1's 4-hour breakout (rc1/strategies.py breakout_spells / breakout_exits), with R53's money and cost books.

Money (PREREG section 2): Rs 1 lakh notional at 1x a trade, whole Delta lots (BTC 0.001, ETH 0.01), USD/INR of the
entry day (ECB). Costs per side: common.perp_fee(book) + slippage 0.02% (common.PERP_SLIP); funding as the studies
(F1C book.perp_book: the last settled rate at entry x each 00/08/16 UTC settlement held; RC1 likewise charged the real
prints). Gap fills at the worse price are the studies' own (f1c walk: a bar that opens through the level exits at its
open; rc1: min(open, stop)).
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.f1c import perp as f1cperp

from .. import common as RC

_RC1 = str(Path(__file__).resolve().parents[2] / "rc1")
if _RC1 not in sys.path:
    sys.path.insert(0, _RC1)
import strategies as rc1s  # noqa: E402  (RC1 imports its own config as a top-level module)
import config as rc1k  # noqa: E402

NOTIONAL_INR = 100_000.0
LOT = {"BTC": 0.001, "ETH": 0.01}
BOOKS = tuple(RC.CRYPTO_BOOKS)


def qty(coin: str, price, fx) -> np.ndarray:
    """Coins for Rs 1 lakh notional at 1x, whole lots (0 = less than one lot)."""
    lot = LOT[coin]
    with np.errstate(invalid="ignore", divide="ignore"):
        q = np.floor(NOTIONAL_INR / np.asarray(fx, float) / (np.asarray(price, float) * lot) + 1e-9) * lot
    return np.where(np.isfinite(q), q, 0.0)


def settlements(t_in, t_out) -> np.ndarray:
    """Funding settlements (00/08/16 UTC) in (entry, exit] (f1c/book.py settlements)."""
    a = pd.DatetimeIndex(t_in).tz_convert("UTC").tz_localize(None).values.astype("datetime64[m]").astype(np.int64)
    b = pd.DatetimeIndex(t_out).tz_convert("UTC").tz_localize(None).values.astype("datetime64[m]").astype(np.int64)
    return (np.floor_divide(b, 480) - np.floor_divide(a, 480)).clip(min=0).astype(float)


def book_rows(coin, t_in, t_out, side, entry, exit_, fx, fund_rate, reason, extra: dict | None = None) -> pd.DataFrame:
    """Per-trade rupee nets in every cost book from raw entry / exit prices (before slippage)."""
    side = np.asarray(side, float)
    entry, exit_, fx = (np.asarray(x, float) for x in (entry, exit_, fx))
    e_fill = entry * (1 + side * RC.PERP_SLIP)
    x_fill = exit_ * (1 - side * RC.PERP_SLIP)
    q = qty(coin, entry, fx)
    gross = side * (x_fill - e_fill) * q
    fund = side * np.nan_to_num(np.asarray(fund_rate, float)) * settlements(t_in, t_out) * entry * q
    out = pd.DataFrame(dict(coin=coin, entry_ts=pd.DatetimeIndex(t_in), exit_ts=pd.DatetimeIndex(t_out), side=side,
                            entry=entry, exit=exit_, q=q, fx=fx, reason=reason, gross=gross * fx, funding=fund * fx,
                            sale_inr=np.where(side > 0, x_fill, e_fill) * q * fx))
    for b in BOOKS:
        fees = np.array([RC.perp_fee(b, abs(x)) for x in e_fill * q]) + np.array([RC.perp_fee(b, abs(x))
                                                                                   for x in x_fill * q])
        out[f"net_{b}"] = (gross - fees - fund) * fx
    for k, v in (extra or {}).items():
        out[k] = v
    return out[(q > 0) & np.isfinite(entry) & np.isfinite(exit_)].reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ F1C perps
def f1c_trades(coin: str, m1: pd.DataFrame, T, side, atr_pct, deadline, ex: f1cperp.PerpExit, fx_at, fund_at):
    """F1C perp trades: entry at the open of the minute that starts at the decision instant T (the next bar), stop =
    max(0.25%, k x atr14_1h_pct), the exit's lock, flat at the deadline's open. Arrays in, one row per trade out."""
    if not isinstance(ex, f1cperp.PerpExit):
        raise TypeError("a perp trade needs an f1c.perp.PerpExit (stop-loss AND profit lock)")
    ts = pd.DatetimeIndex(m1.index)
    T = pd.DatetimeIndex(T)
    i0, di = ts.get_indexer(T), ts.get_indexer(pd.DatetimeIndex(deadline))
    di = np.where((di < 0) & (pd.DatetimeIndex(deadline) > ts[-1]), len(ts), di)
    stop = np.maximum(f1cperp.MIN_STOP, ex.k_atr * np.asarray(atr_pct, float) / 100)
    ok = (i0 >= 0) & (di > i0) & np.isfinite(stop)
    if not ok.any():
        return pd.DataFrame()
    i0, di, stop, sd = i0[ok], di[ok], stop[ok], np.asarray(side, float)[ok]
    o, h, l, c = (m1[k].to_numpy(float) for k in ("o", "h", "l", "c"))
    w = f1cperp.walk(o, h, l, c, i0, sd, stop, di, ex.lock)
    ix = np.where(np.isfinite(w["idx"]), w["idx"], i0).astype(int)
    t_in, t_out = ts[i0], ts[np.minimum(ix, len(ts) - 1)]
    reason = np.array(["stop", "lock", "time", "data_end"], dtype=object)[np.nan_to_num(w["reason"], nan=3).astype(int)]
    return book_rows(coin, t_in, t_out, sd, w["entry"], w["exit"], fx_at(t_in), fund_at(t_in), reason,
                     extra=dict(stop_frac=stop, peak_r=w["peak_r"], T=T[ok]))


# ------------------------------------------------------------------------------------------------ RC1 breakout (4h)
@dataclass(frozen=True)
class BrkExit:
    """RC1 idea g's exits: stop = entry - stop_atr x ATR(14) of the signal bar; the trailing exit (the profit lock) at the
    next open after a close below the prior `trail_n`-bar low; optionally F1C's step lock on top (R = the stop
    distance; +1R locks +0.25R, +nR locks +(n-1)R; f1c/perp.py lock_r)."""
    name: str
    stop_atr: float = rc1k.ATR_K
    trail_n: int = rc1k.BRK_EXIT_N
    step_lock: bool = False

    def __post_init__(self):
        if not self.stop_atr > 0:
            raise ValueError("every crypto trade needs a stop-loss")
        if not self.trail_n >= 1:
            raise ValueError("every crypto trade needs a profit lock (RC1's trailing low exit)")


BRK_EXITS = {"orig": BrkExit("orig"), "step_lock": BrkExit("step_lock", step_lock=True)}


def brk_walk(e: int, o, h, l, c, a, ll, ex: BrkExit):
    """One long breakout trade entered at the open of 4h bar e. Returns (exit bar, exit price, reason).
    Order inside a bar (RC1's, made cautious for the lock): the active level (stop, or the step lock once armed) is
    hit if the bar opens through it (exit at the open) or its low touches it (exit at the level); then a close below
    the prior 10-bar low exits at the next bar's open; only then does the bar's high raise the peak (a new lock from
    the next bar). Without the step lock this is exactly rc1 breakout_spells / breakout_exits."""
    n = len(c)
    entry = o[e]
    stop = entry - ex.stop_atr * a[e - 1]
    R = entry - stop
    peak = entry
    for j in range(e, n):
        lvl, why = stop, "stop"
        if ex.step_lock and R > 0:
            lk = f1cperp.lock_r(np.array([(peak - entry) / R]), "step")[0]
            if np.isfinite(lk) and entry + lk * R > lvl:
                lvl, why = entry + lk * R, "lock"
        if l[j] <= lvl:
            return j, min(o[j], lvl), why
        if not np.isnan(ll[j]) and c[j] < ll[j]:
            return (j + 1, o[j + 1], "trail") if j + 1 < n else (n - 1, c[n - 1], "data_end")
        peak = max(peak, h[j])
    return n - 1, c[n - 1], "data_end"


def brk_levels(h, l, c):
    a = rc1s.atr(h, l, c)
    ll = pd.Series(l).shift(1).rolling(rc1k.BRK_EXIT_N, min_periods=rc1k.BRK_EXIT_N).min().to_numpy()
    hh = pd.Series(h).shift(1).rolling(rc1k.BRK_N, min_periods=rc1k.BRK_N).max().to_numpy()
    return a, ll, hh


def brk_signals(o, h, l, c, ex: BrkExit, start: int = 0) -> list:
    """RC1 idea g's spells with the given exit: entry at open i+1 after close i > the prior 20-bar high, one trade at a
    time; after an exit a new signal can come from the exit bar's close (stop) or the bar after (trailing exit), as
    rc1 breakout_spells. Returns [(entry bar, exit bar, exit price, reason)]."""
    a, ll, hh = brk_levels(h, l, c)
    n = len(c)
    out, i = [], max(start, 0)
    while i < n - 1:
        if np.isnan(hh[i]) or np.isnan(a[i]) or not c[i] > hh[i]:
            i += 1
            continue
        e = i + 1
        x, px, why = brk_walk(e, o, h, l, c, a, ll, ex)
        out.append((e, x, px, why))
        if why == "data_end":
            break
        i = x if why in ("stop", "lock") else x
    return out


def brk_trades(coin: str, b4: pd.DataFrame, spells: list, fx_at, fund_at) -> pd.DataFrame:
    if not spells:
        return pd.DataFrame()
    ts = pd.DatetimeIndex(b4.index)
    o = b4.o.to_numpy(float)
    e = np.array([s[0] for s in spells])
    x = np.array([s[1] for s in spells])
    t_in = ts[e]
    t_out = pd.DatetimeIndex([ts[j] + (pd.Timedelta(hours=4) if r in ("stop", "lock", "data_end") else pd.Timedelta(0))
                              for j, r in zip(x, (s[3] for s in spells))])
    return book_rows(coin, t_in, t_out, np.ones(len(e)), o[e], [s[2] for s in spells], fx_at(t_in), fund_at(t_in),
                     np.array([s[3] for s in spells], dtype=object))
