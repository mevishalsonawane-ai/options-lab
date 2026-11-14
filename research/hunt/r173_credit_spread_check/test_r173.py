"""R173 tests: the spread engine, costs, margin walk, ports of Boss's rules, and the look-ahead guard.

    python -m pytest research/hunt/r173_credit_spread_check -q
"""
from __future__ import annotations

import importlib.util
import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import r173real as R  # noqa: E402
import r171data as D  # noqa: E402


def flat_ix(px=25000.0):
    a = np.full((375, 4), px)
    return a


def leg(px, vol=1000.0):
    a = np.full((375, 5), float(px))
    a[:, 4] = vol
    return a


def make_book(und="NIFTY", days=3, px=25000.0, step=50.0):
    raws, Is = [], []
    d0 = date(2025, 1, 6)
    for k in range(days):
        d = D.Day(day=d0 + timedelta(days=k), und=und, ix=flat_ix(px), exp=d0 + timedelta(days=7), lot=75)
        Is.append(R.their_I(d.ix))
        raws.append(d)
    return R.Book(und, [d.day for d in raws], Is, raws, set())


# ------------------------------------------------------------------------------------------------ strikes / exits
def test_strikes_put_below_for_up_break_and_call_above_for_down_break():
    assert R.strikes(25012.0, 1, 50.0) == (24900.0, 24750.0, "P")
    assert R.strikes(25012.0, -1, 50.0) == (25100.0, 25250.0, "C")
    assert R.strikes(51049.0, 1, 100.0) == (50800.0, 50500.0, "P")


def test_index_exit_order_catastrophe_target_newlevel_squareoff():
    I = R.their_I(flat_ix(100.0))
    I["low"][50] = 80.0
    I["high"][50] = 130.0                       # both in the same minute: catastrophe wins (their order)
    assert R.index_exit(I, 10, 1, 120.0, 90.0) == (50, "catastrophe")
    I2 = R.their_I(flat_ix(100.0))
    I2["high"][40] = 121.0
    assert R.index_exit(I2, 10, 1, 120.0, 90.0) == (40, "target")
    assert R.index_exit(R.their_I(flat_ix(100.0)), 10, 1, 120.0, 90.0, newlvl=(29,)) == (29, "new_level")
    assert R.index_exit(R.their_I(flat_ix(100.0)), 10, 1, 120.0, 90.0, newlvl=(5,)) == (355, "sq")   # before entry: ignored
    I3 = R.their_I(flat_ix(100.0))
    I3["low"][30] = 79.0                        # down break: target below, stop above
    assert R.index_exit(I3, 10, -1, 80.0, 110.0) == (30, "target")


# ------------------------------------------------------------------------------------------------ the spread on prices
def test_spread_pnl_by_hand_with_r17_fills_and_dated_charges():
    B = make_book()
    d = B.raw[0]
    s, w = leg(60.0), leg(20.0)
    s[100:, :4] = 40.0                          # short decays 60 -> 40, wing 20 -> 12
    w[100:, :4] = 12.0
    d.opts[(24900.0, "P")] = s
    d.opts[(24750.0, "P")] = w
    ent = dict(und="NIFTY", day=d.day, k=0, e=50, side=1, ix0=25000.0, target=25048.0, stop=24932.5, newlvl=())
    t = R.simulate(ent, B, "x")
    assert t.fill == 50 and t.why == "sq" and t.xfill == 355
    assert (t.s_in, t.w_in, t.s_out, t.w_out) == (60.0, 20.0, 40.0, 12.0)
    lot, dn = 75, R.dnum(d.day)
    # 5-min volume 5000 units = 66.7 lots >= 20 -> 1 tick; 5 bps
    fs_in, fw_in = round(60 * (1 - 5e-4) - 0.05, 2), round(20 * (1 + 5e-4) + 0.05, 2)
    fs_out, fw_out = round(40 * (1 + 5e-4) + 0.05, 2), round(12 * (1 - 5e-4) - 0.05, 2)
    ch = (R.charges(True, fs_in, lot, dn) + R.charges(False, fw_in, lot, dn)
          + R.charges(False, fs_out, lot, dn) + R.charges(True, fw_out, lot, dn))
    want = ((fs_in - fs_out) - (fw_in - fw_out)) * lot - ch
    assert t.net == pytest.approx(want, abs=0.02)
    assert t.gross_pts == pytest.approx(12.0)
    # STT is on the two SELL orders only, brokerage Rs 20 x 4 orders: charges > Rs 80 and < Rs 80 + 1% of turnover
    assert 80 < ch < 80 + 0.01 * (fs_in + fw_in + fs_out + fw_out) * lot
    # margin: max loss (150 - credit) x lot + 2% of spot x lot
    assert t.margin == pytest.approx((150 - (fs_in - fw_in)) * lot + 0.02 * 25000 * lot)


