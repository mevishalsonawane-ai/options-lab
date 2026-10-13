"""Fast tests for research/context (no data download): the pieces whose rules the app copies."""
from datetime import date

import numpy as np

from research.context import expiry_playbook as X, level_map as LM, lib as L


def bars(rows):
    return [None if r is None else tuple(float(v) for v in r) for r in rows]


def test_bh_monotone_and_nan():
    q = L.bh({"a": 0.01, "b": 0.04, "c": 0.5, "d": float("nan")})
    assert q["a"] <= q["b"] <= q["c"] and np.isnan(q["d"])
    assert abs(q["a"] - 0.03) < 1e-12


def test_level_held_and_broken():
    # resistance at 100: touched in bar 1, never closed above 100.05 -> held
    B = bars([(98, 99, 97, 98.5), (98.5, 100.02, 98, 99), (99, 99.5, 98, 98.2)])
    assert LM.test(B, 100.0, 0) == (True, True)
    # closed above it after the touch -> broken
    B = bars([(98, 99, 97, 98.5), (98.5, 100.02, 98, 99), (99, 101, 98, 100.6)])
    assert LM.test(B, 100.0, 0) == (True, False)
    # never reached -> not tested
    B = bars([(98, 99, 97, 98.5), (98.5, 99.0, 98, 99)])
    assert LM.test(B, 100.0, 0) == (False, False)
    # support from above
    B = bars([(102, 103, 101, 102), (102, 102, 99.98, 100.5), (100.5, 101, 100, 100.8)])
    assert LM.test(B, 100.0, 0) == (True, True)


def test_max_pain():
    oi = {(k, s): 100.0 for k in range(90, 111) for s in "CP"}
    oi[(100.0, "C")] = oi[(100.0, "P")] = 10_000.0
    assert X.max_pain({(float(k), s): v for (k, s), v in oi.items()}) == 100.0


def test_event_session_mapping(monkeypatch):
    import pandas as pd
    days = [date(2025, 2, 6), date(2025, 2, 7), date(2025, 2, 10)]
    ev = pd.DataFrame([dict(date=date(2025, 2, 7), kind="rbi", name="RBI", ist_time="10:00", source="", verified="yes"),
                       dict(date=date(2025, 2, 7), kind="us_cpi", name="US CPI", ist_time="after_close", source="", verified="yes"),
                       dict(date=date(2025, 2, 8), kind="results", name="Reliance results", ist_time="after_close", source="", verified="no")])
    monkeypatch.setattr(L, "events", lambda: ev)
    bn = L.event_sessions("BANKNIFTY", days)
    assert list(zip(bn.session, bn.kind)) == [(date(2025, 2, 7), "rbi"), (date(2025, 2, 10), "us_cpi")]   # Reliance: NIFTY only
    nf = L.event_sessions("NIFTY", days)
    assert (date(2025, 2, 10), "results") in list(zip(nf.session, nf.kind))
