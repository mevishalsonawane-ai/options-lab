"""R103 tests: charges, structures, stops, targets, the expiry close, the model for legs without a print, statistics,
and the look-ahead guard (synthetic days always; real lake days when the built day files exist).

    python -m pytest research/hunt/r103_selling/test_r103.py -q
"""
from __future__ import annotations

import copy
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r103data as R  # noqa: E402
import r103sim as S  # noqa: E402

HAVE_LAKE = all(R.have(u) for u in R.UNDS)
T = S.ENTRIES["0920"]


def flat(v, n=R.N):
    return np.full(n, float(v))


def make_day(paths: dict, spot=22250.0, day=date(2025, 3, 3), expiry=False, und="NIFTY") -> R.Day:
    """A synthetic day: index flat at `spot`; each contract (strike, side) -> close path (open = high = low = close,
    volume 1 everywhere). A path may contain NaN (no bar)."""
    ix = np.full((R.N, 4), spot, dtype=np.float32)
    opts = {}
    for key, c in paths.items():
        c = np.asarray(c, dtype=np.float64)
        a = np.full((R.N, 7), np.nan, dtype=np.float32)
        for j in (R.O, R.H, R.L, R.C):
            a[:, j] = c
        a[:, R.V] = np.where(np.isfinite(c), 1.0, 0.0)
        opts[key] = a
    return R.Day(day, und, ix, opts, flat(14.0), spot, 14.0, expiry, 0 if expiry else 3)


def straddle_paths(c=130.0, p=120.0, extra=None):
    return {(22250.0, "C"): flat(c), (22250.0, "P"): flat(p), **(extra or {})}


def condor_paths(c=110.0, p=90.0):
    paths = straddle_paths(c, p)                                         # EM 200 -> w = 4
    for k, s in [(22450.0, "C"), (22050.0, "P"), (22650.0, "C"), (21850.0, "P")]:
        paths[(k, s)] = flat(20.0)
    return paths


# ------------------------------------------------------------------------------------------------ helpers
def test_ffill_limit():
    a = np.array([1.0, np.nan, np.nan, np.nan, np.nan, 2.0, np.nan])
    out = S.ffill(a, 3)
    assert out[1] == 1.0 and out[3] == 1.0 and np.isnan(out[4]) and out[6] == 2.0
    assert np.isnan(S.ffill(np.array([np.nan, np.nan, 1.0]))[0])


def test_half_spread_floor_and_growth():
    assert S.half_spread(10.0, 0) == pytest.approx(0.05)             # 0.016 < 1 tick
    assert S.half_spread(200.0, 0) == pytest.approx(0.32)
    assert S.half_spread(200.0, 4) == pytest.approx(0.32 * 3)         # 1 + 0.5 x 4


def test_order_charge_dated():
    sell_2025 = S.order_charge(True, 100.0, 75, date(2025, 3, 3))
    sell_2026 = S.order_charge(True, 100.0, 75, date(2026, 4, 1))
    buy_2025 = S.order_charge(False, 100.0, 75, date(2025, 3, 3))
    val = 7500.0
    assert sell_2025 == pytest.approx(20 + val * (0.0003503 + 0.000005) + val * 1e-6 + val * 0.001
                                      + 0.18 * (20 + val * (0.0003503 + 0.000005) + val * 1e-6))
    assert sell_2026 - sell_2025 == pytest.approx(val * 0.0005)       # STT 0.10% -> 0.15% from 1 Apr 2026
    assert buy_2025 == pytest.approx(sell_2025 - val * 0.001 + val * 0.00003)   # no STT, stamp on the buy
    assert S.order_charge(True, np.nan, 75, date(2025, 3, 3)) == 0.0


def test_lot_schedule():
    assert R.lot("NIFTY", date(2025, 3, 3)) == 75 and R.lot("NIFTY", date(2026, 1, 6)) == 65
    assert R.lot("BANKNIFTY", date(2024, 6, 4)) == 15 and R.lot("BANKNIFTY", date(2026, 2, 2)) == 30


