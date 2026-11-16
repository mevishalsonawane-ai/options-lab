"""R189 tests: Pine's WMA and both Hull lines, colours, the entry conditions (CLOSE / LOW), the fresh-signal rule, the
walk (09:20 / 15:00 window, one position per index, busy signals dropped, no-fills), the strike rule, every exit
candidate's exit minute and pricing, the expiry-day square-off, the study helpers (drawdown, months, BH, gates, the exit
choice and its walk-forward, the twin sampler) and the look-ahead guard. Synthetic data only: no Dhan files, no stores.
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
pytest.importorskip("numba")
import r189cfg as K  # noqa: E402
import r189lib as Z  # noqa: E402
import r189study as S  # noqa: E402

NAN = np.nan


# ------------------------------------------------------------------------------------------------ synthetic data
def _trend_close(n_days, slope=0.5, seed=0, noise=0.0, start=22000.0):
    rng = np.random.default_rng(seed)
    steps = slope + rng.normal(0, noise, n_days * K.N) if noise else np.full(n_days * K.N, slope)
    return start + np.cumsum(steps)


def _und(closes=None, n_days=3, slope=0.5, seed=0, noise=0.0, start=date(2024, 12, 2), exp=None, opts=True,
         lot=75, vol=1000.0, spread=1.0):
    """An Und of n_days sessions from one continuous close path; candles: open = previous close, high / low = max /
    min +- spread. Options: every 50-strike within +-500 of the day's closes, price = intrinsic + 50, flat volume."""
    c = _trend_close(n_days, slope, seed, noise) if closes is None else np.asarray(closes, float)
    n_days = len(c) // K.N
    o = np.r_[c[0], c[:-1]]
    hi = np.maximum(o, c) + spread
    lo = np.minimum(o, c) - spread
    ix = np.stack([o, hi, lo, c], axis=1).reshape(n_days, K.N, 4)
    days, d = [], start
    while len(days) < n_days:
        if d.weekday() < 5:
            days.append(d)
        d += timedelta(days=1)
    Ss, keys = [], []
    for di in range(n_days):
        if not opts:
            Ss.append(None)
            keys.append({})
            continue
        cc = ix[di, :, 3]
        mats, kk = [], {}
        lo_k = Z.atm_strike(float(np.nanmin(cc)), 50.0) - 500
        hi_k = Z.atm_strike(float(np.nanmax(cc)), 50.0) + 500
        for s in "CP":
            for k in np.arange(lo_k, hi_k + 1, 50.0):
                intr = np.maximum(cc - k, 0) if s == "C" else np.maximum(k - cc, 0)
                p = np.round(intr + 50.0, 2)
                A = np.stack([p, p + 1, np.maximum(p - 1, 0.05), p, np.full(K.N, vol)])
                kk[(s, float(k))] = len(mats)
                mats.append(A)
        Ss.append(np.stack(mats))
        keys.append(kk)
    ex = np.zeros(n_days, bool) if exp is None else np.asarray(exp, bool)
    return Z.make_und("NIFTY", days, ix, ex, np.full(n_days, lot), Ss, keys)


# ------------------------------------------------------------------------------------------------ the Hull line
def test_lengths_are_pines():
    assert K.HMA_HALF == 27            # Pine: 55 / 2 with an int input
    assert K.HMA_SQRT == 7             # math.round(math.sqrt(55))


def test_wma_pine_weights_and_warmup():
    x = np.array([1.0, 2.0, 3.0, 4.0])
    w = Z.wma(x, 3)
    assert np.isnan(w[:2]).all()
    assert w[2] == pytest.approx((1 * 1 + 2 * 2 + 3 * 3) / 6)
    assert w[3] == pytest.approx((2 * 1 + 3 * 2 + 4 * 3) / 6)


def test_wma_nan_in_window_and_short():
    x = np.array([1.0, NAN, 3.0, 4.0, 5.0])
    w = Z.wma(x, 2)
    assert np.isnan(w[1]) and np.isnan(w[2]) and w[3] == pytest.approx((3 + 8) / 3)
    assert np.isnan(Z.wma(np.array([1.0]), 3)).all()
    assert len(Z.wma(np.array([]), 3)) == 0


