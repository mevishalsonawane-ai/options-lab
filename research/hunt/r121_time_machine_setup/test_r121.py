"""R121 tests: the replay walk (stop before target, locks, trail, time stops), the score and verdict, the per-arm
exits, and the look-ahead guard. Synthetic data only. 28 Oct 2026."""
from __future__ import annotations

import math
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r121lib as X  # noqa: E402

E0 = 600   # 10:00


def bars(path, start=555, rng=1.0):
    """1-minute bars from `start`, closes path[i]; open = the previous close (the first: path[0]); high/low +-rng."""
    n = len(path)
    o = np.array([path[0]] + list(path[:-1]), float)
    c = np.array(path, float)
    return dict(minute=np.arange(start, start + n), open=o, high=np.maximum(o, c) + rng, low=np.minimum(o, c) - rng,
                close=c)


def flat(n=375, v=100.0):
    return [v] * n


def test_no_bar_at_the_entry_minute():
    assert X.walk(bars(flat(10)), E0, 1, X.Exits(stop=5.0)) is None


def test_stop_before_target_inside_a_bar():
    b = bars(flat(), rng=10.0)                       # every bar touches +-10
    assert X.walk(b, E0, 1, X.Exits(stop=5.0, target=5.0)) == -5.0
    assert X.walk(b, E0, -1, X.Exits(stop=5.0, target=5.0)) == -5.0


def test_target_and_square_off():
    p = [100.0] * 45 + [100.0 + 2 * i for i in range(330)]   # climbs 2 a minute from 10:00
    b = bars(p, rng=0.0)
    assert X.walk(b, E0, 1, X.Exits(stop=50.0, target=20.0)) == 20.0
    # no target: out at the square-off bar's open
    got = X.walk(b, E0, 1, X.Exits(stop=50.0, square_off=E0 + 30))
    assert got == pytest.approx(b["open"][E0 + 30 - 555] - b["open"][E0 - 555])
    # a short on the same climb stops
    assert X.walk(b, E0, -1, X.Exits(stop=10.0)) == -10.0


def test_time_stop_tmax():
    p = [100.0] * 45 + [100.0 + 0.1 * i for i in range(330)]
    b = bars(p, rng=0.0)
    got = X.walk(b, E0, 1, X.Exits(stop=50.0, tmax=30))
    assert got == pytest.approx(b["open"][E0 + 30 - 555] - b["open"][E0 - 555])


def test_need_at_twenty_minutes():
    slow = [100.0] * 45 + [100.0 + 0.1 * i for i in range(330)]
    b = bars(slow, rng=0.0)
    x = X.Exits(stop=50.0, need_at=20, need_pts=5.0, square_off=E0 + 60)
    out = X.walk(b, E0, 1, x)                         # 20 minutes in: +1.9, short of +5: out at the 20th minute's open
    assert out == pytest.approx(b["open"][E0 + 20 - 555] - b["open"][E0 - 555])
    fast = [100.0] * 45 + [100.0 + 1.0 * i for i in range(330)]
    b2 = bars(fast, rng=0.0)
    out2 = X.walk(b2, E0, 1, x)                       # +19 by then: kept to the square-off
    assert out2 == pytest.approx(b2["open"][E0 + 60 - 555] - b2["open"][E0 - 555])


def test_locks_only_rise():
    # up 10, then back down through the locked +2: out at the lock, never at the stop
    p = [100.0] * 45 + [100.0 + i for i in range(11)] + [110.0 - i for i in range(1, 30)] + [80.0] * 290
    b = bars(p, rng=0.0)
    x = X.Exits(stop=8.0, locks=((5.0, 0.0), (10.0, 2.0)))
    assert X.walk(b, E0, 1, x) == 2.0
    # only the first lock reached: out at breakeven
    p2 = [100.0] * 45 + [100.0 + i for i in range(7)] + [106.0 - i for i in range(1, 30)] + [70.0] * 294
    assert X.walk(bars(p2, rng=0.0), E0, 1, x) == 0.0


def test_hero_trail_from_the_best_premium():
    # prem 10: the trail is 25% of the best premium; at delta 0.5 that is 0.5 x best premium in index points
    x = X.Exits(stop=None, trail_prem=0.25, prem=10.0, square_off=E0 + 200)
    p = [100.0] * 45 + [100.0 + i for i in range(21)] + [120.0 - i for i in range(1, 40)] + [60.0] * 270
    got = X.walk(bars(p, rng=0.0), E0, 1, x)
    best_prem = 10.0 + 0.5 * 20.0                     # 20
    assert got == pytest.approx(20.0 - 0.5 * best_prem)   # +10
    # straight down from the fill: out at -5 (25% of 10 = 2.5 premium = 5 index points)
    down = [100.0] * 45 + [100.0 - i for i in range(330)]
    assert X.walk(bars(down, rng=0.0), E0, 1, x) == -5.0


