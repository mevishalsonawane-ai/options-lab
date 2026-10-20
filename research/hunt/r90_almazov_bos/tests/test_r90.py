"""R90 tests (PREREG section 9): small hand-made cases and the look-ahead guard. No lake data needed."""
from __future__ import annotations

from datetime import date

import numpy as np
import pytest

from research.hunt.r90_almazov_bos import model
from research.hunt.r90_almazov_bos.model import View, run_as_written, run_session, setup
from research.hunt.r90_almazov_bos.structure import Struct, aggregate, path, reach, structure, subpath, swings

# a hand-drawn series (D = 2): low at 2, high at 6 (uptrend), BOS at 10, new high at 11 (L moves to the low at 8),
# CHoCH down at 15
H_ = [10, 9, 8, 9, 10, 11, 12, 11, 10, 11, 13, 14, 12, 12.5, 11, 10]
L_ = [9, 8, 7, 8, 9, 10, 11, 10, 9, 10, 12, 13, 11, 11.5, 10, 8.5]


def ohlc(hs, ls):
    h, l = np.array(hs, float), np.array(ls, float)
    m = (h + l) / 2
    return m.copy(), h, l, m.copy()


# ------------------------------------------------------------------------------------------------ bars and the path
def test_aggregate_epoch_aligned_and_never_across_sessions():
    # 09:15 IST is unix minute 225 of the UTC day; 225 % 4 = 1, so the first 4-minute bucket holds 09:15-09:17 only
    day0 = (date(2025, 1, 6) - date(1970, 1, 1)).days * 1440
    tod = np.r_[np.arange(555, 565), np.arange(555, 565)]
    mday = np.r_[np.zeros(10, int), np.ones(10, int)]
    ep = np.r_[day0 + tod[:10] - 330, day0 + 1440 + tod[10:] - 330]
    x = np.arange(20, dtype=float)
    B = aggregate(mday, ep, 4, x, x + 1, x - 1, x + 0.5)
    assert list(B["s"]) == [0, 3, 7, 10, 13, 17]
    assert list(B["e"]) == [2, 6, 9, 12, 16, 19]
    assert B["o"][1] == 3 and B["h"][1] == 7 and B["l"][1] == 2 and B["c"][1] == 6.5
    D = aggregate(mday, ep, 0, x, x + 1, x - 1, x + 0.5)
    assert list(D["s"]) == [0, 10] and list(D["day"]) == [0, 1]
    F = aggregate(mday, ep, 5, x, x, x, x)
    assert list(F["s"]) == [0, 5, 10, 15]                       # 5m bars start at the session open


def test_path_nearer_extreme_first_and_reach():
    assert path(10, 12, 7, 9) == (10, 12, 7, 9)                 # high nearer (2 < 3)
    assert path(10, 14, 8, 9) == (10, 8, 14, 9)                 # low nearer
    assert path(10, 12, 8, 9) == (10, 12, 8, 9)                 # tie -> high first
    seq = (10, 12, 7, 9)
    assert reach(seq, 11, True, True) == 1.0
    assert reach(seq, 8, False, True) == 2 + 4
    assert reach(seq, 12, True, True) == np.inf                 # strict: never ABOVE 12
    assert reach(seq, 12, True, False) == 2.0                   # touch
    assert reach((13, 14, 12, 13), 12.5, True, True) == 0.0     # opened beyond
    assert subpath(seq, 3.0) == (11.0, 7, 9)


# ------------------------------------------------------------------------------------------------ structure
def test_swings_confirmed_d_bars_later():
    o, h, l, c = ohlc(H_, L_)
    hs, ls = swings(h, l, 2)
    assert ls[2] and hs[6] and ls[8] and hs[11]
    S = structure(o, h, l, c, 2)
    assert S.trend[7] == 0                                      # the high at 6 is not confirmed until bar 8 closes
    assert S.trend[8] == 1 and S.H[8] == 12 and S.Hid[8] == 6 and S.L[8] == 7 and S.Lid[8] == 2
    assert S.Hok[8]


