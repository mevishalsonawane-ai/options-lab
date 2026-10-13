"""Data Brain v1 (14 Oct 2026): the lake's Drive layout, month-by-month reads, lake-vs-release priority, index-only
indices, the new range features (look-ahead guard + warm-up), the rest-of-day labels, the locked holdout, and the L5
models on tiny synthetic sets. Fixture data only; nothing is downloaded."""
import json
import pickle
import shutil
from datetime import date

import numpy as np
import pandas as pd
import pytest
import sklearn.ensemble  # noqa: F401  (loaded first, so threadpool_limits below reaches its OpenMP pool)

from research.brain import cube, holdout, labels, split
from research.brain.adapters.lake import LakeAdapter
from research.brain.features import compute, load_all, store
from research.brain.guard import check_features
from research.brain.models import train

from conftest import DAYS, to_lake
from test_dictionary import _sessions

@pytest.fixture(autouse=True)
def _small_models(monkeypatch):
    """Tiny sets: fewer boosting rounds and one thread (the real settings are in PREREG.md and train.GBM)."""
    from threadpoolctl import threadpool_limits
    monkeypatch.setattr(train, "GBM", dict(train.GBM, max_iter=40))
    with threadpool_limits(1):
        yield


NEW = ["range_30m_pct", "range_60m_pct", "tod_logrange_30m", "tod_logrange_60m", "er_day"]


# --- adapters ---------------------------------------------------------------------------------------------------------
def _drive_layout(tmp):
    """The lake fixture laid out like gdrive:IraAlgo-data-lake: one sub-folder per dataset, CATALOG.json at the root."""
    flat = tmp / "flat"
    flat.mkdir()
    to_lake(flat)
    drive = tmp / "drive"
    for sub, pat in (("index_1m", "index_1m_*"), ("options_rolling_1m", "opt_*"), ("fut_1m_live_index", "fut_1m_*")):
        (drive / sub).mkdir(parents=True)
        for p in flat.glob(pat):
            shutil.copy(p, drive / sub / p.name)
    shutil.copy(flat / "CATALOG.json", drive / "CATALOG.json")
    return flat, drive


def test_drive_layout_gives_the_same_cube_as_the_flat_lake(tmp_path, lake_built, monkeypatch):
    import research.brain.adapters.lake as lk
    monkeypatch.setattr(lk, "CATALOG", tmp_path / "no-repo-catalog.json")     # use the catalog in the lake folder
    _, drive = _drive_layout(tmp_path)
    a = LakeAdapter(drive)
    assert a.catalog == drive / "CATALOG.json" and a.available("BANKNIFTY")
    assert a.sessions("BANKNIFTY") == DAYS and a.full_sessions("BANKNIFTY") == DAYS
    root = tmp_path / "cache"
    cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[a], verbose=False)
    M, C = cube.load(root, "BANKNIFTY")
    R, RC = lake_built["M"], lake_built["C"]
    for col in ("close", "vix", "flag", "fut_close", "atm_iv"):
        assert np.array_equal(M[col].astype(str).values, R[col].astype(str).values), col
    assert np.array_equal(C.ltp.values, RC.ltp.values, equal_nan=True)


def test_reads_are_month_by_month_and_cover_both_months(tmp_path):
    """Sessions across a month end: load() yields every day, each read filtered to its own month."""
    d = tmp_path / "lake"
    (d / "index_1m").mkdir(parents=True)
    rows = []
    for day in pd.bdate_range("2025-01-29", "2025-02-04"):
        ts = pd.date_range(day + pd.Timedelta(hours=9, minutes=15), periods=375, freq="min", tz="Asia/Kolkata")
        rows.append(pd.DataFrame(dict(ts=ts, open=100.0, high=101.0, low=99.0, close=100.0 + np.arange(375) / 100,
                                      volume=0)))
    pd.concat(rows).to_parquet(d / "index_1m" / "index_1m_FINNIFTY_2025.parquet")
    (d / "CATALOG.json").write_text(json.dumps(dict(release_tag="t", files=[])))
    a = LakeAdapter(d, catalog=d / "CATALOG.json")
    seen = []
    orig = a._read
    a._read = lambda name, frm, to: (seen.append((frm, to)), orig(name, frm, to))[1]
    got = [x.day for x in a.load("FINNIFTY", date(2025, 1, 29), date(2025, 2, 4))]
    assert got == [x.date() for x in pd.bdate_range("2025-01-29", "2025-02-04")]
    assert all(frm.month == to.month for frm, to in seen)
    assert {(frm.month, to.month) for frm, to in seen} == {(1, 1), (2, 2)}
    assert all(len(x.index) == 375 and x.options.empty for x in a.load("FINNIFTY", date(2025, 1, 29), date(2025, 1, 31)))


