"""R62 tests on hand-made bars (PREREG section 4): swings, structure, legs, profile, LVN, FVG, OB, the setup walk, the
look-ahead guard, the volume sources, spikes, held-contract pricing and the stock / option exits.

    python -m pytest research/hunt/r62/test_r62.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r62cfg as K  # noqa: E402
import r62smc as S  # noqa: E402

NAN = np.nan


def bars_from(h, l, o=None, c=None):
    h, l = np.asarray(h, float), np.asarray(l, float)
    o = (h + l) / 2 if o is None else np.asarray(o, float)
    c = (h + l) / 2 if c is None else np.asarray(c, float)
    n = len(h)
    di = np.zeros(n, np.int64)
    r0 = np.arange(n, dtype=np.int64)
    return S.Bars(di, r0, r0, o, h, l, c, r0.copy())


# ------------------------------------------------------------------------------------------------ swings / structure
def test_swing_high_needs_two_lower_each_side_and_confirms_late():
    h = [10, 11, 15, 12, 13, 9, 8]
    l = [9, 10, 14, 11, 12, 8, 7]
    sh, sl = S.swings(np.array(h, float), np.array(l, float), 2)
    assert sh.tolist() == [False, False, True, False, False, False, False]
    # the last two bars can never be swings yet (they need two bars after them)
    assert not sh[-2:].any() and not sl[-2:].any()


def test_swing_ties_left_strict_right_inclusive():
    h = np.array([10, 15, 15, 12, 11, 10], float)
    sh, _ = S.swings(h, h - 1, 2)
    assert not sh[1]          # 15 is not above the bar before it? it is, but it has only one bar on its left
    assert not sh[2]          # 15 is not strictly above its left neighbour (15)
    h2 = np.array([10, 11, 15, 15, 12, 11], float)
    sh2, _ = S.swings(h2, h2 - 1, 2)
    assert sh2[2] and not sh2[3]


def test_structure_bos_and_choch():
    # up-swing high at bar 2 (15); confirmed at bar 4; bar 5 closes 16 > 15 -> bullish break (from none: BOS)
    h = [10, 12, 15, 13, 12, 17, 16, 14, 11, 10, 9, 8]
    l = [9, 11, 14, 12, 10, 15, 13, 12, 9, 8, 7, 6]
    c = [9.5, 11.5, 14.5, 12.5, 11, 16, 14, 13, 10, 9, 8, 7]
    b = bars_from(h, l, c=c)
    s = S.structure(b, 2)
    assert s.brk[5] == 1 and not s.choch[5] and s.state[5] == 1
    assert (s.state[:5] == 0).all()
    # swing low at bar 4 (10, confirmed at bar 6): close 9 at bar 9 < 10 -> bearish break that flips: ChoCH
    k = int(np.flatnonzero(s.brk == -1)[0])
    assert s.choch[k] and s.state[k] == -1 and c[k] < 10


def test_structure_never_uses_unconfirmed_swing():
    # swing high at bar 2 is confirmed only at bar 4: a close above it at bar 3 is impossible (bar 3 high <= it), and a
    # change to bars after t never changes the state at t (causal)
    rng = np.random.default_rng(1)
    h = 100 + np.cumsum(rng.normal(0, 1, 120))
    l = h - 1
    c = h - 0.5
    b = bars_from(h, l, c=c)
    s = S.structure(b, 2)
    for t in (30, 60, 90):
        h2, l2, c2 = h.copy(), l.copy(), c.copy()
        h2[t + 1:] += 50
        l2[t + 1:] -= 50
        c2[t + 1:] += 10
        s2 = S.structure(bars_from(h2, l2, c=c2), 2)
        assert (s2.state[:t + 1] == s.state[:t + 1]).all()
        assert (s2.brk[:t + 1] == s.brk[:t + 1]).all()


def test_state_at_uses_bars_closed_by_then():
    b = bars_from([1, 2, 3], [0, 1, 2])
    s = S.Structure(np.zeros(3, bool), np.zeros(3, bool), np.array([0, 1, -1], np.int8), np.zeros(3, np.int8),
                    np.zeros(3, bool))
    assert S.state_at(b, s, np.array([-1, 0, 1, 2, 5])).tolist() == [0, 0, 1, -1, -1]


# ------------------------------------------------------------------------------------------------ legs
def up_leg_bars():
    # a swing high at bar 2 (101), a swing low at bar 5 (89), a rally that closes above 101 at bar 8 (a BOS), a top at
    # bar 10 (110), then two lower bars that confirm it
    h = [95, 97, 101, 98, 96, 92, 95, 99, 103, 106, 110, 108, 107, 105]
    l = [92, 94, 98, 95, 93, 89, 92, 96, 100, 103, 107, 105, 104, 102]
    c = [94, 96, 100, 96, 94, 90, 94, 98, 102, 105, 109, 106, 105, 103]
    return bars_from(h, l, c=c)


def test_bullish_leg_anchored_low_to_high_and_active_two_bars_later():
    b = up_leg_bars()
    s = S.structure(b, 2)
    lg = S.legs(b, s)
    ups = [x for x in lg if x.dir == 1]
    assert len(ups) == 1
    x = ups[0]
    assert x.l == 5 and x.h == 10 and x.lo == 89 and x.hi == 110
    assert x.active_t == b.t[12]       # swing high at 10, confirmed at the close of bar 12
    assert s.brk[8] == 1


def test_no_leg_without_a_break_between():
    # a swing high that is lower than the previous one (no close above any swing high) makes no bullish leg
    h = [100, 104, 110, 104, 100, 97, 99, 103, 101, 100, 99]
    l = [98, 102, 108, 102, 97, 95, 97, 101, 99, 98, 97]
    b = bars_from(h, l, c=(np.array(h) + np.array(l)) / 2)
    s = S.structure(b, 2)
    assert not [x for x in S.legs(b, s) if x.dir == 1]


# ------------------------------------------------------------------------------------------------ profile / LVN
def test_profile_poc_value_area_and_spreading():
    lo, hi = 100.0, 150.0                       # 50 rows of 1 point
    mh = np.array([150.0, 120.5, 120.5, 120.5, 101.0])
    ml = np.array([100.0, 120.2, 120.2, 120.2, 100.0])
    mv = np.array([50.0, 10.0, 10.0, 10.0, 4.0])
    p = S.profile(mh, ml, mv, lo, hi, rows=50, va=0.7, min_cover=0.8)
    assert p.poc == 20                           # 120.x sits in row 20
    assert np.isclose(p.vol.sum(), mv.sum())
    assert np.isclose(p.vol[20], 30 + 1.0)       # 30 from the three minutes + 50/50 from the wide one
    assert np.isclose(p.vol[0], 1 + 2) and np.isclose(p.vol[1], 1 + 2)   # the 100-101 minute spans rows 0..1
    assert p.val <= 20 <= p.vah
    inside = p.vol[p.val:p.vah + 1].sum()
    assert inside >= 0.7 * p.vol.sum() - 1e-9
    lo_e, hi_e = p.edges(p.poc, p.poc)
    assert (lo_e, hi_e) == (120.0, 121.0)


def test_profile_flat_minute_and_top_edge():
    p = S.profile(np.array([150.0, 125.0]), np.array([150.0, 125.0]), np.array([5.0, 1.0]), 100.0, 150.0, rows=50)
    assert p.poc == 49 and p.vol[25] == 1.0      # the high itself lands in the last row


def test_profile_needs_coverage_and_volume():
    mh = np.array([110.0, 111.0, 112.0, 113.0, 114.0])
    ml = mh - 1
    assert S.profile(mh, ml, np.array([1, NAN, NAN, 1, 1.0]), 100, 120) is None      # 60% < 80%
    assert S.profile(mh, ml, np.zeros(5), 100, 120) is None                          # no volume at all
    assert S.profile(mh, ml, np.array([1, 1, 1, 1, NAN]), 100, 120) is not None      # 80% is enough


def test_poc_tie_goes_to_the_middle_then_lower():
    mh = np.array([101.0, 125.5, 124.5, 149.0])
    ml = mh - 0.2
    p = S.profile(mh, ml, np.ones(4), 100.0, 150.0, rows=50)
    assert p.poc == 24                           # rows 1, 25, 24, 48 tie: nearest 24.5 then lower


def test_lvn_zone_inner_rows_only_highest_for_bulls():
    vol = np.full(50, 100.0)
    vol[0:5] = 0                                 # edge rows: never LVN
    vol[10:13] = 5                               # inner LVN run (low)
    vol[30:32] = 0                               # inner zero-volume run (higher)
    vol[20] = 400                                # POC
    p = S.Profile(vol, 100.0, 1.0, 20, 15, 25)
    assert S.lvn_zone(p, 1) == (130.0, 132.0)
    assert S.lvn_zone(p, -1) == (110.0, 113.0)
    vol2 = np.full(50, 100.0)
    vol2[45:] = 0
    assert S.lvn_zone(S.Profile(vol2, 100.0, 1.0, 20, 15, 25), 1) is None


# ------------------------------------------------------------------------------------------------ FVG / OB
def test_fvg_three_candle_gap():
    b = bars_from([10, 12, 15, 16], [9, 10, 11, 14], c=[9.5, 11.5, 14.5, 15])
    assert S.fvgs(b, 0, 3, 1) == [(10.0, 11.0), (12.0, 14.0)]
    assert S.fvgs(b, 0, 3, -1) == []
    assert S.fvgs(b, 1, 3, 1) == [(12.0, 14.0)]                  # bars must lie inside [a, z]
    bd = bars_from([20, 18, 15], [19, 16, 13])
    assert S.fvgs(bd, 0, 2, -1) == [(15.0, 19.0)]


def test_order_block_last_opposite_candle_full_range_and_clean():
    o = [100, 101, 100.5, 99, 98.5, 100, 103, 104, 105]
    c = [101, 100.5, 99.2, 98.6, 99.5, 102.8, 103.8, 104.8, 105.5]
    h = [101.2, 101.5, 100.8, 99.3, 99.8, 103, 104, 105, 106]
    l = [99.8, 100.2, 99.0, 98.2, 98.4, 99.8, 102.9, 103.9, 104.8]
    b = bars_from(h, l, o=o, c=c)
    s = S.Structure(np.zeros(9, bool), np.zeros(9, bool), np.zeros(9, np.int8), np.zeros(9, np.int8), np.zeros(9, bool))
    s.brk[5] = 1                                                   # an internal bullish break at bar 5
    obs = S.order_blocks(b, s, 0, 8, 1)
    assert obs == [(98.2, 99.3)]                                   # bar 3: the last red candle before bar 5, full range
    l2 = list(l)
    l2[7] = 99.0                                                   # a later low trades back into it: not clean
    assert S.order_blocks(bars_from(h, l2, o=o, c=c), s, 0, 8, 1) == []
    assert S.order_blocks(b, s, 4, 8, 1) == []                     # the OB candle must be inside the leg


# ------------------------------------------------------------------------------------------------ the setup walk
def synth_day(path, nd=1, vol=None):
    """(nd, 375, 4) minute bars following `path` (one price per minute, flat-index), each minute +-0.05 wide."""
    p = np.asarray(path, float)
    M = np.full((nd * K.NROWS, 4), NAN)
    M[:len(p), 0] = np.r_[p[0], p[:-1]]
    M[:len(p), 3] = p
    M[:len(p), 1] = np.maximum(M[:len(p), 0], p) + 0.05
    M[:len(p), 2] = np.minimum(M[:len(p), 0], p) - 0.05
    V = np.full(nd * K.NROWS, NAN)
    V[:len(p)] = 1.0 if vol is None else vol[:len(p)]
    return M.reshape(nd, K.NROWS, 4), V.reshape(nd, K.NROWS)


def bull_path():
    """Session 0: a decline, a low, a rally through the prior swing high (a BOS), a top, then a pullback into the
    middle of the leg and a bounce. Session 1: flat. Made so a POC sits in the leg's middle (time spent there)."""
    seg = []
    seg += list(np.linspace(110, 100, 60))          # 09:15-10:14 down
    seg += list(np.linspace(100, 104, 30))          # bounce: a swing high near 104
    seg += list(np.linspace(104, 98, 30))           # down to the swing low 98
    seg += list(np.linspace(98, 107, 30))           # rally (breaks the ~104 swing high)
    seg += [107.0] * 45                             # long pause around 107: the POC
    seg += list(np.linspace(107, 116, 30))          # up to the top
    seg += list(np.linspace(116, 113, 45))          # first pullback (confirms the swing high)
    seg += list(np.linspace(113, 107.02, 30))       # pullback into the POC
    seg += list(np.linspace(107.02, 112, 50))       # bounce
    seg += [112.0] * (375 - len(seg))
    return seg + [112.0] * 375


