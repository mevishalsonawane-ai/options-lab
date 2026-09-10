"""Contract identity and instrument-master parsing.

The load-bearing property here is that a contract is identified by
(underlying, expiry, strike, right) and NEVER by the broker's instrument_key.
Upstox recycles tokens after a contract settles, so a stored key can silently
resolve to a different underlying's contract later.
"""
from __future__ import annotations

from datetime import date

import pytest

from options_lab.harvest import instruments


# One realistic row per shape, matching the live master fetched 2026-09-07.
NIFTY_CE = {
    "instrument_key": "NSE_FO|50979",
    "trading_symbol": "NIFTY 24050 CE 08 SEP 26",
    "segment": "NSE_FO",
    "instrument_type": "CE",
    "underlying_symbol": "NIFTY",
    "expiry": 1788892199000,  # 2026-09-08 15:29:59 IST
    "strike_price": 24050.0,
    "lot_size": 65,
}
NIFTY_PE = {**NIFTY_CE, "instrument_key": "NSE_FO|50978",
            "trading_symbol": "NIFTY 24050 PE 08 SEP 26", "instrument_type": "PE"}
BANKNIFTY_CE = {
    "instrument_key": "NSE_FO|70108",
    "trading_symbol": "BANKNIFTY 57500 CE 29 SEP 26",
    "segment": "NSE_FO",
    "instrument_type": "CE",
    "underlying_symbol": "BANKNIFTY",
    "expiry": 1790706599000,  # 2026-09-29
    "strike_price": 57500.0,
    "lot_size": 30,
}
NIFTY_FUT = {
    "instrument_key": "NSE_FO|35001",
    "trading_symbol": "NIFTY 29 SEP 26 FUT",
    "segment": "NSE_FO",
    "instrument_type": "FUT",
    "underlying_symbol": "NIFTY",
    "expiry": 1790706599000,
    "strike_price": 0.0,
    "lot_size": 65,
}
EQUITY = {
    "instrument_key": "NSE_EQ|INE002A01018",
    "trading_symbol": "RELIANCE",
    "segment": "NSE_EQ",
    "instrument_type": "EQ",
    "expiry": None,
    "strike_price": 0.0,
    "lot_size": 1,
}

MASTER = [NIFTY_CE, NIFTY_PE, BANKNIFTY_CE, NIFTY_FUT, EQUITY]


def test_expiry_epoch_ms_converts_to_ist_calendar_date():
    assert instruments.expiry_date(1788892199000) == date(2026, 9, 8)


def test_parse_options_keeps_only_ce_and_pe_for_the_requested_underlying():
    got = instruments.parse_options(MASTER, "NIFTY")

    assert [c.instrument_key for c in got] == ["NSE_FO|50979", "NSE_FO|50978"]


def test_parse_options_excludes_futures_of_the_same_underlying():
    got = instruments.parse_options(MASTER, "NIFTY")

    assert all(c.right in ("CE", "PE") for c in got)


def test_parsed_contract_carries_identity_fields():
    (ce, _pe) = instruments.parse_options(MASTER, "NIFTY")

    assert (ce.underlying, ce.expiry, ce.strike, ce.right) == (
        "NIFTY", date(2026, 9, 8), 24050.0, "CE",
    )
    assert ce.lot_size == 65


def test_contract_id_is_independent_of_the_recycled_instrument_key():
    """Two masters where the broker reassigned the token to the same contract."""
    (ce,) = instruments.parse_options([NIFTY_CE], "NIFTY")
    relisted = {**NIFTY_CE, "instrument_key": "NSE_FO|99999"}
    (ce_later,) = instruments.parse_options([relisted], "NIFTY")

    assert ce.contract_id == ce_later.contract_id


def test_contract_id_differs_when_the_token_is_reused_by_another_contract():
    """The dangerous case: same key, different real contract."""
    (nifty,) = instruments.parse_options([NIFTY_CE], "NIFTY")
    impostor = {**BANKNIFTY_CE, "instrument_key": NIFTY_CE["instrument_key"]}
    (banknifty,) = instruments.parse_options([impostor], "BANKNIFTY")

    assert nifty.instrument_key == banknifty.instrument_key
    assert nifty.contract_id != banknifty.contract_id


def test_contract_is_expired_the_day_after_its_expiry_date():
    (ce,) = instruments.parse_options([NIFTY_CE], "NIFTY")

    assert not ce.is_expired(date(2026, 9, 7))
    assert not ce.is_expired(date(2026, 9, 8)), "expiry day is still tradeable"
    assert ce.is_expired(date(2026, 9, 9))


def test_parse_options_rejects_an_unknown_underlying_loudly():
    with pytest.raises(instruments.NoSuchUnderlying):
        instruments.parse_options(MASTER, "SENSEX")
