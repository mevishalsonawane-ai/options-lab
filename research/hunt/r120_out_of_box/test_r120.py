"""R120 tests: lot scaling and the per-order brokerage, futures pricing, the twin-index and morning-after rules,
point-in-time history and the look-ahead guard.

    python3 -m pytest research/hunt/r120_out_of_box -q
"""
from __future__ import annotations

import math
import sys
from collections import deque
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r120cfg as K  # noqa: E402
import r120lib as L  # noqa: E402
import r119cfg as K119  # noqa: E402
import r119lib as L19  # noqa: E402
import r98data as R  # noqa: E402
import r54lib as LB  # noqa: E402
import r50lib as L50  # noqa: E402
import test_r118 as T118  # noqa: E402

LB.load_late()


def ctx_of(**kw):
    return L19.DayCtx(T118.synthetic_day(**kw))


def test_one_lot_matches_r119_engine():
    c = ctx_of(seed=11)
    legs = L.fly_legs(c, K119.ROW_0930)
    tr = L19.run_trade(c, "P1", legs, K119.ROW_0930, K119.ROW_1505)
    f = L.q1_frontier(c)
    assert tr is not None and f is not None
    assert abs(f[1] - tr.net) < 0.05


def test_brokerage_is_per_order_so_lots_amortise_it():
    c = ctx_of(seed=11)
    d, q = c.day.day, c.lot
    one = L50.charge(True, 20.0, q, c.und, d)
    five = L50.charge(True, 20.0, 5 * q, c.und, d)
    assert five < 5 * one                                  # the Rs 20 (+GST) is paid once per order
    assert abs((five - one) - 4 * (one - 20 * 1.18)) < 0.05  # only the percentage part scales


def test_extra_tick_beyond_five_lots():
    c = ctx_of(seed=11)
    pr = [(1, 10.0, 12.0, False)]
    q = c.lot

    def gross(n):
        return L.net_at_lots(c, pr, n) + L50.charge(True, 10.0 + ((n - 1) // 5) * 0.05, n * q, c.und, c.day.day) + \
            L50.charge(False, 12.0 - ((n - 1) // 5) * 0.05, n * q, c.und, c.day.day)
    assert abs(gross(5) - 5 * 2.0 * q) < 0.05
    assert abs(gross(6) - 6 * (2.0 - 0.10) * q) < 0.05     # 1 tick worse in and out from 6 lots


def test_worthless_exit_no_order_at_any_size():
    c = ctx_of(seed=11)
    pr = [(1, 10.0, 0.0, True)]
    for n in (1, 7):
        e = 10.0 + ((n - 1) // 5) * 0.05
        want = -e * n * c.lot - L50.charge(True, e, n * c.lot, c.und, c.day.day)
        assert abs(L.net_at_lots(c, pr, n) - round(want, 2)) < 0.02     # only the entry order: no exit order


def test_choose_n():
    assert L.choose_n({1: (-5, 0), 2: (10, 3), 3: (20, 1), 4: (30, 1)}) == 3
    assert L.choose_n({1: (-5, 0), 2: (-1, 0)}) == 1
    assert L.choose_n({1: (5, 2), 2: (8, 2)}) == 1


def test_futures_round_trip():
    d = date(2025, 1, 2)
    up = L.fut_trade("NIFTY", d, 75, 22000.0, 22010.0, 1)
    dn = L.fut_trade("NIFTY", d, 75, 22000.0, 22010.0, -1)
    assert up < 10 * 75 and dn < -10 * 75 + 1                # spreads and charges on both
    assert L.fut_trade("NIFTY", d, 75, 22000.0, 22010.0, 1, 2.0) < up
    assert math.isnan(L.fut_trade("NIFTY", d, 75, float("nan"), 1.0, 1))
    late = L.fut_trade("NIFTY", date(2026, 5, 4), 65, 22000.0, 22010.0, 1)
    early = L.fut_trade("NIFTY", date(2023, 1, 2), 65, 22000.0, 22010.0, 1)
    assert late < early                                      # STT 0.05% from Apr 2026 vs 0.01% before Apr 2023


def test_open_at_fallbacks():
    ix = np.full((R.N, 4), np.nan)
    ix[10, 3] = 100.0
    ix[12, 0] = 101.0
    assert L.open_at(ix, 11) == 101.0
    assert L.open_at(ix, 20) == 100.0
    assert math.isnan(L.open_at(np.full((R.N, 4), np.nan), 5))


def test_rel_moves_and_signals_point_in_time():
    sp = np.full(R.N, 100.0)
    tw = np.full(R.N, 200.0)
    t = K119.FADE_MARKS[3]
    sp[t:] = 101.0                                           # +1% vs a flat twin
    rel = L.rel_moves(sp, tw)
    assert abs(rel[t] - 1.0) < 1e-9 and abs(rel[K119.FADE_MARKS[0]]) < 1e-12
    hist = {}
    assert L.q4_signals(rel, hist) == []                     # no history yet: no signal
    rng = np.random.default_rng(0)
    for _ in range(12):
        L.push_hist(hist, {m: rng.normal(0, 0.1) for m in K119.FADE_MARKS})
    sig = L.q4_signals(rel, hist)
    assert sig and sig[0] == (t, 1)
    for (a, _), (b, _) in zip(sig, sig[1:]):
        assert b >= a + 1 + K119.FADE_HOLD
    assert all(len(v) <= K.Q4_N for v in hist.values())


def test_q5_threshold_and_m30():
    h = deque(maxlen=K.Q5_N)
    for x in range(K.Q5_MIN - 1):
        h.append(0.1)
    assert L.q5_threshold(h) is None
    h.append(-0.5)
    assert abs(L.q5_threshold(h) - np.percentile(np.abs(np.asarray(h)), K.Q5_PCT)) < 1e-12
    ix = np.full((R.N, 4), np.nan)
    ix[K119.ROW_1500, 3] = 100.0
    ix[R.N - 1, 3] = 99.5
    assert abs(L.m30(ix) + 0.5) < 1e-9


def test_risk_reversal_legs():
    c = ctx_of(seed=3)
    legs = L.rr_legs(c)
    k0, s = c.k0(K119.ROW_1200), c.em[K119.ROW_1200]
    d = max(round(K.Q3_D * s / c.step), 1) * c.step
    w = max(round(K.Q3_W * s / c.step), 1) * c.step
    assert [(lg.k, lg.side, lg.sign) for lg in legs] == [(k0 - d, "P", -1), (k0 + d, "C", 1), (k0 - d - w, "P", 1)]


def test_lookahead_guard():
    """Q2 / Q4 decisions at row t are unchanged when the index after t changes."""
    a, b = ctx_of(seed=21), ctx_of(seed=21)
    cut = K119.FADE_MARKS[5]
    b.sp[cut + 1:] += 250.0
    b.day.ix[cut + 1:] += 250.0
    sa = [x[:3] for x in L.q2_trades(a) if x[0] <= cut]
    sb = [x[:3] for x in L.q2_trades(b) if x[0] <= cut]
    assert [x[:1] + x[2:] for x in sa] == [x[:1] + x[2:] for x in sb]
    tw = np.full(R.N, 200.0)
    ra, rb = L.rel_moves(a.sp, tw), L.rel_moves(b.sp, tw)
    assert {k: v for k, v in ra.items() if k <= cut} == {k: v for k, v in rb.items() if k <= cut}


def test_five_tests_declared():
    assert list(K.TESTS) == ["Q1", "Q2", "Q3", "Q4", "Q5"]
    assert all("source" in v for v in K.TESTS.values())
