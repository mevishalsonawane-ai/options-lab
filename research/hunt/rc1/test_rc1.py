"""RC1 tests: the Indian tax rule (no loss set-off), the TDS cash lock, the look-ahead guard, the funding sign,
liquidation, reading B's carry-forward, the holdout lock and the twins.

    python -m pytest research/hunt/rc1/test_rc1.py -q
"""
from __future__ import annotations

import os
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config as K  # noqa: E402
import data as D  # noqa: E402
import engine as E  # noqa: E402
import strategies as S  # noqa: E402

D0 = date(2024, 5, 1)


def market(closes, start=date(2024, 5, 1), opens=None, highs=None, lows=None, fund=None) -> E.Market:
    c = np.asarray(closes, dtype=float)
    o = np.asarray(opens if opens is not None else c, dtype=float)
    h = np.asarray(highs if highs is not None else np.maximum(o, c), dtype=float)
    lo = np.asarray(lows if lows is not None else np.minimum(o, c), dtype=float)
    t = pd.date_range(pd.Timestamp(start, tz="UTC"), periods=len(c), freq="D")
    m = E.Market(t=t, d=[x.date() for x in t], o={"BTC": o}, h={"BTC": h}, l={"BTC": lo}, c={"BTC": c})
    if fund is not None:
        m.fund["BTC"] = np.asarray(fund, dtype=float)
    return m


ZERO = K.ZERO_COST


# ------------------------------------------------------------------------------------------------ tax: no loss set-off


def test_loss_is_not_set_off_against_a_later_gain():
    b = E.SpotBook(100_000, ZERO, tax=True, tds=False)
    b.buy("BTC", 50_000, 100.0)
    b.sell("BTC", b.qty("BTC"), 80.0, D0)         # loss of 10,000: ignored
    b.buy("BTC", 40_000, 100.0)
    b.sell("BTC", b.qty("BTC"), 125.0, D0)        # gain of 10,000: taxed in full
    assert b.disposals[0][1] == pytest.approx(-10_000)
    assert b.disposals[1][1] == pytest.approx(10_000)
    assert b.accrued_tax() == pytest.approx(0.312 * 10_000)


def test_fees_are_not_deductible():
    b = E.SpotBook(10_000, K.Cost(0.01, 0.0), tax=True, tds=False)
    b.buy("BTC", 10_000, 100.0)                   # fee 1.18% of notional; basis = notional only
    q = b.qty("BTC")
    notional = 10_000 / (1 + 0.01 * 1.18)
    assert q * 100 == pytest.approx(notional)
    b.sell("BTC", q, 100.0, D0)                   # flat price: gain 0 even though fees lost money
    assert b.disposals[-1][1] == pytest.approx(0.0, abs=1e-6)
    assert b.accrued_tax() == 0


def test_fifo_basis_on_a_partial_sale():
    b = E.SpotBook(20_000, ZERO, tax=True, tds=False)
    b.buy("BTC", 10_000, 100.0)   # 100 coins at 100
    b.buy("BTC", 10_000, 200.0)   # 50 coins at 200
    b.sell("BTC", 120, 150.0, D0)  # 100 @100 + 20 @200 -> basis 14,000, value 18,000
    assert b.disposals[-1][1] == pytest.approx(4_000)
    assert b.qty("BTC") == pytest.approx(30)


def test_tax_is_paid_at_the_31_march_close_and_resets():
    m = market([100, 100, 200, 200, 200], start=date(2025, 3, 29))   # 29, 30, 31 Mar, 1, 2 Apr
    sp = {"BTC": [(0, 2, None)]}                                      # sold at the open of 31 Mar (price 200)
    r = E.simulate(m, 0, 5, capital=1000, coins=("BTC",), case="zero", tax=True, tds=False, spells=sp, sleeve=1.0)
    # gain 1000 taxed 312 at the FY end; nothing after
    assert r.tax_paid == pytest.approx(312)
    assert r.final == pytest.approx(2000 - 312)
    assert E.fy_of(date(2025, 3, 31)) == 2024 and E.fy_of(date(2025, 4, 1)) == 2025


