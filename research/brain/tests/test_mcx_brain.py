"""MCX Data Brain (MB1) tests on seeded synthetic data: L2 clean, L3 cube, L4 features (look-ahead guard for every
feature, canaries, warm-up independence), labels, the holdout lock, events and the tensor store."""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcx_synth as S  # noqa: E402

from research.brain import guard as india_guard  # noqa: E402
from research.brain.mcx import clean, config, cube, events, features as F, guard, holdout, labels, tensors  # noqa: E402


@pytest.fixture(scope="module")
def src():
    sp = S.spot_minutes(30)
    sf, qa = clean.spot_frame(S.option_rows(sp))
    ex = clean.option_expiries(sf)
    fu = S.fut_minutes(sp, from_day=S.days(30)[20])
    return dict(raw=sp, sf=sf, qa=qa, ex=ex, fut=fu, daily=S.daily_bars(sp))


@pytest.fixture(scope="module")
def M(src):
    return cube.build_frame(src["sf"], src["fut"], "GOLDM", src["ex"])


# ------------------------------------------------------------------------------------------------ L2 clean
def test_spot_frame_values(src):
    sp, sf = src["raw"], src["sf"]
    assert sf.ts.dt.tz is None and len(sf) == len(sp)
    j = sf.set_index("ts").join(sp.set_index("ts"), rsuffix="_raw")
    assert np.allclose(j.close, j.close_raw, atol=1.0)                         # median of spot +-1
    assert np.allclose(j.atm_iv, j.atm_iv_raw, atol=1e-3)                      # mean of call (+0.5) and put (-0.5)
    assert np.allclose(j.straddle, j.straddle_raw, rtol=1e-9)                  # ATM call + put
    assert (sf.pcr_oi > 0).all() and (sf.opt_vol >= 0).all()


def test_spot_frame_drops_outside_session_and_empty():
    sp = S.spot_minutes(2)
    o = S.option_rows(sp)
    late = o.iloc[:6].copy()
    late["ts"] = late.ts + pd.Timedelta(hours=15)                             # after the close
    sf, qa = clean.spot_frame(pd.concat([o, late]))
    assert qa["outside_session"] == 6 and len(sf) == len(sp)
    e, qa0 = clean.spot_frame(pd.DataFrame())
    assert len(e) == 0 and qa0["rows"] == 0


def test_option_expiry_detected(src):
    assert src["ex"] == [S.days(30)[12]]


# ------------------------------------------------------------------------------------------------ L3 cube
def test_cube_grid_follows_us_time(M):
    n = M.groupby("day").size()
    assert n[date(2025, 10, 31)] == 870 and n[date(2025, 11, 3)] == 895        # US winter time from 2 Nov 2025
    assert M.ts.dt.hour.min() == 9 and M.ts.dt.minute[M.ts.dt.hour == 9].min() == 0


def test_cube_gaps_not_filled(src, M):
    have = set(src["sf"].ts)
    miss = M[~M.ts.isin(have)]
    assert len(miss) > 0 and miss.close.isna().all() and (miss.flag == "gap").all()


def test_cube_segments_roll_after_expiry(src, M):
    d = S.days(30)
    seg = M.groupby("day").seg.first()
    assert seg[d[12]] == "S0" and seg[d[13]] == "S1"
    roll_days = set(M[M.roll].day)
    assert roll_days == {d[13]}


def test_cube_source_from_previous_day():
    sp = S.spot_minutes(6)
    sf, _ = clean.spot_frame(S.option_rows(sp))
    dd = S.days(6)
    sf = sf[sf.ts.dt.date != dd[3]]                                            # option book missing on day 3
    fu = S.fut_minutes(sp)
    m = cube.build_frame(sf, fu, "GOLDM", [])
    src = m.groupby("day").src.first()
    assert src[dd[3]] == "spot" and src[dd[4]] == "fut" and src[dd[5]] == "spot"
    assert m[m.day == dd[3]].close.isna().all()                               # chosen at the open: no switch mid-day
    assert m.groupby("day").seg.first()[dd[4]] == "F2025-12-05"


