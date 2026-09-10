"""Turn listed contracts into stored bars.

The network is injected as `fetch` so the orchestration is testable without it.
An API error is always propagated: a recycled or dead instrument key must never
be mistaken for "this contract had no trades".
"""
from __future__ import annotations

from datetime import date
from typing import Callable, Iterable, Iterator

import pandas as pd

from options_lab.harvest import store, upstox
from options_lab.harvest.instruments import Contract

Fetch = Callable[[str], dict]

BAR_COLUMNS = [c for c in store.COLUMNS if c not in ("source", "fetched_at")]


def to_frame(contract: Contract, bars: Iterable[upstox.Bar]) -> pd.DataFrame:
    """Stamp contract identity onto each bar, in store schema order."""
    rows = [
        {
            "contract_id": contract.contract_id,
            "instrument_key": contract.instrument_key,
            "underlying": contract.underlying,
            "expiry": contract.expiry,
            "strike": contract.strike,
            "right": contract.right,
            "lot_size": contract.lot_size,
            "ts": b.ts,
            "open": b.open,
            "high": b.high,
            "low": b.low,
            "close": b.close,
            "volume": b.volume,
            "open_interest": b.open_interest,
        }
        for b in bars
    ]
    if not rows:
        return pd.DataFrame({c: pd.Series(dtype="object") for c in BAR_COLUMNS})
    return pd.DataFrame(rows, columns=BAR_COLUMNS)


def harvest_contract(
    contract: Contract, start: date, end: date, *, fetch: Fetch,
    today: date | None = None, include_current: bool | None = None,
) -> pd.DataFrame:
    """All 1-minute bars for one contract over [start, end], ascending.

    The dated endpoint does not serve the CURRENT session - probed 2026-09-10
    12:54 IST, it returned 0 candles for today while /intraday returned 220.
    So a range that includes today also asks the intraday endpoint.

    This is the difference between capturing an expiry chain and losing it. An
    option is delisted the moment it settles and its token recycled, so the
    expiring series must be read on its own day; a harvester built only on the
    dated endpoint can never record one, whatever time of day it runs.

    `include_current` is the caller's policy hook, because the intraday call
    doubles the request count and Upstox rate-limits at 429. Only a contract
    that EXPIRES today actually needs it: anything still listed tomorrow can be
    read from the dated endpoint then, unchanged. None means "decide from the
    range" and is what the tests and ad-hoc use rely on.
    """
    today = date.today() if today is None else today
    bars: list[upstox.Bar] = []
    for frm, to in upstox.month_chunks(start, end):
        url = upstox.candle_url(contract.instrument_key, to=to, frm=frm)
        bars.extend(upstox.parse_candles(fetch(url)))

    wants_current = (end >= today if include_current is None
                     else include_current)
    if wants_current:
        bars.extend(upstox.parse_candles(
            fetch(upstox.intraday_url(contract.instrument_key))))

    frame = to_frame(contract, bars)
    if frame.empty:
        return frame
    # The two endpoints can overlap on the current day, so the same minute can
    # arrive twice. Dropping here keeps the duplicate out of the OI diffs that
    # lots.lot_from_chain reads.
    return (frame.drop_duplicates(subset=["contract_id", "ts"], keep="last")
                 .sort_values("ts").reset_index(drop=True))


def split_by_day(frame: pd.DataFrame) -> Iterator[tuple[date, pd.DataFrame]]:
    """Group bars into IST trading dates, the store's partition unit."""
    if frame.empty:
        return
    days = pd.to_datetime(frame["ts"]).dt.tz_convert(store.IST).dt.date
    for day, part in frame.groupby(days, sort=True):
        yield day, part.reset_index(drop=True)


def contracts_to_refresh(
    contracts: Iterable[Contract], *, today: date
) -> list[Contract]:
    """Drop contracts already dead: their tokens are recycled, not queryable."""
    return [c for c in contracts if not c.is_expired(today)]
