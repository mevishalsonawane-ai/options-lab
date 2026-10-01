"""python -m pytest indicator -q"""
import os
import sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import indicator as ind  # noqa: E402
from indicator.core import REGISTRY  # noqa: E402


def frame(n=600, seed=1, volume=True):
    """A random-walk 1-minute OHLCV frame over two sessions."""
    rng = np.random.default_rng(seed)
    t = pd.date_range("2026-09-28 09:15", periods=375, freq="min").append(
        pd.date_range("2026-09-29 09:15", periods=375, freq="min"))[:n]
    c = 54000 + np.cumsum(rng.normal(0, 8, n))
    o = np.r_[c[0], c[:-1]] + rng.normal(0, 2, n)
    h = np.maximum(o, c) + rng.uniform(0, 10, n)
    l = np.minimum(o, c) - rng.uniform(0, 10, n)
    df = pd.DataFrame({"open": o, "high": h, "low": l, "close": c}, index=t)
    if volume:
        df["volume"] = rng.integers(100, 5000, n).astype(float)
    return df


def line(n=200, slope=2.0):
    c = 100 + slope * np.arange(n, dtype=float)
    t = pd.date_range("2026-09-28 09:15", periods=n, freq="min")
    return pd.DataFrame({"open": c - 1, "high": c + 1, "low": c - 1.5, "close": c, "volume": 1000.0}, index=t)


def test_every_indicator_runs_and_is_aligned():
    df = frame()
    other = df.close * 1.01
    for name, e in REGISTRY.items():
        r = e.fn(df, other) if name in ("Correlation", "Beta") else e.fn(df)
        if name == "VolumeProfile":
            assert abs(r.volume.sum() - df.volume.sum()) < 1e-6 and r.poc.sum() == 1
            continue
        assert len(r) == len(df), name
        vals = r.values.astype(float)
        assert np.isfinite(vals).any(), f"{name} is all NaN"


def test_volume_indicators_refuse_an_index_without_volume():
    df = frame(volume=False)
    with pytest.raises(ValueError):
        ind.OBV(df)
    out = ind.compute_all(df)
    assert "RSI" in out and not any(c.startswith("OBV") for c in out)


def test_hand_values():
    df = line(30)
    assert ind.SMA(df, 5).iloc[-1] == pytest.approx(df.close.iloc[-5:].mean())
    w = np.arange(1, 6)
    assert ind.WMA(df, 5).iloc[-1] == pytest.approx(np.dot(df.close.iloc[-5:], w) / w.sum())
    # a straight line: the regression MA sits on it, the slope is the slope, RSI is 100
    assert ind.LSMA(df, 10).iloc[-1] == pytest.approx(df.close.iloc[-1])
    assert ind.LinRegSlope(df, 10).iloc[-1] == pytest.approx(2.0)
    assert ind.RSI(df, 14).iloc[-1] == pytest.approx(100.0)
    # Hull on a line lags (sqrt(n)-1)/3 + 2(n/2-1)/3 - (n-1)/3 bars = 2/3 of a bar for n = 16
    assert ind.HMA(line(200), 16).iloc[-1] == pytest.approx(line(200).close.iloc[-1] - 2.0 * 2 / 3)
    assert ind.EfficiencyRatio(df, 10).iloc[-1] == pytest.approx(1.0)
    # true range: bar 2 of the line is high - low = 2.5 (gap-free)
    assert ind.TrueRange(df).iloc[5] == pytest.approx(max(2.5, abs(df.high.iloc[5] - df.close.iloc[4]),
                                                          abs(df.low.iloc[5] - df.close.iloc[4])))


def test_obv_and_ad_by_hand():
    t = pd.date_range("2026-09-28 09:15", periods=4, freq="min")
    df = pd.DataFrame({"open": [10, 10, 11, 10], "high": [11, 12, 12, 11], "low": [9, 10, 10, 9],
                       "close": [10, 11, 10, 11], "volume": [100.0, 200, 300, 400]}, index=t)
    assert list(ind.OBV(df)) == [0, 200, -100, 300]
    # CLV of bar 2: ((11-10) - (12-11)) / 2 = 0
    assert ind.ADLine(df).iloc[1] == pytest.approx(0.0)


def test_bollinger_and_stochastic_ranges():
    df = frame()
    b = ind.Bollinger(df)
    ok = b.dropna()
    assert (ok.upper >= ok.mid).all() and (ok.lower <= ok.mid).all()
    s = ind.Stochastic(df).dropna()
    assert s.k.between(0, 100).all()
    assert ind.WilliamsR(df).dropna().between(-100, 0).all()
    assert ind.MFI(df).dropna().between(0, 100).all()


def test_supertrend_matches_the_research_version():
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "research"))
    from st_ema_pine import supertrend  # the Pine-exact version used in the research
    df = frame()
    b = pd.DataFrame({"h": df.high.values, "l": df.low.values, "c": df.close.values})
    ref = supertrend(b)                                     # -1 = up in Pine's convention
    mine = ind.Supertrend(df).direction.values
    tail = slice(100, None)                                 # after both have warmed up
    assert (np.where(ref[tail] < 0, 1, -1) == mine[tail]).mean() > 0.97


def test_pivots_use_the_previous_day():
    df = frame(750)
    p = ind.PivotClassic(df)
    d1 = df[df.index.normalize() == df.index[0].normalize()]
    P = (d1.high.max() + d1.low.min() + d1.close.iloc[-1]) / 3
    assert p.P.iloc[-1] == pytest.approx(P)
    assert np.isnan(p.P.iloc[0])


def test_market_structure_flags_a_break():
    df = line(60)
    ms = ind.MarketStructure(df, 3)
    assert set(np.unique(ms.trend)) <= {-1, 0, 1}


def test_catalog_lists_everything():
    cat = ind.catalog()
    assert len(cat) == len(REGISTRY) >= 150
    assert cat["what it is"].str.len().min() > 10