def test_spread_loses_when_short_rises_and_catastrophe_is_costed_as_a_stop():
    B = make_book()
    d = B.raw[0]
    s, w = leg(60.0), leg(20.0)
    s[81:, :4] = 140.0
    w[81:, :4] = 55.0
    d.opts[(24900.0, "P")] = s
    d.opts[(24750.0, "P")] = w
    B.I[0]["low"][80] = 24900.0                 # index through the catastrophe level in minute 80 -> out at 81's open
    ent = dict(und="NIFTY", day=d.day, k=0, e=50, side=1, ix0=25000.0, target=25048.0, stop=24932.5, newlvl=())
    t = R.simulate(ent, B, "x")
    assert t.why == "catastrophe" and t.xm == 80 and t.xfill == 81
    assert t.net < (60 - 140 - 20 + 55) * 75     # worse than the raw loss: costs and stop slippage
    assert t.s_out == 140.0 and t.w_out == 55.0


def test_missing_strike_or_no_print_is_not_a_trade():
    B = make_book()
    d = B.raw[0]
    ent = dict(und="NIFTY", day=d.day, k=0, e=50, side=1, ix0=25000.0, target=25048.0, stop=24932.5, newlvl=())
    assert R.simulate(ent, B, "x") == "no_strike"
    s, w = leg(60.0), leg(20.0)
    w[48:60, :4] = np.nan                       # the wing has no print at 50, 51, 52
    d.opts[(24900.0, "P")] = s
    d.opts[(24750.0, "P")] = w
    assert R.simulate(ent, B, "x") == "no_fill"
    w[52, :4] = 20.0                            # a print at 52 (within the slack): fills there
    assert R.simulate(ent, B, "x").fill == 52


def test_stale_exit_uses_last_close_and_is_flagged():
    B = make_book()
    d = B.raw[0]
    s, w = leg(60.0), leg(20.0)
    w[300:, :4] = np.nan
    w[299, 3] = 9.0
    d.opts[(24900.0, "P")] = s
    d.opts[(24750.0, "P")] = w
    ent = dict(und="NIFTY", day=d.day, k=0, e=50, side=1, ix0=25000.0, target=25048.0, stop=24932.5, newlvl=())
    t = R.simulate(ent, B, "x")
    assert t.stale_exit and t.w_out == 9.0


# ------------------------------------------------------------------------------------------------ account walk
class _T:
    def __init__(self, day, fill, xfill, margin, und="NIFTY"):
        self.day, self.fill, self.xfill, self.margin, self.margin_r103, self.und = day, fill, xfill, margin, margin, und


def test_account_takes_only_what_fits_and_frees_margin_on_exit():
    d = date(2025, 1, 6)
    ts = [_T(d, 10, 100, 40_000), _T(d, 20, 50, 20_000), _T(d, 100, 200, 40_000), _T(d + timedelta(1), 10, 20, 50_000)]
    taken, miss = R.account(ts, 56_000)
    assert [(t.fill, t.margin) for t in taken] == [(10, 40_000), (100, 40_000), (10, 50_000)]
    assert miss == 1
    assert R.account([], 56_000) == ([], 0)


# ------------------------------------------------------------------------------------------------ ports of Boss's rules
def _their(name):
    spec = importlib.util.spec_from_file_location(name, HERE / "inputs" / f"{name}.py")
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


def _random_book(und, days, seed, px):
    rng = np.random.default_rng(seed)
    raws, Is = [], []
    c = px
    for k in range(days):
        a = np.empty((375, 4))
        for r in range(375):
            o = c
            c = o + rng.normal(0, px * 4e-4)
            a[r] = (o, max(o, c) + abs(rng.normal(0, px * 1e-4)), min(o, c) - abs(rng.normal(0, px * 1e-4)), c)
        d = D.Day(day=date(2025, 1, 1) + timedelta(days=k), und=und, ix=a, lot=75)
        raws.append(d)
        Is.append(R.their_I(a))
    return R.Book(und, [d.day for d in raws], Is, raws, set())


def test_r72_breadth_signals_match_their_build_panel_signals():
    m = _their("Reproduce_R72_Breadth_Backtest")
    m.UNDS = list(R.BREADTH_UNDS)
    seeds = {"NIFTY": 1, "BANKNIFTY": 1, "FINNIFTY": 1, "SENSEX": 1}   # same paths -> breadth fires often
    bk = {u: _random_book(u, 6, s, 1000.0) for u, s in seeds.items()}
    mine = R.r72_signals(bk)
    panel = {}
    for u in R.BREADTH_UNDS:
        b = R.bars5(bk[u].I)
        dd = np.repeat(np.array([str(x) for x in bk[u].days]), 75)
        bb = np.tile(np.arange(75), len(bk[u].days))
        panel[u] = (dd, bb, b[:, 0], b[:, 1], b[:, 2], b[:, 3])
    theirs = m.build_panel_signals(panel, M=4)
    for u in R.BREADTH_UNDS:
        assert [(str(d), b, s) for d, b, s in mine[u]] == [(d, b, s) for d, b, s, _, _ in theirs[u]]
    assert sum(len(v) for v in mine.values()) > 0


