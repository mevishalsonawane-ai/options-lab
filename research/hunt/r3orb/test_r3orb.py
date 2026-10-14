"""R3 tests: signals on hand-made days (OrbRules / SweepRules / RangeFadeRules), the exits, the costs, the filter rule,
the holdout refusals and the look-ahead guard. No lake needed (synthetic sessions)."""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r3cfg as K  # noqa: E402
import r3data as D  # noqa: E402
import r3engine as E  # noqa: E402
import study as S  # noqa: E402

N = D.N


def flat_ix(level=50000.0):
    ix = np.full((N, 4), level)
    return ix


def set_bar(ix, b, o, h, l, c):
    """Make 5-minute bar b have these O H L C (spread over its 5 minutes)."""
    r = 5 * b
    ix[r:r + 5, :] = [[o, max(o, c), min(o, c), c]] * 5
    ix[r, 0], ix[r + 4, 3] = o, c
    ix[r + 1, 1], ix[r + 2, 2] = h, l
    for k in range(5):
        ix[r + k, 1] = max(ix[r + k, 1], ix[r + k, 0], ix[r + k, 3])
        ix[r + k, 2] = min(ix[r + k, 2], ix[r + k, 0], ix[r + k, 3])


def opt_path(level=200.0, drift=0.0):
    a = np.full((N, 6), np.nan, dtype=np.float32)
    px = level + drift * np.arange(N)
    a[:, 0] = px
    a[:, 1] = px + 0.5
    a[:, 2] = px - 0.5
    a[:, 3] = px
    a[:, 4] = 1000
    a[:, 5] = 1
    return a


def session(ix=None, und="BANKNIFTY", expiry=False, opts=None, day=date(2024, 1, 10)):
    ix = flat_ix() if ix is None else ix
    s = D.Sess(day, und, ix, np.full(N, 14.0), opts or {}, None, expiry, 15, (50000, 50100, 49900, 50000), 500.0, 14.0,
               D.atm_strike(ix[D.SPOT_ROW, 3], K.STEP[und]))
    return s


def ranged_ix(hi=50100.0, lo=49900.0):
    ix = flat_ix(50000.0)
    set_bar(ix, 2, 50000, hi, 50000, 50000)
    set_bar(ix, 5, 50000, 50000, lo, 50000)
    return ix


# ------------------------------------------------------------------------------------------------ range and signals
def test_opening_range_uses_bars_0915_to_1000_inclusive():
    ix = ranged_ix()
    set_bar(ix, 9, 50000, 50150, 50000, 50000)       # the 10:00 bar is part of the range
    set_bar(ix, 10, 50000, 50300, 50000, 50000)      # the 10:05 bar is not
    hi, lo = E.opening_range(E.bars5(ix))
    assert hi == 50150 and lo == 49900


def test_no_range_without_the_1000_bar():
    ix = ranged_ix()
    ix[45:50] = np.nan
    hi, lo = E.opening_range(E.bars5(ix))
    assert math.isnan(hi)


def test_orb_break_close_beyond_edges():
    ix = ranged_ix()
    set_bar(ix, 12, 50050, 50200, 50050, 50150)
    b5 = E.bars5(ix)
    assert E.signal("orb", b5, 12, 50100, 49900) == 1
    set_bar(ix, 13, 49950, 49950, 49800, 49850)
    assert E.signal("orb", E.bars5(ix), 13, 50100, 49900) == -1
    set_bar(ix, 14, 50050, 50200, 50050, 50100)     # close AT the edge is not beyond
    assert E.signal("orb", E.bars5(ix), 14, 50100, 49900) == 0


def test_orb_fresh_needs_previous_bar_inside():
    ix = ranged_ix()
    set_bar(ix, 12, 50050, 50200, 50050, 50150)
    set_bar(ix, 13, 50150, 50250, 50150, 50200)
    b5 = E.bars5(ix)
    assert E.signal("orb_fresh", b5, 12, 50100, 49900) == 1
    assert E.signal("orb_fresh", b5, 13, 50100, 49900) == 0
    assert E.signal("orb", b5, 13, 50100, 49900) == 1