def test_expiry_days_rule_and_dte():
    import pandas as pd
    tv = pd.Series([0.3, 0.2, 2.0, 1.0, 0.1, 1.5], index=[date(2025, 3, 3 + i) for i in range(6)])
    ex = R.expiry_days(tv)
    assert ex == [date(2025, 3, 4), date(2025, 3, 7)]                # 3 Mar is dropped (adjacent, larger tv)
    sess = [date(2025, 3, 3 + i) for i in range(6)]
    dte = R.dte_of(sess, set(ex))
    assert dte[date(2025, 3, 3)] == 1 and dte[date(2025, 3, 4)] == 0 and dte[date(2025, 3, 5)] == 2
    assert dte[date(2025, 3, 7)] == 0 and dte[date(2025, 3, 8)] == -1


# ------------------------------------------------------------------------------------------------ structures
def test_structure_strikes():
    paths = condor_paths()
    d = make_day(paths)
    assert [(l.strike, l.side, l.short) for l in S.structure(d, T, "STR")] == [(22250.0, "C", True), (22250.0, "P", True)]
    assert [(l.strike, l.short) for l in S.structure(d, T, "STG")] == [(22450.0, True), (22050.0, True)]
    assert [(l.strike, l.short) for l in S.structure(d, T, "IC")] == [(22450.0, True), (22050.0, True), (22650.0, False), (21850.0, False)]
    assert [(l.strike, l.short) for l in S.structure(d, T, "IF")] == [(22250.0, True), (22250.0, True), (22650.0, False), (21850.0, False)]
    assert S.structure(d, T, "IF", sell=False)[0].short is False       # the BUY mirror flips every sign
    del paths[(21850.0, "P")]
    assert S.structure(make_day(paths), T, "IC") is None               # a missing wing: no trade
    d2 = make_day(straddle_paths())
    d2.opts[(22250.0, "P")][:, R.V] = 0.0                              # an untraded leg (no volume in t-2..t)
    assert S.structure(d2, T, "STR") is None


def test_strangle_width_bounds():
    d = make_day(straddle_paths(10.0, 10.0, {(22300.0, "C"): flat(5.0), (22200.0, "P"): flat(5.0)}))
    assert [l.strike for l in S.structure(d, T, "STG")] == [22300.0, 22200.0]   # EM 20 -> w at least 1
    paths = straddle_paths(300.0, 300.0, {(22550.0, "C"): flat(5.0), (21950.0, "P"): flat(5.0)})
    assert [l.strike for l in S.structure(make_day(paths), T, "STG")] == [22550.0, 21950.0]   # EM 600 -> w capped at 6


# ------------------------------------------------------------------------------------------------ one trade
def test_straddle_hold_to_close_numbers():
    c, p = flat(130.0), flat(120.0)
    c[T + 1:] = 100.0          # from the fill minute on the call is 100, the put 90
    p[T + 1:] = 90.0
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    tr = S.simulate(d, T, "STR", "X0", "T0")
    hc, hp = S.half_spread(100.0, 0), S.half_spread(90.0, 0)
    assert tr.credit == pytest.approx((100 - hc) + (90 - hp))
    assert tr.exit_row == S.CLOSE_ROW and tr.reason == "close" and not tr.modelled
    assert tr.gross == pytest.approx(-(2 * hc + 2 * hp) * 75)          # bought back at 100 + h and 90 + h
    assert tr.charges == pytest.approx(sum(S.order_charge(True, x, 75, d.day) + S.order_charge(False, y, 75, d.day)
                                           for x, y in ((100 - hc, 100 + hc), (90 - hp, 90 + hp))))
    assert tr.net == pytest.approx(tr.gross - tr.charges)
    assert tr.margin == pytest.approx(0.11 * 22250 * 75)


def test_leg_stop_fills_at_level_or_close_with_double_slippage():
    c, p = flat(100.0), flat(100.0)
    c[T + 20] = 250.0           # the call spikes to 2.5x in one minute (high = close = 250)
    c[T + 21:] = 100.0
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    e = 100.0 - S.half_spread(100.0, 0)
    tr = S.simulate(d, T, "STR", "X2", "T0")
    assert tr.reason == "mixed" and tr.exit_row == S.CLOSE_ROW         # the put ran to the close
    fill = 250.0 + 2 * S.half_spread(250.0, 0)                          # max(level 2e, close 250) + 2h
    put_leg = (e - (100.0 + S.half_spread(100.0, 0)))
    assert tr.gross == pytest.approx(((e - fill) + put_leg) * 75)
    assert S.simulate(d, T, "STR", "X1", "T0").gross == pytest.approx(tr.gross)
    assert S.simulate(d, T, "STR", "X0", "T0").gross == pytest.approx(2 * put_leg * 75)


