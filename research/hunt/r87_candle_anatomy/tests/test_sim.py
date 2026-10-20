from datetime import date

import numpy as np

from research.hunt.r87_candle_anatomy import cfg
from research.hunt.r87_candle_anatomy import sim as S

N = cfg.NROWS


def flat_option(px=100.0, vol=1e6):
    A = np.full((5, N), px)
    A[1] += 0.5
    A[2] -= 0.5
    A[4] = vol
    return A


def test_time_exit_and_costs():
    A = flat_option()
    idx = np.full(N, 20000.0)
    r = S.simulate(A, idx, 100, True, 19900, 25, 0.15, cfg.SQ_ROW, 75)
    assert r["er"] == 100 and r["xr"] == 125 and r["why"] == "time"
    assert np.isclose(r["fill"], 100 * 1.0005 + 0.05)          # liquid: 1 tick
    assert np.isclose(r["real_x"], 100 * 0.9995 - 0.05)
    t = S.price_trade("NIFTY", date(2025, 3, 3), {("C", 19950.0): A}, idx, np.full(N, 20000.0), 99, True, 19900,
                      25, 0.15, False)
    assert t["strike"] == 19950.0 and t["net"] < 0 and t["charges"] > 40


def test_premium_stop_with_gap():
    A = flat_option()
    A[0, 110] = 80.0
    A[2, 110] = 79.0                              # opens below the stop: filled at the open
    r = S.simulate(A, np.full(N, 20000.0), 100, True, 19000, 60, 0.15, cfg.SQ_ROW, 75)
    assert r["why"] == "stop" and r["xr"] == 110 and r["X"] == 80.0
    assert np.isclose(r["real_x"], 80 * 0.999 - 2 * 0.05)


def test_lock_trails_and_exits_at_floor():
    A = flat_option()
    A[1, 105] = 125.0                               # +25%: arms the lock from the next minute
    A[1, 106:] = 101
    A[2, 107] = 100.0                               # falls: floor = max(fill*1.10, 125 - 0.1*fill)
    r = S.simulate(A, np.full(N, 20000.0), 100, True, 19000, 60, 0.15, cfg.SQ_ROW, 75)
    fill = 100 * 1.0005 + 0.05
    assert r["why"] == "lock" and r["xr"] == 106
    assert np.isclose(r["X"], min(100.0, max(fill * 1.10, 125 - 0.1 * fill)))


def test_underlying_stop_exits_next_open():
    A = flat_option()
    idx = np.full(N, 20000.0)
    idx[103] = 19890.0                              # closes below the candle low 19900
    r = S.simulate(A, idx, 100, True, 19900, 60, 0.15, cfg.SQ_ROW, 75)
    assert r["why"] == "und_stop" and r["xr"] == 104
    idx2 = np.full(N, 20000.0)
    idx2[103] = 20120.0
    r2 = S.simulate(A, idx2, 100, False, 20100, 60, 0.15, cfg.SQ_ROW, 75)
    assert r2["why"] == "und_stop" and r2["xr"] == 104


def test_squareoff_expiry_and_no_print():
    A = flat_option()
    r = S.simulate(A, np.full(N, 20000.0), cfg.SQ_ROW_EXPIRY - 10, True, 0, 75, 0.15, cfg.SQ_ROW_EXPIRY, 75)
    assert r["xr"] == cfg.SQ_ROW_EXPIRY and r["why"] == "squareoff"
    B = flat_option()
    B[:, 100:103] = np.nan
    assert S.simulate(B, np.full(N, 20000.0), 100, True, 0, 25, 0.15, cfg.SQ_ROW, 75) is None
    # an entry at or after square-off is refused
    assert S.price_trade("NIFTY", date(2025, 3, 3), {("C", 19950.0): A}, np.full(N, 20000.0), np.full(N, 20000.0),
                         cfg.SQ_ROW_EXPIRY - 1, True, 0, 25, 0.15, True) is None


def test_thin_contract_pays_more_ticks():
    A = flat_option(vol=0.0)
    r = S.simulate(A, np.full(N, 20000.0), 100, True, 0, 25, 0.15, cfg.SQ_ROW, 75)
    assert np.isclose(r["fill"], 100 * 1.0005 + 4 * 0.05)


def test_stale_exit_marked():
    A = flat_option()
    A[0, 125:130] = np.nan
    r = S.simulate(A, np.full(N, 20000.0), 100, True, 0, 25, 0.15, cfg.SQ_ROW, 75)
    assert r["stale"] and r["X"] == 100.0
