"""R167 tests (PREREG section 9): every pure function, the look-ahead guard for every measurement and rule, and the
engine on synthetic days through R106's real simulator (T versions never exit later than B0; the giveback locks).

    python -m pytest research/hunt/r167_donchian_smart_exits -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r167cfg as K  # noqa: E402
import r167feat as F  # noqa: E402
import r167lib as L  # noqa: E402

N = 375


# ================================================================================================ synthetic data
def walk_days(n_days=24, seed=1, start=25000.0, vol=6.0, drift=0.0):
    rng = np.random.default_rng(seed)
    days, px = [], start
    for _ in range(n_days):
        a = np.full((4, N), np.nan)
        for m in range(N):
            o = px
            c = o + rng.normal(drift, vol)
            a[0, m], a[3, m] = o, c
            a[1, m] = max(o, c) + abs(rng.normal(0, vol / 2))
            a[2, m] = min(o, c) - abs(rng.normal(0, vol / 2))
            px = c
        days.append(a)
    return days


def option_from(idx: np.ndarray, strike: float, side: str = "C", tv: float = 80.0, seed=0) -> np.ndarray:
    """(5, 375) float64 option bars that follow the index (delta about 0.6) plus volume."""
    rng = np.random.default_rng(seed)
    s = 1 if side == "C" else -1
    base = idx[3, 0]
    C = np.full((5, N), np.nan)
    for m in range(N):
        mid = tv + max(s * (idx[3, m] - strike), 0) + 0.6 * s * (idx[3, m] - base) * 0.3
        o = tv + max(s * (idx[0, m] - strike), 0) + 0.6 * s * (idx[0, m] - base) * 0.3
        C[0, m], C[3, m] = max(o, 1), max(mid, 1)
        C[1, m] = max(C[0, m], C[3, m]) + abs(rng.normal(0, 0.5))
        C[2, m] = max(min(C[0, m], C[3, m]) - abs(rng.normal(0, 0.5)), 0.5)
        C[4, m] = float(rng.integers(50, 500))
    return C


def cut(a: np.ndarray, m: int) -> np.ndarray:
    b = np.array(a, float)
    b[..., m + 1:] = np.nan
    return b


@pytest.fixture(scope="module")
def market():
    days = walk_days()
    vix = [np.linspace(12, 14, N) + i * 0.1 for i in range(len(days))]
    ctxs = F.build(days, vix, first_ctx=20)
    return days, vix, ctxs


# ================================================================================================ measurements
def test_wilder_mean_then_smooth_and_skips_nan():
    x = np.array([1.0, np.nan, 3.0, 5.0, 7.0])
    w = F.wilder(x, 3)
    assert np.isnan(w[0]) and np.isnan(w[2])
    assert w[3] == pytest.approx(3.0)                       # mean of 1, 3, 5
    assert w[4] == pytest.approx((3.0 * 2 + 7.0) / 3)
    assert F.wilder(x, 3, min_count=1)[0] == pytest.approx(1.0)
    assert np.isnan(F.wilder(np.array([]), 3)).all()


def test_true_range_uses_previous_close():
    tr = F.true_range(np.array([10.0, 12.0]), np.array([9.0, 11.5]), np.array([9.5, 12.0]))
    assert tr[0] == pytest.approx(1.0) and tr[1] == pytest.approx(2.5)   # |12 - 9.5|


def test_supertrend_turns_down_after_a_fall_and_skips_gaps():
    up = np.linspace(100, 130, 60)
    dn = np.linspace(130, 90, 60)
    c = np.concatenate([up, dn])
    h, l_ = c + 0.5, c - 0.5
    h[5] = np.nan
    d = F.supertrend_dir(h, l_, c)
    assert np.isnan(d[5]) and np.nanmax(d[:60]) == 1.0
    assert d[-1] == -1.0


def test_efficiency_line_is_one_and_needs_15_minutes():
    c = np.full(N, np.nan)
    c[:30] = np.arange(30.0)
    e = F.efficiency(c)
    assert np.isnan(e[13]) and e[14] == pytest.approx(1.0) and e[40] == pytest.approx(1.0)
    z = np.full(N, 5.0)
    assert F.efficiency(z)[20] == 0.0


def test_carry_fills_forward_only():
    x = np.array([np.nan, 2.0, np.nan, 3.0])
    assert np.isnan(F.carry(x)[0]) and list(F.carry(x)[1:]) == [2.0, 2.0, 3.0]


def test_build_ctx_fields(market):
    days, _, ctxs = market
    assert ctxs[0] is None and ctxs[19] is None and ctxs[20] is not None
    c = ctxs[-1]
    assert np.isfinite(c.atr5).all()                         # warmed up from earlier days
    assert c.lev_key.min() >= 0 and all(lv == sorted(lv) for lv in c.lev_lists)
    assert set(np.unique(c.st[np.isfinite(c.st)])) <= {-1.0, 1.0}
    assert np.isfinite(c.vix).all()


def test_build_empty_and_no_vix():
    assert F.build([]) == []
    c = F.build(walk_days(3, seed=9), None)[-1]
    assert np.isnan(c.vix).all() and c.levels_at(-1) == [] and c.levels_at(N) == []


@pytest.mark.parametrize("m", [0, 4, 37, 120, 299, 374])
def test_lookahead_guard_measurements(market, m):
    """The last day cut after minute m gives the same measurements at every minute <= m."""
    days, vix, ctxs = market
    full = ctxs[-1]
    cc = F.build(days[:-1] + [cut(days[-1], m)], vix[:-1] + [cut(vix[-1], m)], first_ctx=20)[-1]
    for j in range(m + 1):
        assert np.allclose(full.atr5[j], cc.atr5[j], equal_nan=True)
        assert np.allclose(full.st[j], cc.st[j], equal_nan=True)
        assert np.allclose(full.er[j], cc.er[j], equal_nan=True)
        assert np.allclose(full.vix[j], cc.vix[j], equal_nan=True)
        assert full.levels_at(j) == cc.levels_at(j)
        for s in (1, -1):
            assert full.swing_below(j, s, s * full.c[j]) == cc.swing_below(j, s, s * full.c[j])


# ================================================================================================ rules: unit cases
def flat_ctx(c, atr=10.0, levels=(), vix=13.0, st=None):
    c = np.asarray(c, float)
    o = np.concatenate([[c[0]], c[:-1]])
    return F.DayCtx(o=o, h=np.maximum(o, c) + 0.5, l=np.minimum(o, c) - 0.5, c=c, atr5=np.full(N, atr),
                    st=np.full(N, 1.0) if st is None else st, er=np.full(N, 0.5), vix=np.full(N, vix),
                    lev_key=np.zeros(N, int), lev_lists=[sorted(levels)])


def test_exit_row_semantics():
    f = np.zeros(N, bool)
    assert L.exit_row(f, 10, 360) == -1
    f[50] = True
    assert L.exit_row(f, 10, 360) == 51 and L.exit_row(f, 51, 360) == -1
    f2 = np.zeros(N, bool)
    f2[359] = True
    assert L.exit_row(f2, 10, 360) == -1                    # the square-off row sells instead


def test_sr_target_before_resistance_in_profit_only():
    c = np.full(N, 100.0)
    c[20:] = np.linspace(100, 120, N - 20)
    ctx = flat_ctx(c, atr=10.0, levels=(90.0, 110.0, 200.0))
    oc = np.full(N, 50.0)
    fire = L.sr_fire(ctx, 1, 10, 360, oc + 10, 50.0)
    m = int(np.argmax(fire))
    assert fire.any() and ctx.h[m] >= 110 - 1.0 and ctx.c[m] <= 111.0
    assert not L.sr_fire(ctx, 1, 10, 360, oc - 10, 50.0)[:m + 1].any()   # not in profit: no target


def test_sr_breakout_keeps_trade_and_stop_ratchets():
    c = np.full(N, 100.0)
    c[30] = 115.0                                           # closes far through 110 in one minute (breakout)
    c[31:] = 115.0
    c[60:] = 108.5                                          # back below the broken level - b = 109
    ctx = flat_ctx(c, atr=10.0, levels=(90.0, 110.0))
    fire = L.sr_fire(ctx, 1, 10, 360, np.full(N, 60.0), 50.0)
    assert not fire[30] and fire[60]


def test_sr_put_mirror_and_fallback_stop():
    c = np.full(N, 100.0)
    c[40:] = 125.0                                          # a put: index up 2.5 ATR is beyond the fallback stop
    ctx = flat_ctx(c, atr=10.0, levels=())
    fire = L.sr_fire(ctx, -1, 10, 360, np.full(N, 40.0), 50.0)
    assert fire[40] and not fire[:40].any()


def test_volx_ratchet_vs_free():
    c = np.full(N, 100.0)
    c[10:40] = np.linspace(100, 125, 30)
    c[40:] = 125.0
    c[80:] = 103.0                                          # falls 22 from the peak area
    atr = np.full(N, 6.0)
    atr[60:] = 20.0                                         # volatility jumps: the free stop widens
    ctx = flat_ctx(c, levels=())
    ctx.atr5 = atr
    t = L.volx_fire(ctx, 1, 10, 360, np.full(N, 10.0), 50.0, True)
    f = L.volx_fire(ctx, 1, 10, 360, np.full(N, 10.0), 50.0, False)
    assert t[80] and not f[80]


def test_volx_target_needs_profit():
    c = np.linspace(100, 160, N)
    ctx = flat_ctx(c, atr=5.0)
    assert L.volx_fire(ctx, 1, 0, 360, np.full(N, 60.0), 50.0, True).any()
    assert not L.volx_fire(ctx, 1, 0, 360, np.full(N, 40.0), 50.0, True).any()


def test_volp_needs_five_bars_and_trails():
    C = np.full((5, N), np.nan)
    C[3, :] = 100.0
    C[1, :], C[2, :] = 101.0, 99.0
    C[3, 100] = 90.0                                        # 10 below: more than 3 x ATR(=2)
    C[1, 100], C[2, 100] = 100.0, 89.0
    fire = L.volp_fire(C, 50, 360, True)
    assert fire[100] and not fire[:100].any()
    C2 = np.full((5, N), np.nan)
    C2[:, :3] = 100.0
    assert np.isnan(L.option_atr(C2)[2])


def test_spikes_and_oi_against():
    v = np.full(N, 100.0)
    v[50] = 400.0
    v[5] = 1000.0                                           # too few earlier minutes
    sp = L.spikes(v)
    assert sp[50] and not sp[5] and not sp[49]
    oi = np.full(N, 1000.0)
    oi[60] = 1030.0
    oc = np.full(N, 50.0)
    oc[60] = 45.0
    assert L.oi_against(oi, oc, 60) and not L.oi_against(oi, oc, 3) and not L.oi_against(None, oc, 60)
    oi2 = oi.copy()
    oi2[55] = np.nan
    assert not L.oi_against(oi2, oc, 60)


def test_flow_tighten_and_extended_time():
    c = np.full(N, 100.0)
    c[50] = 99.0                                            # index bar against on the spike minute
    c[70] = 96.0                                            # later close below the 3-minute low trail
    ctx = flat_ctx(c)
    C = np.full((5, N), 50.0)
    C[4, :] = 100.0
    C[4, 50] = 500.0
    fire, tfire = L.flow_fire(ctx, 1, 10, 360, C, None)
    assert fire[70] and not fire[:70].any()
    assert tfire[39] and not tfire[38]                      # no confirming spike: 30 minutes from 10
    c2 = np.full(N, 100.0)
    c2[20] = 101.0                                          # confirming spike (bar with the trade)
    C2 = C.copy()
    C2[4, 50] = 100.0
    C2[4, 20] = 600.0
    _, t2 = L.flow_fire(flat_ctx(c2), 1, 10, 360, C2, None)
    assert t2[54] and not t2[53]                            # 45 minutes


def test_mom_minhold_and_direction():
    st = np.full(N, 1.0)
    st[11] = -1.0
    st[30] = -1.0
    ctx = flat_ctx(np.full(N, 100.0), st=st)
    f = L.mom_fire(ctx, 1, 10, 360)
    assert not f[11] and f[30]
    assert L.mom_fire(ctx, -1, 10, 360)[12]


def test_regimes_and_ctx_choice():
    ctx = flat_ctx(np.full(N, 100.0), vix=20.0)
    ctx.er = np.full(N, 0.1)
    ctx.er[200:] = 0.5
    r = L.regimes(ctx, 15.0)
    assert r[0] == 2 and r[300] == 3
    ctx.vix[:] = np.nan
    ctx.er[:] = np.nan
    assert (L.regimes(ctx, 15.0) == 0).all()
    rows = [dict(regime=0, res=dict(B0=100, SR=150, MOM=200)), dict(regime=0, res=dict(B0=-50, SR=-10, MOM=-300))]
    ch = L.choose_menu(rows, ("B0", "SR", "MOM"))
    assert ch[0] == "SR" and ch[1] == "B0"                  # MOM nets less; empty regimes -> B0
    tie = [dict(regime=2, res=dict(B0=100, SR=100))]
    assert L.choose_menu(tie, ("B0", "SR"))[2] == "B0"
    fs = {"SR": np.zeros(N, bool)}
    fs["SR"][100] = True
    assert L.ctx_fire(fs, np.zeros(N, int), {0: "SR"})[100]
    assert not L.ctx_fire(fs, np.ones(N, int), {0: "SR", 1: "B0"}).any()


def test_ml_label_order_and_missing():
    c = np.full(N, 100.0)
    ctx = flat_ctx(c, atr=10.0)
    ctx.h = c + 0.5
    ctx.l = c - 0.5
    ctx.h[20] = 111.0
    assert L.ml_label(ctx, 1, 10, 360) == 1.0
    ctx.l[20] = 89.0                                        # both in one minute: adverse first
    assert L.ml_label(ctx, 1, 10, 360) == 0.0
    ctx.atr5[10] = np.nan
    assert np.isnan(L.ml_label(ctx, 1, 10, 360))
    assert L.ml_label(flat_ctx(c), 1, 10, 360) == 0.0       # neither: 0


def test_ml_features_and_model():
    ctx = flat_ctx(np.linspace(100, 110, N), levels=(105.0, 120.0))
    C = np.full((5, N), 50.0)
    C[4, :] = 100.0
    vm = L.vol_median(C[4])
    x = L.ml_features(ctx, 1, 10, 30, C, None, 50.0, vm)
    assert len(x) == len(L.FEATS) and x[1] == 20.0 and np.isnan(x[12])
    i = L.FEATS.index("roc3")
    xp = L.ml_features(ctx, -1, 10, 30, C, None, 50.0, vm)
    assert x[i] > 0 and xp[i] == pytest.approx(-x[i])       # a rising index is favourable for a call, not a put
    rng = np.random.default_rng(0)
    X = rng.normal(size=(400, len(L.FEATS)))
    X[::7, 3] = np.nan
    y = (X[:, 0] + rng.normal(0, 0.5, 400) > 0).astype(float)
    m = L.ExitModel().fit(X, y)
    p = m.proba(X)
    assert 0 < m.theta < 1 and np.mean(p < m.theta) == pytest.approx(0.25, abs=0.02)
    with pytest.raises(ValueError):
        L.ExitModel().fit(X, np.ones(400))
    fire = L.ml_fire(np.array([0.9, 0.1, np.nan]), [12, 13, 14], 0.5)
    assert fire[13] and not fire[12] and not fire[14]


def test_g3_arms_only_after_trigger():
    c = np.full(N, 100.0)
    c[60] = 95.0
    ctx = flat_ctx(c)
    oh = np.full(N, 50.0)
    assert not L.g3_fire(ctx, 1, 10, 360, oh, 60.0).any()
    oh[30] = 61.0
    f = L.g3_fire(ctx, 1, 10, 360, oh, 60.0)
    assert f[60] and not f[:60].any()


def test_giveback_counts():
    tr = [dict(net=-2500, best_net=1800), dict(net=-100, best_net=1500), dict(net=900, best_net=2000),
          dict(net=-3000, best_net=800), dict(net=50, best_net=None)]
    g = L.giveback(tr)
    assert g["reached"] == 3 and g["ended_le0"] == 2 and g["ended_le_m2000"] == 1 and g["ended_le0_net"] == -2600
    assert L.giveback([])["reached_share"] is None


# ================================================================================================ stats, twins, BH, gates
def test_stats_and_moved():
    d1, d2 = date(2025, 1, 1), date(2025, 1, 2)
    tr = [dict(net=100, day=d1, why="stop", und="N", er_sig=5, side="C", xr=20),
          dict(net=-300, day=d2, why="time", und="N", er_sig=6, side="C", xr=40)]
    s = L.stats(tr)
    assert s["trades"] == 2 and s["win"] == 0.5 and s["worst_day"] == -300 and s["max_dd"] == 300
    assert L.stats([])["win"] is None
    base = {("N", d1, 5, "C"): dict(xr=30), ("N", d2, 6, "C"): dict(xr=40)}
    assert L.moved(tr, base) == {"earlier": 1, "same": 1}


def test_twin_window_p_and_joint():
    assert L.twin_window(10, 20, 20) is None and L.twin_window(10, 12, 12) is None
    assert L.twin_window(10, 15, 30) == (11, 29, "b0")
    assert L.twin_window(10, 40, 30) == (31, 39, "rule")
    assert L.twin_window(10, 31, 30) is None
    assert L.twin_p(5.0, [1, 2, 3]) == pytest.approx(0.25)
    j = L.joint_p(10.0, 0.5, np.array([1.0, 20.0]), np.array([0.1, 0.2]))
    assert j["p_net"] == pytest.approx(2 / 3) and j["p_win"] == pytest.approx(1 / 3) and j["p"] == j["p_net"]


def test_bh_counts_earlier_tests():
    q = L.bh([0.01, 0.04, 0.5])
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.06)
    q2 = L.bh([0.001], m_total=2664)
    assert q2[0] == pytest.approx(1.0) and len(L.bh([])) == 0


def test_gates():
    b = {p: dict(win=0.45, net=1000, trades=100) for p in ("design", "wf", "final")}
    good = {p: dict(win=0.51, net=1100, trades=100) for p in ("design", "wf", "final")}
    assert L.gates(good, b, 0.05)["passed"]
    assert not L.gates(good, b, 0.2)["passed"]
    bad = dict(good, final=dict(win=0.49, net=900, trades=30))
    f = L.gates(bad, b, 0.05)["fails"]
    assert any("win rate" in x for x in f) and any("net lower" in x for x in f) and any("final trades" in x for x in f)


def test_cfg_counts_and_windows():
    assert len(K.VARIANTS) == 17 and len(K.TESTS) == 102 and K.M_TOTAL == 2664
    assert K.versions("G1") == ("T",) and K.versions("SR") == ("T", "F")
    assert K.train_window(date(2025, 4, 2), "wf") == (K.START, date(2025, 3, 31))
    assert K.train_window(date(2026, 5, 1), "final") == (K.START, K.DESIGN_END)
    with pytest.raises(ValueError):
        K.train_window(date(2026, 5, 1), "wf")
    assert K.period_of(date(2026, 10, 14)) is None


# ================================================================================================ look-ahead guard: rules
@pytest.mark.parametrize("m", [40, 90, 150, 240, 330])
def test_lookahead_guard_rules(market, m):
    """Every rule's flags at minutes <= m are the same when the index, VIX, option and OI are cut after m."""
    days, vix, ctxs = market
    full = ctxs[-1]
    cc = F.build(days[:-1] + [cut(days[-1], m)], vix[:-1] + [cut(vix[-1], m)], first_ctx=20)[-1]
    strike = float(round(days[-1][3, 20] / 50) * 50)
    Cf = option_from(days[-1], strike, "C", seed=3)
    Cc = cut(Cf, m)
    oif = 1000 + np.cumsum(np.random.default_rng(5).normal(0, 10, N))
    oic = cut(oif, m)
    f, sq, raw = 20, 360, float(Cf[0, 20])
    rng = np.random.default_rng(2)
    X = rng.normal(size=(300, len(L.FEATS)))
    model = L.ExitModel().fit(X, (X[:, 2] > 0).astype(float))
    for s in (1, -1):
        def all_fires(ctx, C, oi):
            out = dict(SR=L.sr_fire(ctx, s, f, sq, C[3], raw), VXT=L.volx_fire(ctx, s, f, sq, C[3], raw, True),
                       VXF=L.volx_fire(ctx, s, f, sq, C[3], raw, False), VPT=L.volp_fire(C, f, sq, True),
                       VPF=L.volp_fire(C, f, sq, False), MOM=L.mom_fire(ctx, s, f, sq),
                       G3=L.g3_fire(ctx, s, f, sq, C[1], raw * 1.05))
            a, t = L.flow_fire(ctx, s, f, sq, C, oi)
            out["FLA"], out["FLT"] = a, t
            reg = L.regimes(ctx, 13.5)
            out["CTX"] = L.ctx_fire({"SR": out["SR"], "MOM": out["MOM"]}, reg, {0: "SR", 1: "MOM", 2: "SR", 3: "B0"})
            vm = L.vol_median(C[4])
            rows = list(range(f + 2, min(m, sq - 1) + 1))
            P = model.proba(np.array([L.ml_features(ctx, s, f, j, C, oi, raw, vm) for j in rows]))
            out["ML"] = L.ml_fire(P, rows, model.theta)
            return out
        A, B = all_fires(full, Cf, oif), all_fires(cc, Cc, oic)
        for k in A:
            assert (A[k][:m + 1] == B[k][:m + 1]).all(), (k, s, m)