def test_leg_stop_level_exactly_touched_fills_at_level():
    c, p = flat(100.0), flat(100.0)
    e = 100.0 - S.half_spread(100.0, 0)
    c[T + 5] = 2 * e           # the high touches the level, the close is at the level
    c[T + 6:] = 100.0
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    tr = S.simulate(d, T, "STR", "X2", "T0")
    fill = 2 * e + 2 * S.half_spread(2 * e, 0)
    assert tr.gross == pytest.approx(((e - fill) + (e - (100.0 + S.half_spread(100.0, 0)))) * 75, abs=0.5)


def test_wing_leaves_with_its_short():
    paths = condor_paths()
    paths[(22450.0, "C")][T + 10:] = 60.0                               # the short call triples
    paths[(22650.0, "C")][T + 10:] = 35.0                               # its wing gains
    d = make_day(paths)
    tr = S.simulate(d, T, "IC", "X2", "T0")
    assert tr.reason == "mixed"
    h20, h20w = S.half_spread(20.0, 4), S.half_spread(20.0, 8)
    short_call = (20 - h20) - (60 + 2 * S.half_spread(60.0, 4))
    wing = (35 - S.half_spread(35.0, 8)) - (20 + h20w)                   # sold in the stop minute at its close - h
    put_side = ((20 - h20) - (20 + h20)) + ((20 - h20w) - (20 + h20w))   # both put legs to the close
    assert tr.gross == pytest.approx((short_call + wing + put_side) * 75)


def test_combined_stop_and_target_fill_next_open():
    c, p = flat(100.0), flat(100.0)
    c[T + 30:] = 300.0          # loss on the pair on closes = 200 >= credit (about 199.7): X3 fires at T+30
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    tr = S.simulate(d, T, "STR", "X3", "T0")
    assert tr.reason == "combined" and tr.exit_row == T + 31
    e = 100.0 - S.half_spread(100.0, 0)
    gross = ((e - (300 + 2 * S.half_spread(300.0, 0))) + (e - (100 + 2 * S.half_spread(100.0, 0)))) * 75
    assert tr.gross == pytest.approx(gross)
    # X4: 3% of the margin (0.11 x 22250 x 75 x 0.03 = Rs 5,506 = 73.4 a unit) fires earlier on a smaller rise
    c2 = flat(100.0)
    c2[T + 10:] = 180.0
    tr4 = S.simulate(make_day({(22250.0, "C"): c2, (22250.0, "P"): p}), T, "STR", "X4", "T0")
    assert tr4.reason == "combined" and tr4.exit_row == T + 11
    assert S.simulate(make_day({(22250.0, "C"): c2, (22250.0, "P"): p}), T, "STR", "X3", "T0").reason == "close"
    # T1: the pair decays to half -> target at the next open
    c3, p3 = flat(100.0), flat(100.0)
    c3[T + 40:] = 50.0
    p3[T + 40:] = 45.0
    tr1 = S.simulate(make_day({(22250.0, "C"): c3, (22250.0, "P"): p3}), T, "STR", "X0", "T1")
    assert tr1.reason == "target" and tr1.exit_row == T + 41
    assert tr1.gross == pytest.approx(((e - (50 + S.half_spread(50.0, 0))) + (e - (45 + S.half_spread(45.0, 0)))) * 75)
    assert S.simulate(make_day({(22250.0, "C"): c, (22250.0, "P"): p}), T, "STR", "X3", "T1").reason == "combined"


def test_expiry_day_closes_at_1505_and_elm():
    d = make_day(straddle_paths(), expiry=True)
    tr = S.simulate(d, T, "STR", "X0", "T0")
    assert tr.exit_row == R.minute(15, 5)
    assert tr.margin == pytest.approx((0.11 + 0.02) * 22250 * 75)
    assert S.simulate(d, R.minute(15, 5), "STR", "X0", "T0") is None    # too late to enter


