"""F1C engine: rule signals -> trades (perps or Delta India options) -> rupees, plus random-entry twins.

A rule sees a decision frame: brain features at every 5-minute close (bars closing on :00, :05, ...) of one coin,
labels removed. It returns decisions (ts = the decision bar, side +1 long / -1 short, and optionally its own time
limit). Every decision is inside Boss's window (00:00-09:00 IST) and every trade is flat by 09:00 IST.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from typing import Callable

import numpy as np
import pandas as pd

from . import book, data, options, perp
from . import f1ccfg as C


@dataclass(frozen=True)
class Rule:
    rid: str
    title: str
    venue: str                              # "perp" or "option"
    coins: tuple
    signal: Callable                        # f(frame) -> DataFrame[ts, side]
    max_hold_min: int | None = None         # None = to 09:00 IST
    max_per_day: int | None = None
    needs: tuple = field(default_factory=tuple)

    @property
    def exits(self):
        return perp.EXITS if self.venue == "perp" else options.EXITS


ONE = pd.Timedelta(minutes=1)


def in_window(dec_ts) -> np.ndarray:
    """True when the ENTRY (the minute after the decision bar) is inside 00:00-09:00 IST: the bar closing at 00:00 IST
    (18:29 UTC) is the window's first decision, the one closing at 09:00 IST is not a decision."""
    t = pd.DatetimeIndex(dec_ts) + ONE + C.IST
    return np.asarray(t.hour < 9)


def window_end(dec_ts) -> pd.DatetimeIndex:
    """09:00 IST (03:30 UTC) of the IST day of each decision's entry."""
    t = pd.DatetimeIndex(dec_ts) + ONE + C.IST
    return t.normalize() + pd.Timedelta(hours=9) - C.IST


def frame(sym: str, part: str, columns=None) -> pd.DataFrame:
    """The decision frame: features at 5-minute closes inside the window, the label columns dropped."""
    F = data.rows(sym, 5, part, horizon_min=0, columns=columns)
    if F.empty:
        return F
    F = F[[c for c in F.columns if not c.startswith("y_")]]
    F = F[in_window(F.ts)].reset_index(drop=True)
    e = F.ts + ONE + C.IST                                   # the entry minute on the IST clock
    F["ist_min"] = (e.dt.hour * 60 + e.dt.minute).astype(int)
    F["ist_day"] = e.dt.date
    return F


class Market:
    """Perp minute bars of one coin over a part, with index lookups."""

    def __init__(self, sym: str, part: str):
        spans = C.DESIGN if part == "design" else (C.HOLDOUT,)
        # each span on its own (design never holds a holdout minute in memory); a trade cannot cross from one span
        # to the next because its deadline must lie inside the part (data.keep)
        M = pd.concat([data.bars(sym, a.date(), (b - pd.Timedelta(days=1)).date()) for a, b in spans],
                      ignore_index=True)
        self.sym, self.part = sym, part
        self.ts = pd.DatetimeIndex(M.ts)
        self.o, self.h, self.l, self.c = (M[k].to_numpy(float) for k in ("perp_open", "perp_high", "perp_low",
                                                                          "perp_close"))
        self.spot = M.close.to_numpy(float)
        self.fund = M.funding_rate.to_numpy(float)

    def idx(self, ts) -> np.ndarray:
        return self.ts.get_indexer(pd.DatetimeIndex(ts))


def _deadlines(dec_ts, max_hold_min) -> pd.DatetimeIndex:
    we = window_end(dec_ts)
    if max_hold_min is None:
        return we
    lim = pd.DatetimeIndex(dec_ts) + pd.Timedelta(minutes=1 + max_hold_min)
    return pd.DatetimeIndex(np.minimum(we.values, lim.values))


def perp_trades(rule: Rule, mk: Market, dec: pd.DataFrame, atr_pct: np.ndarray, ex: perp.PerpExit,
                stress: float = 1.0, account: float = C.ACCOUNT, part: str = "design") -> pd.DataFrame:
    """All candidate trades of the decisions (not yet one-at-a-time)."""
    if dec.empty:
        return pd.DataFrame()
    t_in = pd.DatetimeIndex(dec.ts) + pd.Timedelta(minutes=1)
    dl = _deadlines(dec.ts, dec["max_hold"].iloc[0] if "max_hold" in dec else rule.max_hold_min)
    i0, di = mk.idx(t_in), mk.idx(dl)
    stop = np.maximum(perp.MIN_STOP, ex.k_atr * np.asarray(atr_pct, float) / 100)
    ok = (i0 >= 0) & (di > i0) & np.isfinite(stop) & data.keep(dl, part, 0)
    i0, di, stop, side = i0[ok], di[ok], stop[ok], dec.side.to_numpy(float)[ok]
    w = perp.walk(mk.o, mk.h, mk.l, mk.c, i0, side, stop, di, ex.lock)
    fund = mk.fund[np.maximum(i0 - 1, 0)]
    return book.perp_book(mk.sym, mk.ts, i0, w, side, stop, fund, account, stress)


