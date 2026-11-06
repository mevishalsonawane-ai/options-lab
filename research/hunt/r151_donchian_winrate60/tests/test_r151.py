"""R151 tests: the threshold rule and the fold cut (synthetic), and the features' look-ahead wall (real store; skipped
when the R51 clean store / lake is not on this machine, e.g. in CI)."""
from __future__ import annotations

import os
import subprocess
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

from research.hunt.r151_donchian_winrate60 import pick as PK

HERE = Path(__file__).resolve().parents[1]


def row(thr, trades, win, per):
    return {"thr": thr, "trades": trades, "win": win, "per_trade": per}


BASE = {"trades": 1000, "win": 0.45, "per_trade": 20.0}


def test_first_pass_is_the_lowest_threshold_meeting_all_three():
    curve = [row(0.7, 300, 0.65, 50), row(0.5, 600, 0.61, 25), row(0.3, 900, 0.50, 22), row(0.6, 400, 0.62, 30)]
    assert PK.first_pass(curve, BASE)["thr"] == 0.5


def test_first_pass_needs_each_condition():
    assert PK.first_pass([row(0.5, 249, 0.70, 99)], BASE) is None          # < 25% of the arm's trades
    assert PK.first_pass([row(0.5, 250, 0.70, 99)], BASE)["thr"] == 0.5    # exactly 25% is enough
    assert PK.first_pass([row(0.5, 500, 0.599, 99)], BASE) is None         # 59.9% wins
    assert PK.first_pass([row(0.5, 500, 0.60, 19.9)], BASE) is None        # below the arm's per trade
    assert PK.first_pass([row(0.5, 0, 0.0, 0.0) | {"trades": 0}], BASE) is None
    assert PK.first_pass([], BASE) is None


def test_thresholds_ascending_unique_and_empty_safe():
    q = PK.thresholds([0.2, 0.2, 0.4, 0.9, 0.1])
    assert list(q) == sorted(set(q)) and q[0] == 0.1 and q[-1] <= 0.9
    assert PK.thresholds([]).size == 0
    assert list(PK.thresholds([0.5, 0.5])) == [0.5]


def test_model_numbers_score_as_the_pipeline_does():
    """The app's score from the exported numbers (R72Picker.score) is sklearn's predict_proba, NaNs included."""
    from sklearn.impute import SimpleImputer
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    rng = np.random.default_rng(3)
    X = rng.normal(size=(400, 5)) * [1, 10, 0.01, 3, 100]
    y = (X[:, 0] + X[:, 1] / 10 + rng.normal(size=400) > 0).astype(int)
    X[rng.random(X.shape) < 0.1] = np.nan
    mdl = make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True), StandardScaler(), LogisticRegression(C=0.1, max_iter=3000)).fit(X, y)
    nums = PK.model_numbers(mdl, list("abcde"), 0.4656)
    assert nums["feats"] == list("abcde") and nums["threshold"] == 0.4656
    got = [PK.score_from_numbers(nums, x) for x in X[:50]]
    assert np.allclose(got, mdl.predict_proba(X[:50])[:, 1], atol=1e-12)


def test_the_committed_model_is_the_apps():
    """results/r72_picker_model.json holds the numbers R72Picker.kt carries (its first and last coefficient and intercept)."""
    import json
    d = json.loads((HERE / "results" / "r72_picker_model.json").read_text())
    kt = (HERE.parents[2] / "android" / "engine" / "src" / "main" / "kotlin" / "com" / "optionslab" / "engine" / "orb" / "R72Picker.kt").read_text()
    assert len(d["feats"]) == 28 and d["threshold"] == 0.4656
    for v in (d["coef"][0], d["coef"][-1], d["intercept"], d["median"][18], d["scale"][-1]):
        assert repr(v) in kt, v


