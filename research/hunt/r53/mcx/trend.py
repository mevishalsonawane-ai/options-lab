"""MA10 app-trend12 (PREREG section 4; app McxTrendRules, research/hunt/m2 TSM252) on the four legs the lake has.

- Series: the lake's near-month continuous daily futures (fut_daily_continuous; not back-adjusted, as the app reads).
- Signal on the last MCX trading day of each month (the month's last row): sign(close - close 252 rows earlier);
  0 with too little history. Held from the next session's open for the month.
- Minis, 1 lot (PREREG): CRUDEOILM = CRUDEOIL x 10 units; NATGASMINI = NATURALGAS x 250; SILVERMIC = SILVER per kg
  x 1; GOLDPETAL = GOLD per gram x 1 (GOLD's quote is per 10 g, so x 0.1).
- Rolls: a row where the open interest jumps > 1.8x (or the volume falls under 5% of its 20-day median), as R2's
  load_mcx_daily, is the first row of the next contract. On that day the overnight gap (old contract's close to the
  new contract's open) is not booked, and a roll is charged: closing and reopening 1 lot at that open (charges + half
  spread each side). The app rolls 5 trading days before expiry; the series' roll day is used instead (stated).
- Accounting by leg-month (the "trade" unit, entry_ts = the month's first session): for a position carried from the
  previous month, the gap from that month's last close to this month's first open (none on a roll day); for a side
  change, the old side closes and the new side opens at the first open (costs per side: common.mcx_fut_charges with
  the other side 0, plus common.fut_half_spread); then each day's close-to-close move (open-to-close on the first day
  of a new position). Positions are marked to market at the end of a period, not closed.
- Months traded: Dec 2024 - Mar 2026 (design; the signal of 29 Nov 2024 is the first, so nothing is entered before
  20 Nov 2024) and Apr - Oct 2026 (final; the data end 13 Oct).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C
from research.hunt.r53.mcx import data as D

# series -> (product, units per point of the series' quote, tick in the product's quote, family, quote scale)
LEGS = {"CRUDEOIL": ("CRUDEOILM", 10.0, 1.0, "crude", 1.0),
        "NATURALGAS": ("NATGASMINI", 250.0, 0.1, "natgas", 1.0),
        "SILVER": ("SILVERMIC", 1.0, 1.0, "silver", 1.0),
        "GOLD": ("GOLDPETAL", 1.0, 1.0, "gold", 0.1)}
LOOKBACK = 252


def mark_rolls(df: pd.DataFrame) -> pd.Series:
    oi = df.oi.astype(float)
    vol = df.volume.astype(float)
    with np.errstate(all="ignore"):
        r = ((oi / oi.shift()) > 1.8) | (vol < 0.05 * vol.rolling(20, min_periods=5).median().shift())
    return r.fillna(False).astype(bool)


def month_signals(df: pd.DataFrame, lookback: int = LOOKBACK) -> pd.Series:
    """Signal read on each month's last row (index = that row's date): sign(close - close lookback rows earlier)."""
    c = df.close.values
    out = {}
    last_rows = df.groupby(df.index.to_period("M")).apply(lambda x: x.index[-1])
    pos = {d: k for k, d in enumerate(df.index)}
    for d in last_rows.values:
        k = pos[pd.Timestamp(d)]
        out[pd.Timestamp(d)] = int(np.sign(c[k] - c[k - lookback])) if k >= lookback else 0
    return pd.Series(out).sort_index()


def side_cost(price: float, units: float, tick: float, fam: str, buy: bool) -> float:
    rs = price * units
    ch = C.mcx_fut_charges(rs, 0.0) if buy else C.mcx_fut_charges(0.0, rs)
    return ch + C.fut_half_spread(price, tick, fam) * units


def leg_months(df: pd.DataFrame, sides: dict, units: float, tick: float, fam: str, scale: float, leg: str,
               months: list) -> list:
    """sides: {Period month: side held that month}. Returns one record per leg-month with a position or a close."""
    df = df.copy()
    df["roll"] = mark_rolls(df)
    o, c = df.open.values * scale, df.close.values * scale
    per = df.index.to_period("M")
    out = []
    prev_side = 0
    prev_close = None
    for mo in months:
        ix = np.nonzero(per == mo)[0]
        side = int(sides.get(mo, 0))
        if not len(ix):
            continue
        a = ix[0]
        pnl, cost = 0.0, 0.0
        roll0 = bool(df.roll.values[a])
        if prev_side != 0:
            if not roll0 and prev_close is not None:
                pnl += prev_side * (o[a] - prev_close) * units
            elif roll0 and prev_side == side:
                cost += side_cost(o[a], units, tick, fam, buy=prev_side < 0) + side_cost(o[a], units, tick, fam, buy=side > 0)
            if prev_side != side:
                cost += side_cost(o[a], units, tick, fam, buy=prev_side < 0)
        if side != 0:
            if prev_side != side:
                cost += side_cost(o[a], units, tick, fam, buy=side > 0)
            pnl += side * (c[a] - o[a]) * units
            for k in ix[1:]:
                if df.roll.values[k]:
                    pnl += side * (c[k] - o[k]) * units
                    cost += side_cost(o[k], units, tick, fam, buy=side < 0) + side_cost(o[k], units, tick, fam, buy=side > 0)
                else:
                    pnl += side * (c[k] - c[k - 1]) * units
        if side != 0 or prev_side != 0:
            out.append(dict(entry_ts=df.index[a], leg=leg, side=side, prev_side=prev_side, gross=float(pnl),
                            cost=float(cost), net=float(pnl - cost), days=int(len(ix))))
        prev_side = side
        prev_close = c[ix[-1]]
    return out


def months_of(part: str) -> list:
    if part == "design":
        return list(pd.period_range("2024-12", "2026-03", freq="M"))
    return list(pd.period_range("2026-04", "2026-10", freq="M"))


def load_legs(lake=None) -> dict:
    out = {}
    for s in LEGS:
        d = D.load_daily(s, lake)
        out[s] = d[d.index <= pd.Timestamp(C.FINAL[1])]
    return out


def rule_sides(dfs: dict) -> dict:
    """{leg: {month: side}}: the month-end signal applies to the next month."""
    out = {}
    for s, df in dfs.items():
        sg = month_signals(df)
        out[s] = {(pd.Timestamp(d).to_period("M") + 1): int(v) for d, v in sg.items()}
    return out


def run(dfs: dict, sides: dict, part: str) -> list:
    """All legs' leg-month records of one period. For the final part the position carried from the last design month
    is the rule's own (sides[leg][Mar 2026])."""
    months = months_of(part)
    trades = []
    for s, df in dfs.items():
        prod, units, tick, fam, scale = LEGS[s]
        mo_all = months
        if part == "final":
            mo_all = [months[0] - 1] + months          # carry the March position in, then drop March's record
        recs = leg_months(df[df.index >= pd.Timestamp("2024-11-20") if part == "design" else df.index >= pd.Timestamp("2026-03-01")],
                          sides[s], units, tick, fam, scale, prod, mo_all)
        if part == "final":
            recs = [r for r in recs if pd.Timestamp(r["entry_ts"]).to_period("M") >= months[0]]
        trades += recs
    return [t for t in trades if C.part_of(t["entry_ts"]) == part]


def twin_nets(dfs: dict, sides: dict, part: str, n: int, rng) -> np.ndarray:
    """Each twin flips a fair coin for the side of every leg-month the rule holds (same legs, months, exits, costs)."""
    out = np.zeros(n)
    months = months_of(part)
    for b in range(n):
        tw = {}
        for s in dfs:
            tw[s] = dict(sides[s])
            for mo in months:
                if tw[s].get(mo, 0) != 0:
                    tw[s][mo] = int(rng.choice([-1, 1]))
        out[b] = sum(t["net"] for t in run(dfs, tw, part))
    return out