def test_hull_boss_vs_standard_formula():
    rng = np.random.default_rng(3)
    c = 100 + np.cumsum(rng.normal(0, 1, 300))
    b = Z.hull(c, "BOSS")
    s = Z.hull(c, "STD")
    w55, w27 = Z.wma(c, 55), Z.wma(c, 27)
    assert np.allclose(b, Z.wma(2 * w55 - w27, 7), equal_nan=True)
    assert np.allclose(s, Z.wma(2 * w27 - w55, 7), equal_nan=True)
    assert np.isnan(b[:60]).all() and np.isfinite(b[60:]).all()     # 55 + 7 - 2 = 60 bars of warm-up
    assert np.isfinite(s[60:]).all()


def test_boss_line_lags_more_than_standard_on_a_ramp():
    c = np.arange(400, dtype=float)
    b, s = Z.hull(c, "BOSS"), Z.hull(c, "STD")
    # WMA(n) lags a ramp by (n - 1) / 3 bars: WMA55 18, WMA27 8.67, WMA7 2. Standard: 2(c - 8.67) - (c - 18) - 2
    # = c - 1.33. Boss's: 2(c - 18) - (c - 8.67) - 2 = c - 29.33, more than a plain WMA55's 18.
    assert c[-1] - s[-1] == pytest.approx(4 / 3)
    assert c[-1] - b[-1] == pytest.approx(88 / 3)
    assert c[-1] - Z.wma(c, 55)[-1] == pytest.approx(18)


def test_direction_colours():
    d = Z.direction(np.array([NAN, 1.0, 2.0, 2.0, 1.5, NAN, 3.0]))
    assert list(d) == [0, 0, 1, 0, -1, 0, 0]


def test_lines_skip_missing_minutes_across_days():
    U = _und(n_days=2)
    ix2 = U.ix.copy()
    ix2[0, 100] = NAN                   # a missing minute is skipped, not filled
    L = Z.lines_of(ix2)
    h, d = L["BOSS"]
    assert np.isnan(h[0, 100]) and d[0, 100] == 0
    flat = ix2[:, :, 3][np.isfinite(ix2[:, :, 3])]
    ref = Z.hull(flat, "BOSS")
    assert h[1, 0] == pytest.approx(ref[K.N - 1])   # day 2's first minute continues day 1's series


# ------------------------------------------------------------------------------------------------ entry conditions
def test_conditions_close_and_low_and_colour():
    ix = np.full((1, 6, 4), NAN)
    c = np.array([10, 12, 12, 9, 12, 12.0])
    ix[0, :, 3] = c
    ix[0, :, 2] = [9, 11, 9.5, 8, 11, 11]
    ix[0, :, 1] = c + 1
    h = np.array([[10, 10, 10, 10, 10, 10.0]])
    dr = np.array([[1, 1, 1, 1, 1, -1]], np.int8)
    cC, cP = Z.conditions(ix, h, dr, "close")
    assert list(cC[0]) == [False, False, True, False, False, False]   # t=5: red line blocks the call
    lC, _ = Z.conditions(ix, h, dr, "low")
    assert list(lC[0]) == [False, False, False, False, False, False]  # t=2: candle 2's low (9.5) is below the line
    # puts mirror
    ix[0, :, 3] = [10, 8, 8, 8, 8, 8.0]
    ix[0, :, 1] = [11, 9, 9, 9, 10.5, 9]
    dr2 = -np.ones((1, 6), np.int8)
    _, cP = Z.conditions(ix, h, dr2, "close")
    assert list(cP[0]) == [False, False, True, True, True, True]
    _, lP = Z.conditions(ix, h, dr2, "low")
    assert list(lP[0]) == [False, False, True, True, False, False]    # t=4's high 10.5 is above the line


def test_conditions_need_both_candles_present():
    ix = np.full((1, 4, 4), NAN)
    ix[0, :, 3] = [12, NAN, 12, 12]
    h = np.full((1, 4), 10.0)
    dr = np.ones((1, 4), np.int8)
    cC, _ = Z.conditions(ix, h, dr, "close")
    assert list(cC[0]) == [False, False, False, True]


def test_fresh_needs_false_to_true():
    c = np.array([[False, True, True, False, True, True]])
    assert list(Z.fresh(c)[0]) == [False, True, False, False, True, False]
    assert list(Z.fresh(np.array([[True, True]]))[0]) == [True, False]