# ================================================================================================ engine on synthetic days
def _day(idx, C, strike, side="C", d=date(2025, 6, 2)):
    import engine as E
    import r51data
    E._STACKS.clear()                                       # the stack cache is keyed by (index, date)
    import r167engine as EN
    EN.clear_memo()                                         # so is R167's exit-row memo
    D = r51data.Day(d, idx.astype(float), atm=np.full(N, strike), con={(side, float(strike)): C.astype(np.float32)})
    return D


@pytest.fixture(scope="module")
def engine_mods():
    pytest.importorskip("numba")
    import r167engine as EN
    import r152study as R
    return EN, R.exits()


def test_exits_for_versions(engine_mods):
    EN, b0 = engine_mods
    assert EN.exits_for("SR", "T", b0) is b0 and EN.exits_for("G1", "T", b0) is b0
    sr = EN.exits_for("SR", "F", b0)
    assert sr.stop == -1.0 and sr.tmax == -1 and sr.steps == b0.steps and sr.sq_row == b0.sq_row
    mom = EN.exits_for("MOM", "F", b0)
    assert mom.stop == b0.stop and mom.tmax == -1
    assert EN.exits_for("CTX", "F", b0, "B0") is b0 and EN.exits_for("CTX", "F", b0, "VOLX").stop == -1.0


