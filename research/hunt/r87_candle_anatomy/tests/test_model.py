from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import cfg
from research.hunt.r87_candle_anatomy import model as MO
from research.hunt.r87_candle_anatomy import sim as S


def _ohlc(px=25000.0):
    a = np.full((4, cfg.NROWS), px)
    a[1] += 20
    a[2] -= 20
    return a


def test_contract_shape_and_monotone():
    d, exp = date(2025, 3, 3), date(2025, 3, 6)
    vix = np.full(cfg.NROWS, 14.0)
    C = MO.contract("C", 24950.0, d, _ohlc(), vix, 1.0, exp)
    P = MO.contract("P", 25050.0, d, _ohlc(), vix, 1.0, exp)
    assert C.shape == (5, cfg.NROWS)
    assert (C[1] > C[3]).all() and (C[2] < C[3]).all()          # call high at the index high
    assert (P[1] > P[3]).all() and (P[2] < P[3]).all()          # put high at the index low
    assert C[3, 0] > 50 and C[3, 0] < 400                       # a 1-ITM weekly NIFTY call: plausible premium
    assert C[3, -1] < C[3, 0]                                   # time decay through the day
    v2 = MO.contract("C", 24950.0, d, _ohlc(), vix * 1.5, 1.0, exp)
    assert v2[3, 0] > C[3, 0]


def test_contract_missing_minutes_and_expiry_day():
    o = _ohlc()
    o[:, 100] = np.nan
    vix = np.full(cfg.NROWS, 14.0)
    C = MO.contract("C", 24950.0, date(2025, 3, 6), o, vix, 1.0, date(2025, 3, 6))
    assert np.isnan(C[:, 100]).all()
    assert np.isclose(C[3, -1], 50.0, atol=2.0)                 # at expiry close ~ intrinsic


def test_sensex_expiry_fallback_thursdays():
    e = MO.expiry_dates("SENSEX", date(2026, 9, 1), date(2026, 10, 31))
    assert date(2026, 10, 15) in e and date(2026, 10, 22) in e
    assert MO.next_expiry(e, date(2026, 10, 13)) == date(2026, 10, 15)


def test_iv_scale_point_in_time():
    idx = pd.Index([date(2025, 1, d) for d in range(1, 31)])
    rng = np.random.default_rng(0)
    n = pd.Series(25000 * np.exp(np.cumsum(rng.normal(0, 0.01, 30))), index=idx)
    b = pd.Series(50000 * np.exp(np.cumsum(rng.normal(0, 0.02, 30))), index=idx)
    s1 = MO.iv_scale({"NIFTY": n, "BANKNIFTY": b}, "BANKNIFTY", date(2025, 1, 25))
    b2 = b.copy()
    b2.iloc[-5:] *= 3                                           # changes after the day must not matter
    s2 = MO.iv_scale({"NIFTY": n, "BANKNIFTY": b2}, "BANKNIFTY", date(2025, 1, 25))
    assert s1 == s2 and 1.2 < s1 < 3.5
    assert MO.iv_scale({"NIFTY": n}, "NIFTY", date(2025, 1, 25)) == 1.0


def test_modelled_trade_prices_end_to_end():
    d, exp = date(2025, 3, 3), date(2025, 3, 6)
    o = _ohlc()
    o[:, 130:] += 60                                            # the index rises 60 points at 11:25
    vix = np.full(cfg.NROWS, 14.0)
    con = {("C", 24950.0): MO.contract("C", 24950.0, d, o, vix, 1.0, exp)}
    t = S.price_trade("NIFTY", d, con, o[3], np.full(cfg.NROWS, np.nan), 100, True, 24900.0, 75, 0.15, False)
    assert t is not None and t["strike"] == 24950.0 and t["net"] > 0
