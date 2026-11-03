"""R145 (crypto top-100 neural network): data, look-ahead guard, labels, splits, stats and the stop/lock simulator.

The model smoke tests need torch / lightgbm; CI's research job does not install them, so those two tests are skipped
there (importorskip) and run where the study runs. Every other test runs everywhere.
"""
import numpy as np
import pandas as pd
import pytest

from research.brain.crypto import config
from research.brain.crypto.r145 import data, guard, panel as pn, run, stats, trade


# --- synthetic data -----------------------------------------------------------------------------------------------------
def minutes(start="2024-01-01", n=60 * 24 * 3, seed=0, drift=0.0):
    rng = np.random.default_rng(seed)
    ts = pd.date_range(start, periods=n, freq="1min")
    lr = rng.normal(drift, 1e-3, n)
    c = 100 * np.exp(np.cumsum(lr))
    o = np.r_[100, c[:-1]]
    h = np.maximum(o, c) * (1 + rng.uniform(0, 5e-4, n))
    lo = np.minimum(o, c) * (1 - rng.uniform(0, 5e-4, n))
    v = rng.uniform(1, 10, n)
    return pd.DataFrame(dict(ts=ts, open=o, high=h, low=lo, close=c, volume=v, quote_volume=v * c,
                             count=rng.integers(1, 50, n), taker_buy_quote_volume=v * c * rng.uniform(0.3, 0.7, n)))


def synth_panel(days=20, coins=("BTCUSDT", "ETHUSDT", "AAAUSDT", "BBBUSDT"), seed=1, missing=True):
    bars = {}
    for i, s in enumerate(coins):
        m = minutes(n=60 * 24 * days, seed=seed + i)
        if missing and i == 2:
            m = m.drop(index=range(3000, 3100))                         # a gap: those bars must stay missing
        if i == 3:
            m = m.iloc[60 * 24 * 2:]                                     # listed two days later
        bars[s] = data.to_bars(m)
    fund = {s: pd.DataFrame({"ts": pd.date_range("2023-12-01", periods=3 * (days + 40), freq="8h"),
                             "interval_h": 8.0,
                             "rate": np.random.default_rng(seed + i).normal(1e-4, 1e-4, 3 * (days + 40))})
            for i, s in enumerate(coins)}
    return pn.make_panel(bars), fund


# --- 1-minute -> 5-minute bars ------------------------------------------------------------------------------------------
def test_to_bars_ohlc_rv_and_gaps():
    m = minutes(n=30)
    b = data.to_bars(m)
    assert len(b) == 6
    first = m.iloc[:5]
    assert b.open[0] == first.open.iloc[0] and b.close[0] == first.close.iloc[-1]
    assert b.high[0] == first.high.max() and b.low[0] == first.low.min()
    lr = np.diff(np.log(m.close.to_numpy()))
    assert np.isclose(b.rv[1], np.sum(lr[4:9] ** 2))                    # bar 2's 5 returns, incl. the cross-bar one
    assert b.n1.tolist() == [5.0] * 6
    gap = data.to_bars(m.drop(index=range(5, 10)))                      # a whole bar missing -> absent, never filled
    assert len(gap) == 5 and pd.Timestamp("2024-01-01 00:05") not in set(gap.ts)
    assert np.isclose(gap.rv[1], np.sum(lr[10:14] ** 2))                # the return across the gap is not counted


def test_leadlag_finds_a_one_minute_lead():
    m = minutes(n=5000, seed=3)
    br = data.btc_returns(m)
    alt = m.copy()
    alt["close"] = alt.close.shift(1).bfill()                           # the alt copies BTC one minute later
    ll = data.leadlag(alt, br).set_index("lag")["corr"]
    assert ll[1] > 0.95 and abs(ll[0]) < 0.1 and abs(ll[-1]) < 0.1


def test_design_months_never_include_the_holdout():
    ms = data.design_months("2026-01", "2026-10")
    assert ms[-1] == "2026-03" and all(pd.Period(m, "M").start_time < config.HOLDOUT_START for m in ms)
    assert data.design_months("2026-01", "2026-10", allow_holdout=True)[-1] == "2026-10"