def test_bos_new_leg_and_protected_low():
    o, h, l, c = ohlc(H_, L_)
    S = structure(o, h, l, c, 2)
    assert S.bos[10] == 1 and not S.Hok[10] and S.H[10] == 12   # broken, kept until a new swing high
    assert S.H[12] == 12                                        # the high at 11 needs bar 13 to close
    assert S.H[13] == 14 and S.Hid[13] == 11 and S.Hok[13]
    assert S.L[13] == 9 and S.Lid[13] == 8                      # the swing low between the two highs is protected


def test_choch_down_rebuilds():
    o, h, l, c = ohlc(H_, L_)
    S = structure(o, h, l, c, 2)
    assert S.choch[15] == -1 and S.trend[15] == -1
    assert S.H[15] == 14 and S.Hid[15] == 11                    # the failed up-leg's top is the protected high
    assert np.isnan(S.L[15]) and not S.Lok[15]                  # no active low until a new swing low confirms


def test_downtrend_start_and_mirror():
    o, h, l, c = ohlc([-x for x in L_], [-x for x in H_])       # the mirror image
    S = structure(o, h, l, c, 2)
    assert S.trend[8] == -1 and S.L[8] == -12 and S.H[8] == -7 and S.Lok[8]
    assert S.bos[10] == -1
    assert S.L[13] == -14 and S.H[13] == -9
    assert S.choch[15] == 1 and S.trend[15] == 1 and S.L[15] == -14


@pytest.mark.parametrize("bar,expect_bos", [((10, 13, 6, 8), 1), ((9, 13, 6, 8), 0)])
def test_outside_bar_path_order(bar, expect_bos):
    o, h, l, c = ohlc(H_[:9], L_[:9])
    o, h, l, c = (np.r_[a, b] for a, b in zip((o, h, l, c), bar))
    S = structure(o, h, l, c, 2)
    assert S.trend[8] == 1
    assert S.choch[9] == -1 and S.trend[9] == -1                # the protected low (7) is broken either way
    assert S.bos[9] == expect_bos                               # BOS only if the path reached 12 before 7
    if expect_bos:
        assert S.H[9] == 13                                     # the bar's own high tops the failed leg


def test_reset_restarts():
    o, h, l, c = ohlc(H_ + H_, L_ + L_)
    reset = np.zeros(len(o), bool)
    reset[16] = True
    S = structure(o, h, l, c, 2, reset)
    assert S.trend[16 + 7] == 0 and S.trend[16 + 8] == 1


# ------------------------------------------------------------------------------------------------ setups
def flat_struct(n, tr, H, Hid, Hok, L, Lid, Lok):
    f = lambda v, dt: np.full(n, v, dt)  # noqa: E731
    return Struct(f(tr, np.int8), f(H, float), f(Hid, np.int64), f(Hok, bool), f(L, float), f(Lid, np.int64),
                  f(Lok, bool), np.zeros(n, np.int8), np.zeros(n, np.int8))


def flat_view(n, tr, H, Hid, Hok, L, Lid, Lok):
    S = flat_struct(n, tr, H, Hid, Hok, L, Lid, Lok)
    return View(S.trend, S.H, S.Hid, S.Hok, S.L, S.Lid, S.Lok)


def test_setup_long_short_and_filters():
    S = flat_struct(3, 1, 100.0, 5, True, 95.0, 3, False)
    V = flat_view(3, 1, 110.0, 7, True, 90.0, 2, False)
    s = setup(0, 99.0, S, V, 1.0, 0.05, set(), set())
    assert s[0] == 1 and s[1] == 100 and s[2] == pytest.approx(94.95) and s[3] == 110 and s[4:] == (5, 7)
    assert setup(0, 100.0, S, V, 1.0, 0.05, set(), set()) is None          # close not below the entry
    assert setup(0, 99.0, S, V, 2.0, 0.05, set(), set()) is None           # RR 10 / 5.05 < 2
    assert setup(0, 99.0, S, V, 1.0, 0.05, {5}, set()) is None             # chart level already used
    assert setup(0, 99.0, S, V, 1.0, 0.05, set(), {7}) is None             # HTF target already hit
    Vd = flat_view(3, -1, 110.0, 7, False, 90.0, 2, True)
    assert setup(0, 99.0, S, Vd, 1.0, 0.05, set(), set()) is None          # trends differ
    assert setup(0, 99.0, S, V, 1.0, 0.05, set(), set(), daily=np.array([-1, -1, -1])) is None
    V2 = flat_view(3, 1, 99.5, 7, True, 90.0, 2, False)
    assert setup(0, 99.0, S, V2, 1.0, 0.05, set(), set()) is None          # HTF target not beyond the entry
    Ss = flat_struct(3, -1, 105.0, 4, False, 100.0, 6, True)
    Vs = flat_view(3, -1, 120.0, 1, False, 90.0, 8, True)
    s = setup(0, 101.0, Ss, Vs, 1.0, 0.05, set(), set())
    assert s[0] == -1 and s[1] == 100 and s[2] == pytest.approx(105.05) and s[3] == 90