def test_many_small_round_trips_pay_tax_on_an_overall_flat_result():
    # up 10%, down 10%, repeated: every winning trade is taxed, every losing one is ignored
    closes = [100, 110, 100, 110, 100, 110, 100]
    m = market(closes)
    sp = {"BTC": [(0, 1, None), (2, 3, None), (4, 5, None)]}   # buy 100, sell 110 each time
    pre = E.simulate(m, 0, 7, capital=1000, coins=("BTC",), case="zero", tax=False, tds=False, spells=sp, sleeve=1.0)
    aft = E.simulate(m, 0, 7, capital=1000, coins=("BTC",), case="zero", tax=True, tds=False, spells=sp, sleeve=1.0)
    assert pre.final > aft.final
    assert aft.tax_paid > 0


# ------------------------------------------------------------------------------------------------ TDS


def test_tds_locks_one_percent_for_a_year_then_returns_it():
    b = E.SpotBook(10_000, ZERO, tax=False, tds=True)
    b.buy("BTC", 10_000, 100.0)
    b.sell("BTC", b.qty("BTC"), 100.0, D0)
    assert b.cash == pytest.approx(9_900)
    assert b.value({"BTC": 100.0}) == pytest.approx(10_000)        # still yours, just locked
    b.release_tds(D0 + timedelta(days=364))
    assert b.cash == pytest.approx(9_900)
    b.release_tds(D0 + timedelta(days=365))
    assert b.cash == pytest.approx(10_000)


def test_tds_lock_costs_compounding_when_cash_is_reinvested():
    closes = [100, 120, 120, 144, 144]
    m = market(closes)
    sp = {"BTC": [(0, 1, None), (1, 4, None)]}     # sell then rebuy at the same open: the 1% is out of the market
    with_tds = E.simulate(m, 0, 5, capital=1000, coins=("BTC",), case="zero", tax=False, tds=True, spells=sp, sleeve=1.0)
    no_tds = E.simulate(m, 0, 5, capital=1000, coins=("BTC",), case="zero", tax=False, tds=False, spells=sp, sleeve=1.0)
    assert with_tds.final < no_tds.final
    assert no_tds.final - with_tds.final == pytest.approx(1000 * 1.2 * 0.01 * 0.2, rel=1e-6)


# ------------------------------------------------------------------------------------------------ perps


def test_funding_sign_longs_pay_shorts_receive_when_positive():
    for side, sign in ((+1, -1), (-1, +1)):
        p = E.PerpBook(10_000, ZERO, tax=False)
        p.open("BTC", side, 5_000, 1_000.0)         # 5,000 notional = 5000 contracts of 0.001 at 1,000
        m0, cash0 = p.pos["BTC"]["margin"], p.cash
        p.apply_funding("BTC", 0.001, 1_000.0)
        assert p.pos["BTC"]["margin"] - m0 == pytest.approx(sign * 0.001 * 5_000)
        assert p.cash == cash0                        # isolated margin: free cash is untouched
    p = E.PerpBook(10_000, ZERO, tax=False)
    p.open("BTC", +1, 5_000, 1_000.0)
    m0 = p.pos["BTC"]["margin"]
    p.apply_funding("BTC", -0.001, 1_000.0)          # negative funding: longs receive
    assert p.pos["BTC"]["margin"] - m0 == pytest.approx(+5.0)


def test_funding_can_never_take_an_isolated_account_below_zero():
    p = E.PerpBook(10_000, ZERO, tax=False, lev=3)
    p.open("BTC", +1, 30_000, 1_000.0)              # all cash is margin
    assert p.cash == pytest.approx(0.0)
    lp0 = p.liq_price("BTC")
    p.apply_funding("BTC", 0.05, 1_000.0)            # pays 1,500 out of the 10,000 margin
    assert p.liq_price("BTC") > lp0                  # the liquidation price moves closer
    assert p.check_liq("BTC", lp0 + 1, 1_000.0, D0)
    assert p.cash == pytest.approx(0.0) and p.value({"BTC": 1.0}) == pytest.approx(0.0)


