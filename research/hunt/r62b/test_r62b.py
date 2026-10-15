"""R62b tests on hand-made bars (PREREG sections 1, 4): the port of EmreKb's zigzag, MSB and order block, the
look-ahead guard (no repainting), zones, touches, rejections, the signal window, the held-contract trade with its time
exit and risk:reward filter, the twins, the spike rule and the period guard.

    python -m pytest research/hunt/r62b/test_r62b.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r62bcfg as K  # noqa: E402
import r62bmsb as S  # noqa: E402

NAN = np.nan


def path(w):
    """Closes stepping by 1 through the waypoints w."""
    c = [float(w[0])]
    for a, b in zip(w, w[1:]):
        st = 1 if b > a else -1
        c += list(np.arange(a + st, b + st, st, dtype=float))
    return np.array(c)


def ohlc(c):
    """Rising bars green (o < c), falling bars red, turning bars doji; high / low 0.5 around the close."""
    c = np.asarray(c, float)
    return c - 0.2 * np.sign(np.gradient(c)), c + 0.5, c - 0.5, c


# the waypoint path used below: 10 -> 14 -> 11 -> 16 -> 8 -> 12 -> 10 -> 20 -> 15
C0 = path([10, 14, 11, 16, 8, 12, 10, 20, 15])


def run(c=C0, length=3, fib=0.273):
    o, h, l, cc = ohlc(c)
    return S.msb(o, h, l, cc, length, fib), (o, h, l, cc)


# ------------------------------------------------------------------------------------------------ zigzag (L52-73)
def test_pivot_is_recorded_at_the_trend_flip_not_at_the_pivot_bar():
    m, _ = run()
    # the swing low 10.5 is bar 7; the zigzag only knows it at bar 8, when the trend turns up (no repainting)
    assert np.isnan(m.l0[7]) and m.l0[8] == 10.5 and m.l0i[8] == 7
    assert m.trend[7] == -1 and m.trend[8] == 1
    # the swing high 16.5 at bar 12 is pushed at bar 13 (a new 3-bar low)
    assert m.h0[12] == 13.5 and m.h0[13] == 16.5 and m.h0i[13] == 12 and m.h1[13] == 13.5


def test_no_flip_before_length_bars_and_trend_starts_up():
    o, h, l, c = ohlc([10, 9, 8, 7, 6])
    m = S.msb(o, h, l, c, 9, 0.273)
    assert (m.trend == 1).all() and (m.market == 1).all() and not m.brk.any()
    assert np.isnan(m.h0).all() and np.isnan(m.l0).all()


def test_empty_and_flat_bars():
    e = np.array([], float)
    m = S.msb(e, e, e, e, 9, 0.273)
    assert len(m.market) == 0
    f = np.full(40, 100.0)
    m = S.msb(f, f, f, f, 9, 0.273)
    assert not m.brk.any()


# ------------------------------------------------------------------------------------------------ MSB (L104-109)
def test_bearish_msb_needs_the_fib_extension():
    m, _ = run(fib=0.273)
    # new low 7.5 (bar 20, pushed at 21) < l1 10.5 - |h0 16.5 - l1 10.5| x 0.273 = 8.862 -> bearish MSB at bar 21
    assert m.brk[21] == -1 and m.market[21] == -1 and m.market[20] == 1
    assert np.flatnonzero(m.brk).tolist() == [21, 37]
    # with fib 0.5 the threshold is 10.5 - 3 = 7.5: 7.5 is not below it, so no break at 21
    m5, _ = run(fib=0.5)
    assert m5.brk[21] == 0


def test_bullish_msb_and_the_two_new_pivots_guard():
    m, _ = run()
    # after the bearish break (21) a new high 12.5 (pushed 25) and a new low 9.5 (pushed 27) are both needed; 12.5 is
    # not above h1 16.5, so no break; the high 20.5 (pushed 37) > 12.5 + |12.5 - 9.5| x 0.273 -> bullish MSB at 37
    assert (m.market[21:37] == -1).all() and m.brk[37] == 1
    assert m.h0[37] == 20.5 and m.h1[37] == 12.5 and m.l0[37] == 9.5


def test_guard_blocks_a_second_break_on_one_new_pivot():
    # fib 0.5: the first break needs h0 to be pushed (bar 25); the guard is armed one bar later (L107's history)
    m5, _ = run(fib=0.5)
    assert m5.brk[25] == -1
    assert m5.brk[26:37].sum() == 0 and m5.brk[37] == 1


def test_one_bar_late_valuewhen_does_not_double_break():
    # a flip followed at once by conditions for the opposite flip on the next bar must not flip back: the guard
    # compares to l0 / h0 recorded on the bar AFTER the flip, and neither pivot has changed by then
    m, _ = run()
    assert m.brk[22] == 0 and m.brk[38] == 0


# ------------------------------------------------------------------------------------------------ OB (L111-127)
def test_order_block_loops_and_full_range():
    m, (o, h, l, c) = run()
    # bearish OB at 21: last green candle in l1i 7 .. h0i[3] = 12 -> bar 11 (bar 12 is the top: a doji)
    assert m.be_ob[21] == 11 and o[11] < c[11] and not o[12] < c[12]
    # bullish OB at 37: last red candle in h1i 24 .. l0i[3] = 26 -> bar 25
    assert m.bu_ob[37] == 25
    zs = S.zones(S.Bars(np.zeros(len(c), np.int64), np.arange(len(c)), np.arange(len(c)), o, h, l, c,
                        np.arange(len(c), dtype=np.int64)), m)
    assert [(z.k, z.dir, z.ob) for z in zs] == [(21, -1, 11), (37, 1, 25)]
    z = zs[1]
    assert (z.zlo, z.zhi) == (l[25], h[25])                       # the OB candle's full range
    assert z.stop == pytest.approx(min(9.5, l[25]) * (1 - K.STOP_BUFFER))
    assert z.tgt == 20.5                                           # the swing high: next opposing level
    zb = zs[0]
    assert zb.stop == pytest.approx(max(16.5, h[11]) * (1 + K.STOP_BUFFER)) and zb.tgt == 7.5


def test_pine_for_loop_direction_and_na():
    assert list(S._loop(3, 5)) == [3, 4, 5]
    assert list(S._loop(5, 3)) == [5, 4, 3]                        # Pine v5 counts down
    assert list(S._loop(NAN, 3)) == [] and list(S._loop(3, NAN)) == []
    assert not S._eq(NAN, NAN) and S._eq(1.0, 1.0)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_no_repainting_prefix_equals_full():
    """Every value at bar t is the same whether the bars after t exist or not, and changing them changes nothing."""
    rng = np.random.default_rng(7)
    c = 100 + np.cumsum(rng.normal(0, 1, 400))
    o = c + rng.normal(0, 0.3, 400)
    h = np.maximum(o, c) + rng.random(400)
    l = np.minimum(o, c) - rng.random(400)
    full = S.msb(o, h, l, c, 9, 0.273)
    assert full.brk.any()
    for t in (50, 120, 250, 399):
        part = S.msb(o[:t + 1], h[:t + 1], l[:t + 1], c[:t + 1], 9, 0.273)
        h2, l2 = h.copy(), l.copy()
        h2[t + 1:] += 30
        l2[t + 1:] -= 30
        alt = S.msb(o, h2, l2, c, 9, 0.273)
        for k in ("trend", "market", "brk", "h0", "h0i", "l0", "l0i", "h1", "l1", "bu_ob", "be_ob"):
            a, b, z = getattr(full, k)[:t + 1], getattr(part, k), getattr(alt, k)[:t + 1]
            assert np.array_equal(a, b, equal_nan=True), (k, t)
            assert np.array_equal(a, z, equal_nan=True), (k, t)


def test_candidates_lookahead_guard():
    """A candidate signalled at minute s is unchanged when every minute after s is replaced."""
    M = grid_trend(seed=3)
    full, _ = S.candidates(M, 5)
    assert full
    c = full[len(full) // 2]
    cut = c.di * K.NROWS + c.s
    M2 = M.reshape(-1, 4).copy()
    M2[cut + 1:] = M2[cut + 1:] * 1.03 + 5
    alt, _ = S.candidates(M2.reshape(M.shape), 5)
    key = lambda x: (x.di, x.s, x.side, x.conf, x.zlo, x.zhi, x.stop, x.tgt, x.htf)   # noqa: E731
    before = [key(x) for x in full if x.di * K.NROWS + x.s <= cut]
    after = [key(x) for x in alt if x.di * K.NROWS + x.s <= cut]
    assert before == after


def grid_trend(seed=1, nd=6):
    """A synthetic index: nd sessions of 375 minutes of a random walk with swings."""
    rng = np.random.default_rng(seed)
    n = nd * K.NROWS
    c = 20000 + np.cumsum(rng.normal(0, 4, n)) + 150 * np.sin(np.arange(n) / 160)
    o = np.concatenate([[c[0]], c[:-1]])
    h = np.maximum(o, c) + rng.random(n) * 3
    l = np.minimum(o, c) - rng.random(n) * 3
    return np.stack([o, h, l, c], axis=1).reshape(nd, K.NROWS, 4)


# ------------------------------------------------------------------------------------------------ zones and entries
def bars_1(o, h, l, c):
    n = len(c)
    r = np.arange(n, dtype=np.int64)
    return S.Bars(np.zeros(n, np.int64), r, r, np.asarray(o, float), np.asarray(h, float), np.asarray(l, float),
                  np.asarray(c, float), r.copy())


def test_reject_bar_close_back_out_or_next_green():
    z = S.Zone(k=0, dir=1, zlo=100.0, zhi=102.0, ob=0, stop=99.0, tgt=110.0, act_t=0)
    # bar 2 touches (low 101.5) and closes back above 102 -> signal at bar 2
    b = bars_1([105, 104, 103], [106, 105, 104], [104, 103, 101.5], [105, 104, 103])
    assert S.reject_bar(b, z, 99) == 2
    # bar 2 closes inside (101); bar 3 green and above 102 -> bar 3
    b = bars_1([105, 104, 103, 101], [106, 105, 104, 104], [104, 103, 100.5, 100.8], [105, 104, 101, 103])
    assert S.reject_bar(b, z, 99) == 3
    # bar 3 red -> the zone is used up
    b = bars_1([105, 104, 103, 104], [106, 105, 104, 104.5], [104, 103, 100.5, 100.8], [105, 104, 101, 103])
    assert S.reject_bar(b, z, 99) == -1
    # the touch bar closes below the zone (99.5) -> no entry
    b = bars_1([105, 104, 103, 99], [106, 105, 104, 104], [104, 103, 99, 98], [105, 104, 99.5, 103])
    assert S.reject_bar(b, z, 99) == -1
    # the touch comes after the zone's end -> nothing
    b = bars_1([105, 104, 103], [106, 105, 104], [104, 103, 101.5], [105, 104, 103])
    assert S.reject_bar(b, z, 1) == -1


def test_reject_bar_bearish_mirror():
    z = S.Zone(k=0, dir=-1, zlo=100.0, zhi=102.0, ob=0, stop=103.0, tgt=90.0, act_t=0)
    b = bars_1([97, 98, 99], [97.5, 98.5, 100.5], [96, 97, 98], [97, 98, 99])
    assert S.reject_bar(b, z, 99) == 2


def test_touch_minute_and_missing_minutes():
    Mf = np.full((20, 4), 105.0)
    Mf[:, 2] = 104.0
    Mf[6] = NAN                                                    # a missing minute triggers nothing
    Mf[8, 2] = 101.0
    z = S.Zone(k=0, dir=1, zlo=100.0, zhi=102.0, ob=0, stop=99.0, tgt=110.0, act_t=3)
    assert S.touch_minute(Mf, z, 19) == 8
    assert S.touch_minute(Mf, z, 7) == -1                          # the zone died before the touch
    Mf[3, 2] = 101.0                                               # a touch AT the activation minute does not count
    assert S.touch_minute(Mf, z, 19) == 8


def test_zone_life_ends_at_close_through_or_next_session():
    # 5 bars per "session" to keep it small: use the real grid with bars at known times
    n = 10
    t = np.array([K.row(10, 0) + 5 * i for i in range(n)], np.int64)
    b = S.Bars(np.zeros(n, np.int64), t - 4, t, np.full(n, 105.0), np.full(n, 106.0), np.full(n, 104.0),
               np.full(n, 105.0), t.copy())
    z = S.Zone(k=0, dir=1, zlo=100.0, zhi=102.0, ob=0, stop=99.0, tgt=110.0, act_t=int(t[0]))
    assert S.zone_life_end(b, z, 1 << 62, 0, 5) == 2 * K.NROWS - 1      # end of the next session
    assert S.zone_life_end(b, z, 1 << 62, 0, 1) == K.NROWS - 1          # the last session: its own end
    assert S.zone_life_end(b, z, int(t[3]), 0, 5) == int(t[3])          # the opposite MSB
    b.c[4] = 99.0                                                       # a close below the zone kills it
    assert S.zone_life_end(b, z, 1 << 62, 0, 5) == int(t[4])


def test_candidates_signal_window_side_and_levels():
    M = grid_trend(seed=5, nd=8)
    for tf in (5, 15):
        cs, cnt = S.candidates(M, tf)
        assert cnt["msb"] >= cnt["fresh"] >= 0
        for c in cs:
            assert K.SIG_FIRST <= c.s <= K.SIG_LAST
            assert c.conf in ("tch", "rej")
            if c.side > 0:
                assert c.stop < c.zlo + 1e-9 and c.stop < c.zhi
            else:
                assert c.stop > c.zhi - 1e-9
        assert len({(c.zone, c.conf) for c in cs}) == len(cs)          # one try per zone and confirmation


def test_candidates_need_a_fresh_zone():
    """A zone whose MSB bar closed inside it gives no candidate."""
    M = grid_trend(seed=11, nd=8)
    b = S.make_bars(M, 5)
    m = S.msb(b.o, b.h, b.l, b.c)
    zs = S.zones(b, m)
    stale = {i for i, z in enumerate(zs) if not ((z.dir > 0 and b.c[z.k] > z.zhi) or (z.dir < 0 and b.c[z.k] < z.zlo))}
    cs, _ = S.candidates(M, 5)
    assert not ({c.zone for c in cs} & stale)


def test_make_bars_missing_minutes_and_short_last_hour():
    M = np.full((1, K.NROWS, 4), 100.0)
    M[0, 0:5] = NAN                                                # the 09:15 five-minute slot is empty
    b = S.make_bars(M, 5)
    assert len(b) == 74 and b.r0[0] == 5
    b60 = S.make_bars(M, 60)
    assert b60.rend[-1] == K.NROWS - 1 and b60.r0[-1] == 360       # the 15:15 bar has 15 minutes


# ------------------------------------------------------------------------------------------------ trade and twins
def _day(d, idx):
    import r51data
    D = r51data.Day(d, idx)
    D.atm = np.full(375, 23000.0)
    return D


def test_exit_row_time_exit_and_stop_first():
    import r62bsim as Z
    h = np.full(375, 100.0)
    l = np.full(375, 99.0)
    assert Z.exit_row(h, l, 50, 360, 60, 1, 90.0, 110.0) == (110, 3)        # nothing hit: time exit at 50 + 60
    assert Z.exit_row(h, l, 340, 360, 60, 1, 90.0, 110.0) == (360, 0)       # the square-off comes first
    h[70], l[70] = 111.0, 89.0
    assert Z.exit_row(h, l, 50, 360, 60, 1, 90.0, 110.0) == (71, 1)         # both in one minute: the stop
    l[70] = 95.0
    assert Z.exit_row(h, l, 50, 360, 60, 1, 90.0, 110.0) == (71, 2)
    assert Z.exit_row(h, l, 50, 360, 60, -1, 110.0, 96.0) == (71, 1)        # short: high through the stop first


def test_index_trade_held_contract_time_exit_and_rr_filter():
    import r62bsim as Z
    idx = np.full((4, 375), 23000.0)
    D = _day(date(2025, 1, 6), idx)
    D.atm[100:] = 23100.0                                           # the rolling ATM moves; the held strike does not
    ce = np.full((5, 375), NAN, np.float32)
    ce[0:4, :] = 150.0
    ce[4, :] = 1000
    ce[0:4, 111:] = 170.0
    other = np.full((5, 375), NAN, np.float32)
    other[0:4, :] = 10.0
    other[4, :] = 1000
    D.con = {("C", 22950.0): ce, ("C", 23050.0): other}
    t = Z.index_trade("NIFTY", D, 1, 50, stop=22900.0, tgt=23300.0, hold=60)
    assert t["strike"] == 22950.0 and t["E"] == 150.0 and t["X"] == 170.0
    assert t["why"] == "time" and t["xr"] == 111 and t["rr"] == pytest.approx(3.0)
    assert t["net2"] < t["net"]                                     # 2x costs cost more
    assert Z.index_trade("NIFTY", D, 1, 50, stop=22900.0, tgt=23100.0, hold=60, rr_min=1.5) is None   # rr 1.0
    assert Z.index_trade("NIFTY", D, 1, 50, stop=23050.0, tgt=23300.0, hold=60) is None   # already below the stop
    assert Z.index_trade("NIFTY", D, 1, 50, stop=22900.0, tgt=22990.0, hold=60) is None   # already past the target


def test_index_trade_stop_on_index_expiry_squareoff_and_no_fill():
    import r62bsim as Z
    idx = np.full((4, 375), 23000.0)
    idx[2, 120] = 22000.0
    idx[0, 121] = 22400.0                                          # the index open when the option is sold
    D = _day(date(2025, 1, 9), idx)
    ce = np.full((5, 375), NAN, np.float32)
    ce[0:4, :] = 100.0
    ce[0:4, 121] = 60.0
    ce[4, :] = 1000
    D.con = {("C", 22950.0): ce}
    t = Z.index_trade("NIFTY", D, 1, 50, stop=22500.0, tgt=24000.0, hold=200)
    assert t["xr"] == 121 and t["X"] == 60.0 and t["why"] == "stop" and t["r_mult"] < 0
    D.expiry = True
    t2 = Z.index_trade("NIFTY", D, 1, 300, stop=10000.0, tgt=99999.0, hold=200)
    assert t2["xr"] == K.row(15, 5) and t2["why"] == "squareoff"
    ce[0:4, 51:54] = NAN                                            # no print in the fill minute or the next 2
    assert Z.index_trade("NIFTY", D, 1, 50, stop=22500.0, tgt=24000.0, hold=60) is None


def test_twins_same_distances_and_no_fill_adds_zero():
    import r62bsim as Z
    idx = np.full((4, 375), 23000.0)
    D = _day(date(2025, 1, 6), idx)
    ce = np.full((5, 375), NAN, np.float32)
    ce[0:4, :] = 150.0
    ce[4, :] = 1000
    D.con = {("C", 22950.0): ce}
    t = dict(und="NIFTY", side=1, ref=23000.0, stop=22900.0, tgt=23300.0, hold=60)
    nets = Z.twin_index_nets(t, D, 50, np.random.default_rng(1))
    assert nets.shape == (50,) and (nets < 0).all()                 # flat price: every twin pays its costs
    D2 = _day(date(2025, 1, 7), idx)                               # another day (the engine caches stacks by day)
    D2.con = {}
    assert (Z.twin_index_nets(t, D2, 20, np.random.default_rng(1)) == 0).all()


def test_spike_rule():
    import r62bdata as DATA
    c = np.array([10, 10, 30, 10, 10, NAN, 10], float)
    assert DATA.spike_minutes(c).tolist() == [False, False, True, False, False, False, False]
    assert not DATA.spike_minutes(np.array([10, 30], float)).any()


# ------------------------------------------------------------------------------------------------ settings
def test_period_guard_and_variants():
    with pytest.raises(PermissionError):
        K.check_range(date(2024, 11, 1), date(2025, 1, 1))
    with pytest.raises(PermissionError):
        K.check_range(date(2025, 1, 1), date(2026, 4, 2))
    assert len(K.VARIANTS) == 16 and len(set(K.VARIANTS)) == 16
    assert K.parse_variant("15m-rej-h-rr") == dict(tf=15, conf="rej", htf=True, target="rr")
    assert K.FIB == 0.273 and K.ZIGZAG_LEN == 9 and K.quarter(date(2024, 12, 2)) == "2025Q1"