def test_their_liquidity_port_matches_indicator_liquidity():
    from indicator import liquidity as L
    m = _their("Reproduce_R52_Liquidity_Backtest")
    bk = _random_book("NIFTY", 8, 7, 1000.0)
    days = [{"day": str(d), "I": I} for d, I in zip(bk.days, bk.I)]
    b15 = m.bars(days, 15)
    a = [(z.kind, z.side, z.top, z.bottom, z.known, z.broken) for z in m.swing_zones(b15) + m.pool_zones(b15)]
    b = [(z.kind, z.side, z.top, z.bottom, z.known, z.broken) for z in L.swing_zones(b15, 20, "full") + L.pool_zones(b15, 2, 5, 10)]
    assert a == b and len(a) > 0


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_r72_entries_do_not_read_index_minutes_at_or_after_the_fill():
    bk = {u: _random_book(u, 6, 1, 1000.0) for u in R.BREADTH_UNDS}
    for u in R.TRADED:
        bk[u].exp_days = set()
    sigs = R.r72_signals(bk)
    ents = R.r72_entries(bk, sigs, date(2025, 1, 1), date(2025, 1, 31))
    assert ents
    for en in ents[:5]:
        bk2 = {u: _random_book(u, 6, 1, 1000.0) for u in R.BREADTH_UNDS}
        for u in R.TRADED:
            bk2[u].exp_days = set()
        for u in R.BREADTH_UNDS:           # scramble everything from the fill minute on (open at e kept: the fill price)
            k = bk2[u].days.index(en["day"])
            I = bk2[u].I[k]
            for f in ("high", "low", "close"):
                I[f][en["e"]:] = I[f][en["e"]:] * 1.3
            I["open"][en["e"] + 1:] *= 0.7
            for kk in range(k + 1, len(bk2[u].I)):
                for f in ("open", "high", "low", "close"):
                    bk2[u].I[kk][f][:] *= 1.5
        sig2 = R.r72_signals(bk2)
        e2 = R.r72_entries(bk2, sig2, date(2025, 1, 1), date(2025, 1, 31))
        key = lambda x: (x["und"], x["day"], x["e"], x["side"], x["ix0"])   # noqa: E731
        assert key(en) in {key(x) for x in e2}