def test_cube_futures_only_symbol(src):
    m = cube.build_frame(None, src["fut"], "COPPER", [])
    assert (m.src == "fut").all() and np.allclose(m.close.dropna(), m.fc.dropna())
    assert m.atm_iv.isna().all()


def test_cube_option_context_carried_at_most_15(src):
    sf = src["sf"].copy()
    d0 = S.days(1)[0]
    cut = sf.ts.dt.date.eq(d0) & (sf.ts.dt.hour * 60 + sf.ts.dt.minute).between(600, 660)
    sf.loc[cut, ["atm_iv", "straddle", "pcr_oi", "opt_vol"]] = np.nan
    m = cube.build_frame(sf, None, "GOLDM", src["ex"])
    tod = m.ts.dt.hour * 60 + m.ts.dt.minute
    day0 = m.day == d0
    assert m[day0 & tod.between(600, 614)].atm_iv.notna().all()
    assert m[day0 & tod.between(615, 660)].atm_iv.isna().all()
    assert m[day0].opt_age.max() <= config.OPT_MAX_AGE


def test_cube_events_minutes():
    cal = events.calendar(date(2025, 10, 1), date(2025, 11, 30))
    assert cal["eia"][date(2025, 10, 8)] == 20 * 60          # 10:30 New York in summer time = 20:00 IST
    assert cal["eia"][date(2025, 11, 5)] == 21 * 60          # winter time = 21:00 IST
    assert cal["cpi"][date(2025, 10, 24)] == 18 * 60         # 08:30 New York = 18:00 IST
    sp = S.spot_minutes(5, start="2025-10-06")
    sf, _ = clean.spot_frame(S.option_rows(sp))
    m = cube.build_frame(sf, None, "CRUDEOIL", [])
    row = m[(m.ts == pd.Timestamp("2025-10-08 19:59"))]
    assert float(row.ev_eia.iloc[0]) == 0.0                  # the bar ending at 20:00
    assert m[m.day == date(2025, 10, 7)].ev_eia.isna().all()


def test_cube_builder_lookahead(src):
    cuts = india_guard.default_cuts(src["sf"].ts[src["sf"].ts.dt.date >= S.days(30)[19]], 4)
    assert guard.check_cube(src["sf"], src["fut"], "GOLDM", src["ex"], cuts) == []


# ------------------------------------------------------------------------------------------------ L4 features
def test_feature_count_and_names(M, src):
    f = F.compute(M, "GOLDM", None, src["daily"])
    assert len(F.REGISTRY) >= 50 and list(f.columns) == ["ts"] + F.names()
    assert not any(n.startswith("y_") for n in F.names())


def test_every_feature_point_in_time(M, src):
    sub = M[M.day >= S.days(30)[8]].reset_index(drop=True)          # covers a roll, the fut join, US time change
    cuts = india_guard.default_cuts(sub.ts[sub.day >= S.days(30)[12]], 5)
    bad = guard.check_features(sub, "GOLDM", cuts, src["daily"])
    assert bad == {}, bad


def test_guard_catches_canaries(M):
    sub = M[M.day >= S.days(30)[25]].reset_index(drop=True)
    cuts = india_guard.default_cuts(sub.ts, 3)

    def fn(m, c, u, n):
        out = F.compute(m, "GOLDM", ["ret_5m"])
        cl = m.sort_values("ts").close.to_numpy(float)
        out["canary_next"] = np.r_[cl[1:], np.nan]
        out["canary_dayhigh"] = m.sort_values("ts").groupby(m.ts.dt.date).close.transform("max").to_numpy()
        return out
    bad = india_guard.check_features(sub, None, "GOLDM", cuts, None, 7, compute_fn=fn, perturb=guard.PERTURB)
    assert "canary_next" in bad and "canary_dayhigh" in bad and "ret_5m" not in bad