def test_index_only_lake_does_not_take_days_from_the_release(tmp_path, adapter, monkeypatch):
    """--source auto: a lake with the index but no options for a day leaves that day to the release (with its chain);
    once the lake has the options, the lake owns it."""
    flat, _ = _drive_layout(tmp_path)
    no_opt = tmp_path / "no_opt"
    shutil.copytree(flat, no_opt)
    (no_opt / "opt_BANKNIFTY_WEEK_2025.parquet").unlink()
    lk = LakeAdapter(no_opt, catalog=no_opt / "CATALOG.json")
    assert lk.sessions("BANKNIFTY") == DAYS and lk.full_sessions("BANKNIFTY") == []
    root = tmp_path / "c1"
    cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[lk, adapter], verbose=False)
    assert {cube.manifest(root, "BANKNIFTY", d)["sources"]["index"].split(":")[0] for d in DAYS} == {"release"}
    full = LakeAdapter(flat, catalog=flat / "CATALOG.json")
    cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[full, adapter], verbose=False)
    assert {cube.manifest(root, "BANKNIFTY", d)["sources"]["index"].split(":")[0] for d in DAYS} == {"lake"}
    # and with nothing else, the index-only lake still builds every day (no chain)
    root2 = tmp_path / "c2"
    cube.build(root2, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[lk], verbose=False)
    M, C = cube.load(root2, "BANKNIFTY")
    assert M.day.nunique() == len(DAYS) and (C.empty or (C.flag == "gap").all())


def test_index_without_options_builds_and_computes_features(tmp_path):
    """FINNIFTY / MIDCPNIFTY / SENSEX / BANKEX in the lake have the index only: the cube and every feature still work,
    option features are NaN, never invented."""
    d = tmp_path / "lake"
    (d / "index_1m").mkdir(parents=True)
    rng = np.random.default_rng(1)
    rows = []
    for day in pd.bdate_range("2025-03-03", periods=12):
        ts = pd.date_range(day + pd.Timedelta(hours=9, minutes=15), periods=375, freq="min", tz="Asia/Kolkata")
        c = 60000 * np.exp(np.cumsum(rng.normal(0, 4e-4, 375)))
        rows.append(pd.DataFrame(dict(ts=ts, open=c, high=c * 1.0004, low=c * 0.9996, close=c, volume=0)))
    pd.concat(rows).to_parquet(d / "index_1m" / "index_1m_BANKEX_2025.parquet")
    (d / "CATALOG.json").write_text("{}")
    a = LakeAdapter(d, catalog=d / "CATALOG.json")
    root = tmp_path / "cache"
    res = cube.build(root, "BANKEX", date(2025, 3, 1), date(2025, 3, 31), adapter_list=[a], verbose=False)
    assert res["built"] == 12
    M, C = cube.load(root, "BANKEX")
    F = compute(M, C, "BANKEX")
    assert len(F) == 12 * 375
    assert np.isfinite(F.range_30m_pct).sum() > 0 and np.isfinite(F.tod_logrange_30m).sum() > 0
    assert F.filter(like="pcr").isna().all().all()
    from research.brain import tables                     # no futures volume: an empty profile table, no crash
    r = tables.build(root, "BANKEX", date(2025, 3, 1), date(2025, 3, 31), verbose=False)
    assert all(v["built"] == 12 for v in r.values())
    assert tables.load(root, "BANKEX", "profiles").empty and len(tables.load(root, "BANKEX", "levels")) > 0


