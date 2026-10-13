"""OF1 trading rules (PREREG §8): the app's option-buying arm on a daily signal, from daily bhavcopy bars.

    one trade = buy the ATM call (side +1) or put (side -1) of the nearest expiry at day t+1's OPEN, one lot;
                stop -30% of the premium, step lock (+40% reached -> lock +15%), otherwise exit at the day's close.

Daily bars cannot say which came first, the high or the low, so the order is PESSIMISTIC: if the low reaches the stop the
stop fills (even if the high came first); only a day that never touched the stop can lock (exit = max(lock, close) once
the high reached the trigger). An open at or below the stop fills at the open.

Fills: buy = open + 5 bps + 2 ticks; sell at close/lock = price - 5 bps - 2 ticks; stop = price - 10 bps - 4 ticks
(research/obuy/costs.py Fills 'liq' with a 1-20-lot 5-minute volume). Charges: Costs('dated') (rates in force).
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT / "research") not in sys.path:
    sys.path.insert(0, str(ROOT / "research"))

from obuy.config import LOT_SCHEDULE  # noqa: E402
from obuy.costs import Costs  # noqa: E402

TICK = 0.05
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}


@dataclass(frozen=True)
class Arm:
    stop: float = 0.30           # exit when the premium falls 30%
    trigger: float = 0.40        # step lock armed at +40% ...
    lock: float = 0.15           # ... locking +15%


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def _tick_round(x: float) -> float:
    return round(round(x / TICK) * TICK, 2)


def fill_buy(p: float) -> float:
    return _tick_round(p * 1.0005) + 2 * TICK


def fill_sell(p: float, stop: bool = False) -> float:
    x = _tick_round(p * (0.999 if stop else 0.9995)) - (4 if stop else 2) * TICK
    return max(round(x, 2), 0.0)


def simulate_bar(o: float, h: float, l: float, c: float, arm: Arm = Arm()) -> tuple[float, str]:
    """Exit price (before exit slippage) and the reason, for a long option bought at the bar's open."""
    stop = o * (1 - arm.stop)
    if l <= stop:
        return stop, "stop"
    if h >= o * (1 + arm.trigger):
        return max(o * (1 + arm.lock), c), "lock"
    return c, "close"


def pick(day_rows: pd.DataFrame, und: str, d: date, ref: float, side: int) -> pd.Series | None:
    """The ATM option (strike nearest `ref`, the index open of d) of the nearest expiry after d, with a traded bar."""
    o = day_rows[(day_rows.und == und) & (day_rows.kind == ("CE" if side > 0 else "PE"))]
    o = o[(pd.to_datetime(o.expiry).dt.date > d) & (o.open > 0) & (o.contracts.fillna(0) > 0)]
    if o.empty:
        return None
    near = o[pd.to_datetime(o.expiry).dt.date == pd.to_datetime(o.expiry).dt.date.min()]
    k = round(ref / STEP[und]) * STEP[und]
    near = near.assign(dist=(near.strike - k).abs()).sort_values("dist")
    r = near.iloc[0]
    return r if r.dist <= STEP[und] else None


def trade(day_rows: pd.DataFrame, und: str, d: date, ref: float, side: int, arm: Arm = Arm(),
          costs: Costs = Costs("dated")) -> dict | None:
    r = pick(day_rows, und, d, ref, side)
    if r is None:
        return None
    o, h, l, c = float(r.open), float(r.high), float(r.low), float(r.close)
    px, why = simulate_bar(o, h, l, c, arm)
    entry = fill_buy(o)
    exit_ = fill_sell(px, stop=(why == "stop"))
    q = lot(und, d)
    dn = np.array([(d - date(1970, 1, 1)).days])
    ch = float(costs.charge(True, np.array([entry]), np.array([q]), dn)[0] + costs.charge(False, np.array([exit_]), np.array([q]), dn)[0])
    gross = (exit_ - entry) * q
    return {"date": d, "und": und, "side": side, "strike": float(r.strike), "expiry": r.expiry, "open": o, "high": h,
            "low": l, "close": c, "entry": entry, "exit": exit_, "why": why, "lot": q, "gross": gross, "charges": ch,
            "net": gross - ch}


def twin_p(trades: pd.DataFrame, alt: pd.DataFrame, draws: int = 1000, seed: int = 8) -> float:
    """Random-side twin: on the same days, each trade's side is a coin flip (the other side's result from `alt`, the
    opposite-side trades of the same days). p = share of twins whose net total >= the rule's."""
    m = trades[["date", "net"]].merge(alt[["date", "net"]], on="date", suffixes=("", "_alt"))
    if m.empty:
        return np.nan
    rng = np.random.default_rng(seed)
    a, b = m.net.to_numpy(), m.net_alt.to_numpy()
    flips = rng.random((draws, len(m))) < 0.5
    tot = np.where(flips, b, a).sum(axis=1)
    return float(((tot >= a.sum()).sum() + 1) / (draws + 1))