def test_open_never_spends_more_than_the_cash():
    p = E.PerpBook(1_000, K.PERP_COST["base"], tax=False, lev=3)
    p.open("BTC", +1, 1_000_000, 1_000.0)
    assert p.cash >= 0


def test_liquidation_at_3x_loses_the_margin_and_1x_survives_a_halving():
    p = E.PerpBook(10_000, ZERO, tax=False, lev=3)
    p.open("BTC", +1, 9_000, 1_000.0)
    assert p.liq_price("BTC") == pytest.approx(1000 * (1 - 1 / 3 + K.MM))
    assert not p.check_liq("BTC", 700.0, 1000.0, D0)
    assert p.check_liq("BTC", 660.0, 1000.0, D0)
    assert p.cash == pytest.approx(10_000 - 3_000) and p.liquidations == 1
    q = E.PerpBook(10_000, ZERO, tax=False, lev=1)
    q.open("BTC", +1, 9_000, 1_000.0)
    assert not q.check_liq("BTC", 500.0, 1000.0, D0)
    s = E.PerpBook(10_000, ZERO, tax=False, lev=1)
    s.open("BTC", -1, 9_000, 1_000.0)                # a 1x short dies when the price about doubles
    assert s.check_liq("BTC", 1000.0, 1_996.0, D0)


def test_contracts_are_whole():
    p = E.PerpBook(10_000, ZERO, tax=False)
    assert p.open("BTC", +1, 0.0009 * 1_000_000, 1_000_000.0) == 0   # less than one 0.001 BTC contract
    assert p.open("BTC", +1, 0.0025 * 1_000_000, 1_000_000.0) == 2


def test_reading_a_ignores_losses_reading_b_sets_them_off_and_carries_forward():
    for reading in ("A", "B"):
        p = E.PerpBook(100_000, ZERO, tax=True, reading=reading)
        p.open("BTC", +1, 10_000, 100.0)
        p.close("BTC", 90.0, D0)                     # -1,000
        p.open("BTC", +1, 10_000, 100.0)
        p.close("BTC", 115.0, D0)                    # +1,500
        if reading == "A":
            assert p.accrued_tax() == pytest.approx(0.312 * 1_500)
        else:
            assert p.accrued_tax() == pytest.approx(0.312 * 500)
    p = E.PerpBook(100_000, ZERO, tax=True, reading="B")
    p.open("BTC", +1, 10_000, 100.0)
    p.close("BTC", 80.0, D0)                         # -2,000 in FY 2024
    p.pay_tax(2024)
    assert p.tax_paid == 0 and sum(x for _, x in p.carry) == pytest.approx(2_000)
    p.open("BTC", +1, 10_000, 100.0)
    p.close("BTC", 130.0, D0)                        # +3,000 in FY 2025: 1,000 taxable
    assert p.accrued_tax() == pytest.approx(0.312 * 1_000)
    p.pay_tax(2025)
    assert p.tax_paid == pytest.approx(312) and not p.carry


# ------------------------------------------------------------------------------------------------ look-ahead guard


def _guard(fn, n=400, cut=300, seed=1):
    rng = np.random.default_rng(seed)
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.03, n)))
    h = c * (1 + rng.uniform(0, 0.02, n))
    lo = c * (1 - rng.uniform(0, 0.02, n))
    o = np.concatenate([[c[0]], c[:-1]])
    base = fn(o, h, lo, c)
    c2, h2, l2, o2 = c.copy(), h.copy(), lo.copy(), o.copy()
    c2[cut + 1:] *= 3.0
    h2[cut + 1:] *= 3.0
    l2[cut + 1:] *= 0.2
    o2[cut + 1:] *= 0.5
    alt = fn(o2, h2, l2, c2)
    return base, alt, cut


