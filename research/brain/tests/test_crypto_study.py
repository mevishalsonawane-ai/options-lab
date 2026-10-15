"""C1 study plumbing: BH, AUC, the fixed models, and an end-to-end synthetic run (archive -> cube -> features ->
labels -> study.load) across the holdout date, which must return design rows only."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

import crypto_synth as syn
from research.brain.crypto import config, cube, holdout, labels, study, window
from research.brain.crypto.adapters import ArchiveAdapter, DvolAdapter
from research.brain.crypto.features import store


def test_bh_matches_the_textbook():
    p = np.array([0.01, 0.04, 0.03, 0.20])
    assert study.bh(p) == pytest.approx([0.04, 0.16 / 3, 0.16 / 3, 0.20])     # sorted: .01 .03 .04 .20
    assert study.bh(np.array([0.5]))[0] == 0.5


def test_auc_and_its_bootstrap():
    rng = np.random.default_rng(0)
    y = rng.integers(0, 2, 2000).astype(float)
    assert study.auc(y, y) == 1.0 and study.auc(y, -y) == 0.0
    noise = rng.normal(size=2000)
    assert abs(study.auc(y, noise) - 0.5) < 0.05
    week = np.repeat(np.arange(40), 50)
    assert study.auc_p(y, y + rng.normal(0, 0.5, 2000), week, rng) < 0.01
    assert study.auc_p(y, noise, week, rng) > 0.05


def test_models_recover_a_planted_signal():
    rng = np.random.default_rng(1)
    X = rng.normal(size=(3000, 3))
    X[rng.random(X.shape) < 0.05] = np.nan                        # missing inputs -> training median
    y = 2 * np.nan_to_num(X[:, 0]) + rng.normal(0, 0.1, 3000)
    pred = study.ridge(X[:2000], y[:2000], X[2000:])
    assert np.corrcoef(pred, y[2000:])[0, 1] > 0.9
    yb = (np.nan_to_num(X[:, 1]) + rng.normal(0, 0.5, 3000) > 0).astype(float)
    pb = study.logistic(X[:2000], yb[:2000], X[2000:])
    assert study.auc(yb[2000:], pb) > 0.8 and ((pb > 0) & (pb < 1)).all()


def test_end_to_end_design_rows_stop_before_the_holdout(tmp_path):
    r = syn.raw(45, "2026-02-20", seed=5)                         # synthetic rows on both sides of 1 Apr 2026
    syn.write_archive(tmp_path / "raw", r)
    root = tmp_path / "cache"
    cube.build(root, "BTCUSDT", date(2026, 2, 20), date(2026, 4, 5), adapter=ArchiveAdapter(tmp_path / "raw"),
               dvol=DvolAdapter({}), verbose=False)
    store.build(root, "BTCUSDT", date(2026, 2, 20), date(2026, 4, 5), verbose=False)
    labels.build(root, "BTCUSDT", date(2026, 2, 20), date(2026, 4, 5))
    old = study.WARMUP_DAYS
    study.WARMUP_DAYS = 10                                      # synthetic data is too short for the real warm-up
    try:
        H, Dd, meta = study.load(root, "BTCUSDT")
        WH, WW, wmeta = window.load(root, "BTCUSDT")
    finally:
        study.WARMUP_DAYS = old
    assert len(H) > 200 and len(Dd) > 10
    assert (H.ts + pd.Timedelta(minutes=60) < config.HOLDOUT_START).all()
    assert (Dd.ts + pd.Timedelta(minutes=1440) < config.HOLDOUT_START).all()
    assert Dd.ts.max() == pd.Timestamp("2026-03-30 23:59")     # its window ends 31 Mar 23:59
    assert (H.ts.dt.minute == 59).all() and (Dd.ts.dt.hour == 23).all()
    with pytest.raises(holdout.HoldoutError):
        holdout.assert_design(store.load(root, "BTCUSDT"))     # the store itself holds April rows: guarded

    # Boss's window: 9 IST hours a day, every target inside 00:00-09:00 IST and before the holdout
    assert set(WH.win_hour) == set(range(9)) and (WH.groupby(WH.ts.dt.date.map(str)).size() <= 9).all()
    starts = WH.ts + pd.Timedelta(minutes=1) + pd.Timedelta(minutes=330)        # IST clock of the target's start
    assert (starts.dt.hour <= 8).all() and (starts.dt.minute == 0).all()
    assert (WH.win_hour == starts.dt.hour).all()                               # window hour = IST hour 0..8
    wd = (WH.ts + pd.Timedelta(minutes=331)).dt.weekday >= 5
    assert (WH.weekend == wd.astype(float)).all()                              # weekend of the IST date
    ends = WH.ts + pd.Timedelta(minutes=61)
    ist_end = ends + pd.Timedelta(minutes=330)
    assert ((ist_end.dt.hour < 9) | ((ist_end.dt.hour == 9) & (ist_end.dt.minute == 0))).all()
    assert (WW.ts.dt.hour == 18).all() and (WW.ts.dt.minute == 29).all()
    assert (WW.ts + pd.Timedelta(minutes=window.WIN_MIN) < config.HOLDOUT_START).all()


def test_window_pass_rules_follow_the_prereg():
    base = dict(q=0.01, skill_pct=6.0, auc=0.53, costs={"net_bps": 0.1}, beats_costs=True)
    assert window._passes({**base, "id": "W1-BTCUSDT"}) and not window._passes({**base, "id": "W1-X", "skill_pct": 4.9})
    assert window._passes({**base, "id": "W4-ETHUSDT"})
    assert not window._passes({**base, "id": "W4-X", "costs": {"net_bps": -0.1}})
    assert not window._passes({**base, "id": "W2-X", "auc": 0.519})
    assert not window._passes({**base, "id": "W5-03IST-X", "beats_costs": False})
    assert not window._passes({**base, "id": "W3-X", "q": 0.05})


def test_window_tests_run_on_synthetic_window_hours():
    rng = np.random.default_rng(4)
    n = 9 * 400
    days = pd.date_range("2024-01-01 18:29", periods=400, freq="D")
    ts = pd.DatetimeIndex(np.repeat(days.values, 9)) + pd.to_timedelta(np.tile(np.arange(9) * 60, 400), unit="min")
    r = rng.normal(0, 0.5, n)
    H = pd.DataFrame(dict(ts=ts, win_hour=np.tile(np.arange(9), 400), weekend=(ts.weekday >= 5).astype(float),
                          y_ret_60m=r, y_range_60m=np.abs(r) + 0.3, range_60m_pct=np.abs(np.roll(r, 1)) + 0.3,
                          range_240m_pct=0.8, rv_1440m_pct=2.0, dvol=np.nan))
    t1 = window.test_w1(H, "BTCUSDT")
    assert t1["n"] > 1000 and 0 <= t1["p"] <= 1 and np.isfinite(t1["skill_pct"])
    t5 = window.test_w5(H, "BTCUSDT")
    assert len(t5) == 9 and all(t["n"] == 400 for t in t5) and not any(t["beats_costs"] for t in t5)