def test_htf_view_uses_closed_htf_bars_only():
    Sh = flat_struct(3, 1, 0.0, 0, True, 0.0, 0, False)
    Sh.H[:] = [10.0, 20.0, 30.0]
    V = model.htf_view(np.array([0, 5, 10, 15, 20, 25]), np.array([0, 10, 20]), Sh)
    assert list(V.trend) == [0, 0, 1, 1, 1, 1]
    assert list(V.H[2:]) == [10.0, 10.0, 20.0, 20.0]


# ------------------------------------------------------------------------------------------------ the indicator's own run
def bars_from(rows):
    a = np.array(rows, float)
    return dict(o=a[:, 0], h=a[:, 1], l=a[:, 2], c=a[:, 3])


def test_as_written_gap_fill_target_and_target_used_once():
    B = bars_from([(98, 99, 97, 99), (101, 104, 100.5, 103), (103, 111, 102, 110), (99, 99.5, 98, 99),
                   (99, 103, 98.5, 102), (102, 112, 101, 111)])
    S = flat_struct(6, 1, 100.0, 5, True, 95.0, 3, False)
    V = flat_view(6, 1, 110.0, 7, True, 90.0, 2, False)
    W = run_as_written(B, S, V, 1.0, 0.05)
    assert len(W) == 1                                          # the chart level and the HTF target are used once
    w = W[0]
    assert w["k_in"] == 1 and w["fill"] == 101 and w["why"] == "target" and w["exit"] == 110 and w["k_out"] == 2
    assert w["pts"] == pytest.approx(9)


def test_as_written_stop_and_target_in_one_bar_path():
    # filled at 100 on the way up to 111 (high nearer the open), target 110 first; then a second case low-first
    B = bars_from([(98, 99, 97, 99), (99.5, 111, 94, 95)])
    S = flat_struct(2, 1, 100.0, 5, True, 95.0, 3, False)
    V = flat_view(2, 1, 110.0, 7, True, 90.0, 2, False)
    w = run_as_written(B, S, V, 1.0, 0.05)[0]
    assert w["why"] == "target" and w["exit"] == 110
    B = bars_from([(98, 99, 97, 99), (98, 111, 85, 95)])        # low nearer: down first (no fill), up through 100 to 111
    w = run_as_written(B, S, V, 1.0, 0.05)[0]
    assert w["fill"] == 100 and w["why"] == "target"
    B = bars_from([(98, 99, 97, 99), (99.5, 101, 90, 95)])      # up to 101 (fill), then down through the stop
    w = run_as_written(B, S, V, 1.0, 0.05)[0]
    assert w["why"] == "stop" and w["exit"] == pytest.approx(94.95) and w["pts"] == pytest.approx(-5.05)


def test_as_written_gap_through_stop_exits_at_open():
    B = bars_from([(98, 99, 97, 99), (99, 101, 98.5, 100.5), (90, 92, 89, 91)])
    S = flat_struct(3, 1, 100.0, 5, True, 95.0, 3, False)
    V = flat_view(3, 1, 110.0, 7, True, 90.0, 2, False)
    w = run_as_written(B, S, V, 1.0, 0.05)[0]
    assert w["k_out"] == 2 and w["why"] == "stop" and w["exit"] == 90


