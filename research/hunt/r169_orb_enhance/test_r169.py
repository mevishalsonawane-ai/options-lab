"""R169 tests (PREREG section 5): every pure function with edge cases, R3 parity on synthetic days, skip-only and
tighten-only properties, and the look-ahead guard. 13 Nov 2026."""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r169cfg as K  # noqa: E402
import r169lib as X  # noqa: E402
import r3engine as E  # noqa: E402
from r3data import Sess  # noqa: E402

N = 375


def make_sess(seed=0, drift=0.0, day=date(2025, 6, 10), expiry=False, nan_rows=(), step=100.0):
    """A synthetic BANKNIFTY day: index random walk (+ drift after 10:00), ATM +-4 calls / puts priced from it."""
    rng = np.random.default_rng(seed)
    base = 50_000.0
    ret = rng.normal(0, 8, N)
    ret[50:] += drift
    close = base + np.cumsum(ret)
    op = np.r_[base, close[:-1]]
    hi = np.maximum(op, close) + rng.uniform(0, 6, N)
    lo = np.minimum(op, close) - rng.uniform(0, 6, N)
    ix = np.c_[op, hi, lo, close]
    for r in nan_rows:
        ix[r] = np.nan
    atm = math.floor(close[9] / step + 0.5) * step
    opts = {}
    for j in range(-4, 5):
        k = atm + j * step
        for side, sg in (("C", 1), ("P", -1)):
            intr = np.maximum(sg * (close - k), 0)
            prem = intr + 300 + 0.3 * sg * (close - k).clip(-500, 500) * 0 + 200 * np.exp(-abs(close - k) / 400)
            o_ = np.r_[prem[0], prem[:-1]]
            a = np.c_[o_, np.maximum(o_, prem) + 1, np.minimum(o_, prem) - 1, prem, np.full(N, 500.0), np.full(N, 1e5)]
            opts[(float(k), side)] = a.astype(np.float32)
    return Sess(day, "BANKNIFTY", ix, np.full(N, 14.0), opts, None, expiry, 35,
                (base - 50, base + 150, base - 200, base - 20), 400.0, 13.5, atm)


# ------------------------------------------------------------------------------------------------ parity / properties
@pytest.mark.parametrize("arm", K.ARMS)
@pytest.mark.parametrize("seed", range(6))
def test_b0_equals_r3(arm, seed):
    s = make_sess(seed, drift=(seed % 3 - 1) * 3.0)
    mine = X.taken(X.run_session(s, X.Cfg(arm, costs="app")))
    r3 = E.run_session(s, E.baseline(arm))
    assert [(t["entry_row"], t["exit_row"], round(t["net"], 6)) for t in mine] == \
           [(t["entry_row"], t["exit_row"], round(t["net"], 6)) for t in r3]


@pytest.mark.parametrize("fid", ["F3", "F4", "F5", "F6", "F7", "F8", "F9"])
@pytest.mark.parametrize("arm", K.ARMS)
def test_filters_only_remove_trades(fid, arm):
    for seed in range(8):
        s = make_sess(seed, drift=(seed % 3 - 1) * 3.0, expiry=seed == 3)
        ctx = dict(atr5=X.atr5(None, X.bars5(s.ix)), busy_med=np.full(75, 20.0))
        b0 = X.run_session(s, X.Cfg(arm), ctx)
        v = X.run_session(s, X.Cfg(arm, 1, (fid,)), ctx)
        key = lambda t: (t["entry_row"], t["exit_row"], t["side"])  # noqa: E731
        assert [key(t) for t in v] == [key(t) for t in b0]          # skipped breaks hold their side
        assert {key(t) for t in X.taken(v)} <= {key(t) for t in b0}


@pytest.mark.parametrize("cid", ["X1", "X2", "X3", "X4", "X5", "X6"])
@pytest.mark.parametrize("arm", K.ARMS)
def test_exits_tighten_only(cid, arm):
    for seed in range(8):
        s = make_sess(seed, drift=(seed % 3 - 1) * 3.0)
        b5 = X.bars5(s.ix)
        hi, lo = E.opening_range(b5)
        for t in X.taken(X.run_session(s, X.Cfg(arm))):
            v = X.simulate(s, X.Cfg(arm, 1, (cid,)), t["side"], t["sig_row"], t["k"], 40.0, hi, lo)
            assert v["exit_row"] <= t["exit_row"]


