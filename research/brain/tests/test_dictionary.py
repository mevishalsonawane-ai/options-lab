"""Data-dictionary fields: volume / VWAP, volume profile, levels, indicators, candles, options extras, breadth.

Volume-based fields run on the lake-format fixture (conftest.to_lake: real prices, SYNTHETIC test-only futures volume,
two futures contracts so the roll is exercised). Everything else also runs on the release fixture in
test_lookahead.py.
"""

import numpy as np
import pandas as pd
import pytest

from research.brain import cube, levels, profile
from research.brain.features import PENDING, REGISTRY, compute, load_all, store
from research.brain.features.registry import Ctx, Feature
from research.brain.guard import check_cube, check_features

from conftest import DAYS, NEAR, NEXT, NEXT_PREMIUM
from test_lookahead import _cuts

VOLUME_FAMILIES = ("volume", "profile", "levels")


def _names(*fams):
    load_all()
    return sorted(n for n, f in REGISTRY.items() if f.family in fams)


# --- futures, roll, precision ----------------------------------------------------------------------------------------
def test_roll_day_switches_to_next_month(lake_built):
    M = lake_built["M"]
    by = M.groupby("day").agg(exp=("fut_expiry", "first"), roll=("fut_roll", "first"))
    before = by.index < pd.Timestamp(NEAR)
    assert (by.exp[before] == pd.Timestamp(NEAR)).all() and not by.roll[before].any()
    assert by.exp[pd.Timestamp(NEAR)] == pd.Timestamp(NEXT) and by.roll[pd.Timestamp(NEAR)]
    # on the roll day the futures bars are the next month's (the fixture puts it NEXT_PREMIUM above the near month)
    b = M.groupby("day").basis.median()
    assert b[pd.Timestamp(NEAR)] - b[pd.Timestamp(DAYS[2])] > NEXT_PREMIUM - 100
    assert set(M.vol_precision) <= {"estimate", "none"} and (M.vol_precision == "estimate").mean() > 0.95


def test_release_has_no_volume_and_says_so(built):
    M = built["M"]
    assert M.fut_volume.isna().all() and (M.vol_precision == "none").all()
    F = compute(M, built["C"], "BANKNIFTY", _names("volume", "profile"))
    assert F.drop(columns="ts").isna().all().all()             # no volume -> NaN, never zero


def test_cube_with_futures_is_point_in_time(lake):
    dd = {d.day: d for d in lake.load("BANKNIFTY", DAYS[0], DAYS[-1])}
    for d in (DAYS[1], NEAR):
        g = pd.date_range(f"{d} 09:15", periods=375, freq="min")
        bad = check_cube(dd[d], [g[0], g[14], g[200], g[373]], set(dd))
        assert not bad, bad[:10]


# --- point in time on the data with volume ---------------------------------------------------------------------------
def test_volume_profile_level_features_point_in_time(lake_built):
    names = _names(*VOLUME_FAMILIES)
    F = compute(lake_built["M"], lake_built["C"], "BANKNIFTY", names)
    for n in ("vwap", "rel_volume_20d", "poc_dist_pct", "week_poc_dist_pct", "prev_poc_dist_pct", "lvl_above_dist_pct"):
        if n != "rel_volume_20d":
            assert F[n].notna().mean() > 0.2, n                 # really computed here (not all NaN)
    bad = check_features(lake_built["M"], lake_built["C"], "BANKNIFTY", _cuts(lake_built["M"]), names=names)
    assert not bad, f"reading the future: {sorted(bad)}"


