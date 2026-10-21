"""R98 tests: look-ahead guard on real lake days, exits and charges, expiry detection, census multiples.

    python -m pytest research/hunt/r98/test_r98.py -q
"""
from __future__ import annotations

import copy
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
import rows as W  # noqa: E402

HAVE_LAKE = all(R.path(u).exists() for u in R.UNDS)


def masked(day: R.Day, after: int, value=np.nan) -> R.Day:
    """A copy with every bar after row `after` replaced by `value` (blank or absurd future)."""
    d = copy.deepcopy(day)
    d.ix[after + 1:] = value
    for a in d.opts.values():
        a[after + 1:] = value
    if d.vix is not None:
        d.vix[after + 1:] = value
    return d


@pytest.mark.skipif(not HAVE_LAKE, reason="needs the built R98 day pickles")
@pytest.mark.parametrize("und", R.UNDS)
@pytest.mark.parametrize("value", [np.nan, 1e6])
def test_features_do_not_look_ahead(und, value):
    days = R.load(und)
    day = days[len(days) // 2]
    t = R.minute(13, 30)
    full = [r for r in W.day_rows(day, False) if r["t"] == t]
    # keep the entry bar (t + 1) so the rows still exist; everything from t + 2 on is blanked / absurd
    part = [r for r in W.day_rows(masked(day, t + 1, value), False) if r["t"] == t]
    assert full, "no rows at 13:30 on the test day"
    key = lambda r: (r["strike"], r["side"])
    a, b = {key(r): r for r in full}, {key(r): r for r in part}
    assert a.keys() == b.keys()
    for k in a:
        for f in (c for c in a[k] if c.startswith("f_")):
            x, y = a[k][f], b[k][f]
            assert (np.isnan(x) and np.isnan(y)) or x == pytest.approx(y), (k, f, x, y)


def test_exits_target_stop_and_hold():
    e = 2.0
    path = np.array([2.0, 1.5, 2.5, 4.1, 0.5, np.nan, 0.3])
    x = dict(zip(W.EXITS, W.exits_for("NIFTY", e, path)))
    assert x[(2.0, 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 4.0))      # 2x target fills at 4.0, not at the 4.1 close
    assert x[(5.0, 0.8)] == pytest.approx(L.net_pnl("NIFTY", e, 1.5))      # stop at 1.6: the 1.5 close exits
    assert x[(np.inf, 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 0.3))   # held to the last close
    assert x[(1.2, 0.8)] == pytest.approx(L.net_pnl("NIFTY", e, 1.5))      # the stop came first


def test_charges_and_worthless_expiry():
    lose_all = L.net_pnl("NIFTY", 3.0, 0.05)
    assert lose_all < -5000 and lose_all > -5100                           # premium + buy-side charges, no sell order
    flat = L.net_pnl("NIFTY", 3.0, 3.0)
    assert -200 < flat < -40                                               # round trip at the same price loses the costs
    assert L.net_pnl("SENSEX", 2.0, 20.0) > 40000


def test_expiry_detection_rules():
    idx = [date(2026, 1, d) for d in (5, 6, 7, 8, 9, 12, 13)]
    tv = pd.Series([1.0, 0.8, 0.05, 0.12, 1.2, 0.9, 0.04], index=idx)
    out = R.expiry_days(tv)
    # 7 Jan (0.05, next 0.12 > 2x) and 8 Jan (0.12, next 1.2) are adjacent candidates: the smaller one (7 Jan) stays;
    # 13 Jan is the last session with a tiny time value
    assert out == [date(2026, 1, 7), date(2026, 1, 13)]


def test_future_max_and_otm():
    x = np.full(R.N, np.nan)
    x[10], x[20], x[L.T_END + 3] = 2.0, 9.0, 50.0                         # 50 prints after 15:20: outside the window
    fm = L.future_max(x)
    assert fm[10] == 9.0 and fm[20] != fm[20] and np.isnan(fm[R.N - 1])
    assert L.otm(100.0, "C", 99.0) and not L.otm(100.0, "P", 99.0)


def test_profit_lock_ladder_fills_like_the_app():
    import lock as K
    e = 2.0
    be = K.be_level("NIFTY", e)
    assert be == pytest.approx(2.05)                     # Rs 2 + about Rs 0.04 of charges per unit, rounded up a tick
    nan = np.nan
    o = np.array([2.0, 2.1, 2.4, 2.3])
    h = np.array([2.1, 2.7, 2.45, 2.3])                  # peak 2.7 = +35%: the first rung (lock breakeven) from minute 2
    l = np.array([1.98, 2.5, 2.0, 2.2])
    c = np.array([2.05, 2.6, 2.1, 2.25])
    x = dict(zip(K.VARIANTS, K.simulate("NIFTY", e, o, h, l, c)))
    assert x[("add30", 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, be))      # stopped at breakeven in minute 2
    assert x[("none", 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 2.25))     # no lock: out at the last close
    # a gap below the lock fills at the open; the -50% stop alone is not touched
    o2, h2, l2, c2 = np.array([2.0, 2.0, 1.2]), np.array([2.1, 2.9, 1.3]), np.array([1.9, 2.0, 1.1]), np.array([2.0, 2.8, 1.2])
    y = dict(zip(K.VARIANTS, K.simulate("NIFTY", e, o2, h2, l2, c2)))
    assert y[("add30", 0.0)] == pytest.approx(L.net_pnl("NIFTY", e, 1.2))     # peak 2.9 = +45%: lock 2.05, gapped to 1.2
    assert y[("none", 0.5)] == pytest.approx(L.net_pnl("NIFTY", e, 1.2))      # no lock: the 1.2 close at the end
    # +60% locks +30%; compounding x1.69 locks x1.3
    lv = K.lock_levels("add30", e, be, np.array([2.0, 2.6, 3.2, 3.9]))
    assert list(lv[1:]) == pytest.approx([2.05, 2.6, 3.2])
    lc = K.lock_levels("cmp30", e, be, np.array([2.0, 2.6, 3.38, 4.4]))
    assert list(lc[1:]) == pytest.approx([2.05, 2.6, 3.38])