# ------------------------------------------------------------------------------------------------ strikes
def test_strike_rule():
    assert Z.atm_strike(22024.9, 50) == 22000 and Z.atm_strike(22025, 50) == 22050
    assert Z.strike_for("C", 22010, 50, 1) == 21950 and Z.strike_for("P", 22010, 50, 1) == 22050
    assert Z.strike_for("C", 22010, 50, 0) == 22000


# ------------------------------------------------------------------------------------------------ forced exits
def _day_arrays(n=40, base=100.0):
    c = np.full(n, base)
    o, h, l = c.copy(), c + 1, c - 1
    hull = np.full(n, base - 5)
    dr = np.ones(n, np.int8)
    return o, h, l, c, hull, dr


def test_forced_flip_and_cross():
    o, h, l, c, hull, dr = _day_arrays()
    dr[10] = -1
    assert Z.forced_exit(h, l, c, o, hull, dr, 1, 3, 4, 35, 1, False, 0.0) == (11, 1)
    c2 = c.copy()
    c2[7] = 90.0
    assert Z.forced_exit(h, l, c2, o, hull, dr, 1, 3, 4, 35, 2, False, 0.0) == (8, 1)
    assert Z.forced_exit(h, l, c2, o, hull, dr, 1, 3, 4, 35, 3, False, 0.0) == (8, 1)
    # a put: blue is against it; the close above the line exits
    assert Z.forced_exit(h, l, c, o, hull, dr, -1, 3, 4, 35, 1, False, 0.0) == (5, 1)
    assert Z.forced_exit(h, l, c, o, hull, dr, -1, 3, 4, 35, 2, False, 0.0) == (5, 1)
    # no exit before the square-off
    assert Z.forced_exit(h, l, c, o, hull, np.ones(40, np.int8), 1, 3, 4, 35, 1, False, 0.0) == (-1, 0)


def test_forced_index_stop_and_targets():
    o, h, l, c, hull, dr = _day_arrays()
    l[3] = 97.0                                   # signal candle low = stop; entry at o[4] = 100 -> R = 3
    l2 = l.copy()
    l2[9] = 96.5
    assert Z.forced_exit(h, l2, c, o, hull, dr, 1, 3, 4, 35, 0, True, 0.0) == (10, 2)
    h2 = h.copy()
    h2[6] = 104.6                                 # 1.5R target = 104.5
    assert Z.forced_exit(h2, l2, c, o, hull, dr, 1, 3, 4, 35, 0, True, 1.5) == (7, 3)
    assert Z.forced_exit(h2, l2, c, o, hull, dr, 1, 3, 4, 35, 0, True, 2.0) == (10, 2)   # 2R = 106 not reached
    h3 = h2.copy()
    l3 = l2.copy()
    l3[6] = 96.0                                  # stop and target in one minute: the stop
    assert Z.forced_exit(h3, l3, c, o, hull, dr, 1, 3, 4, 35, 0, True, 1.5) == (7, 2)


def test_forced_index_stop_r_nonpositive_fires_at_once():
    o, h, l, c, hull, dr = _day_arrays()
    l[3] = 101.0                                  # entry 100 already under the stop: R <= 0, no target
    assert Z.forced_exit(h, l, c, o, hull, dr, 1, 3, 4, 35, 0, True, 2.0) == (5, 2)


def test_forced_index_stop_put_and_missing_minute():
    o, h, l, c, hull, dr = _day_arrays()
    h[3] = 102.0
    h[5] = NAN
    l[5] = NAN
    h[6] = 102.5
    assert Z.forced_exit(h, l, c, o, hull, dr, -1, 3, 4, 35, 0, True, 0.0) == (7, 2)


# ------------------------------------------------------------------------------------------------ pricing
def _contract(n=K.N, price=100.0, vol=1000.0):
    p = np.full(n, price)
    return np.stack([p, p + 1, p - 1, p, np.full(n, vol)])


