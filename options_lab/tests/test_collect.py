"""Chain harvest orchestration: contract -> store rows, with the network injected."""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab.harvest import collect, upstox
from options_lab.harvest.instruments import Contract

CE = Contract(
    underlying="NIFTY", expiry=date(2026, 9, 8), strike=24050.0, right="CE",
    lot_size=65, instrument_key="NSE_FO|50979",
    trading_symbol="NIFTY 24050 CE 08 SEP 26",
)


def _payload(day: str, *minutes: str):
    return {
        "status": "success",
        "data": {"candles": [
            [f"{day}T{m}:00+05:30", 58.0, 59.0, 57.0, 58.5, 900000, 7880000]
            for m in reversed(minutes)
        ]},
    }


def test_to_frame_carries_contract_identity_onto_every_bar():
    bars = upstox.parse_candles(_payload("2026-09-04", "09:15", "09:16"))

    frame = collect.to_frame(CE, bars)

    assert frame["contract_id"].tolist() == ["NIFTY|2026-09-08|24050|CE"] * 2
    assert frame["lot_size"].tolist() == [65, 65]
    assert frame["right"].tolist() == ["CE", "CE"]


def test_to_frame_of_no_bars_is_empty_but_well_shaped():
    frame = collect.to_frame(CE, [])

    assert frame.empty
    assert "open_interest" in frame.columns


def test_harvest_contract_requests_one_window_per_month_chunk():
    seen = []

    def fake_fetch(url):
        seen.append(url)
        return _payload("2026-09-04", "09:15")

    collect.harvest_contract(CE, date(2026, 6, 15), date(2026, 9, 4), fetch=fake_fetch)

    assert len(seen) == len(upstox.month_chunks(date(2026, 6, 15), date(2026, 9, 4)))
    assert all("NSE_FO%7C50979" in u for u in seen)


def test_harvest_contract_concatenates_windows_into_one_ascending_frame():
    def fake_fetch(url):
        return _payload("2026-09-04", "09:16") if "09-04" in url else _payload(
            "2026-08-04", "09:15")

    frame = collect.harvest_contract(CE, date(2026, 8, 4), date(2026, 9, 4),
                                     fetch=fake_fetch)

    assert frame["ts"].is_monotonic_increasing
    assert len(frame) == 2


def test_harvest_contract_propagates_an_api_error_instead_of_returning_empty():
    def dead(url):
        return {"status": "error",
                "errors": [{"errorCode": "UDAPI100011", "message": "Invalid Instrument key"}]}

    with pytest.raises(upstox.UpstoxError):
        collect.harvest_contract(CE, date(2026, 9, 4), date(2026, 9, 4), fetch=dead)


def test_split_by_day_groups_bars_into_ist_trading_dates():
    frames = collect.to_frame(
        CE, upstox.parse_candles(_payload("2026-09-03", "09:15"))
             + upstox.parse_candles(_payload("2026-09-04", "09:15")))

    by_day = dict(collect.split_by_day(frames))

    assert sorted(by_day) == [date(2026, 9, 3), date(2026, 9, 4)]
    assert all(len(f) == 1 for f in by_day.values())


def test_contracts_to_refresh_skips_contracts_that_expired_before_collection_started():
    """A contract dead before we ever ran cannot be fetched - Upstox recycles its token."""
    dead = Contract(underlying="NIFTY", expiry=date(2026, 8, 25), strike=24000.0,
                    right="CE", lot_size=65, instrument_key="NSE_FO|1",
                    trading_symbol="x")

    got = collect.contracts_to_refresh([CE, dead], today=date(2026, 9, 7))

    assert got == [CE]