def test_setup_walk_finds_the_poc_retest_long():
    M, V = synth_day(bull_path(), nd=2)
    cands, cnt = S.candidates(M, V)
    poc = [c for c in cands if c.zone == "poc" and c.side == 1]
    assert cnt["legs"] >= 1 and cnt["profiles"] >= 1
    assert poc, cands
    c = poc[0]
    assert c.zlo <= 107.0 <= c.zhi + 0.2
    assert c.stop < 98 and abs(c.stop - 97.95 * (1 - K.STOP_BUFFER)) < 0.1
    assert c.st_tgt == pytest.approx(116.05, abs=0.01)
    assert (c.s + 1) % 5 == 0 and K.SIG_FIRST <= c.s <= K.SIG_LAST


def test_setup_walk_lookahead_guard():
    """Changing every minute after a candidate's signal minute never changes that candidate (or any earlier one)."""
    M, V = synth_day(bull_path(), nd=2)
    cands, _ = S.candidates(M, V)
    assert cands
    for c in cands[:3]:
        f = c.di * K.NROWS + c.s
        M2, V2 = M.copy().reshape(-1, 4), V.copy().reshape(-1)
        M2[f + 1:] = M2[f + 1:] * 1.3 + 5
        V2[f + 1:] = 99.0
        c2, _ = S.candidates(M2.reshape(M.shape), V2.reshape(V.shape))
        before = [x for x in cands if (x.di, x.s) <= (c.di, c.s)]
        after = [x for x in c2 if (x.di, x.s) <= (c.di, c.s)]
        assert before == after


