"""The bounded-loss version of the expiry put.

The naked strategy's own docstring says the loss is unbounded and to size
accordingly, and sizing.py exists entirely to answer "how much capital survives
a -6% day". But there was no way to actually RUN the bounded variant, so the
trade-off could not be measured - only asserted.

Buying a further-OTM put caps the loss at (short strike - wing strike) minus
the net credit. It also costs: a second Rs 20 order, a second half-spread,
stamp duty the writer never pays, and - on exactly the sessions the wing does
its job - exercise STT at 0.125% of the wing's intrinsic.

Whether that is worth paying is an empirical question. This makes it askable.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab.strategy import expiry_put as ep

IST = "Asia/Kolkata"


def _chain(day, spot=24_000.0, settle=None, step=50.0, n=20):
    settle = spot if settle is None else settle
    stamps = [(pd.Timestamp(f"{day} 11:00", tz=IST), spot)]
    stamps += [(pd.Timestamp(f"{day} 15:{m:02d}", tz=IST), settle)
               for m in range(0, 30, 5)]
    rows = []
    for ts, fwd in stamps:
        for i in range(-n, n + 1):
            k = spot + i * step
            for right in ("CE", "PE"):
                intr = max(fwd - k, 0.0) if right == "CE" else max(k - fwd, 0.0)
                rows.append({"ts": ts, "strike": k, "right": right,
                             "close": intr + 20.0, "volume": 1000})
    return pd.DataFrame(rows)


# --- the wing --------------------------------------------------------------
def test_a_hedged_trade_records_both_legs():
    day = date(2024, 1, 4)

    trades, _ = ep.run_backtest([(day, _chain(day))], lot_size=65,
                                otm_pct=0.0075, wing_pct=0.0075)

    t = trades.iloc[0]
    assert t["wing_strike"] < t["strike"], "the wing sits BELOW the short put"
    assert t["wing_debit"] > 0


def test_the_credit_is_net_of_the_wing():
    day = date(2024, 1, 4)
    naked, _ = ep.run_backtest([(day, _chain(day))], lot_size=65, otm_pct=0.0075)
    hedged, _ = ep.run_backtest([(day, _chain(day))], lot_size=65,
                                otm_pct=0.0075, wing_pct=0.0075)

    assert hedged.iloc[0]["credit"] < naked.iloc[0]["credit"]


def test_the_loss_is_bounded_by_the_width_of_the_spread():
    """The whole point. A crash that costs the naked put many multiples of its
    credit costs the hedged one the width, once."""
    day = date(2024, 1, 4)
    crash = _chain(day, settle=18_000.0)          # a 25% fall

    naked, _ = ep.run_backtest([(day, crash)], lot_size=65, otm_pct=0.0075)
    hedged, _ = ep.run_backtest([(day, crash)], lot_size=65,
                                otm_pct=0.0075, wing_pct=0.0075)

    h = hedged.iloc[0]
    width = h["strike"] - h["wing_strike"]

    # A 25% fall costs the naked put many times its credit; the hedged one
    # loses the width once, plus the costs of both legs. Those costs are part
    # of the bound - the width alone is NOT the floor.
    assert h["net_pnl"] > 0.05 * naked.iloc[0]["net_pnl"]
    assert h["net_pnl"] >= -h["max_loss"]
    assert h["max_loss"] > width * h["qty"], "costs sit on top of the width"


def test_a_quiet_expiry_keeps_less_than_the_naked_trade():
    """The hedge is not free. On the 97% of sessions where nothing happens it
    is pure cost, which is exactly the trade-off worth measuring."""
    day = date(2024, 1, 4)
    naked, _ = ep.run_backtest([(day, _chain(day))], lot_size=65, otm_pct=0.0075)
    hedged, _ = ep.run_backtest([(day, _chain(day))], lot_size=65,
                                otm_pct=0.0075, wing_pct=0.0075)

    assert hedged.iloc[0]["net_pnl"] < naked.iloc[0]["net_pnl"]


def test_the_hedged_trade_pays_more_cost_than_the_naked_one():
    """Second order, second half-spread, stamp duty, and exercise STT when the
    wing lands in the money."""
    day = date(2024, 1, 4)
    naked, _ = ep.run_backtest([(day, _chain(day))], lot_size=65, otm_pct=0.0075)
    hedged, _ = ep.run_backtest([(day, _chain(day))], lot_size=65,
                                otm_pct=0.0075, wing_pct=0.0075)

    assert hedged.iloc[0]["cost"] > naked.iloc[0]["cost"]


def test_an_in_the_money_wing_pays_exercise_stt(): 
    """On the sessions the wing does its job it also pays the buyer's 0.125%
    exercise charge - a cost that only appears when it matters."""
    day = date(2024, 1, 4)
    quiet, _ = ep.run_backtest([(day, _chain(day))], lot_size=65,
                               otm_pct=0.0075, wing_pct=0.0075)
    crash, _ = ep.run_backtest([(day, _chain(day, settle=18_000.0))],
                               lot_size=65, otm_pct=0.0075, wing_pct=0.0075)

    assert crash.iloc[0]["cost"] > quiet.iloc[0]["cost"]


# --- refusals --------------------------------------------------------------
def test_no_wing_reproduces_the_naked_trade_exactly():
    """Backward compatibility is not optional: every published figure is naked."""
    day = date(2024, 1, 4)
    a, _ = ep.run_backtest([(day, _chain(day))], lot_size=65, otm_pct=0.0075)
    b, _ = ep.run_backtest([(day, _chain(day))], lot_size=65, otm_pct=0.0075,
                           wing_pct=None)

    assert a.iloc[0]["net_pnl"] == b.iloc[0]["net_pnl"]
    assert a.iloc[0]["cost"] == b.iloc[0]["cost"]


def test_a_wing_that_lands_on_the_short_strike_is_refused():
    """A zero-width spread is not a hedge; it is two orders and no protection."""
    day = date(2024, 1, 4)

    _, skipped = ep.run_backtest([(day, _chain(day))], lot_size=65,
                                 otm_pct=0.0075, wing_pct=0.0)

    assert len(skipped) == 1


def test_a_negative_wing_is_refused():
    day = date(2024, 1, 4)

    with pytest.raises(ValueError):
        ep.run_session(day, _chain(day), lot_size=65, wing_pct=-0.01)


def test_a_wing_beyond_the_listed_strikes_is_skipped_with_its_reason():
    day = date(2024, 1, 4)
    narrow = _chain(day, n=3)      # strikes only +/- 150 points

    _, skipped = ep.run_backtest([(day, narrow)], lot_size=65,
                                 otm_pct=0.0075, wing_pct=0.20)

    assert len(skipped) == 1
    assert skipped[0][1]