@pytest.mark.parametrize("seed", [11, 12, 13, 14])
def test_engine_t_versions_never_exit_later_than_b0(engine_mods, seed):
    """On random synthetic days, every T version's exit row is <= B0's for the same candidate; F may be later."""
    EN, b0 = engine_mods
    days = walk_days(22, seed=seed, vol=8.0)
    ctx = F.build(days, [np.full(N, 13.0)] * len(days), first_ctx=21)[-1]
    idx = days[-1]
    strike = float(round(idx[3, 30] / 50) * 50)
    C = option_from(idx, strike, "C", seed=seed)
    D = _day(idx, C, strike, d=date(2025, 6, seed - 8))
    oi = 1000 + np.cumsum(np.random.default_rng(seed).normal(0, 15, N))
    cands = [dict(side="C", strike=strike, er=r) for r in (30, 120, 250)]
    base = EN.price("NIFTY", D, ctx, lambda s, k: oi, cands, "B0", "T", b0, 1)
    rng = np.random.default_rng(seed)
    X = rng.normal(size=(300, len(L.FEATS)))
    extra = dict(vix_med=12.0, choice_T={0: "SR", 1: "VOLX", 2: "MOM", 3: "FLOW"},
                 choice_F={0: "B0", 1: "VOLX", 2: "MOM", 3: "SR"}, model=L.ExitModel().fit(X, (X[:, 1] > 0) * 1.0))
    for rule, ver in K.VARIANTS:
        for lots in (1, 3):
            res = EN.price("NIFTY", D, ctx, lambda s, k: oi, cands, rule, ver, b0, lots, extra)
            bl = EN.price("NIFTY", D, ctx, lambda s, k: oi, cands, "B0", "T", b0, lots)
            for t, b in zip(res, bl):
                assert (t is None) == (b is None)
                if t is None:
                    continue
                assert t["er"] == b["er"]                      # same fill whatever the exits
                if ver == "T":
                    assert t["xr"] <= b["xr"], (rule, t["xr"], b["xr"], t["why"])
                assert np.isfinite(t["net"]) and t["lots"] == lots
    assert all(t is not None for t in base)


