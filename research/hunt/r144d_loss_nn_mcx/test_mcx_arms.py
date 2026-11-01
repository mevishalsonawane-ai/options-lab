"""Tests for R144d's MCX arm rebuild (mcx_arms.py): look-ahead guard for every arm's features function, edge cases
(empty data, gaps, roll / expiry day, missing US price, holidays), and the copied engine pieces on small synthetic data.

    python -m pytest research/hunt/r144d_loss_nn_mcx/test_mcx_arms.py -q
"""
import datetime as dt
import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcx_arms as A  # noqa: E402

IST = A.IST


# ---------------------------------------------------------------- synthetic market
def minutes(days, start="09:00", end="23:29", seed=0, gap_every=None):
    rng = np.random.default_rng(seed)
    rows = []
    px = 100.0
    oi = 100000.0
    for d in days:
        t = pd.date_range(f"{d} {start}", f"{d} {end}", freq="1min", tz=IST)
        if gap_every:
            t = t[np.arange(len(t)) % gap_every != 0]
        for x in t:
            px *= np.exp(rng.normal(0, 0.001))
            oi += rng.normal(0, 5)
            rows.append(dict(ts=x, open=px, high=px * 1.0005, low=px * 0.9995, close=px, volume=float(rng.integers(0, 50)), oi=oi))
    return pd.DataFrame(rows)


def daily(days, seed=1):
    rng = np.random.default_rng(seed)
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, len(days))))
    return pd.DataFrame(dict(date=pd.to_datetime(days), open=c * 0.999, high=c * 1.01, low=c * 0.99, close=c,
                             volume=1000.0, oi=5000.0))


def hourly(t0, t1, seed=2):
    t = pd.date_range(t0, t1, freq="1h", tz=IST)
    rng = np.random.default_rng(seed)
    c = 30 * np.exp(np.cumsum(rng.normal(0, 0.002, len(t))))
    return pd.DataFrame(dict(ts=t, open=c, close=c))


DAYS = [d.date() for d in pd.bdate_range("2026-03-02", "2026-04-10")]
ENTRY = pd.Timestamp("2026-04-09 19:31", tz=IST)


def garble(df, entry, col="ts", end_offset=None):
    """Overwrite every row at/after the entry (or ending after it) with garbage, keeping the timestamps."""
    df = df.copy()
    late = df[col] >= entry if end_offset is None else df[col] + end_offset > entry
    for c in df.columns:
        if c != col and pd.api.types.is_numeric_dtype(df[c]):
            df.loc[late, c] = 9e9
    return df


def ctx_from_full(full, entry, side, extra=None):
    """What the builders do: truncate every input to what ended before the entry, then build the context."""
    bars, D, opt, optk, us, fx = full
    day = entry.normalize().tz_localize(None)
    cal = np.array(pd.to_datetime(DAYS + [d.date() for d in pd.bdate_range("2026-04-13", "2026-06-30")]).values, dtype="datetime64[D]")
    ctx = dict(entry_ts=entry, side=side, daily=D[D.date < day], cal=cal, expiry=dt.date(2026, 4, 21),
               roll=dt.date(2026, 4, 22), prev_close_ts=A.mcx_close_ts(pd.Timestamp("2026-04-08")),
               us_data_days={dt.date(2026, 4, 9)},
               us=A.truncate(us, entry, end_offset=pd.Timedelta(hours=1)), fx=A.truncate(fx, entry, end_offset=pd.Timedelta(hours=1)),
               opt=A.truncate(opt, entry), optk=A.truncate(optk, entry))
    ctx.update(extra or {})
    return A.truncate(bars, entry), ctx


def full_data():
    bars = minutes(DAYS)
    D = daily(DAYS)
    opt = minutes(DAYS[-3:], start="17:00", seed=5).assign(iv=40.0)
    optk = minutes(DAYS, start="17:00", seed=6)
    us = hourly("2026-03-30", "2026-04-11", seed=3)
    fx = hourly("2026-03-30", "2026-04-11", seed=4)
    return bars, D, opt, optk, us, fx