def test_features_warmup_independent(M, src):
    full = F.compute(M, "GOLDM", None, src["daily"])
    late = M[M.day >= S.days(30)[3]].reset_index(drop=True)
    part = F.compute(late, "GOLDM", None, src["daily"])
    last = S.days(30)[-2:]
    a = full[full.ts.dt.date.isin(last)].reset_index(drop=True)
    b = part[part.ts.dt.date.isin(last)].reset_index(drop=True)
    for c in F.names():
        assert india_guard._same(a[c], b[c]), c


def test_features_respect_segments(M):
    f = F.compute(M, "GOLDM", ["ret_60m", "gap_open", "dist_prev_high"])
    first = M.index[M.roll][0]
    assert np.isnan(f.ret_60m.iloc[first]) and np.isnan(f.gap_open.iloc[first])
    assert np.isnan(f.dist_prev_high.iloc[first])


def test_feature_values_simple():
    ts = pd.date_range("2025-10-06 09:00", periods=870, freq="min")
    c = 100 * np.exp(np.arange(870) * 1e-4)
    m = pd.DataFrame({"ts": ts, "close": c, "flag": "ok", "seg": "S0"})
    f = F.compute(m, "GOLDM", ["ret_5m", "range_30m", "pos_60m", "er_60m", "min_since_open", "min_to_close"])
    assert f.ret_5m.iloc[100] == pytest.approx(0.05, rel=1e-6)
    assert f.range_30m.iloc[100] == pytest.approx((np.exp(29e-4) - 1) * 100, rel=1e-6)
    assert f.pos_60m.iloc[100] == 1.0 and f.er_60m.iloc[100] == pytest.approx(1.0)
    assert f.min_since_open.iloc[0] == 0 and f.min_to_close.iloc[-1] == 0


def test_daily_features_use_earlier_days_only(M, src):
    d = src["daily"].copy()
    f1 = F.compute(M, "GOLDM", ["d_range_prev_pct"], d)
    day = S.days(30)[10]
    d.loc[pd.to_datetime(d.date).dt.date == day, "high"] *= 1.5          # change only day 10's own bar
    f2 = F.compute(M, "GOLDM", ["d_range_prev_pct"], d)
    on = (M.day == day).to_numpy()
    nxt = (M.day == S.days(30)[11]).to_numpy()
    assert np.allclose(f1.d_range_prev_pct[on], f2.d_range_prev_pct[on])
    assert not np.allclose(f1.d_range_prev_pct[nxt], f2.d_range_prev_pct[nxt])


def test_catalogue_up_to_date():
    assert F.CATALOG.read_text() == F.catalogue_md() + "\n"


# ------------------------------------------------------------------------------------------------ labels
def test_labels_values_and_bounds():
    ts = pd.date_range("2025-10-06 09:00", periods=870, freq="min")
    c = 100 + np.arange(870) * 0.01
    m = pd.DataFrame({"ts": ts, "close": c, "flag": "ok", "seg": "S0"})
    y = labels.compute(m)
    assert y.y_range_15m.iloc[0] == pytest.approx((c[15] - c[1]) / c[0] * 100)
    assert y.y_ret_15m.iloc[0] == pytest.approx(np.log(c[15] / c[0]) * 100)
    assert np.isnan(y.y_range_60m.iloc[-60]) and np.isfinite(y.y_range_60m.iloc[-61])
    assert not any(not k.startswith("y_") for k in y.columns if k != "ts")


def test_labels_tolerate_few_gaps_not_many():
    ts = pd.date_range("2025-10-06 09:00", periods=870, freq="min")
    c = np.full(870, 100.0)
    c[5:10] = np.nan                                                    # 5 of 15 missing in the first window
    m = pd.DataFrame({"ts": ts, "close": c, "flag": np.where(np.isnan(c), "gap", "ok"), "seg": "S0"})
    y = labels.compute(m)
    assert np.isnan(y.y_range_15m.iloc[0])                              # 10/15 < 80%
    assert np.isfinite(y.y_range_60m.iloc[0])                           # 55/60 >= 80%


def test_labels_never_cross_segment(M):
    y = labels.compute(M)
    first = M.index[M.roll][0]
    assert y.y_range_60m.iloc[first - 30: first].isna().all()


