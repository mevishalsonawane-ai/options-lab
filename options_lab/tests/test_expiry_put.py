"""Expiry-day short put: the one structure that measured positive.

Three rules here were each discovered by being got wrong first, so each is
pinned by a test:

  FORWARD FROM PARITY, NOT FUTURES. The near-month future carries a median
  +48.5 point basis (max +228) over spot. That is most of a 0.75% strike
  offset, so pricing the strike off the future mis-sets it every single day.
  F = median over the N nearest strikes of (K + CE - PE).

  NEAREST STRIKE, NOT FLOORED. "The nearest listed strike at or below
  F x 0.9925" and "the nearest listed strike to F x 0.9925" are different
  rules. Flooring lands below target on ~100% of days instead of ~46%, and
  costs Rs 81/trade - about a fifth of the strategy's whole return.

  ONE ORDER IN, NONE OUT. The position is held to cash settlement and never
  bought back. A second order plus a second crossed spread is the entire
  margin: squaring off at 15:29 drops the 1.00% variant from 100% to 95.9%.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab.strategy import expiry_put as ep


def _chain(spot=24000.0, step=50.0, n=12, basis=0.0):
    """A synthetic chain whose parity forward is exactly `spot + basis`."""
    fwd = spot + basis
    rows = []
    for i in range(-n, n + 1):
        k = spot + i * step
        # Intrinsic-plus-flat-time-value, so K + CE - PE == fwd exactly.
        tv = 20.0
        ce = max(fwd - k, 0.0) + tv
        pe = max(k - fwd, 0.0) + tv
        rows.append({"strike": k, "right": "CE", "close": ce, "volume": 1000})
        rows.append({"strike": k, "right": "PE", "close": pe, "volume": 1000})
    return pd.DataFrame(rows)


def test_parity_forward_recovers_the_true_forward():
    got = ep.parity_forward(_chain(spot=24000.0, basis=0.0))

    assert got == pytest.approx(24000.0, abs=1e-6)


def test_parity_forward_is_unaffected_by_a_futures_basis():
    """The whole point: parity reads the options, not the future."""
    got = ep.parity_forward(_chain(spot=24000.0, basis=0.0))
    futures_price = 24048.5          # the measured median basis

    assert abs(got - futures_price) > 40, "parity must not track the future"
    assert got == pytest.approx(24000.0, abs=1e-6)


def test_parity_forward_uses_only_the_nearest_n_strikes():
    chain = _chain()
    wide = chain.copy()
    # Corrupt a far strike; it must not move the answer.
    wide.loc[wide["strike"] == 24000.0 + 12 * 50, "close"] = 99999.0

    assert ep.parity_forward(wide, n_strikes=10) == pytest.approx(
        ep.parity_forward(chain, n_strikes=10), abs=1e-6)


def test_parity_forward_skips_strikes_with_a_missing_side():
    chain = _chain()
    chain = chain[~((chain["strike"] == 24000.0) & (chain["right"] == "PE"))]

    got = ep.parity_forward(chain)

    assert got == pytest.approx(24000.0, abs=1e-6)


def test_parity_forward_refuses_a_chain_with_too_few_usable_strikes():
    thin = _chain(n=1)

    with pytest.raises(ep.ThinChain):
        ep.parity_forward(thin, n_strikes=10)


def test_strike_is_the_nearest_listed_not_the_one_below():
    """target = 24000 x 0.9925 = 23820. On a 50-grid the neighbours are
    23800 (below, distance 20) and 23850 (above, distance 30)."""
    strikes = [23750.0, 23800.0, 23850.0, 23900.0]

    assert ep.select_strike(strikes, forward=24000.0, otm_pct=0.0075) == 23800.0


def test_the_nearest_strike_may_land_above_the_target():
    """target = 23820 with a 100-grid: 23800 is 20 away, 23900 is 80 away.
    With target 23860 the nearest is 23900 - ABOVE it. Flooring would take
    23800 and collect less premium."""
    strikes = [23700.0, 23800.0, 23900.0, 24000.0]

    assert ep.select_strike(strikes, forward=24000.0, otm_pct=0.0075) == 23800.0
    assert ep.select_strike(strikes, forward=24100.0, otm_pct=0.0075) == 23900.0


def test_selecting_a_strike_from_an_empty_ladder_is_refused():
    with pytest.raises(ValueError):
        ep.select_strike([], forward=24000.0, otm_pct=0.0075)


def test_settlement_is_the_last_thirty_minute_average_not_the_close():
    idx = pd.date_range("2026-09-08 15:00", "2026-09-08 15:29", freq="1min",
                        tz="Asia/Kolkata")
    spot = pd.Series(range(len(idx)), index=idx, dtype="float64") + 23800.0

    got = ep.settlement_price(spot, date(2026, 9, 8))

    assert got == pytest.approx(spot.mean())
    assert got != spot.iloc[-1], "the 15:29 print is not the settlement"


def test_settlement_ignores_bars_before_the_settlement_window():
    idx = pd.date_range("2026-09-08 09:15", "2026-09-08 15:29", freq="1min",
                        tz="Asia/Kolkata")
    spot = pd.Series(23000.0, index=idx)
    spot.loc[spot.index >= pd.Timestamp("2026-09-08 15:00", tz="Asia/Kolkata")] = 24000.0

    assert ep.settlement_price(spot, date(2026, 9, 8)) == pytest.approx(24000.0)


def test_an_option_expiring_worthless_pays_the_full_credit_less_costs():
    t = ep.settle_trade(strike=23800.0, credit=4.90, settlement=24000.0,
                        lot_size=65, lots=1, regime="quoted")

    assert t["intrinsic"] == 0.0
    assert t["gross_pnl"] == pytest.approx(4.90 * 65)
    assert t["cost"] > 0
    assert t["net_pnl"] < t["gross_pnl"]
    assert t["won"]


def test_an_in_the_money_put_pays_credit_minus_intrinsic():
    t = ep.settle_trade(strike=23800.0, credit=4.90, settlement=23700.0,
                        lot_size=65, lots=1, regime="quoted")

    assert t["intrinsic"] == pytest.approx(100.0)
    assert t["gross_pnl"] == pytest.approx((4.90 - 100.0) * 65)
    assert not t["won"]


def test_the_loss_is_unbounded_below_and_scales_one_for_one_with_the_move():
    mild = ep.settle_trade(strike=23800.0, credit=4.90, settlement=23700.0,
                           lot_size=65, lots=1, regime="quoted")
    severe = ep.settle_trade(strike=23800.0, credit=4.90, settlement=22600.0,
                             lot_size=65, lots=1, regime="quoted")

    extra_move = (23700.0 - 22600.0) * 65
    assert severe["gross_pnl"] == pytest.approx(mild["gross_pnl"] - extra_move)


def test_only_one_order_is_charged_because_the_position_is_never_bought_back():
    """Held to settlement means one brokerage leg, not two."""
    from options_lab import costs
    t = ep.settle_trade(strike=23800.0, credit=100.0, settlement=24000.0,
                        lot_size=65, lots=1, regime="quoted")
    round_trip = costs.round_trip(premium=100.0, lot_size=65, lots=1,
                                  regime="quoted")

    assert t["cost"] < round_trip.total, "a held-to-settle trade pays less"