def garbled(full, entry):
    bars, D, opt, optk, us, fx = full
    day = entry.normalize().tz_localize(None)
    D2 = D.copy(); late = D2.date >= day
    for c in ("open", "high", "low", "close"):
        D2.loc[late, c] = 9e9
    h = pd.Timedelta(hours=1)
    return (garble(bars, entry), D2, garble(opt, entry), garble(optk, entry), garble(us, entry, end_offset=h),
            garble(fx, entry, end_offset=h))


EXTRAS = {
    "NGEVE": (A.features_ngeve, dict(sig=dict(hi=101.0, lo=99.0, sigF=101.2, level=101.0, sig_min=1170, entry_lag=0))),
    "SILM": (A.features_silm, dict(k=2, K=105.0, grid_n=0)),
    "USSIL": (A.features_ussil, dict(sig=dict(fair=0.004, us_on=0.003, inr_on=0.001, p_prev=99.0))),
    "TREND": (A.features_trend, dict(sig=dict(ret252=0.2, vol60=0.25, carry=-0.1, dsr=12.0, reentry=0.0, leg=1.0))),
}


def same(a, b):
    assert a.keys() == b.keys()
    for k in a:
        x, y = a[k], b[k]
        assert (np.isnan(x) and np.isnan(y)) or x == y, (k, x, y)


# ---------------------------------------------------------------- look-ahead guard, every arm
@pytest.mark.parametrize("arm", list(EXTRAS))
@pytest.mark.parametrize("side", [1, -1])
def test_lookahead_guard(arm, side):
    fn, extra = EXTRAS[arm]
    full = full_data()
    f1 = fn(*ctx_from_full(full, ENTRY, side, extra))
    f2 = fn(*ctx_from_full(garbled(full, ENTRY), ENTRY, side, extra))
    same(f1, f2)
    assert np.isfinite(f1["f_push"]) and np.isfinite(f1["f_trend20"]) and np.isfinite(f1["f_us_on"])
    assert np.isfinite(f1["f_vol_rel"]) and np.isfinite(f1["f_od"]) and f1["f_oiq"] in (1.0, 2.0, 3.0, 4.0)
    if arm in ("NGEVE", "SILM"):
        assert np.isfinite(f1["f_prem"]) and np.isfinite(f1["f_opt_vol_rel"]) and f1["f_iv"] == 40.0


def test_features_reject_future_bars():
    bars, ctx = ctx_from_full(full_data(), ENTRY, 1)
    full_bars = full_data()[0]
    with pytest.raises(ValueError):
        A.base_features(full_bars, ctx)  # not truncated: contains the entry minute and later
    D = full_data()[1]
    with pytest.raises(ValueError):
        A.base_features(bars, dict(ctx, daily=D))  # a daily bar of the entry day
    us = full_data()[4]
    with pytest.raises(ValueError):
        A.base_features(bars, dict(ctx, us=us[us.ts < ENTRY]))  # an hourly bar started before but ends after the entry
    opt = full_data()[2]
    with pytest.raises(ValueError):
        A.option_features(opt, None, ENTRY, 100.0)


def test_signed_features_flip_with_side():
    full = full_data()
    up = A.base_features(*ctx_from_full(full, ENTRY, 1))
    dn = A.base_features(*ctx_from_full(full, ENTRY, -1))
    for k in ("f_push", "f_trend20", "f_gap", "f_d_open", "f_ret5", "f_us_on", "f_od"):
        assert up[k] == pytest.approx(-dn[k])
    for k in ("f_d_pdh", "f_d_pdl", "f_fx_on", "f_rng_today", "f_vol_rel", "f_prev_rng"):
        assert up[k] == pytest.approx(dn[k])


