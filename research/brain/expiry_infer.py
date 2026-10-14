"""Expiry days inferred from the lake's option prices, for indices with no expiry rule in calendar.RULES (F1, 14 Oct
2026). Without an expiry date the cube builds no chain, so FINNIFTY, MIDCPNIFTY and SENSEX had option files in the lake
but no chain in the brain (COVERAGE.md: "with option chain 0").

    python -m research.brain.expiry_infer --und FINNIFTY SENSEX     # -> calendar_data/expiries.csv rows

MIDCPNIFTY is left out on purpose: its rolling series shows a Friday "expiry" 3-4 days before a Monday one (2023-24),
so the method does not recover its dates reliably; it stays index-only.

Method (R25, research/hunt/r25/r25data.py): Dhan's rolling option rows are the nearest expiry. On an expiry day the ATM
straddle's time value near the close (last ATM call + put close from 15:00 to 15:20, minus the intrinsic value, % of
spot) is below 0.6%, and the next session's (the next contract) is more than twice it. The last session in the data
is never called an expiry. Expiries are scheduled weeks ahead, so knowing them at minute t is not look-ahead; this
only recovers the dates from the data instead of a weekday rule.
"""
from __future__ import annotations

import argparse
from datetime import date

import numpy as np
import pandas as pd

from . import calendar, config
from .adapters.lake import LakeAdapter, _naive_ist

TV_MAX, TV_JUMP = 0.6, 2.0
SOURCE = "lake ATM time value 15:00-15:20 (R25 method)"


def close_time_value(o: pd.DataFrame) -> pd.Series:
    """o: lake option rows (ts naive IST, offset, side, close, strike, spot). ATM straddle time value near the close,
    % of spot, per session date."""
    mn = (o.ts - o.ts.dt.normalize()).dt.total_seconds() // 60
    x = o[(o.offset == 0) & (mn >= 15 * 60) & (mn <= 15 * 60 + 20)].sort_values("ts")
    if x.empty:
        return pd.Series(dtype=float)
    x = x.assign(d=x.ts.dt.date)
    last = x.groupby(["d", "side"]).last()
    c = last.xs("C", level="side") if "C" in last.index.get_level_values(1) else None
    p = last.xs("P", level="side") if "P" in last.index.get_level_values(1) else None
    if c is None or p is None:
        return pd.Series(dtype=float)
    j = c[["close", "strike", "spot"]].join(p[["close", "strike"]], rsuffix="_p", how="inner")
    j = j[j.strike == j.strike_p]
    return ((j.close + j.close_p - (j.spot - j.strike).abs()) / j.spot * 100).sort_index()


def expiry_days(tv: pd.Series) -> list[date]:
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return [d for d, v in ok.items() if v]


def infer(lake: LakeAdapter, und: str) -> list[date]:
    tvs = []
    for p in lake._files(rf"opt_{und}_WEEK_\d{{4}}\.parquet"):
        o = pd.read_parquet(p, columns=["ts", "offset", "side", "close", "strike", "spot"])
        o["ts"] = _naive_ist(o.ts)
        o["side"] = o.side.astype(str).str[0].str.upper()
        tvs.append(close_time_value(o))
    if not tvs:
        return []
    tv = pd.concat(tvs)
    tv = tv[~tv.index.duplicated(keep="last")]
    return expiry_days(tv)


def write(unds, lake: LakeAdapter) -> pd.DataFrame:
    rows = []
    for u in unds:
        days = infer(lake, u)
        rows += [dict(date=d, und=u, source=SOURCE) for d in days]
        wd = pd.Series([d.weekday() for d in days]).value_counts().sort_index().to_dict()
        print(f"{u}: {len(days)} expiry days inferred ({days[0] if days else '-'} .. {days[-1] if days else '-'}); "
              f"weekdays {wd}")
    new = pd.DataFrame(rows, columns=["date", "und", "source"])
    old = pd.read_csv(calendar.EXPIRIES_CSV) if calendar.EXPIRIES_CSV.exists() else new.iloc[:0]
    old = old[~old.und.isin(unds)]
    out = pd.concat([old, new.astype({"date": str})], ignore_index=True).sort_values(["und", "date"])
    out.to_csv(calendar.EXPIRIES_CSV, index=False)
    calendar.observed_expiries.cache_clear()
    return new


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.expiry_infer")
    ap.add_argument("--und", nargs="+", default=["FINNIFTY", "SENSEX"])
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    bad = [u for u in a.und if u in calendar.RULES]
    if bad:
        raise SystemExit(f"{bad} have weekday rules and release contracts; this tool is for indices without them")
    write(a.und, LakeAdapter(config.paths(a.cache).lake))


if __name__ == "__main__":
    main()