def test_lots_scale_quantity_and_charges():
    s = make_sess(1, drift=3.0)
    t1 = X.taken(X.run_session(s, X.Cfg("orb", 1)))
    t3 = X.taken(X.run_session(s, X.Cfg("orb", 3)))
    assert t1 and [t["qty"] for t in t3] == [3 * t["qty"] for t in t1]
    assert all(c3["charges"] < 3 * c1["charges"] for c1, c3 in zip(t1, t3))     # brokerage is per order


def test_dated_costs_differ_from_app_before_april_2026():
    s = make_sess(2, drift=3.0, day=date(2025, 6, 10))
    a = X.taken(X.run_session(s, X.Cfg("orb", costs="app")))
    d = X.taken(X.run_session(s, X.Cfg("orb")))
    assert a and all(x["charges"] < y["charges"] for x, y in zip(d, a))           # STT 0.1% before 1 Apr 2026


# ------------------------------------------------------------------------------------------------ the walk
def _walk(o, h, l, c, **kw):
    n = len(c)
    ix = np.full(n, np.nan)
    args = dict(c0=0, e=100.0, trig=60.0, tgt=40.0, lad_t=40.0, be=101.0, be10=0.0, ts_row=-1, ts_need=0.0, g3_need=0.0,
                side=1, srt_lvl=0.0, fail_mode=0, rhi=0.0, rlo=0.0, sq=n)
    args.update(kw)
    ixh, ixl, ixc = kw.pop("ixh", ix), kw.pop("ixl", ix), kw.pop("ixc", ix)
    for k in ("ixh", "ixl", "ixc"):
        args.pop(k, None)
    return X.walk(np.asarray(o, float), np.asarray(h, float), np.asarray(l, float), np.asarray(c, float), ixh, ixl, ixc,
                  args["c0"], args["e"], args["trig"], args["tgt"], args["lad_t"], args["be"], args["be10"], args["ts_row"],
                  args["ts_need"], args["g3_need"], args["side"], args["srt_lvl"], args["fail_mode"], args["rhi"],
                  args["rlo"], args["sq"])


def test_walk_gap_through_stop_fills_at_open():
    r, p, why = _walk([100, 50], [101, 51], [99, 45], [100, 48])
    assert (r, p, why) == (1, 50.0, 0)


def test_walk_target_and_square_off_and_nan():
    r, p, why = _walk([100, np.nan, 120, 140, 140], [101, np.nan, 141, 141, 141], [99, np.nan, 119, 139, 139], [100, np.nan, 140, 140, 140])
    assert (r, why) == (3, 2)                            # NaN minute skipped; sold at the next open
    r, p, why = _walk([100] * 5, [101] * 5, [99] * 5, [100] * 5)
    assert (r, why) == (5, 4)


def test_walk_be10_lock():
    o = [100, 112, 111, 100]
    h = [101, 112, 111, 101]
    l = [99, 111, 110, 99]
    r, p, why = _walk(o, h, l, o, tgt=0.0, lad_t=0.0, be10=0.10)
    assert why == 1 and r == 3 and p == 100.0           # min(level 101, open 100)


def test_walk_time_stop():
    o = [100] * 50
    r, p, why = _walk(o, [101] * 50, [99] * 50, o, ts_row=39, ts_need=5.0)
    assert (r, why) == (40, 3)
    r, p, why = _walk(o, [106] + [101] * 49, [99] * 50, o, ts_row=39, ts_need=5.0)
    assert why == 4


def test_walk_index_exits():
    n = 20
    o = [100.0] * n
    ixc = np.full(n, 50_000.0)
    ixh, ixl = ixc + 5, ixc - 5
    ixc2 = ixc.copy()
    ixc2[9] = 49_990.0                                  # a 5-minute bar (rows 5-9) closes back inside the range
    r, p, why = _walk(o, [101] * n, [99] * n, o, fail_mode=1, rhi=49_995.0, rlo=49_000.0, ixc=ixc2, ixh=ixh, ixl=ixl)
    assert (r, why) == (10, 7)
    ixh2 = ixh.copy()
    ixh2[4] = 50_200.0
    r, p, why = _walk(o, [101] * n, [99] * n, o, srt_lvl=50_100.0, ixc=ixc, ixh=ixh2, ixl=ixl)
    assert (r, why) == (5, 6)
    ixc3 = ixc.copy()
    ixc3[6] = 49_980.0                                  # below the lows of rows 3-5
    h = [121] * n                                       # +50% of target reached
    r, p, why = _walk(o, h, [99] * n, o, tgt=0.0, lad_t=0.0, g3_need=20.0, ixc=ixc3, ixh=ixh, ixl=ixl)
    assert (r, why) == (7, 5)