def test_missing_minutes_and_empty_data():
    M, V = synth_day(bull_path(), nd=2)
    M[0, 200:203] = NAN                            # missing minutes: bars are built from what is there
    V[0, 200:203] = NAN
    cands, _ = S.candidates(M, V)
    assert isinstance(cands, list)
    E = np.full((3, K.NROWS, 4), NAN)
    assert S.candidates(E, np.full((3, K.NROWS), NAN))[0] == []
    b = S.make_bars(M, 5)
    assert np.isfinite(b.c).all() and (np.diff(b.t) > 0).all()


def test_hour_bars_last_slot_is_short():
    M, _ = synth_day(bull_path(), nd=2)
    b = S.make_bars(M[:1], 60)
    assert b.rend.tolist() == [59, 119, 179, 239, 299, 359, 374]


def test_no_entry_outside_the_signal_window():
    M, V = synth_day(bull_path(), nd=2)
    for c in S.candidates(M, V)[0]:
        assert K.SIG_FIRST <= c.s <= K.SIG_LAST


# ------------------------------------------------------------------------------------------------ data helpers
def test_spike_minutes_rule():
    import r62data as D
    c = np.full(375, NAN)
    c[[0, 1, 2, 5, 6]] = [100, 200, 101, 100, 300]
    m = D.spike_minutes(c)
    assert m[1] and not m[0] and not m[2] and not m[6]           # minute 6 is the last trade: never a spike
    c2 = np.full(375, NAN)
    c2[[0, 1, 2, 3]] = [100, 200, 200, 100]                      # two-minute spike: not flagged
    assert not D.spike_minutes(c2).any()