# --- the new features: look-ahead guard and warm-up -------------------------------------------------------------------
def test_new_range_features_are_point_in_time(lake_built):
    X = _sessions(lake_built["M"], 24)
    last = sorted(X.day.unique())[-1]
    cuts = [last + pd.Timedelta(hours=9, minutes=m) for m in (15, 44, 100, 200, 300, 374)]
    assert not check_features(X, None, "BANKNIFTY", cuts, names=NEW)


def test_new_features_declared_lookback_is_enough(lake_built):
    from research.brain.features.registry import warmup_sessions
    X = _sessions(lake_built["M"], 40)
    days = sorted(X.day.unique())
    for n in NEW:
        w = warmup_sessions([n])
        a = compute(X[X.day >= days[-1 - w]], None, "BANKNIFTY", [n])
        b = compute(X[X.day >= days[-8 - w]], None, "BANKNIFTY", [n])
        a, b = a[a.ts.dt.normalize() == days[-1]][n].values, b[b.ts.dt.normalize() == days[-1]][n].values
        assert np.allclose(a, b, equal_nan=True), n


def test_tod_logrange_matches_its_definition(lake_built):
    X = _sessions(lake_built["M"], 25)
    F = compute(X, None, "BANKNIFTY", ["tod_logrange_30m"])
    days = sorted(X.day.unique())
    t = days[-1] + pd.Timedelta(hours=10)                       # 10:00 bar: window 10:01 .. 10:30 in earlier days
    want = []
    for d in days[-21:-1]:
        g = X[X.day == d].set_index("ts")
        ok = g.flag.isin(["ok", "stale"])
        h, lo, c = g.high.where(ok), g.low.where(ok), g.close.where(ok)
        a = d + pd.Timedelta(hours=10, minutes=1)
        w = slice(a, a + pd.Timedelta(minutes=29))
        want.append(np.log((h[w].max() - lo[w].min()) / c[d + pd.Timedelta(hours=10)]))
    assert np.isclose(F.set_index("ts").tod_logrange_30m[t], np.nanmean(want))


# --- labels and the holdout -------------------------------------------------------------------------------------------
def test_rest_of_day_labels(built):
    M = built["M"]
    Lb = labels.compute(M)
    assert list(Lb.columns) == labels.COLUMNS
    m = M.sort_values("ts").reset_index(drop=True)
    d = m[m.day == m.day.iloc[0]]
    i = d.index[29]                                             # 09:44
    rest = d.loc[i + 1:]
    ok = rest.flag.isin(["ok", "stale"])
    rng = rest.high[ok].max() - rest.low[ok].min()
    c = d.close[i]
    assert np.isclose(Lb.y_range_rest[i], rng / c * 100)
    assert np.isclose(Lb.y_er_rest[i], abs(d.close.iloc[-1] - c) / rng)
    assert np.isnan(Lb.y_range_rest[d.index[-1]])               # nothing after the last bar


def test_labels_are_built_for_design_days_only(tmp_path, adapter, monkeypatch):
    root = tmp_path / "c"
    cube.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], adapter_list=[adapter], verbose=False)
    monkeypatch.setattr(holdout, "DESIGN_END", DAYS[1])
    labels.build(root, "BANKNIFTY", DAYS[0], DAYS[-1])
    got = sorted(p.stem for p in (root / "labels" / "BANKNIFTY").glob("*/*.parquet"))
    assert got == [str(DAYS[0]), str(DAYS[1])]


def test_holdout_is_refused():
    assert holdout.DESIGN_END == date(2026, 4, 13) and holdout.HOLDOUT_START == date(2026, 4, 27)
    holdout.assert_design(pd.Series(pd.to_datetime(["2026-04-13 15:29"])))
    for bad in ("2026-04-20 09:15", "2026-04-27 09:15"):
        with pytest.raises(ValueError):
            holdout.assert_design(pd.Series(pd.to_datetime(["2025-01-01", bad])))


