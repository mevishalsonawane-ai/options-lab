"""R98 tests: look-ahead guard on real lake days, exits and charges, expiry detection, census multiples.

    python -m pytest research/hunt/r98/test_r98.py -q
"""
from __future__ import annotations

import copy
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
import rows as W  # noqa: E402

HAVE_LAKE = all(R.path(u).exists() for u in R.UNDS)


def masked(day: R.Day, after: int, value=np.nan) -> R.Day:
    """A copy with every bar after row `after` replaced by `value` (blank or absurd future)."""
    d = copy.deepcopy(day)
    d.ix[after + 1:] = value
    for a in d.opts.values():
        a[after + 1:] = value
    if d.vix is not None:
        d.vix[after + 1:] = value
    return d


@pytest.mark.skipif(not HAVE_LAKE, reason="needs the built R98 day pickles")
@pytest.mark.parametrize("und", R.UNDS)
@pytest.mark.parametrize("value", [np.nan, 1e6])
def test_features_do_not_look_ahead(und, value):
    days = R.load(und)
    day = days[len(days) // 2]
    t = R.minute(13, 30)
    full = [r for r in W.day_rows(day, False) if r["t"] == t]
    # keep the entry bar (t + 1) so the rows still exist; everything from t + 2 on is blanked / absurd
    part = [r for r in W.day_rows(masked(day, t + 1, value), False) if r["t"] == t]
    assert full, "no rows at 13:30 on the test day"
    key = lambda r: (r["strike"], r["side"])
    a, b = {key(r): r for r in full}, {key(r): r for r in part}
    assert a.keys() == b.keys()
    for k in a:
        for f in (c for c in a[k] if c.startswith("f_")):
            x, y = a[k][f], b[k][f]
            assert (np.isnan(x) and np.isnan(y)) or x == pytest.approx(y), (k, f, x, y)


def test_exits_target_stop_and_hold():
    e = 2.0
    path = np.array([2.0, 1.5, 2.5, 4.1, 0.5, np.nan, 0.3])
    x = dict(zip(W.EXITS, W.exits_for("NIFTY", e, path)))
    assert x[(2.0, 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 4.0))      # 2x target fills at 4.0, not at the 4.1 close
    assert x[(5.0, 0.8)] == pytest.approx(L.net_pnl("NIFTY", e, 1.5))      # stop at 1.6: the 1.5 close exits
    assert x[(np.inf, 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 0.3))   # held to the last close
    assert x[(1.2, 0.8)] == pytest.approx(L.net_pnl("NIFTY", e, 1.5))      # the stop came first


def test_charges_and_worthless_expiry():
    lose_all = L.net_pnl("NIFTY", 3.0, 0.05)
    assert lose_all < -5000 and lose_all > -5100                           # premium + buy-side charges, no sell order
    flat = L.net_pnl("NIFTY", 3.0, 3.0)
    assert -200 < flat < -40                                               # round trip at the same price loses the costs
    assert L.net_pnl("SENSEX", 2.0, 20.0) > 40000


def test_expiry_detection_rules():
    idx = [date(2026, 1, d) for d in (5, 6, 7, 8, 9, 12, 13)]
    tv = pd.Series([1.0, 0.8, 0.05, 0.12, 1.2, 0.9, 0.04], index=idx)
    out = R.expiry_days(tv)
    # 7 Jan (0.05, next 0.12 > 2x) and 8 Jan (0.12, next 1.2) are adjacent candidates: the smaller one (7 Jan) stays;
    # 13 Jan is the last session with a tiny time value
    assert out == [date(2026, 1, 7), date(2026, 1, 13)]


def test_future_max_and_otm():
    x = np.full(R.N, np.nan)
    x[10], x[20], x[L.T_END + 3] = 2.0, 9.0, 50.0                         # 50 prints after 15:20: outside the window
    fm = L.future_max(x)
    assert fm[10] == 9.0 and fm[20] != fm[20] and np.isnan(fm[R.N - 1])
    assert L.otm(100.0, "C", 99.0) and not L.otm(100.0, "P", 99.0)


def test_profit_lock_ladder_fills_like_the_app():
    import lock as K
    e = 2.0
    be = K.be_level("NIFTY", e)
    assert be == pytest.approx(2.05)                     # Rs 2 + about Rs 0.04 of charges per unit, rounded up a tick
    nan = np.nan
    o = np.array([2.0, 2.1, 2.4, 2.3])
    h = np.array([2.1, 2.7, 2.45, 2.3])                  # peak 2.7 = +35%: the first rung (lock breakeven) from minute 2
    l = np.array([1.98, 2.5, 2.0, 2.2])
    c = np.array([2.05, 2.6, 2.1, 2.25])
    x = dict(zip(K.VARIANTS, K.simulate("NIFTY", e, o, h, l, c)))
    assert x[("add30", 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, be))      # stopped at breakeven in minute 2
    assert x[("none", 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 2.25))     # no lock: out at the last close
    # a gap below the lock fills at the open; the -50% stop alone is not touched
    o2, h2, l2, c2 = np.array([2.0, 2.0, 1.2]), np.array([2.1, 2.9, 1.3]), np.array([1.9, 2.0, 1.1]), np.array([2.0, 2.8, 1.2])
    y = dict(zip(K.VARIANTS, K.simulate("NIFTY", e, o2, h2, l2, c2)))
    assert y[("add30", 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 1.2))     # peak 2.9 = +45%: lock 2.05, gapped to 1.2
    assert y[("none", 0.5)] == pytest.approx(L.net_pnl("NIFTY", e, 1.2))      # no lock: the 1.2 close at the end
    # +60% locks +30%; compounding x1.69 locks x1.3
    lv = K.lock_levels("add30", e, be, np.array([2.0, 2.6, 3.2, 3.9]))
    assert list(lv[1:]) == pytest.approx([2.05, 2.6, 3.2])
    lc = K.lock_levels("cmp30", e, be, np.array([2.0, 2.6, 3.38, 4.4]))
    assert list(lc[1:]) == pytest.approx([2.05, 2.6, 3.38])


def test_hero2_exits_on_a_synthetic_path():
    import hero2 as H
    e = 2.0
    n = 40
    o = np.full(n, 2.0); h = np.full(n, 2.1); l = np.full(n, 1.9); c = np.full(n, 2.0)
    # the option runs to 12 (6x) by minute 10, then falls back to 3 by the end
    run = np.linspace(2.0, 12.0, 11)
    c[:11], h[:11], o[:11], l[:11] = run, run + 0.2, run - 0.1, run - 0.2
    c[11:], h[11:], o[11:], l[11:] = np.linspace(11.5, 3.0, n - 11), np.linspace(11.6, 3.1, n - 11), \
        np.linspace(11.6, 3.1, n - 11), np.linspace(11.4, 2.9, n - 11)
    ix = np.concatenate([np.linspace(100, 110, 11), np.linspace(109.5, 101, n - 11)])
    x = dict(zip(H.COLS, H.simulate("NIFTY", e, o, h, l, c, ix, 1.0, 4.0)))
    assert x["E1_half2x_s0"] == pytest.approx(H.net_legs("NIFTY", e, [(0.5, 4.0), (0.5, 3.0)]))
    # give-back 50% armed at 5x: the peak before the break is 12.2 -> stop 2 + 0.5 x 10.2 = 7.1; the breaking minute
    # opens below it on this falling path (a gap), so the fill is that open
    jb = 11 + int(np.argmax(l[11:] <= 7.1))                        # after the 12.2 peak (minute 10) sets the level
    assert o[jb] <= 7.1
    assert x["E2_arm5x_g0.5_s0"] == pytest.approx(H.net_legs("NIFTY", e, [(1.0, min(o[jb], 7.1))]))
    # index trail 1 straddle (4 points) from the best close 110: the first close <= 106 sells at that minute's option close
    j = 11 + int(np.argmax(ix[11:] <= 106.0))                      # after the 110 high of minute 10
    assert x["E3_idx1em_s0"] == pytest.approx(H.net_legs("NIFTY", e, [(1.0, c[j])]))
    assert x["B_hold"] == pytest.approx(H.net_legs("NIFTY", e, [(1.0, 3.0)]))
    assert H.net_legs("NIFTY", e, [(1.0, 2.0)]) == pytest.approx(L.net_pnl("NIFTY", e, 2.0))


def test_hero2_time_stop_and_initial_stop():
    import hero2 as H
    e, n = 4.0, 70
    o = np.full(n, 3.0); h = np.full(n, 3.2); l = np.full(n, 2.9); c = np.full(n, 3.0)
    c[-1] = 12.0                                                       # a late jump the time stop never sees
    x = dict(zip(H.COLS, H.simulate("NIFTY", e, o, h, l, c, np.full(n, 100.0), 1.0, 5.0)))
    assert x["E4_time30"] == pytest.approx(H.net_legs("NIFTY", e, [(1.0, 3.0)]))       # below entry at 30 min: out
    assert x["B_hold"] == pytest.approx(H.net_legs("NIFTY", e, [(1.0, 12.0)]))
    l2 = l.copy(); l2[5] = 1.5; o2 = o.copy()
    y = dict(zip(H.COLS, H.simulate("NIFTY", e, o2, h, l2, c, np.full(n, 100.0), 1.0, 5.0)))
    assert y["E1_half2x_s0.5"] == pytest.approx(H.net_legs("NIFTY", e, [(1.0, 2.0)]))    # -50% stop at 2.0 in minute 5


def _synthetic_trend_day():
    """NIFTY flat at 10,000 to 13:00, then +1 point a minute; every option = intrinsic + Rs 20, so EM13 = 40."""
    n = R.N
    t13 = R.minute(13, 0)
    s = np.full(n, 10000.0)
    s[t13:] = 10000.0 + np.arange(n - t13)
    ix = np.stack([s, s + 0.5, s - 0.5, s], axis=1)
    opts = {}
    for k in np.arange(9800.0, 10300.0, 50.0):
        for side in "CP":
            v = (np.maximum(s - k, 0) if side == "C" else np.maximum(k - s, 0)) + 20.0
            opts[(float(k), side)] = np.stack([v, v + 0.1, v - 0.1, v, np.full(n, 100.0), np.full(n, 1000.0), np.full(n, 15.0)], axis=1)
    return R.Day(date(2026, 1, 6), "NIFTY", ix, opts, np.full(n, 14.0), 10000.0, 14.0, 100.0)


def test_conf_trigger_and_index_exits():
    import conf as F
    day = _synthetic_trend_day()
    opt, idx = F.day_trades(day)
    a = {(r["k"], r["inst"]): r for r in opt}
    r = a[(1.5, "ATM")]
    assert r["t"] == R.minute(14, 0) and r["strike"] == 10050.0 and r["side"] == "C"     # 10,060 = 1.5 x 40 at 14:00
    e = 10061.0 - 10050.0 + 20.0 + L.TICK                                                  # the 14:01 open + 1 tick
    assert r["entry"] == pytest.approx(e)
    # X4: target 1 straddle beyond the trigger level (10,100), reached at 14:40 -> the call's close then is 50 + 20
    assert r["X4_stop0.5_tgt1"] == pytest.approx(F.net_legs("NIFTY", e, [(1.0, 70.0)]))
    # X1 (and every stop, as the index never retraces): out at the 15:20 close, index 10,140 -> 90 + 20
    for x in ("X1_hold", "X2_stop0.5", "X3_stop1", "X6_trail0.5"):
        assert r[x] == pytest.approx(F.net_legs("NIFTY", e, [(1.0, 110.0)])), x
    assert a[(2.0, "ATM")]["t"] == R.minute(14, 20)                                        # 10,080 = 2 x 40
    assert a[(1.5, "ITM1")]["strike"] == 10000.0 and a[(1.5, "OTM1")]["strike"] == 10100.0
    xi = {(r["k"], r["exit"]): r for r in idx}
    assert xi[(1.5, "X1_hold")]["pnl_em"] == pytest.approx((10140 - 10061) / 40.0)


def test_conf_stop_on_retrace():
    import conf as F
    ix = np.array([106.0, 107.0, 105.0, 104.0, 103.0])
    # trigger level 106 (up), EM 4: the 0.5-straddle stop is at 104, first reached in minute 3
    assert F.exit_minute("X2_stop0.5", ix, 1.0, 106.0, 4.0) == 3
    assert F.exit_minute("X6_trail0.5", ix, 1.0, 106.0, 4.0) == 2                          # 2 below the 107 best
    assert F.exit_minute("X3_stop1", ix, 1.0, 106.0, 4.0) == -1
    assert F.option_exit_price(np.array([1.0, np.nan, np.nan, 2.0]), 1) == 2.0


@pytest.mark.skipif(not HAVE_LAKE, reason="needs the built R98 day pickles")
@pytest.mark.parametrize("und", ["NIFTY", "BANKNIFTY"])
def test_new_features_do_not_look_ahead(und):
    import features_e as F
    days = R.load(und)
    day = days[len(days) // 3]
    t = R.minute(13, 30)
    rs_ = [(t, k, s) for (k, s), a in day.opts.items() if not np.isnan(a[t, R.C]) and 1 <= a[t, R.C] <= 10][:6]
    assert rs_
    bu, bv = np.linspace(0.2, 0.8, R.N), np.linspace(0.3, 0.6, R.N)
    oth = {"X": np.linspace(100, 101, R.N)}
    full = F.day_features(day, rs_, bu, bv, oth, False, True)
    m = masked(day, t, 1e6)
    bu2, bv2 = bu.copy(), bv.copy(); bu2[t + 1:] = 99; bv2[t + 1:] = 99
    oth2 = {"X": oth["X"].copy()}; oth2["X"][t + 1:] = 1e6
    part = F.day_features(m, rs_, bu2, bv2, oth2, False, True)
    for a, b in zip(full, part):
        for f in F.NEW:
            x, y = a[f], b[f]
            assert (x != x and y != y) or x == pytest.approx(y), (f, x, y)


def test_ignition_trigger_on_the_synthetic_day():
    import ignition as G
    day = _synthetic_trend_day()
    # a cheap 10100 CE: Rs 2 until the index passes 10,030 (13:30), then +0.1 a point; volume 10 a minute, with a burst
    # of 200 a minute from 14:00
    s = day.ix[:, 3]
    c = 2.0 + 0.1 * np.maximum(s - 10030.0, 0)
    a = day.opts[(10100.0, "C")]
    a[:, R.O], a[:, R.H], a[:, R.L], a[:, R.C] = c, c + 0.05, c - 0.05, c
    a[:, R.V] = 10.0
    a[R.minute(14, 0):R.minute(14, 5), R.V] = 200.0
    trg = G.triggers(day)
    # 13:31-13:59 the price rises but the volume is flat (no burst); at 14:00 the 5-minute volume is 240 against a
    # mean of about 55: burst 4.3 >= 3; at 14:01 it is 430 / 61 = 7.1 >= 5. Price 5 at 14:00 = 2.5x the low of 2.
    assert trg[(3.0, 1.3)][:3] == (R.minute(14, 0), 10100.0, "C")
    assert trg[(3.0, 1.5)][:3] == (R.minute(14, 0), 10100.0, "C")
    assert trg[(5.0, 1.3)][0] == R.minute(14, 1)


# ---- FT3 as the app runs it (ft3_app.py): Black-76 IV, the IV clock, the frozen cut-offs, the LIMIT, no look-ahead ---------

def test_ft3_app_black76_iv_inverts_and_refuses_no_time_value():
    import ft3_app as F
    t = F.years_to_1530(R.minute(11, 0))
    assert t == pytest.approx(270 / (365 * 24 * 60))
    assert F.years_to_1530(R.minute(15, 30)) == pytest.approx(1 / (365 * 24 * 60))     # at least a minute
    for call, k in ((True, 22600.0), (False, 22400.0), (True, 22500.0)):
        p = F.b76(22510.0, k, t, 0.2, call)
        assert F.iv(p, 22510.0, k, t, call) == pytest.approx(0.2, abs=1e-6)
    assert np.isnan(F.iv(5.0, 22600.0, 22500.0, t, True))          # an ITM call under intrinsic
    assert np.isnan(F.iv(0.0, 22510.0, 22600.0, t, True))
    assert np.isnan(F.iv(np.nan, 22510.0, 22600.0, t, True))
    # The frozen cut-offs are the design 10th percentiles to 4 decimals (FORWARD_TEST.md, 21 Oct amendment).
    assert (F.IV_REL_MAX, F.DIST_EM_MAX, F.PX_LO, F.PX_HI) == (1.0333, 1.5466, 6.0, 10.0)
    assert F.MARKS[0] == R.minute(11, 0) and F.MARKS[-1] == R.minute(12, 55) and len(F.MARKS) == 24
    assert F.T_EXIT == R.minute(15, 4)


def ft3_day(seed: int = 3) -> R.Day:
    """A NIFTY expiry day whose index walks; every option Black-76 priced at 20% vol (the 22,600 call at 22% from 11:20,
    so its IV ratio is 1.10 then) and traded every minute."""
    import ft3_app as F
    rng = np.random.default_rng(seed)
    s = 22510.0 + np.cumsum(rng.normal(0, 3, R.N))
    ix = np.full((R.N, 4), np.nan)
    ix[:, 0] = ix[:, 1] = ix[:, 2] = ix[:, 3] = s
    opts = {}
    for k in range(22300, 22751, 50):
        for side in "CP":
            a = np.full((R.N, 7), np.nan)
            for t in range(R.N):
                sig = 0.22 if (k == 22600 and side == "C" and t >= R.minute(11, 20)) else 0.20
                a[t, R.C] = F.b76(s[t], float(k), F.years_to_1530(t), sig, side == "C")
            a[:, R.O] = a[:, R.C]
            a[:, R.V] = 100
            opts[(float(k), side)] = a
    return R.Day(date(2026, 10, 27), "NIFTY", ix, opts)


def test_ft3_app_pick_reads_only_its_minute():
    """The pick at every mark is the same when every later bar is replaced by an absurd value (1e6), on a moving day."""
    import ft3_app as F
    day = ft3_day()
    seen = 0
    for t in F.MARKS:
        a = F.pick_at(day, t)
        b = F.pick_at(masked(day, t, 1e6), t)
        assert a == b, t
        seen += a is not None
    assert seen > 0, "the synthetic day must give picks for the guard to mean something"


def test_ft3_app_pick_filters_and_the_limit_cap():
    import ft3_app as F
    day = ft3_day()
    for t in F.MARKS:
        p = F.pick_at(day, t)
        if p is None:
            continue
        k, side, px, dist, r = p
        assert 6.0 <= px <= 10.0 and dist <= 1.5466 + 1e-9 and r <= 1.0333 + 1e-9
        assert (k > day.ix[t, 3]) if side == "C" else (k < day.ix[t, 3])
        if t >= R.minute(11, 20):
            assert not (k == 22600.0 and side == "C"), "IV 1.10x the ATM's is out"
    # The LIMIT: the first of the next 3 opens + 1 tick within price x 1.2 + 0.10.
    a = np.full((R.N, 7), np.nan)
    t = R.minute(11, 0)
    a[t + 1, R.O], a[t + 2, R.O], a[t + 3, R.O] = 12.0, 9.60, 8.0
    assert F.fill(a, t, 8.0) == (pytest.approx(9.65), t + 2)        # cap 9.70: 12.05 no, 9.65 yes
    assert F.fill(a, t, 8.0, start=3) == (pytest.approx(8.05), t + 3)
    a[t + 2, R.O] = 9.70
    assert F.fill(a, t, 6.0) is None                                # cap 7.30: none of 12.05 / 9.75 / 8.05