def test_labels_design_only(M):
    m = M.copy()
    m["ts"] = m.ts + pd.Timedelta(days=255)                              # shift into Jun-Jul 2026
    y = labels.design(m)
    assert y.ts.max().date() <= config.DESIGN_END


# ------------------------------------------------------------------------------------------------ holdout
def test_holdout_split_and_lock(tmp_path):
    assert config.part(date(2026, 6, 23)) == "design" and config.part(date(2026, 6, 24)) == "embargo"
    assert config.part(date(2026, 7, 1)) == "holdout"
    with pytest.raises(holdout.HoldoutError):
        holdout.assert_design(pd.DataFrame({"ts": [pd.Timestamp("2026-06-24 10:00")]}))
    holdout.assert_design(pd.DataFrame({"ts": [pd.Timestamp("2026-06-23 23:00")]}))
    log = tmp_path / "reads.json"
    holdout.read_holdout("idea-x", tmp_path, log)
    with pytest.raises(holdout.HoldoutError):
        holdout.read_holdout("idea-x", tmp_path, log)
    assert "idea-x" in json.loads(log.read_text())


# ------------------------------------------------------------------------------------------------ tensors
def test_tensor_store_roundtrip(M, src, tmp_path):
    import pyarrow.feather as pf
    f = F.compute(M, "GOLDM", None, src["daily"])
    names = F.names()
    tensors.write_symbol(tmp_path, "GOLDM", f, names)
    X = np.load(tmp_path / "GOLDM.f32.npy", mmap_mode="r")
    assert X.dtype == np.float32 and X.shape == (len(M), len(names))
    assert np.allclose(X, f[names].to_numpy(np.float32), equal_nan=True)
    t = pf.read_table(tmp_path / "GOLDM.arrow", memory_map=True)
    assert t.num_rows == len(M) and np.allclose(t.column("ret_5m").to_numpy(zero_copy_only=False),
                                                X[:, names.index("ret_5m")], equal_nan=True)
    tensors.write_joint(tmp_path, {"GOLDM": f, "SILVERM": f.iloc[100:].reset_index(drop=True)}, names)
    J = np.load(tmp_path / "joint.f32.npy", mmap_mode="r")
    assert J.shape == (2, len(M), len(names)) and np.isnan(J[1, :100]).all()
    man = tensors.manifest(tmp_path, {"signature": F.signature()})
    assert "GOLDM.f32.npy" in man["files"] and len(man["files"]["GOLDM.f32.npy"]["sha256"]) == 64


def test_window_view_matches_slices():
    a = np.arange(50 * 3, dtype=np.float32).reshape(50, 3)
    ends = np.array([9, 20, 49])
    v = tensors.window_view(a, ends, 10)
    assert v.shape == (3, 10, 3)
    for i, e in enumerate(ends):
        assert np.array_equal(v[i], a[e - 9: e + 1])


def test_valid_ends_and_encoder_point_in_time(M, src):
    f = F.compute(M, "GOLDM", None, src["daily"])
    ends = tensors.valid_ends(M)
    seg = M.seg.to_numpy()
    for e in ends[::500]:
        assert len(set(seg[e - 120: e + 1])) == 1
    rows = ends[(M.ts.iloc[ends].dt.minute % 15 == 0).to_numpy()][-40:]
    enc = tensors.encoder_inputs(M, f, rows)
    assert enc.shape == (len(rows), 24, 6) and enc.dtype == np.float32 and np.isfinite(enc).all()
    t = M.ts.iloc[rows[10]]
    m2 = M.copy()
    late = (m2.ts > t).to_numpy()
    m2.loc[late, "close"] *= 1.07
    m2.loc[late, "atm_iv"] += 9
    f2 = F.compute(m2, "GOLDM", None, src["daily"])
    enc2 = tensors.encoder_inputs(m2, f2, rows)
    assert np.allclose(enc[:11], enc2[:11]) and not np.allclose(enc[11:], enc2[11:])
