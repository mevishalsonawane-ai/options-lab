"""R21 unit tests on synthetic days (no release files needed): python3 -m pytest research/hunt/r21/test_r21.py -q"""
from __future__ import annotations

import math
import os
import sys
from datetime import date

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import data as D  # noqa: E402
import rules as R  # noqa: E402
import run as RUN  # noqa: E402
from data import C, H, L, O, OI, V, Day, minute  # noqa: E402

N = 375


def _bars(px: np.ndarray, vol: float = 500.0, oi: float = 1e5) -> np.ndarray:
    a = np.full((N, 6), np.nan)
    a[:, O] = np.r_[px[0], px[:-1]]
    a[:, C] = px
    a[:, H] = np.maximum(a[:, O], a[:, C])
    a[:, L] = np.minimum(a[:, O], a[:, C])
    a[:, V] = vol
    a[:, OI] = oi
    return a


def _opt(spot: np.ndarray, k: float, side: str) -> np.ndarray:
    """A crude expiry-day price: intrinsic + time value that shrinks through the day and with distance."""
    rem = np.linspace(1.0, 0.02, N)
    tv = 40 * rem * np.exp(-np.abs(spot - k) / 60.0)
    intr = np.maximum(spot - k, 0) if side == "C" else np.maximum(k - spot, 0)
    return np.round(np.maximum(intr + tv, 0.05) / 0.05) * 0.05


def make_day(spot: np.ndarray, und="NIFTY", d=date(2025, 6, 5), lot=75) -> Day:
    ix = np.c_[np.r_[spot[0], spot[:-1]], np.maximum(spot, np.r_[spot[0], spot[:-1]]),
               np.minimum(spot, np.r_[spot[0], spot[:-1]]), spot]
    step = 50
    base = round(spot[0] / step) * step
    opts = {}
    for i in range(-12, 13):
        k = float(base + i * step)
        for s in ("C", "P"):
            opts[(k, s)] = _bars(_opt(spot, k, s))
    return Day(d, und, ix, opts, np.full(N, 13.0), spot[0], 13.0, lot)


def flat_then_rally(rally_from=minute(14, 10), pts=250.0) -> np.ndarray:
    s = np.full(N, 25000.0)
    r = np.arange(N)
    up = np.clip((r - rally_from) / 40.0, 0, 1) * pts
    return s + up


def test_lookahead_guard_every_variant_every_minute():
    rng = np.random.default_rng(1)
    for seed in range(3):
        spot = 25000 + np.cumsum(rng.normal(0, 6, N))
        day = make_day(spot)
        for p in RUN.VARIANTS:
            for t in range(p.first - 2, p.last + 3):
                a = R.decide(day, p, t)
                b = R.decide(R.masked(day, t), p, t)
                assert (a is None) == (b is None), (p.name, t)
                if a:
                    assert (a.side, a.strike, a.ref) == (b.side, b.strike, b.ref)


def test_lookahead_guard_poisoned_future_changes_nothing():
    """Rows after t set to absurd values: the decision at t must not move."""
    day = make_day(flat_then_rally())
    for p in RUN.VARIANTS:
        for t in range(p.first, p.last + 1, 7):
            a = R.decide(day, p, t)
            z = R.masked(day, t)
            z.ix[t + 1:] = 1e6
            for kk in z.opts:
                z.opts[kk][t + 1:] = [999, 999, 999, 999, 1e9, 1e9]
            b = R.decide(z, p, t)
            assert (a is None) == (b is None)
            if a:
                assert (a.strike, a.ref) == (b.strike, b.ref)


def test_one_way_rally_onesided_fires_where_the_straddle_does_not():
    """13-Oct shape: a steady one-way rally. The call gains, the put collapses, the straddle barely grows."""
    spot = flat_then_rally(minute(13, 50), 260.0)
    day = make_day(spot)
    # make the ATM straddle flat (the put's collapse offsets the call): replace ATM legs with a constant straddle
    for t in range(N):
        k = R.atm(day, spot[t])
        for s, share in (("C", 0.5), ("P", 0.5)):
            if (k, s) in day.opts:
                day.opts[(k, s)][t, [O, H, L, C]] = 20.0 * share
    v0 = R.first_signal(day, RUN.VARIANTS[0])
    v1 = R.first_signal(day, RUN.VARIANTS[1])
    assert v0 is None
    assert v1 is not None and v1.side == 1 and 1.0 <= v1.ref <= 5.0


def test_baseline_needs_momentum_and_straddle_in_the_same_minute():
    day = make_day(np.full(N, 25000.0))
    assert R.first_signal(day, RUN.VARIANTS[0]) is None          # no momentum: never


def test_pick_band_traded_and_slack():
    spot = np.full(N, 25000.0)
    day = make_day(spot)
    t = minute(14, 0)
    c = R.pick(day, 1, t, (1.0, 5.0))
    assert c is not None and c[0] > 25000 and 1 <= c[1] <= 5
    # not traded in the last 5 bars -> skipped
    k = c[0]
    day.opts[(k, "C")][t - 6: t + 1, V] = 0
    c2 = R.pick(day, 1, t, (1.0, 5.0))
    assert c2 is None or c2[0] != k
    # a further-OTM strike priced well above it -> stale, skipped
    day = make_day(spot)
    day.opts[(k + 50, "C")][:, C] = 50.0
    c3 = R.pick(day, 1, t, (1.0, 5.0))
    assert c3 is None or c3[0] != k


def test_onesided_needs_k_times_its_own_low():
    day = make_day(np.full(N, 25000.0))
    t = minute(14, 0)
    c = R.pick(day, 1, t, (1.0, 15.0))
    assert c is not None
    assert R.pick(day, 1, t, (1.0, 15.0), k_up=2.5) is None      # time decay only: never 2.5x its low