def test_missing_prices_are_modelled_and_fill_falls_back():
    c, p = flat(100.0), flat(100.0)
    c[T + 5:T + 60] = np.nan     # 55 minutes without a call bar: the model prices it near its last IV, no false stop
    c[T + 60:] = 100.0
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    tr = S.simulate(d, T, "STR", "X2", "T1")
    assert tr.reason == "close" and tr.modelled
    c2 = flat(100.0)
    c2[S.CLOSE_ROW - 2:] = np.nan
    tr2 = S.simulate(make_day({(22250.0, "C"): c2, (22250.0, "P"): p}), T, "STR", "X0", "T0")
    assert np.isfinite(tr2.net)


def test_buy_side_mirror():
    c, p = flat(100.0), flat(100.0)
    c[T + 1:] = 110.0
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    sell = S.simulate(d, T, "STR", "X0", "T0")
    buy = S.simulate(d, T, "STR", "X0", "T0", sell=False, buy_rules=S.BUY_RULES["B0"])
    assert buy.credit == pytest.approx(-sell.credit, abs=1.0)           # a debit of the same size (spreads apart)
    assert buy.gross == pytest.approx(-sell.gross - 2 * (2 * S.half_spread(110.0, 0) + 2 * S.half_spread(100.0, 0)) * 75, abs=1.0)
    c3, p3 = flat(100.0), flat(100.0)
    c3[T + 20:] = 40.0
    p3[T + 20:] = 50.0
    b1 = S.simulate(make_day({(22250.0, "C"): c3, (22250.0, "P"): p3}), T, "STR", "X0", "T0", sell=False, buy_rules=S.BUY_RULES["B1"])
    assert b1.reason == "combined" and b1.exit_row == T + 21          # the 50%-of-debit stop on the buy side


# ------------------------------------------------------------------------------------------------ model valuation
def test_bs_price_and_implied_vol():
    c, p = S.bs_price(22250.0, 22250.0, 7 / 252, 0.13, True), S.bs_price(22250.0, 22250.0, 7 / 252, 0.13, False)
    assert c - p == pytest.approx(22250.0 * (1 - np.exp(-0.065 * 7 / 252)), abs=0.01)      # put-call parity
    assert S.bs_price(22250.0, 21000.0, 1 / 252, 0.13, True) == pytest.approx(1250.0 + 21000 * (1 - np.exp(-0.065 / 252)), abs=1.0)
    assert S.bs_price(22250.0, 24000.0, 1 / 252, 0.13, True) < 0.05
    assert S.implied_vol(c, 22250.0, 22250.0, 7 / 252, True) == pytest.approx(0.13, abs=1e-6)
    assert np.isnan(S.implied_vol(np.nan, 22250.0, 22250.0, 0.01, True))
    assert np.isnan(S.implied_vol(5000.0, 22250.0, 22250.0, 0.01, True))     # above the no-arbitrage band
    assert S.years_left(0)[R.N - 1] > 0 and S.years_left(3)[0] == pytest.approx((3 + 1.0) / 252)


def test_leg_without_print_is_valued_by_the_model_not_the_entry_price():
    """A wing that leaves the ATM+-10 window: its close is NaN after row T+30; the exit uses the model (own last IV x
    the nearer strike's IV change), never the entry price (the bug found on 22 Oct 2026)."""
    paths = condor_paths()
    d = make_day(paths)
    d.opts[(21850.0, "P")][T + 30:] = np.nan
    d.dte = 1
    tr = S.simulate(d, T, "IC", "X0", "T0")
    assert tr.modelled
    wing = S.structure(d, T, "IC")[3]
    assert wing.model[T + 34] and not wing.model[T + 32] and not wing.model[T + 29]    # 3 minutes of ffill first
    assert wing.close[T + 29] == 20.0
    T1 = S.years_left(1)
    sig = S.implied_vol(20.0, 22250.0, 21850.0, T1[T + 29], False)
    scale = S.implied_vol(20.0, 22250.0, 22050.0, T1[S.CLOSE_ROW], False) / S.implied_vol(20.0, 22250.0, 22050.0, T1[T + 29], False)
    assert wing.close[S.CLOSE_ROW] == pytest.approx(S.bs_price(22250.0, 21850.0, T1[S.CLOSE_ROW], sig * scale, False), abs=0.05)
    assert 0.05 < wing.close[S.CLOSE_ROW] < 20.0            # never the entry price
    ref = S.simulate(make_day(paths), T, "IC", "X0", "T0")  # the same day with the wing printed flat at 20
    assert tr.gross < ref.gross


