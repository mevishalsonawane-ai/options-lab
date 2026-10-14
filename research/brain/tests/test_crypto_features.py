"""Crypto L4: every feature is point-in-time (the look-ahead guard), the guard can fail, warm-up independence, the
catalogue is current, and no feature module reads labels. Synthetic data from crypto_synth.py (36 days, so the
30-day funding z-score and the 14-day ATR are exercised)."""
from __future__ import annotations

import re
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

import crypto_synth as syn
from research.brain.crypto import adapters, clean, cube, guard
from research.brain.crypto.features import REGISTRY, load_all
from research.brain.crypto.features import registry as R
from research.brain.crypto.features.__main__ import FEATURES_MD

DAYS = 36
START = "2025-01-01"


@pytest.fixture(scope="module")
def minutes():
    r = syn.raw(DAYS, START, seed=3)
    # a gap and a missing funding settlement, so the guard also runs over holes
    r["spot"] = r["spot"][~((r["spot"].ts >= "2025-02-03 05:00") & (r["spot"].ts < "2025-02-03 05:20"))]
    r["funding"] = r["funding"][r["funding"].ts.dt.floor("min") != pd.Timestamp("2025-02-02 16:00")]
    g = clean.grid(pd.Timestamp(START), pd.Timestamp(START) + pd.Timedelta(days=DAYS) - pd.Timedelta(minutes=1))
    tr = syn.option_trades(DAYS, START)
    tr = tr[~((tr.timestamp >= pd.Timestamp("2025-02-04 10:00").value // 10**6)
              & (tr.timestamp < pd.Timestamp("2025-02-04 14:00").value // 10**6))]      # a 4-hour hole in the trades
    r["options"] = adapters.option_hours(tr, g[0] - pd.Timedelta(hours=1), g[-1])
    M, _ = cube.build_frame(r, syn.dvol(DAYS, START), g)
    return M


def test_every_feature_is_computed_and_mostly_finite(minutes):
    load_all()
    F = R.compute(minutes, "BTCUSDT")
    last = F[F.ts >= pd.Timestamp("2025-02-04")]
    assert len(REGISTRY) >= 55 and F.shape[1] == len(REGISTRY) + 1
    in_window_only = {"min_to_window_end"}                     # NaN outside 00:00-09:00 IST by definition
    empty = [c for c in last.columns if c not in {"ts"} | in_window_only and last[c].notna().mean() < 0.5]
    assert empty == [], f"features NaN on most of the last day: {empty}"
    assert last.min_to_window_end.notna().mean() == pytest.approx(9 / 24, abs=1e-3)
    assert (last.min_to_window_end.notna() == (last.in_boss_window == 1)).all()


def test_every_feature_passes_the_lookahead_guard(minutes):
    cuts = [pd.Timestamp("2025-02-02 15:59"), pd.Timestamp("2025-02-03 05:10"), pd.Timestamp("2025-02-04 23:59"),
            pd.Timestamp("2025-02-05 00:00"), pd.Timestamp("2025-02-05 13:37")]
    bad = guard.check_features(minutes, "BTCUSDT", cuts)
    assert bad == {}, f"look-ahead in {sorted(bad)}"


def test_day_features_pass_the_guard_with_the_ist_day(minutes):
    names = ["pdh_dist_pct", "pdl_dist_pct", "pdc_dist_pct", "pwh_dist_pct", "day_ret_pct", "vwap_day_dist_pct",
             "dow", "is_weekend", "minute_of_day", "atr14_1d_pct", "day_range_pos"]
    cuts = [pd.Timestamp("2025-02-04 18:29"), pd.Timestamp("2025-02-04 18:30"), pd.Timestamp("2025-02-05 02:00")]
    assert guard.check_features(minutes, "BTCUSDT", cuts, names=names, market="crypto-ist") == {}
    F = R.compute(minutes, "BTCUSDT", names=["minute_of_day", "dow"], market="crypto-ist").set_index("ts")
    assert F.minute_of_day[pd.Timestamp("2025-02-04 18:30")] == 0          # IST midnight = 18:30 UTC
    assert F.dow[pd.Timestamp("2025-02-04 18:30")] == 2                    # Wednesday 5 Feb in IST


def test_the_guard_catches_leaky_features(minutes):
    def leaky(m, c, u, n):
        m = m.sort_values("ts").reset_index(drop=True)
        day_hi = m.groupby(m.ts.dt.date).high.transform("max")
        return pd.DataFrame({"ts": m.ts, "next_close": m.close.shift(-1), "day_final_high": day_hi})
    from research.brain import guard as india
    bad = india.check_features(minutes, None, "BTCUSDT", [pd.Timestamp("2025-02-03 12:00")], compute_fn=leaky,
                               perturb=guard.PERTURB)
    assert set(bad) == {"next_close", "day_final_high"}


def test_warmup_independence(minutes):
    """Features of the last day with the declared warm-up equal those with 3 more days loaded (incremental = full)."""
    W = R.warmup_days()
    assert W <= DAYS - 4
    day = pd.Timestamp(START) + pd.Timedelta(days=DAYS - 1)
    outs = []
    for w in (W, W + 3):
        m = minutes[minutes.ts >= day - pd.Timedelta(days=w)]
        F = R.compute(m, "BTCUSDT")
        outs.append(F[F.ts >= day].reset_index(drop=True))
    a, b = outs
    diff = [c for c in a.columns if c != "ts" and not np.allclose(a[c], b[c], equal_nan=True, rtol=1e-9, atol=1e-12)]
    assert diff == [], f"depend on more history than declared: {diff}"


def test_catalogue_is_current_and_features_never_read_labels():
    assert FEATURES_MD.read_text() == R.catalogue_md() + "\n", "run python -m research.brain.crypto.features catalog"
    src = Path(R.__file__).parent
    for f in src.glob("*.py"):
        txt = f.read_text()
        assert not re.search(r"\b(labels|split|holdout)\b\s+import|import\s+.*\b(labels|holdout)\b|from \.\.(\.)? import .*labels",
                             txt), f.name
    assert not [n for n in REGISTRY if n.startswith("y_")]


def test_india_and_crypto_registries_are_separate():
    from research.brain.features import REGISTRY as INDIA
    from research.brain.features import load_all as india_all
    india_all()
    load_all()
    assert INDIA is not REGISTRY
    assert not {"funding_z_30d", "vp24_poc_dist_pct", "basis_z_7d", "in_us"} & set(INDIA)   # nothing leaked across
    assert all(f.family in R.FAMILIES for f in REGISTRY.values())