def _single_option_day(path: np.ndarray) -> tuple[Day, R.Signal]:
    spot = np.full(N, 25000.0)
    day = make_day(spot)
    day.opts = {(25100.0, "C"): _bars(path, vol=2000)}
    t = minute(13, 59)
    return day, R.Signal(t, 1, 25100.0, float(path[t]))


def test_exits_half_at_5x_rest_at_20x():
    p = np.full(N, 2.0)
    p[minute(14, 10):] = 10.5
    p[minute(14, 30):] = 50.0
    day, s = _single_option_day(p)
    tr = R.simulate(day, s, R.Params())
    assert [x[3] for x in tr.exits] == ["5x", "20x"]
    assert tr.net > 0 and tr.lots >= 1


def test_stop_on_a_minute_close_at_40pct():
    p = np.full(N, 3.0)
    p[minute(14, 20):] = 1.0
    day, s = _single_option_day(p)
    tr = R.simulate(day, s, R.Params())
    assert tr.exits[-1][3] == "stop" and tr.net < 0
    assert tr.net >= -R.BUDGET - 200                              # never more than the ticket + charges


def test_lock_and_trail_and_plain():
    p = np.full(N, 2.0)
    p[minute(14, 10): minute(14, 20)] = 9.0       # 4.5x: arms the lock and the trail, not the 5x target
    p[minute(14, 20):] = 3.9                      # back under 2x and under half of the best close
    day, s = _single_option_day(p)
    lock = R.simulate(day, s, R.Params(exits="lock"))
    trail = R.simulate(day, s, R.Params(exits="trail"))
    plain = R.simulate(day, s, R.Params(exits="plain"))
    app = R.simulate(day, s, R.Params(exits="app"))
    assert lock.exits[-1][3] == "lock2x"
    assert trail.exits[-1][3] == "trail"
    assert plain.exits[-1][3] == "1505" and len(plain.exits) == 1
    assert app.exits[-1][3] == "1505"


def test_entry_never_chases_above_the_cap():
    p = np.full(N, 2.0)
    p[minute(14, 0):] = 5.0                       # jumps 150% the minute after the signal
    day, s = _single_option_day(p)
    day.opts[(25100.0, "C")][minute(14, 0):, [O, L]] = 5.0
    assert R.simulate(day, s, R.Params()) is None


def test_lot_unknown_or_too_dear_skips():
    p = np.full(N, 4.0)
    day, s = _single_option_day(p)
    day.lot = 0
    assert R.simulate(day, s, R.Params()) is None
    day.lot = 5000                                # one lot costs more than Rs 5,000
    assert R.simulate(day, s, R.Params()) is None


def test_market_closed_or_empty_day_gives_nothing():
    day = make_day(np.full(N, 25000.0))
    day.ix[:] = np.nan
    for p in RUN.VARIANTS:
        assert R.first_signal(day, p) is None
    day = make_day(np.full(N, 25000.0))
    day.opts = {}
    for p in RUN.VARIANTS:
        assert R.first_signal(day, p) is None


def test_index_gap_makes_momentum_unknown():
    day = make_day(flat_then_rally())
    t = minute(14, 30)
    day.ix[t - 15] = np.nan
    assert math.isnan(R.momentum(day, t, 15))


def test_stale_leg_makes_the_straddle_invalid():
    day = make_day(np.full(N, 25000.0))
    t = minute(14, 0)
    k = R.atm(day, 25000.0)
    day.opts[(k, "C")][t - 5: t + 1, V] = 0
    assert math.isnan(R.straddle(day, t))


def test_exit_at_1505_uses_that_bar_or_the_last_trade():
    p = np.full(N, 3.0)
    day, s = _single_option_day(p)
    day.opts[(25100.0, "C")][R.EXIT_ROW:] = np.nan                # no bar from 15:05 on
    tr = R.simulate(day, s, R.Params(exits="plain"))
    assert tr.exits[-1][3] == "1505" and tr.exits[-1][0] == R.EXIT_ROW - 1
    v5 = R.vol5_lots(day.opts[(25100.0, "C")], R.EXIT_ROW - 1, day.lot)
    assert tr.exits[-1][2] == pytest.approx(R.sell_px(3.0, v5, 1.0))       # the last trade, less the spread


def test_bh_matches_hand_values():
    q = RUN.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], rel=1e-4)


def test_twins_keep_side_and_window():
    day = make_day(flat_then_rally())
    rng = np.random.default_rng(0)
    p = RUN.VARIANTS[4]
    for _ in range(50):
        s = R.twin_signal(day, p, 1, rng)
        assert s is None or (p.first <= s.row <= p.last and s.side == 1)


def test_lot_schedule():
    assert D.lot("NIFTY", date(2025, 12, 30)) == 75 and D.lot("NIFTY", date(2025, 12, 31)) == 65
    assert D.lot("BANKNIFTY", date(2025, 6, 27)) == 35


def test_thirteen_october_is_not_in_any_choice():
    import config as K
    assert K.FORWARD_ONLY == date(2026, 10, 13)
    assert all(K.FORWARD_ONLY > c for c in K.RELEASE_HOLDOUT.values())


def test_holdout_is_read_once(tmp_path, monkeypatch):
    import config as K
    monkeypatch.setattr(K, "OUT", tmp_path)
    (tmp_path / f"holdout_read_{K.SOURCE}.flag").write_text("read\n")
    monkeypatch.setattr(RUN.D, "cached", lambda *a, **k: {"NIFTY": [], "BANKNIFTY": []})
    with pytest.raises(SystemExit):
        RUN.main()
