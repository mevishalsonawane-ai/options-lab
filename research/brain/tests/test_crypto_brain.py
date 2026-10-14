"""Crypto brain L1-L3, labels, splits, costs and the holdout, on small synthetic data (crypto_synth.py)."""
from __future__ import annotations

import json
from datetime import date

import numpy as np
import pandas as pd
import pytest

import crypto_synth as syn
from research.brain import labels as india_labels
from research.brain.crypto import clean, config, costs, cube, fetch, guard, holdout, labels
from research.brain.crypto.adapters import ArchiveAdapter, DvolAdapter
from research.brain.markets import MARKETS, region_flags


def _frame(days=3, start="2025-01-03", **kw):
    r = syn.raw(days, start, **kw)
    d = syn.dvol(days, start)
    g = clean.grid(pd.Timestamp(start), pd.Timestamp(start) + pd.Timedelta(days=days) - pd.Timedelta(minutes=1))
    M, info = cube.build_frame(r, d, g)
    return r, d, g, M


# --- the grid: 24x7, UTC, no sessions ---------------------------------------------------------------------------------
def test_grid_is_24x7_with_no_session_gaps():
    r, d, g, M = _frame(3, "2025-01-03")             # Friday, Saturday, Sunday
    assert len(M) == 3 * 1440
    assert (M.ts.diff().dropna() == pd.Timedelta(minutes=1)).all()
    assert sorted(set(M.ts.dt.weekday)) == [4, 5, 6]          # the weekend is on the grid like any day
    assert (M.groupby("day").minute.agg(["min", "max", "count"]).values == [0, 1439, 1440]).all()
    assert set(M.flag) == {"ok"} and set(M.perp_flag) == {"ok"}
    assert MARKETS["crypto"].trades_on(date(2025, 1, 4)) and not MARKETS["nse"].trades_on(date(2025, 1, 4))
    assert len(MARKETS["crypto"].grid(date(2025, 1, 4))) == 1440 and len(MARKETS["nse"].grid(date(2025, 1, 6))) == 375


def test_ist_analysis_day_and_region_clocks():
    t = pd.DatetimeIndex(["2025-01-01 18:29", "2025-01-01 18:30", "2025-01-01 23:59"])
    assert list(MARKETS["crypto"].analysis_day(t).astype(str)) == ["2025-01-01"] * 3
    assert list(MARKETS["crypto-ist"].analysis_day(t).astype(str)) == ["2025-01-01", "2025-01-02", "2025-01-02"]
    # London 08:00 local is 08:00 UTC in January and 07:00 UTC in July (daylight saving), on weekdays only
    jan = region_flags(pd.DatetimeIndex(["2025-01-06 07:59", "2025-01-06 08:00"]))["europe"]
    jul = region_flags(pd.DatetimeIndex(["2025-07-07 06:59", "2025-07-07 07:00"]))["europe"]
    sat = region_flags(pd.DatetimeIndex(["2025-01-04 12:00"]))
    assert list(jan) == [False, True] and list(jul) == [False, True] and not any(v[0] for v in sat.values())