@pytest.mark.parametrize("name, fn", [
    ("sma100", lambda o, h, lo, c: S.trend_on("sma100", c, h, lo)),
    ("sma200", lambda o, h, lo, c: S.trend_on("sma200", c, h, lo)),
    ("dc50", lambda o, h, lo, c: S.trend_on("dc50", c, h, lo)),
    ("vol", lambda o, h, lo, c: S.vol_weights(np.ones(len(c)), c)),
    ("atr", lambda o, h, lo, c: S.atr(h, lo, c)),
    ("carry", lambda o, h, lo, c: S.carry_state((c - 100) / 1e4)),
])
def test_signals_do_not_read_the_future(name, fn):
    base, alt, cut = _guard(fn)
    np.testing.assert_array_equal(np.nan_to_num(base[:cut + 1], nan=-9), np.nan_to_num(alt[:cut + 1], nan=-9))


def test_positions_trade_on_the_next_bar():
    on = np.array([0, 1, 1, 0, 1])
    np.testing.assert_array_equal(S.to_mask(on), [0, 0, 1, 1, 0])
    np.testing.assert_array_equal(S.shift_weights(np.array([0.5, 0.2, 0.0])), [0, 0.5, 0.2])


def test_breakout_entries_before_a_bar_do_not_depend_on_later_bars():
    def entries(o, h, lo, c):
        return [s[0] for s in S.breakout_spells(o, h, lo, c)]
    base, alt, cut = _guard(entries, n=600, cut=450)
    assert [e for e in base if e <= cut] == [e for e in alt if e <= cut]


def test_breakout_stop_fills_at_the_stop_or_a_gap_open():
    n = 40
    c = np.full(n, 100.0)
    h, lo, o = c + 1, c - 1, c.copy()
    c[25] = 110; h[25] = 111                     # breakout at the close of bar 25 -> entry at open 26
    o[26:] = 110; c[26:] = 110; h[26:] = 111; lo[26:] = 109
    lo[28] = 90; o[28] = 108                     # stop inside bar 28 (stop = 110 - 2 ATR)
    sp = S.breakout_spells(o, h, lo, c)
    assert sp[0][0] == 26 and sp[0][1] == 28
    a = S.atr(h, lo, c)[25]
    assert sp[0][2] == pytest.approx(min(o[28], 110 - 2 * a))
    o[28] = 80                                   # gap through the stop: fills at the open
    sp = S.breakout_spells(o, h, lo, c)
    assert sp[0][2] == pytest.approx(80)


# ------------------------------------------------------------------------------------------------ holdout lock


def test_holdout_is_locked_and_design_cut_is_strict(monkeypatch):
    t = pd.date_range("2025-10-10", "2025-10-15", freq="D", tz="UTC")
    df = pd.DataFrame({"t": t, "c": range(len(t))})
    monkeypatch.delenv("RC1_READ_HOLDOUT", raising=False)
    d = D._cut(df, "design")
    assert d.t.max() < K.HOLDOUT_START_TS and len(d) == 3
    with pytest.raises(PermissionError):
        D._cut(df, "holdout")
    monkeypatch.setenv("RC1_READ_HOLDOUT", "1")
    assert len(D._cut(df, "holdout")) == 6


# ------------------------------------------------------------------------------------------------ twins, BH, simulate


def test_shuffle_keeps_spell_lengths_and_count():
    rng = np.random.default_rng(0)
    m = np.array([0, 1, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0])
    lengths = sorted(e - a for a, e in E.mask_to_spells(m))
    for _ in range(50):
        x = S.shuffle_mask(m, rng)
        assert x.sum() == m.sum() and len(x) == len(m)
        got = E.mask_to_spells(x)
        if len(got) == len(lengths):            # (two spells merge when a zero-length gap lands between them)
            assert sorted(e - a for a, e in got) == lengths


def test_bh():
    q = S.bh([0.01, 0.04, 0.03, 0.20])
    np.testing.assert_allclose(q, [0.04, 0.0533333, 0.0533333, 0.2], rtol=1e-4)


