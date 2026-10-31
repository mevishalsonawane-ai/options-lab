"""Tests for R139 (PREREG.md): the swing structure and Dow trend on hand-built 15-minute zigzags, every rule's entry and
stop, the bearish mirror, one-trade-at-a-time, empty data, the holdout lock and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r139 as M  # noqa: E402
from test_r131 import grid, random_grid, truncate  # noqa: E402

R = M.R
SEG = 3                       # bars per zigzag leg (a 25-bar session holds 8 legs)


def zig(points, seg=SEG, w=0.1):
    """15-minute bars walking in straight lines between the given turning prices (closes); wicks w beyond the body.
    The turning point p_s closes on bar s * seg."""
    bars = [(points[0], points[0] + w, points[0] - w, points[0])]
    for a, b in zip(points[:-1], points[1:]):
        for s in range(1, seg + 1):
            o = bars[-1][3]
            c = a + (b - a) * s / seg
            bars.append((o, max(o, c) + w, min(o, c) - w, c))
    return bars


def sides(bars_per_day):
    return R.sides_of(grid(bars_per_day, tf=15))


def pivot_bar(s):
    return s * SEG


# ------------------------------------------------------------------------------------------------ structure
UP = [100, 105, 102, 108, 104, 111, 107, 107]           # HH + HL: highs 105, 108, 111; lows 102, 104, 107


def test_structure_confirms_a_pivot_only_k_bars_later():
    ch = sides([zig(UP)])[1].chart(15)
    Hs, Ls = M.structure(ch)
    i = pivot_bar(3)                                        # the swing high 108 (+ wick)
    assert ch.h[i] == pytest.approx(108.1)
    assert i not in Hs[i + ch.k - 1]                        # not known one bar early
    assert Hs[i + ch.k, 0] == i                             # known at the close of bar i + k


def test_trend_up_down_and_mixed():
    ch = sides([zig(UP)])[1].chart(15)
    Hs, Ls = M.structure(ch)
    tr = M.trend(ch, Hs, Ls)
    assert tr[pivot_bar(4) + 3] == 1                         # after the higher low 104 is confirmed
    assert tr[0] == 0                                        # not enough swings yet
    chd = sides([zig([111, 106, 109, 103, 107, 100, 104, 104])])[1].chart(15)
    H2, L2 = M.structure(chd)
    assert M.trend(chd, H2, L2)[pivot_bar(4) + 3] == -1     # LH + LL


def test_empty_data_gives_no_candidates():
    g = grid([[]], tf=15)
    S = R.sides_of(g)
    for var in M.VARIANTS.values():
        assert M.candidates(S, var) == []


# ------------------------------------------------------------------------------------------------ rules
def test_s1_buys_the_confirmed_higher_low_with_its_stop():
    S = sides([zig(UP)])[1]
    c = M.det_hl(S)
    j = pivot_bar(4)                                         # the higher low 104 (wick 103.9)
    hit = [x for x in c if x.r0 == (j + 3) * 15 + 15]
    assert len(hit) == 1
    assert hit[0].stop == pytest.approx(R.stop_below(103.9))
    assert hit[0].tgt is None                                # 2R


def test_s1_mirror_is_the_lower_high_sell():
    down = [111, 106, 109, 103, 107, 100, 104, 104]           # LH 107 at leg 4
    S = sides([zig(down)])[-1]                                # the put's (mirrored) side
    c = M.det_hl(S)
    j = pivot_bar(4)
    hit = [x for x in c if x.r0 == (j + 3) * 15 + 15]
    assert len(hit) == 1
    assert -hit[0].stop == pytest.approx(107.1 * (1 + R.STOP_PCT))   # stop just above the lower high


def test_s2_bos_first_close_above_the_last_high():
    pts = [100, 105, 102, 108, 104, 106, 112]                 # the higher low is confirmed below 108; then up
    bars = zig(pts)
    S = sides([bars])[1]
    c = M.det_bos(S)
    assert len(c) == 1
    ch = S.chart(15)
    t = c[0].r0 // 15 - 1
    assert ch.c[t] > 108.1 >= ch.c[t - 1]
    assert c[0].stop == pytest.approx(R.stop_below(103.9))    # below the last higher low


def test_s3_choch_after_a_down_trend():
    pts = [111, 106, 109, 103, 107, 100, 109]                 # LH 107, LL 100, then a close above 107.1
    S = sides([zig(pts)])[1]
    c = M.det_choch(S)
    assert len(c) == 1
    assert c[0].stop == pytest.approx(R.stop_below(99.9))


def test_s4_one_two_three():
    # p0 low 100, hb high 106, p1 low 97 (LL), p2 high 103 (LH), p3 low 99 (HL), then up through 103.1
    pts = [104, 100, 106, 97, 103, 99, 102, 108]               # point 3 is confirmed before the break
    bars = zig(pts)
    S = sides([bars])[1]
    c = M.det_r123(S)
    assert len(c) == 1
    r = c[0].r0 - 1                                           # the minute whose wick took out point 2
    assert S.mh[0][r] >= 103.1 + M.TICK
    assert np.nanmax(S.mh[0][:r][pivot_bar(4) * 15 + 15:]) < 103.1 + M.TICK
    assert c[0].stop == pytest.approx(R.stop_below(98.9))


def test_s5_first_higher_low_after_the_shift():
    pts = [111, 106, 109, 103, 107, 100, 109, 104, 112]
    S = sides([zig(pts)])[1]
    c = M.det_mss_hl(S)
    assert len(c) == 1
    assert c[0].stop == pytest.approx(R.stop_below(103.9))
    assert c[0].r0 == (pivot_bar(7) + 3) * 15 + 15


def test_s7_swing_failure():
    bars = zig([104, 100, 105])                               # swing low 99.9 at bar 4
    bars += [(105, 105.2, 104.8, 105)] * 2
    bars += [(105, 105.1, 99.5, 100.5)]                       # wick below 99.9, close back above
    bars += [(100.5, 101, 100.2, 100.8)] * 3
    S = sides([bars])[1]
    c = M.det_sfp(S)
    assert len(c) == 1
    assert c[0].stop == pytest.approx(R.stop_below(99.5))


def test_s8_equilibrium_and_s9_shallow_levels():
    # higher low 104 (wick 103.9) after a high 108; the leg runs to 116.1, then pulls back to 105
    pts = [100, 105, 102, 108, 104, 116, 105]
    S = sides([zig(pts)])[1]
    eq = M.det_eq(S)
    sh = M.det_shallow(S)
    top, low = 116.1, 103.9
    assert len(eq) == 1 and len(sh) == 1
    assert eq[0].tgt == pytest.approx(top) and sh[0].tgt == pytest.approx(top)
    assert eq[0].stop == pytest.approx(R.stop_below(low))
    assert sh[0].stop == pytest.approx(R.stop_below(top - 0.5 * (top - low)))
    assert S.ml[0][eq[0].r0 - 1] <= top - 0.5 * (top - low)
    assert S.ml[0][sh[0].r0 - 1] <= top - 0.25 * (top - low)
    assert sh[0].r0 < eq[0].r0                                # the shallow level is reached first


def test_s11_first_lower_high_after_a_lower_low_is_a_put():
    # an up-trend, then a LOWER LOW (95), then the first lower high (101): buy the put (mirrored side)
    pts = [100, 106, 102, 108, 95, 101, 90]
    S = sides([zig(pts)])[-1]
    c = M.det_lh_ll(S)
    assert len(c) == 1
    assert -c[0].stop == pytest.approx(101.1 * (1 + R.STOP_PCT))
    assert c[0].r0 == (pivot_bar(5) + 3) * 15 + 15


def test_s10_first_hour_filter():
    var = M.VARIANTS["S10"]
    g = random_grid(np.random.default_rng(5), 20)
    out = M.candidates(R.sides_of(g), var)
    assert out and all(c.r0 <= M.FIRST_HOUR_LAST for _, c in out)


# ------------------------------------------------------------------------------------------------ trading
def test_sequence_skips_both_sides_and_overlaps():
    g = random_grid(np.random.default_rng(3), 3)
    S = R.sides_of(g)
    ref = S[1].mo[1][100]
    cands = [(1, M.C(1, 100, ref - 5)), (-1, M.C(1, 100, -ref - 5)),        # both sides at once -> neither
             (1, M.C(1, 200, S[1].mo[1][200] - 5)), (1, M.C(1, 201, S[1].mo[1][201] - 5))]
    T = M.sequence(S, cands, set(), g.days, lambda d: True)
    assert [t["entry"] for t in T][0] == 200
    assert all(t["entry"] != 100 for t in T)
    if len(T) > 1:
        assert T[1]["entry"] > T[0]["exit"]


def test_sequence_uses_the_rule_target():
    g = random_grid(np.random.default_rng(4), 2)
    S = R.sides_of(g)
    ref = S[1].mo[0][50]
    T = M.sequence(S, [(1, M.C(0, 50, ref - 10, ref + 3))], set(), g.days, lambda d: True)
    assert T and T[0]["tgt_pts"] == pytest.approx(3)
    T2 = M.sequence(S, [(1, M.C(0, 50, ref - 10, ref - 1))], set(), g.days, lambda d: True)
    assert T2 == []                                           # a target behind the entry: no trade


def test_gates_and_holdout_lock(tmp_path, monkeypatch):
    s = dict(n=400, avg_net=10, avg_net_x2=5, p=0.01, max_dd=-1000, worst_month=-500,
             by_fold={f: dict(total=1.0) for f in M.K.FOLDS})
    assert all(M.gates(s, 0.05).values())
    assert not M.gates(dict(s, worst_month=-14000), 0.05)["account"]
    assert not M.gates({}, 1.0)["n"]
    monkeypatch.setattr(M, "OUT", tmp_path / "o")
    monkeypatch.setattr(M, "RESULTS", tmp_path / "r")
    with pytest.raises(R.HoldoutError):
        M.lock("start")                                       # no design results yet
    (tmp_path / "r").mkdir()
    (tmp_path / "r" / "design.json").write_text("{}")
    M.lock("start")
    M.lock("done")
    with pytest.raises(R.HoldoutError):
        M.lock("start")                                       # read once only


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("v", [v for v, x in M.VARIANTS.items() if not x.get("nn")])
def test_lookahead_guard(v):
    """Every candidate is found again when every minute from its entry minute on is deleted."""
    rng = np.random.default_rng(139)
    g = random_grid(rng, 30)
    var = M.VARIANTS[v]
    full = M.candidates(R.sides_of(g), var)
    assert full, v
    pick = [full[i] for i in rng.choice(len(full), size=min(6, len(full)), replace=False)]
    for sd, c in pick:
        again = M.candidates(R.sides_of(truncate(g, c.d, c.r0)), var)
        key = lambda s2, x: (s2, x.d, x.r0, round(x.stop, 6), None if x.tgt is None else round(x.tgt, 6))  # noqa
        assert key(sd, c) in [key(s2, x) for s2, x in again], (v, sd, c)