def test_run_batch_premium_stop_target_time_and_nofill():
    A = _contract()
    A[2, 20] = 80.0                               # -15% stop at 85 hit on row 20
    S_ = A[None]
    n = K.N
    ix = np.full(n, 22000.0)
    hull = np.full(n, 21990.0)
    dr = np.ones(n, np.int8)
    args = (ix, ix, ix, ix, hull, dr, K.SQ_ROW)
    res, er, xr, rs = Z.run_batch(S_, np.array([0]), np.array([9]), np.array([1]), *args, 0, False, 0.0, 0.15, -1.0,
                                  np.zeros((0, 2)), -1, 0.05, 75.0, *Z.E51.SLIP)
    assert er[0] == 10 and xr[0] == 20 and rs[0] == 1 and res[0, 1] == pytest.approx(85.0)
    # +10% target before the stop
    B = A.copy()
    B[1, 15] = 111.0
    res, er, xr, rs = Z.run_batch(B[None], np.array([0]), np.array([9]), np.array([1]), *args, 0, False, 0.0, 0.15,
                                  0.10, np.zeros((0, 2)), -1, 0.05, 75.0, *Z.E51.SLIP)
    assert xr[0] == 15 and rs[0] == 3 and res[0, 3] == pytest.approx(110.0)
    # time stop 5: the close of the 5th minute held
    res, er, xr, rs = Z.run_batch(A[None], np.array([0]), np.array([9]), np.array([1]), *args, 0, False, 0.0, 0.15,
                                  -1.0, np.zeros((0, 2)), 5, 0.05, 75.0, *Z.E51.SLIP)
    assert xr[0] == 14 and rs[0] == 4
    # no trade in the 3-minute slack: no fill
    C_ = A.copy()
    C_[0, 10:13] = NAN
    res, er, xr, rs = Z.run_batch(C_[None], np.array([0]), np.array([9]), np.array([1]), *args, 0, False, 0.0, 0.15,
                                  -1.0, np.zeros((0, 2)), -1, 0.05, 75.0, *Z.E51.SLIP)
    assert rs[0] == 0
    res, er, xr, rs = Z.run_batch(A[None], np.array([-1]), np.array([9]), np.array([1]), *args, 0, False, 0.0, 0.15,
                                  -1.0, np.zeros((0, 2)), -1, 0.05, 75.0, *Z.E51.SLIP)
    assert rs[0] == 0


def test_run_batch_index_stop_is_a_stop_sale_and_line_exit_market():
    A = _contract()
    n = K.N
    ih = np.full(n, 22001.0)
    il = np.full(n, 21999.0)
    ic = np.full(n, 22000.0)
    il[9] = 21995.0                               # the signal candle's low = the index stop
    il[30] = 21990.0                              # under it
    hull = np.full(n, 21990.0)
    dr = np.ones(n, np.int8)
    st = Z.run_batch(A[None], np.array([0]), np.array([9]), np.array([1]), ih, il, ic, ic, hull, dr, K.SQ_ROW, 0, True,
                     0.0, 0.15, -1.0, np.zeros((0, 2)), -1, 0.05, 75.0, *Z.E51.SLIP)
    assert st[2][0] == 31 and st[3][0] == 12
    stop_sale = st[0][0, 3]
    dr2 = dr.copy()
    dr2[30] = -1
    ln = Z.run_batch(A[None], np.array([0]), np.array([9]), np.array([1]), ih, ih, ic, ic, hull, dr2, K.SQ_ROW, 1,
                     False, 0.0, 0.15, -1.0, np.zeros((0, 2)), -1, 0.05, 75.0, *Z.E51.SLIP)
    assert ln[2][0] == 31 and ln[3][0] == 11
    assert ln[0][0, 3] > stop_sale                 # market sale beats a stop sale at the same raw price


def test_run_batch_step_lock():
    A = _contract()
    A[1, 15] = 125.0                              # best +25% -> LOCK50 keeps +10% (110)
    A[:4, 16:25] = np.array([115.0, 116.0, 112.0, 115.0])[:, None]
    A[2, 20] = 105.0                              # through the lock: sold at the lock level (open 115 above it)
    steps = np.array(K.LOCK50)
    n = K.N
    ix = np.full(n, 22000.0)
    res, er, xr, rs = Z.run_batch(A[None], np.array([0]), np.array([9]), np.array([1]), ix, ix, ix, ix,
                                  np.full(n, 21990.0), np.ones(n, np.int8), K.SQ_ROW, 0, False, 0.0, 0.15, -1.0,
                                  steps, -1, 0.05, 75.0, *Z.E51.SLIP)
    assert xr[0] == 20 and rs[0] == 2 and res[0, 1] == pytest.approx(110.0)


