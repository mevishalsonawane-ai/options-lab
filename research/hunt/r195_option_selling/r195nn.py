"""R195 N1 (PREREG.md section 4): a 6-hidden-layer MLP that filters S4-NIFTY's hedged straddle.

Boss (18 Nov 2026): "You could create a neuronetwork with 5-10 layers for more insight". Features are known at the
09:20 decision (index, India VIX, the 09:20 straddle); the target is S4-NIFTY's own net per trade. Walk-forward: test
year Y in 2023-2025 is predicted by a net fitted on every S4 trade before 1 Jan Y; the holdout by one net fitted on
every design trade, frozen. A day is traded when the prediction is > 0.
"""
from __future__ import annotations

import math
import pickle
import sys
import warnings
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r195cfg as K  # noqa: E402
import r195rules as RU  # noqa: E402
from r195engine import years_left  # noqa: E402

UND = "NIFTY"
WF_YEARS = (2023, 2024, 2025)
FEATURES = ["vix", "vix_chg5", "iv_rv20", "rv5_rv20", "gap", "range_ratio", "ret_0915_0920", "trend20", "sessions_to_exp",
            "weekday", "straddle_pct"]


def features(d: date, store: dict, Dl, expiry: date) -> list | None:
    """The 11 inputs for day d, from sessions before d and d's minutes up to 09:20 only (NaN -> None)."""
    i = Dl.pos.get(d)
    D = store.get(d)
    if i is None or D is None or D.atm is None or i < 25:
        return None
    r = RU.R0920
    k0, s = RU.straddle(D, UND, r)
    spot = D.idx[3, r]
    t = float(years_left(d, expiry, r))
    rv20, rv5 = RU.rv(Dl, i, 20), RU.rv(Dl, i, 5)
    v, v5 = Dl.vix920[i], Dl.vix920[i - 5]
    rng = Dl.high - Dl.low
    base = np.nanmean(rng[i - 21: i - 1])
    sma = np.nanmean(Dl.close[i - 20: i])
    later = [x for x in Dl.days[i:] if x <= expiry]
    o = D.idx[0, 0]
    x = [v, v / v5 - 1 if v5 else np.nan, (s / (0.7979 * spot * math.sqrt(t))) / rv20 if t > 0 and rv20 else np.nan,
         rv5 / rv20 if rv20 else np.nan, Dl.open_[i] / Dl.close[i - 1] - 1, rng[i - 1] / base if base else np.nan,
         spot / o - 1, Dl.close[i - 1] / sma - 1, float(len(later) - 1), float(d.weekday()), s / spot]
    return x if all(np.isfinite(x)) else None


def model():
    from sklearn.neural_network import MLPRegressor
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    net = MLPRegressor(hidden_layer_sizes=(32,) * 6, activation="relu", solver="adam", alpha=1e-3, early_stopping=True,
                       validation_fraction=0.1, n_iter_no_change=20, max_iter=500, random_state=K.SEED)
    return make_pipeline(StandardScaler(), net)


def fit_predict(train_x, train_y, test_x) -> np.ndarray:
    m = model()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        m.fit(np.array(train_x), np.array(train_y))
    return m.predict(np.array(test_x))


def _s4(part: str, mult: float):
    name = f"{part}_S4-NIFTY.pkl" if mult == 1.0 else f"{part}2x_S4-NIFTY.pkl"
    with open(K.OUT / "trades" / name, "rb") as f:
        return pickle.load(f)


def trades(part: str, mult: float = 1.0) -> list:
    """N1's trades: S4-NIFTY's trades in the part on days the walk-forward net predicts > 0."""
    import r195study as S
    store, Dl, exp = S.setup(UND)
    wf = _s4("wf", 1.0)                                     # targets are always the 1x-slippage design trades
    tgt = _s4(part, mult) if not (part == "wf" and mult == 1.0) else wf
    X = {t["day"]: features(t["day"], store, Dl, exp[t["day"]]) for t in wf + (tgt if part == "ho" else [])}
    out = []
    folds = [(y, [t for t in tgt if t["day"].year == y]) for y in WF_YEARS] if part == "wf" else [(None, tgt)]
    for y, test in folds:
        train = [t for t in wf if (y is None or t["day"] < date(y, 1, 1)) and X.get(t["day"]) is not None]
        test = [t for t in test if X.get(t["day"]) is not None]
        if len(train) < 100 or not test:
            continue
        pred = fit_predict([X[t["day"]] for t in train], [t["net"] / 1000.0 for t in train], [X[t["day"]] for t in test])
        out += [dict(t, tag="N1-NIFTY") for t, p in zip(test, pred) if p > 0]
    return sorted(out, key=lambda t: t["day"])
