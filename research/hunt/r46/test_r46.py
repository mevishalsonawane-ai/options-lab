"""R46 tests: indicators vs hand values, crossover edges, the next-bar and one-minute-later fills, stop / lock / trail on
gaps, the time exit, max 2 a day across IST midnight, empty and gappy data, the expiry rule, fees, the walk-forward MLP
(never trained on its test month), the look-ahead guard and the holdout lock.

    python -m pytest research/hunt/r46 -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r46 import book as B
from research.hunt.r46 import data as D
from research.hunt.r46 import ind
from research.hunt.r46 import nn
from research.hunt.r46 import pool as PL
from research.hunt.r46 import r46cfg as K
from research.hunt.r46.exits import DATA_END, LOCK, STOP, TIME, Exit, walk


# --- indicators ---------------------------------------------------------------------------------------------------------
def test_ema_hand_values():
    # n = 3 -> alpha 0.5: 1, 1.5, 2.25, 3.125
    assert np.allclose(ind.ema([1, 2, 3, 4], 3), [1, 1.5, 2.25, 3.125])
    assert np.allclose(ind.ema(np.arange(50.0), 8), pd.Series(np.arange(50.0)).ewm(span=8, adjust=False).mean())
    assert len(ind.ema([], 8)) == 0


def test_adx_hand_values_n2():
    # n = 2, hand-worked:
    # bar1: up=1, dn=-1 -> +DM 1, -DM 0, TR max(2, |11-9|=2, |9-9|=0)=2 ... see the table below
    h = np.array([10.0, 11.0, 12.0, 11.0, 13.0])
    l = np.array([8.0, 9.0, 10.0, 9.0, 10.0])
    c = np.array([9.0, 10.0, 11.0, 10.0, 12.0])
    # TR: [nan, 2, 2, 2, 3]; +DM: [nan, 1, 1, 0, 2]; -DM: [nan, 0, 0, 1, 0]
    # i=2: S_tr=4, S_p=2, S_m=0 -> +DI 50, -DI 0, DX 100
    # i=3: S_tr=4-2+2=4, S_p=2-1+0=1, S_m=0-0+1=1 -> +DI 25, -DI 25, DX 0 ; ADX[3] = mean(DX[2..3]) = 50
    # i=4: S_tr=4-2+3=5, S_p=1-0.5+2=2.5, S_m=1-0.5+0=0.5 -> +DI 50, -DI 10, DX 100*40/60=66.67
    #      ADX[4] = (50*1 + 66.67)/2 = 58.33
    adx, pdi, mdi = ind.wilder_adx(h, l, c, 2)
    assert np.isnan(adx[:3]).all()
    assert pdi[2] == pytest.approx(50) and mdi[2] == pytest.approx(0)
    assert pdi[3] == pytest.approx(25) and mdi[3] == pytest.approx(25)
    assert adx[3] == pytest.approx(50)
    assert pdi[4] == pytest.approx(50) and mdi[4] == pytest.approx(10)
    assert adx[4] == pytest.approx((50 + 200 / 3) / 2)


def test_adx_short_and_flat():
    a, p, m = ind.wilder_adx([1.0, 2.0], [0.5, 1.0], [1.0, 1.5], 14)
    assert np.isnan(a).all()
    n = 40
    a, p, m = ind.wilder_adx(np.ones(n), np.ones(n), np.ones(n), 14)       # no range at all: zeros, not NaN/crash
    assert np.nan_to_num(a[27:]).max() == 0


def test_crossover_edges():
    a = np.array([1, 2, 3, 3, 2, 4.0])
    b = np.array([2, 2, 2, 3, 3, 3.0])
    # i=2: 3>2 and prev 2<=2 -> cross (touching counts as "at or below")
    # i=3: 3>3 false; i=5: 4>3 and prev 2<=3 -> cross
    assert ind.cross_up(a, b).tolist() == [False, False, True, False, False, True]
    assert not ind.cross_up([5.0], [1.0]).any()                 # bar 0 can never cross
    assert ind.cross_up([np.nan, 2.0], [1.0, 1.0]).tolist() == [False, False]


def _m1(n, start="2024-01-01 00:00", seed=0, drift=0.0):
    rng = np.random.default_rng(seed)
    t = pd.date_range(start, periods=n, freq="1min")
    c = 40000 * np.exp(np.cumsum(rng.normal(drift, 0.0008, n)))
    o = np.r_[c[0], c[:-1]]
    return pd.DataFrame(dict(open=o, high=np.maximum(o, c) * 1.0003, low=np.minimum(o, c) * 0.9997, close=c,
                             volume=rng.uniform(1, 5, n)), index=t)


def test_5min_bars_and_gaps():
    m = _m1(20)
    m.iloc[5:10] = np.nan                                       # a whole 5-minute bar missing
    m.iloc[12] = np.nan                                         # one minute missing inside a bar
    b = ind.to_5min(m)
    assert list(b.index.minute) == [0, 10, 15]                  # 00:05 bar dropped, nothing filled
    assert b.n.tolist() == [5, 4, 5]
    assert b.open.iloc[0] == m.open.iloc[0] and b.close.iloc[0] == m.close.iloc[4]
    assert b.high.iloc[1] == m.high.iloc[10:15].max()
    assert len(ind.to_5min(m.iloc[:0])) == 0


def test_next_bar_entry_and_signal_definition():
    m = _m1(5 * 400, seed=3)
    b = ind.to_5min(m)
    S = ind.signals(b)
    assert (S.entry - S.index == pd.Timedelta(minutes=5)).all()      # entry = the next bar's open minute
    cu = ind.cross_up(S.ema_f.to_numpy(), S.ema_s.to_numpy())
    assert (S.call == (cu & (S.adx.fillna(-1) > 15))).all()
    assert not (S.call & S.put).any()


def test_lookahead_guard_truncation_and_perturbation():
    m = _m1(5 * 700, seed=4)
    perp = m.close * 1.0002
    full_b = ind.to_5min(m)
    full_S = ind.signals(full_b)
    full_F = ind.nn_features(full_b, full_S, perp.groupby(perp.index.floor("5min")).last())
    for cut in (300, 450, 640):
        t = full_b.index[cut]
        mm = m[m.index < t + pd.Timedelta(minutes=5)].copy()
        rng = np.random.default_rng(cut)
        tail = m[m.index >= t + pd.Timedelta(minutes=5)].copy() * rng.uniform(0.5, 2.0)    # perturbed future
        for mode, src in (("deleted", mm), ("perturbed", pd.concat([mm, tail]))):
            b = ind.to_5min(src)
            S = ind.signals(b)
            pp = perp.reindex(src.index) * (1 if mode == "deleted" else 1)
            F = ind.nn_features(b, S, pp.groupby(pp.index.floor("5min")).last())
            k = slice(None, t)
            for col in ("ema_f", "ema_s", "adx", "call", "put"):
                assert np.allclose(S.loc[k, col].astype(float), full_S.loc[k, col].astype(float), equal_nan=True), \
                    (mode, cut, col)
            assert np.allclose(F.loc[k].to_numpy(float), full_F.loc[k].to_numpy(float), equal_nan=True), (mode, cut)


def test_nn_label_reads_only_after_entry():
    m = _m1(300)
    E = pd.DatetimeIndex([m.index[10]])
    y = ind.nn_label(m.close, E)
    assert y[0] == float(m.close.iloc[10 + 59] > m.close.iloc[9])
    assert np.isnan(ind.nn_label(m.close, pd.DatetimeIndex([m.index[-5]])))[0]


# --- exits --------------------------------------------------------------------------------------------------------------
L1 = Exit(0.5, "step", step=0.25, first=1.25)
L2 = Exit(0.5, "trail", start=1.30, keep=0.5)


def _w(ex, o, h, l, dl=60, ref=100.0):
    o, h, l = (np.array([x], float) for x in (o, h, l))
    p, w, c, pk = walk(ex, np.array([ref]), o, h, l, dl)
    return p[0], w[0], c[0], pk[0]


def test_exit_needs_stop_and_lock():
    with pytest.raises(ValueError):
        Exit(0.0, "step")
    with pytest.raises(ValueError):
        Exit(0.5, "none")
    with pytest.raises(ValueError):
        Exit(0.5, "trail", start=1.0)


def test_stop_touch_and_gap():
    assert _w(L1, [100, 90], [101, 91], [95, 49]) == (50.0, STOP, 1, 1.01)       # touch: exit at the level
    p, w, c, _ = _w(L1, [100, 40], [101, 45], [95, 30])                            # gap through: the worse open
    assert (p, w, c) == (40.0, STOP, 1)


def test_step_lock_ladder_and_gap():
    # peak 1.30x -> lock 1.00x; peak 1.55x -> lock 1.25x; then a gap down to 1.10 exits at the open 110 (< 125)
    p, w, c, pk = _w(L1, [100, 120, 150, 110], [130, 140, 155, 112], [99, 115, 140, 100])
    assert (p, w, c) == (110.0, LOCK, 3) and pk == pytest.approx(1.55)
    assert L1.lock_mult(np.array([1.24, 1.25, 1.49, 1.5, 2.0, 2.3]) ).tolist() == [0, 1.0, 1.0, 1.25, 1.75, 2.0]


def test_lock_applies_from_next_minute():
    # the minute that makes the 1.30x peak also dips to 0.99x: the lock is not active yet, no exit there
    p, w, c, _ = _w(L1, [100, 120], [130, 125], [99, 100])
    assert (w, c) == (LOCK, 1) and p == 100.0


def test_trail_keeps_half_the_gain():
    # peak 1.60 -> lock 1.30; low 1.25 touches it -> exit at 130
    p, w, c, _ = _w(L2, [100, 150, 140], [160, 150, 141], [99, 135, 125])
    assert (p, w, c) == (130.0, LOCK, 2)
    assert L2.lock_mult(np.array([1.29, 1.30, 2.0])).tolist() == pytest.approx([0, 1.15, 1.5])


def test_time_exit_and_data_end():
    o = np.full(61, 100.0)
    p, w, c, _ = _w(L1, o, o + 1, o - 1)
    assert (p, w, c) == (100.0, TIME, 60)
    o2 = o.copy()
    o2[10:] = np.nan
    p, w, c, _ = _w(L1, o2, o2 + 1, o2 - 1)
    assert (w, c) == (DATA_END, 10) and p == 100.0
    empty = np.zeros((0, 61))
    assert len(walk(L1, np.zeros(0), empty, empty, empty, 60)[0]) == 0


# --- options: expiry rule, fills, fees ----------------------------------------------------------------------------------
def test_expiry_rule():
    t = pd.Timestamp("2025-03-10 10:30")
    assert D.expiry_of(t) == pd.Timestamp("2025-03-10 12:00")                  # exactly 90 min left: today's
    assert D.expiry_of(t + pd.Timedelta(minutes=5)) == pd.Timestamp("2025-03-11 12:00")
    assert D.expiry_of(pd.Timestamp("2025-03-10 23:55")) == pd.Timestamp("2025-03-11 12:00")
    ent = D.entries_for_expiry(pd.Timestamp("2025-03-11 12:00"))
    assert ent[0] == pd.Timestamp("2025-03-10 10:35") and ent[-1] == pd.Timestamp("2025-03-11 10:30")
    assert len(ent) == 288
    assert all(D.expiry_of(e) == pd.Timestamp("2025-03-11 12:00") for e in ent[[0, 100, -1]])


def test_fee_schedule_and_cap():
    assert D.fee_rule(pd.Timestamp("2024-06-01 12:00")) == (0.0003, 0.10)
    assert D.fee_rule(pd.Timestamp("2025-08-01 12:00")) == (0.00015, 0.10)
    assert D.fee_rule(pd.Timestamp("2025-10-01 12:00")) == (0.00015, 0.05)
    assert D.fee_rule(pd.Timestamp("2026-05-01 12:00")) == (0.0001, 0.035)
    # notional fee 0.0001 x 100000 x 0.001 = 0.01; cap 0.035 x 2 x 0.001 = 0.00007 -> the cap wins (cheap option)
    assert PL.fee(2.0, 100000.0, 0.001, (0.0001, 0.035)) == pytest.approx(0.00007 * 1.18)
    assert PL.fee(500.0, 100000.0, 0.001, (0.0001, 0.035)) == pytest.approx(0.01 * 1.18)


def _optday(trades: dict, E=pd.Timestamp("2025-03-11 12:00")):
    """trades: {(type, strike): [(minute ts, price, volume), ...]} -> OptDay from a synthetic candle frame."""
    rows = []
    for (typ, k), lst in trades.items():
        for ts, px, v in lst:
            rows.append(dict(timestamp=int(pd.Timestamp(ts).value // 10**6), option_type=typ, strike=k,
                             expiry_ts=int(E.value // 10**6), open=px, high=px, low=px, close=px, volume=v))
    return D.OptDay(E, frame=pd.DataFrame(rows))


def test_pick_first_real_trade_and_atm():
    E0 = pd.Timestamp("2025-03-10 20:00")
    day = _optday({("C", 80000.0): [(E0 - pd.Timedelta(minutes=30), 500, 1), (E0 + pd.Timedelta(minutes=2), 510, 3)],
                   ("C", 80500.0): [(E0, 300, 0), (E0 + pd.Timedelta(minutes=1), 300, 2)],
                   ("C", 79000.0): [(E0, 900, 5)]})                                  # > 1% from spot: never
    k, f = day.pick("C", 80100.0, day.idx(E0))
    assert k == 80000.0 and f == day.idx(E0) + 2                # nearest strike; its first VOLUME > 0 minute
    k2, _ = day.pick("C", 80300.0, day.idx(E0))
    assert k2 == 80500.0
    k3, f3 = day.pick("C", 80100.0, day.idx(E0 + pd.Timedelta(minutes=10)))       # nothing traded in the window
    assert k3 is None and f3 == -1


def test_naive_vs_one_minute_later_fill():
    E0 = pd.Timestamp("2025-03-10 20:00")
    tr = [(E0 + pd.Timedelta(minutes=1), 500.0, 2), (E0 + pd.Timedelta(minutes=2), 520.0, 1)]
    tr += [(E0 + pd.Timedelta(minutes=3 + i), 520.0, 0) for i in range(80)]
    day = _optday({("C", 80000.0): tr})
    spot = pd.Series(80000.0, index=day.grid)
    P = PL.option_pool_day(day, spot, "C", {"L1": L1})
    r = P[P.entry == E0].set_index("mode")
    assert r.loc["naive", "mid"] == 500.0 and r.loc["naive", "buy"] == 500.0              # the printed trade
    assert r.loc["real", "mid"] == 520.0                                                   # one minute later
    hs = PL.half_spread(520.0, 80000.0)
    assert r.loc["real", "buy"] == pytest.approx(520.0 + hs)
    assert r.loc["stress", "buy"] == pytest.approx(520.0 + PL.half_spread(520.0, 80000.0, 1.5))
    assert r.loc["real", "reason"] == TIME
    assert r.loc["real", "fill_ts"] == E0 + pd.Timedelta(minutes=2)
    assert r.loc["real", "exit_ts"] == E0 + pd.Timedelta(minutes=2 + 61)                  # 60 min after the fill
    sell = 520.0 - PL.half_spread(520.0, 80000.0) - K.TICK - 0.01 * 520.0
    assert r.loc["real", "sell"] == pytest.approx(sell)
    assert r.loc["real", "tax"] == 0.0 and r.loc["real", "net"] < 0                        # a loser: no tax
    # an entry where nothing traded in the 5-minute window is not fillable
    late = P[(P.entry == E0 + pd.Timedelta(minutes=5)) & (P["mode"] == "real")]
    assert not late.ok.any()


def test_tax_only_on_winners():
    E0 = pd.Timestamp("2025-03-10 20:00")
    tr = [(E0, 500.0, 1), (E0 + pd.Timedelta(minutes=1), 500.0, 1), (E0 + pd.Timedelta(minutes=3), 900.0, 1)]
    tr += [(E0 + pd.Timedelta(minutes=4 + i), 900.0, 0) for i in range(70)]
    day = _optday({("C", 80000.0): tr})
    P = PL.option_pool_day(day, pd.Series(80000.0, index=day.grid), "C", {"L2": L2})
    r = P[(P.entry == E0) & (P["mode"] == "real")].iloc[0]
    assert r.gross > 0 and r.tax == pytest.approx(K.TAX * r.gross) and r.net_after == pytest.approx(r.net - r.tax)


def test_empty_option_day():
    day = D.OptDay(pd.Timestamp("2025-03-11 12:00"), frame=pd.DataFrame(
        columns=["timestamp", "option_type", "strike", "expiry_ts", "open", "high", "low", "close", "volume"]))
    assert not day.ok
    assert len(PL.option_pool_day(day, pd.Series(dtype=float), "C", {"L1": L1})) == 0


def test_perp_pool_long_short_mirror():
    t = pd.date_range("2025-01-01", periods=200, freq="1min")
    px = np.full(200, 100.0)
    px[5:] = 99.0                                                 # -1%: a long stops at -0.4%, a short gains
    perp = pd.DataFrame(dict(open=px, high=px, low=px, close=px), index=t)
    perp.iloc[5, perp.columns.get_loc("open")] = 100.0
    ex = Exit(0.004, "trail", start=1.003, keep=0.5)
    L = PL.perp_pool(perp, pd.DatetimeIndex([t[0]]), 1, ex).iloc[0]
    S = PL.perp_pool(perp, pd.DatetimeIndex([t[0]]), -1, ex).iloc[0]
    assert L.reason == STOP and L.gross < 0
    assert S.gross > 0 and S.reason in (LOCK, TIME)
    assert S.tds == pytest.approx(K.TDS * S.buy)                  # a short sells at entry


# --- book: one at a time, max 2 per IST day -----------------------------------------------------------------------------
def _pool(entries, hold=10, ok=True):
    e = pd.DatetimeIndex(entries)
    return pd.DataFrame(dict(ok=ok, exit_ts=e + pd.Timedelta(minutes=hold), gross=1.2, fees=0.2, net=1.0, net_after=0.7, tax=0.3,
                             tds=0.01, reason=TIME), index=e)


def test_max_two_per_ist_day_across_midnight():
    # IST midnight = 18:30 UTC. 18:00, 18:15, 18:25 UTC are the same IST day (the 3rd is refused); 18:30 is a new day
    e = pd.DatetimeIndex(["2025-01-01 18:00", "2025-01-01 18:15", "2025-01-01 18:25", "2025-01-01 18:30",
                          "2025-01-01 18:45", "2025-01-01 19:00"])
    T = B.select(e, _pool(e, hold=5))
    assert list(T.index) == list(e[[0, 1, 3, 4]])


def test_one_position_at_a_time_and_unfilled_uses_no_slot():
    e = pd.DatetimeIndex(["2025-01-02 01:00", "2025-01-02 01:05", "2025-01-02 02:00", "2025-01-02 03:00"])
    P = _pool(e, hold=30)
    P.loc[e[2], "ok"] = False
    T = B.select(e, P)
    assert list(T.index) == [e[0], e[3]]                        # 01:05 is inside the open trade; 02:00 unfilled
    assert len(B.select(pd.DatetimeIndex([]), P)) == 0


def test_summary_twins_bh():
    e = pd.date_range("2025-01-01 01:00", periods=100, freq="5min")
    P = _pool(e)
    P["net_after"] = np.linspace(-1, 1, 100)
    T = P.iloc[-10:]
    s = B.summary(T, 1)
    assert s["trades"] == 10 and s["after_tax"] == pytest.approx(T.net_after.sum(), abs=0.01)
    p, tw = B.twin_p(T, P, draws=200)
    assert p < 0.05 and len(tw) == 200
    p2, _ = B.twin_p(P.iloc[:10], P, draws=200)
    assert p2 > 0.9
    assert B.summary(T.iloc[:0], 5) == dict(trades=0, days=5)
    q = B.bh([0.01, 0.04, 0.03, np.nan])
    assert q[0] == pytest.approx(0.03) and np.isnan(q[3]) and q[1] == pytest.approx(0.04)
    assert B.max_dd(np.array([1, -2, 1, -3, 5.0])) == pytest.approx(4.0)


# --- MLP walk-forward ---------------------------------------------------------------------------------------------------
def test_mlp_learns_and_walk_forward_never_sees_test_month():
    rng = np.random.default_rng(1)
    idx = pd.date_range("2024-01-01", "2024-09-30 23:55", freq="5min")
    X = rng.normal(size=(len(idx), 12))
    y = (X[:, 0] + 0.3 * rng.normal(size=len(idx)) > 0).astype(float)
    F = pd.DataFrame(X, index=idx, columns=K.NN_FEATURES)
    entries = idx + pd.Timedelta(minutes=5)
    q = pd.Series(True, index=idx)
    months = [pd.Timestamp("2024-08-01"), pd.Timestamp("2024-09-01")]
    seen = []
    orig = nn.MLP.fit

    def spy(self, Xt, yt, **kw):
        seen.append(len(Xt))
        return orig(self, Xt, yt, epochs=3)

    nn.MLP.fit = spy
    try:
        prob, log = nn.walk_forward(F, y, entries, months, q)
    finally:
        nn.MLP.fit = orig
    aug = (entries >= months[0]) & (entries < months[1])
    lab_end = entries + pd.Timedelta(minutes=60)
    assert seen[0] == int((lab_end < months[0]).sum())          # only labels that ended before August
    assert prob[aug].notna().all() and prob[entries < months[0]].isna().all()
    acc = ((prob[aug] > 0.5) == (y[aug] > 0.5)).mean()
    assert acc > 0.8
    # too little history -> the month is skipped, not trained on less
    p2, log2 = nn.walk_forward(F, y, entries, [pd.Timestamp("2024-03-01")], q)
    assert log2[0]["trained"] is False and p2.isna().all()


# --- holdout lock -------------------------------------------------------------------------------------------------------
def test_holdout_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "HOLD_LOG", tmp_path / "HOLDOUT_READ.json")
    D.open_holdout("x")
    with pytest.raises(D.HoldoutError):
        D.open_holdout("x")


def test_design_never_loads_holdout(monkeypatch, tmp_path):
    for kind in ("spot", "perp"):
        for ym in ("2026-03", "2026-04"):
            d = tmp_path / f"{kind}_BTC" / ym[:4]
            d.mkdir(parents=True, exist_ok=True)
            t = pd.date_range(ym + "-01", periods=3, freq="1min")
            pd.DataFrame(dict(open_time=(t - pd.Timestamp(0)) // pd.Timedelta(milliseconds=1), open=1.0, high=1.0, low=1.0, close=1.0,
                              volume=1.0)).to_parquet(d / f"BTCUSDT-klines_1m-{ym}.parquet")
    monkeypatch.setattr(K, "LAKE", tmp_path)
    monkeypatch.setattr(K, "DATA_START", pd.Timestamp("2026-03-01"))
    m = D.load_1m("spot", K.DESIGN[1])
    assert m.index.max() < K.HOLDOUT[0]
