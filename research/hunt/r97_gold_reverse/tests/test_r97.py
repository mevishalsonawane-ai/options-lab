"""R97 tests: swing measurement, real-time triggers, the feature look-ahead guard and edge cases (synthetic data
only; no lake needed)."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r97_gold_reverse import features as FE
from research.hunt.r97_gold_reverse import moves as M


# ------------------------------------------------------------------------------------------------ zig-zag
def test_zigzag_legs_and_confirmations():
    F = np.array([100, 120, 90, 60, 80, 150, 300, 250, 280, 200, 210, 100, 130], float)
    legs = M.zigzag(F, 50)
    assert [(g.i0, g.i1, g.d, g.size) for g in legs] == [(0, 1, 1, 20), (1, 3, -1, 60), (3, 6, 1, 240),
                                                          (6, 11, -1, 200)]
    # the 300 top is confirmed when price is back 50 from it (index 7, 250); the last low is never confirmed
    assert legs[2].ic == 7 and legs[3].ic == -1


def test_zigzag_quiet_day_is_one_leg():
    F = np.array([100, 101, 99, 103, 102], float)
    legs = M.zigzag(F, 50)
    assert len(legs) == 1 and legs[0].size == 4 and legs[0].d == 1


def test_zigzag_empty_and_flat():
    assert M.zigzag(np.array([], float), 50) == []
    assert M.zigzag(np.array([5.0]), 50) == []
    assert M.zigzag(np.full(10, 7.0), 50) == []


def test_zigzag_no_leg_retraces_more_than_X():
    rng = np.random.default_rng(1)
    F = 1000 + np.cumsum(rng.normal(0, 10, 3000))
    X = 60
    for g in M.zigzag(F, X):
        seg = F[g.i0:g.i1 + 1]
        if g.d > 0:
            assert np.max(np.maximum.accumulate(seg) - seg) < X
        else:
            assert np.max(seg - np.minimum.accumulate(seg)) < X
        assert g.size == pytest.approx(abs(F[g.i1] - F[g.i0]))


def test_day_stats_round_trip_and_counts():
    F = np.r_[np.linspace(1000, 1500, 51), np.linspace(1490, 1000, 50)]   # up 500 then all the way back
    s = M.day_stats(F, (100,), (200, 500))
    assert s["range"] == 500 and s["oc"] == 0 and s["round_trip"] == pytest.approx(1.0)
    assert s["n_legs_100_500"] == 2 and s["max_leg_100"] == 500
    assert M.day_stats(np.array([1.0]), (100,), (200,)) == {}


# ------------------------------------------------------------------------------------------------ triggers
def test_trigger_points_are_causal():
    rng = np.random.default_rng(2)
    F = 1000 + np.cumsum(rng.normal(0, 15, 800))
    full = M.trigger_points(F, 50)
    for i, d, p in full[:40]:
        part = M.trigger_points(F[:i + 1], 50)
        assert part[-1] == (i, d, p)                      # the same trigger exists with the future cut off


def test_follow_trail_failed_and_day_end():
    F = np.array([100, 160, 200, 260, 250, 190], float)
    t = M.follow(F, 1, 1, 100.0, 60)                       # long from 160 (pivot 100), trail 60
    assert t.why == "trail" and t.best == 260 and t.exit == 190 and t.capture == 30 and t.swing == 160
    F2 = np.array([100, 160, 120, 90], float)
    t2 = M.follow(F2, 1, 1, 100.0, 60)                     # back below the pivot: the move failed
    assert t2.why == "failed" and t2.exit == 90 and t2.capture == -70
    F3 = np.array([100, 160, 170, 180], float)
    t3 = M.follow(F3, 1, 1, 100.0, 60)
    assert t3.why == "day_end" and t3.capture == 20
    t4 = M.follow(np.array([300, 240, 230, 280], float), 1, -1, 300.0, 40)
    assert t4.why == "trail" and t4.capture == pytest.approx(-40)


# ------------------------------------------------------------------------------------------------ features
def _session(n=400, seed=3, start="2026-03-02 09:00"):
    rng = np.random.default_rng(seed)
    ts = pd.date_range(start, periods=n, freq="1min")
    ts = ts.delete([k for k in (50, 51, 200) if k < n])   # gaps in the grid
    F = 150000 + np.cumsum(rng.normal(0, 40, len(ts)))
    tod = (ts.hour * 60 + ts.minute).values
    vol = pd.Series(rng.integers(0, 50, len(ts)).astype(float), index=ts)
    xau = pd.Series(4000 + np.cumsum(rng.normal(0, 0.5, n + 30)),
                    index=pd.date_range(pd.Timestamp(start) - pd.Timedelta(minutes=20), periods=n + 30, freq="1min"))
    ag = pd.Series(180000 + np.cumsum(rng.normal(0, 50, n + 30)), index=xau.index)
    prev = dict(high=float(F[0] + 300), low=float(F[0] - 300), close=float(F[0] - 50))
    ev = [("cpi", int(tod[min(150, len(tod) - 1)]))]
    return ts, F, tod, prev, vol, xau, ag, ev


def test_features_lookahead_guard():
    """Row i must not change when everything after row i (MCX rows, volume, context minutes) is removed."""
    ts, F, tod, prev, vol, xau, ag, ev = _session()
    full = FE.day_features(ts, F, tod, prev, vol, xau, ag, ev)
    for i in (0, 5, 29, 30, 31, 49, 50, 120, 199, 250, len(ts) - 1):
        cut = ts[i] + pd.Timedelta(minutes=1)
        part = FE.day_features(ts[:i + 1], F[:i + 1], tod[:i + 1], prev, vol[vol.index <= ts[i]],
                               xau[xau.index <= cut], ag[ag.index <= cut], ev)
        a, b = full.iloc[i], part.iloc[i]
        for c in full.columns:
            x, y = a[c], b[c]
            if isinstance(x, (float, np.floating)) and np.isnan(x):
                assert np.isnan(y), (i, c)
            else:
                assert x == pytest.approx(y), (i, c)


def test_features_context_known_only_after_close():
    """A context value stamped later than the MCX close (ts + 1 min) is not used."""
    ts, F, tod, prev, vol, xau, ag, ev = _session(n=60)
    x2 = xau.copy()
    k = ts[40] + pd.Timedelta(minutes=2)                   # one minute after row 40's close
    x2.loc[k] = 1e9
    a = FE.day_features(ts, F, tod, prev, vol, xau, ag, ev)
    b = FE.day_features(ts, F, tod, prev, vol, x2.sort_index(), ag, ev)
    assert a.iloc[40].xau5_bp == pytest.approx(b.iloc[40].xau5_bp)


def test_features_missing_inputs():
    ts, F, tod, prev, vol, xau, ag, ev = _session(n=40)
    fe = FE.day_features(ts, F, tod, None, None, None, None, None)
    assert fe.vol_ratio.isna().all() and fe.xau15_bp.isna().all() and fe.ev_rel.isna().all()
    assert not fe.x_up_pdh15.any() and fe.gap.isna().all()
    r = FE.directional(fe, 10, -1)
    assert np.isnan(r["gap"]) and r["at_event"] is False


def test_features_breaks_and_direction():
    ts = pd.date_range("2026-03-02 09:00", periods=120, freq="1min")
    tod = (ts.hour * 60 + ts.minute).values
    F = np.r_[np.full(30, 1000.0), np.linspace(1000, 2100, 90)]
    prev = dict(high=1500.0, low=900.0, close=980.0)
    fe = FE.day_features(ts, F, tod, prev)
    i = int(np.argmax(F > 1500))
    assert fe.x_up_pdh15.iloc[i] and not fe.x_up_round15.iloc[i]
    assert fe.x_up_round15.iloc[int(np.argmax(F > 2000))]
    assert fe.x_up_or15.iloc[30:].any() and not fe.x_up_or15.iloc[:30].any()   # OR known from 09:30
    up, dn = FE.directional(fe, i, 1), FE.directional(fe, i, -1)
    assert up["break_pd"] and not dn["break_pd"] and up["ret15"] == -dn["ret15"] > 0
    assert up["gap"] == pytest.approx(20.0)


# ------------------------------------------------------------------------------------------------ engine
from research.hunt.r97_gold_reverse import engine as EN  # noqa: E402

NAN = float("nan")


def _arr(v):
    a = np.asarray(v, float)
    return a, a.copy(), a.copy(), a.copy(), np.full(len(a), np.nan)


def test_walk_underlying_trail_and_stop():
    o, h, l, c, mdl = _arr([100, 101, 103, 104, 102, 101])
    F = np.array([1000, 1050, 1150, 1200, 1100, 1090], float)
    k, why, px, md = EN.walk(o, h, l, c, mdl, F, 1, 100.0, 101.0, 900.0, 100.0, 0.10, ladder_pts=())
    assert (k, why, px) == (4, "utrail", None)                 # back 100 from the 1200 best at minute 4
    k, why, *_ = EN.walk(o, h, l, c, mdl, np.array([1000, 950, 899, 880, 870, 860.0]), 1, 100.0, 101.0, 900.0,
                         None, 0.10, ladder_pts=())
    assert (k, why) == (2, "ustop")
    # short side: the stop is above, the trail measures from the lowest close
    k, why, *_ = EN.walk(o, h, l, c, mdl, np.array([1000, 950, 900, 1010, 1020, 1030.0]), -1, 100.0, 101.0, 1100.0,
                         100.0, 0.10, ladder_pts=())
    assert (k, why) == (3, "utrail")


def test_walk_premium_stop_before_underlying_checks():
    o = np.array([100, 95, 80, 85.0])
    h, l, c, mdl = o.copy(), np.array([100, 94, 79, 84.0]), o.copy(), np.full(4, np.nan)
    k, why, px, md = EN.walk(o, h, l, c, mdl, np.full(4, 1000.0), 1, 100.0, 101.0, 900.0, None, 0.10, ladder_pts=())
    assert (k, why, px) == (2, "stop", 80.0)                    # opened below the 90 stop: out at the open


def test_points_lock_ladder_and_trail():
    lock = EN.lock_pts_update(-np.inf, 150.0, 100.0, 104.0, ((30, NAN), (60, 30)), 40.0)
    assert lock == pytest.approx(104.0)                         # +50: breakeven only (+60 not reached)
    lock = EN.lock_pts_update(-np.inf, 125.0, 100.0, 104.0, ((30, NAN), (60, 30)), 40.0)
    assert lock == -np.inf                                      # +25: nothing locked yet
    lock = EN.lock_pts_update(-np.inf, 135.0, 100.0, 104.0, ((30, NAN), (60, 30)), 40.0)
    assert lock == pytest.approx(104.0)                         # +35: breakeven (fill + costs)
    lock = EN.lock_pts_update(-np.inf, 200.0, 100.0, 104.0, ((30, NAN), (60, 30)), 40.0)
    assert lock == pytest.approx(160.0)                         # past the last step: peak - 40
    # the walk exits at the lock once the premium falls back
    o = np.array([100, 120, 140, 136, 108.0])
    lo = np.array([100, 120, 140, 136, 100.0])                 # minute 4 opens above the lock, trades through it
    k, why, px, _ = EN.walk(o, o.copy(), lo, o.copy(), np.full(5, np.nan), np.full(5, 1000.0), 1, 100.0,
                            104.0, 0.0, None, 0.5, ladder_pts=((30, NAN), (60, 30)), trail_gap_pts=40.0)
    assert (k, why, px) == (4, "lock", 104.0)


def test_sim_fills_next_print_and_exits_same_day():
    from research.hunt.r96_mcx_buy.tests.test_r96 import make_book
    up = lambda i, ts: 5000.0 + np.where(ts.hour >= 10, 2.0 * np.minimum(i % 871 - 60, 300), 0.0)  # noqa: E731
    bk = make_book(days=2, path=up)
    m = bk.m
    i = m.at_or_after(m.days[0], 10 * 60)
    t = EN.sim(bk, i, 1, float(m.F[i] - 100), 50.0, 12 * 60, 0, 5, None, 0.10, ((30, NAN),), 20.0)
    assert t is not None and t["entry_ts"] == pd.Timestamp(m.ts[i + 1]) and t["exit_ts"].hour <= 12
    assert t["exit_ts"].date() == t["entry_ts"].date() and t["gross"] > 0 and t["cost"] > 0
    # no trade on the option expiry day, none when nothing prints within the wait
    bk2 = make_book(days=2, path=up, exp_day=str(pd.Timestamp(m.days[0]).date()))
    assert EN.sim(bk2, bk2.m.at_or_after(bk2.m.days[0], 600), 1, NAN, 50.0, None) is None
    bk3 = make_book(days=1, drop_prints=lambda O: (O.ts.dt.hour == 10))
    assert EN.sim(bk3, bk3.m.at_or_after(bk3.m.days[0], 600), 1, NAN, 50.0, None) is None


# ------------------------------------------------------------------------------------------------ rules
def _wave_book(days=3):
    from research.hunt.r96_mcx_buy.tests.test_r96 import make_book
    wave = lambda i, ts: 5000.0 + 400 * np.sin(i / 37.0) + 3.0 * (i % 871)   # noqa: E731  swings + a drift
    return make_book(days=days, path=wave)


def test_rule_signals_do_not_use_the_future():
    """Every rule's signals up to minute i are the same when the sessions are cut after minute i."""
    from research.hunt.r53.mcx.data import Market
    from research.hunt.r96_mcx_buy.data import Book
    from research.hunt.r97_gold_reverse import rules as R
    bk = _wave_book()
    m = bk.m
    period = (pd.Timestamp(m.days[0]).date(), pd.Timestamp(m.days[-1]).date())
    ctx = dict(vol=None, xau=None, ag=None, evm={})
    cut = m.day_first[m.days[1]] + 400                       # in the middle of the second session
    m2 = Market("SYN", m.ts[:cut + 1], m.F[:cut + 1], None)
    m2.k_at = {k: v[:cut + 1] for k, v in m.k_at.items()}
    bk2 = Book("GOLDM", m2, {}, pd.DataFrame(), 10.0, "gold", ())
    for rid in ("R97A", "R97B", "R97C", "R97D", "R97F", "R97G"):
        a = [(s["i_sig"], s["side"], s["stop_pts"]) for s in R.RULES[rid].candidates(bk, period, dict(ctx))
             if s["i_sig"] <= cut]
        b = [(s["i_sig"], s["side"], s["stop_pts"]) for s in R.RULES[rid].candidates(bk2, period, dict(ctx))]
        assert a == b, rid
    assert R.RULES["R97E"].candidates(bk, period, dict(ctx)) == []     # no XAUUSD: no signal


def test_rule_specs_respect_windows_and_exits():
    from research.hunt.r97_gold_reverse import rules as R
    bk = _wave_book()
    m = bk.m
    period = (pd.Timestamp(m.days[0]).date(), pd.Timestamp(m.days[-1]).date())
    ctx = dict(vol=None, xau=None, ag=None, evm={})
    sp = R.RULES["R97D"].candidates(bk, period, dict(ctx))
    assert sp and all(R.FIRST_TOD <= s["tod"] <= 690 for s in sp)
    for s in sp:
        assert s["prem_stop"] > 0 and s["ladder_pts"] and np.isfinite(s["ustop"])
        assert s["side"] * (m.F[s["i_sig"]] - s["ustop"]) == pytest.approx(s["stop_pts"])
