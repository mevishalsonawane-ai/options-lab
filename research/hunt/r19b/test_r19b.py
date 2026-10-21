"""R19b tests: the trail's arithmetic, its place in the exit order, fills, and the look-ahead guard (PREREG.md)."""
from __future__ import annotations

import numpy as np
import pytest

from research.hunt.r19b import sim as S
from research.hunt.r19b import run as R

A1 = S.Trail("A1", "rs", 500.0, False)
A2 = S.Trail("A2", "rs", 500.0, True)
P3 = S.Trail("P3", "pct", 0.03, False)


# ---------------------------------------------------------------- trail_trigger
def test_a1_waits_for_plus_500_per_lot():
    assert S.trail_trigger(A1, 100.0, 104.0, 120) is None                 # +Rs 480 a lot: not yet
    assert S.trail_trigger(A1, 100.0, 104.20, 120) == pytest.approx(100.05)  # +Rs 504: peak - 4.17 -> 100.03, up to 100.05


def test_a2_is_active_from_the_entry():
    assert S.trail_trigger(A2, 100.0, 100.0, 120) == pytest.approx(95.85)  # 100 - 4.1667 -> 95.8333, up to 95.85


def test_rupees_per_lot_become_points_by_lot_size():
    # Rs 500 = 4.17 points at MIDCPNIFTY's 120, 16.67 at BANKNIFTY's 30, 33.33 at 15
    assert A1.points(195.45, 120) == pytest.approx(500 / 120)
    assert A1.points(500.0, 30) == pytest.approx(500 / 30)
    assert S.trail_trigger(A1, 500.0, 540.0, 30) == pytest.approx(523.35)
    assert S.trail_trigger(A1, 500.0, 540.0, 15) == pytest.approx(506.70)


def test_percent_variant_scales_with_the_premium():
    assert S.trail_trigger(P3, 200.0, 205.0, 120) is None                 # +2.5% < 3%
    assert S.trail_trigger(P3, 200.0, 210.0, 120) == pytest.approx(204.0)


def test_trigger_never_above_the_peak_and_on_the_tick():
    for peak in np.linspace(105, 140, 50):
        t = S.trail_trigger(A1, 100.0, float(peak), 120)
        assert t <= peak + 1e-9
        assert abs(t / S.TICK - round(t / S.TICK)) < 1e-6


def test_missing_or_bad_inputs_give_no_trigger():
    assert S.trail_trigger(None, 100.0, 120.0, 30) is None
    assert S.trail_trigger(A1, 100.0, float("nan"), 30) is None
    assert S.trail_trigger(A1, 100.0, None, 30) is None
    assert S.trail_trigger(A1, 0.0, 120.0, 30) is None
    assert S.trail_trigger(A1, 100.0, 120.0, 0) is None


def test_trigger_only_rises_with_the_peak():
    last = -1.0
    for peak in [105, 107, 106, 110, 108, 115]:
        best = max(105, peak) if last < 0 else max(peak, best)
        t = S.trail_trigger(A1, 100.0, best, 120)
        assert t >= last
        last = t


# ---------------------------------------------------------------- the minute walk
def _series(rows, start=600):
    """rows: (o, h, l, c) per minute from `start`."""
    a = np.array(rows, float)
    return (np.arange(start, start + len(a)), a[:, 0], a[:, 1], a[:, 2], a[:, 3])


def _signal(done=600, side=1):
    # a CE trade with no structural exit nearby: level far below, no target, no failed break / new level
    return dict(done=done, side=side, level=10_000.0 if side < 0 else 0.0, target=None, fb=None, nl=None, sig_min=done - 5,
                strike=50000, right="C" if side > 0 else "P", close=0.0)


def _imin(n=400, start=600):
    return {m: (1.0, 1.0) for m in range(start - 5, start + n)}


def test_trail_exits_at_trigger_after_the_peak_counts():
    # entry 100 (+fills), peak 110 at minute 601, minute 602 trades down through peak - 500/120
    rows = [(100, 100, 100, 100), (100, 110, 100, 109), (109, 109, 104, 105)] + [(105, 105, 105, 105)] * 30
    tr = S.walk(_signal(), _series(rows), _imin(), 120, A1)
    assert tr["why"] == "trail"
    trig = S.trail_trigger(A1, tr["entry"], 110.0, 120)
    assert tr["exit"] == pytest.approx(S.stop_fill(trig))
    assert tr["exit_min"] == 602


