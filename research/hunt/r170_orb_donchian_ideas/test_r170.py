"""R170 tests (PREREG section 6): new helpers with edge cases, B0 = R169's B0, skip-only filters, tighten-only exits,
the 20-point exits, rupee levels from the real fill, and the look-ahead guard. 13 Nov 2026."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r169_orb_enhance"))
import r170lib as X  # noqa: E402
import r169lib as X9  # noqa: E402
import r3engine as E  # noqa: E402
from test_r169 import make_sess  # noqa: E402

ARMS = ("orb", "orb_fresh", "orb_sweep")


def ctx_for(s, prev=None):
    b5 = X9.bars5(s.ix)
    days = [X9.bars5(p.ix) for p in (prev or [])] + [b5]
    allb, off = X.cont_series(days)
    return dict(atr5=X9.atr5(None, b5), ema20=X.ema(allb[:, 3], 20)[off:], st5=X.supertrend_dir(allb)[off:],
                st1=X.supertrend_dir(s.ix), htf15=X.htf_side(days, 3), htf60=X.htf_side(days, 12), width_med=300.0,
                levels=[50_100.0, 49_900.0], bandvol_med=np.full(375, 1.0), breadth={}, expiry_date=date(2025, 6, 26))


# ------------------------------------------------------------------------------------------------ properties
@pytest.mark.parametrize("arm", ARMS)
def test_b0_equals_r169(arm):
    for seed in range(6):
        s = make_sess(seed, drift=(seed % 3 - 1) * 3.0)
        a = X.taken(X.run_session2(s, X.Cfg(arm), ctx_for(s)))
        b = X9.taken(X9.run_session(s, X9.Cfg(arm)))
        assert [(t["entry_row"], t["exit_row"], round(t["net"], 6)) for t in a] == \
               [(t["entry_row"], t["exit_row"], round(t["net"], 6)) for t in b]


@pytest.mark.parametrize("fid", [f for f in X.FILTERS if f not in ("K1", "K2")])
def test_filters_only_remove(fid):
    for arm in ARMS:
        for seed in range(5):
            s = make_sess(seed, drift=(seed % 3 - 1) * 3.0, expiry=seed == 2)
            c = ctx_for(s)
            b0 = X.run_session2(s, X.Cfg(arm), c)
            v = X.run_session2(s, X.Cfg(arm, 1, (fid,)), c)
            key = lambda t: (t["entry_row"], t["exit_row"], t["side"])  # noqa: E731
            assert [key(t) for t in v] == [key(t) for t in b0]
            assert {key(t) for t in X.taken(v)} <= {key(t) for t in b0}


@pytest.mark.parametrize("cid", [c for c in X.EXITS if c != "DCAP"])
@pytest.mark.parametrize("lots", [1, 3])
def test_exits_tighten_only(cid, lots):
    for arm in ARMS:
        for seed in range(5):
            s = make_sess(seed, drift=(seed % 3 - 1) * 3.0)
            c = ctx_for(s)
            b5 = X9.bars5(s.ix)
            for t in X.taken(X.run_session2(s, X.Cfg(arm, lots), c)):
                v = X.simulate2(s, X.Cfg(arm, lots, (cid,)), t["side"], t["sig_row"], t["k"], 40.0, b5, c)
                assert v["exit_row"] <= t["exit_row"], (cid, arm, seed)


def test_dcap_only_removes_entries():
    for seed in range(8):
        s = make_sess(seed, drift=(seed % 3 - 1) * 4.0)
        c = ctx_for(s)
        b0 = X.taken(X.run_session2(s, X.Cfg("orb_sweep", 3), c))
        v = X.taken(X.run_session2(s, X.Cfg("orb_sweep", 3, ("DCAP",)), c))
        assert [t["entry_row"] for t in v] == [t["entry_row"] for t in b0][:len(v)]


def test_twenty_point_exits():
    s = make_sess(1, drift=3.0)
    c = ctx_for(s)
    b5 = X9.bars5(s.ix)
    for t in X.taken(X.run_session2(s, X.Cfg("orb"), c)):
        for z, stop in (("Z1", 20.0), ("Z2", 20.0), ("Z3", 15.0)):
            v = X.simulate2(s, X.Cfg("orb", 1, (z,)), t["side"], t["sig_row"], t["k"], 40.0, b5, c)
            if v["why"] == "stop":
                assert t["entry"] - v["exit"] <= stop + 2.0          # the level, then stop slippage


# ------------------------------------------------------------------------------------------------ the walk
def W(o, h, l, c, **kw):
    n = len(c)
    nan = np.full(n, np.nan)
    a = dict(ixh=nan, ixl=nan, ixc=nan, stdir=np.zeros(n, np.int64), c0=0, e=100.0, trig=60.0, tgt=40.0, lad_t=40.0,
             be=101.0, be_pct=0.0, lf_p=np.zeros(0), lf_k=np.zeros(0), g_reach=0.0, g_keep=0.0, g_trail=0.0, q_tgt=0.0,
             ts_row=-1, ts_need=0.0, nip_row=-1, side=1, ixa_lvl=0.0, fk_row=-1, fk_lvl=0.0, volx_atr=0.0, volx_ref=0.0,
             volp_atr=0.0, mom=0, sq=n)
    a.update(kw)
    f = lambda x: np.asarray(x, float)  # noqa: E731
    return X.walk2(f(o), f(h), f(l), f(c), a["ixh"], a["ixl"], a["ixc"], a["stdir"], a["c0"], a["e"], a["trig"], a["tgt"],
                   a["lad_t"], a["be"], a["be_pct"], a["lf_p"], a["lf_k"], a["g_reach"], a["g_keep"], a["g_trail"],
                   a["q_tgt"], a["ts_row"], a["ts_need"], a["nip_row"], a["side"], a["ixa_lvl"], a["fk_row"], a["fk_lvl"],
                   a["volx_atr"], a["volx_ref"], a["volp_atr"], a["mom"], a["sq"])


def test_walk2_plain_equals_r169_walk():
    rng = np.random.default_rng(1)
    for _ in range(40):
        c = 100 + np.cumsum(rng.normal(0, 3, 100))
        o = np.r_[100, c[:-1]]
        h, l = np.maximum(o, c) + 1, np.minimum(o, c) - 1
        nan = np.full(100, np.nan)
        a = W(o, h, l, c, trig=E.floor_tick(60.0))
        b = X9.walk(o, h, l, c, nan, nan, nan, 0, 100.0, E.floor_tick(60.0), 40.0, 40.0, 101.0, 0.0, -1, 0.0, 0.0, 1, 0.0,
                    0, 0.0, 0.0, 100)
        assert (a[0], a[2]) == (b[0], b[2]) and (np.isnan(a[1]) and np.isnan(b[1]) or a[1] == b[1])


def test_walk2_rules():
    n = 40
    o = [100.0] * n
    assert W(o, [101] * n, [99] * n, [99.5] * n, nip_row=29)[0:3:2] == (30, 5)      # not in profit at 30 min
    assert W(o, [101] * n, [99] * n, [100.5] * n, nip_row=29)[2] == 4
    ix = np.full(n, 50_000.0)
    ix2 = ix.copy()
    ix2[7] = 49_900.0
    assert W(o, [101] * n, [99] * n, o, ixa_lvl=49_950.0, ixc=ix2, ixh=ix, ixl=ix)[0:3:2] == (8, 6)
    assert W(o, [101] * n, [99] * n, o, fk_row=2, fk_lvl=50_010.0, ixc=ix, ixh=ix + 5, ixl=ix - 5)[0:3:2] == (3, 10)
    assert W(o, [101] * n, [99] * n, o, fk_row=2, fk_lvl=50_001.0, ixc=ix, ixh=ix + 5, ixl=ix - 5)[2] == 4
    sd = np.ones(n, np.int64)
    sd[10:] = -1
    assert W(o, [101] * n, [99] * n, o, mom=1, stdir=sd, ixc=ix, ixh=ix, ixl=ix)[0:3:2] == (11, 9)
    h = [101] * 5 + [130] + [101] * 34
    assert W(o, h, [99] * n, o, tgt=0.0, lad_t=0.0, q_tgt=125.0)[0:3:2] == (6, 7)
    r, p, why = W([100, 110, 103], [100, 116, 103], [99, 109, 100], [100, 115, 101], tgt=0.0, lad_t=0.0, be_pct=0.15)
    assert why == 1 and p == 101.0
    r, p, why = W([100, 112, 110], [100, 121, 110], [99, 111, 100], [100, 120, 101], tgt=0.0, lad_t=0.0,
                  lf_p=np.array([0.10, 0.20]), lf_k=np.array([0.02, 0.12]))
    assert why == 1 and p == pytest.approx(110.0)
    r, p, why = W([100, 130, 120], [100, 131, 120], [99, 129, 105], [100, 130, 110], tgt=0.0, lad_t=0.0, g_reach=125.0,
                  g_keep=105.0, g_trail=10.0)
    assert why == 1 and p == pytest.approx(120.0)                                    # min(level 121, open 120)


def test_walk2_lookahead_guard():
    rng = np.random.default_rng(7)
    for _ in range(40):
        c = 100 + np.cumsum(rng.normal(0, 3, 120))
        o = np.r_[100, c[:-1]]
        h, l = np.maximum(o, c) + 1, np.minimum(o, c) - 1
        ix = 50_000 + np.cumsum(rng.normal(0, 5, 120))
        sd = X.supertrend_dir(np.c_[ix, ix + 3, ix - 3, ix])
        kw = dict(trig=60.0, be_pct=0.15, lf_p=np.array([0.1]), lf_k=np.array([0.02]), g_reach=110.0, g_keep=101.0,
                  g_trail=5.0, q_tgt=135.0, ts_row=44, ts_need=5.0, nip_row=29, ixa_lvl=49_950.0, fk_row=4,
                  fk_lvl=50_002.0, volx_atr=10.0, volx_ref=50_000.0, volp_atr=1.0, mom=1)
        r, p, why = W(o, h, l, c, ixc=ix, ixh=ix + 3, ixl=ix - 3, stdir=sd.astype(np.int64), **kw)
        cut = min(r + 1, 119)
        j = lambda a: np.r_[a[:cut], np.full(len(a) - cut, 7e6)]  # noqa: E731
        sdj = np.r_[sd[:cut], -sd[cut:]].astype(np.int64)
        r2, p2, why2 = W(j(o), j(h), j(l), j(c), ixc=j(ix), ixh=j(ix + 3), ixl=j(ix - 3), stdir=sdj, **kw)
        assert (r, why) == (r2, why2)


# ------------------------------------------------------------------------------------------------ helpers
def test_ema_and_supertrend_causal():
    x = np.r_[np.nan, np.arange(1.0, 30.0)]
    e = X.ema(x, 20)
    assert np.isnan(e[0]) and e[1] == 1.0 and e[-1] < x[-1]
    rng = np.random.default_rng(3)
    c = 100 + np.cumsum(rng.normal(0, 1, 200))
    bars = np.c_[c, c + 1, c - 1, c]
    d = X.supertrend_dir(bars)
    assert set(np.unique(d)) <= {-1, 0, 1} and (d[:9] == 0).all()
    for cut in (50, 120):
        assert (X.supertrend_dir(bars[:cut]) == d[:cut]).all()
    assert (X.supertrend_dir(np.full((20, 4), np.nan)) == 0).all()


def test_swings_confirmation_and_levels():
    b = np.full((20, 4), 100.0)
    b[:, 1], b[:, 2] = 101.0, 99.0
    b[8, 1] = 110.0
    assert 110.0 not in X.swings(b, 3, upto=10) and 110.0 in X.swings(b, 3, upto=11)
    a, bh, na, nb = X.level_check([100.2, 99.0, 101.0], 100.0, 1, broken=101.0)
    assert a == pytest.approx(0.2) and bh == pytest.approx(1.0) and na and not nb
    a, bh, na, nb = X.level_check([], 100.0, 1, broken=0.0)
    assert a == np.inf and bh == np.inf and not na and not nb


def test_htf_side_and_groups():
    rng = np.random.default_rng(2)
    days = []
    for _ in range(6):
        c = 100 + np.cumsum(rng.normal(0, 1, 75))
        days.append(np.c_[c, c + 1, c - 1, c])
    g = X.group_bars(days[0], 12)
    assert len(g) == 7                                   # 6 full hours + the short 15:15 bar
    s15 = X.htf_side(days, 3)
    assert set(np.unique(s15)) <= {-1, 0, 1}
    assert (X.htf_side(days[:1], 12)[:5] == 0).all()     # not enough 60-minute bars
    alt = [d.copy() for d in days]
    alt[-1][40:] = 1e6
    assert (X.htf_side(alt, 3)[:38] == s15[:38]).all()


def test_option_helpers():
    s = make_sess(0)
    assert np.isfinite(X.pcr(s, 100))
    s2 = make_sess(0)
    s2.opts = {}
    assert np.isnan(X.pcr(s2, 100)) and (X.band_cumvol(s2) == 0).all()
    a = s.opts[(s.atm, "C")].astype(float)
    assert X.signed_volume(a, 100, 3) == pytest.approx(
        sum(a[r, 5 - 1] * np.sign(a[r, 3] - a[r - 1, 3]) for r in (98, 99, 100)))
    assert np.isfinite(X.opt_atr(a, 100)) and np.isnan(X.opt_atr(np.full((375, 6), np.nan), 100))
    assert X.pivots((0, np.nan, 1, 1)) == []
    p = X.pivots((0, 110.0, 90.0, 100.0))
    assert p[0] == pytest.approx(100.0) and p[1] == pytest.approx(110.0)


def test_filter_lookahead_guard():
    for seed in (1, 4):
        for row in (64, 99, 149):
            s = make_sess(seed, drift=3.0)
            c = ctx_for(s)
            b5 = X9.bars5(s.ix)
            hi, lo = E.opening_range(b5)
            s2 = make_sess(seed, drift=3.0)
            s2.ix[row + 1:] = 9e9
            for a in s2.opts.values():
                a[row + 1:] = 9e5
            b52 = X9.bars5(s2.ix)
            for fid in [f for f in X.FILTERS if f not in ("K1", "K2", "E5", "E6", "E7", "P8", "P9")]:
                for arm in ARMS:
                    for side in (1, -1):
                        assert X.skip2(fid, s, arm, side, row, hi, lo, b5, s.atm, c) == \
                               X.skip2(fid, s2, arm, side, row, hi, lo, b52, s2.atm, c), fid
            f1 = X.pick_features(s, 1, row, hi, lo, b5, s.atm, c)
            f2 = X.pick_features(s2, 1, row, hi, lo, b52, s2.atm, c)
            assert all((np.isnan(f1[k]) and np.isnan(f2[k])) or f1[k] == pytest.approx(f2[k]) for k in f1)


def test_unknown_never_skips():
    s = make_sess(0)
    b5 = X9.bars5(s.ix)
    hi, lo = E.opening_range(b5)
    empty = dict(breadth={})
    s.prev = (np.nan, np.nan, np.nan, np.nan)
    s.opts = {}
    s.vix = np.full(375, np.nan)
    # the filters whose inputs were removed (yesterday, the options, VIX, the context); index-only ones still decide
    for fid in ("E3", "E4", "E5", "E6", "E7", "E8", "E10", "E11", "P5", "P8", "P9", "P13", "P12", "V1", "V2", "I1", "I2",
                "D1", "D2", "O1", "K1", "K2", "A1", "Q1"):
        assert X.skip2(fid, s, "orb", 1, 64, hi, lo, b5, s.atm, empty) is False, fid