# --- L2: the timestamp-unit bug, gaps, duplicates, stale ---------------------------------------------------------------
def test_timestamp_units_detected_per_value():
    t = pd.Timestamp("2025-01-01 00:00")
    s = int(t.value // 10**9)
    vals = [s, s * 1000, s * 10**6, s * 10**9]                # seconds, ms, us, ns
    assert (clean.to_utc(vals) == t).all()
    mixed = clean.to_utc([s * 1000 - 60_000, s * 10**6])       # 31 Dec in ms, 1 Jan in us: one concat of two files
    assert list(mixed) == [t - pd.Timedelta(minutes=1), t]
    assert clean.to_utc(["2025-01-01 00:05:00"])[0] == t + pd.Timedelta(minutes=5)


def test_archive_adapter_across_the_microsecond_switch(tmp_path):
    r = syn.raw(4, "2024-12-30")                               # Dec in ms, Jan 2025 in us (Binance's real switch)
    syn.write_archive(tmp_path / "raw", r)
    ad = ArchiveAdapter(tmp_path / "raw")
    assert ad.symbols() == ["BTCUSDT"] and ad.months("BTCUSDT") == ["2024-12", "2025-01"]
    assert set(ad.kinds("BTCUSDT")) == {"spot", "perp", "mark", "index", "premium", "funding", "metrics"}
    root = tmp_path / "cache"
    res = cube.build(root, "BTCUSDT", date(2024, 12, 30), date(2025, 1, 2), adapter=ad, dvol=DvolAdapter({}), verbose=False)
    assert res["built"] == 4
    M = cube.load(root, "BTCUSDT")
    assert len(M) == 4 * 1440 and (M.ts.diff().dropna() == pd.Timedelta(minutes=1)).all()
    assert np.allclose(M.close.values, r["spot"].close.values) and np.allclose(M.perp_close, r["perp"].close)
    assert set(M.flag) == {"ok"}                               # no shifted or doubled minutes at 1 Jan 00:00
    man = cube.manifest(root, "BTCUSDT", date(2025, 1, 1))
    assert man["cube_version"] == config.CUBE_VERSION and man["rows"] == 1440 and man["coverage"]["oi"] == 1440
    again = cube.build(root, "BTCUSDT", date(2024, 12, 30), date(2025, 1, 2), adapter=ad, dvol=DvolAdapter({}),
                       verbose=False)
    assert again["built"] == 0 and again["skipped"] == 4      # incremental: nothing changed, nothing rebuilt


def test_gaps_are_marked_not_filled():
    r = syn.raw(2, "2025-01-03")
    hole = (r["spot"].ts >= "2025-01-03 10:00") & (r["spot"].ts < "2025-01-03 10:10")
    r["spot"] = r["spot"][~hole]
    g = clean.grid(pd.Timestamp("2025-01-03"), pd.Timestamp("2025-01-04 23:59"))
    M, info = cube.build_frame(r, None, g)
    gap = (M.ts >= "2025-01-03 10:00") & (M.ts < "2025-01-03 10:10")
    assert (M.flag[gap] == "gap").all() and M.close[gap].isna().all() and M.volume[gap].isna().all()
    assert M.basis[gap].isna().all() and (M.perp_flag[gap] == "ok").all()
    assert (M.flag[~gap] == "ok").all()
    assert len(M) == 2 * 1440                                  # the hole is on the grid, not dropped


def test_duplicates_and_zero_volume():
    r = syn.raw(1, "2025-01-03")
    s = r["spot"]
    dup = s.iloc[[100]]
    conflict = s.iloc[[200]].copy()
    conflict["close"] *= 1.001
    s = pd.concat([s, dup, conflict], ignore_index=True)
    s.loc[300, ["open", "high", "low", "close"]] = s.loc[299, "close"]
    s.loc[300, "volume"] = 0.0
    r["spot"] = s
    g = clean.grid(pd.Timestamp("2025-01-03"), pd.Timestamp("2025-01-03 23:59"))
    M, info = cube.build_frame(r, None, g)
    assert info["spot"]["dup_same"] == 1 and info["spot"]["dup_conflict"] == 1
    assert M.flag[200] == "suspect" and M.flag[100] == "ok"
    assert M.flag[300] == "stale"                              # zero volume: no trade this minute
    assert M.close[200] == r["spot"].close[200]               # the first row is kept, never averaged


def test_suspect_jump_and_premium_may_be_negative():
    r = syn.raw(1, "2025-01-03")
    r["spot"].loc[500, ["close", "high"]] = r["spot"].loc[499, "close"] * 1.2
    g = clean.grid(pd.Timestamp("2025-01-03"), pd.Timestamp("2025-01-03 23:59"))
    M, _ = cube.build_frame(r, None, g)
    assert M.flag[500] == "suspect" and np.isnan(M.basis[500])
    assert (M.premium_close < 0).any() or (M.premium_close > 0).all()
    assert set(M.premium_flag) == {"ok"}                       # a negative premium is data, not a bad price


# --- slow series: funding, metrics, DVOL ---------------------------------------------------------------------------------
def test_funding_alignment_and_time_to_next():
    r, d, g, M = _frame(2, "2025-01-03")
    f = r["funding"].set_index("ts").rate
    at = M.set_index("ts")
    s1 = f[f.index.floor("min") == pd.Timestamp("2025-01-03 08:00")].iloc[0]
    s0 = f[f.index.floor("min") == pd.Timestamp("2025-01-03 00:00")].iloc[0]
    assert at.funding_rate["2025-01-03 07:59"] == s0           # before the 08:00:00.015 settlement: the old rate
    assert at.funding_rate["2025-01-03 08:00"] == s1           # from the row that contains it (known at 08:01)
    assert at.funding_age_min["2025-01-03 08:00"] == 0 and at.funding_age_min["2025-01-03 09:00"] == 60
    assert at.min_to_next_funding["2025-01-03 07:59"] == 0     # this bar ends at the settlement
    assert at.min_to_next_funding["2025-01-03 08:00"] == 479
    assert at.min_to_next_funding["2025-01-03 23:59"] == 0 and at.min_to_next_funding["2025-01-03 16:30"] == 449
    assert set(M.funding_interval_h.dropna()) == {8}
    # every placed value is known by its row's end
    for ts in r["funding"].ts:
        row = ts.floor("min")
        if row in at.index:
            assert row + pd.Timedelta(minutes=1) >= ts


def test_missing_settlement_goes_nan_after_max_age():
    r = syn.raw(2, "2025-01-03")
    r["funding"] = r["funding"][~((r["funding"].ts > "2025-01-03 07:00") & (r["funding"].ts < "2025-01-03 09:00"))]
    g = clean.grid(pd.Timestamp("2025-01-03"), pd.Timestamp("2025-01-04 23:59"))
    M, _ = cube.build_frame(r, None, g)
    at = M.set_index("ts")
    lim = config.MAX_AGE["funding"]
    t_last = pd.Timestamp("2025-01-03 00:00")
    assert np.isfinite(at.funding_rate[t_last + pd.Timedelta(minutes=lim)])
    assert np.isnan(at.funding_rate[t_last + pd.Timedelta(minutes=lim + 1)])     # never carried past its limit
    assert np.isfinite(at.funding_rate["2025-01-03 16:00"])


def test_metrics_and_dvol_alignment():
    r, d, g, M = _frame(2, "2025-01-03")
    at = M.set_index("ts")
    me = r["metrics"].set_index("ts")
    # a metrics row stamped T describes [T, T+5): it goes on row T+5, never earlier (the 13 Oct review found the
    # stamp is the period's START: OI value / OI = the mark price at T+5)
    assert at.oi["2025-01-03 00:05"] == me.oi["2025-01-03 00:00"] and at.metrics_age_min["2025-01-03 00:05"] == 0
    assert at.oi["2025-01-03 00:04"] == me.oi["2025-01-02 23:55"] and at.metrics_age_min["2025-01-03 00:04"] == 4
    for t, v in me.oi.items():
        rows = at.index[(at.oi == v).values & (at.metrics_age_min == 0).values]
        assert all(r >= t + pd.Timedelta(minutes=5) for r in rows)
    dv = d.set_index("ts").dvol
    assert at.dvol["2025-01-03 08:00"] == dv["2025-01-03 07:00"]   # the 07:00 hourly bar closes at 08:00
    assert at.dvol["2025-01-03 07:59"] == dv["2025-01-03 06:00"]


def test_cube_builder_lookahead_guard():
    r, d, g, M = _frame(2, "2025-01-03")
    cuts = [g[0], pd.Timestamp("2025-01-03 07:59"), pd.Timestamp("2025-01-03 08:00"), pd.Timestamp("2025-01-03 23:59"),
            pd.Timestamp("2025-01-04 13:37")]
    assert guard.check_cube(r, d, g, cuts) == []


def test_cube_guard_catches_a_leak(monkeypatch):
    r, d, g, M = _frame(1, "2025-01-03")
    real = clean.ffill_age

    def bfill_age(a, max_age):                                  # a deliberately leaky carry: fills BACKWARDS
        v, age = real(a, max_age)
        return pd.Series(np.asarray(a, float)).bfill().to_numpy(), age
    monkeypatch.setattr(clean, "ffill_age", bfill_age)
    bad = guard.check_cube(r, d, g, [pd.Timestamp("2025-01-03 07:30")])
    assert any(c == "funding_rate" for c, _, _ in bad)


# --- costs -------------------------------------------------------------------------------------------------------------
def test_costs_fees_slippage_funding():
    assert costs.round_trip("BTCUSDT", "perp", 30) == pytest.approx(2 * (0.0005 + 0.0001))
    assert costs.round_trip("BTCUSDT", "spot", 30) == pytest.approx(2 * (0.0010 + 0.0001))
    assert costs.round_trip("SOLUSDT", "perp", 30, stress=2) == pytest.approx(2 * (0.0005 + 0.0004))
    e = pd.Timestamp("2025-01-03 07:30")
    base = 2 * (0.0005 + 0.0001)
    assert costs.round_trip("BTCUSDT", "perp", 60, +1, 1e-4, entry=e) == pytest.approx(base + 1e-4)   # long pays
    assert costs.round_trip("BTCUSDT", "perp", 60, -1, 1e-4, entry=e) == pytest.approx(base - 1e-4)   # short gets
    assert costs.round_trip("BTCUSDT", "perp", 20, +1, 1e-4, entry=e) == pytest.approx(base)          # exits before
    rng = np.random.default_rng(0)
    ents = pd.DatetimeIndex(pd.Timestamp("2025-01-01") + pd.to_timedelta(rng.integers(0, 40000, 200), unit="min"))
    for h in (1, 60, 480, 1440):
        fast = costs.settlements_count(ents, h)
        slow = [costs.settlements_between(t, t + pd.Timedelta(minutes=h)) for t in ents]
        assert list(fast) == slow
    net = costs.net_returns(np.array([0.5, 0.5]), "BTCUSDT", np.array([1, -1]), 60, np.array([0, 0]))
    assert net == pytest.approx([0.5 - 0.12, -0.5 - 0.12])


# --- the locked holdout ------------------------------------------------------------------------------------------------
def test_holdout_is_fixed_and_never_fetched(tmp_path):
    assert config.HOLDOUT_START == pd.Timestamp("2026-04-01")
    assert fetch.months("2026-01", "2026-12") == ["2026-01", "2026-02", "2026-03"]
    assert not [p for p in fetch.plan(["BTCUSDT"], "2026-03", "2026-05") if "2026-04" in p or "2026-05" in p]
    assert any("2026-04" in p for p in fetch.plan(["BTCUSDT"], "2026-04", "2026-04", allow_holdout=True))
    f = pd.DataFrame({"ts": pd.date_range("2026-03-31 22:00", "2026-04-01 01:00", freq="min")})
    d = holdout.design(f, horizon_min=60)
    assert d.ts.max() == pd.Timestamp("2026-03-31 22:59")      # its 60-minute window ends 23:59, before the holdout
    holdout.assert_design(d)
    with pytest.raises(holdout.HoldoutError):
        holdout.assert_design(f)
    got = holdout.read_holdout("idea-x", f, root=tmp_path)
    assert got.ts.min() == config.HOLDOUT_START
    with pytest.raises(holdout.HoldoutError):
        holdout.read_holdout("idea-x", f, root=tmp_path)     # one read per idea
    assert "idea-x" in json.loads((tmp_path / "crypto/study/holdout_reads.json").read_text())


def test_walk_forward_purges_label_windows_and_refuses_holdout():
    ts = pd.date_range("2025-01-01", "2025-03-31 23:59", freq="60min")
    H = 1440
    for tr, te in holdout.walk_forward(ts, n_splits=3, horizon_min=H):
        lo = ts[te].min()
        assert (ts[tr] + pd.Timedelta(minutes=H) < lo).all()   # train strictly before, windows purged
    with pytest.raises(holdout.HoldoutError):
        list(holdout.walk_forward(pd.date_range("2026-03-01", "2026-04-02", freq="h")))


# --- labels ------------------------------------------------------------------------------------------------------------
def test_labels_run_across_midnight_and_stop_at_the_data_end():
    r, d, g, M = _frame(2, "2025-01-03")
    L = labels.compute(M).set_index("ts")
    c = M.set_index("ts").close
    t = pd.Timestamp("2025-01-03 23:30")
    assert L.y_ret_60m[t] == pytest.approx(np.log(c[t + pd.Timedelta(minutes=60)] / c[t]) * 100)   # into 4 Jan
    assert np.isnan(L.y_ret_1440m[pd.Timestamp("2025-01-04 00:00")])       # its window runs past the data
    assert np.isfinite(L.y_ret_1440m[pd.Timestamp("2025-01-03 23:59")])
    assert all(col.startswith("y_") for col in L.columns)
    assert india_labels.HORIZONS == (15, 30, 60)                           # the Indian labels are untouched


def test_labels_next_to_a_missing_day_are_nan_not_shifted():
    r, d, g, M = _frame(3, "2025-01-03")
    hole = M.ts.dt.date == date(2025, 1, 4)
    L = labels.compute(M[~hole]).set_index("ts")               # cube.build skips a day no source covers
    assert len(L) == 2 * 1440
    assert np.isnan(L.y_ret_60m[pd.Timestamp("2025-01-03 23:30")])           # its window falls in the missing day
    assert np.isnan(L.y_ret_1440m[pd.Timestamp("2025-01-03 12:00")])
    c = M.set_index("ts").close
    t = pd.Timestamp("2025-01-05 10:00")
    assert L.y_ret_60m[t] == pytest.approx(np.log(c[t + pd.Timedelta(minutes=60)] / c[t]) * 100)


def test_boss_window_is_midnight_to_nine_ist():
    from research.brain.markets import window_flags
    t = pd.DatetimeIndex(["2025-01-03 18:29", "2025-01-03 18:30", "2025-01-04 03:29", "2025-01-04 03:30",
                          "2025-01-04 12:00"])                  # 23:59, 00:00, 08:59, 09:00, 17:30 IST
    inside, left = window_flags(t, config.WINDOW)
    assert list(inside) == [False, True, True, False, False]
    assert left[1] == 539 and left[2] == 0 and np.isnan(left[0]) and np.isnan(left[3])


# --- F1 refresh (14 Oct 2026): the Drive lake's Parquet layout, Deribit DVOL for both coins, option-trade columns ----
def _write_lake(root, frames, sym="BTCUSDT"):
    """The same rows as the Drive crypto lake stores them: monthly Parquet, epoch ms, `count` for trades."""
    def kl(d):
        ot = d.ts.values.astype("datetime64[ms]").astype(np.int64)
        return pd.DataFrame({"open_time": ot, "open": d.open, "high": d.high, "low": d.low, "close": d.close,
                             "volume": d.volume, "close_time": ot + 59_999, "quote_volume": d.quote_volume,
                             "count": d.trades.astype(int), "taker_buy_volume": d.taker_buy_volume,
                             "taker_buy_quote_volume": d.taker_buy_volume * d.close})
    where = {"spot": "spot/klines_1m", "perp": "futures_um/klines_1m", "mark": "futures_um/markprice_1m",
             "index": "futures_um/indexprice_1m"}
    tag = {"spot": "klines_1m", "perp": "klines_1m", "mark": "markprice_1m", "index": "indexprice_1m"}
    for k, folder in where.items():
        d = frames[k]
        for per, part in d.groupby(d.ts.dt.to_period("M")):
            p = root / folder / sym / str(per.year) / f"{sym}-{tag[k]}-{per}.parquet"
            p.parent.mkdir(parents=True, exist_ok=True)
            kl(part).to_parquet(p, index=False)
    f = frames["funding"]
    for per, part in f.groupby(f.ts.dt.to_period("M")):
        p = root / "futures_um/funding" / sym / str(per.year) / f"{sym}-funding-{per}.parquet"
        p.parent.mkdir(parents=True, exist_ok=True)
        pd.DataFrame(dict(calc_time=part.ts.values.astype("datetime64[ms]").astype(np.int64),
                          funding_interval_hours=part.interval_h, last_funding_rate=part.rate)).to_parquet(p, index=False)
    m = frames["metrics"]
    for per, part in m.groupby(m.ts.dt.to_period("M")):
        p = root / "futures_um/metrics" / sym / str(per.year) / f"{sym}-metrics-{per}.parquet"
        p.parent.mkdir(parents=True, exist_ok=True)
        pd.DataFrame(dict(create_time=part.ts.values.astype("datetime64[ms]").astype(np.int64),
                          sum_open_interest=part.oi, sum_open_interest_value=part.oi_value,
                          count_toptrader_long_short_ratio=part.top_ls_accounts,
                          sum_toptrader_long_short_ratio=part.top_ls_positions, count_long_short_ratio=part.ls_accounts,
                          sum_taker_long_short_vol_ratio=part.taker_ls_vol)).to_parquet(p, index=False)


def test_lake_parquet_layout_builds_the_same_cube_as_the_archive(tmp_path):
    r = syn.raw(4, "2024-12-30")
    syn.write_archive(tmp_path / "arch", r)
    _write_lake(tmp_path / "lake", r)
    a = ArchiveAdapter(tmp_path / "arch")
    b = ArchiveAdapter(tmp_path / "lake")
    assert set(b.kinds("BTCUSDT")) == {"spot", "perp", "mark", "index", "funding", "metrics"}   # the lake has no premium
    assert b.months("BTCUSDT") == ["2024-12", "2025-01"]
    built = {}
    for name, ad in (("arch", a), ("lake", b)):
        root = tmp_path / f"cache_{name}"
        cube.build(root, "BTCUSDT", date(2024, 12, 30), date(2025, 1, 2), adapter=ad, dvol=DvolAdapter({}), verbose=False)
        built[name] = cube.load(root, "BTCUSDT")
    A, B = built["arch"], built["lake"]
    assert len(A) == len(B) == 4 * 1440
    for c in ["close", "perp_close", "mark_close", "index_close", "funding_rate", "oi", "ls_accounts", "trades", "basis_bps"]:
        assert np.allclose(A[c], B[c], equal_nan=True), c
    assert B.premium_close.isna().all() and set(B.premium_flag) == {"gap"}
    # both layouts in one root (lake klines + archive premium): every kind found, nothing doubled
    _write_lake(tmp_path / "arch" / "lake", r)
    both = ArchiveAdapter(tmp_path / "arch")
    raw = both.load("BTCUSDT", pd.Timestamp("2024-12-31"), pd.Timestamp("2024-12-31 23:59"))
    M, _ = cube.build_frame(raw, None, clean.grid(pd.Timestamp("2024-12-31"), pd.Timestamp("2024-12-31 23:59")))
    assert set(M.flag) == {"ok"} and M.premium_close.notna().all()


def test_dvol_adapter_reads_the_options_lake_for_both_coins(tmp_path):
    t = pd.date_range("2025-01-01", periods=48, freq="h")
    for coin, base in (("BTC", 50.0), ("ETH", 70.0)):
        p = tmp_path / "deribit" / "dvol" / f"{coin}_1h.parquet"
        p.parent.mkdir(parents=True, exist_ok=True)
        pd.DataFrame(dict(timestamp=t.values.astype("datetime64[ms]").astype(np.int64), open=base, high=base,
                          low=base, close=base + np.arange(48))).to_parquet(p, index=False)
    ad = DvolAdapter(options_root=tmp_path)
    assert ad.available("BTCUSDT") and ad.available("ETHUSDT") and not ad.available("SOLUSDT")
    d = ad.load("ETHUSDT", pd.Timestamp("2025-01-01 05:00"), pd.Timestamp("2025-01-01 10:00"))
    row = d[d.ts == pd.Timestamp("2025-01-01 07:00")].iloc[0]
    assert row.avail == pd.Timestamp("2025-01-01 08:00") and row.dvol == 77.0      # known when its hour ends
    assert DvolAdapter({}).available("BTCUSDT") is False                           # an explicit {} means none


def test_option_hours_values_and_hour_end_stamp():
    h0 = pd.Timestamp("2025-01-02 10:00")
    ms = lambda t: int(pd.Timestamp(t).value // 10**6)                              # noqa: E731
    rows = []
    # 24 hours of one call buy (1 coin) and one put sell (0.5 coin) a hour, before and inside the hour of interest
    for k in range(30):
        t = h0 - pd.Timedelta(hours=26) + pd.Timedelta(hours=k, minutes=10)
        rows.append(dict(timestamp=ms(t), iv=60.0, index_price=100.0, strike=100.0, expiry_ts=ms(t + pd.Timedelta(days=7)),
                         option_type="C", direction="buy", amount=1.0))
        rows.append(dict(timestamp=ms(t), iv=60.0, index_price=100.0, strike=100.0, expiry_ts=ms(t + pd.Timedelta(days=7)),
                         option_type="P", direction="sell", amount=0.5))
    # skew: puts at 90% IV 70, calls at 110% IV 55, over the 4 hours to 11:00; one IV-0 print that must be dropped
    for j in range(6):
        t = h0 - pd.Timedelta(hours=2) + pd.Timedelta(minutes=20 * j)
        rows.append(dict(timestamp=ms(t), iv=70.0, index_price=100.0, strike=90.0, expiry_ts=ms(t + pd.Timedelta(days=10)),
                         option_type="P", direction="buy", amount=0.0))
        rows.append(dict(timestamp=ms(t), iv=55.0, index_price=100.0, strike=110.0, expiry_ts=ms(t + pd.Timedelta(days=10)),
                         option_type="C", direction="buy", amount=0.0))
    rows.append(dict(timestamp=ms(h0 + pd.Timedelta(minutes=30)), iv=0.0, index_price=100.0, strike=100.0,
                     expiry_ts=ms(h0 + pd.Timedelta(days=5)), option_type="C", direction="buy", amount=0.0))
    tr = pd.DataFrame(rows).sort_values("timestamp")
    from research.brain.crypto.adapters import option_hours
    out = option_hours(tr, h0 - pd.Timedelta(hours=5), h0 + pd.Timedelta(hours=2)).set_index("ts")
    r = out.loc[h0 + pd.Timedelta(hours=1)]                    # the hour [10:00, 11:00) is stamped 11:00
    assert r.opt_atm_iv == 60.0 and r.opt_n_1h == 2           # the IV-0 print is not a trade here
    assert r.opt_skew == pytest.approx(15.0)
    assert r.opt_pc_24h == pytest.approx(0.5)
    assert r.opt_flow_24h == pytest.approx((24 * 1.0 + 24 * 0.5) / (24 * 1.5))      # call buys + put sells = bullish
    # point in time: deleting every trade at or after 11:00 leaves every row stamped <= 11:00 unchanged
    cut = option_hours(tr[tr.timestamp < ms(h0 + pd.Timedelta(hours=1))], h0 - pd.Timedelta(hours=5),
                       h0 + pd.Timedelta(hours=2)).set_index("ts")
    keep = out.index <= h0 + pd.Timedelta(hours=1)
    pd.testing.assert_frame_equal(out[keep], cut[keep])
    # the first trade is at 08:10 the day before: the hour 07:00-08:00 (row 08:00) has a full 24 hours behind it,
    # the hour 06:00-07:00 (row 07:00) does not and is NaN, not a short-window ratio
    assert np.isfinite(out.loc[h0 - pd.Timedelta(hours=2)].opt_pc_24h)
    assert np.isnan(out.loc[h0 - pd.Timedelta(hours=3)].opt_pc_24h)


def test_cube_guard_with_option_columns_and_carry_limit():
    days, start = 3, "2025-01-03"
    r = syn.raw(days, start)
    g = clean.grid(pd.Timestamp(start), pd.Timestamp(start) + pd.Timedelta(days=days) - pd.Timedelta(minutes=1))
    from research.brain.crypto.adapters import option_hours
    tr = syn.option_trades(days, start)
    tr = tr[~((tr.timestamp >= pd.Timestamp("2025-01-04 03:00").value // 10**6)
              & (tr.timestamp < pd.Timestamp("2025-01-04 09:00").value // 10**6))]          # a 6-hour hole
    r["options"] = option_hours(tr, g[0] - pd.Timedelta(hours=1), g[-1])
    d = syn.dvol(days, start)
    M, _ = cube.build_frame(r, d, g)
    at = M.set_index("ts")
    assert at.opt_age_min["2025-01-04 02:00"] == 0 and np.isfinite(at.opt_atm_iv["2025-01-04 02:00"])
    assert at.opt_age_min["2025-01-04 02:59"] == 59                        # the 01:00-02:00 hour, carried
    assert np.isfinite(at.opt_atm_iv["2025-01-04 05:00"])                   # 03:00 row (hour 02-03) + 120 min
    assert np.isnan(at.opt_atm_iv["2025-01-04 05:01"])                      # never carried past 2 hours
    cuts = [pd.Timestamp("2025-01-03 10:59"), pd.Timestamp("2025-01-03 11:00"), pd.Timestamp("2025-01-04 04:30")]
    assert guard.check_cube(r, d, g, cuts) == []


def test_truncated_ema_matches_a_direct_window_and_skips_gaps():
    from research.brain.crypto.features.trend_f import TRUNC, tema
    rng = np.random.default_rng(5)
    x = 100 + np.cumsum(rng.normal(0, 0.1, 3000))
    span = 20
    a, L = 2 / (span + 1), TRUNC * span
    w = a * (1 - a) ** np.arange(L)
    direct = np.array([np.dot(w, x[t - np.arange(L)]) / w.sum() for t in range(L - 1, 3000)])
    got = tema(x, span)
    assert np.isnan(got[:L - 1]).all() and np.allclose(got[L - 1:], direct, rtol=1e-10)
    y = x.copy()
    y[1500:1510] = np.nan
    gy = tema(y, span)
    fin = np.isfinite(y[1505 - np.arange(L)])
    exp = np.dot(w[fin], y[1505 - np.arange(L)][fin]) / w[fin].sum()
    assert gy[1505] == pytest.approx(exp, rel=1e-10)                         # a gap drops out of the average
    z = x.copy()
    z[:2000] = np.nan
    tz = tema(z, span)
    assert np.isnan(tz[2003]) and np.isfinite(tz[2010])     # 4 closes = 33% of the weight: NaN; 11 = 67%: a value