def test_liq_entries_do_not_read_index_minutes_after_the_fill():
    bk = {"NIFTY": _random_book("NIFTY", 12, 3, 1000.0)}
    ents = R.liq_entries(bk, date(2025, 1, 1), date(2025, 1, 31), und_list=("NIFTY",))
    assert ents
    en = ents[len(ents) // 2]
    bk2 = {"NIFTY": _random_book("NIFTY", 12, 3, 1000.0)}
    k = bk2["NIFTY"].days.index(en["day"])
    I = bk2["NIFTY"].I[k]
    for f in ("high", "low", "close"):
        I[f][en["e"]:] *= 1.2
    I["open"][en["e"] + 1:] *= 1.2
    for kk in range(k + 1, 12):
        for f in ("open", "high", "low", "close"):
            bk2["NIFTY"].I[kk][f][:] *= 0.8
    e2 = R.liq_entries(bk2, date(2025, 1, 1), date(2025, 1, 31), und_list=("NIFTY",))
    key = lambda x: (x["day"], x["e"], x["side"], x["ix0"], x["level"])   # noqa: E731
    assert key(en) in {key(x) for x in e2}


# ------------------------------------------------------------------------------------------------ descriptive helpers
def test_bs_delta_sane():
    # ATM call, 7 days, 15% vol: price ~ 0.4 * S * sigma * sqrt(T)
    S, t = 25000.0, 7 / 365
    p = 0.3989 * S * 0.15 * math.sqrt(t)
    assert R.bs_delta(p, S, S, "C", t) == pytest.approx(0.5, abs=0.02)
    assert R.bs_delta(p, S, S, "P", t) == pytest.approx(-0.5, abs=0.02)
    assert math.isnan(R.bs_delta(0.0, S, S, "C", t))


def test_their_model_has_no_wing_and_fixed_greeks():
    """The audit's core finding as a test: their spread price does not depend on the strike or the days to expiry."""
    m = _their("Reproduce_R72_Breadth_Backtest")
    I = {"open": np.full(375, 25000.0)}
    a = m.option_path_1m(I, 50, 1, 14.0, dte_days=3.0, moneyness="OTM_SPREAD")
    b = m.option_path_1m(I, 50, -1, 14.0, dte_days=3.0, moneyness="OTM_SPREAD")
    assert a == b and a[1] == 0.28 and a[3] == pytest.approx(a[0] * 0.22 / 375)


def test_summary_helpers():
    import r173parity as P
    t = pd.DataFrame(dict(day=["2024-12-02", "2024-12-02", "2025-05-02"], und="NIFTY", pts=[1, -1, 2], rs=[100.0, -50.0, 20.0]))
    s = P.summary(t)
    assert s["n"] == 3 and s["net"] == 70 and s["design"] == 50 and s["walkforward"] == 20 and s["max_dd"] == 0
    import r173study as S
    t2 = pd.DataFrame(dict(day=[date(2024, 12, 2), date(2024, 12, 3), date(2025, 1, 2)], net=[100.0, -150.0, 20.0]))
    s2 = S.summ(t2)
    assert s2["max_dd"] == -150 and s2["worst_month"] == -50 and s2["worst_day"] == -150
    assert S.bh_q(0.00001) == pytest.approx(0.03915)


# ------------------------------------------------------------------------------------------------ Amendment B
def test_twap_side_uses_only_closes_before_the_fill_minute():
    import r173b as B
    ix = np.full((375, 4), 100.0)
    ix[:10, 3] = 90.0                           # closes before minute 10 average 90
    ix[10, 0] = 95.0
    assert B.twap_side(ix, 10, 1) is True and B.twap_side(ix, 10, -1) is False
    ix2 = ix.copy()
    ix2[10:, 3] = 1e6                           # closes at / after the fill minute must not matter
    assert B.twap_side(ix2, 10, 1) is True
    ix3 = ix.copy()
    ix3[10, 0] = np.nan
    assert B.twap_side(ix3, 10, 1) is None and B.twap_side(ix, 0, 1) is None


def test_variant_strike_pickers():
    import r173b as B
    assert B.pick_wide(25012.0, 1, 50.0, None, 50) == (24900.0, 24600.0, "P")
    assert B.pick_debit(25012.0, 1, 50.0, None, 50) == (25150.0, 25000.0, "C")     # short further OTM, long ATM
    assert B.pick_debit(25012.0, -1, 50.0, None, 50) == (24850.0, 25000.0, "P")


def test_pick_delta_chooses_strike_nearest_028_from_prices_before_entry():
    import r173b as B
    from scipy.stats import norm
    d = D.Day(day=date(2025, 1, 6), und="NIFTY", ix=flat_ix(25000.0), exp=date(2025, 1, 9), lot=75)
    S0, sig = 25000.0, 0.15
    t = 3 / 365 + (375 - 50) / 375 * 6.25 / 24 / 365
    for k in np.arange(24500.0, 25000.0, 50.0):
        sd = sig * math.sqrt(t)
        d1 = (math.log(S0 / k) + 0.5 * sd * sd) / sd
        p = k * norm.cdf(-(d1 - sd)) - S0 * norm.cdf(-d1)
        d.opts[(k, "P")] = leg(p)
        d.opts[(k, "P")][50:, :4] = 999.0      # prices from the entry minute on are ignored
    ks, kw, r = B.pick_delta(S0, 1, 50.0, d, 50)
    want = min(np.arange(24500.0, 25000.0, 50.0), key=lambda k: abs(abs(R.bs_delta(
        float(d.opts[(k, "P")][49, 3]), S0, k, "P", t)) - 0.28))
    assert (ks, kw, r) == (want, want - 150.0, "P")


def test_debit_spread_margin_is_debit_plus_exposure():
    import r173b as B
    bk = make_book()
    d = bk.raw[0]
    d.opts[(25150.0, "C")] = leg(30.0)
    d.opts[(25000.0, "C")] = leg(90.0)
    ent = dict(und="NIFTY", day=d.day, k=0, e=50, side=1, ix0=25000.0, target=25048.0, stop=24932.5, newlvl=())
    t = R.simulate(ent, bk, "x", B.pick_debit)
    debit = t.w_in * (1 + 5e-4) + 0.05 - (t.s_in * (1 - 5e-4) - 0.05)
    assert t.ks == 25150.0 and t.kw == 25000.0
    assert t.margin == pytest.approx(debit * 75 + 0.02 * 25000 * 75, abs=1.0)


def test_random_skip_p_is_small_for_a_clearly_better_subset_and_large_for_a_worse_one():
    import r173b as B
    rng = np.random.default_rng(0)
    x = np.r_[np.full(50, 100.0), np.full(50, -100.0)]
    keep = x > 0
    p_n, p_mc = B.random_skip_p(x, keep, rng)
    assert p_n < 1e-6 and p_mc < 0.01
    p_n2, p_mc2 = B.random_skip_p(x, ~keep, rng)
    assert p_n2 > 0.99 and p_mc2 > 0.99