# --- VWAP --------------------------------------------------------------------------------------------------------------
def test_vwap_and_bands_match_definition(lake_built):
    M = lake_built["M"].sort_values("ts").reset_index(drop=True)
    F = compute(M, lake_built["C"], "BANKNIFTY", ["vwap", "vwap_z", "vwap_up1_dist_pct", "vwap_band",
                                                   "avwap_prev_high_dist_pct", "fut_volume_day"])
    d = M[M.day == pd.Timestamp(DAYS[1])]
    i = d.index[200]
    s = d.loc[:i]
    ok = s.fut_flag.isin(["ok", "stale"]) & s.fut_volume.notna()
    tp = ((s.fut_high + s.fut_low + s.fut_close) / 3)[ok]
    v = s.fut_volume[ok]
    vw = (tp * v).sum() / v.sum()
    sd = np.sqrt((v * (tp - vw) ** 2).sum() / v.sum())
    assert np.isclose(F.vwap[i], vw, rtol=1e-10)
    assert np.isclose(F.vwap_z[i], (M.fut_close[i] - vw) / sd, rtol=1e-8)
    assert np.isclose(F.vwap_up1_dist_pct[i], (M.fut_close[i] / (vw + sd) - 1) * 100, rtol=1e-8)
    assert np.isclose(F.fut_volume_day[i], v.sum())
    assert F.vwap_band.dropna().isin([-2, -1, 0, 1, 2]).all()
    # the anchored VWAP from the previous day's high exists after day 1 and is NaN on the roll day (other contract)
    a = F.avwap_prev_high_dist_pct
    assert a[M.day == pd.Timestamp(DAYS[0])].isna().all() and a[M.day == pd.Timestamp(DAYS[1])].notna().mean() > 0.9
    assert a[M.day == pd.Timestamp(NEAR)].isna().all()


def test_relative_volume_same_minute_last_20_sessions(lake_built):
    # 24 sessions made by repeating the four fixture days with new dates; a feature only sees earlier sessions
    M = lake_built["M"]
    parts = []
    for k in range(24):
        d = M[M.day == pd.Timestamp(DAYS[k % 4])].copy()
        new = pd.Timestamp("2025-01-01") + pd.Timedelta(days=k)
        d["ts"] = new + (d.ts - d.day)
        d["day"] = new
        d["fut_volume"] = d.fut_volume * (1 + k / 10)
        d["fut_expiry"] = pd.Timestamp(NEXT)
        parts.append(d)
    X = pd.concat(parts, ignore_index=True)
    F = compute(X, None, "BANKNIFTY", ["rel_volume_20d"])
    V = X.pivot(index="day", columns="minute", values="fut_volume")
    day, minute = pd.Timestamp("2025-01-01") + pd.Timedelta(days=22), 100
    want = V.loc[day, minute] / V.loc[:day].iloc[-21:-1][minute].mean()
    got = F.rel_volume_20d[(X.day == day) & (X.minute == minute)].iloc[0]
    assert np.isclose(got, want)
    assert F.rel_volume_20d[X.day < pd.Timestamp("2025-01-16")].isna().all()   # < 15 earlier sessions


# --- volume profile ----------------------------------------------------------------------------------------------------
def test_profile_math_on_a_toy():
    b0, H = profile.spread([100, 100, 110], [110, 100, 130], [105, 100, 120], [10, 5, 20], 10.0)
    assert b0 == 10 and np.isclose(H.sum(), 35)
    assert np.allclose(H[0], [10, 0, 0, 0]) and np.allclose(H[1], [5, 0, 0, 0]) and np.allclose(H[2], [0, 10, 10, 0])
    poc, lo, hi = profile.poc_va(np.array([[1, 2, 10, 3, 1, 0, 0]], float))
    assert poc[0] == 2 and (lo[0], hi[0]) == (2, 3)                # 13 of 17 >= 70%; the bigger neighbour first
    poc, lo, hi = profile.poc_va(np.array([[0, 0, 5, 0, 5, 0]], float))
    assert poc[0] == 2 and (lo[0], hi[0]) == (2, 4)                # empty bins inside are crossed, ties go up