def test_gap_through_the_stop_fills_at_the_open():
    p = [100.0] * 45 + [100.0, 100.0, 80.0] + [80.0] * 327
    b = bars(p, rng=0.0)
    b["open"][E0 + 2 - 555] = 80.0
    assert X.walk(b, E0, 1, X.Exits(stop=5.0)) == -20.0


def test_score_and_verdict():
    assert X.verdict_of([0.5] * 9).verdict == "unknown"
    assert X.verdict_of([1.0] * 10 + [None]).verdict == "edge"
    assert X.verdict_of([-1.0] * 12).verdict == "against"
    v = X.verdict_of([1.0, -1.0] * 10)
    assert v.verdict == "coin flip" and v.n == 20 and v.mean == 0.0
    assert X.verdict_of([0.04] * 30).verdict == "coin flip"       # inside the 0.05 R floor


def test_replay_r_uses_the_r_unit():
    t = dict(arm="R52", prem=10.0, setup=None, und="NIFTY", entry_ts=datetime(2025, 1, 1, 10, 0), side=1)
    x = X.real_exits(t)
    assert x.stop == 6.0 and x.r_unit == 6.0 and x.tmax == 60 and x.square_off == 15 * 60 + 10
    down = [100.0] * 45 + [100.0 - i for i in range(330)]
    assert X.replay_r(bars(down, rng=0.0), t, X.real_exits) == -1.0
    assert X.replay_r(None, t, X.real_exits) is None


def test_real_exits_per_arm():
    T = lambda **k: dict(dict(arm="R51", prem=100.0, setup=None, und="NIFTY", stop_pts=30.0, target_pts=30.0,  # noqa: E731
                              exit_at=time(15, 15)), **k)
    x = X.real_exits(T())
    assert x.stop == 30.0 and x.tmax == 30 and x.target is None and x.locks[0] == (50.0, 10.0)
    assert X.real_exits(T(arm="SOLO", setup="S1")) is None
    assert X.real_exits(T(arm="SOLO", setup="S4")).stop == 50.0
    liq = X.real_exits(T(arm="LIQ", stop_pts=15.0, pool_target_pts=None))
    assert liq.target is None and liq.need_at == 20 and liq.need_pts == 10.0 and liq.stop == 15.0
    assert X.real_exits(T(arm="LIQ", stop_pts=30.0, pool_target_pts=55.0)).target == 55.0
    assert X.real_exits(T(arm="VIXDIV", und="BANKNIFTY")) is None
    orb = X.real_exits(T(arm="ORB_SWEEP"))
    assert orb.stop == 80.0 and orb.target == 160.0 and orb.locks[1] == (80.0, 40.0)
    h = X.real_exits(T(arm="HERO", prem=10.0))
    assert h.stop is None and h.trail_prem == 0.25 and h.r_unit == 5.0
    assert X.real_exits(T(arm="PINE_MATRIX_5M", stop_pts=None)) is None
    with pytest.raises(KeyError):
        X.real_exits(T(arm="MOM60"))


def test_setup_exits_are_the_app_walk():
    t = dict(stop_pts=30.0, target_pts=60.0, exit_at=time(15, 10))
    x = X.setup_exits(t)
    assert x.stop == 30.0 and x.target == 60.0 and x.square_off == 910 and x.tmax is None and not x.locks
    assert X.setup_exits(dict(stop_pts=None, target_pts=None, exit_at=None)) is None


def test_lookahead_guard():
    days = [date(2025, 1, 1) + timedelta(days=i) for i in range(100)]
    entries = [(d, 1.0) for d in days]
    s = X.sample_before(entries, days[80], days)
    assert len(s) == 60
    # the entry day and later days never count, whatever they hold
    poisoned = entries + [(days[80], -99.0), (days[90], -99.0)]
    assert X.sample_before(poisoned, days[80], days) == s
    # a day with no session in the underlying's calendar is never in the window
    assert X.sample_before([(date(2030, 1, 1), 5.0)], days[80], days) == []
    assert X.sample_before(entries, days[0], days) == []