def test_option_volume_sums_contracts_and_ignores_negatives():
    import r62data as D

    class Day:
        con = {}
    d = Day()
    a = np.full((5, 375), NAN, np.float32)
    b = np.full((5, 375), NAN, np.float32)
    a[4, 10], b[4, 10], a[4, 11] = 100, 50, -5
    d.con = {("C", 100.0): a, ("P", 100.0): b}
    v = D.option_volume(d)
    assert v[10] == 150 and v[11] == 0 and np.isnan(v[12])
    d.con = {}
    assert np.isnan(D.option_volume(d)).all()


def test_tpo_counts_present_minutes():
    import r62data as D
    M = np.full((1, 375, 4), NAN)
    M[0, 3, 3] = 5.0
    t = D.tpo(M)
    assert t[0, 3] == 1 and np.isnan(t[0, 4])


# ------------------------------------------------------------------------------------------------ simulation pieces
def test_underlying_exit_scan_stop_first_and_next_minute():
    import r62sim as Z
    h = np.full(375, 101.0)
    l = np.full(375, 99.0)
    l[50] = 94.0                                                   # stop hit at row 50
    h[40] = 110.0                                                  # target hit earlier at row 40
    assert Z.scan_exit(h, l, 10, 360, 1, 95.0, 105.0) == (41, 2)
    assert Z.scan_exit(h, l, 10, 360, 1, 95.0, 120.0) == (51, 1)
    h[60], l[60] = 130.0, 80.0
    assert Z.scan_exit(h, l, 55, 360, 1, 95.0, 120.0) == (61, 1)   # both in one minute: the stop
    assert Z.scan_exit(h, l, 61, 360, 1, 95.0, 200.0) == (360, 0)  # neither: the square-off row
    hm = h.copy()
    hm[70:80] = NAN
    assert Z.scan_exit(hm, np.where(np.isnan(hm), NAN, l), 70, 360, -1, 150.0, 50.0) == (360, 0)