# ------------------------------------------------------------------------------------------------ session rules
def minute_case(highs, lows, closes=None, opens=None):
    mh, ml = np.array(highs, float), np.array(lows, float)
    mc = (mh + ml) / 2 if closes is None else np.array(closes, float)
    mo = mc.copy() if opens is None else np.array(opens, float)
    n = len(mh)
    mt = 9 * 60 + 15 + np.arange(n)
    return dict(mo=mo, mh=mh, ml=ml, mc=mc, mt=mt)


def test_session_fill_minute_target_and_busy():
    hs = [99, 99, 99, 99, 99, 99.5, 100.2, 104, 108, 110.5, 99, 99, 99, 99, 99, 100.5, 101, 101, 101, 101]
    ls = [97, 97, 97, 97, 97, 98, 99.5, 100, 103, 107, 97, 97, 97, 97, 97, 99, 99, 99, 99, 99]
    cs = [98] * 5 + [99, 100, 103, 107, 110] + [98] * 5 + [100, 100, 100, 100, 100]
    M = minute_case(hs, ls, cs)
    B = dict(o=np.array([98, 99, 98, 100.0]), c=np.array([98, 110, 98, 100.0]), s=np.array([0, 5, 10, 15]),
             e=np.array([4, 9, 14, 19]), day=np.zeros(4, int))
    S = flat_struct(4, 1, 100.0, 5, True, 95.0, 3, False)
    V = flat_view(4, 1, 110.0, 7, True, 90.0, 2, False)
    sq = np.array([19])
    T = run_session(M, B, S, V, 1.0, 0.05, sq, 9 * 60 + 16, 14 * 60 + 45, np.array([True]))
    assert len(T) == 1                                          # the HTF target (id 7) is used, no second trade
    t = T[0]
    assert t["t"] == 6 and t["fill"] == 100 and t["why"] == "target" and t["xm"] == 9 and t["pts"] == pytest.approx(10)
    # the fill minute (09:21) before the window opens -> no trade from bar 1; bar 3's fill at 09:30 is allowed
    T = run_session(M, B, S, V, 1.0, 0.05, sq, 9 * 60 + 30, 14 * 60 + 45, np.array([True]))
    assert [t["k"] for t in T] == [3] and T[0]["t"] == 15
    # a bar of another session never fills an order set at the previous session's last bar
    B2 = dict(B, day=np.array([0, 1, 1, 1]))
    T = run_session(M, B2, S, V, 1.0, 0.05, np.array([19, 19]), 9 * 60 + 16, 14 * 60 + 45, np.array([True, True]))
    assert all(t["k"] != 1 for t in T)


def test_session_flat_time_exit():
    hs = [99] * 5 + [100.5, 101, 101, 101, 101]
    ls = [97] * 5 + [99, 99.5, 99.5, 99.5, 99.5]
    M = minute_case(hs, ls, [98] * 5 + [100.2] * 5)
    B = dict(o=np.array([98, 99.0]), c=np.array([98, 100.2]), s=np.array([0, 5]), e=np.array([4, 9]),
             day=np.zeros(2, int))
    S = flat_struct(2, 1, 100.0, 5, True, 95.0, 3, False)
    V = flat_view(2, 1, 110.0, 7, True, 90.0, 2, False)
    T = run_session(M, B, S, V, 1.0, 0.05, np.array([8]), 9 * 60 + 16, 14 * 60 + 45, np.array([True]))
    # minute 5 opened at 100.2, above the entry: a gap fill at the open
    assert T[0]["fill"] == pytest.approx(100.2)
    assert T[0]["why"] == "time" and T[0]["xm"] == 8 and T[0]["pts"] == pytest.approx(M["mo"][8] - 100.2)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _synthetic(seed: int, days: int = 4):
    rng = np.random.default_rng(seed)
    n = 375 * days
    c = 20000 + np.cumsum(rng.normal(0, 6, n))
    o = np.r_[c[0], c[:-1]] + rng.normal(0, 1, n)
    h = np.maximum(o, c) + np.abs(rng.normal(0, 3, n))
    l = np.minimum(o, c) - np.abs(rng.normal(0, 3, n))
    mday = np.repeat(np.arange(days), 375)
    tod = np.tile(9 * 60 + 15 + np.arange(375), days)
    ep = (mday + 20000) * 1440 + tod - 330
    return dict(mo=o, mh=h, ml=l, mc=c, mt=tod), mday, ep