def test_walk_lookahead_guard():
    rng = np.random.default_rng(5)
    for _ in range(50):
        c = 100 + np.cumsum(rng.normal(0, 3, 120))
        o = np.r_[100, c[:-1]]
        h = np.maximum(o, c) + 1
        l = np.minimum(o, c) - 1
        ix = 50_000 + np.cumsum(rng.normal(0, 5, 120))
        kw = dict(tgt=40.0, lad_t=40.0, be10=0.1, ts_row=30, ts_need=5.0, g3_need=20.0, srt_lvl=50_030.0, fail_mode=1,
                  rhi=49_990.0, rlo=49_000.0, ixc=ix, ixh=ix + 3, ixl=ix - 3, trig=E.floor_tick(100 - 40))
        r, p, why = _walk(o, h, l, c, **dict(kw))
        cut = min(r + 1, 119)
        junk = lambda a: np.r_[a[:cut], np.full(len(a) - cut, 1e6)]  # noqa: E731
        kw2 = dict(kw, ixc=junk(ix), ixh=junk(ix + 3), ixl=junk(ix - 3))
        r2, p2, why2 = _walk(junk(o), junk(h), junk(l), junk(c), **kw2)
        assert (r, why) == (r2, why2)


# ------------------------------------------------------------------------------------------------ features / filters
def test_features_lookahead_guard():
    s = make_sess(4, drift=3.0)
    b5 = X.bars5(s.ix)
    hi, lo = E.opening_range(b5)
    for row in (54, 79, 124, 199):
        f = X.features(s, 1, row, hi, lo, b5, s.atm)
        s2 = make_sess(4, drift=3.0)
        s2.ix[row + 1:] = 9e9
        for a in s2.opts.values():
            a[row + 1:] = 9e5
        b52 = X.bars5(s2.ix)
        f2 = X.features(s2, 1, row, hi, lo, b52, s2.atm)
        assert all((np.isnan(f[k]) and np.isnan(f2[k])) or f[k] == pytest.approx(f2[k]) for k in f)
        ctx = dict(atr5=X.atr5(None, b5), busy_med=np.full(75, 20.0))
        ctx2 = dict(atr5=X.atr5(None, b52), busy_med=np.full(75, 20.0))
        for fid in ("F3", "F4", "F5", "F6", "F7", "F8", "F9"):
            for arm in K.ARMS:
                assert X.skip_reason(fid, s, arm, 1, row, hi, lo, b5, ctx) == X.skip_reason(fid, s2, arm, 1, row, hi, lo, b52, ctx2)


def test_atr5_warmup_and_busy_median():
    b5 = X.bars5(make_sess(1).ix)
    a = X.atr5(None, b5)
    assert np.isnan(a[:13]).all() and np.isfinite(a[13:]).all()
    a2 = X.atr5(b5, b5)
    assert np.isfinite(a2).all()
    assert np.isnan(X.busy_median([a2] * 9, 20))
    assert X.busy_median([a2] * 10, 20) == pytest.approx(a2[20])
    assert np.isnan(X.atr5(None, np.full((75, 4), np.nan))).all()


