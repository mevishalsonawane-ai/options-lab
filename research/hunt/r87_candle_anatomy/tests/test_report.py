import numpy as np

from research.hunt.r87_candle_anatomy import report as RP
from research.hunt.r87_candle_anatomy import wickcheck as WC


def test_matched_rate_compares_same_distance():
    dist = np.array([0.05, 0.05, 0.55, 0.55, 0.55, 0.55, np.nan])
    hit = np.array([1.0, 1.0, 0.0, 1.0, 0.0, 1.0, 1.0])
    sel = np.array([False, False, True, True, False, False, True])
    rate, same, n = WC.matched_rate(dist, hit, sel)
    assert n == 2 and rate == 0.5 and same == 0.5           # same-distance bin: 2 of 4 broken


def test_verdict_texts():
    s = dict(finding=True, eff_atr_5=0.2, holds_oos=False)
    assert "1 instrument/timeframe finding" in RP.verdict(dict(stats=[s], up_more_than_matched=[],
                                                              down_more_than_matched=[]))
    none = RP.verdict(dict(stats=[dict(finding=False)], up_more_than_matched=[], down_more_than_matched=[]))
    assert none.startswith("no edge")