def test_model_uses_own_implied_vol_then_atm_iv():
    T2 = S.years_left(2)
    c = flat(100.0)
    c[T + 10:] = np.nan
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): flat(100.0)})
    d.dte = 2
    legs = S.structure(d, T, "STR")
    assert np.isfinite(legs[0].close[T + 20]) and legs[0].model[T + 20]
    sig = S.implied_vol(100.0, 22250.0, 22250.0, T2[T + 9], True)
    assert legs[0].close[T + 20] == pytest.approx(S.bs_price(22250.0, 22250.0, T2[T + 20], sig, True), abs=0.05)
    assert legs[0].high[T + 20] == legs[0].close[T + 20]     # a missing high is the modelled close
    paths = condor_paths()
    paths[(21850.0, "P")][T + 1:] = np.nan
    d2 = make_day(paths)
    d2.dte = 2
    wing = S.structure(d2, T, "IC")[3]
    sig_w = S.implied_vol(20.0, 22250.0, 21850.0, T2[T], False)
    scale = S.implied_vol(20.0, 22250.0, 22050.0, T2[T + 40], False) / S.implied_vol(20.0, 22250.0, 22050.0, T2[T], False)
    assert wing.close[T + 40] == pytest.approx(S.bs_price(22250.0, 21850.0, T2[T + 40], sig_w * scale, False), abs=0.05)


def test_model_scales_own_iv_by_the_reference_strike():
    T2 = S.years_left(2)
    paths = condor_paths()
    paths[(21850.0, "P")][T + 1:] = np.nan                   # the far put wing has no print after entry
    paths[(22050.0, "P")][T + 1:] = 30.0                     # the nearer put re-prices up: its IV rises
    d = make_day(paths)
    d.dte = 2
    wing = S.structure(d, T, "IC")[3]
    sig_own = S.implied_vol(20.0, 22250.0, 21850.0, T2[T], False)
    ref0 = S.implied_vol(20.0, 22250.0, 22050.0, T2[T], False)
    ref1 = S.implied_vol(30.0, 22250.0, 22050.0, T2[T + 40], False)
    want = S.bs_price(22250.0, 21850.0, T2[T + 40], sig_own * ref1 / ref0, False)
    assert wing.close[T + 40] == pytest.approx(want, abs=0.05)
    assert wing.close[T + 40] > S.bs_price(22250.0, 21850.0, T2[T + 40], sig_own, False)


def test_short_leg_deep_in_the_money_is_still_priced_and_stopped():
    """The index crashes 1,300 points: the 09:20 ATM put leaves the window; the model keeps pricing it so the per-leg
    stop fires (a crash day is exactly when a stop matters)."""
    c, p = flat(100.0), flat(100.0)
    p[T + 10:] = np.nan
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    d.ix[T + 10:, 3] = 20950.0
    d.dte = 1
    tr = S.simulate(d, T, "STR", "X2", "T0")
    assert tr.reason == "mixed" and tr.modelled
    assert tr.gross < -1000 * 75
    assert S.simulate(d, T, "STR", "X0", "T0").gross < tr.gross   # no stop: the full crash


# ------------------------------------------------------------------------------------------------ statistics
def test_stats_and_tests():
    trades = [S.simulate(make_day(straddle_paths(), day=date(2025, 3, 3 + i)), T, "STR", "X0", "T0") for i in range(5)]
    st = S.stats(trades, blocks=[(date(2025, 3, 3), date(2025, 3, 4)), (date(2025, 3, 5), date(2025, 3, 9))])
    assert st["trades"] == 5 and st["win_rate"] == 0.0 and st["blocks_positive"] == 0
    assert st["max_dd"] == pytest.approx(st["net"])
    assert S.stats([]) == dict(trades=0, net=0.0)
    assert S.sign_flip_p(np.ones(12)) < 0.01
    assert S.sign_flip_p(np.array([1.0, -1.0] * 6)) > 0.5
    assert S.sign_flip_p(np.array([])) == 1.0
    q = S.bh(np.array([0.001, 0.02, 0.03, 0.5]))
    assert q[0] == pytest.approx(0.004) and q[1] == pytest.approx(0.04) and q[2] == pytest.approx(0.04) and q[3] == 0.5


