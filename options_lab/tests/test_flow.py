"""Signed option order flow.

The only leading option-derived feature the research found: tick-rule
sign(delta price) x volume, calls positive, puts negative, summed across the
liquid chain. IC +0.109 at 1 min after controlling for intraday mean reversion
and the index's own contemporaneous return.

It is built from the WHOLE liquid chain, causally. It must never be built from
the single contract a rule is about to trade - that is how look-ahead enters an
options backtest.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from options_lab.features import flow


def _chain(rows):
    """rows: (contract_id, right, minute, close, volume)"""
    return pd.DataFrame(
        [{
            "contract_id": cid, "right": right,
            "ts": pd.Timestamp(f"2026-09-04 {m}", tz="Asia/Kolkata"),
            "close": close, "volume": vol,
        } for cid, right, m, close, vol in rows]
    )


def test_a_rising_call_on_volume_is_positive_flow():
    chain = _chain([("C1", "CE", "09:15", 100.0, 0),
                    ("C1", "CE", "09:16", 101.0, 500)])

    got = flow.signed_flow(chain)

    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == 500


def test_a_rising_put_on_volume_is_negative_flow():
    """Buying puts is bearish, so a put's contribution carries the opposite sign."""
    chain = _chain([("P1", "PE", "09:15", 100.0, 0),
                    ("P1", "PE", "09:16", 101.0, 500)])

    got = flow.signed_flow(chain)

    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == -500


def test_a_falling_call_is_negative_flow():
    chain = _chain([("C1", "CE", "09:15", 100.0, 0),
                    ("C1", "CE", "09:16", 99.0, 500)])

    got = flow.signed_flow(chain)

    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == -500


def test_an_unchanged_price_contributes_nothing_however_large_the_volume():
    """Stale prints are 37.7% of option rows; they must not create flow."""
    chain = _chain([("C1", "CE", "09:15", 100.0, 0),
                    ("C1", "CE", "09:16", 100.0, 999999)])

    got = flow.signed_flow(chain)

    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == 0


def test_contributions_are_summed_across_the_whole_chain():
    chain = _chain([("C1", "CE", "09:15", 100.0, 0), ("C1", "CE", "09:16", 101.0, 300),
                    ("C2", "CE", "09:15", 50.0, 0), ("C2", "CE", "09:16", 49.0, 100),
                    ("P1", "PE", "09:15", 80.0, 0), ("P1", "PE", "09:16", 81.0, 200)])

    got = flow.signed_flow(chain)

    # +300 (call up) - 100 (call down) - 200 (put up) = 0
    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == 0


def test_the_first_bar_of_a_contract_has_no_prior_price_so_contributes_nothing():
    chain = _chain([("C1", "CE", "09:15", 100.0, 700)])

    got = flow.signed_flow(chain)

    assert got.loc[pd.Timestamp("2026-09-04 09:15", tz="Asia/Kolkata")] == 0


def test_flow_is_causal_and_never_uses_a_future_bar():
    """Truncating the chain must not change any flow value already computed."""
    rows = [("C1", "CE", "09:15", 100.0, 0), ("C1", "CE", "09:16", 101.0, 300),
            ("C1", "CE", "09:17", 105.0, 900)]
    full = flow.signed_flow(_chain(rows))
    truncated = flow.signed_flow(_chain(rows[:2]))

    shared = truncated.index
    pd.testing.assert_series_equal(full.loc[shared], truncated, check_names=False)


def test_index_rows_are_excluded_from_the_chain_aggregate():
    chain = _chain([("IX", "IX", "09:15", 24000.0, 0),
                    ("IX", "IX", "09:16", 24010.0, 0),
                    ("C1", "CE", "09:15", 100.0, 0),
                    ("C1", "CE", "09:16", 101.0, 400)])

    got = flow.signed_flow(chain)

    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == 400


def test_volume_normalised_flow_divides_by_chain_volume():
    """Raw flow scales with activity; the normalised form is comparable across days."""
    chain = _chain([("C1", "CE", "09:15", 100.0, 0), ("C1", "CE", "09:16", 101.0, 300),
                    ("C2", "CE", "09:15", 50.0, 0), ("C2", "CE", "09:16", 49.0, 100)])

    got = flow.signed_flow(chain, normalise=True)

    assert got.loc[pd.Timestamp("2026-09-04 09:16", tz="Asia/Kolkata")] == pytest.approx(
        (300 - 100) / 400)


def test_an_empty_chain_yields_an_empty_series_not_an_error():
    got = flow.signed_flow(_chain([]))

    assert got.empty