_DAYS: dict = {}


def option_trades(rule: Rule, mk: Market, dec: pd.DataFrame, ex, stress: float = 1.0, account: float = C.ACCOUNT,
                  part: str = "design") -> pd.DataFrame:
    if dec.empty:
        return pd.DataFrame()
    from research.hunt.rc2 import config as RC
    dl = _deadlines(dec.ts, rule.max_hold_min)
    spot_i = mk.idx(dec.ts)
    rows = []
    for t, s, d, si in zip(pd.DatetimeIndex(dec.ts), dec.side.to_numpy(int), dl, spot_i):
        if si < 0 or not data.keep([d], part, 0)[0]:
            continue
        ed = (t + ONE + C.IST).date()                        # the expiry at 12:00 UTC of the entry's IST date
        key = (mk.sym, ed)
        if key not in _DAYS:                                 # ~1.5 MB a day: the whole design fits (about 1 GB)
            _DAYS[key] = options.Day(mk.sym, ed)
        day = _DAYS[key]
        if not day.ok:
            continue
        r = options.trade(day, t, s, mk.spot[si], d, ex, account,
                          spread_mult=1.5 if stress > 1 else 1.0,
                          fee_rate=RC.FEE_RATE_STRESS if stress > 1 else RC.FEE_RATE)
        if r is not None:
            rows.append(r)
    return pd.DataFrame(rows)


def trades(rule: Rule, sym: str, mk: Market, F: pd.DataFrame, ex, stress: float = 1.0, account: float = C.ACCOUNT,
           part: str = "design", dec: pd.DataFrame | None = None) -> pd.DataFrame:
    """The rule's selected trades (one at a time, day cap, max per day) for one coin and exit variant."""
    if dec is None:
        dec = rule.signal(F, mk)
    if dec is None or dec.empty:
        return pd.DataFrame()
    dec = dec[in_window(dec.ts)].sort_values("ts").reset_index(drop=True)
    if rule.venue == "perp":
        atr = F.set_index("ts").atr14_1h_pct.reindex(pd.DatetimeIndex(dec.ts)).to_numpy()
        B = perp_trades(rule, mk, dec, atr, ex, stress, account, part)
    else:
        B = option_trades(rule, mk, dec, ex, stress, account, part)
    if B.empty:
        return B
    B = book.select(B, account, rule.max_per_day)
    B["coin"] = sym
    return B


def twin_decisions(rule_dec: pd.DataFrame, F: pd.DataFrame, n: int, seed: int) -> pd.DataFrame:
    """Random entries: the rule's own IST entry minutes (drawn at random), random design days, coin-flip sides."""
    rng = np.random.default_rng(seed)
    if rule_dec.empty:
        return pd.DataFrame(columns=["ts", "side"])
    e = pd.DatetimeIndex(rule_dec.ts) + ONE + C.IST
    mins = e.hour * 60 + e.minute
    pick = rng.choice(np.asarray(mins), n)
    by_min = {m: g.ts.to_numpy() for m, g in F.groupby("ist_min")}
    ts = []
    for m in pick:
        cand = by_min.get(int(m))
        if cand is not None and len(cand):
            ts.append(cand[rng.integers(0, len(cand))])
    ts = pd.DatetimeIndex(np.sort(np.array(ts, dtype="datetime64[ns]")))
    return pd.DataFrame({"ts": ts, "side": rng.choice([-1, 1], len(ts))})


def twin_pool(rule: Rule, sym: str, mk: Market, F: pd.DataFrame, ex, rule_dec: pd.DataFrame, n: int = 3000,
              seed: int = 0) -> np.ndarray:
    """Net rupees after tax A of random-entry twins (each an independent trade: no one-at-a-time, no day cap)."""
    td = twin_decisions(rule_dec, F, n, seed)
    if td.empty:
        return np.array([])
    if rule.venue == "perp":
        atr = F.set_index("ts").atr14_1h_pct.reindex(pd.DatetimeIndex(td.ts)).to_numpy()
        B = perp_trades(rule, mk, td, atr, ex)
    else:
        B = option_trades(rule, mk, td, ex)
    return B.net_a.to_numpy() if len(B) else np.array([])


def design_days(F: pd.DataFrame) -> int:
    return int(F.ist_day.nunique()) if len(F) else 0