def test_sweep_and_fade_are_reversals():
    ix = ranged_ix()
    set_bar(ix, 12, 50050, 50150, 50040, 50060)      # pokes above, closes back inside -> PE
    set_bar(ix, 16, 49950, 49960, 49850, 49950)      # pokes below, closes back inside -> CE
    b5 = E.bars5(ix)
    assert E.signal("orb_sweep", b5, 12, 50100, 49900) == -1
    assert E.signal("orb_sweep", b5, 16, 50100, 49900) == 1
    # fade: within 10% of the edge (w = 200 -> 20 points), closing inside
    set_bar(ix, 17, 50050, 50085, 50040, 50060)
    b5 = E.bars5(ix)
    assert E.signal("range_fade", b5, 17, 50100, 49900) == -1
    assert E.signal("orb_sweep", b5, 17, 50100, 49900) == 0
    set_bar(ix, 18, 50050, 50075, 50040, 50060)      # 50075 < 50080: not in the outer 10%
    assert E.signal("range_fade", E.bars5(ix), 18, 50100, 49900) == 0


def test_windows_match_the_app():
    assert E.WINDOW["orb"] == (10, 55)               # 10:05 .. 13:55
    assert E.WINDOW["orb_sweep"] == (10, 62)         # 10:05 .. 14:25
    assert E.WINDOW["range_fade"] == (15, 55)        # 10:30 .. 13:55


def test_atm_half_up():
    assert D.atm_strike(50050.0, 100) == 50100
    assert D.atm_strike(50049.99, 100) == 50000
    assert D.atm_strike(22025.0, 50) == 22050


# ------------------------------------------------------------------------------------------------ exits
def walk(a, e, P=40.0, tgt=40.0, lad=40.0, be=None, lock=(0, 0), tr=(0, 0), ts=(-1, 0.0), c0=0, sq=E.SQ_ROW, lot=15):
    a = a.astype(np.float64)
    be = e if be is None else be
    return E._walk(a[:, 0], a[:, 1], a[:, 2], a[:, 3], c0, e, E.floor_tick(e - P), tgt, lad, be, float(lot),
                   float(lock[0]), float(lock[1]), float(tr[0]), float(tr[1]), ts[0], ts[1], sq)


def test_stop_target_and_square_off():
    a = opt_path(200.0)
    a[50, 2] = 150.0                                  # low through the stop (160)
    r, p, why = walk(a, 200.0)
    assert (r, why) == (50, 0) and p == 160.0
    b = opt_path(200.0)
    b[30, 1] = 245.0                                  # high through the target: sell at the next open
    r, p, why = walk(b, 200.0)
    assert (r, why) == (31, 2)
    c = opt_path(200.0)
    r, p, why = walk(c, 200.0)
    assert (r, why) == (E.SQ_ROW, 4)


def test_ladder_locks_from_the_next_minute():
    a = opt_path(200.0)
    a[20, 1] = 221.0                                  # +21: rung 2 (+20 -> lock +10)
    a[20, 2] = 205.0                                  # the same minute's low does not hit the new lock
    a[21, 0], a[21, 2] = 215.0, 209.0                 # the next minute opens 215, its low hits the 210 lock
    r, p, why = walk(a, 200.0)
    assert (r, why) == (21, 1) and abs(p - 210.0) < 1e-9


def test_boss_lock_and_trail_without_target():
    a = opt_path(200.0)
    a[20:40, 1] = 300.0                               # peak +100 a unit = +1,500 a lot of 15
    a[20:40, 0] = a[20:40, 3] = 290.0
    a[20:40, 2] = 285.0
    a[40, 0], a[40, 2] = 290.0, 200.0
    r, p, why = walk(a, 200.0, tgt=0.0, lad=0.0, lock=(1000, 500), tr=(1000, 500))
    # trail: 300 - 500/15 = 266.67 -> ceil to the tick 266.70; the lock keeps 200 + 33.33 -> 233.35
    assert why == 1 and r == 40 and abs(p - 266.70) < 1e-6
    a[40, 0] = 250.0                                  # a gap open below the stop fills at the open
    assert abs(walk(a, 200.0, tgt=0.0, lad=0.0, lock=(1000, 500), tr=(1000, 500))[1] - 250.0) < 1e-9


def test_time_stop_fires_when_no_progress():
    a = opt_path(200.0)
    r, p, why = walk(a, 200.0, ts=(44, 10.0), tgt=0.0)
    assert (r, why) == (45, 3)
    b = opt_path(200.0, drift=0.5)                    # reaches +10 within 20 minutes: no time stop
    r, p, why = walk(b, 200.0, ts=(44, 10.0), tgt=0.0, lad=0.0)
    assert why != 3


def test_expiry_day_squares_off_at_1505():
    ix = ranged_ix()
    set_bar(ix, 12, 50050, 50200, 50050, 50150)
    k = D.atm_strike(ix[D.SPOT_ROW, 3], 100)
    s = session(ix, expiry=True, opts={(k, "C"): opt_path(200.0)})
    tr = E.run_session(s, E.baseline("orb"))
    assert len(tr) == 1 and tr[0]["exit_row"] == E.SQ_EXPIRY and tr[0]["entry_row"] == 65