def test_cash_trade_stop_gap_and_limit_target():
    import r62sim as Z
    o = np.full(375, 100.0)
    h = np.full(375, 100.5)
    l = np.full(375, 99.5)
    c = np.full(375, 100.0)
    o[30], l[30] = 95.0, 94.0                                       # gaps through the stop at 98: filled at the open
    x, why, row = Z.cash_walk(o, h, l, c, 10, 360, 1, 98.0, 110.0)
    assert (x, why, row) == (95.0, 1, 30)
    o2 = np.full(375, 100.0)
    l2 = np.full(375, 99.5)
    h2 = h.copy()
    h2[40] = 103.0
    x, why, row = Z.cash_walk(o2, h2, l2, c, 10, 360, 1, 98.0, 102.0)
    assert (x, why, row) == (102.0, 2, 40)
    o2[41], h2[41] = 104.0, 104.5                                    # a gap above the limit target fills at the open
    assert Z.cash_walk(o2, h2, l2, c, 41, 360, 1, 98.0, 102.0)[:2] == (104.0, 2)
    x, why, row = Z.cash_walk(o2, h, l2, c, 10, 360, 1, 90.0, 120.0)
    assert (why, row) == (0, 360) and x == 100.0


def test_index_trade_is_priced_on_the_held_contract():
    """An ATM switch never changes the price: the trade reads only the contract bought."""
    import r62sim as Z
    import r51data

    d = date(2025, 1, 6)
    idx = np.full((4, 375), 23000.0)
    D = r51data.Day(d, idx)
    D.atm = np.full(375, 23000.0)
    D.atm[100:] = 23100.0                                          # the rolling ATM moves to another strike
    ce = np.full((5, 375), NAN, np.float32)
    ce[0:4, :] = 150.0
    ce[4, :] = 1000
    ce[0:4, 200:] = 180.0                                          # the held contract rises
    other = np.full((5, 375), NAN, np.float32)
    other[0:4, :] = 10.0
    other[4, :] = 1000
    D.con = {("C", 22950.0): ce, ("C", 23050.0): other}
    t = Z.index_trade("NIFTY", D, side=1, s=50, stop=22000.0, tgt=24000.0)
    assert t is not None and t["strike"] == 22950.0               # 1-ITM at the signal minute's ATM
    assert t["E"] == 150.0 and t["X"] == 180.0 and t["why"] == "squareoff"
    assert t["xr"] == K.SQ_IDX


def test_index_trade_expiry_squareoff_and_forced_exit():
    import r62sim as Z
    import r51data

    d = date(2025, 1, 9)
    idx = np.full((4, 375), 23000.0)
    idx[2, 120] = 22000.0                                          # index low through the stop at row 120
    D = r51data.Day(d, idx)
    D.atm = np.full(375, 23000.0)
    ce = np.full((5, 375), NAN, np.float32)
    ce[0:4, :] = 100.0
    ce[0:4, 121] = 60.0
    ce[4, :] = 1000
    D.con = {("C", 22950.0): ce}
    t = Z.index_trade("NIFTY", D, side=1, s=50, stop=22500.0, tgt=24000.0)
    assert t["xr"] == 121 and t["X"] == 60.0 and t["why"] == "stop" and t["why_opt"] == "rule_exit"
    D.expiry = True
    t2 = Z.index_trade("NIFTY", D, side=1, s=50, stop=10000.0, tgt=99999.0)
    assert t2["xr"] == K.row(15, 5)                                 # expiry-day square-off 15:05


def test_period_guard():
    with pytest.raises(PermissionError):
        K.check_range(date(2024, 11, 1), date(2025, 1, 1))
    with pytest.raises(PermissionError):
        K.check_range(date(2025, 1, 1), date(2026, 4, 2))


def test_variants_are_sixteen():
    assert len(K.VARIANTS) == 16 and len(set(K.VARIANTS)) == 16
    assert K.parse_variant("poc-c-h-2r") == dict(zone="poc", conf=True, htf=True, target="2r")