def test_lock_tables():
    assert K.LOCK50[0] == (0.2, 0.1) and K.LOCK50[-1] == (5.0, 2.5)
    assert K.LOCK60[0] == (0.1, 0.06) and K.LOCK60[-1] == (5.0, 3.0)
    assert len(K.EXITS) == 16 and K.N_TESTS == 1026 and K.M_TOTAL == 4686 + 1026


def test_expiry_day_squares_off_at_1505():
    U = _und(n_days=3, exp=[False, False, True])
    assert U.sq(2) == K.SQ_ROW_EXPIRY == 350 and U.sq(0) == K.SQ_ROW == 355
    r = Z.price_day(U, 2, [100], ["C"], 0, "BOSS", "X04")
    assert r["ok"][0] and r["xr"][0] == 350 and Z.REASON[int(r["reason"][0])] == "squareoff"


def test_price_day_missing_strike_and_no_chain():
    U = _und(n_days=2)
    U.key[1] = {}
    r = Z.price_day(U, 1, [100], ["C"], 0, "BOSS", "X04")
    assert not r["ok"][0] and r["net"][0].tolist() == [0.0, 0.0, 0.0]
    U2 = _und(n_days=2, opts=False)
    assert not U2.tradable(1)


def test_net_scales_with_lots_and_pays_charges():
    U = _und(n_days=2, slope=0.0)
    r = Z.price_day(U, 1, [100], ["C"], 0, "BOSS", "X13")
    net = r["net"][0]
    assert net[0] < 0 and net[1] < net[0] and net[2] < net[1]      # flat price: costs only, growing with lots


# ------------------------------------------------------------------------------------------------ the walk
def test_walk_window_fresh_and_one_position():
    U = _und(n_days=3, slope=0.5)
    tr, nf = Z.walk(U, "BOSS-X04", ("BOSS", "close", 0, "X04"), date(2024, 11, 20), date(2026, 12, 31))
    assert tr, "a steady up-trend gives a call"
    for t in tr:
        assert K.FIRST_T <= t.t <= K.LAST_T and t.side == "C"
        assert t.fill >= t.t + 1
    # one position per index: entries never overlap
    by = {}
    for t in tr:
        by.setdefault(t.day, []).append(t)
    for ts in by.values():
        for a, b in zip(ts, ts[1:]):
            assert b.t + 1 >= a.exit
    # in a steady trend the condition stays true: only one fresh signal per day at most (no re-entry)
    assert all(len(v) <= 1 for v in by.values())


def test_walk_busy_signal_dropped_and_reentry_needs_fresh():
    rng = np.random.default_rng(5)
    U = _und(n_days=4, noise=3.0, slope=0.0, seed=5)
    tr, _ = Z.walk(U, "BOSS-X16", ("BOSS", "close", 0, "X16"), date(2024, 1, 1), date(2027, 1, 1))
    hh, dd = U.lines["BOSS"]
    cC, cP = Z.conditions(U.ix, hh, dd, "close")
    fC, fP = Z.fresh(cC), Z.fresh(cP)
    for t in tr:
        di = U.days.index(t.day)
        assert (fC if t.side == "C" else fP)[di, t.t]
    by = {}
    for t in tr:
        by.setdefault(t.day, []).append(t)
    for ts in by.values():
        for a, b in zip(ts, ts[1:]):
            assert b.t + 1 >= a.exit


def test_walk_counts_nofill_and_respects_dates():
    U = _und(n_days=3, slope=0.5)
    for di in range(3):
        U.key[di] = {}
    tr, nf = Z.walk(U, "BOSS-X04", ("BOSS", "close", 0, "X04"), date(2024, 1, 1), date(2027, 1, 1))
    assert tr == [] and nf >= 1
    U = _und(n_days=3, slope=0.5)
    tr, nf = Z.walk(U, "BOSS-X04", ("BOSS", "close", 0, "X04"), date(2030, 1, 1), date(2030, 2, 1))
    assert tr == [] and nf == 0