# ---------------------------------------------------------------- edge cases
def test_empty_data_gives_nan_not_crash():
    e = pd.DataFrame(columns=["ts", "close"])
    ctx = dict(entry_ts=ENTRY, side=1, daily=pd.DataFrame(columns=["date", "open", "high", "low", "close"]))
    f = A.base_features(e, ctx)
    assert f["f_min"] == 19 * 60 + 31 and f["f_wd"] == 3
    for k in ("f_push", "f_trend20", "f_gap", "f_vol_rel", "f_us_on", "f_dte", "f_us_data"):
        assert np.isnan(f[k])
    o = A.option_features(None, None, ENTRY, 100.0)
    assert all(np.isnan(v) for v in o.values())


def test_short_history_and_gaps():
    days = DAYS[-3:]
    bars = minutes(days, gap_every=3)  # every third minute missing
    D = daily(days)
    b, ctx = A.truncate(bars, ENTRY), dict(entry_ts=ENTRY, side=1, daily=D[D.date < ENTRY.normalize().tz_localize(None)])
    f = A.base_features(b, ctx)
    assert np.isnan(f["f_trend20"]) and np.isnan(f["f_push"])  # < 14 daily bars: no ATR
    assert np.isnan(f["f_vol_rel"])  # fewer than 5 past sessions at the same clock minutes
    assert np.isnan(f["f_d_pdh"])  # one daily bar before the entry: no ATR to scale by


def test_us_price_missing_or_stale():
    full = list(full_data())
    b, ctx = ctx_from_full(tuple(full), ENTRY, 1)
    assert np.isfinite(A.base_features(b, ctx)["f_us_on"])
    f = A.base_features(b, dict(ctx, us=None))
    assert np.isnan(f["f_us_on"]) and np.isfinite(f["f_fx_on"])
    us = ctx["us"]
    stale = us[us.ts < ENTRY - pd.Timedelta(hours=10)]  # last US bar 10 h old (e.g. a US holiday)
    assert np.isnan(A.base_features(b, dict(ctx, us=stale))["f_us_on"])
    nopc = us[us.ts > ctx["prev_close_ts"]]  # nothing around the previous MCX close
    assert np.isnan(A.base_features(b, dict(ctx, us=nopc))["f_us_on"])


def test_dte_roll_day_and_holidays():
    cal = np.array(pd.to_datetime(["2026-04-20", "2026-04-21", "2026-04-23", "2026-04-24"]).values, dtype="datetime64[D]")
    td = A._trading_days_between
    assert td(cal, dt.date(2026, 4, 21), dt.date(2026, 4, 21)) == 0  # expiry day
    assert td(cal, dt.date(2026, 4, 20), dt.date(2026, 4, 23)) == 2  # 22 Apr is a holiday
    assert np.isnan(td(cal, dt.date(2026, 4, 20), None))
    e = pd.Timestamp("2026-04-21 09:05", tz=IST)
    f = A.base_features(pd.DataFrame(columns=["ts", "close"]),
                        dict(entry_ts=e, side=1, daily=None, cal=cal, expiry=dt.date(2026, 4, 21), roll=dt.date(2026, 4, 23)))
    assert f["f_dte"] == 0 and f["f_roll_days"] == 1


def test_us_data_flag_only_inside_calendar():
    ctx = dict(side=1, daily=None, us_data_days={dt.date(2026, 4, 9)})
    e = pd.DataFrame(columns=["ts", "close"])
    assert A.base_features(e, dict(ctx, entry_ts=ENTRY))["f_us_data"] == 1
    assert A.base_features(e, dict(ctx, entry_ts=ENTRY + pd.Timedelta(days=1)))["f_us_data"] == 0
    old = pd.Timestamp("2023-04-10 10:00", tz=IST)
    assert np.isnan(A.base_features(e, dict(ctx, entry_ts=old))["f_us_data"])


def test_dst_and_close():
    assert A.us_dst("2026-07-01") and not A.us_dst("2026-01-15")
    assert A.us_dst("2026-03-08") and not A.us_dst("2026-11-01")
    assert A.mcx_close_ts("2026-07-01").strftime("%H:%M") == "23:30"
    assert A.mcx_close_ts("2026-01-15").strftime("%H:%M") == "23:55"