def test_run_group_matches_single_runs_and_twins_reproduce():
    days = [make_day(condor_paths(), day=date(2025, 3, 3 + i)) for i in range(3)]
    g = S.run_group(days, "IC", T)
    for (s, t), lst in g.items():
        assert [x.net for x in lst] == pytest.approx([S.simulate(d, T, "IC", s, t).net for d in days])
    a = S.run_twins(days, "STR", 3, seed=7)
    b = S.run_twins(days, "STR", 3, seed=7)
    assert a == b and len(a) == 3 and set(a[0]) == {(s, t) for s in S.STOPS for t in S.TARGETS}


def test_day_set_and_events(tmp_path):
    f = tmp_path / "ev.csv"
    f.write_text("date,kind,name,source\n2025-03-04,budget,x,y\n", encoding="utf-8")
    ev = S.events(f)
    days = [make_day(straddle_paths(), day=date(2025, 3, 3 + i), expiry=(i == 2)) for i in range(3)]
    assert [d.day for d in S.day_set(days, "ALL", ev)] == [date(2025, 3, 3), date(2025, 3, 5)]
    assert [d.day for d in S.day_set(days, "EXPIRY", ev)] == [date(2025, 3, 5)]
    real = S.events(Path(__file__).resolve().parent / "events.csv")
    assert date(2024, 6, 4) in real and date(2026, 2, 1) in real


# ------------------------------------------------------------------------------------------------ look-ahead guard
def masked(day: R.Day, after: int, value=np.nan) -> R.Day:
    """A copy with every bar after row `after` replaced by `value` (blank or absurd future); the IV cache is dropped."""
    d = copy.deepcopy(day)
    d.__dict__.pop("_iv_paths", None)
    d.ix[after + 1:] = value
    for a in d.opts.values():
        a[after + 1:] = value
    if d.vix is not None:
        d.vix[after + 1:] = value
    return d


@pytest.mark.parametrize("value", [np.nan, 1e6])
def test_entry_decision_does_not_look_ahead_synthetic(value):
    d = make_day(condor_paths())
    for st in S.STRUCTS:
        a = [(l.strike, l.side, l.short) for l in S.structure(d, T, st)]
        b = [(l.strike, l.side, l.short) for l in S.structure(masked(d, T, value), T, st)]
        assert a == b


@pytest.mark.parametrize("value", [np.nan, 1e6])
def test_model_value_does_not_look_ahead_synthetic(value):
    """The modelled price of a leg at row s reads nothing after s."""
    paths = condor_paths()
    paths[(21850.0, "P")][T + 1:] = np.nan
    paths[(22050.0, "P")][T + 1:] = 30.0
    d = make_day(paths)
    d.dte = 2
    s = T + 40
    full = S.structure(d, T, "IC")[3].close[:s + 1]
    part = S.structure(masked(d, s, value), T, "IC")[3].close[:s + 1]
    assert np.allclose(full, part, equal_nan=True)


@pytest.mark.skipif(not HAVE_LAKE, reason="needs the built R103 day files")
@pytest.mark.parametrize("und", R.UNDS)
@pytest.mark.parametrize("value", [np.nan, 1e6])
def test_trades_do_not_look_ahead_real_days(und, value):
    """On real design days: (1) the structure chosen at t is the same when everything after t is blanked / absurd;
    (2) a trade that ended at row s (stop or target) is identical when everything after s is blanked / absurd."""
    days = [d for d in R.load(und, years=[2025]) if date(2025, 2, 1) <= d.day <= date(2025, 4, 30)]
    assert days
    checked = 0
    for d in days[:40]:
        for st in S.STRUCTS:
            legs = S.structure(d, T, st)
            if legs is None:
                continue
            legs_m = S.structure(masked(d, T, value), T, st)
            assert legs_m is not None and [(l.strike, l.side) for l in legs] == [(l.strike, l.side) for l in legs_m]
            for stop, tg in (("X2", "T0"), ("X3", "T1"), ("X4", "T0")):
                tr = S.simulate(d, T, st, stop, tg, legs=legs)
                if tr is None or tr.exit_row >= S.CLOSE_ROW_EXPIRY:
                    continue
                tr2 = S.simulate(masked(d, tr.exit_row, value), T, st, stop, tg)
                assert tr2 is not None and tr2.net == pytest.approx(tr.net) and tr2.exit_row == tr.exit_row
                checked += 1
    assert checked > 0


