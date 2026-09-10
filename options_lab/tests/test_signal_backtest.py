"""Backtest harness for the UT Bot + LinReg option strategy.

The correctness properties that have burned this project before, pinned here:
  - a signal on bar i is ACTED ON at bar i+1's open, never at bar i's close;
  - the contract chosen at entry is the contract held to exit;
  - costs are charged on every trade and cannot be switched off;
  - an exit always happens - no trade may run past the session close.
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from options_lab.strategy import signal_backtest as sb

IST = "Asia/Kolkata"


def _bars(closes, start="2026-09-04 09:15"):
    idx = pd.date_range(start, periods=len(closes), freq="1min", tz=IST)
    c = np.asarray(closes, dtype="float64")
    return pd.DataFrame({"Open": c, "High": c + 1, "Low": c - 1, "Close": c}, index=idx)


def _chain(index, strikes=(24000.0, 24100.0)):
    """A flat, fully-liquid two-strike chain covering the same minutes."""
    rows = []
    for k in strikes:
        for right, base in (("CE", 50.0), ("PE", 40.0)):
            for ts in index:
                rows.append({
                    "contract_id": f"NIFTY|2026-09-08|{k:g}|{right}",
                    "expiry": date(2026, 9, 8), "strike": k, "right": right,
                    "ts": ts, "open": base, "high": base, "low": base,
                    "close": base, "volume": 10_000, "open_interest": 100_000,
                })
    return pd.DataFrame(rows)


def test_a_signal_is_acted_on_at_the_next_bars_open_not_the_signal_bars_close():
    bars = _bars([24000, 24010, 24020, 24030])
    chain = _chain(bars.index)
    buys = np.array([False, True, False, False])
    sells = np.zeros(4, dtype=bool)

    trades = sb.run(bars, chain, buys, sells, lot_size=65)

    assert len(trades) == 1
    assert trades.iloc[0]["entry_ts"] == bars.index[2], "bar i signal -> bar i+1 entry"


def test_a_signal_on_the_final_bar_is_dropped_because_it_cannot_be_filled():
    bars = _bars([24000, 24010, 24020])
    chain = _chain(bars.index)
    buys = np.array([False, False, True])

    trades = sb.run(bars, chain, buys, np.zeros(3, dtype=bool), lot_size=65)

    assert trades.empty


def test_a_buy_signal_buys_a_call_and_a_sell_signal_buys_a_put():
    bars = _bars([24000, 24010, 24020, 24030, 24040])
    chain = _chain(bars.index)
    buys = np.array([True, False, False, False, False])
    sells = np.array([False, False, True, False, False])

    trades = sb.run(bars, chain, buys, sells, lot_size=65)

    assert list(trades["right"]) == ["CE", "PE"]


def test_the_contract_entered_is_the_contract_exited():
    bars = _bars([24000, 24010, 24020, 24030])
    chain = _chain(bars.index)
    buys = np.array([True, False, False, False])

    trades = sb.run(bars, chain, buys, np.zeros(4, dtype=bool), lot_size=65)

    assert trades.iloc[0]["contract_id"] == trades.iloc[0]["exit_contract_id"]


def test_an_opposite_signal_closes_the_open_position():
    bars = _bars([24000, 24010, 24020, 24030, 24040, 24050])
    chain = _chain(bars.index)
    buys = np.array([True, False, False, False, False, False])
    sells = np.array([False, False, True, False, False, False])

    trades = sb.run(bars, chain, buys, sells, lot_size=65)

    assert trades.iloc[0]["exit_reason"] == "opposite_signal"
    assert trades.iloc[0]["exit_ts"] == bars.index[3]


def test_every_trade_closes_by_the_session_end():
    bars = _bars([24000, 24010, 24020, 24030])
    chain = _chain(bars.index)
    buys = np.array([True, False, False, False])

    trades = sb.run(bars, chain, buys, np.zeros(4, dtype=bool), lot_size=65)

    assert trades.iloc[0]["exit_reason"] == "session_close"
    assert trades.iloc[0]["exit_ts"] == bars.index[-1]


def test_costs_are_charged_so_a_flat_option_price_loses_money():
    """The chain here never moves. A zero-cost harness would report zero P&L."""
    bars = _bars([24000, 24010, 24020, 24030])
    chain = _chain(bars.index)
    buys = np.array([True, False, False, False])

    trades = sb.run(bars, chain, buys, np.zeros(4, dtype=bool), lot_size=65)

    assert trades.iloc[0]["gross_pnl"] == pytest.approx(0.0, abs=1e-9)
    assert trades.iloc[0]["net_pnl"] < 0
    assert trades.iloc[0]["cost"] > 0


def test_the_atm_strike_is_the_one_nearest_the_underlying_at_entry():
    bars = _bars([24000, 24090, 24095, 24099])
    chain = _chain(bars.index)
    buys = np.array([False, True, False, False])

    trades = sb.run(bars, chain, buys, np.zeros(4, dtype=bool), lot_size=65)

    assert trades.iloc[0]["strike"] == 24100.0


def test_no_position_is_opened_while_one_is_already_open():
    bars = _bars([24000, 24010, 24020, 24030, 24040])
    chain = _chain(bars.index)
    buys = np.array([True, True, True, False, False])

    trades = sb.run(bars, chain, buys, np.zeros(5, dtype=bool), lot_size=65)

    assert len(trades) == 1


def test_a_missing_contract_at_entry_skips_the_trade_rather_than_inventing_a_price():
    bars = _bars([24000, 30000, 30010, 30020])       # far outside the chain
    chain = _chain(bars.index)
    buys = np.array([False, True, False, False])

    trades = sb.run(bars, chain, buys, np.zeros(4, dtype=bool),
                    lot_size=65, max_strike_distance_pct=0.01)

    assert trades.empty