def test_peak_of_a_minute_counts_only_from_the_next_minute():
    # one minute that both makes the high and trades back down: no trail exit in that same minute
    rows = [(100, 100, 100, 100), (100, 110, 100, 105)] + [(105, 106, 104.9, 105)] * 3
    tr = S.walk(_signal(), _series(rows), _imin(), 120, A1)
    assert tr["exit_min"] == 602 and tr["why"] == "trail"


def test_gap_through_the_trail_fills_at_the_open():
    rows = [(100, 100, 100, 100), (100, 110, 100, 109), (101, 101, 100.5, 100.8)] + [(100.8,) * 4] * 10
    tr = S.walk(_signal(), _series(rows), _imin(), 120, A1)
    assert tr["why"] == "trail"
    assert tr["exit"] == pytest.approx(S.stop_fill(101.0))


def test_a_minute_with_no_trade_keeps_the_last_peak():
    m = np.array([600, 601, 605, 606] + list(range(607, 640)))
    o = np.array([100, 100, 106, 106] + [106] * 33, float)
    h = np.array([100, 110, 106, 106] + [106] * 33, float)
    lo = np.array([100, 100, 105.5, 106] + [106] * 33, float)
    c = o.copy()
    tr = S.walk(_signal(), (m, o, h, lo, c), _imin(), 120, A1)
    assert tr["why"] == "trail" and tr["exit_min"] == 605


def test_the_trail_never_widens_the_premium_stop():
    # A2 from the entry with a big lot: its trigger (entry - 0.33) is far above the -15% stop; a fall to -20% hits the
    # trail first, never later than the plain stop
    rows = [(100, 100, 100, 100), (100, 100, 80, 80)] + [(80,) * 4] * 5
    ctrl = S.walk(_signal(), _series(rows), _imin(), 1500, None)
    a2 = S.walk(_signal(), _series(rows), _imin(), 1500, A2)
    assert ctrl["why"] == "premium_stop"
    assert a2["exit_min"] <= ctrl["exit_min"] and a2["exit"] >= ctrl["exit"]


def test_without_a_trail_the_walk_is_the_control():
    rng = np.random.default_rng(1)
    p = 100 + np.cumsum(rng.normal(0, 1, 200))
    rows = [(x, x + 1, x - 1, x) for x in p]
    a = S.walk(_signal(), _series(rows), _imin(), 30, None)
    b = S.walk(_signal(), _series(rows), _imin(), 30, S.Trail("off", "rs", 1e12, False))
    assert a == b


def test_look_ahead_guard_prices_after_the_exit_do_not_matter():
    rng = np.random.default_rng(7)
    for seed in range(20):
        p = 100 + np.cumsum(rng.normal(0, 1.5, 120))
        rows = [(x, x + abs(rng.normal(0, 1)), x - abs(rng.normal(0, 1)), x) for x in p]
        ser = _series(rows)
        a = S.walk(_signal(), ser, _imin(), 120, A1)
        k = a["exit_min"] - 600
        rows2 = rows[: k + 1] + [(1.0, 1.0, 1.0, 1.0)] * (len(rows) - k - 1) if a["why"] == "trail" else rows
        b = S.walk(_signal(), _series(rows2), _imin(), 120, A1)
        assert (a["why"], a["exit_min"], a["exit"]) == (b["why"], b["exit_min"], b["exit"])


def test_trail_never_sells_above_a_price_that_traded():
    rng = np.random.default_rng(3)
    for _ in range(30):
        p = 100 + np.cumsum(rng.normal(0, 2, 100))
        rows = [(x, x + 1, x - 1, x) for x in np.maximum(p, 1)]
        tr = S.walk(_signal(), _series(rows), _imin(), 120, A1)
        if tr["why"] == "trail":
            j = tr["exit_min"] - 600
            assert tr["exit"] <= rows[j][0] + 1e-9


# ---------------------------------------------------------------- fills, statistics
def test_fills_are_worse_than_the_price():
    assert S.buy_fill(100.0) > 100.0
    assert S.sell_fill(100.0) < 100.0
    assert S.stop_fill(100.0) < S.sell_fill(100.0)
    assert S.stop_fill(0.01) == S.TICK


def test_bh_and_sign_flip():
    q = R.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    assert R.sign_flip_p(np.array([5.0] * 20)) < 0.001
    assert R.sign_flip_p(np.array([-5.0] * 20)) > 0.99
    assert R.sign_flip_p(np.zeros(5)) == 1.0


def test_today_illustration_matches_boss_arithmetic():
    rows = dict((r.split("|")[1].strip(), r) for r in R.today()[2:])
    assert "+2,004" in rows["A1"]
    assert "-864" in rows["CTRL (what happened)"]