# --- L5 models on tiny synthetic sets ---------------------------------------------------------------------------------
def _synthetic_reg(n_days=100, seed=0):
    """Log range = usual level + 0.8 * a 'busy' signal + noise; the time-of-day baseline sees only the level."""
    rng = np.random.default_rng(seed)
    rows = []
    for d in pd.bdate_range("2023-01-02", periods=n_days):
        busy = rng.normal()
        for m in train.TARGETS["range30"]["minutes"]:
            ts = d + pd.Timedelta(hours=9, minutes=15 + m)
            rows.append(dict(ts=ts, day=ts.date(), busy=busy + rng.normal(0, .2), noise=rng.normal(),
                             tod_logrange_30m=-1.5, log_range_30m=-1.5 + 0.3 * busy + rng.normal(0, .5),
                             log_range_60m=-1.2 + rng.normal(0, .5), log_vix=np.log(14.0), precision="none",
                             target=-1.5 + 0.8 * busy + rng.normal(0, .2)))
    X = pd.DataFrame(rows)
    X["base_tod"], X["base_last"], X["vix_h"] = X.tod_logrange_30m, X.log_range_30m, np.nan
    return X


def test_walk_forward_model_beats_baselines_on_a_learnable_signal():
    X = _synthetic_reg()
    r = train.evaluate(X, "range30", train.walk(X, "range30"))
    assert r["sessions"] == 80 and r["rows"] == 80 * len(train.TARGETS["range30"]["minutes"])
    assert r["skill"] > 0.3 and r["p"] < 0.01 and r["best_baseline"] in ("b_tod", "b_last", "b_vix")
    assert len(r["calibration"]) == 10 and sum(c["n"] for c in r["calibration"]) == r["rows"]
    assert all(f["train_rows"] > 0 for f in r["folds"])


def test_walk_forward_trains_strictly_before_and_purges():
    X = _synthetic_reg(60)
    for tr, te in split.walk_forward(X.ts, n_splits=train.N_SPLITS, min_train_blocks=train.MIN_TRAIN_BLOCKS,
                                     horizon=pd.Timedelta(minutes=30)):
        assert X.ts.iloc[tr].max() + pd.Timedelta(minutes=30) < X.ts.iloc[te].min()


def test_no_signal_is_not_predictable():
    X = _synthetic_reg(seed=3)
    X["target"] = -1.5 + np.random.default_rng(9).normal(0, .5, len(X))   # pure noise around the usual level
    X["busy"] = np.random.default_rng(10).normal(size=len(X))
    r = train.evaluate(X, "range30", train.walk(X, "range30"))
    r["q"] = r["p"]
    assert r["skill"] < 0.05 and train.verdict(r, "reg") != "predictable"


def test_classifier_and_calibration_on_synthetic():
    rng = np.random.default_rng(4)
    days = pd.bdate_range("2023-01-02", periods=300)
    sig = rng.normal(size=len(days))
    X = pd.DataFrame(dict(ts=days + pd.Timedelta(hours=9, minutes=44), day=days.date, signal=sig,
                          er_day=sig + rng.normal(0, .3, len(days)), day_range_pct=rng.random(len(days)),
                          gap_pct=0.0, log_vix=np.log(15) + rng.normal(0, .1, len(days)), log_range_30m=-1.0,
                          dte_days=3.0, precision="none"))
    X["target"] = (sig + rng.normal(0, .5, len(days)) > 0).astype(float)
    X["prev_state"] = X.target.shift(1)
    r = train.evaluate(X, "daytype", train.walk(X, "daytype"))
    assert r["auc"]["gbm"] > 0.7 and r["auc"]["lin"] > 0.75 and r["p"] < 0.05
    assert 0.3 < r["base_rate"] < 0.7 and len(r["calibration"]) == 10


def test_bh():
    q = train.bh([0.01, 0.04, 0.03, 0.5])
    assert np.allclose(q, [0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])     # 0.03: min(0.03*4/2, 0.04*4/3)