def test_hold_pays_tax_once_on_the_final_sale():
    m = market([100, 150, 200])
    buys = {"BTC": np.array([1000.0, 0, 0])}
    r = E.simulate(m, 0, 3, capital=1000, coins=("BTC",), case="zero", tax=True, tds=True, buys=buys)
    assert r.tax_paid == pytest.approx(0.312 * 1000)
    assert r.final == pytest.approx(2000 - 312)       # TDS is counted back at the end (a credit)


def test_empty_window_has_no_trades():
    m = market([100] * 10)
    r = E.simulate(m, 0, 10, capital=1000, coins=("BTC",), spells={"BTC": []})
    assert r.orders == 0 and r.final == pytest.approx(1000)


def test_perp_long_pays_funding_through_simulate():
    m = market([100] * 5, fund=[0, 0.01, 0.01, 0, 0])
    r = E.simulate(m, 0, 5, capital=10_000, coins=("BTC",), venue="perp", case="zero", tax=False, tds=False,
                   spells={"BTC": [(0, 5, None)]}, sleeve=1.0)
    # 10,000 notional (100,000 contracts of 0.001 at 100) pays 1% twice
    assert r.final == pytest.approx(10_000 - 200)


# ------------------------------------------------------------------------------------------------ review fixes (13 Oct)


def test_funding_does_not_liquidate_a_profitable_position():
    p = E.PerpBook(10_000, ZERO, tax=False, lev=1)
    p.open("BTC", +1, 10_000, 1_000.0)
    p.apply_funding("BTC", 0.5, 4_000.0)             # pays 20,000 on a 40,000 notional: margin goes to -10,000
    assert p.pos["BTC"]["margin"] < 0
    assert not p.check_liq("BTC", 3_900.0, 4_000.0, D0)   # equity 20,000 > 0: not liquidated
    p.close("BTC", 4_000.0, D0)
    assert p.cash == pytest.approx(10_000 + 30_000 - 20_000)


def test_liquidation_never_takes_more_than_the_posted_margin():
    p = E.PerpBook(10_000, ZERO, tax=False, lev=3)
    p.open("BTC", +1, 15_000, 1_000.0)               # 5,000 margin, 5,000 free cash
    p.apply_funding("BTC", 0.01, 1_000.0)
    assert p.check_liq("BTC", 1.0, 1_000.0, D0)
    assert p.cash == pytest.approx(5_000)


def test_shuffle_never_merges_spells():
    rng = np.random.default_rng(1)
    m = np.array([1, 1, 0, 1, 0, 0, 1, 1, 1, 0])     # 3 spells, a zero leading gap
    for _ in range(300):
        x = S.shuffle_mask(m, rng)
        assert len(E.mask_to_spells(x)) == 3 and x.sum() == m.sum()
        assert sorted(e - a for a, e in E.mask_to_spells(x)) == [1, 2, 3]


def test_reading_b_carry_expires_after_8_years():
    p = E.PerpBook(100_000, ZERO, tax=True, reading="B")
    p.carry.append((2010, 5_000.0))
    p.fy_net = 1_000.0
    p.pay_tax(2019)                                   # the FY2010 loss is too old
    assert p.tax_paid == pytest.approx(312)


def test_perp_tax_shortfall_comes_from_margin_not_a_loan():
    p = E.PerpBook(1_000, ZERO, tax=True, reading="B", lev=1)
    p.open("BTC", +1, 1_000, 100.0)
    assert p.cash == pytest.approx(0)
    p.fy_net = 1_000.0
    p.pay_tax(2024)
    assert p.cash == pytest.approx(0) and p.pos["BTC"]["margin"] == pytest.approx(1_000 - 312)


def test_breakout_twin_exits_match_the_real_rule():
    rng = np.random.default_rng(3)
    n = 800
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, n)))
    h, lo = c * 1.01, c * 0.99
    o = np.concatenate([[c[0]], c[:-1]])
    real = S.breakout_spells(o, h, lo, c)
    twin = S.breakout_exits([s[0] for s in real], o, h, lo, c)
    assert twin == real
