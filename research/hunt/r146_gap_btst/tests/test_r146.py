"""R146 tests: synthetic data only (no release or Yahoo file is read).

    python -m pytest research/hunt/r146_gap_btst/tests -q
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from research.hunt.r146_gap_btst import cfg as K
from research.hunt.r146_gap_btst import features as F
from research.hunt.r146_gap_btst import models as M
from research.hunt.r146_gap_btst import study as S
from research.hunt.r146_gap_btst import trade as T
from research.obuy.costs import Costs, Fills

N = K.N


# ================================================================================================ fixtures
def day_arr(base=100.0, drift=0.0, seed=0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    c = base + np.cumsum(rng.normal(drift, 0.2, N))
    o = np.concatenate([[base], c[:-1]])
    a = np.vstack([o, np.maximum(o, c) + 0.1, np.minimum(o, c) - 0.1, c])
    return a


def opt_arr(price=100.0, oi=1000.0, seed=0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    c = price + np.cumsum(rng.normal(0, 0.1, N))
    o = np.concatenate([[price], c[:-1]])
    a = np.vstack([o, np.maximum(o, c) + 0.05, np.minimum(o, c) - 0.05, c, np.full(N, 10.0),
                   oi + np.arange(N, dtype=float)]).astype(np.float32)
    return a


def chain(x: float, step: float, seed=0) -> dict:
    k0 = F.atm(x, step)
    out = {}
    for i in range(-6, 7):
        out[("C", k0 + i * step)] = opt_arr(100 - 10 * i, 1000 + 50 * i, seed + i + 20)
        out[("P", k0 + i * step)] = opt_arr(100 + 10 * i, 1200 - 50 * i, seed + i + 40)
    return out


def call_features(a, opt, others, vix, bu_hist=None):
    d, nd = date(2024, 9, 10), date(2024, 9, 11)
    sessions = [d - timedelta(days=i) for i in range(5, 0, -1)] + [d, nd]
    return F.m_features("NIFTY", d, nd, a, 99.0, opt, others, vix, 13.0, bu_hist or list(np.linspace(-1, 1, 30)),
                        sessions, {date(2024, 9, 12)}, {nd}, set(), {}, None)


# ================================================================================================ look-ahead guard
def test_lookahead_guard_features_ignore_everything_after_1519():
    a = day_arr(100, seed=1) * 240           # NIFTY-like level
    x = a[3, K.R1519]
    opt = chain(x, 50.0, seed=3)
    others = {"FINNIFTY": (day_arr(200, seed=4), 199.0), "SENSEX": (day_arr(300, seed=5), 301.0)}
    vix = day_arr(13, seed=6)
    f1 = call_features(a, opt, others, vix)
    rng = np.random.default_rng(99)

    def scramble(z):
        z = np.array(z, dtype=np.float64, copy=True)
        z[..., K.R1519 + 1:] = rng.normal(0, 1e4, z[..., K.R1519 + 1:].shape)
        return z
    a2 = scramble(a)
    opt2 = {k: scramble(v) for k, v in opt.items()}
    others2 = {u: (scramble(b), pc) for u, (b, pc) in others.items()}
    f2 = call_features(a2, opt2, others2, scramble(vix))
    for k in f1:
        assert (np.isnan(f1[k]) and np.isnan(f2[k])) or f1[k] == pytest.approx(f2[k]), k


def test_m_features_do_not_take_next_session_arrays():
    import inspect
    params = inspect.signature(F.m_features).parameters
    assert not any("next" in p or p.startswith("nd_") for p in params if p != "nd")


def test_walk_forward_trains_only_on_nights_whose_next_session_is_before_the_block(monkeypatch):
    seen = []
    real = M.HistGradientBoostingClassifier

    class Spy(real):
        def fit(self, X, y, *a, **k):
            seen.append(len(X))
            return super().fit(X, y, *a, **k)
    monkeypatch.setattr(M, "HistGradientBoostingClassifier", Spy)
    d0 = date(2024, 1, 1)
    days = [d0 + timedelta(days=i) for i in range(200)]
    rng = np.random.default_rng(0)
    df = pd.DataFrame({"d": days[:-1], "nd": days[1:], "f": rng.normal(size=199), "g": rng.normal(size=199)})
    out = M.walk_forward(df, ["f"], "M", date(2024, 4, 1), date(2024, 6, 30), size_models=False)
    # April block: nights with nd < 1 Apr -> d <= 30 Mar: 90 nights (1 Jan .. 30 Mar)
    assert seen[0] == sum(1 for d in days[1:] if d < date(2024, 4, 1))
    assert out.d.min() >= date(2024, 4, 1) and out.d.max() <= date(2024, 6, 30)


def test_cut_design_never_holds_holdout_rows():
    df = pd.DataFrame({"d": [K.HOLD_START - timedelta(days=1), K.HOLD_START, K.HOLD_START + timedelta(days=3)]})
    assert list(S.cut(df, "design").d) == [K.HOLD_START - timedelta(days=1)]
    assert len(S.cut(df, "holdout")) == 2


def test_holdout_refuses_second_read(tmp_path, monkeypatch):
    flag = tmp_path / "HOLDOUT_READ.flag"
    flag.write_text("read")
    monkeypatch.setattr(K, "FLAG", flag)
    with pytest.raises(SystemExit):
        S.holdout()


# ================================================================================================ features
def test_build_up_matches_nightrules_formula():
    opt = {}
    x, step = 22010.0, 50.0
    for i, k in enumerate([21900, 21950, 22000, 22050, 22100]):
        c = np.full((6, N), np.nan)
        p = np.full((6, N), np.nan)
        c[3, 0], c[3, K.R1519], c[5, 0], c[5, K.R1519] = 100, 110, 1000, 1500 + i       # price up, OI +500+i
        p[3, 0], p[3, K.R1519], p[5, 0], p[5, K.R1519] = 100, 90, 2000, 1800            # price down, OI -200
        opt[("C", float(k))], opt[("P", float(k))] = c, p
    num = sum(1 * (500 + i) - (-1) * 200 for i in range(5))
    den = sum(1500 + i + 1800 for i in range(5))
    assert F.build_up(opt, x, step) == pytest.approx(num / den)


def test_build_up_missing_strike_left_out_and_no_oi_is_nan():
    assert np.isnan(F.build_up({}, 22000.0, 50.0))


def test_zscore_needs_20_values_and_uses_last_60():
    assert np.isnan(F.zscore(1.0, [0.1] * 19))
    h = [0.0] * 100 + list(np.linspace(-1, 1, 60))
    z = F.zscore(1.0, h)
    ref = (1.0 - np.mean(h[-60:])) / np.std(h[-60:], ddof=1)
    assert z == pytest.approx(ref)


def test_m_features_values_on_a_simple_day():
    a = np.full((4, N), np.nan)
    a[:, :K.R1519 + 1] = 100.0
    a[3, K.R1519] = a[0, K.R1519] = 102.0
    a[1, K.R1519] = 102.0
    a[3, K.R1449] = 101.0
    a[0, 0] = 99.5
    a[2, 0] = 99.0
    f = call_features(a, {}, {}, None)
    assert f["m_move"] == pytest.approx(102 / 99 - 1)
    assert f["m_loc"] == pytest.approx(1.0)
    assert f["m_l30"] == pytest.approx(102 / 101 - 1)
    assert f["m_gap0"] == pytest.approx(99.5 / 99 - 1)
    assert f["m_us_ev"] == 1.0 and f["m_in_ev"] == 0.0
    assert f["m_dte"] == 1.0 and f["m_exp"] == 0.0         # expiry 12 Sep, sessions known to 11 Sep
    assert np.isnan(f["m_vix"])


def test_m_targets_gap_from_1519_and_next_open():
    a = day_arr(100, seed=1)
    nx = np.full((4, N), np.nan)
    nx[0, 0] = 105.0
    t = F.m_targets(a, nx, 100.0)
    assert t["g"] == pytest.approx(np.log(1.05))


def test_m_targets_missing_next_open_is_nan():
    t = F.m_targets(day_arr(), np.full((4, N), np.nan), 100.0)
    assert np.isnan(t["g"])


def test_day_ret_closed_market_is_zero_and_prev_ret_strictly_before():
    idx = [date(2024, 1, 1), date(2024, 1, 2), date(2024, 1, 4)]
    df = pd.DataFrame({"close": [100.0, 110.0, 99.0]}, index=idx)
    assert F.day_ret(df, date(2024, 1, 3)) == 0.0
    assert F.day_ret(df, date(2024, 1, 2)) == pytest.approx(np.log(1.1))
    assert F.prev_ret(df, date(2024, 1, 4)) == pytest.approx(np.log(1.1))      # not 4 Jan's own
    assert np.isnan(F.prev_ret(df, date(2024, 1, 1)))


def test_clean_daily_drops_stale_open_year_and_bad_bars():
    days = [date(2010, 1, 1) + timedelta(days=i) for i in range(40)] + \
           [date(2011, 1, 1) + timedelta(days=i) for i in range(40)]
    c = np.linspace(100, 140, 80)
    o = c - 0.5
    o[1:40] = c[:39]                                   # 2010: every open == previous close
    df = pd.DataFrame({"open": o, "high": np.maximum(o, c) + 1, "low": np.minimum(o, c) - 1, "close": c}, index=days)
    df.iloc[60, df.columns.get_loc("high")] = 0.1      # inconsistent bar
    out, chk = F.clean_daily(df)
    assert 2010 in chk["stale_open_years"] and chk["inconsistent_bars"] == 1
    assert all(d.year == 2011 for d in out.index) and len(out) == 39


def test_d_frame_target_is_next_open_over_close_and_features_from_t():
    days = [date(2024, 1, 1) + timedelta(days=i) for i in range(12)]
    c = np.arange(100.0, 112.0)
    o = c - 0.3
    ix = pd.DataFrame({"open": o, "high": c + 1, "low": o - 1, "close": c}, index=days)
    df = F.d_frame(ix, None, {}, None, None, end=date(2030, 1, 1))
    r = df.iloc[0]                                      # t = days[5]
    assert r.d == days[5] and r.nd == days[6]
    assert r.g == pytest.approx(np.log(o[6] / c[5]))
    assert r.d_ret == pytest.approx(np.log(c[5] / c[4]))
    assert len(df) == 12 - 6


# ================================================================================================ rules / stats
def test_rules():
    assert list(M.r_loc(np.array([0.6, 0.59, 0.4, 0.41]))) == [1, 0, -1, 0]
    assert list(M.r_r17([0.7, 0.7, 0.2], [0.01, -0.01, -0.01], [0.5, 0.5, -0.5])) == [1, 0, -1]
    assert list(M.r_r3p([0.7, 0.7, 0.2], [0.01, 0.01, -0.01], [0.5, 0.5, -1], [0.6, 0.4, -0.5])) == [1, 0, -1]
    assert list(M.r_r3p([0.7], [0.01], [1], [np.nan])) == [0]
    assert list(M.r_l30(np.array([0.0004, -0.0006, 0.001]), 0.0005)) == [0, -1, 1]
    assert list(M.side_from_p(np.array([0.56, 0.52, 0.40, np.nan]), 0.05)) == [1, 0, -1, 0]


def test_hit_stats_perfect_and_random():
    rng = np.random.default_rng(0)
    g = rng.normal(size=400)
    s = np.sign(g).astype(int)
    r = M.hit_stats(s, g, np.ones(400, int), rng, perms=300)
    assert r["hit"] == 1.0 and r["p_perm"] < 0.01
    r2 = M.hit_stats(rng.choice([-1, 1], 400), g, np.ones(400, int), rng, perms=300)
    assert r2["p_perm"] > 0.01


def test_bh_matches_definition():
    q = M.bh([0.01, 0.04, 0.03, np.nan])
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.04) and q[2] == pytest.approx(0.04)
    assert np.isnan(q[3])


def test_blocks_months_and_years():
    assert M.blocks(None, "M", date(2024, 11, 5), date(2025, 2, 1)) == [date(2024, 11, 1), date(2024, 12, 1),
                                                                         date(2025, 1, 1), date(2025, 2, 1)]
    assert M.blocks(None, "D", date(2012, 1, 1), date(2013, 6, 1)) == [date(2012, 1, 1), date(2013, 1, 1)]


# ================================================================================================ trade pricer
def leg_setup(entry=100.0, nxt=110.0):
    x, step = 22010.0, 50.0
    k = F.atm(x, step) - step                          # 1-ITM call
    a = np.full((6, N), np.nan, dtype=np.float32)
    a[:4, K.ENTRY_ROWS[0]] = entry
    a[:4, K.R1519] = entry
    nx = np.full((6, N), np.nan, dtype=np.float32)
    for xid, r in K.EXIT_ROW.items():
        nx[:4, r] = nxt
    return x, k, {("C", k): a}, {("C", k): nx}


def test_tradable_rule():
    d, nd = date(2024, 9, 10), date(2024, 9, 11)
    assert T.tradable(d, nd, date(2024, 9, 12), {d}) == "expiry_day"
    assert T.tradable(d, nd, nd, set()) == "expires_by_next_session"
    assert T.tradable(d, nd, date(2024, 9, 12), set()) == ""
    assert T.tradable(d, nd, None, set()) == "no_contract"


def test_leg_entry_exit_and_net():
    x, k, od, on = leg_setup(100.0, 110.0)
    d = date(2024, 9, 10)
    lg, why = T.leg("NIFTY", d, 1, x, od, on)
    assert why == "" and lg.strike == k and lg.e == 100.0 and lg.x["X16"] == 110.0
    q = K.lot("NIFTY", d)
    ef, xf = float(Fills("app").buy(100.0)), float(Fills("app").sell(110.0))
    co = Costs("app")
    ref = (xf - ef) * q - float(co.charge(True, ef, q)) - float(co.charge(False, xf, q)) - 0.0016 * 210 * q
    assert T.net(lg, "X16") == pytest.approx(ref)
    assert T.net(lg, "X16", 1.5) < T.net(lg, "X16")
    assert lg.why["S30"] == "X16"


def test_leg_entry_falls_to_1521_and_exit_falls_forward():
    x, k, od, on = leg_setup()
    a = od[("C", k)]
    a[:4, K.ENTRY_ROWS[0]] = np.nan
    a[:4, K.ENTRY_ROWS[1]] = 101.0
    nx = on[("C", k)]
    nx[:4, K.EXIT_ROW["X16"]] = np.nan
    nx[:4, K.EXIT_ROW["X16"] + 2] = 107.0
    lg, _ = T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)
    assert lg.e == 101.0 and lg.x["X16"] == 107.0 and lg.why["X16"] == "X16_late"


def test_leg_no_entry_bar_and_missing_strike():
    x, k, od, on = leg_setup()
    od[("C", k)][:4, :] = np.nan
    assert T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)[1] == "no_entry_bar"
    assert T.leg("NIFTY", date(2024, 9, 10), -1, x, od, on)[1] == "strike_missing"


def test_leg_no_exit_bar_before_0945():
    x, k, od, on = leg_setup()
    on[("C", k)][:4, :] = np.nan
    assert T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)[1] == "no_exit_bar"


def test_fill_guard_drops_stray_print():
    x, k, od, on = leg_setup()
    od[("C", k)][0, K.ENTRY_ROWS[0]] = 500.0          # open 5x its close
    assert T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)[1] == "fill_guard_entry"


def test_stop_gap_through_fills_at_open():
    x, k, od, on = leg_setup(100.0, 110.0)
    nx = on[("C", k)]
    nx[:4, K.EXIT_ROW["X15"]] = 60.0                  # opens 40% down: through the -30% stop
    lg, _ = T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)
    assert lg.why["S30"] == "stop_gap_through" and lg.x["S30"] == 60.0
    assert lg.xf["S30"] == pytest.approx(float(Fills("app").sell(60.0, stop=True)))


def test_stop_same_day_after_entry():
    x, k, od, on = leg_setup(100.0, 110.0)
    a = od[("C", k)]
    a[:4, K.row(15, 25)] = 100.0
    a[2, K.row(15, 25)] = 65.0
    lg, _ = T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)
    assert lg.why["S30"] == "stop_same_day" and lg.x["S30"] == pytest.approx(70.0)


def test_lot_schedule():
    assert K.lot("NIFTY", date(2024, 9, 1)) == 25 and K.lot("NIFTY", date(2025, 1, 2)) == 75
    assert K.lot("NIFTY", date(2026, 1, 5)) == 65 and K.lot("BANKNIFTY", date(2025, 7, 1)) == 35


# ================================================================================================ trading metrics
def toy_nights(n=60):
    rng = np.random.default_rng(1)
    d0 = date(2024, 8, 1)
    df = pd.DataFrame({"d": [d0 + timedelta(days=i) for i in range(n)], "tradable": True})
    df["net_c_X16"] = rng.normal(100, 50, n)
    df["net_p_X16"] = rng.normal(-100, 50, n)
    df["prem_c"] = df["prem_p"] = 10000.0
    return df


def test_trade_metrics_and_twin():
    t = toy_nights()
    side = np.ones(len(t), int)
    m = S.trade_metrics(t, side, "X16", len(t))
    assert m["trades"] == 60 and m["net_per_trade"] == pytest.approx(t.net_c_X16.mean())
    assert m["max_dd"] <= 0
    p = S.twin_p(t, side, m["total"], np.random.default_rng(0), B=500)
    assert p < 0.01
    p_bad = S.twin_p(t, -side, float(t.net_p_X16.sum()), np.random.default_rng(0), B=500)
    assert p_bad > 0.9


def test_max_dd():
    assert S.max_dd(np.array([100, -50, -80, 200, -10])) == pytest.approx(-130)
    assert S.max_dd(np.array([])) == 0.0


def test_guard_a2_real_gap_passes_isolated_print_fails():
    a = np.full((6, N), np.nan, dtype=np.float32)
    a[0, 10], a[3, 10], a[0, 11] = 150.0, 100.0, 148.0          # far from its close, but the next bar agrees
    assert T._guard(a, 10)
    a[0, 11] = 101.0                                            # next bar back near 100: an isolated print
    assert not T._guard(a, 10)


def test_x15_stale_open_uses_bar_close_and_stop_reads_it():
    x, k, od, on = leg_setup(100.0, 110.0)
    nx = on[("C", k)]
    r = K.EXIT_ROW["X15"]
    nx[0, r], nx[1, r], nx[2, r], nx[3, r] = 150.0, 150.0, 66.0, 68.0     # stale open, trades at 66-68
    nx[:4, r + 1] = 68.0                                                   # 09:16 trades there too
    lg, why = T.leg("NIFTY", date(2024, 9, 10), 1, x, od, on)
    assert why == "" and lg.why["X15"] == "X15_close" and lg.x["X15"] == 68.0
    assert lg.why["S30"] == "stop_gap_through" and lg.x["S30"] == 68.0     # 68 <= 70: gapped through the stop


def test_size_stats_ignores_a_night_without_next_open():
    t = pd.DataFrame({"g": [0.01, np.nan, -0.02, 0.005], "gm_train": 0.0, "am_train": 0.01,
                      "g_ridge": [0.0, 0.0, -0.01, 0.0], "g_gb": [0.0, 0.0, -0.01, 0.0],
                      "a_ridge": [0.01, 0.01, 0.02, 0.01], "a_gb": [0.01, 0.01, 0.02, 0.01]})
    r = S.size_stats(t)
    assert all(np.isfinite(v["r2_vs_train_mean"]) for v in r.values())