def test_profit_lock_arms_then_exits_on_give_back():
    """Lock (0.4, 0.2): the pair decays to 50% of the credit (profit 50% >= 40%: armed), then rises back to 85% of
    the credit (profit 15% <= 20%): exit at the next open. Never armed: no lock exit."""
    c, p = flat(100.0), flat(100.0)
    c[T + 20:] = 50.0
    p[T + 20:] = 50.0
    c[T + 40:] = 85.0
    p[T + 40:] = 85.0
    d = make_day({(22250.0, "C"): c, (22250.0, "P"): p})
    tr = S.simulate(d, T, "STR", "X0", "T0", lock=(0.4, 0.2))
    assert tr.reason == "target" and tr.exit_row == T + 41
    e = 100.0 - S.half_spread(100.0, 0)
    assert tr.gross == pytest.approx(2 * (e - (85 + S.half_spread(85.0, 0))) * 75)
    c2, p2 = flat(100.0), flat(100.0)
    c2[T + 20:] = 75.0                                       # profit 25% < 40%: never armed
    p2[T + 20:] = 75.0
    c2[T + 40:] = 95.0
    p2[T + 40:] = 95.0
    assert S.simulate(make_day({(22250.0, "C"): c2, (22250.0, "P"): p2}), T, "STR", "X0", "T0", lock=(0.4, 0.2)).reason == "close"
    assert S.simulate(d, T, "STR", "X0", "T0").reason == "close"   # no lock given: unchanged


# ------------------------------------------------------------------------------------------------ forward test (PREREG_FWD)
import pandas as pd  # noqa: E402
import r103fwd as F  # noqa: E402


def test_width_mult_and_close_row():
    paths = straddle_paths(110.0, 90.0, {(22550.0, "C"): flat(10.0), (21950.0, "P"): flat(10.0),
                                         (22450.0, "C"): flat(20.0), (22050.0, "P"): flat(20.0)})
    d = make_day(paths)
    assert [l.strike for l in S.structure(d, T, "STG")] == [22450.0, 22050.0]                 # EM 200 -> 4 strikes
    assert [l.strike for l in S.structure(d, T, "STG", width_mult=1.5, max_w=10)] == [22550.0, 21950.0]   # 6 strikes
    tr = S.simulate(d, T, "STR", "X0", "T0", close_row=R.minute(14, 30))
    assert tr.exit_row == R.minute(14, 30)
    assert S.simulate(make_day(straddle_paths(), expiry=True), T, "STR", "X0", "T0", close_row=R.minute(15, 20)).exit_row == R.minute(15, 5)


def test_skip_filters_read_only_the_past():
    d = make_day(straddle_paths(110.0, 90.0))
    d.prev_close = 22250.0
    t = F.ENTRIES["1015"]
    assert not F.skip(d, t, "ALL")
    g = copy.deepcopy(d)
    g.ix[0, 0] = 22250.0 * 1.01                                  # a 1% gap at the open
    assert F.skip(g, t, "GAP") and F.skip(g, t, "ALL") and not F.skip(g, t, "VIX")
    r = copy.deepcopy(d)
    r.ix[10, 1] = 22250.0 + 250.0                                # range 250 > the 200-point straddle
    assert F.skip(r, t, "RANGE") and not F.skip(r, t, "GAP")
    late = copy.deepcopy(d)
    late.ix[t + 5, 1] = 30000.0                                  # after the entry minute: ignored
    late.vix[t + 5:] = 99.0
    assert not F.skip(late, t, "ALL")
    v = copy.deepcopy(d)
    v.vix[:] = 14.0 * 1.06                                       # VIX 6% above its previous close
    assert F.skip(v, t, "VIX")


