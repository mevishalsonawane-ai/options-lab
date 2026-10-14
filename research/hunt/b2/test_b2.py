"""B2 tests: the look-ahead guard on every swept indicator (1 / 5 / 15-minute) and the context features, inputs never
include a target or outcome, the walk-forward never trains on or near its test block, AUC / BH / trading helpers, and
the holdout refusals. Synthetic data only."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).resolve().parent))
import b2data as D  # noqa: E402
import b2model as M  # noqa: E402
import b2sweep as S  # noqa: E402


def synth_minutes(days=30, seed=0):
    rng = np.random.default_rng(seed)
    ds = pd.bdate_range("2024-01-01", periods=days)
    rows = []
    p = 20000.0
    for d in ds:
        for m in range(375):
            o = p
            p = p * (1 + rng.normal(0, 0.0008))
            rows.append((d, m, o, max(o, p) + abs(rng.normal(0, 3)), min(o, p) - abs(rng.normal(0, 3)), p))
    return pd.DataFrame(rows, columns=["day", "m", "o", "h", "l", "c"])


@pytest.mark.parametrize("tf", [1, 5, 15])
def test_swept_indicators_are_point_in_time(tf):
    M0 = synth_minutes()
    last = M0.day.max()
    days = pd.Index([last])
    base = S.at_rows(S.indicators(S.bars(M0, tf), f"t{tf}"), days, tf)
    for t in (15, 60, 200, 315):
        Mp = M0.copy()
        fut = (Mp.day == last) & (Mp.m > t)
        rng = np.random.default_rng(t)
        for c in "ohlc":
            Mp.loc[fut, c] = rng.uniform(1, 1e5, fut.sum())
        pz = S.at_rows(S.indicators(S.bars(Mp, tf), f"t{tf}"), days, tf)
        a, b = base.xs(t, level="row"), pz.xs(t, level="row")
        assert np.allclose(a.values.astype(float), b.values.astype(float), equal_nan=True), (tf, t)


def test_higher_timeframe_uses_only_closed_bars():
    M0 = synth_minutes(days=3)
    B = S.bars(M0, 15)
    F = S.indicators(B, "t15")
    x = S.at_rows(F, pd.Index([M0.day.max()]), 15)
    # at row 15 (09:30) the last closed 15-minute bar ends at row 14; at row 25 still row 14; at row 30 row 29
    ends = {t: ((t + 1) // 15) * 15 - 1 for t in (15, 25, 30)}
    assert ends == {15: 14, 25: 14, 30: 29}
    assert x.loc[(M0.day.max(), 25)].equals(x.loc[(M0.day.max(), 15)])


def test_context_is_point_in_time(monkeypatch):
    M0 = synth_minutes(days=25)
    last = M0.day.max()
    monkeypatch.setattr(S, "OTHER", ())
    base = S.context(M0, "NIFTY", pd.Index([last]))
    Mp = M0.copy()
    fut = (Mp.day == last) & (Mp.m > 100)
    Mp.loc[fut, ["o", "h", "l", "c"]] = 1e6
    pz = S.context(Mp, "NIFTY", pd.Index([last]))
    a = base.xs(100, level="row")
    b = pz.xs(100, level="row")
    assert np.allclose(a.values.astype(float), b.values.astype(float), equal_nan=True)


def test_supertrend_flips():
    c = np.r_[np.linspace(100, 120, 50), np.linspace(120, 90, 50)]
    side, line = S._supertrend(c + 0.5, c - 0.5, c, np.full(100, 1.0), 2.0)
    assert side[40] == 1 and side[-1] == -1


def test_inputs_never_include_targets_or_outcomes():
    X = pd.DataFrame({"day": [pd.Timestamp("2024-01-02")], "und": ["NIFTY"], "row": [15], "rsi": [50.0], "ret15": [0.1],
                      "ret30": [0.1], "ret60": [0.1], "abs30": [0.1], "net_ce_atm_x1": [1.0], "why_ce_atm_x1": [1.0],
                      "cut_ce_atm_x1": [0.0], "G15": [1.0], "GEOD": [1.0], "CEW": [1.0], "DAYG": [1.0], "ret_15m": [0.2]})
    cols = D.feature_cols(X)
    assert set(cols) == {"row", "rsi", "ret_15m"}          # ret_15m is the PAST 15 minutes, a feature


def test_walk_forward_never_touches_its_block():
    days = pd.Series(pd.bdate_range("2021-08-02", "2026-04-13"))
    for blk, (a, b) in D.BLOCKS.items():
        tr, te = M.fold_masks(days, blk)
        assert not (tr & te).any()
        assert days[tr].max() < pd.Timestamp(a)
        ud = np.sort(days.unique())
        gap = ud[(ud > days[tr].max()) & (ud < pd.Timestamp(a))]
        assert len(gap) >= D.GAP_SESSIONS - 1
        assert days[te].min() >= pd.Timestamp(a) and days[te].max() <= pd.Timestamp(b)


def test_weighted_auc_matches_sklearn_and_weights():
    rng = np.random.default_rng(1)
    y = rng.integers(0, 2, 500)
    s = rng.normal(size=500) + y * 0.3
    s[:50] = np.round(s[:50])                               # ties
    assert abs(M.auc_w(y, s) - roc_auc_score(y, s)) < 1e-9
    w = rng.integers(0, 3, 500)
    rep = np.repeat(np.arange(500), w)
    assert abs(M.auc_w(y, s, w) - roc_auc_score(y[rep], s[rep])) < 1e-9


def test_bootstrap_p_small_for_signal_large_for_noise():
    rng = np.random.default_rng(2)
    days = np.repeat(np.arange(200), 20)
    y = rng.integers(0, 2, len(days))
    p_sig, _, _ = M.boot_p(y, y + rng.normal(0, 1.5, len(y)), days, n=300)
    p_noise, _, _ = M.boot_p(y, rng.normal(size=len(y)), days, n=300)
    assert p_sig < 0.01 and p_noise > 0.05


def test_bh():
    assert np.allclose(M.bh([0.01, 0.04, 0.03, 0.5]), [0.04, 0.16 / 3, 0.16 / 3, 0.5])


def test_trade_rule_one_per_side_per_day_and_twins():
    Z = pd.DataFrame(dict(und="NIFTY", day=pd.Timestamp("2024-01-02"), row=[15, 20, 25, 30],
                          net_ce_atm_x1=[10.0, 20, 30, 40], net_pe_atm_x1=[-5.0, -6, -7, -8]))
    T = M.trade_rule(Z, np.array([0.9, 0.95, 0.1, 0.05]), 0.8, 0.2)
    assert list(T.side) == ["ce", "pe"] and list(T.net) == [10.0, -7.0]
    tw = M.twins(Z, T, n=200)
    assert tw.min() >= 10 - 8 and tw.max() <= 40 - 5


def test_tree_rules_report_actual_rates():
    rng = np.random.default_rng(3)
    X = pd.DataFrame(dict(a=rng.normal(size=4000), b=rng.normal(size=4000)))
    y = (X.a > 0.5).astype(float).values
    r = M.tree_rules(X, y * 0.8 + 0.1, y, min_leaf=100)
    top = r[0]
    assert "a" in top["rule"] and top["actual"] in (0.0, 1.0)


def test_holdout_refused_without_flag():
    if D.HOLD_FLAG.exists():
        pytest.skip("holdout already read")
    with pytest.raises(PermissionError):
        D.load("holdout")