def test_option_features_values():
    t = pd.date_range("2026-04-09 19:00", periods=20, freq="1min", tz=IST)
    opt = pd.DataFrame(dict(ts=t, high=10.2, low=9.8, close=10.0, volume=[0] * 5 + [3] * 15, oi=77.0, iv=33.0))
    f = A.option_features(opt, None, pd.Timestamp("2026-04-09 19:20", tz=IST), 200.0)
    assert f["f_prem"] == 10.0 and f["f_prem_pct"] == 0.05 and f["f_opt_oi"] == 77.0 and f["f_iv"] == 33.0
    assert f["f_opt_spread"] == pytest.approx(0.04)
    assert f["f_opt_noTrade"] == pytest.approx(0.0)  # the last 15 minutes all traded
    f = A.option_features(opt, None, pd.Timestamp("2026-04-09 19:40", tz=IST), 200.0)
    assert f["f_opt_noTrade"] == 1.0  # no row at all in 19:25-19:39: every minute without a trade


# ---------------------------------------------------------------- copied engine pieces
def test_r2_px_at_and_fut_pnl():
    y = pd.DataFrame(dict(open=[1.0, 2.0, 3.0], close=[1.5, 2.5, 3.5]),
                     index=pd.to_datetime(["2026-01-01 00:00", "2026-01-01 01:00", "2026-01-01 05:00"]))
    assert A.r2_px_at(y, pd.Timestamp("2026-01-01 01:00")) == 2.0  # open of the bar starting then
    assert A.r2_px_at(y, pd.Timestamp("2026-01-01 02:30")) == 2.5  # close of the last bar <= 2 h before
    assert np.isnan(A.r2_px_at(y, pd.Timestamp("2026-01-01 03:30")))  # older than 2 h: missing
    g, n = A.r2_fut_pnl(1, 100000.0, 101000.0)
    assert g == 1000.0 and n < g
    g2, n2 = A.r2_fut_pnl(-1, 100000.0, 101000.0)
    assert g2 == -1000.0 and n2 < g2


def fake_market(days, seg, n_per_day=300):
    ts, day, tod, F, sg = [], [], [], [], []
    for d, s in zip(days, seg):
        t = pd.date_range(f"{d} 09:00", periods=n_per_day, freq="1min")
        ts += list(t); day += [np.datetime64(pd.Timestamp(d))] * n_per_day
        tod += list(t.hour * 60 + t.minute); F += list(np.linspace(100, 101, n_per_day)); sg += [s] * n_per_day
    day = np.array(day, dtype="datetime64[ns]")
    m = SimpleNamespace(ts=np.array(ts, dtype="datetime64[ns]"), day=day, tod=np.array(tod), F=np.array(F),
                        seg=np.array(sg), days=np.unique(day))
    m.day_cut = {d: int(np.where(day == d)[0][-11]) for d in m.days}
    return m


def test_r2_c2_skips_roll_missing_us_and_short_days():
    days = ["2026-04-06", "2026-04-07", "2026-04-08", "2026-04-09"]
    m = fake_market(days, seg=[0, 0, 1, 1])
    hr = pd.DataFrame(dict(us_on=[0.01, 0.01, np.nan, -0.02], inr_on=[0.0, np.nan, 0.0, 0.0]), index=pd.to_datetime(days))
    t = A.r2_c2_trades(m, hr, pd.Timestamp("2026-04-01"), pd.Timestamp("2026-04-30"))
    got = {x["date"].date().isoformat(): x["side"] for x in t}
    # 6 Apr: no previous day; 8 Apr: contract changed overnight; 9 Apr: fine (short); 7 Apr: inr missing -> us only
    assert got == {"2026-04-07": 1, "2026-04-09": -1}
    x = [x for x in t if x["side"] == 1][0]
    assert x["p_in"] == m.F[x["e"]] and m.tod[x["e"]] == 9 * 60 + 5
    m2 = fake_market(days, seg=[0, 0, 0, 0], n_per_day=150)  # < 200 minutes a day: skipped
    assert A.r2_c2_trades(m2, hr, pd.Timestamp("2026-04-01"), pd.Timestamp("2026-04-30")) == []