def test_final_profile_only_after_close_developing_intraday(lake_built):
    ctx = Ctx(lake_built["M"], None, "BANKNIFTY")
    T = profile.table(ctx)
    day, dev, week = T[T.kind == "day"], T[T.kind == "dev"], T[T.kind == "week"]
    assert len(day) == len(DAYS) and len(week) == len(DAYS) and (T.precision == "estimate").all()
    for _, r in day.iterrows():
        last_bar = lake_built["M"][lake_built["M"].day == r.day].ts.max()
        assert r.valid_from == r.day + pd.Timedelta(hours=15, minutes=30) > last_bar
        d = dev[(dev.day == r.day)].sort_values("valid_from").iloc[-1]   # developing at the last bar == final
        assert (d.poc, d.vah, d.val) == (r.poc, r.vah, r.val)
        assert r["val"] < r["poc"] < r["vah"] and r["hvn"] and np.isclose(sum(r["hist"]), r["volume"])
    # read "as of" an intraday minute: today's final profile is not there yet, yesterday's is
    t = pd.Timestamp(f"{DAYS[2]} 11:00")
    got = profile.asof(T, t, "day")
    assert set(got.day.dt.date) == {DAYS[0], DAYS[1]}
    assert (profile.asof(T, t, "dev").valid_from <= t).all()
    # week to date: 24-27 Mar is one ISO week, but the roll on 27 Mar starts a new contract's week
    w = week.set_index(week.day.dt.date)
    assert w.start[DAYS[2]] == pd.Timestamp(DAYS[0]) and w.start[NEAR] == pd.Timestamp(NEAR)


def test_guard_catches_a_feature_reading_todays_final_profile(lake_built):
    def leaky(ctx):
        P = profile.day_profiles(ctx)["final"]
        poc = np.array([x["poc"] if x is not None else np.nan for x in P])
        return ctx.fut()["c"] / poc[ctx.dayid] - 1                 # today's FINAL POC: known only at the close
    REGISTRY["_canary_final_poc"] = Feature("_canary_final_poc", "profile", 1, ("x",), "0m", "x", leaky)
    try:
        bad = check_features(lake_built["M"], lake_built["C"], "BANKNIFTY", _cuts(lake_built["M"])[:5],
                             names=["_canary_final_poc", "poc_dist_pct", "prev_poc_dist_pct"])
    finally:
        del REGISTRY["_canary_final_poc"]
    assert set(bad) == {"_canary_final_poc"}


# --- levels ------------------------------------------------------------------------------------------------------------
def test_levels_table(lake_built):
    M = lake_built["M"]
    L = levels.table(Ctx(M, None, "BANKNIFTY"))
    assert set(L.type) <= set(levels.LEVEL_TYPES)
    later = L[L.day > pd.Timestamp(DAYS[0])]
    for t in ("pdh", "pdl", "pdc", "pivot", "r1", "s3", "cpr_tc", "cpr_bc", "round_major", "round_minor", "or15_high",
              "swing_high", "swing_low", "prev_poc", "prev_vah", "prev_val", "oi_call", "oi_put"):
        assert t in set(later.type), t
    rm = L[L.type == "round_major"].price
    assert (rm % 500 == 0).all() and (L[L.type == "round_minor"].price % 100 == 0).all()
    assert not (L[L.type == "round_minor"].price % 500 == 0).any()
    o = L[L.type == "or15_high"]
    assert (o.valid_from == o.day + pd.Timedelta(hours=9, minutes=29)).all()
    assert (L.valid_from >= L.day + pd.Timedelta(hours=9, minutes=15)).all()
    assert (L.formula_version == levels.FORMULA_VERSION).all()
    assert (L[L.type.isin(["prev_poc", "prev_vah", "prev_val"])].precision == "estimate").all()
    # the max-OI strikes of 26 Mar are for the 27 Mar expiry, still live on the 27th; the first session has no history
    assert (L[L.day == pd.Timestamp(DAYS[0])][["held", "broke"]] == 0).all().all()
    assert L.held.sum() + L.broke.sum() > 0