def test_premium_at_or_below_stop_points_is_refused():
    ix = ranged_ix()
    set_bar(ix, 12, 50050, 50200, 50050, 50150)
    k = D.atm_strike(ix[D.SPOT_ROW, 3], 100)
    s = session(ix, opts={(k, "C"): opt_path(40.0)})
    assert E.run_session(s, E.baseline("orb")) == []


def test_side_cap_and_cooldown():
    ix = ranged_ix()
    for b in range(12, 40):
        set_bar(ix, b, 50150, 50200, 50150, 50150)   # beyond the high on every bar
    k = D.atm_strike(ix[D.SPOT_ROW, 3], 100)
    a = opt_path(200.0)
    a[70, 2] = 100.0                                  # the first trade stops at row 70
    s = session(ix, opts={(k, "C"): a})
    capped = E.run_session(s, E.baseline("orb"))
    uncapped = E.run_session(s, E.baseline("orb_uncapped"))
    assert len(capped) == 1
    assert len(uncapped) >= 2 and uncapped[1]["entry_row"] >= 75   # cooldown: the bar after the exit's bar


def test_charges_and_fills_are_adverse():
    assert E.buy_px(100.0, opt_path(100.0).astype(float), 50, 15) > 100.0
    assert E.sell_px(100.0, opt_path(100.0).astype(float), 50, 15) < 100.0
    assert E.charge(True, 100.0, 15, "BANKNIFTY") > 20.0
    assert E.charge(False, 100.0, 15, "SENSEX") != E.charge(False, 100.0, 15, "BANKNIFTY")


def test_itm_strike_is_one_step_in():
    s = session()
    assert E.strike_for(s, 1, 1) == s.atm - 100 and E.strike_for(s, -1, 1) == s.atm + 100


# ------------------------------------------------------------------------------------------------ filter rule, BH
def test_filter_rule_needs_the_same_worst_tercile_on_both_indices():
    rng = np.random.default_rng(0)
    tr = []
    for i in range(600):
        u = "BANKNIFTY" if i % 2 else "NIFTY"
        x = rng.normal()
        net = -100 - 200 * (x < -0.43)                # the low tercile of 'vix_prev' is worst on both
        f = {k: rng.normal() for k in E.FEATURES}
        f["vix_prev"], f["expiry"] = x, 0.0
        tr.append(dict(und=u, year=2022, net=net + rng.normal() * 5, feats=f))
    p = S.choose_filter(tr)["pick"]
    assert p["feature"] == "vix_prev" and p["worst"] == "low" and p["skip"][0] == -np.inf


def test_bh_matches_definition():
    q = E.bh([0.01, 0.04, 0.03, 0.5])
    assert np.allclose(q, [0.04, 0.16 / 3, 0.16 / 3, 0.5])   # 0.04 x 4 / 3 caps 0.03 x 4 / 2


# ------------------------------------------------------------------------------------------------ holdout safety
def test_holdout_index_is_refused_without_the_flag():
    for u in K.HOLDOUT_UNDS:
        with pytest.raises(PermissionError):
            D.load(u)
        if not K.HOLDOUT_FLAG.exists():
            with pytest.raises(PermissionError):
                D.load(u, "holdout")


def test_holdout_and_design_indices_do_not_overlap():
    assert not set(K.DESIGN_UNDS) & set(K.HOLDOUT_UNDS)
    assert "BANKNIFTY" in K.DESIGN_UNDS


# ------------------------------------------------------------------------------------------------ the look-ahead guard
def random_session(seed):
    rng = np.random.default_rng(seed)
    ix = np.full((N, 4), np.nan)
    p = 50000 + np.cumsum(rng.normal(0, 25, N))
    o = np.r_[p[0], p[:-1]]
    ix[:, 0], ix[:, 3] = o, p
    ix[:, 1] = np.maximum(o, p) + rng.uniform(0, 20, N)
    ix[:, 2] = np.minimum(o, p) - rng.uniform(0, 20, N)
    k0 = D.atm_strike(ix[D.SPOT_ROW, 3], 100)
    opts = {}
    for k in np.arange(k0 - 400, k0 + 401, 100):
        for side, sg in (("C", 1), ("P", -1)):
            intr = np.maximum(sg * (p - k), 0)
            px = intr + 150 + rng.normal(0, 3, N).cumsum() * 0.2
            a = np.zeros((N, 6), dtype=np.float32)
            a[:, 0] = np.r_[px[0], px[:-1]]
            a[:, 3] = px
            a[:, 1] = np.maximum(a[:, 0], px) + 1
            a[:, 2] = np.minimum(a[:, 0], px) - 1
            a[:, 4] = rng.integers(0, 5000, N)
            a[:, 5] = 1
            opts[(float(k), side)] = a
    vix = 14 + np.cumsum(rng.normal(0, 0.05, N))
    s = D.Sess(date(2024, 1, 10), "BANKNIFTY", ix, vix, opts, None, False, 15, (50000, 50200, 49800, 50000), 400.0,
               14.0, k0)
    return s


