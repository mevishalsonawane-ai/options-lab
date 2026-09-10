"""Second round of edge-case regressions.

Every one of these accepted impossible input and returned a plausible answer.
That is the failure mode this project keeps paying for: not a crash, but a
number that looks fine and is wrong.
"""
from __future__ import annotations

import tempfile
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd
import pytest

from options_lab import units
from options_lab.features import gates
from options_lab.harvest import store, upstox
from options_lab.strategy import signal_backtest as sb

FETCHED = datetime(2026, 9, 7, 20, 0, tzinfo=timezone.utc)


# --- A. an expired contract must not bucket as expiry-day ------------------
def test_dte_bucket_refuses_a_negative_dte():
    """gates.dte() raises Expired for a past expiry, but dte_bucket used to
    return "0" - classifying a DEAD contract as tradeable on expiry day."""
    with pytest.raises(gates.Expired):
        gates.dte_bucket(-5)


def test_dte_bucket_still_accepts_expiry_day_itself():
    assert gates.dte_bucket(0) == "0"


# --- B. a reversed date range must not silently fetch nothing --------------
def test_month_chunks_refuses_a_reversed_range():
    """Returning [] made the caller fetch nothing and conclude no data exists."""
    with pytest.raises(ValueError):
        upstox.month_chunks(date(2026, 9, 4), date(2026, 9, 1))


def test_month_chunks_accepts_a_single_day():
    assert upstox.month_chunks(date(2026, 9, 4), date(2026, 9, 4)) == [
        (date(2026, 9, 4), date(2026, 9, 4))]


# --- C. the traded expiry must be chosen, not stumbled into ----------------
def _two_expiry_chain(index):
    rows = []
    for e in (date(2026, 9, 8), date(2026, 9, 15)):
        for right, px in (("CE", 50.0), ("PE", 40.0)):
            for ts in index:
                rows.append({"contract_id": f"NIFTY|{e:%Y-%m-%d}|24000|{right}",
                             "expiry": e, "strike": 24000.0, "right": right,
                             "ts": ts, "open": px, "high": px, "low": px,
                             "close": px, "volume": 100, "open_interest": 100})
    return pd.DataFrame(rows)


def test_signal_backtest_refuses_a_chain_carrying_several_expiries():
    """It used to take chain['expiry'].iloc[0] - trading whichever expiry
    happened to sort first, with no way for the caller to know which."""
    idx = pd.date_range("2026-09-04 09:15", periods=4, freq="1min",
                        tz="Asia/Kolkata")
    bars = pd.DataFrame({"Open": [24000.0] * 4, "High": [24001.0] * 4,
                         "Low": [23999.0] * 4, "Close": [24000.0] * 4}, index=idx)

    with pytest.raises(sb.AmbiguousChain):
        sb.run(bars, _two_expiry_chain(idx), np.array([True, False, False, False]),
               np.zeros(4, bool), lot_size=65)


def test_signal_backtest_works_once_the_chain_is_one_expiry():
    idx = pd.date_range("2026-09-04 09:15", periods=4, freq="1min",
                        tz="Asia/Kolkata")
    bars = pd.DataFrame({"Open": [24000.0] * 4, "High": [24001.0] * 4,
                         "Low": [23999.0] * 4, "Close": [24000.0] * 4}, index=idx)
    chain = _two_expiry_chain(idx)
    one = chain[chain["expiry"] == date(2026, 9, 8)]

    trades = sb.run(bars, one, np.array([True, False, False, False]),
                    np.zeros(4, bool), lot_size=65)

    assert len(trades) == 1
    assert trades.iloc[0]["contract_id"].startswith("NIFTY|2026-09-08|")


# --- D. an empty write must not create a phantom partition -----------------
def test_writing_an_empty_frame_does_not_mark_the_day_as_harvested():
    root = tempfile.mkdtemp()

    store.write_day(root, "NIFTY", date(2026, 9, 5), store.empty_frame(),
                    source="upstox", fetched_at=FETCHED)

    assert store.harvested_days(root, "NIFTY") == [], \
        "a day with no bars must not report as collected"


def test_a_real_write_still_marks_the_day_as_harvested():
    root = tempfile.mkdtemp()
    bars = pd.DataFrame({
        "contract_id": ["X"], "instrument_key": ["K"], "underlying": ["NIFTY"],
        "expiry": [date(2026, 9, 8)], "strike": [24000.0], "right": ["CE"],
        "lot_size": [65],
        "ts": pd.date_range("2026-09-04 09:15", periods=1, freq="1min",
                            tz="Asia/Kolkata"),
        "open": [1.0], "high": [1.0], "low": [1.0], "close": [1.0],
        "volume": [650], "open_interest": [6500]})

    store.write_day(root, "NIFTY", date(2026, 9, 4), bars,
                    source="upstox", fetched_at=FETCHED)

    assert store.harvested_days(root, "NIFTY") == [date(2026, 9, 4)]


# --- E. impossible values ---------------------------------------------------
def test_negative_quantities_are_refused():
    """Open interest and volume cannot be negative; a negative means the
    upstream data is corrupt, not that the position is short."""
    with pytest.raises(units.UnitsMismatch):
        units.to_contracts(pd.Series([-130]), lot_size=65, src_units="units")


def test_a_traded_fraction_above_one_is_refused():
    """You cannot trade in 150% of a session's minutes."""
    with pytest.raises(ValueError):
        gates.liquid(traded_fraction=1.5)


def test_a_nan_traded_fraction_is_refused_rather_than_read_as_illiquid():
    with pytest.raises(ValueError):
        gates.liquid(traded_fraction=float("nan"))
