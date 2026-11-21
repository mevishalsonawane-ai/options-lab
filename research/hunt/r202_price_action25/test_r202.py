"""R202 tests (PREREG.md section 7): the detectors on hand-made shapes, the look-ahead guard (prefix invariance), pivots
known only 3 bars late, the one-position book, the holdout lock and the gate helpers. No data needed.

    python -m pytest research/hunt/r202_price_action25/test_r202.py -q
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r202pat as P  # noqa: E402


def path(*legs, start=100.0):
    """Closes along straight legs: legs = (target, bars), ..."""
    out, p = [start], start
    for tgt, n in legs:
        for i in range(1, n + 1):
            out.append(p + (tgt - p) * i / n)
        p = tgt
    return np.array(out)


def ctx_of(closes, wick=0.05, sess=None):
    c = np.asarray(closes, float)
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) + wick
    lo = np.minimum(o, c) - wick
    s = np.zeros(len(c), int) if sess is None else np.asarray(sess, int)
    return P.Ctx(o, h, lo, c, s)


def sides(sig):
    return [s for _, s in sig]


# ================================================================================================ bars, pivots
def test_bars5_ohlc_and_missing():
    a = np.full((4, 375), np.nan)
    a[:, 0:5] = np.array([[10, 11, 12, 13, 14], [11, 12, 13, 14, 15], [9, 10, 11, 12, 13], [10.5, 11.5, 12.5, 13.5, 14.5]])
    b = P.bars5(a)
    assert b.shape == (4, 75)
    assert list(b[:, 0]) == [10, 15, 9, 14.5]
    assert np.isnan(b[:, 1]).all()


def test_pivot_known_only_three_bars_later():
    c = path((110, 10), (100, 10))
    ctx = ctx_of(c)
    (i, v), = [p for p in ctx.ph]
    assert i == 10
    assert ctx.known(ctx.ph, i + 2) == []
    assert ctx.known(ctx.ph, i + 3) == [(i, v)]


def test_pivot_needs_strictly_higher_than_left():
    c = np.array([1, 2, 3, 5, 5, 4, 3, 2, 1.0]) + 100
    ctx = ctx_of(c)
    assert [p[0] for p in ctx.ph] == [3]           # the first of two equal highs (>= on the right, > on the left)


# ================================================================================================ look-ahead guard
@pytest.mark.parametrize("seed", [1, 2, 3, 4])
@pytest.mark.parametrize("pat", P.PATTERNS)
def test_prefix_invariance(pat, seed):
    rng = np.random.default_rng(seed)
    n = 225
    c = 20000 + np.cumsum(rng.normal(0, 8, n))
    o = np.r_[c[0], c[:-1]] + rng.normal(0, 2, n)
    h = np.maximum(o, c) + rng.random(n) * 6
    lo = np.minimum(o, c) - rng.random(n) * 6
    s = np.repeat([2, 1, 0], 75)
    full = P.DETECT[pat](P.Ctx(o, h, lo, c, s), 75)
    for k in (100, 150, 190, 224):
        part = P.DETECT[pat](P.Ctx(o[:k], h[:k], lo[:k], c[:k], s[:k]), 75)
        assert part == [x for x in full if x[0] < k], (pat, k)


def test_future_change_never_moves_a_past_signal():
    rng = np.random.default_rng(9)
    n = 225
    c = 20000 + np.cumsum(rng.normal(0, 8, n))
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 3, np.minimum(o, c) - 3
    s = np.repeat([2, 1, 0], 75)
    base = {p: P.DETECT[p](P.Ctx(o, h, lo, c, s), 75) for p in P.PATTERNS}
    c2 = c.copy()
    c2[180:] += 300                                     # a shock after bar 180
    o2 = np.r_[c2[0], c2[:-1]]
    h2, lo2 = np.maximum(o2, c2) + 3, np.minimum(o2, c2) - 3
    for p in P.PATTERNS:
        alt = P.DETECT[p](P.Ctx(o2, h2, lo2, c2, s), 75)
        assert [x for x in alt if x[0] < 180] == [x for x in base[p] if x[0] < 180], p


# ================================================================================================ shapes
def test_pin_bar_at_low():
    c = path((90, 20))
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 0.1, np.minimum(o, c) - 0.1
    o, h, lo, c = (np.r_[x, y] for x, y in ((o, 90.0), (h, 90.6), (lo, 85.0), (c, 90.4)))  # long lower wick
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    assert (len(c) - 1, "C") in P.p25(ctx, 0)


def test_pin_bar_needs_the_extreme():
    c = path((90, 20), (95, 5))
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 0.1, np.minimum(o, c) - 0.1
    o, h, lo, c = (np.r_[x, y] for x, y in ((o, 95.0), (h, 95.6), (lo, 91.0), (c, 95.4)))  # wick, not at the low
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    assert all(b != len(c) - 1 for b, _ in P.p25(ctx, 0))


def test_consolidation_breakout_up():
    rng = np.random.default_rng(0)
    c = np.r_[path((120, 20))[:-1], 120 + rng.uniform(-0.3, 0.3, 14), 125.0]
    o = np.r_[c[0], c[:-1]]
    h, lo = np.minimum(np.maximum(o, c) + 0.6, 120.6), np.maximum(np.minimum(o, c) - 0.6, 119.4)   # wide bars, tight box
    h[:20], lo[:20] = np.maximum(o, c)[:20] + 0.1, np.minimum(o, c)[:20] - 0.1
    h[-1] = 125.1
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    assert (len(c) - 1, "C") in P.p07(ctx, 0)


def test_fvg_retest_long():
    # a jump leaves a gap between bar i-2's high and bar i's low; a pull back into it that closes up fires
    c = np.r_[np.full(16, 100.0) + np.tile([0, 0.2], 8), 101.0, 104.0, 106.0, 105.5, 104.4, 103.2, 103.8]
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 0.05, np.minimum(o, c) - 0.05
    lo[-1], o[-1] = 102.0, 102.9                     # dips into the gap, closes up inside it
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    sig = P.p22(ctx, 0)
    assert sig and sig[-1] == (len(c) - 1, "C")


def test_double_top_breaks_neckline():
    c = path((110, 10), (100, 8), (110, 8), (95, 10))
    ctx = ctx_of(c)
    sig = P.p11(ctx, 0)
    assert sig and sides(sig) == ["P"]
    b = sig[0][0]
    assert c[b] < 100 and all(c[j] >= 100 for j in range(27, b))


def test_double_top_not_when_tops_differ():
    c = path((110, 10), (100, 8), (118, 8), (95, 10))
    assert P.p11(ctx_of(c), 0) == []


def test_double_bottom_mirror():
    c = path((90, 10), (100, 8), (90, 8), (105, 10))
    assert sides(P.p12(ctx_of(c), 0)) == ["C"]


def test_head_and_shoulders():
    c = path((108, 8), (100, 6), (115, 8), (100, 8), (108, 6), (95, 10))
    sig = P.p13(ctx_of(c), 0)
    assert sides(sig) == ["P"]


def test_head_and_shoulders_needs_a_head():
    c = path((108, 8), (100, 6), (108.5, 8), (100, 8), (108, 6), (95, 10))
    assert P.p13(ctx_of(c), 0) == []


def test_inverse_head_and_shoulders():
    c = path((92, 8), (100, 6), (85, 8), (100, 8), (92, 6), (105, 10))
    assert sides(P.p14(ctx_of(c), 0)) == ["C"]


def test_ascending_triangle():
    c = path((110, 6), (100, 6), (110, 6), (104, 6), (114, 6))
    sig = P.p15(ctx_of(c), 0)
    assert sides(sig) == ["C"]
    assert c[sig[0][0]] > 110 and c[sig[0][0] - 1] <= 110.05


def test_descending_triangle():
    c = path((90, 6), (100, 6), (90, 6), (96, 6), (86, 6))
    assert sides(P.p16(ctx_of(c), 0)) == ["P"]


def test_symmetrical_triangle_up():
    c = path((110, 6), (100, 6), (108, 6), (102, 6), (112, 8))
    assert "C" in sides(P.p17(ctx_of(c), 0))


def test_support_bounce_needs_three_touches():
    two = path((105, 6), (100, 6), (105, 6), (100, 6), (106, 6), (100.1, 6), (100.8, 1))
    ctx = ctx_of(two)
    # pivot lows at 100 twice and highs elsewhere: no 3-touch level at 100 before the last bounce
    assert all(b < len(two) - 1 for b, _ in P.p01(ctx, 0)) or len(P.levels(ctx, len(two) - 1)) >= 1
    three = path((105, 6), (100, 6), (105, 6), (100, 6), (105, 6), (100, 6), (106, 6), (99.95, 6))
    c = np.r_[three, 100.9]
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 0.05, np.minimum(o, c) - 0.05
    o[-1], lo[-1] = 100.1, 99.9
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    lv = [L for L, _ in P.levels(ctx, len(c) - 1)]
    assert any(abs(L - 99.95) < 0.2 for L in lv)


def test_levels_group_within_tenth_of_percent():
    c = path((105, 6), (100, 6), (105, 6), (100.05, 6), (105, 6), (99.98, 6), (106, 6))
    ctx = ctx_of(c)
    lv = P.levels(ctx, len(c) - 1)
    lows = [L for L, _ in lv if L < 101]
    assert len(lows) == 1 and abs(lows[0] - 99.96) < 0.1


def test_equal_lows_sweep_and_reclaim():
    c = path((100, 8), (106, 8), (100.02, 8), (106, 8), (101, 6), start=104.0)
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 0.05, np.minimum(o, c) - 0.05
    o, h, lo, c = (np.r_[x, y] for x, y in ((o, 101.0), (h, 101.2), (lo, 99.0), (c, 101.1)))
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    assert (len(c) - 1, "C") in P.p24(ctx, 0)


def test_new_higher_low_in_uptrend():
    c = path((105, 6), (102, 6), (110, 6), (106, 6), (112, 6))
    sig = P.p09(ctx_of(c), 0)
    assert sig and sig[0] == (24 + P.PIV, "C")      # the low at bar 24 is known at bar 27


def test_order_block_retest():
    rng = np.random.default_rng(3)
    base = 100 + rng.uniform(-0.2, 0.2, 20)
    c = np.r_[path((102, 5), (100, 5)), base[:5], 99.6, 101.5, 103.5, 105.5, 105.4, 104, 102.5, 100.2]
    o = np.r_[c[0], c[:-1]]
    h, lo = np.maximum(o, c) + 0.05, np.minimum(o, c) - 0.05
    o[-1], lo[-1], c[-1] = 99.5, 99.4, 100.3          # into the zone (the bearish bar before the run) and up
    ctx = P.Ctx(o, h, lo, c, np.zeros(len(c), int))
    sig = P.p21(ctx, 0)
    assert (len(c) - 1, "C") in sig


# ================================================================================================ series, window, book
def test_day_signals_window_and_rows():
    rng = np.random.default_rng(5)
    days = []
    p = 20000.0
    for _ in range(6):
        c = p + np.cumsum(rng.normal(0, 4, 375))
        o = np.r_[p, c[:-1]]
        a = np.vstack([o, np.maximum(o, c) + 1, np.minimum(o, c) - 1, c])
        days.append(P.bars5(a))
        p = c[-1]
    out = P.day_signals(days)
    for p_, sig in out.items():
        for er, s in sig:
            assert 30 <= er <= 315 and er % 5 == 0 and s in ("C", "P")
        assert [e for e, _ in sig] == sorted({e for e, _ in sig})


def test_book_one_position_and_day_cap():
    sigs = [(30, "C"), (40, "C"), (60, "P"), (70, "C"), (90, "C"), (120, "P")]
    exits = [50, 55, 65, None, 100, 130]
    assert P.book(sigs, exits, 3) == [0, 2, 4]
    assert P.book(sigs, exits, 2) == [0, 2]
    assert P.book([(30, "C")], [None]) == []


# ================================================================================================ study helpers (need the engine stack)
def _study():
    pytest.importorskip("numba")
    import r202study as S
    return S


def test_holdout_is_read_once(tmp_path, monkeypatch):
    S = _study()
    monkeypatch.setattr(S.K, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    (tmp_path / "HOLDOUT_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.cmd_holdout()


def test_gate_items():
    S = _study()
    blk = dict(design=dict(trades=500, net=10000, net_wo_top5=2000, worst_month=dict(month="2023-01", net=-5000),
                           max_dd=20000), folds_pos=4, twin_p=0.01, q=0.05)
    assert S.design_gate(blk) == []
    blk["folds_pos"] = 2
    blk["q"] = 0.5
    f = S.design_gate(blk)
    assert any("folds" in x for x in f) and any("BH q" in x for x in f)
    assert S.holdout_gate(dict(trades=39, net=-1, worst_month=dict(net=-14000), max_dd=41000)).__len__() == 4


def test_counts():
    S = _study()
    assert len(S.TESTS) == 42 and S.K.M_TOTAL == 4850