def test_manifest_splits_renamed_coins(tmp_path):
    f = tmp_path / "m.csv"
    pd.DataFrame({"perp_symbol": ["BTCUSDT", None, "TONUSDT+GRAMUSDT"]}).to_csv(f, index=False)
    assert data.manifest_symbols(f) == ["BTCUSDT", "TONUSDT", "GRAMUSDT"]


# --- features: point in time --------------------------------------------------------------------------------------------
def test_lookahead_guard_passes_on_synthetic_panel():
    P, fund = synth_panel()
    grid = P["close"].index
    dec = grid[pn.decision_bars(grid)] + pd.Timedelta(minutes=5)
    cuts = [dec[-1] - pd.Timedelta(hours=1), dec[len(dec) // 2], dec[-30] + pd.Timedelta(minutes=2), dec[-200]]
    assert guard.check(P, fund, cuts) == []


def test_guard_catches_a_leaky_feature(monkeypatch):
    P, fund = synth_panel(days=16)
    real = pn.features

    def leaky(P_, fund_=None, dec=None, first_seen=None):
        F = real(P_, fund_, dec, first_seen)
        nxt = P_["close"].shift(-12).iloc[pn.decision_bars(P_["close"].index)]       # one hour ahead: a leak
        nxt.index = nxt.index + pd.Timedelta(minutes=5)
        s = nxt.stack(future_stack=True)
        F["leak"] = s.reindex(pd.MultiIndex.from_arrays([F.ts, F.sym])).to_numpy()
        return F

    monkeypatch.setattr(pn, "features", leaky)
    grid = P["close"].index
    dec = grid[pn.decision_bars(grid)] + pd.Timedelta(minutes=5)
    bad = guard.check(P, fund, [dec[-40]])
    assert any(b[0] == "leak" for b in bad)


def test_features_rows_respect_listing_warmup_and_gaps():
    P, fund = synth_panel(days=20)
    F = pn.features(P, fund)
    first_b = P["n1"]["BBBUSDT"].first_valid_index()
    assert F[F.sym == "BBBUSDT"].ts.min() >= first_b + pd.Timedelta(days=14)
    gap_bar_open = P["n1"].index[P["n1"]["AAAUSDT"].isna()]
    assert len(gap_bar_open) > 0
    dec_ts = set(F[F.sym == "AAAUSDT"].ts - pd.Timedelta(minutes=5))
    assert not dec_ts & set(gap_bar_open)                               # no row on a missing decision bar
    assert F.ts.min() >= P["close"].index[0] + pd.Timedelta(days=14)
    assert (F.ts.dt.minute == 0).all()
    assert F.fund_rate.notna().mean() > 0.9


def test_funding_uses_only_settled_rates():
    P, fund = synth_panel(days=16)
    F = pn.features(P, fund)
    row = F[F.sym == "BTCUSDT"].iloc[-1]
    f = fund["BTCUSDT"]
    last = f[f.ts <= row.ts].iloc[-1]
    assert np.isclose(row.fund_rate, last.rate * 1e4)
    assert 0 <= row.fund_next_min <= 480


# --- labels -------------------------------------------------------------------------------------------------------------
def test_labels_read_only_the_future_and_the_entry_close():
    P, fund = synth_panel(days=16, missing=False)
    L = pn.labels(P)
    t = L.ts.iloc[len(L) // 2]
    k_open = t - pd.Timedelta(minutes=5)                                # the decision bar's open time
    P2 = {f: d.copy() for f, d in P.items()}
    fut = P2["close"].index > k_open
    rng = np.random.default_rng(0)
    for f in ("open", "high", "low", "close", "rv"):
        P2[f].loc[fut] = P2[f].loc[fut].values * rng.uniform(0.9, 1.1, P2[f].loc[fut].shape)
    L2 = pn.labels(P2)
    a, b = L[L.ts == t].reset_index(drop=True), L2[L2.ts == t].reset_index(drop=True)
    assert not np.allclose(a.y_ret_60, b.y_ret_60)                      # labels read the future
    assert not np.allclose(a.y_vol_60, b.y_vol_60)
    P3 = {f: d.copy() for f, d in P.items()}
    past = P3["close"].index < k_open                                   # strictly before the decision bar
    for f in ("open", "high", "low", "close", "rv"):
        P3[f].loc[past] = P3[f].loc[past].values * rng.uniform(0.9, 1.1, P3[f].loc[past].shape)
    L3 = pn.labels(P3)
    c = L3[L3.ts == t].reset_index(drop=True)
    for col in ("y_ret_15", "y_ret_60", "y_ret_240", "y_dir_60", "y_vol_60", "y_vol_240", "y_xs_60"):
        assert np.allclose(a[col], c[col], equal_nan=True), col
    # size and big move are scaled by the trailing volatility known at t (a past value), by design


def test_label_values():
    P, _ = synth_panel(days=16, missing=False)
    L = pn.labels(P).dropna(subset=["y_ret_60"])
    r = L.iloc[100]
    k = P["close"].index.get_loc(r.ts - pd.Timedelta(minutes=5))
    c = P["close"][r.sym]
    assert np.isclose(r.y_ret_60, np.log(c.iloc[k + 12] / c.iloc[k]))
    assert np.isclose(r.y_vol_60, 0.5 * np.log(P["rv"][r.sym].iloc[k + 1:k + 13].sum()))
    assert r.y_dir_60 == float(r.y_ret_60 > 0)


# --- walk-forward split -------------------------------------------------------------------------------------------------
def test_split_purges_and_embargoes():
    ts = pd.date_range("2024-01-08", "2026-03-31 20:00", freq="h")
    D = pd.DataFrame({"ts": ts})
    for f in range(len(run.FOLDS)):
        fit, va, tr, te = run.split(D, f)
        lo = pd.Timestamp(run.FOLDS[f][0])
        assert (D.ts.iloc[tr] + run.MAX_H + run.EMBARGO <= lo).all()
        assert D.ts.iloc[fit].max() < D.ts.iloc[va].min()
        assert set(fit) | set(va) == set(tr) and not set(fit) & set(va)
        assert (D.ts.iloc[te] >= lo).all() and (D.ts.iloc[te] < pd.Timestamp(run.FOLDS[f][1])).all()
    assert pd.Timestamp(run.FOLDS[-1][1]) <= config.HOLDOUT_START


def test_seq_gather_reads_only_past_bars():
    from research.brain.crypto.r145.models import SeqGather
    C = np.arange(100 * 3 * 2, dtype=np.float32).reshape(100, 3, 2)
    g = SeqGather(C, np.array([60]), np.array([1]), L=48)
    s = g(np.array([0]))
    assert s.shape == (1, 48, 2)
    assert (s[0, -1] == C[60, 1]).all() and (s[0, 0] == C[13, 1]).all()


# --- statistics ---------------------------------------------------------------------------------------------------------
def test_bh_matches_definition():
    p = np.array([0.01, 0.04, 0.03, 0.5, np.nan])
    q = stats.bh(p)
    # sorted 0.01, 0.03, 0.04, 0.5 -> raw 0.04, 0.06, 0.0533, 0.5 -> monotone 0.04, 0.0533, 0.0533, 0.5
    assert np.isclose(q[0], 0.04) and np.isclose(q[2], 0.16 / 3) and np.isclose(q[1], 0.16 / 3)
    assert np.isclose(q[3], 0.5) and np.isnan(q[4])


def test_auc_and_ic():
    y = np.array([0, 0, 1, 1.0])
    assert stats.auc(np.array([0.1, 0.2, 0.8, 0.9]), y) == 1.0
    assert stats.auc(np.array([0.9, 0.8, 0.2, 0.1]), y) == 0.0
    ts = np.repeat(pd.date_range("2025-01-01", periods=3, freq="h").values, 12)
    yv = np.tile(np.arange(12.0), 3)
    ic = stats.ic_by_time(ts, yv * 2, yv)
    assert np.allclose(ic.values, 1.0)


def test_compare_finds_skill_and_no_skill():
    rng = np.random.default_rng(0)
    n = 24 * 7 * 30
    ts = pd.date_range("2025-01-01", periods=n, freq="h")
    sig = rng.normal(size=n)
    y = (sig + rng.normal(size=n) * 2 > 0).astype(float)
    df = pd.DataFrame({"ts": ts, "fold": np.repeat(np.arange(6), n // 6 + 1)[:n], "y_dir_60": y,
                       "N1|y_dir_60": 1 / (1 + np.exp(-sig)), "B0|y_dir_60": np.full(n, y.mean()),
                       "TW|y_dir_60": 1 / (1 + np.exp(-rng.normal(size=n) * 0.1))})
    r = stats.compare(df, "y_dir_60", "N1", "B0")
    assert r["p"] < 1e-3 and r["model_auc"] > 0.6 and r["folds_pos"] == 6
    r2 = stats.compare(df, "y_dir_60", "TW", "B0")
    assert r2["p"] > 0.01 and abs(r2["model_auc"] - 0.5) < 0.03


def test_bootstrap_auc_p():
    rng = np.random.default_rng(1)
    y = rng.integers(0, 2, 2000).astype(float)
    wk = np.repeat(np.arange(40), 50)
    a, p = stats.bootstrap_auc_p(y + rng.normal(0, 1, 2000), y, wk, n_boot=200)
    assert a > 0.6 and p < 0.01
    a2, p2 = stats.bootstrap_auc_p(rng.normal(size=2000), y, wk, n_boot=200)
    assert p2 > 0.05


# --- stop / lock simulator ----------------------------------------------------------------------------------------------
def _path(closes, highs=None, lows=None):
    c = np.asarray(closes, float)[:, None]
    o = np.r_[c[:1], c[:-1]]                                            # each bar opens at the previous close
    h = np.maximum(o, c) if highs is None else np.asarray(highs, float)[:, None]
    lo = np.minimum(o, c) if lows is None else np.asarray(lows, float)[:, None]
    return o, h, lo, c


def test_stop_hits_first_and_gap_exits_at_open():
    O, H, L, C = _path([100, 100.5, 99.0, 98.0], lows=[100, 100.4, 98.9, 97])
    g, off, why = trade.simulate(O, H, L, C, np.array([0]), np.array([0]), np.array([1]), np.array([0.01]), 3,
                                 1.0, "trail", be=np.array([0.001]))
    assert why[0] == 1 and off[0] == 2 and np.isclose(g[0], -0.01)
    O2 = np.array([[100], [95], [95], [95]], float)                     # opens 5% below: gap through the stop
    g2, _, why2 = trade.simulate(O2, O2, O2, O2, np.array([0]), np.array([0]), np.array([1]), np.array([0.01]), 3,
                                 1.0, "step", be=np.array([0.001]))
    assert why2[0] == 2 and np.isclose(g2[0], np.log(0.95))


def test_trail_lock_gives_back_half():
    # up 3% (peak), then falls: exit when the gain is below half the peak
    c = [100, 101, 103, 102, 101.2, 100.5]
    O, H, L, C = _path(c)
    g, off, why = trade.simulate(O, H, L, C, np.array([0]), np.array([0]), np.array([1]), np.array([0.01]), 5,
                                 2.0, "trail", be=np.array([0.001]))
    assert why[0] in (1, 3) and g[0] > 0 and g[0] <= np.log(1.03)
    assert np.isclose(g[0], 0.5 * np.log(1.03)) or g[0] < 0.5 * np.log(1.03)


def test_step_lock_moves_stop_to_breakeven_then_plus_one():
    s = 0.01
    O, H, L, C = _path([100, 101.2, 100.0, 99.0])                       # +1.2% then back below entry
    g, _, why = trade.simulate(O, H, L, C, np.array([0]), np.array([0]), np.array([1]), np.array([s]), 3, 2.0,
                               "step", be=np.array([0.0012]))
    assert why[0] == 1 and np.isclose(g[0], 0.0012)
    O, H, L, C = _path([100, 102.5, 101.5, 100.0])                      # +2.5%: the stop moves to +1 s
    g, _, why = trade.simulate(O, H, L, C, np.array([0]), np.array([0]), np.array([1]), np.array([s]), 3, 2.0,
                               "step", be=np.array([0.0012]))
    assert np.isclose(g[0], s)


def test_short_side_and_time_exit():
    O, H, L, C = _path([100, 99.8, 99.6, 99.7])
    g, off, why = trade.simulate(O, H, L, C, np.array([0]), np.array([0]), np.array([-1]), np.array([0.01]), 3,
                                 1.0, "trail", be=np.array([0.001]))
    assert why[0] == 0 and off[0] == 3 and np.isclose(g[0], -np.log(99.7 / 100))


def test_costs_and_window():
    assert np.isclose(trade.cost_frac("BTCUSDT"), 2 * (0.0005 + 1e-4))
    assert np.isclose(trade.cost_frac("SUIUSDT", 2.0), 2 * (0.0005 + 10e-4))
    ts = pd.DatetimeIndex(["2025-01-01 18:30", "2025-01-01 19:00", "2025-01-02 02:00", "2025-01-02 03:00",
                           "2025-01-02 12:00"])
    assert trade.in_window(ts, 60).tolist() == [True, True, True, False, False]


def test_trades_charge_funding_per_settlement():
    O, H, L, C = _path([100.0] * 20)
    rows = pd.DataFrame({"ts": pd.DatetimeIndex(["2025-01-01 07:00"]), "sym": ["BTCUSDT"], "bar": [0], "col": [0],
                         "side": [1], "sig_bar": [0.002], "fund_rate": [1.0]})
    t = trade.trades(rows, O, H, L, C, 60, 2.0, "trail")
    assert np.isclose(t.net.iloc[0], -trade.cost_frac("BTCUSDT") - 1e-4)       # one settlement (08:00) held, long pays
    rows.loc[0, "ts"] = pd.Timestamp("2025-01-01 09:00")
    t = trade.trades(rows, O, H, L, C, 60, 2.0, "trail")
    assert np.isclose(t.net.iloc[0], -trade.cost_frac("BTCUSDT"))


def test_no_overlap_one_position_per_coin():
    sel = pd.DataFrame({"ts": pd.DatetimeIndex(["2025-01-01 00:00", "2025-01-01 01:00", "2025-01-01 04:00"]),
                        "sym": ["A", "A", "A"]})
    assert len(trade.no_overlap(sel, 240)) == 2 and len(trade.no_overlap(sel, 60)) == 3


# --- models (need torch / lightgbm) -------------------------------------------------------------------------------------
def test_mlp_learns_a_planted_signal():
    pytest.importorskip("torch")
    from research.brain.crypto.r145 import models as M
    rng = np.random.default_rng(0)
    n, k = 20000, 8
    X = rng.normal(size=(n, k)).astype(np.float32)
    Y = np.full((n, len(M.TARGETS)), np.nan)
    i_dir = M.TARGETS.index("y_dir_60")
    i_vol = M.TARGETS.index("y_vol_60")
    Y[:, i_dir] = (X[:, 0] + rng.normal(size=n) > 0)
    Y[:, i_vol] = X[:, 1] * 2 + rng.normal(size=n) * 0.1
    sc = M.TargetScale().fit(Y)
    Ys = sc.fwd(Y)
    net, ep, hist = M.fit_net(X[:16000], Ys[:16000], X[16000:], Ys[16000:], epochs=3, log=lambda *a: None)
    pr = sc.inv(M.predict_net(net, X[16000:]))
    assert stats.auc(pr[:, i_dir], Y[16000:, i_dir]) > 0.7
    assert np.corrcoef(pr[:, i_vol], Y[16000:, i_vol])[0, 1] > 0.9
    assert hist[-1] <= hist[0]


def test_baselines_run():
    pytest.importorskip("lightgbm")
    pytest.importorskip("sklearn")
    from research.brain.crypto.r145 import models as M
    rng = np.random.default_rng(0)
    n = 6000
    df = pd.DataFrame({c: rng.normal(size=n) for c in M.HAR + ["f"]})
    for t in M.TARGETS:
        df[t] = (rng.normal(size=n) > 0).astype(float) if M.is_binary(t) else rng.normal(size=n)
    nv = M.naive(df.iloc[:5000], df.iloc[5000:])
    assert set(nv) == set(M.TARGETS) and np.all(nv["y_xs_60"] == 0)
    Z = df[M.HAR + ["f"]].to_numpy(np.float32)
    lin = M.linear(Z[:5000], df.iloc[:5000], Z[5000:])
    assert lin["y_dir_60"].min() >= 0 and lin["y_dir_60"].max() <= 1
    tr, info = M.trees(Z[:4000], df.iloc[:4000], Z[4000:5000], df.iloc[4000:5000], Z[5000:])
    assert len(tr["y_vol_240"]) == 1000 and info["y_vol_240"]["rounds"] >= 0