def poisoned(s, t):
    """Every row after t replaced by noise."""
    from copy import deepcopy
    d = deepcopy(s)
    rng = np.random.default_rng(99)
    d.ix[t + 1:] = rng.uniform(1, 1e6, d.ix[t + 1:].shape)
    d.vix[t + 1:] = rng.uniform(1, 100, d.vix[t + 1:].shape)
    for k in d.opts:
        d.opts[k][t + 1:] = rng.uniform(1, 1e4, d.opts[k][t + 1:].shape).astype(np.float32)
    return d


@pytest.mark.parametrize("seed", range(25))
def test_lookahead_signals_features_and_entries(seed):
    """Each decision (signal, feature, the filter's skip, the entry price) is unchanged when every minute after it is
    noise. Checked for every arm and the C variants' filters."""
    s = random_session(seed)
    for arm in E.ARMS:
        b5 = E.bars5(s.ix)
        hi, lo = E.opening_range(b5)
        first, last = E.WINDOW[arm]
        for b in range(first, last + 1):
            row = 5 * b + 4
            p = poisoned(s, row)
            pb5 = E.bars5(p.ix)
            assert E.opening_range(pb5) == (hi, lo)
            sg = E.signal(arm, b5, b, hi, lo)
            assert E.signal(arm, pb5, b, hi, lo) == sg
            if sg:
                f1, f2 = E.features(s, sg, row, hi, lo), E.features(p, sg, row, hi, lo)
                assert all((np.isnan(f1[k]) and np.isnan(f2[k])) or f1[k] == f2[k] for k in f1)
    # the entry (the next minute's open, its fill) needs row + 1 only
    for arm in E.ARMS:
        tr = E.run_session(s, E.baseline(arm))
        for t in tr:
            p = poisoned(s, t["entry_row"])
            t2 = E.simulate(p, E.baseline(arm), t["side"], t["sig_row"], t["k"], K.PTS["BANKNIFTY"])
            assert t2 is not None and t2["entry"] == t["entry"]


@pytest.mark.parametrize("seed", range(5))
def test_exits_do_not_peek(seed):
    """The exit decided at row r is the same when every row after r is noise (the walk reads rows <= r)."""
    s = random_session(seed + 100)
    for v in [E.baseline("orb"), E.baseline("orb_sweep"),
              E.Variant("x", "orb", side_cap=1, tgt_mult=0.0, lock_rs=(1000.0, 500.0), trail_rs=(1000.0, 500.0),
                        time_stop=(45, 0.25))]:
        for t in E.run_session(s, v):
            if t["why"] in ("stop", "lock"):
                p = poisoned(s, t["exit_row"])
                t2 = E.simulate(p, v, t["side"], t["sig_row"], t["k"], K.PTS["BANKNIFTY"])
                assert t2["exit_row"] == t["exit_row"] and t2["exit"] == t["exit"]


def test_python_and_numba_walk_agree():
    py = getattr(E._walk, "py_func", None)
    if py is None:
        pytest.skip("numba not installed: only the Python walk exists")
    rng = np.random.default_rng(5)
    for _ in range(50):
        a = opt_path(200.0)
        a[:, 1] += rng.uniform(0, 40, N)
        a[:, 2] -= rng.uniform(0, 40, N)
        args = (a[:, 0].astype(float), a[:, 1].astype(float), a[:, 2].astype(float), a[:, 3].astype(float), 10, 200.0,
                160.0, 0.0, 40.0, 201.0, 15.0, 1000.0, 500.0, 1000.0, 500.0, 54, 10.0, E.SQ_ROW)
        r1, r2 = E._walk(*args), py(*args)
        assert r1[0] == r2[0] and r1[2] == r2[2] and ((np.isnan(r1[1]) and np.isnan(r2[1])) or abs(r1[1] - r2[1]) < 1e-9)
