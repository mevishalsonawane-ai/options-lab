"""R46 amendment tests: the chain grid and the rolling ATM series, held-contract features and labels, the dip pool's
same-contract drop and one-minute-later fill, CoinSwitch / Delta costs with and without GST, the ensemble's walk-forward
(purge + embargo, never trained on its block), the AUC, the literal reproduction of the other AI's code (mixed rows,
idx + 4 = 2 minutes), the look-ahead guard and the stop + lock rule.

    python -m pytest research/hunt/r46 -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r46 import amend_cfg as A
from research.hunt.r46 import chain as CH
from research.hunt.r46 import costs as C
from research.hunt.r46 import data as D
from research.hunt.r46 import dip as DP
from research.hunt.r46 import ens as EN
from research.hunt.r46 import panel as PN
from research.hunt.r46 import r46cfg as K
from research.hunt.r46.exits import from_cfg

E = pd.Timestamp("2025-06-10 12:00")


def candles(rows):
    """rows: (minute ts, type, strike, close, volume) -> a Delta candle frame for expiry E (o = h = l = c)."""
    df = pd.DataFrame(rows, columns=["ts", "option_type", "strike", "close", "volume"])
    df["timestamp"] = (pd.DatetimeIndex(df.ts).astype("int64") // 10**6).astype("int64")
    df["expiry_ts"] = int(E.value // 10**6)
    df["open"] = df["high"] = df["low"] = df["close"]
    return df.drop(columns="ts")


def spot_frame(a, b, level=100_000.0):
    idx = pd.date_range(a, b, freq="1min")
    return pd.DataFrame({"open": level, "high": level, "low": level, "close": level, "volume": 1.0}, index=idx)


# --- views and settings -------------------------------------------------------------------------------------------------
def test_views_fixed_before_results():
    v = A.VIEWS["nov24"]
    assert v["design"] == (pd.Timestamp("2024-11-20"), pd.Timestamp("2026-04-01"))
    assert v["final"] == K.HOLDOUT                       # the same span as R46's (already read) holdout
    assert A.PRIMARY == "nov24"
    b = A.blocks("nov24")
    assert b[0] == pd.Timestamp("2025-02-01") and pd.Timestamp("2026-04-01") in b and b[-1] == A.FINAL[1]
    assert all(x < y for x, y in zip(b[:-1], b[1:]))
    assert A.blocks("full")[0] == pd.Timestamp("2024-04-01")


def test_every_tested_exit_has_a_stop_and_a_lock():
    for name in set(A.ENS_EXITS) | set(A.DIP_EXITS):
        ex = from_cfg(K.OPT_EXITS[name])
        assert 0 < ex.stop < 1 and ex.lock in ("step", "trail")
    assert {from_cfg(K.OPT_EXITS[n]).lock for n in A.DIP_EXITS} == {"step", "trail"}
    with pytest.raises(ValueError):
        from_cfg(dict(stop=0.5, lock="none"))


# --- chain --------------------------------------------------------------------------------------------------------------
def test_own_windows_tile_time_and_match_the_expiry_rule():
    a, b = CH.own_window(E)
    assert D.expiry_of(a) == E and D.expiry_of(b) == E
    assert D.expiry_of(a - CH.ONE) == E - pd.Timedelta(days=1)
    assert D.expiry_of(b + CH.ONE) == E + pd.Timedelta(days=1)
    ex = CH.expiries(pd.Timestamp("2025-06-09 00:00"), pd.Timestamp("2025-06-11 00:00"))
    assert ex[0] == pd.Timestamp("2025-06-09 12:00") and pd.Timestamp("2025-06-11 12:00") in ex


def test_chain_day_carries_prices_and_picks_the_nearest_priced_strike():
    a, _ = CH.own_window(E)
    t1 = a + pd.Timedelta(minutes=10)
    fr = candles([(t1, "C", 100_000.0, 500.0, 2), (t1, "C", 100_200.0, 400.0, 1),
                  (t1 + pd.Timedelta(minutes=5), "C", 99_800.0, 600.0, 3)])
    day = CH.ChainDay(E, fr)
    ks, o, h, l, c, v = day.arr["C"]
    assert list(ks) == [99_800.0, 100_000.0, 100_200.0]
    i = day.idx(t1)
    assert np.isnan(c[1, i - 1]) and c[1, i] == 500 and c[1, i + 30] == 500       # carried forward, NaN before
    assert v[1, i] == 2 and v[1, i + 1] == 0
    spot = np.full(len(day.grid), 99_850.0)
    rows = day.atm("C", spot)
    assert rows[i - 1] == -1                                  # nothing priced yet
    assert ks[rows[i]] == 100_000.0                           # 99,800 has no price yet at t1
    assert ks[rows[i + 5]] == 99_800.0                        # now it has, and it is nearer
    far = day.atm("C", np.full(len(day.grid), 120_000.0))
    assert (far == -1).all()                                  # beyond the 1% band: no ATM
    tie = day.atm("C", np.full(len(day.grid), 100_100.0))
    assert ks[tie[i]] == 100_000.0                            # a tie goes to the lower strike


def test_rolling_series_switches_contracts_and_contract_id():
    a, _ = CH.own_window(E)
    t1 = a + pd.Timedelta(minutes=1)
    fr = candles([(t1, "C", 100_000.0, 500.0, 1), (t1, "C", 100_200.0, 400.0, 1)])
    day = CH.ChainDay(E, fr)
    spot = np.full(len(day.grid), 100_010.0)
    j = day.idx(t1) + 3
    spot[j:] = 100_190.0
    r = CH.rolling_day(day, "C", spot, day.atm("C", spot))
    assert r.premium.iloc[1] == 500 and r.premium.iloc[3] == 500 and r.premium.iloc[4] == 400
    assert r.strike.iloc[4] == 100_200.0
    cid = CH.contract_id(r)
    assert cid[0] == -1 and cid[1] != cid[4] and cid[1] == cid[3]


# --- costs --------------------------------------------------------------------------------------------------------------
def trade_rows(n=1, q=None):
    T = pd.DataFrame(dict(buy=[300.0] * n, sell=[360.0] * n, spot=[100_000.0] * n, spot_out=[101_000.0] * n,
                          expiry=[pd.Timestamp("2026-05-01 12:00")] * n))
    if q is not None:
        T["q"] = q
    return T


def test_coinswitch_fees_with_and_without_gst():
    T = trade_rows()
    g = C.recost_opt(T, "cs_gst").iloc[0]
    n = C.recost_opt(T, "cs_nogst").iloc[0]
    fee0 = A.CS_OPT_RATE * (100_000 + 101_000) * K.LOT_BTC
    assert n.fees == pytest.approx(fee0) and g.fees == pytest.approx(fee0 * 1.18)
    assert g.gross == pytest.approx(60 * K.LOT_BTC) and g.tax == pytest.approx(K.TAX * 60 * K.LOT_BTC)
    assert n.net_after == pytest.approx(n.gross - n.fees - n.tax)


def test_delta_recost_matches_the_r46_pool_fee():
    from research.hunt.r46 import pool as PL
    T = trade_rows(q=[2.0])
    d = C.recost_opt(T, "delta").iloc[0]
    rule = D.fee_rule(T.expiry.iloc[0])
    want = PL.fee(300.0, 100_000.0, 2.0, rule) + PL.fee(360.0, 101_000.0, 2.0, rule)
    assert d.fees == pytest.approx(float(want))


def test_losing_trade_pays_no_tax_and_perp_recost():
    T = trade_rows()
    T["sell"] = 200.0
    r = C.recost_opt(T, "cs_nogst").iloc[0]
    assert r.tax == 0 and r.net_after == pytest.approx(r.net)
    P = pd.DataFrame(dict(buy=[100_000.0], sell=[100_500.0], gross=[500.0]))
    for s, (rate, gst) in A.PERP_SCHEMES.items():
        x = C.recost_perp(P, s).iloc[0]
        assert x.fees == pytest.approx(rate * (1 + gst) * 200_500.0)
    assert C.recost_opt(T.iloc[:0], "delta").empty


# --- ensemble features, AUC and walk-forward ----------------------------------------------------------------------------
def test_rsi_simple_hand_values():
    P = np.array([[float(x) for x in range(30, 15, -1)]])           # rising every bar into now -> 100
    assert EN.rsi_simple(P)[0] == 100
    flat = np.full((1, 15), 5.0)
    assert EN.rsi_simple(flat)[0] == 50
    mix = np.array([[10, 9, 10, 9, 10, 9, 10, 9, 10, 9, 10, 9, 10, 9, 10.0]])   # 7 ups of 1, 7 downs of 1
    assert EN.rsi_simple(mix)[0] == pytest.approx(50)
    mix[0, 14] = np.nan
    assert np.isnan(EN.rsi_simple(mix)[0])


def test_path_features_hand_values():
    P = np.array([[110.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 55.0, 50.0,
                   50.0]])
    S = np.full((1, 13), 100_000.0)
    ts = pd.DatetimeIndex(["2025-06-10 06:04"])
    F = EN.path_features(P, S, ts, pd.DatetimeIndex([E]))
    assert F.ret1[0] == pytest.approx(0.10) and F.ret12[0] == pytest.approx(110 / 55 - 1)
    assert F.dte_h[0] == pytest.approx((E - ts[0]) / pd.Timedelta(hours=1))
    assert F.hour[0] == pytest.approx((6 + 4 / 60) / 24) and F.ce_spot[0] == pytest.approx(110 / 100_000)
    assert F.spot_ret1[0] == 0 and list(F.columns) == list(A.ENS_FEATURES)


def test_auc():
    assert EN.auc([0, 0, 1, 1], [0.1, 0.2, 0.8, 0.9]) == 1.0
    assert EN.auc([0, 0, 1, 1], [0.9, 0.8, 0.2, 0.1]) == 0.0
    assert EN.auc([0, 1, 0, 1], [0.5, 0.5, 0.5, 0.5]) == 0.5
    assert np.isnan(EN.auc([1, 1], [0.2, 0.3]))


def test_train_mask_purges_and_embargoes():
    T = pd.Timestamp("2025-04-01")
    ts = pd.date_range("2025-03-31 20:00", "2025-04-01 01:00", freq="5min")
    m = EN.train_mask(ts, T, pd.Timestamp("2025-01-01"))
    last = ts[m].max()
    assert last + pd.Timedelta(minutes=60) <= T - pd.Timedelta(minutes=60)
    assert not m[ts >= T].any()
    assert not EN.train_mask(ts, T, pd.Timestamp("2025-04-02")).any()       # data_from after everything


class Spy:
    """A model that remembers what it was trained on and predicts its training base rate."""
    seen = []

    def fit(self, X, y):
        Spy.seen.append(X[:, 0].max())
        self.p = float(np.mean(y))
        return self

    def predict(self, X):
        return np.full(len(X), self.p)


def test_walk_forward_never_trains_on_its_block(monkeypatch):
    monkeypatch.setattr(A, "ENS_MIN_TRAIN", 10)
    ts = pd.date_range("2024-11-20", "2025-07-15", freq="6h")
    t_num = (ts - ts[0]) / pd.Timedelta(hours=1)
    X = pd.DataFrame({f: np.asarray(t_num, float) for f in A.ENS_FEATURES})
    y = (np.arange(len(ts)) % 2).astype(float)
    Spy.seen = []
    prob, log = EN.walk_forward(X, y, ts, "nov24", model=Spy)
    starts = [pd.Timestamp(r["block"]) for r in log if r.get("trained")]
    assert starts and len(Spy.seen) == len(starts)
    for T, mx in zip(starts, Spy.seen):
        assert ts[0] + pd.Timedelta(hours=mx) + pd.Timedelta(minutes=120) <= T     # label + embargo end before T
    assert np.isnan(prob[np.asarray(ts < pd.Timestamp("2025-02-01"))]).all()      # nothing before the first block


def test_random_split_auc_runs():
    ts = pd.date_range("2025-01-01", periods=200, freq="5min")
    X = pd.DataFrame({f: np.arange(200.0) for f in A.ENS_FEATURES})
    y = (np.arange(200) >= 100).astype(float)
    r = EN.random_split_auc(X, y, ts, (ts[0], ts[-1] + CH.ONE), model=Spy)
    assert r["n_test"] == 40 and r["n_train"] == 160


# --- panel: held features, the dip pool, look-ahead -------------------------------------------------------------------
def day_with_switch():
    """Two strikes: 100,000 trades 400 then falls to 280 (same contract); 100,200 sits at 200. Spot starts nearer
    100,000 and moves nearer 100,200 later."""
    a, b = CH.own_window(E)
    rows = []
    t = a - pd.Timedelta(minutes=100)
    for m in range(0, 400):
        ts = t + pd.Timedelta(minutes=m)
        px = 400.0 if m < 214 else 280.0
        rows.append((ts, "C", 100_000.0, px, 1))
        rows.append((ts, "C", 100_200.0, 200.0, 1))
        rows.append((ts, "P", 100_000.0, 300.0, 1))
    return candles(rows), a


def test_dip_uses_the_same_contract_and_fills_a_minute_later():
    fr, a = day_with_switch()
    day = CH.ChainDay(E, fr)
    spot = np.full(len(day.grid), 100_050.0)
    rows = day.atm("C", spot)
    so = spot.copy()
    exits = {e: from_cfg(K.OPT_EXITS[e]) for e in A.DIP_EXITS}
    d = PN.dip_day(day, "C", rows, so, exits)
    dips = d[(d.bar_chg <= A.DIP_DROP) & (d.exit == "L1")]
    assert len(dips) == 1
    r = dips.iloc[0]
    assert r.strike == 100_000.0 and r.prem == 280 and r.prev == 400
    assert r.fill_ts == r.ts + pd.Timedelta(minutes=A.DIP_FILL_FROM)          # the first trade 1 minute after close
    assert r.buy > r.mid == 280                                                # plus half the spread
    # a strike switch alone is never a dip: the 100,200 contract never moves
    spot2 = np.where(np.arange(len(day.grid)) > day.idx(a) + 50, 100_150.0, 100_050.0)
    d2 = PN.dip_day(day, "C", day.atm("C", spot2), so, exits)
    assert (d2[d2.strike == 100_200.0].bar_chg == 0).all()


def test_no_trade_no_fill():
    fr, a = day_with_switch()
    day = CH.ChainDay(E, fr)
    ks, o, h, l, c, v = day.arr["C"]
    v[:] = 0.0                                                                 # nothing trades
    rows = day.atm("C", np.full(len(day.grid), 100_050.0))
    d = PN.dip_day(day, "C", rows, np.full(len(day.grid), 100_050.0), {"L1": from_cfg(K.OPT_EXITS["L1"])})
    assert len(d) and not d.filled.any()
    s = DP.size(d)
    assert not s.ok.any()


def test_held_features_ignore_the_future():
    fr, a = day_with_switch()
    spot = np.full(4000, 100_050.0)
    day = CH.ChainDay(E, fr)
    h1 = PN.held_day(day, day.atm("C", spot[:len(day.grid)]), spot[:len(day.grid)])
    cut = a + pd.Timedelta(minutes=150)
    ts_ms = pd.to_datetime(fr.timestamp, unit="ms")
    day2 = CH.ChainDay(E, fr[ts_ms <= cut])
    h2 = PN.held_day(day2, day2.atm("C", spot[:len(day2.grid)]), spot[:len(day2.grid)])
    m = h1.merge(h2, on="ts", suffixes=("", "_cut"))
    m = m[m.ts <= cut]
    assert len(m)
    for j in range(PN.PATH_LAGS):
        assert np.allclose(m[f"p{j}"], m[f"p{j}_cut"], equal_nan=True)
    assert (m.strike == m.strike_cut).all()


# --- their code, literally ----------------------------------------------------------------------------------------------
def test_their_code_compares_call_rows_with_put_rows():
    ts = pd.date_range("2025-06-10 00:00", periods=6, freq="1min")
    c = pd.DataFrame(dict(ts=ts, side="C", expiry=E, strike=100_000.0, premium=[200.0, 210, 205, 220, 230, 240]))
    p = pd.DataFrame(dict(ts=ts, side="P", expiry=E, strike=100_000.0, premium=[400.0] * 6))
    df = DP.interleave(c, p, ts[0], ts[-1] + CH.ONE)
    assert list(df.side[:4]) == ["C", "P", "C", "P"]
    T = DP.their_trades(df, "C")
    # every call row after the first "dropped" 45-49% from the put row before it: a signal on each with room for
    # idx + 4 (the first row has no previous row)
    assert len(T) == 3 and (T.prev_cat == "mixed").all()
    assert (T.minutes == 2).all()                            # idx + 4 rows = 2 minutes, not 60
    assert T.pnl.iloc[0] == pytest.approx(300 * (220 / 210 - 1) - 5)
    s = DP.summarise_their(T)
    assert s["clean_trades"] == 0 and s["trades"] == 3 and s["share_of_total_not_clean"] == 1.0


def test_rolling15_flags_switches():
    ts = pd.date_range("2025-06-10 00:00", periods=120, freq="1min")
    strike = np.where(np.arange(120) < 30, 100_000.0, 100_200.0)
    prem = np.where(np.arange(120) < 30, 400.0, 250.0)
    r = pd.DataFrame(dict(ts=ts, side="C", expiry=E, strike=strike, premium=prem))
    T = DP.rolling15(r, ts[0], ts[-1] + CH.ONE)
    assert len(T) == 1 and T.prev_cat.iloc[0] == "switch"
    assert DP.summarise_their(T)["clean_trades"] == 0
    assert DP.summarise_their(T.iloc[:0])["trades"] == 0


def test_select_one_at_a_time_and_size():
    idx = pd.DatetimeIndex(["2025-06-10 00:15", "2025-06-10 00:30", "2025-06-10 01:30"])
    pool = pd.DataFrame(dict(ok=[True, True, True], exit_ts=idx + pd.Timedelta(minutes=61)), index=idx)
    T = DP.select_one_at_a_time(idx, pool)
    assert list(T.index) == [idx[0], idx[2]]                  # the 00:30 signal falls inside the first trade
    assert DP.select_one_at_a_time(pd.DatetimeIndex([]), pool).empty
    s = DP.size(pd.DataFrame(dict(buy=[70.0, 400_000.0], filled=[True, True], sell=[80.0, 1.0])))
    assert s.q.iloc[0] == pytest.approx(4.285) and s.ok.iloc[0]
    assert s.q.iloc[1] == 0 and not s.ok.iloc[1]