def test_end_to_end_on_the_fixture_store(tmp_path, lake_built, monkeypatch):
    """Feature store + labels -> design_frame -> walk-forward -> metrics JSON + a model file that loads and predicts."""
    from research.brain.features import price
    monkeypatch.setattr(price._tod_range, "__defaults__", (20, 1))           # 4 fixture days: 1 earlier session is enough
    root = tmp_path / "c"
    shutil.copytree(lake_built["root"] / "cube", root / "cube")
    store.build(root, "BANKNIFTY", DAYS[0], DAYS[-1], verbose=False)
    labels.build(root, "BANKNIFTY", DAYS[0], DAYS[-1])
    X = train.design_frame(root, "BANKNIFTY", "range30")
    mins = ((X.ts.dt.hour - 9) * 60 + X.ts.dt.minute - 15).unique()
    assert set(mins) <= set(train.TARGETS["range30"]["minutes"]) and X.day.nunique() <= len(DAYS)
    assert not any(c.startswith("y_") for c in train.feature_cols(X))
    # every baseline is on the target's scale (log of a percent): a units slip shows as an error of ~log(100) = 4.6
    for b in ("base_tod", "base_last"):
        assert np.nanmedian(np.abs(X[b] - X.target)) < 1.0, b
    out = tmp_path / "m.json"
    res = train.run(root, ["BANKNIFTY"], out=out, verbose=False)            # 4 days: too little, says so
    assert all(t.get("note") == "too little design data" for t in res["tests"])
    monkeypatch.setattr(train, "N_SPLITS", 2)
    monkeypatch.setattr(train, "MIN_TRAIN_BLOCKS", 1)
    monkeypatch.setattr(train, "MIN_SESSIONS_PER_BLOCK", 1)
    monkeypatch.setattr(train, "GBM", dict(train.GBM, min_samples_leaf=5, max_iter=20))
    res = train.run(root, ["BANKNIFTY"], out=out, verbose=False)
    t = {x["target"]: x for x in res["tests"]}
    assert t["range30"]["rows"] > 0 and "p" in t["range30"] and "q" in t["range30"]
    assert json.loads(out.read_text())["holdout_read"] is False
    with open(root / "models" / train.VERSION / "BANKNIFTY_range30.pkl", "rb") as f:
        m = pickle.load(f)
    assert m["design_end"] == "2026-04-13" and np.isfinite(m["gbm"].predict(X)).all()


# --- refresh ----------------------------------------------------------------------------------------------------------
def test_refresh_coverage_report(built, tmp_path):
    from research.brain import refresh
    c = refresh.coverage(built["root"], ["BANKNIFTY", "NIFTY"], tmp_path / "no-lake")
    b = c["indices"]["BANKNIFTY"]
    assert b["days"] == len(DAYS) and b["first"] == str(DAYS[0]) and b["sources"] == {"release": len(DAYS)}
    assert b["chain_days"] == len(DAYS) and b["design_days"] == len(DAYS) and b["holdout_days"] == 0
    assert c["indices"]["NIFTY"] == dict(days=0)
    md = refresh.coverage_md(c)
    assert "| BANKNIFTY | 4 |" in md and "| NIFTY | 0 |" in md and "fut_1m_live_mcx" in md


def test_refresh_syncs_only_what_the_brain_reads(tmp_path, monkeypatch):
    from research.brain import refresh
    from research.brain.adapters import release
    calls = []
    monkeypatch.setattr(refresh.subprocess, "run", lambda cmd, **k: (calls.append(cmd),
                                                                       type("R", (), dict(returncode=0, stderr=""))())[1])
    monkeypatch.setattr(release, "fetch", lambda names, dest: calls.append(("fetch", tuple(names))))
    refresh.sync(tmp_path / "lake", tmp_path / "raw", ["BANKNIFTY", "FINNIFTY"])
    cmd = calls[0]
    assert cmd[:4] == ["rclone", "copy", refresh.DRIVE, str(tmp_path / "lake")]
    inc = [cmd[i + 1] for i, x in enumerate(cmd) if x == "--include"]
    assert inc == refresh.SYNC_INCLUDE and not any("mcx" in x or "equity" in x or "bhav" in x for x in inc)
    assert [c for c in calls if c[0] == "fetch"] == [("fetch", tuple(release.files_for("BANKNIFTY")))]
    calls.clear()
    refresh.sync(tmp_path / "lake", tmp_path / "raw", ["NIFTY"], lake_too=False)
    assert all(c[0] == "fetch" for c in calls)
