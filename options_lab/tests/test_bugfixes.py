"""Regression tests for three defects found by edge-case probing.

None of these were caught by the existing 186 tests, because every test fed
the modules clean, well-formed input. All three fail SILENTLY or CRYPTICALLY
on input that real data produces routinely.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from options_lab import ic
from options_lab.indicators import utbot
from options_lab.strategy import expiry_put as ep


# --- 1. parity_forward on a multi-timestamp chain --------------------------
def _snapshot(spot=24000.0, n=6):
    rows = []
    for i in range(-n, n + 1):
        k = spot + i * 50
        rows.append({"strike": k, "right": "CE", "close": max(spot - k, 0) + 20})
        rows.append({"strike": k, "right": "PE", "close": max(k - spot, 0) + 20})
    return pd.DataFrame(rows)


def test_parity_forward_refuses_a_chain_with_repeated_strikes():
    """The raw chain has one row per contract PER MINUTE. Passing it whole
    used to raise 'The truth value of a Series is ambiguous' from deep inside
    pandas, which tells the caller nothing about what they did wrong."""
    snap = _snapshot()
    two_minutes = pd.concat([snap, snap], ignore_index=True)

    with pytest.raises(ep.NotASnapshot) as exc:
        ep.parity_forward(two_minutes)

    assert "snapshot" in str(exc.value).lower()


def test_parity_forward_still_works_on_a_proper_snapshot():
    assert ep.parity_forward(_snapshot()) == pytest.approx(24000.0, abs=1e-6)


def test_the_refusal_names_a_duplicated_strike_so_it_can_be_debugged():
    snap = _snapshot()
    dup = pd.concat([snap, snap[snap["strike"] == 24000.0]], ignore_index=True)

    with pytest.raises(ep.NotASnapshot) as exc:
        ep.parity_forward(dup)

    assert "24000" in str(exc.value)


# --- 2. utbot silently accepting NaN ---------------------------------------
def test_utbot_refuses_a_series_containing_nan():
    """A single NaN produced two NaN stop bars and reset the trailing-stop
    state machine to an arbitrary side, silently dropping a signal."""
    close = 100 + np.cumsum(np.random.default_rng(0).normal(size=60))
    close[30] = np.nan

    with pytest.raises(utbot.DirtyInput):
        utbot.signals(close + 0.5, close - 0.5, close, key_value=2.0, atr_period=1)


def test_utbot_names_where_the_nan_is():
    close = np.arange(60.0)
    close[42] = np.nan

    with pytest.raises(utbot.DirtyInput) as exc:
        utbot.trailing_stop(close + 0.5, close - 0.5, close, key_value=2.0,
                            atr_period=1)

    assert "42" in str(exc.value)


def test_utbot_still_works_on_clean_input():
    close = 100 + np.cumsum(np.random.default_rng(1).normal(size=200))
    buys, sells = utbot.signals(close + 0.5, close - 0.5, close,
                                key_value=2.0, atr_period=1)

    assert buys.sum() + sells.sum() > 0
    assert not (buys & sells).any()


# --- 3. panel_ic overstating how many sessions it measured -----------------
def _sessions(n, per):
    return pd.Series(np.repeat(np.arange(n), per))


def test_panel_ic_reports_how_many_sessions_actually_produced_an_ic():
    """A session with no rank variance yields no IC and is dropped. Reporting
    it in n_sessions overstates the evidence behind daily_ic_mean and its CI."""
    rng = np.random.default_rng(2)
    fwd = pd.Series(rng.normal(size=300))
    feat = fwd.copy()
    feat[100:200] = 7.0                       # session 1 is constant

    row = ic.panel_ic(feature=feat, forward=fwd, sessions=_sessions(3, 100))

    assert row.n_sessions == 3
    assert row.n_sessions_measured == 2


def test_measured_equals_total_when_every_session_is_usable():
    rng = np.random.default_rng(3)
    f = pd.Series(rng.normal(size=300))

    row = ic.panel_ic(feature=f, forward=pd.Series(rng.normal(size=300)),
                      sessions=_sessions(3, 100))

    assert row.n_sessions_measured == row.n_sessions == 3


def test_the_shortfall_is_visible_in_the_formatted_row():
    rng = np.random.default_rng(4)
    fwd = pd.Series(rng.normal(size=300))
    feat = fwd.copy()
    feat[100:200] = 7.0

    row = ic.panel_ic(feature=feat, forward=fwd, sessions=_sessions(3, 100),
                      controls=pd.DataFrame({"c": rng.normal(size=300)}))

    assert "2/3" in row.format(), "a reader must see the shortfall, not just n=3"
