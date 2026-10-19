import json

import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import batlas as BA
from research.hunt.r87_candle_anatomy import build as B
from research.hunt.r87_candle_anatomy import candles as CA
from research.hunt.r87_candle_anatomy import context as CX
from research.hunt.r87_candle_anatomy.tests import synth


def test_tokens():
    k = CA.anatomy(synth.bars([(100, 110, 100, 110), (100, 106, 94, 100.5), (100, 101, 90, 98), (100, 112, 99, 102)]))
    assert list(BA.tokens(k)) == ["GBN", "DN", "RSL", "GSU"]


def test_seq3_same_session_only():
    k = CA.anatomy(synth.bars([(100, 110, 100, 110)] * 18))
    k["atr"] = 5.0
    s = BA.seq3(k)
    assert s.iloc[:2].isna().all() and s.iloc[2] == "GBN-GBN-GBN"
    k.loc[10:, "day"] = pd.Timestamp("2024-01-03")
    s = BA.seq3(k)
    assert s.iloc[10:12].isna().all() and s.iloc[12] == "GBN-GBN-GBN"


def test_features_and_clusters_are_deterministic():
    m = CX.minute_context(synth.minutes(days=8, seed=2), "NIFTY")
    k = B.candle_table(m, "NIFTY", 5, set())
    F = BA.seq_features(k)
    assert F.shape[1] == 12 and np.isnan(F[:2]).all()
    BA.K_CLUSTERS, old = 4, BA.K_CLUSTERS
    try:
        C1, C2 = BA.fit_clusters(F), BA.fit_clusters(F)
    finally:
        BA.K_CLUSTERS = old
    assert np.allclose(C1, C2)
    lab = BA.assign(F, C1)
    assert (lab[:2] == -1).all() and set(lab[np.isfinite(F).all(1)]) <= {0, 1, 2, 3}


def test_daily_table_crosses_days_and_has_no_lookahead():
    rng = np.random.default_rng(4)
    ts = pd.bdate_range("2015-01-01", periods=300)
    c = 8000 * np.exp(np.cumsum(rng.normal(0, 0.01, 300)))
    o = np.r_[c[0], c[:-1]] * (1 + rng.normal(0, 0.002, 300))
    d = pd.DataFrame(dict(ts=ts, o=o, h=np.maximum(o, c) * 1.004, l=np.minimum(o, c) * 0.996, c=c))
    k = BA.daily_table(d, "NIFTY", set())
    assert np.isfinite(k.f_ret5.iloc[100]) and np.isclose(k.f_ret5.iloc[100], (c[105] - c[100]) / k.atr.iloc[100])
    assert k.day.nunique() == 300
    cut = BA.daily_table(d.iloc[:200], "NIFTY", set())
    feats = [x for x in cut.columns if not x.startswith("f_") and x not in ("ts", "day", "tl")]
    a, b = k.iloc[:200][feats], cut[feats]
    for x in feats:
        if a[x].dtype.kind in "fc":
            assert np.allclose(a[x].to_numpy(float), b[x].to_numpy(float), equal_nan=True), x
        else:
            na, nb = (v.astype(object).where(v.notna(), "<na>").astype(str).to_numpy() for v in (a[x], b[x]))
            assert (na == nb).all(), x


def test_scan_finds_planted_sequence_effect():
    m = CX.minute_context(synth.minutes(days=40, seed=9), "NIFTY")
    k = B.candle_table(m, "NIFTY", 5, set())
    plant = np.random.default_rng(1).random(len(k)) < 0.15
    k.loc[plant, "f_ret5"] += 1.5
    rows = BA.scan(k, {"x": plant, "y": ~plant}, "f_ret5", ("und", "tf", "tod", "trend", "volreg", "col"),
                   ((k.day.min().date(), k.day.max().date()),), None, dict(und="NIFTY", tf=5, cond=""), 50)
    t = BA.finish(rows).set_index("family")
    assert t.loc["x", "q"] < 0.01 and t.loc["x", "eff"] > 1.0 and len(json.loads(t.loc["x", "fold_eff"])) == 1
