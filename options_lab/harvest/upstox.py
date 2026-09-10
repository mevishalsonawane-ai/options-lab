"""Upstox v3 candle client - dated history and the current session.

Works with no Authorization header as of 2026-09-07. That is undocumented
server behaviour and can be withdrawn without notice, so every caller must
treat an auth failure as a loud, fatal condition rather than "no data".
"""
from __future__ import annotations

import urllib.parse
from dataclasses import dataclass
from datetime import date, datetime, timedelta

BASE = "https://api.upstox.com/v3/historical-candle"
MAX_WINDOW = timedelta(days=30)  # the 1-minute endpoint rejects longer ranges


class UpstoxError(RuntimeError):
    """The API returned an error payload. Never silently treated as empty."""


@dataclass(frozen=True)
class Bar:
    ts: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int
    open_interest: int


def candle_url(instrument_key: str, *, to: date, frm: date) -> str:
    """Path order is /{to}/{from}; reversing it returns an empty candle list."""
    key = urllib.parse.quote(instrument_key, safe="")
    return f"{BASE}/{key}/minutes/1/{to:%Y-%m-%d}/{frm:%Y-%m-%d}"


def intraday_url(instrument_key: str) -> str:
    """Bars for the CURRENT session, which the dated endpoint cannot return.

    Probed 2026-09-10 12:54 IST on a liquid ATM contract:

        /minutes/1/2026-09-10/2026-09-10   HTTP 200, 0 candles
        /intraday/<key>/minutes/1          HTTP 200, 220 candles, latest 12:54

    This is the difference between capturing an expiry chain and losing it.
    An option is delisted the moment it settles and its token is recycled, so
    the expiring series must be read on its own day - and the dated endpoint
    only ever serves sessions that have already closed. A harvester built on
    it can never record a same_day chain, whatever time it runs.
    """
    key = urllib.parse.quote(instrument_key, safe="")
    return f"{BASE}/intraday/{key}/minutes/1"


def parse_candles(payload: dict) -> list[Bar]:
    """Upstox returns candles newest-first; we store ascending."""
    if payload.get("status") != "success":
        errors = payload.get("errors") or [{"message": "unknown Upstox error"}]
        detail = "; ".join(
            f"{e.get('errorCode', '?')}: {e.get('message', '')}" for e in errors
        )
        raise UpstoxError(detail)

    rows = payload.get("data", {}).get("candles", [])
    bars = [
        Bar(
            ts=datetime.fromisoformat(ts),
            open=float(o),
            high=float(h),
            low=float(low),
            close=float(c),
            volume=int(v),
            open_interest=int(oi),
        )
        for ts, o, h, low, c, v, oi in rows
    ]
    bars.sort(key=lambda b: b.ts)
    return bars


def month_chunks(start: date, end: date) -> list[tuple[date, date]]:
    """Split [start, end] into contiguous windows of at most one month.

    A reversed range used to return [], so the caller fetched nothing and read
    the empty result as "no data exists" rather than "I asked wrongly".
    """
    if end < start:
        raise ValueError(
            f"end {end} is before start {start}; the range is reversed"
        )
    chunks: list[tuple[date, date]] = []
    lo = start
    while lo <= end:
        hi = min(lo + MAX_WINDOW, end)
        chunks.append((lo, hi))
        lo = hi + timedelta(days=1)
    return chunks
