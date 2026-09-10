"""Upstox v3 historical-candle client.

Three things this must get right, all of which the previous F&O harness got wrong:
  - candles arrive NEWEST-FIRST and must be stored ascending;
  - a session runs 09:15..15:39 (385 bars), not 375;
  - the 1-minute endpoint refuses ranges longer than one calendar month.
"""
from __future__ import annotations

from datetime import date

import pytest

from options_lab.harvest import upstox


# Real response shape, newest-first, [ts, O, H, L, C, volume, open_interest].
PAYLOAD = {
    "status": "success",
    "data": {
        "candles": [
            ["2026-09-04T09:17:00+05:30", 60.0, 66.0, 44.05, 60.65, 2566200, 7896005],
            ["2026-09-04T09:16:00+05:30", 59.0, 61.0, 58.00, 60.00, 1000000, 7890000],
            ["2026-09-04T09:15:00+05:30", 58.0, 59.0, 57.00, 58.50, 900000, 7880000],
        ]
    },
}


def test_candle_url_percent_encodes_the_pipe_in_the_instrument_key():
    url = upstox.candle_url("NSE_FO|50979", to=date(2026, 9, 4), frm=date(2026, 9, 4))

    assert "NSE_FO%7C50979" in url
    assert url.endswith("/minutes/1/2026-09-04/2026-09-04")


def test_candle_url_puts_to_date_before_from_date():
    """Upstox's path order is /{to}/{from} - reversing it silently returns nothing."""
    url = upstox.candle_url("NSE_INDEX|Nifty 50", to=date(2026, 9, 4), frm=date(2026, 9, 1))

    assert url.endswith("/2026-09-04/2026-09-01")


def test_parse_candles_returns_bars_in_ascending_time_order():
    bars = upstox.parse_candles(PAYLOAD)

    assert [b.ts.strftime("%H:%M") for b in bars] == ["09:15", "09:16", "09:17"]


def test_parse_candles_maps_the_seventh_field_to_open_interest():
    bars = upstox.parse_candles(PAYLOAD)

    assert bars[0].open_interest == 7880000
    assert bars[-1].open_interest == 7896005
    assert bars[-1].volume == 2566200
    assert (bars[-1].open, bars[-1].high, bars[-1].low, bars[-1].close) == (
        60.0, 66.0, 44.05, 60.65,
    )


def test_parse_candles_returns_empty_for_a_non_trading_day():
    assert upstox.parse_candles({"status": "success", "data": {"candles": []}}) == []


def test_parse_candles_raises_on_an_api_error_rather_than_returning_empty():
    """UDAPI100011 means the token is dead or recycled. Never treat it as 'no data'."""
    payload = {
        "status": "error",
        "errors": [{"errorCode": "UDAPI100011", "message": "Invalid Instrument key"}],
    }

    with pytest.raises(upstox.UpstoxError) as exc:
        upstox.parse_candles(payload)

    assert "UDAPI100011" in str(exc.value)


def test_month_chunks_returns_a_single_window_for_a_short_range():
    assert upstox.month_chunks(date(2026, 9, 1), date(2026, 9, 4)) == [
        (date(2026, 9, 1), date(2026, 9, 4)),
    ]


def test_month_chunks_splits_a_long_range_into_windows_of_at_most_one_month():
    chunks = upstox.month_chunks(date(2026, 6, 15), date(2026, 9, 4))

    assert chunks[0][0] == date(2026, 6, 15)
    assert chunks[-1][1] == date(2026, 9, 4)
    assert all((hi - lo).days <= 31 for lo, hi in chunks)


def test_month_chunks_are_contiguous_and_non_overlapping():
    chunks = upstox.month_chunks(date(2026, 6, 15), date(2026, 9, 4))

    for (_, prev_hi), (next_lo, _) in zip(chunks, chunks[1:]):
        assert (next_lo - prev_hi).days == 1


# --- today's bars ----------------------------------------------------------
"""The dated endpoint cannot see the current session.

Probed 2026-09-10 12:54 IST against a liquid ATM contract:

    /minutes/1/2026-09-10/2026-09-10   HTTP 200, 0 candles
    /intraday/.../minutes/1            HTTP 200, 220 candles, latest 12:54

That is not a detail. The harvester exists because a contract is delisted the
moment it settles, so an expiry chain must be captured ON its expiry day - and
the endpoint it used is precisely the one that cannot do that. Every expiry
session it has ever run against was already gone.
"""


def test_the_intraday_url_carries_no_date_range():
    """The dated form returns nothing for today; this one has no dates at all."""
    url = upstox.intraday_url("NSE_FO|44758")

    assert "/intraday/" in url
    assert "2026" not in url


def test_the_intraday_url_escapes_the_instrument_key():
    """Instrument keys carry a pipe, which is not URL-safe."""
    url = upstox.intraday_url("NSE_FO|44758")

    assert "|" not in url
    assert "%7C" in url


def test_intraday_and_dated_urls_are_different_endpoints():
    assert upstox.intraday_url("NSE_FO|1") != upstox.candle_url(
        "NSE_FO|1", to=date(2026, 9, 10), frm=date(2026, 9, 10))


def test_intraday_candles_parse_exactly_like_dated_ones():
    """Same payload shape, so parse_candles is reused rather than duplicated."""
    payload = {"status": "success", "data": {"candles": [
        ["2026-09-10T12:54:00+05:30", 1.0, 2.0, 0.5, 1.5, 100, 6500],
        ["2026-09-10T12:53:00+05:30", 1.0, 2.0, 0.5, 1.4, 90, 6435],
    ]}}

    bars = upstox.parse_candles(payload)

    assert len(bars) == 2
    assert bars[0].ts < bars[1].ts, "ascending, as everywhere else"
    assert bars[-1].close == 1.5


def test_an_intraday_error_payload_is_still_fatal():
    """An auth withdrawal must never read as 'the session was quiet'."""
    with pytest.raises(upstox.UpstoxError):
        upstox.parse_candles({"status": "error", "errors": [
            {"errorCode": "UDAPI100050", "message": "Invalid credentials"}]})