def test_gap_and_room_and_expiry_filters():
    s = make_sess(0)
    s.prev = (0, 50_010.0, 49_900.0, 50_000.0)
    s.ix[0, 0] = 50_100.0                                       # +0.2% gap: small
    assert X.gap_pct(s) == pytest.approx(0.2)
    b5 = X.bars5(s.ix)
    hi, lo = E.opening_range(b5)
    assert X.skip_reason("F5", s, "orb", 1, 54, hi, lo, b5, {}) is True      # with a small gap: fills
    assert X.skip_reason("F5", s, "orb", -1, 54, hi, lo, b5, {}) is False
    s.ix[0, 0] = 50_400.0                                       # +0.8%: big
    assert X.skip_reason("F5", s, "orb", -1, 54, hi, lo, b5, {}) is True     # against a big gap
    assert X.skip_reason("F5", s, "orb", 1, 54, hi, lo, b5, {}) is False
    s.prev = (0, np.nan, np.nan, np.nan)
    assert X.skip_reason("F5", s, "orb", 1, 54, hi, lo, b5, {}) is False     # unknown never skips
    assert X.skip_reason("F8", s, "orb", 1, 54, hi, lo, b5, {}) is False
    c = b5[54 // 5, 3]
    s.prev = (0, c + 20, c - 500, c)
    assert X.skip_reason("F8", s, "orb", 1, 54, hi, lo, b5, {}) is True
    s.prev = (0, c + 31, c - 500, c)
    assert X.skip_reason("F8", s, "orb", 1, 54, hi, lo, b5, {}) is False
    s.expiry = True
    assert X.skip_reason("F9", s, "orb", 1, 54, hi, lo, b5, {}) is True


def test_first15_edge_and_fvg():
    s = make_sess(0)
    s.ix[:50, 1] = 50_000.0
    s.ix[3, 1] = 50_100.0
    s.ix[:50, 2] = 49_900.0
    s.ix[30, 2] = 49_800.0
    assert X.edge_in_first15(s.ix, True) is True and X.edge_in_first15(s.ix, False) is False
    b5 = np.full((75, 4), 100.0)
    b5[10] = (110, 120, 108, 118)
    b5[8] = (100, 105, 95, 102)
    b5[12] = (118, 130, 115, 128)
    assert X.skip_reason("F7", s, "orb", 1, 64, 0, 0, b5, {}) is True      # high of bar 10 (120) is not below bar 12's low (115)
    b5[12] = (122, 130, 121, 128)
    assert X.skip_reason("F7", s, "orb", 1, 64, 0, 0, b5, {}) is False     # a gap: 120 < 121
    assert X.skip_reason("F7", s, "orb", -1, 64, 0, 0, b5, {}) is True
    b5[12] = (110, 115, 106, 112)
    assert X.skip_reason("F7", s, "orb", 1, 64, 0, 0, b5, {}) is True
    assert X.edge_in_first15(np.full((375, 4), np.nan), True) is None


def test_f1_f2_unknown_never_skips():
    s = make_sess(0)
    b5 = X.bars5(s.ix)
    for fid in ("F1", "F2"):
        key = "pick" if fid == "F1" else "net"
        assert X.skip_reason(fid, s, "orb", 1, 54, 0, 0, b5, {key: None}) is False
        assert X.skip_reason(fid, s, "orb", 1, 54, 0, 0, b5, {key: True}) is False
        assert X.skip_reason(fid, s, "orb", 1, 54, 0, 0, b5, {key: False}) is True


# ------------------------------------------------------------------------------------------------ strike by delta
def test_black_scholes_round_trip_and_delta():
    S, k, T = 50_000.0, 50_000.0, 10 / 365
    for sig in (0.1, 0.2, 0.4):
        for call in (True, False):
            p = X.bs_price(S, k, T, 0.065, sig, call)
            assert X.implied_vol(p, S, k, T, 0.065, call) == pytest.approx(sig, abs=1e-6)
    d = X.bs_delta(S, k, T, 0.065, 0.15, True)
    assert 0.5 < d < 0.56
    assert X.bs_delta(S, k, T, 0.065, 0.15, False) == pytest.approx(d - 1)
    assert np.isnan(X.implied_vol(1.0, S, 45_000.0, T, 0.065, True))       # below intrinsic
    assert np.isnan(X.implied_vol(np.nan, S, k, T, 0.065, True))


def test_strike_by_delta_choice_and_fallback():
    s = make_sess(0)
    exp = date(2025, 6, 26)
    k60 = X.strike_by_delta(s, 1, 54, 0.60, exp)
    k50 = X.strike_by_delta(s, 1, 54, 0.50, exp)
    assert k60 <= k50                                    # a deeper call has the higher delta
    assert X.strike_by_delta(s, 1, 54, 0.60, None) == s.atm
    s.opts = {}
    assert X.strike_by_delta(s, 1, 54, 0.60, exp) == s.atm
    assert X.years_to_expiry(date(2025, 6, 26), 374, date(2025, 6, 26)) > 0


# ------------------------------------------------------------------------------------------------ scoring and gates
def test_score_edges():
    z = X.score([], [date(2025, 1, 1)])
    assert z["trades"] == 0 and z["net"] == 0 and z["max_dd"] == 0
    tr = [dict(day=date(2025, 1, 1), net=100.0), dict(day=date(2025, 1, 2), net=-300.0), dict(day=date(2025, 2, 3), net=50.0)]
    s = X.score(tr, [date(2025, 1, 1), date(2025, 1, 2), date(2025, 2, 3), date(2025, 2, 4)])
    assert s["max_dd"] == -300.0 and s["worst_month"] == -200.0 and s["worst_day"] == -300.0
    assert s["win"] == pytest.approx(2 / 3) and s["be_win"] == pytest.approx(300 / 375)
    assert s["per_session"] == pytest.approx(-150 / 4)


def test_gate1_branches_and_limits():
    b = dict(net=100.0, max_dd=-50.0, win=0.40)
    assert X.gate1(dict(net=150.0, max_dd=-40.0, win=0.40), b) == (True, "net")
    assert X.gate1(dict(net=150.0, max_dd=-60.0, win=0.40), b) == (False, None)
    assert X.gate1(dict(net=100.0, max_dd=-60.0, win=0.45), b) == (True, "win")
    assert X.gate1(dict(net=99.0, max_dd=-10.0, win=0.50), b) == (False, None)
    assert X.within_limits(dict(max_dd=-40_000.0, worst_month=-13_000.0))
    assert not X.within_limits(dict(max_dd=-40_001.0, worst_month=0.0))
    assert X.twin_p(5.0, np.array([1, 2, 3, 6])) == pytest.approx(2 / 5)
    assert X.bh([0.01, 0.04, 0.03]) == pytest.approx([0.03, 0.04, 0.04])


def test_view_row_and_network_verdict():
    import r169study as S
    assert S.view_row(4) is None and S.view_row(5) == 4 and S.view_row(60) == 59
    assert S.view_row(400) is None                      # older than 5 minutes after the last view (14:54)
    net = {(date(2025, 1, 1), 59): (10.0, -5.0)}
    assert S.net_verdict(net, date(2025, 1, 1), 1, 59) is True
    assert S.net_verdict(net, date(2025, 1, 1), -1, 59) is False
    assert S.net_verdict(net, date(2025, 1, 2), 1, 59) is None


def test_choose_combos_conflicts():
    import r169study as S
    g = {"S1": (500, True), "S2": (400, True), "F3": (300, True), "X6": (200, True), "X4": (900, False)}
    c = S.choose_combos(g)
    assert c["C1"] == ("S1", "F3") and c["C2"] == ("S1", "F3", "X6") and c["C3"] == ("F3", "X6")
    assert S.choose_combos({"F3": (1, True)})["C1"] == ()


def test_picker_needs_enough_trades():
    import r169study as S
    few = [dict(feats={f: 1.0 for f in X.PICK_FEATS}, net=1.0 if i % 2 else -1.0) for i in range(20)]
    p = S.Picker(few)
    assert not p.ok and p.keep({}) is None
    rng = np.random.default_rng(0)
    many = [dict(feats={f: float(rng.normal()) for f in X.PICK_FEATS}, net=float(rng.normal())) for _ in range(300)]
    many[0]["feats"]["vix_prev"] = np.nan
    p = S.Picker(many)
    assert p.ok and p.keep({f: 0.0 for f in X.PICK_FEATS}) in (True, False)


def test_wait_one_bar_moves_entry():
    for seed in range(6):
        s = make_sess(seed, drift=4.0)
        b0 = X.taken(X.run_session(s, X.Cfg("orb")))
        w1 = X.taken(X.run_session(s, X.Cfg("orb", 1, ("W1",))))
        if b0 and w1 and b0[0]["side"] == w1[0]["side"]:
            assert w1[0]["sig_row"] >= b0[0]["sig_row"] + 5


def test_empty_and_missing_range_days():
    s = make_sess(0, nan_rows=range(45, 50))           # no 10:00 bar: no range, no trade
    assert X.run_session(s, X.Cfg("orb")) == []
    s = make_sess(0)
    s.atm = np.nan
    assert X.run_session(s, X.Cfg("orb_sweep")) == []


def test_expiry_day_squares_off_earlier():
    s = make_sess(1, drift=0.0, expiry=True)
    for t in X.taken(X.run_session(s, X.Cfg("orb"))):
        assert t["exit_row"] <= E.SQ_EXPIRY


def test_bh_total_counts_earlier_tests():
    q = X.bh_total([0.0001, 0.5], 1000)
    assert q[0] == pytest.approx(0.1) and q[1] == 1.0
    assert X.bh_total([], 10).size == 0
    assert X.bh_total([0.01, 0.02], 2) == pytest.approx([0.02, 0.02])