def test_train_mask_takes_no_trade_from_the_fold_or_later():
    days = [date(2025, 3, 31), date(2025, 4, 1), date(2025, 6, 30), date(2024, 12, 2), date(2025, 7, 1)]
    m = PK.train_mask(days, date(2025, 4, 1))
    assert list(m) == [True, False, False, True, False]
    assert not PK.train_mask([], date(2025, 4, 1)).size


_STORE = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
_LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake")).expanduser()


@pytest.mark.skipif(not (_STORE.exists() and (_LAKE / "index_daily.parquet").exists()), reason="needs the R51 store and lake")
def test_features_do_not_read_minutes_from_the_decision_on():
    """Scramble every index's (and VIX's) minutes from er on, and that day's 5-min bars ending at or after er: the
    features must not change. Run in a child process (study.py rewrites sys.argv on import)."""
    code = r'''
import sys, numpy as np
sys.argv = [sys.argv[0], "design"]
sys.path.insert(0, %r)
import study as T
S, K, B = T.S, T.K, T.B
us = S.units("R51", K.START, K.DESIGN_END)
rng = np.random.default_rng(7)
picks = [us[i] for i in rng.choice(len(us), 6, replace=False)]
for key, cands, _, _ in picks:
    und, day = key[0], key[1]
    c = sorted(cands, key=lambda c: int(c["er"]))[0]
    er = int(c["er"])
    f0 = T.features(und, day, er, c["side"], c, 0)
    for u in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"):
        try:
            x = B.lv(u)
        except Exception:
            continue
        if day not in x.pos:
            continue
        x.idx[day] = np.array(x.idx[day], float)
        x.idx[day][:, er:] = rng.normal(1e5, 1e4, x.idx[day][:, er:].shape)
        m = (x.b["day"] == x.pos[day]) & (x.b["end"] >= er)
        for k in ("c", "h", "l"):
            x.b[k][m] = rng.normal(1e5, 1e4, m.sum())
        T.Dm._EMA.pop(u, None)
    v = T.vix_days().get(day)
    if v is not None:
        v[er:] = 999.0
    f1 = T.features(und, day, er, c["side"], c, 0)
    for k in f0:
        a, b = f0[k], f1[k]
        assert (np.isnan(a) and np.isnan(b)) or a == b, (k, a, b)
print("OK")
''' % str(HERE)
    r = subprocess.run([sys.executable, "-W", "ignore", "-c", code], capture_output=True, text=True, timeout=1800,
                       env={**os.environ, "R51_CLEAN": "1"})
    assert r.returncode == 0 and r.stdout.strip().endswith("OK"), r.stdout[-2000:] + r.stderr[-3000:]


@pytest.mark.skipif(not (_STORE.exists() and (_LAKE / "index_daily.parquet").exists()), reason="needs the R51 store and lake")
def test_features_are_not_reused_across_arms():
    """Amendment D: two calls with the same (index, day, minute, side) but different candidates must not share features,
    and "breaks today" counts the earlier candidates of the units passed in."""
    code = r'''
import sys
sys.argv = [sys.argv[0], "design"]
sys.path.insert(0, %r)
import study as T
from datetime import date
T.features = lambda und, day, er, side, c, n: {"f_count": c.get("f_count"), "breaks_today": n}
k = ("NIFTY", date(2025, 5, 2))
a = T.feats_of_units([(k, [{"er": 30, "side": "C"}], None, None)])
b = T.feats_of_units([(k, [{"er": 10, "side": "P"}, {"er": 30, "side": "C", "f_count": 5}], None, None)])
key = ("NIFTY", date(2025, 5, 2), 30, "C")
assert a[key] == {"f_count": None, "breaks_today": 0}, a
assert b[key] == {"f_count": 5, "breaks_today": 1}, b
print("OK")
''' % str(HERE)
    r = subprocess.run([sys.executable, "-W", "ignore", "-c", code], capture_output=True, text=True, timeout=600,
                       env={**os.environ, "R51_CLEAN": "1"})
    assert r.returncode == 0 and r.stdout.strip().endswith("OK"), r.stdout[-2000:] + r.stderr[-3000:]