def test_level_counts_come_from_earlier_sessions_only(lake_built):
    M = lake_built["M"]
    full = levels.table(Ctx(M, None, "BANKNIFTY"))
    # delete the last session: the earlier sessions' rows (incl. held/broke) do not change
    cut = levels.table(Ctx(M[M.day < pd.Timestamp(DAYS[-1])], None, "BANKNIFTY"))
    a = full[full.day < pd.Timestamp(DAYS[-1])].reset_index(drop=True)
    pd.testing.assert_frame_equal(a, cut)
    # scramble the last session after its open: its own held/broke/strength do not change (only earlier days count)
    X = M.copy()
    late = (X.day == pd.Timestamp(DAYS[-1])) & (X.minute > 0)
    rng = np.random.default_rng(1)
    for c in ("open", "high", "low", "close"):
        X.loc[late, c] = X.loc[late, c] * rng.uniform(0.97, 1.03, late.sum())
    X.loc[late, "high"] = X.loc[late, ["open", "high", "low", "close"]].max(axis=1)
    X.loc[late, "low"] = X.loc[late, ["open", "high", "low", "close"]].min(axis=1)
    pert = levels.table(Ctx(X, None, "BANKNIFTY"))
    k = ["type", "price"]
    f0 = full[(full.day == pd.Timestamp(DAYS[-1])) & (full.valid_from == full.day + pd.Timedelta(hours=9, minutes=15))]
    p0 = pert[(pert.day == pd.Timestamp(DAYS[-1])) & (pert.valid_from == pert.day + pd.Timedelta(hours=9, minutes=15))]
    j = f0.merge(p0, on=k, suffixes=("", "_p"))
    assert len(j) > 20 and (j.held == j.held_p).all() and (j.broke == j.broke_p).all()


# --- the rest ------------------------------------------------------------------------------------------------------------
def test_indicators_and_candles_sane(built):
    F = compute(built["M"], built["C"], "BANKNIFTY", _names("indicators", "candles"))
    assert F.rsi14.dropna().between(0, 100).all() and F.adx14.dropna().between(0, 100).all()
    assert F.supertrend_dir.dropna().isin([-1, 1]).all()
    for c in ("trend_15m", "trend_1h"):
        assert F[c].dropna().isin([-1, 0, 1]).all()
    assert F.trend_1d.isna().all()                                 # needs 21 earlier sessions: honest NaN here
    for tf in (5, 15):
        assert F[f"c{tf}m_upper_wick"].dropna().between(0, 1).all()
        assert F[f"c{tf}m_engulf"].dropna().isin([-1, 0, 1]).all()
    # a bar is visible only from its last minute: nothing before 09:19 for 5-minute bars
    first = pd.DatetimeIndex(F.ts).time < pd.Timestamp("09:19").time()
    assert F.c5m_lower_wick[first].isna().all()


def test_options_extras(built):
    F = compute(built["M"], built["C"], "BANKNIFTY", _names("options"))
    assert F.iv_rank_252.isna().all() and F.iv_pct_252.isna().all()       # needs 252 earlier sessions
    d = F.greek_atm_delta_c.dropna()
    assert d.between(0.3, 0.7).mean() > 0.9
    assert np.allclose((F.greek_atm_delta_c - F.greek_atm_delta_p).dropna(), 1.0)   # q = 0: call - put delta = 1
    assert (F.greek_atmm2_delta_c.dropna() > F.greek_atmp2_delta_c.dropna().reindex(F.greek_atmm2_delta_c.dropna().index)
            .fillna(-1)).all()
    assert (F.pcr_vol_day.dropna() > 0).all()
    assert F.em_vix_1d_pct.notna().mean() > 0.99


def test_iv_rank_uses_only_earlier_sessions(built):
    M = built["M"]
    parts = []
    for k in range(256):
        d = M[M.day == pd.Timestamp(DAYS[1])].copy()
        new = pd.Timestamp("2024-01-01") + pd.Timedelta(days=k)
        d["ts"], d["day"] = new + (d.ts - d.day), new
        d["atm_iv"] = 0.10 + 0.001 * k                               # rising IV: today is a new high
        parts.append(d)
    X = pd.concat(parts, ignore_index=True)
    F = compute(X, None, "BANKNIFTY", ["iv_rank_252", "iv_pct_252"])
    last = X.day == X.day.max()
    assert F.iv_rank_252[X.day < pd.Timestamp("2024-09-09")].isna().all()
    assert np.isclose(F.iv_pct_252[last].iloc[10], 100.0)
    assert F.iv_rank_252[last].iloc[10] > 100                       # above the past window's high: rank > 100


