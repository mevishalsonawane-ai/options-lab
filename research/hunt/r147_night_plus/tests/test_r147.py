"""R147 tests: synthetic data only (no lake file is read).

    python -m pytest research/hunt/r147_night_plus/tests -q
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from research.hunt.r147_night_plus import cfg as K
from research.hunt.r147_night_plus import data as D
from research.hunt.r147_night_plus import study as S

N = K.N


def day(base=100.0, seed=0):
    rng = np.random.default_rng(seed)
    c = base + np.cumsum(rng.normal(0, 0.2, N))
    o = np.concatenate([[base], c[:-1]])
    return np.column_stack([o, np.maximum(o, c) + 0.1, np.minimum(o, c) - 0.1, c])


# ================================================================================================ look-ahead
def test_decision_features_ignore_everything_after_1519():
    a = day(22000, 1)
    f1 = D.decision_features(a, 21900.0)
    b = a.copy()
    b[K.R1519 + 1:] = np.random.default_rng(5).normal(0, 1e5, b[K.R1519 + 1:].shape)
    f2 = D.decision_features(b, 21900.0)
    assert f1 == pytest.approx(f2)


def test_decision_features_values():
    a = np.full((N, 4), np.nan)
    a[:K.R1519 + 1] = 100.0
    a[0, K.O] = 99.0
    a[K.R1519, :] = [101, 102, 100, 102]
    a[K.R1419, K.C] = 101.0
    f = D.decision_features(a, 100.0)
    assert f["x"] == 102 and f["move"] == pytest.approx(0.02) and f["loc"] == pytest.approx(1.0)
    assert f["l60"] == pytest.approx(102 / 101 - 1) and f["gap0"] == pytest.approx(-0.01)


def test_nights_only_returns_the_asked_part(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "OUT", tmp_path)
    recs = [{"und": "NIFTY", "d": date(2024, 5, 1), "part": "design", "qty": 25, "legs": {}},
            {"und": "NIFTY", "d": date(2025, 5, 1), "part": "hold", "qty": 75, "legs": {}}]
    import pickle
    (tmp_path / "nights_NIFTY.pkl").write_bytes(pickle.dumps(recs))
    assert list(S.nights("NIFTY", "design").d) == [date(2024, 5, 1)]
    assert list(S.nights("NIFTY", "hold").d) == [date(2025, 5, 1)]


def test_part_boundaries():
    assert K.part(date(2021, 12, 31)) == "warm" and K.part(date(2022, 1, 3)) == "design"
    assert K.part(date(2024, 12, 31)) == "design" and K.part(date(2025, 1, 1)) == "hold"


def test_holdout_refuses_second_read(tmp_path, monkeypatch):
    f = tmp_path / "flag"
    f.write_text("x")
    monkeypatch.setattr(K, "FLAG", f)
    with pytest.raises(SystemExit):
        S.holdout()


# ================================================================================================ data rules
def test_drop_foreign_rows():
    ixc = pd.Series({(date(2024, 1, 2), 5): 22000.0, (date(2024, 1, 2), 6): 22000.0})
    o = pd.DataFrame({"d": [date(2024, 1, 2)] * 3, "m": [5, 5, 6], "spot": [22010.0, 47000.0, 22000.0],
                      "strike": [22000.0, 47000.0, 23000.0]})
    out = D.drop_foreign(o, ixc, 50.0)
    assert list(out.spot) == [22010.0]          # BANKNIFTY-level row and the 20-step strike are gone


def test_expiry_days_rule():
    d = [date(2024, 1, i) for i in (1, 2, 3, 4)]
    tv = pd.Series([1.0, 0.4, 1.5, 0.9], index=d)
    assert D.expiry_days(tv) == {date(2024, 1, 2)}


def test_buildup_matches_nightrules():
    rows = []
    x, step = 22010.0, 50.0
    for i, k in enumerate([21900, 21950, 22000, 22050, 22100]):
        for m, cc, oi, pc, po in ((0, 100, 1000, 100, 2000), (K.R1519, 110, 1500 + i, 90, 1800)):
            rows += [{"strike": float(k), "side": "C", "m": m, "close": cc, "oi": oi},
                     {"strike": float(k), "side": "P", "m": m, "close": pc, "oi": po}]
    num = sum((500 + i) + 200 for i in range(5))
    den = sum(1500 + i + 1800 for i in range(5))
    assert D.buildup(pd.DataFrame(rows), x, step) == pytest.approx(num / den)


def test_strong_close_and_r3():
    assert D.strong_close(0.7, 0.01, 0.6) == 1 and D.strong_close(0.3, -0.01, 0.4) == -1
    assert D.strong_close(0.7, 0.01, 0.4) == 0 and D.strong_close(0.7, 0.01, np.nan) == 0
    assert D.r3_side(1, 0.6) == 1 and D.r3_side(1, 0.4) == 0 and D.r3_side(-1, -0.5) == -1 and D.r3_side(1, np.nan) == 0


def test_guard_and_price_leg():
    g0 = pd.DataFrame({"strike": 100.0, "side": "C", "m": [K.ENTRY_ROWS[0]], "open": 50.0, "high": 51.0, "low": 49.0,
                       "close": 50.0})
    g1 = pd.DataFrame({"strike": 100.0, "side": "C", "m": [K.X16, K.X16 + 1], "open": [60.0, 60.5], "high": 61.0,
                       "low": 59.0, "close": [60.0, 60.5]})
    assert D.price_leg(g0, g1, 100.0, 1) == (50.0, 60.0, "ok")
    assert D.price_leg(g0, g1, 100.0, -1)[2] == "no_entry_bar"
    a = np.full((10, 4), np.nan)
    a[2] = [150, 150, 100, 100]
    a[3, 0] = 101
    assert not D.guard_ok(a, 2)
    a[3, 0] = 148
    assert D.guard_ok(a, 2)


def test_zscore():
    assert np.isnan(D.zscore(1.0, [0.1] * 19))
    h = list(np.linspace(-1, 1, 80))
    assert D.zscore(1.0, h) == pytest.approx((1 - np.mean(h[-60:])) / np.std(h[-60:], ddof=1))


# ================================================================================================ filters / stats
def frame():
    return pd.DataFrame({"wkend": [True, False, False], "us_ev": [False, True, False], "in_ev": [False, False, False],
                         "move": [0.02, 0.001, -0.001], "l60": [-0.001, 0.002, 0.003], "hsi": [-0.01, 0.01, 0.01],
                         "vix": [19.0, 12.0, np.nan], "loc": [0.85, 0.65, 0.1], "strad_ratio": [1.3, 1.0, np.nan]})


def test_filters():
    df, s = frame(), np.array([1, 1, -1])
    assert list(S.filter_skip(df, s, "F1")) == [True, False, False]
    assert list(S.filter_skip(df, s, "F2")) == [False, True, False]
    assert list(S.filter_skip(df, s, "F3")) == [True, False, False]
    assert list(S.filter_skip(df, s, "F4")) == [True, False, True]        # put with the last hour up
    assert list(S.filter_skip(df, s, "F5")) == [True, False, True]
    assert list(S.filter_skip(df, s, "F6")) == [True, False, False]
    assert list(S.filter_skip(df, s, "F7")) == [False, True, False]
    assert list(S.filter_skip(df, s, "F8")) == [True, False, False]
    assert not S.filter_skip(df, np.zeros(3, int), "F1").any()


def test_skip_twin_and_signflip():
    rng = np.random.default_rng(0)
    nets = np.concatenate([np.full(20, -1000.0), rng.normal(300, 50, 200)])
    skip = np.zeros(220, bool)
    skip[:20] = True                                # skipping exactly the losers
    assert S.skip_twin_p(nets, skip, rng, 500) < 0.01
    assert S.signflip_p(np.full(50, 10.0), rng, 500) < 0.01
    assert S.signflip_p(np.full(50, -10.0), rng, 500) > 0.9


def test_twin_side_p():
    rng = np.random.default_rng(1)
    c, p = np.full(40, 100.0), np.full(40, -100.0)
    assert S.twin_side_p(c, p, 4000.0, rng, 500) < 0.01


def test_gap_class_and_metrics():
    assert [S.gap_class(g) for g in (-0.01, -0.002, 0.0, 0.003, 0.006)] == list(S.GAP_CLASSES)
    d0 = date(2024, 1, 1)
    m = S.metrics([d0, d0 + timedelta(days=40)], [100.0, -300.0], [900.0, 900.0])
    assert m["trades"] == 2 and m["max_dd"] == -300.0 and m["share_loss_gt_30pct_premium"] == 0.5


def test_bh():
    q = S.bh([0.01, 0.04, 0.03, np.nan])
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.04) and np.isnan(q[3])


def test_lots():
    assert K.lot("FINNIFTY", date(2025, 1, 1)) == 65 and K.lot("SENSEX", date(2025, 1, 1)) == 20
    assert K.lot("MIDCPNIFTY", date(2024, 1, 1)) == 75