def test_engine_giveback_locks_turn_bosss_path_into_no_loss(engine_mods):
    """Boss's case: the option runs up (+Rs 1,500 and more open profit at 1 lot) and then collapses back below the -15%
    stop. B0 ends with a loss; G1 (breakeven lock) ends near zero or better and G2 (+Rs 500 floor trailing Rs 1,000)
    ends in profit. A gap through the lock is the only way to do worse."""
    EN, b0 = engine_mods
    idx = walk_days(1, seed=4, vol=0.5)[0]
    strike = 25000.0
    C = np.full((5, N), np.nan)
    path = np.full(N, 200.0)
    path[31:45] = np.linspace(200, 236, 14)                 # +36 x 75 = +Rs 2,700 at the top (NIFTY 75, Jun 2025)
    path[45:58] = np.linspace(236, 160, 13)                 # back down within 30 minutes, through breakeven, to -20%
    path[58:] = 160.0
    C[0] = np.concatenate([[200.0], path[:-1]])
    C[3] = path
    C[1] = np.maximum(C[0], C[3]) + 0.2
    C[2] = np.minimum(C[0], C[3]) - 0.2
    C[4] = 5000.0
    D = _day(idx, C, strike)
    ctx = F.build([idx], [np.full(N, 13.0)])[-1]
    cand = [dict(side="C", strike=strike, er=30)]
    b = EN.price("NIFTY", D, ctx, None, cand, "B0", "T", b0, 1)[0]
    g1 = EN.price("NIFTY", D, ctx, None, cand, "G1", "T", b0, 1)[0]
    g2 = EN.price("NIFTY", D, ctx, None, cand, "G2", "T", b0, 1)[0]
    assert b["best_net"] >= 1500
    assert b["why"] == "stop" and b["net"] < -1500          # today's exits: the -15% stop, a loss
    assert g1["net"] >= -150 and g2["net"] > 300            # slippage and charges only
    assert g1["xr"] <= b["xr"] and g2["xr"] <= g1["xr"]
