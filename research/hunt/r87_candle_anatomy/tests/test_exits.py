from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import exits as X
from research.hunt.r87_candle_anatomy import cfg


def _k(closes, highs=None, lows=None, day="2025-01-02"):
    n = len(closes)
    d = pd.Timestamp(day)
    ts = [d + pd.Timedelta(minutes=cfg.OPEN_MIN + 5 * i) for i in range(n)]
    c = np.asarray(closes, float)
    return pd.DataFrame(dict(ts=ts, tl=[t + pd.Timedelta(minutes=4) for t in ts], day=d, slot=[5 * i for i in range(n)],
                             o=c, h=c + 1 if highs is None else highs, l=c - 1 if lows is None else lows, c=c,
                             orh=np.where(np.arange(n) >= 3, 103.0, np.nan), orl=np.where(np.arange(n) >= 3, 97.0, np.nan)))


def test_orb_first_break_each_side_only():
    k = _k([100, 101, 102, 101, 104, 105, 99, 96, 95, 104])
    s = X.arm_signals(k, "ORB")
    assert list(zip(s.sig_row, s.long)) == [(24, True), (39, False)]


def test_donchian_needs_20_previous_candles():
    c = [100.0] * 25 + [105.0]
    s = X.arm_signals(_k(c), "DON")
    assert list(s.sig_row) == [25 * 5 + 4] and s.long.iloc[0]


def test_first_after_and_triggers():
    rows = np.array([50, 80, 120])
    assert X.first_after(rows, 79) == 80 and X.first_after(rows, 80) == 120 and X.first_after(rows, 200) == -1


def test_evaluate_paired_and_risk_gate():
    days = [date(2025, 2, 1) + pd.Timedelta(days=i) for i in range(60)]
    rng = np.random.default_rng(0)
    base = rng.normal(-50, 300, 60)
    tr = pd.DataFrame(dict(und="NIFTY", arm="DON", day=days, long=True, er=100, base=base, base_why="time",
                           real_entry=True, X1=base + 100 + rng.normal(0, 30, 60), X1_hit=True, X2=base - 100, X2_hit=True, X3=base,
                           X3_hit=False))
    folds = ((days[0], days[19]), (days[20], days[39]), (days[40], days[59]))
    res = {r["overlay"]: r for r in X.evaluate(tr, folds)}
    assert res["X1"]["delta"] > 0 and res["X1"]["pass"]
    assert not res["X2"]["pass"] and not res["X3"]["pass"]