def _run_all(M, mday, ep, tf=5, htf=20, D=2):
    Bk = aggregate(mday, ep, tf, M["mo"], M["mh"], M["ml"], M["mc"])
    Hk = aggregate(mday, ep, htf, M["mo"], M["mh"], M["ml"], M["mc"])
    Sl = structure(Bk["o"], Bk["h"], Bk["l"], Bk["c"], D)
    Sh = structure(Hk["o"], Hk["h"], Hk["l"], Hk["c"], D)
    V = model.htf_view(Bk["s"], Hk["s"], Sh)
    ndays = int(mday.max()) + 1
    sq = np.array([d * 375 + 360 for d in range(ndays)])
    T = run_session(M, Bk, Sl, V, 1.0, 0.05, sq, 9 * 60 + 16, 14 * 60 + 45, np.ones(ndays, bool))
    W = run_as_written(Bk, Sl, V, 1.0, 0.05)
    return Bk, Sl, V, T, W


@pytest.mark.parametrize("seed,tf,htf,D", [(1, 5, 20, 2), (2, 3, 12, 2), (3, 1, 4, 2), (4, 5, 60, 3), (5, 15, 75, 2),
                                           (6, 1, 4, 2), (7, 15, 0, 2)])
def test_look_ahead_guard(seed, tf, htf, D):
    M, mday, ep = _synthetic(seed, 15)
    base = _run_all(M, mday, ep, tf, htf, D)
    Bk = base[0]
    n = len(M["mc"])
    seen = 0
    for cut in (n // 3 + 7, n // 2 + 3, 2 * n // 3 + 11):
        rng = np.random.default_rng(seed * 1000 + cut)
        N = {k: v.copy() for k, v in M.items()}
        for k in ("mo", "mh", "ml", "mc"):
            N[k][cut + 1:] = 20000 + rng.normal(0, 300, n - cut - 1)
        N["mh"] = np.maximum.reduce([N["mh"], N["mo"], N["mc"]])
        N["ml"] = np.minimum.reduce([N["ml"], N["mo"], N["mc"]])
        alt = _run_all(N, mday, ep, tf, htf, D)
        done = Bk["e"] <= cut                                   # chart bars closed by the cut
        for name in ("trend", "H", "Hid", "Hok", "L", "Lid", "Lok", "bos", "choch"):
            np.testing.assert_array_equal(getattr(base[1], name)[done], getattr(alt[1], name)[done])
            if name not in ("bos", "choch"):
                np.testing.assert_array_equal(getattr(base[2], name)[done], getattr(alt[2], name)[done])
        key = lambda t: (t["day"], t["k"], t["t"], t["side"], t["entry"], t["stop"], t["target"])  # noqa: E731
        a = [key(t) for t in base[3] if t["t"] <= cut]
        assert a == [key(t) for t in alt[3] if t["t"] <= cut]
        kw = lambda t: (t["k_in"], t["side"], t["entry"], t["stop"], t["target"])  # noqa: E731
        b = [kw(t) for t in base[4] if Bk["e"][t["k_in"]] <= cut]
        assert b == [kw(t) for t in alt[4] if Bk["e"][t["k_in"]] <= cut]
        seen += len(a) + len(b)
    if tf == 1:
        assert seen > 0                                         # the guard compared real setups


def test_swing_not_used_before_confirmation():
    # the same series, but bar 8's high is raised so that the high at 6 is NOT a swing: no trend at bar 8
    o, h, l, c = ohlc(H_, L_)
    S1 = structure(o, h, l, c, 2)
    h2 = h.copy()
    h2[8] = 12.5
    S2 = structure(o, h2, l, c, 2)
    np.testing.assert_array_equal(S1.trend[:8], S2.trend[:8])
    assert S1.trend[8] == 1 and S2.trend[8] == 0
