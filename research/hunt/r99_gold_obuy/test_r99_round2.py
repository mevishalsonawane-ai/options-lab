"""R99 round 2 tests (synthetic data only, R96's synthetic book): the 44-variant grid, each family's signal (ORB break and
its stop, the day trend against the 20-session median range, the big-impulse follow / fade distances), at most one trade a
day, and the look-ahead guard (a day's first signal does not change when every later bar is replaced).

    python -m pytest research/hunt/r99_gold_obuy/test_r99_round2.py -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r96_mcx_buy.tests.test_r96 import make_book
from research.hunt.r99_gold_obuy import study as R1
from research.hunt.r99_gold_obuy import study2 as R2


@pytest.fixture(autouse=True)
def _fresh():
    R2._RANGES = None
    yield
    R2._RANGES = None
    R1._BOOK = None


def mins(ts):
    return np.asarray((ts - ts.normalize()).total_seconds() / 60)


def test_the_grid_is_the_preregistered_44():
    V = R2.grid2()
    assert len(V) == 44 and len({v.name for v in V}) == 44
    n = {f: sum(v.fam == f for v in V) for f in ("D1", "D2", "E", "F", "G")}
    assert n == {"D1": 12, "D2": 8, "E": 8, "F": 8, "G": 8}
    assert not {v.name for v in V} & {v.name for v in R1.grid()}, "no round-2 name repeats a round-1 name"


def test_orb_breaks_the_window_and_stops_at_its_other_side():
    # 09:00-09:29 between 4990 and 5010; flat 5000 to 09:39; then +1 a minute: the first close above 5010 is 09:51 (5011).
    def path(i, ts):
        t = mins(ts)
        osc = np.where(t < 570, np.where(t % 2 == 0, 4990.0, 5010.0), 5000.0)
        return np.where(t >= 580, 5000 + (t - 580), osc)
    bk = make_book(days=1, path=path)
    m = bk.m
    d = m.days[0]
    ix = m.day_ix(d)
    v = R2.V2("D1", "ATM", win="09:00-09:30", exit="sq")
    sig = R2.signals(m, d, ix, v)
    i, side, tgt, stp = sig[0]
    assert m.tod[i] == 591 and side == 1 and m.F[i] == 5011.0
    assert tgt == float("inf") and stp == pytest.approx(5011.0 - 4990.0)
    assert R2.signals(m, d, ix, R2.V2("D1", "ATM", win="09:00-09:30", exit="2x"))[0][2] == pytest.approx(40.0)
    assert R2.entry_window(m, d, v)[0] == 570, "no signal inside the window itself"


def test_day_trend_needs_the_move_against_the_earlier_ranges():
    # 12 sessions with a 200-point range (5000 -> 5200 -> 5000), then a day rising 0.25 a minute from 09:00 (+120 at 17:00).
    def path(i, ts):
        t = mins(ts)
        day = np.asarray((ts.normalize() - ts.normalize()[0]).days)
        tri = 5000 + 200 - np.abs((t - 540) - 435) * (200 / 435)
        rise = 5000 + 0.25 * (t - 540)
        return np.where(day < 16, tri, rise)        # bdate days 0..11 span 16 calendar days
    bk = make_book(days=13, path=path)
    m = bk.m
    d = m.days[12]
    ix = m.day_ix(d)
    assert R2.median_range_before(m, d) == pytest.approx(200.0, abs=1.0)
    assert np.isnan(R2.median_range_before(m, m.days[5])), "fewer than 10 earlier sessions: no signal"
    sig = R2.signals(m, d, ix, R2.V2("E", "ATM", clock=17 * 60, k=0.5))
    i, side, tgt, stp = sig[0]
    assert m.tod[i] == 17 * 60 and side == 1 and tgt == float("inf") and stp == pytest.approx(60.0)
    assert R2.signals(m, d, ix, R2.V2("E", "ATM", clock=17 * 60, k=0.25))
    # +120 is under 0.5 x 200 at 15:00 (+90): no signal at an earlier clock with that move
    v = R2.V2("E", "ATM", clock=15 * 60, k=0.5)
    assert R2.signals(m, d, ix, v) == []


def test_big_impulse_follow_and_fade_distances():
    def path(i, ts):
        t = mins(ts)
        return np.where(t < 600, 5000.0, np.where(t < 630, 5000 + 15 * (t - 600), 5450.0))
    bk = make_book(days=1, path=path)
    m = bk.m
    d = m.days[0]
    ix = m.day_ix(d)
    mv = R1.impulse_moves(m, 30)
    f = R2.signals(m, d, ix, R2.V2("F", "ATM", X=400, W=30, H=120), mv)[0]
    g = R2.signals(m, d, ix, R2.V2("G", "ATM", X=400, W=30, H=60), mv)[0]
    assert f[0] == g[0] and mv[f[0]] >= 400
    assert f[1] == 1 and f[2] == float("inf") and f[3] == pytest.approx(mv[f[0]])
    assert g[1] == -1 and g[2] == pytest.approx(0.5 * mv[f[0]]) and g[3] == pytest.approx(0.5 * mv[f[0]])
    assert R2.hold_of(R2.V2("F", "ATM", X=400, W=30, H=None)) is None
    assert R2.hold_of(R2.V2("D1", "ATM", win="09:00-10:00")) is None


def test_at_most_one_trade_a_day():
    rng = np.random.default_rng(3)
    steps = rng.normal(0, 25, 6 * 900)
    bk = make_book(days=6, path=lambda i, ts: 5000 + np.cumsum(steps[:len(i)]))
    R1._BOOK = bk
    period = (pd.Timestamp(bk.m.days[0]).date(), pd.Timestamp(bk.m.days[-1]).date())
    for v in (R2.V2("G", "ATM", X=60, W=5, H=60), R2.V2("D1", "ATM", win="09:00-09:30", exit="2x")):
        tr, _ = R2.run_variant2(v, period=period)
        assert tr, v.name
        days = [t["day"] for t in tr]
        assert len(days) == len(set(days)), v.name
        assert all(t["variant"] == v.name and "target_d" in t for t in tr)


def test_lookahead_guard_the_first_signal_does_not_see_later_bars():
    rng = np.random.default_rng(11)
    steps = rng.normal(0, 30, 13 * 900)
    base = make_book(days=13, path=lambda i, ts: 5000 + np.cumsum(steps[:len(i)]))
    m = base.m
    d = m.days[12]
    ix = m.day_ix(d)
    checked = 0
    for v in (R2.V2("D1", "ATM", win="09:00-10:00"), R2.V2("D2", "ATM", win="17:00-17:30"), R2.V2("E", "ATM", clock=17 * 60, k=0.25),
              R2.V2("F", "ATM", X=150, W=30, H=120), R2.V2("G", "ATM", X=150, W=30, H=60)):
        mv = R1.impulse_moves(m, v.W) if v.W else None
        R2._RANGES = None
        sig = R2.signals(m, d, ix, v, mv)
        if not sig:
            continue
        i0 = sig[0][0]
        bad = m.F.copy()
        bad[i0 + 1:] = 9e9
        bk2 = make_book(days=13, path=lambda ii, ts: bad[ii])
        mv2 = R1.impulse_moves(bk2.m, v.W) if v.W else None
        R2._RANGES = None
        assert R2.signals(bk2.m, d, bk2.m.day_ix(d), v, mv2)[0] == sig[0], v.name
        checked += 1
    assert checked >= 4, checked
