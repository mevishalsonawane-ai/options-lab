"""Liquidity swings and liquidity pools, written from the published descriptions of LuxAlgo's "Liquidity Swings" and
"Liquidity Pools" TradingView indicators (their source is theirs; this is our own implementation of the idea).

Liquidity swing: a pivot high / low with [length] bars on each side (confirmed [length] bars later). Its zone is the
  pivot candle's full range ("Full Range") or its wick beyond the body ("Wick Extremity"). The zone counts how often
  price comes back into it; it is taken (broken) when a close goes through its outer edge (above a high's top,
  below a low's bottom).
Liquidity pool: an area where price was rejected [contacts] times - each contact at least [gap] bars after the
  previous one - and that then held for [confirm] more bars without a close through it. For highs the area is the
  first contact's upper wick (body top .. high) and a contact is a bar that trades into it and closes back below its
  top; lows mirror it. A close through it before confirmation discards it; after confirmation that close is the
  break (the pool is "taken").
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from .core import indicator

C = "Levels & structure"


@dataclass
class Zone:
    kind: str          # "swing" or "pool"
    side: int          # +1 a high (liquidity above price), -1 a low (liquidity below)
    top: float
    bottom: float
    origin: int        # bar the zone is anchored to
    known: int         # first bar at which it is known (confirmation)
    broken: int = -1   # bar whose close went through it, -1 if still active
    contacts: int = 0

    @property
    def edge(self) -> float:
        return self.top if self.side > 0 else self.bottom


def swing_zones(df: pd.DataFrame, length: int = 20, area: str = "full") -> list[Zone]:
    h, l, o, c = (df[k].values for k in ("high", "low", "open", "close"))
    n = len(c)
    zones: list[Zone] = []
    for i in range(2 * length, n):
        j = i - length
        win = slice(j - length, j + length + 1)
        if h[j] == h[win].max() and (h[win] == h[j]).sum() == 1:
            bot = l[j] if area == "full" else max(o[j], c[j])
            zones.append(Zone("swing", 1, h[j], bot, j, i))
        if l[j] == l[win].min() and (l[win] == l[j]).sum() == 1:
            top = h[j] if area == "full" else min(o[j], c[j])
            zones.append(Zone("swing", -1, top, l[j], j, i))
    _mark(zones, h, l, c)
    return zones


def pool_zones(df: pd.DataFrame, contacts: int = 2, gap: int = 5, confirm: int = 10, max_age: int = 300) -> list[Zone]:
    h, l, o, c = (df[k].values for k in ("high", "low", "open", "close"))
    n = len(c)
    out: list[Zone] = []
    cand: list[list] = []          # [side, top, bottom, origin, contacts, last_contact]
    for i in range(n):
        keep = []
        for z in cand:
            side, top, bot, origin, cnt, last = z
            if i - origin > max_age:
                continue
            if (side > 0 and c[i] > top) or (side < 0 and c[i] < bot):
                continue                                  # broken before it was confirmed
            touched = (h[i] >= bot and c[i] < top) if side > 0 else (l[i] <= top and c[i] > bot)
            if touched and i - last >= gap and cnt < contacts:
                z[4] += 1
                z[5] = i
            if z[4] >= contacts and i - z[5] >= confirm:
                if not any(q.side == side and q.broken < 0 and q.bottom <= top and bot <= q.top for q in out[-50:]):
                    out.append(Zone("pool", side, top, bot, origin, i, contacts=z[4]))
                continue
            keep.append(z)
        cand = keep
        body_hi, body_lo = max(o[i], c[i]), min(o[i], c[i])
        if h[i] > body_hi:
            cand.append([1, h[i], body_hi, i, 1, i])
        if l[i] < body_lo:
            cand.append([-1, body_lo, l[i], i, 1, i])
    _mark(out, h, l, c)
    return out


def _mark(zones, h, l, c):
    """Count revisits and find the bar whose close takes each zone."""
    for z in zones:
        for i in range(z.known, len(c)):
            if (z.side > 0 and c[i] > z.top) or (z.side < 0 and c[i] < z.bottom):
                z.broken = i
                break
            if h[i] >= z.bottom and l[i] <= z.top:
                z.contacts += 1


def _per_bar(df, zones):
    n = len(df)
    br_up = np.zeros(n); br_dn = np.zeros(n)
    for z in zones:
        if z.broken >= 0:
            (br_up if z.side > 0 else br_dn)[z.broken] = 1
    return pd.DataFrame({"break_up": br_up, "break_down": br_dn}, index=df.index)


@indicator(C, "Liquidity swings (after LuxAlgo): pivot zones of the full candle or the wick; 1 on the bar a close "
              "takes a swing high (break_up) or a swing low (break_down).")
def LiquiditySwings(df, length=20, area="full"):
    return _per_bar(df, swing_zones(df, length, area))


@indicator(C, "Liquidity pools (after LuxAlgo): wick areas rejected N times, gap bars apart, held for confirm bars; "
              "1 on the bar a close takes a pool above (break_up) or below (break_down).")
def LiquidityPools(df, contacts=2, gap=5, confirm=10):
    return _per_bar(df, pool_zones(df, contacts, gap, confirm))