def test_rules_and_selection():
    rules = F.all_rules()
    assert len(rules) == 90 and F.BASE in rules and len({r.name for r in rules}) == 90
    assert F.parse(F.BASE.name) == F.BASE and F.parse("w1.25-1100-1430-RANGE") == F.Rule(1.25, "1100", "1430", "RANGE")
    base = dict(trades_A=50, trades_B=80, win_rate_A=0.75, win_rate_B=0.72, net_A=1.0, net_B=1.0, worst_month_A=0.0,
                worst_month_B=0.0, per_trade_A=10.0, per_trade_B=10.0)
    df = pd.DataFrame([dict(base, rule=F.BASE.name), dict(base, rule="a"), dict(base, rule="b", win_rate_B=0.80),
                       dict(base, rule="c", net_B=-1.0), dict(base, rule="d", win_rate_A=0.71),
                       dict(base, rule="e", trades_A=39), dict(base, rule="f", worst_month_A=-13001.0)])
    assert F.select(df) == ["b", "a", "d"]        # ranked by the lower of the two win rates (b 75%, a 72%, d 71%)


def test_forward_scorer_refuses_history_and_scores_forward():
    legs = {(22500.0, "C"): flat(30.0), (22000.0, "P"): flat(30.0)}         # EM 250 -> the strangle 5 strikes out
    old = make_day(straddle_paths(extra=legs), day=date(2026, 10, 13), expiry=True)
    new = make_day(straddle_paths(extra=legs), day=date(2026, 10, 27), expiry=True)
    out = F.score(days=[old], rules=[F.BASE.name])
    assert out.trades.iloc[0] == 0 and not out.passes.iloc[0]
    out2 = F.score(days=[old, new], rules=[F.BASE.name])
    assert out2.trades.iloc[0] == 1 and not out2.decision_due.iloc[0]
    assert F.binom_p(26, 40) == pytest.approx(0.0403, abs=1e-3) and F.binom_p(0, 0) == 1.0


# ------------------------------------------------------------------------------------------------ idea 1 (PREREG_VRP)
import r103vrp as V  # noqa: E402


def test_rv20_uses_only_previous_closes(tmp_path):
    days = pd.bdate_range("2025-01-01", periods=40)
    closes = 100 * np.exp(np.cumsum(np.r_[0, np.tile([0.01, -0.01], 19), 0.5]))      # a huge jump on the last day
    df = pd.DataFrame(dict(symbol="NIFTY", date=days, open=closes, high=closes, low=closes, close=closes, volume=0))
    (tmp_path / "index_daily").mkdir()
    df.to_parquet(tmp_path / "index_daily" / "index_daily.parquet")
    rv = V.rv20("NIFTY", tmp_path)
    last = days[-1].date()
    assert rv[last] == pytest.approx(np.std(np.tile([0.01, -0.01], 10), ddof=1) * np.sqrt(252))   # the jump is not in it
    assert np.isnan(rv[days[5].date()])


def test_implied_sources_and_ratio():
    d = make_day(straddle_paths(110.0, 90.0))
    d.dte = 2
    t = V.ENTRY
    T2 = S.years_left(2)[t]
    want = np.mean([S.implied_vol(110.0, 22250.0, 22250.0, T2, True), S.implied_vol(90.0, 22250.0, 22250.0, T2, False)])
    assert V.implied(d, t, "ATMIV") == pytest.approx(want)
    assert V.implied(d, t, "VIX") == pytest.approx(0.14)
    assert V.ratio(d, t, "VIX", {d.day: 0.10}) == pytest.approx(1.4)
    assert np.isnan(V.ratio(d, t, "VIX", {}))
    m = masked(d, t, 1e6)                                     # nothing after the entry minute is read
    assert V.implied(m, t, "ATMIV") == pytest.approx(want) and V.implied(m, t, "VIX") == pytest.approx(0.14)


def test_twin_p():
    x = np.array([1.0] * 10 + [-1.0] * 10)
    assert V.twin_p(x, 10, 10.0, 1) < 0.01                    # picking all the winners is rare
    assert V.twin_p(x, 10, -10.0, 1) > 0.99
    assert V.twin_p(x, 0, 0.0, 1) == 1.0