def test_walk_puts_in_a_down_trend_and_itm_strike():
    U = _und(n_days=3, slope=-0.5)
    tr, _ = Z.walk(U, "ITM-X04", ("BOSS", "close", 1, "X04"), date(2024, 1, 1), date(2027, 1, 1))
    assert tr and all(t.side == "P" for t in tr)
    t = tr[0]
    di = U.days.index(t.day)
    assert t.strike == Z.atm_strike(U.ix[di, t.t, 3], 50.0) + 50.0


def test_empty_und():
    U = Z.make_und("NIFTY", [], np.zeros((0, K.N, 4)), np.zeros(0, bool), np.zeros(0, int), [], [])
    assert Z.walk(U, "BOSS-X01", ("BOSS", "close", 0, "X01"), date(2024, 1, 1), date(2027, 1, 1)) == ([], 0)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_lookahead_decision_unchanged_by_future_noise():
    U = _und(n_days=3, noise=2.0, slope=0.1, seed=11)
    di, t = 2, 150
    base_l = Z.lines_of(U.ix)
    ix2 = U.ix.copy()
    rng = np.random.default_rng(99)
    ix2[di, t + 1:] += rng.normal(0, 50, ix2[di, t + 1:].shape)
    L2 = Z.lines_of(ix2)
    for name in K.LINES:
        assert np.allclose(base_l[name][0][di, :t + 1], L2[name][0][di, :t + 1])
        assert np.array_equal(base_l[name][1][di, :t + 1], L2[name][1][di, :t + 1])
        for reading in ("close", "low"):
            a = Z.conditions(U.ix, *base_l[name], reading)
            b = Z.conditions(ix2, *L2[name], reading)
            assert np.array_equal(a[0][di, :t + 1], b[0][di, :t + 1]) and np.array_equal(a[1][di, :t + 1], b[1][di, :t + 1])
    assert Z.strike_for("C", U.ix[di, t, 3], 50, 0) == Z.strike_for("C", ix2[di, t, 3], 50, 0)


@pytest.mark.parametrize("x", sorted(K.EXITS))
def test_lookahead_exit_unchanged_after_exit(x):
    U = _und(n_days=3, noise=2.0, slope=0.05, seed=21)
    di = 2
    hh, dd = U.lines["BOSS"]
    tt = [60, 120, 200]
    for side in "CP":
        r = Z.price_day(U, di, tt, [side] * 3, 0, "BOSS", x)
        for i, t in enumerate(tt):
            if not r["ok"][i]:
                continue
            xr = int(r["xr"][i])
            U2 = _und(n_days=3, noise=2.0, slope=0.05, seed=21)
            rng = np.random.default_rng(7)
            U2.ix[di, xr:] += rng.normal(0, 40, U2.ix[di, xr:].shape)
            for k in U2.key[di].values():
                U2.S[di][k][:4, xr + 1:] *= rng.uniform(0.5, 1.5)
            U2.lines = {name: (U.lines[name][0].copy(), U.lines[name][1].copy()) for name in K.LINES}
            L2 = Z.lines_of(U2.ix)
            for name in K.LINES:   # the line after the exit row is free to change; before it must not
                U2.lines[name][0][di, xr:] = L2[name][0][di, xr:]
                U2.lines[name][1][di, xr:] = L2[name][1][di, xr:]
            r2 = Z.price_day(U2, di, [t], [side], 0, "BOSS", x)
            assert r2["xr"][0] == xr
            assert r2["X"][0] == pytest.approx(r["X"][i])


# ------------------------------------------------------------------------------------------------ study helpers
def _t(day, net, und="NIFTY", side="C", reason="stop15"):
    return SimpleNamespace(day=day, net=(net, 2 * net, 3 * net), und=und, side=side, reason=reason)


def test_drawdown_months_stats():
    assert S.max_drawdown([]) == 0.0
    assert S.max_drawdown([100, -50, -80, 200, -10]) == 130
    assert S.max_drawdown([-10, -5]) == 15
    tr = [_t(date(2025, 1, 2), 100), _t(date(2025, 1, 2), -40), _t(date(2025, 2, 3), -300)]
    s = S.stats(tr, 0)
    assert s["trades"] == 3 and s["net"] == -240 and s["worst_day"] == -300 and s["worst_month"] == -300
    assert s["avg_win"] == 100 and s["avg_loss"] == -170 and s["win"] == pytest.approx(100 / 3)
    assert S.stats([], 0)["trades"] == 0
    assert S.stats(tr, 2)["net"] == -720