def test_pending_breadth_is_listed_not_computed(built):
    load_all()
    assert {"breadth_ad_banknifty", "breadth_above_vwap_nifty", "heavyweight_contrib_banknifty"} <= set(PENDING)
    F = compute(built["M"], built["C"], "BANKNIFTY")
    assert not set(PENDING) & set(F.columns)
    from research.brain.features.__main__ import FEATURES_MD
    assert "## Pending data" in FEATURES_MD.read_text()


def test_store_writes_precision(tmp_path, lake, adapter):
    for ad, want in ((lake, "estimate"), (adapter, "none")):
        root = tmp_path / ad.name
        cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[ad], verbose=False)
        store.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], verbose=False)
        F = store.load(root, "BANKNIFTY")
        assert "precision" in F and (F.precision == want).mean() > 0.95


def test_l3_tables_written_incrementally(tmp_path, lake):
    from research.brain import tables
    cube.build(tmp_path, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[lake], verbose=False)
    r1 = tables.build(tmp_path, "BANKNIFTY", DAYS[0], DAYS[-1], verbose=False)
    r2 = tables.build(tmp_path, "BANKNIFTY", DAYS[0], DAYS[-1], verbose=False)
    assert all(r1[w]["built"] == len(DAYS) for w in tables.TABLES) and all(r2[w]["built"] == 0 for w in tables.TABLES)
    P, L = tables.load(tmp_path, "BANKNIFTY", "profiles"), tables.load(tmp_path, "BANKNIFTY", "levels")
    assert set(P.kind) == {"day", "week", "dev"} and P.day.nunique() == len(DAYS) and len(L) > 100
    M, _ = cube.load(tmp_path, "BANKNIFTY")
    want = levels.table(Ctx(M, None, "BANKNIFTY"))
    pd.testing.assert_frame_equal(L.reset_index(drop=True), want, check_dtype=False)


def _sessions(M, n, start="2025-01-06"):
    """n sessions made by repeating the fixture days on consecutive business days (one contract throughout)."""
    out = []
    for k, new in enumerate(pd.bdate_range(start, periods=n)):
        d = M[M.day == pd.Timestamp(DAYS[k % 3])].copy()       # 24-26 Mar: no roll, same contract
        d["ts"], d["day"] = new + (d.ts - d.day), new
        d["fut_volume"] = d.fut_volume * (1 + (k % 7) / 10)
        d["expiry"] = new + pd.Timedelta(days=3)
        out.append(d)
    return pd.concat(out, ignore_index=True)


def test_declared_lookbacks_are_enough(lake_built):
    """Warm-up independence for the new families on 30 sessions: a feature computed with its declared lookback of
    sessions before the day equals the same feature computed with 6 more (what an incremental build relies on)."""
    from research.brain.features.registry import warmup_sessions
    X = _sessions(lake_built["M"], 30)
    days = sorted(X.day.unique())
    target = days[-1]
    fams = ("volume", "profile", "indicators", "candles")
    names = [n for n in _names(*fams) if warmup_sessions([n]) + 6 < len(days)]
    assert len(names) > 50
    bad = []
    for n in names:
        w = warmup_sessions([n])
        a = compute(X[X.day >= days[-1 - w]], None, "BANKNIFTY", [n])
        b = compute(X[X.day >= days[-7 - w]], None, "BANKNIFTY", [n])
        a, b = a[a.ts.dt.normalize() == target][n].values, b[b.ts.dt.normalize() == target][n].values
        if not np.allclose(a, b, rtol=1e-9, atol=1e-12, equal_nan=True):
            bad.append(n)
    assert not bad, bad


def test_precision_mismatch_refused():
    from research.brain import split
    F = pd.DataFrame(dict(precision=["estimate"] * 4 + ["exact"] * 2 + ["none"] * 2))
    split.check_precision(F, [0, 1, 2], [3, 6, 7])                 # estimate vs estimate (+ no volume): fine
    with pytest.raises(ValueError):
        split.check_precision(F, [0, 1, 2], [4, 5])                # trained on estimates, tested on exact
