"""R30 tests on synthetic data (no lake needed): python -m pytest research/hunt/r30/test_r30.py -q

Look-ahead guard: a model's inputs at time t use only data <= t (sequence, crypto features); scaling / imputation /
label scales are fitted on training rows only; the holdout lock; trade simulators (option buys, crypto stop + lock);
the metrics. The network tests need torch and are skipped without it.
"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r30cfg as K  # noqa: E402
import evalx as E  # noqa: E402
import nets as NN  # noqa: E402
import india as I  # noqa: E402
import crypto as C  # noqa: E402
import run as R  # noqa: E402


# ------------------------------------------------------------------------------------------------ periods
def test_india_periods_match_f1_and_never_touch_the_brain_holdout():
    assert K.part(date(2023, 1, 2)) == "holdout" and K.part(date(2024, 6, 28)) == "holdout"
    assert K.part(date(2022, 12, 20)) == "embargo" and K.part(date(2024, 7, 10)) == "embargo"
    assert K.part(date(2022, 12, 14)) == "design" and K.part(date(2024, 7, 15)) == "design"
    assert K.part(date(2026, 4, 14)) == "unused" and K.part(date(2026, 5, 1)) == "unused"
    assert K.part(date(2021, 7, 30)) == "unused"
    for lo, hi in K.FOLDS.values():                       # every fold is design
        assert K.part(lo) == "design" and K.part(hi) == "design"


def test_crypto_window_must_fit_inside_one_part():
    t = pd.Timestamp("2025-03-31 23:00")
    assert K.c_part(t, 15) == "design"
    assert K.c_part(t, 240) == "unused"                   # runs into the holdout: neither
    assert K.c_part(pd.Timestamp("2025-06-01"), 240) == "holdout"
    assert K.c_part(pd.Timestamp("2025-10-15"), 15) == "unused"     # embargo
    assert K.c_part(pd.Timestamp("2026-04-02"), 15) == "unused"     # the brain's lock, never used


# ------------------------------------------------------------------------------------------------ look-ahead
def _minutes_stub(n=400, seed=0):
    m = I.Minutes.__new__(I.Minutes)
    m.ch = np.random.default_rng(seed).normal(size=(n, 5)).astype(np.float32)
    return m


def test_sequence_at_t_uses_only_rows_up_to_t():
    m = _minutes_stub()
    p = 200
    a = m.seq_at(p).copy()
    assert a.shape == (K.SEQ_N, 5)
    np.testing.assert_array_equal(a[-1], m.ch[p])            # the last row IS row t
    m.ch[p + 1:] = 999.0                                     # change the future
    np.testing.assert_array_equal(m.seq_at(p), a)
    m.ch[p] = 5.0                                            # change row t: the sequence changes
    assert not np.array_equal(m.seq_at(p), a)


def test_sequence_before_the_first_row_is_zero_padded_with_mask_zero():
    m = _minutes_stub()
    s = m.seq_at(3)
    assert (s[:-4] == 0).all() and np.array_equal(s[-4:], m.ch[:4])


def _bars(n=4000, seed=1):
    rng = np.random.default_rng(seed)
    c = 30000 * np.exp(np.cumsum(rng.normal(0, 0.001, n)))
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) * (1 + rng.uniform(0, 0.0005, n))
    lo = np.minimum(o, c) * (1 - rng.uniform(0, 0.0005, n))
    v = rng.uniform(5, 50, n)
    idx = pd.date_range("2024-01-01", periods=n, freq="1min")
    return pd.DataFrame(dict(open=o, high=h, low=lo, close=c, volume=v, count=rng.integers(100, 900, n).astype(float),
                             taker_buy_volume=v * rng.uniform(0.3, 0.7, n)), index=idx)


def test_crypto_features_at_t_ignore_every_later_bar():
    B = _bars()
    t = 3000
    F1, S1, s1 = C.features(B)
    B2 = B.copy()
    B2.iloc[t + 1:] = B2.iloc[t + 1:] * 1.5                  # a big shock after t
    F2, S2, s2 = C.features(B2)
    pd.testing.assert_series_equal(F1.iloc[t], F2.iloc[t])
    np.testing.assert_array_equal(S1[:t + 1], S2[:t + 1])
    assert s1.iloc[t] == s2.iloc[t]
    assert not F1.iloc[t + 5].equals(F2.iloc[t + 5])          # the shock does show up later


def test_crypto_labels_read_only_rows_after_t():
    B = _bars()
    t = 2000
    r1, g1 = C.labels(B, 15)
    B2 = B.copy()
    B2.iloc[:t] = B2.iloc[:t] * 0.5                           # rows before t do not move the label at t
    r2, g2 = C.labels(B2, 15)
    assert r1[t] == r2[t] and g1[t] == g2[t]
    assert np.isclose(r1[t], np.log(B.close.iloc[t + 15] / B.close.iloc[t]) * 100)


def test_input_columns_never_include_labels_or_outcomes():
    D = pd.DataFrame({"day": [1], "row": [2], "und": ["NIFTY"], "f_a": [1.0], "y_ret_15": [0.1], "tr_15_C_net": [5.0],
                      "ctx_iv": [0.2], "precision": ["none"]})
    assert I.input_cols(D) == ["f_a"]


# ------------------------------------------------------------------------------------------------ training-only fits
def test_tab_scaler_and_imputer_are_fitted_on_training_rows_only():
    rng = np.random.default_rng(0)
    X = rng.normal(size=(200, 3))
    X[::7, 1] = np.nan
    tr = np.arange(150)
    t1 = NN.Tab().fit(X[tr])
    X2 = X.copy()
    X2[150:] = 1e6                                           # test rows change wildly
    t2 = NN.Tab().fit(X2[tr])
    np.testing.assert_array_equal(t1.mu, t2.mu)
    np.testing.assert_array_equal(t1.sd, t2.sd)
    np.testing.assert_array_equal(t1.med, t2.med)
    assert np.abs(t1.transform(X2[150:])).max() <= K.CLIP     # test rows are clipped, not refitted


def test_tab_drops_columns_mostly_nan_in_training():
    X = np.ones((10, 2))
    X[:9, 1] = np.nan
    assert list(NN.Tab().fit(X).keep) == [0]


def test_val_mask_takes_the_last_sessions_with_a_gap():
    days = np.repeat(np.arange(10), 3)
    val, keep = NN.val_mask(days, share=0.2, gap=1)
    assert set(days[val]) == {8, 9}
    assert set(days[~keep]) == {7}


def test_net_scalers_and_label_scale_ignore_validation_and_test_rows():
    pytest.importorskip("torch")
    rng = np.random.default_rng(0)
    n = 300
    X = rng.normal(size=(n, 4))
    S = rng.normal(size=(n, 10, 3)).astype(np.float32)
    S[..., -1] = 1.0
    Y = np.column_stack([(rng.random(n) > 0.5).astype(float), rng.normal(size=n)])
    val = np.zeros(n, bool)
    val[240:] = True
    a = NN.Net("gru", ["bin", "reg"], seeds=(0,))
    old = (K.EPOCHS, K.PATIENCE)
    try:
        K.EPOCHS, K.PATIENCE = 2, 1
        a.fit(X, S, Y, val)
        X2, S2, Y2 = X.copy(), S.copy(), Y.copy()
        X2[240:] += 100
        S2[240:, :, :2] += 100
        Y2[240:, 1] += 100
        b = NN.Net("gru", ["bin", "reg"], seeds=(0,)).fit(X2, S2, Y2, val)
    finally:
        K.EPOCHS, K.PATIENCE = old
    np.testing.assert_allclose(a.tab.mu, b.tab.mu)
    np.testing.assert_allclose(a.seq.mu, b.seq.mu)
    np.testing.assert_allclose(a.ymu, b.ymu)
    np.testing.assert_allclose(a.ysd, b.ysd)
    p = a.predict(X[:5], S[:5])
    assert p.shape == (5, 2) and ((p[:, 0] > 0) & (p[:, 0] < 1)).all()


@pytest.mark.parametrize("kind", ["mlp", "tcn", "gru"])
def test_each_net_trains_and_is_small(kind):
    pytest.importorskip("torch")
    m = NN.build(kind, 20, 5, 6)
    assert NN.n_params(m) < 25_000


def test_tcn_is_causal():
    torch = pytest.importorskip("torch")
    torch.manual_seed(0)
    m = NN.build("tcn", 3, 5, 1).eval()
    xt = torch.zeros(1, 3)
    xs = torch.randn(1, 60, 5)
    out = m(xt, xs)
    xs2 = xs.clone()
    xs2[:, :59] += 3.0                                       # the TCN reads the LAST step's features only through
    assert not torch.allclose(m(xt, xs2), out)               # its causal receptive field (rows <= t)
    conv = m.c[0]
    y1 = conv(xs.transpose(1, 2))
    xs3 = xs.clone()
    xs3[:, 40:] += 5.0                                       # a later change never reaches an earlier output
    y2 = conv(xs3.transpose(1, 2))
    assert torch.allclose(y1[..., :40], y2[..., :40])


def test_folds_train_strictly_before_the_test_block():
    days = [d.date() for d in pd.bdate_range("2021-08-02", "2026-04-13") if K.part(d.date()) == "design"]
    D = pd.DataFrame({"day": np.repeat(days, 2), "und": "NIFTY"})
    for q in ("q1", "q4"):
        for f, (tr, te) in R.folds(q, D).items():
            assert D.day.values[tr].max() < D.day.values[te].min()
    tr4, te4 = R.folds("q4", D)["2025"]
    tr1, _ = R.folds("q1", D)["2025"]
    assert len(set(D.day.values[tr1])) - len(set(D.day.values[tr4])) == 1   # Q4 drops the last training session


# ------------------------------------------------------------------------------------------------ holdout lock
def test_holdout_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "OUT", tmp_path)
    with pytest.raises(I.HoldoutError):
        I.load("NIFTY", "holdout")
    with pytest.raises(I.HoldoutError):
        I.build("NIFTY", "holdout")
    I.holdout_gate("india", "start")
    assert I.holdout_open("india")
    I.holdout_gate("india", "done")
    assert not I.holdout_open("india")
    with pytest.raises(I.HoldoutError):
        I.holdout_gate("india", "start")


# ------------------------------------------------------------------------------------------------ trades
def _opt(px, vol=5000.0):
    a = np.full((I.N, 7), np.nan)
    a[:, I.O_] = np.r_[px[0], px[:-1]]
    a[:, I.C_] = px
    a[:, I.H_] = np.maximum(a[:, I.O_], px) + 0.5
    a[:, I.L_] = np.minimum(a[:, I.O_], px) - 0.5
    a[:, I.V_] = vol
    return a


def test_option_buy_time_exit_pays_costs():
    a = _opt(np.full(I.N, 100.0))
    t = I.sim_buy(a, 100, 115, 75, "NIFTY")
    assert t["why"] == "time" and t["exit_row"] == 115 and t["net"] < 0   # flat price: costs only
    assert t["buy"] > 100 and t["sell"] < 100


def test_option_buy_premium_stop_and_target():
    px = np.full(I.N, 100.0)
    px[110:] = 60.0
    t = I.sim_buy(_opt(px), 100, 160, 75, "NIFTY", stop=0.3)
    assert t["why"] == "stop" and t["exit_row"] == 110 and t["net"] < -40 * 75
    px = np.full(I.N, 20.0)
    px[130:] = 90.0
    t = I.sim_buy(_opt(px), 100, 200, 75, "NIFTY", target=80.0)
    assert t["why"] == "target" and t["sell"] <= 80.0


def test_option_buy_without_an_entry_bar_is_no_trade():
    a = _opt(np.full(I.N, 100.0))
    a[101] = np.nan
    assert I.sim_buy(a, 100, 115, 75, "NIFTY") is None


def test_option_buy_next_morning_exit():
    a = _opt(np.full(I.N, 100.0))
    b = _opt(np.full(I.N, 130.0))
    t = I.sim_buy(a, K.Q4_ROW, I.N - 1, 75, "NIFTY", nxt=b, exit_next_open=1)
    assert t["why"] == "next_open" and t["net"] > 0


def test_atm_is_half_up():
    assert I.atm(24025.0, 50) == 24050.0 and I.atm(24024.9, 50) == 24000.0


def test_crypto_every_trade_has_a_stop_and_losses_are_bounded():
    B = _bars(3000, seed=3)
    rows = np.arange(100, 2500, 15)
    sig = np.full(len(rows), 0.3)                            # 0.3% stop distance
    for side in (1, -1):
        out = C.sim_perp(B, rows, side, 60, sig)
        ok = np.isfinite(out["net"])
        loss_frac = -out["net"][ok] / (B.open.values[rows + 1][ok] * K.C_QTY["BTCUSDT"] * K.INR_PER_USD)
        # worst loss: the stop (0.3%) plus a gap through it, plus costs; never an open-ended loss
        assert loss_frac.max() < 0.003 + 0.004 + 2 * (K.C_FEE * (1 + K.C_GST) + K.C_SLIP)


def test_crypto_profit_lock_keeps_break_even():
    n = 400
    idx = pd.date_range("2025-01-01", periods=n, freq="1min")
    c = np.full(n, 100.0)
    c[102:110] = 101.0                                       # +1%: the lock arms (sigma 0.5%)
    c[110:] = 98.0                                           # then a crash through the entry
    o = np.r_[c[0], c[:-1]]
    B = pd.DataFrame(dict(open=o, high=np.maximum(o, c), low=np.minimum(o, c), close=c), index=idx)
    out = C.sim_perp(B, np.array([100]), 1, 60, np.array([0.5]))
    be = 2 * (K.C_FEE * (1 + K.C_GST) + K.C_SLIP)
    # exit at the trailing stop (best 101 - 0.5% ~ 100.5) or break-even; never at the 98 crash
    assert out["net"][0] >= -1e-9 - 98 * be and out["exit_k"][0] < 60


# ------------------------------------------------------------------------------------------------ metrics
def test_bh_and_auc():
    q = E.bh([0.01, 0.04, 0.03, 0.5, np.nan])
    np.testing.assert_allclose(q[:4], [0.04, 0.16 / 3, 0.16 / 3, 0.5])
    assert np.isnan(q[4])
    y = np.array([0, 0, 1, 1])
    assert E.auc(y, np.array([0.1, 0.2, 0.8, 0.9])) == 1.0
    assert E.auc(y, np.array([0.5, 0.5, 0.5, 0.5])) == 0.5


def test_boot_diff_prefers_the_better_model():
    rng = np.random.default_rng(0)
    y = (rng.random(2000) > 0.5).astype(float)
    good = np.clip(0.5 + (y - 0.5) * 0.3, 0.01, 0.99)
    days = np.repeat(np.arange(200), 10)
    d = E.boot_diff("bin", y, good, np.full(2000, 0.5), days)
    assert d["diff"] < 0 and d["hi"] < 0 and d["p"] < 0.01


def test_trade_stats_counts_no_trade_days_as_zero():
    st = E.trade_stats([100.0, -50.0], [1, 2], [1, 2, 3, 4])
    assert st["pnl_per_day"] == pytest.approx(12.5) and st["worst_day"] == -50.0 and st["max_drawdown"] == -50.0


def test_side_twins_are_centred_on_the_two_sides():
    tw = E.twins_side(np.full(100, 10.0), np.full(100, -10.0), n=2000)
    assert abs(tw.mean()) < 20


# ------------------------------------------------------------------------------------------------ lake options
def _lake_day(d, spot, atm_tv):
    rows = []
    for m in range(I.N):
        ts = pd.Timestamp(d) + pd.Timedelta(minutes=9 * 60 + 15 + m)
        for off in (-1, 0, 1):
            k = 1000.0 + off * 50
            for s in ("CE", "PE"):
                intr = max(spot - k, 0) if s == "CE" else max(k - spot, 0)
                rows.append(dict(ts=ts, offset=off, side=s, strike=k, open=intr + atm_tv / 2, high=intr + atm_tv / 2,
                                 low=intr + atm_tv / 2, close=intr + atm_tv / 2, volume=100.0, oi=1000.0, iv=0.2,
                                 spot=spot))
    return rows


def test_lake_options_find_expiry_days_from_the_time_value(tmp_path, monkeypatch):
    rows = _lake_day("2024-01-03", 1000.0, 20.0) + _lake_day("2024-01-04", 1000.0, 2.0) + \
        _lake_day("2024-01-05", 1000.0, 25.0)                  # 4 Jan: tiny time value, then it jumps: an expiry
    pd.DataFrame(rows).to_parquet(tmp_path / "opt_NIFTY_WEEK_2024.parquet")
    monkeypatch.setattr(K, "LAKE", tmp_path)
    LO = I.LakeOptions("NIFTY")
    assert LO.expiry_days == {date(2024, 1, 4)}
    a = LO.day(date(2024, 1, 3))
    assert set(a) == {(s, k) for s in "CP" for k in (950.0, 1000.0, 1050.0)}
    assert a[("C", 1000.0)].shape == (I.N, 7) and a[("C", 1000.0)][100, I.C_] == 10.0
    assert LO.day(date(2024, 1, 8)) == {}