def test_select_and_folds():
    tr = [_t(date(2025, 1, 2), 10), _t(date(2025, 5, 2), 20, und="SENSEX", side="P"), _t(date(2026, 5, 2), 30)]
    assert len(S.select(tr, "all", "both", "design")) == 2
    assert len(S.select(tr, "NIFTY", "both", "full")) == 2
    assert len(S.select(tr, "all", "P", "full")) == 1
    assert len(S.select(tr, "all", "both", "final")) == 1
    assert S.fold_nets(tr) == [10, 20, 0, 0]


def test_bh_with_prior_tests():
    q = S.bh_total([0.001, 0.5], 1000)
    assert q[0] == pytest.approx(0.001 * 1000 / 1) and q[1] == 1.0
    assert len(S.bh_total([], 10)) == 0
    q2 = S.bh_total([0.01, 0.02, 0.03], 3)
    assert np.allclose(q2, [0.03, 0.03, 0.03])


def test_twin_p_and_sampler():
    pe, pn = S.twin_p(10.0, np.array([0.0, 1, 2, 3, 20]))
    assert pe == pytest.approx(2 / 6) and 0 < pn < 1
    assert S.twin_p(0.0, np.zeros(5)) == (1.0, 1.0)
    table = np.arange(2 * 4 * 3, dtype=float).reshape(2, 4, 3)
    ranks = S.twin_ranks(2, 4, 50, np.random.default_rng(1))
    ranks2 = S.twin_ranks(2, 4, 50, np.random.default_rng(1))
    assert all(np.array_equal(a, b) for a, b in zip(ranks, ranks2))
    out = S.twin_draws(table, [2, 0], ranks)
    assert out.shape == (50, 3)
    for i in range(50):                      # two distinct candles of day 0
        pick = ranks[0][i, :2]
        assert len(set(pick.tolist())) == 2 and out[i, 0] == pytest.approx(table[0, pick, 0].sum())
    assert np.all(S.twin_draws(table, [0, 0], ranks) == 0)
    full = S.twin_draws(table, [9, 0], ranks)      # more than the candles: all of them
    assert np.allclose(full[:, 0], table[0, :, 0].sum())


def test_exit_choice_rule():
    nets = {"X01": [10, 20, 30, 40], "X02": [100, -1, 100, 100], "X03": [26, 26, 26, 26], "X04": [5, 5, 5, 5]}
    order = S.rank_exits(nets)
    assert order[0] == "X03" and order[1] == "X01" and order[2] == "X04" and order[3] == "X02"
    none = {"X01": [-1, 5, 5, 5], "X02": [100, -1, 0, 0]}
    assert S.rank_exits(none) == ["X02", "X01"]
    tie = {"X05": [5, 5, 5, 5], "X02": [5, 5, 5, 5]}
    assert S.rank_exits(tie)[0] == "X02"


def test_selection_walk_forward():
    nets = {"X01": [10, -5, 7, 1], "X02": [5, 6, -2, 3]}
    wf, picks = S.selection_walk_forward(nets)
    # fold 2: pick on fold 1 -> X01 (10 > 5) -> -5; fold 3: on folds 1-2, only X02 all > 0 -> -2;
    # fold 4: on folds 1-3, none all > 0 -> best total X01 (12) vs X02 (9) -> X01 -> 1
    assert picks == ["X01", "X02", "X01"] and wf == -5 - 2 + 1


def test_gates():
    good = dict(net=5000, trades=50, net_wo_top5=100, max_dd=1000, worst_month=-500)
    des = dict(net=1000, max_dd=1000, worst_month=-500)
    assert S.gate(good, 10, des, 0.01, 0.05) == []
    bad = dict(good, net=-1, trades=10, net_wo_top5=-1, max_dd=50000)
    f = S.gate(bad, -1, dict(des, worst_month=-20000), 0.2, 0.5)
    assert len(f) == 7


def test_rule_sets():
    assert len(K.rule_sets(None)) == 16
    rs = K.rule_sets("X05")
    assert len(rs) == 19 and rs["STD-X05"] == ("STD", "close", 0, "X05") and rs["ITM-X05"][2] == 1
    assert rs["LOW-X05"][1] == "low"