def test_m3_costs_reproduce_a_published_trade():
    M = A.m3lib()
    # research/hunt/m3/results/trades_holdout.csv.gz, 8 Jul 2026 NATURALGAS EVE put: fill 15.85, exit 16.35, net 488.72
    T = pd.DataFrame(dict(sym=["NATURALGAS"], inst=["OPT"], side=[-1], fill=[np.float32(15.85)], exit=[np.float32(16.35)],
                          tod=[1145]))
    c = A.m3_costs(T, M)
    assert c.gross.iloc[0] == pytest.approx(625.0, abs=0.01)
    assert c.net.iloc[0] == pytest.approx(488.723968, abs=0.01)


def m2_leg(n_days=600, roll_every=21, seed=3):
    rng = np.random.default_rng(seed)
    idx = pd.bdate_range("2020-01-01", periods=n_days)
    c = 100 * np.exp(np.cumsum(rng.normal(0.0005, 0.01, n_days)))
    o = c * np.exp(rng.normal(0, 0.003, n_days))
    roll = np.zeros(n_days, bool); roll[::roll_every] = True; roll[0] = False
    g = pd.DataFrame(dict(open=o, high=np.maximum(o, c) * 1.005, low=np.minimum(o, c) * 0.995, close=c, roll=roll,
                          glitch=False), index=idx)
    gap = (g.open - g.close.shift(1)).where(~g.roll, 0.0).fillna(0.0)
    g["adj"] = g.close.iloc[0] + (gap + g.close - g.open).cumsum() - (g.close.iloc[0] - g.open.iloc[0])
    r = ((gap + g.close - g.open) / g.close.shift(1).abs()).fillna(0.0)
    g["vol20"] = r.rolling(20).std(); g["vol60"] = r.rolling(60).std() * np.sqrt(252)
    return g


def test_m2_leg_trades_sum_to_engine_and_roll_exits():
    P, E = A.m2_modules()
    spec = dict(pv=10, lots=1, rt_spread=20, floor=0.08, contract="X")
    g = m2_leg()
    s = E.signal(g, "TSM252")
    tr, dp = A.m2_leg_trades(E, g, "X", s, spec)
    E.SPEC["X"] = spec
    try:
        ref = E.run_futures({"X": g}, {"X": s}, "LS", constrained=False)
    finally:
        E.SPEC.pop("X")
    assert sum(x["gross"] - x["cost"] for x in tr) == pytest.approx(ref.pnl.sum(), rel=1e-9, abs=1e-6)
    assert np.allclose(dp, ref.pnl.values)
    assert {x["why"] for x in tr} <= {"trend_roll", "trend_flip", "trend_flat", "open_at_data_end_mtm"}
    rolls = [x for x in tr if x["why"] == "trend_roll"]
    assert rolls and all(not x["at_open"] and g.roll.values[x["t1"] + 1] for x in rolls)  # out at the close before the roll
    for x in tr:  # no roll inside a trade: the gross is the plain price move
        if x["why"] != "open_at_data_end_mtm":
            assert x["gross"] == pytest.approx(x["side"] * (x["exit"] - x["entry"]) * spec["pv"], rel=1e-9, abs=1e-6)


def test_m2_no_signal_without_history():
    P, E = A.m2_modules()
    g = m2_leg(n_days=200)
    tr, dp = A.m2_leg_trades(E, g, "X", E.signal(g, "TSM252"), dict(pv=10, lots=1, rt_spread=20, floor=0.08))
    assert tr == [] and not dp.any()


def test_truncate_hourly_end():
    y = hourly("2026-04-09 17:00", "2026-04-09 21:00")
    t = A.truncate(y, pd.Timestamp("2026-04-09 19:30", tz=IST), end_offset=pd.Timedelta(hours=1))
    assert t.ts.max() == pd.Timestamp("2026-04-09 18:00", tz=IST)  # the 19:00 bar ends 20:00, after the entry
