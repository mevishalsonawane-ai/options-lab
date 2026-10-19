import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import candles as CA
from research.hunt.r87_candle_anatomy import cfg
from research.hunt.r87_candle_anatomy.tests import synth


def test_resample_anchors_at_0915_and_drops_thin_candles():
    m = synth.minutes(days=2, chain=False)
    k = CA.resample(m, 5)
    assert (k.slot % 5 == 0).all()
    assert k.ts.dt.time.min().strftime("%H:%M") == "09:15"
    first = m[m.ts < m.ts.iloc[0] + pd.Timedelta(minutes=5)]
    assert k.o.iloc[0] == first.o.iloc[0] and k.h.iloc[0] == first.h.max() and k.c.iloc[0] == first.c.iloc[-1]
    # a candle with fewer than half its minutes is dropped
    day = m.ts.dt.normalize().iloc[0]
    cut = m[~((m.ts >= day + pd.Timedelta(minutes=cfg.OPEN_MIN + 10)) & (m.ts < day + pd.Timedelta(minutes=cfg.OPEN_MIN + 13)))]
    k2 = CA.resample(cut, 5)
    assert not ((k2.day == day) & (k2.slot == 10)).any()
    # never spans sessions
    assert (k.groupby(["day", "slot"]).size() == 1).all()


def test_resample_15m_last_candle_is_full():
    m = synth.minutes(days=1, chain=False)
    k = CA.resample(m, 15)
    assert k.slot.max() == 360 and len(k) == 25


def test_anatomy_numbers():
    k = synth.bars([(100, 110, 90, 105), (100, 100, 100, 100), (105, 106, 95, 96)])
    a = CA.anatomy(k)
    r = a.iloc[0]
    assert r.rng == 20 and r.body == 5 and r.up == 5 and r.lo == 10
    assert np.isclose(r.body_pct, 0.25) and np.isclose(r.up_pct, 0.25) and np.isclose(r.lo_pct, 0.5)
    assert np.isclose(r.clv, 0.75) and r.col == 1
    flat = a.iloc[1]
    assert flat.body_pct == 0 and flat.clv == 0.5 and flat.col == 0
    assert a.iloc[2].col == -1


def test_atr_uses_previous_candles_only():
    m = synth.minutes(days=3, chain=False)
    k = CA.anatomy(CA.resample(m, 5))
    a1 = k.atr.to_numpy()
    k2 = k.copy()
    k2.loc[k2.index[40], "h"] *= 1.05                   # a huge candle at 40 changes ATR only from 41 on
    a2 = CA.anatomy(k2[["ts", "tl", "day", "slot", "o", "h", "l", "c", "n"]]).atr.to_numpy()
    assert np.allclose(a1[:41], a2[:41], equal_nan=True)
    assert a2[41] > a1[41]
    assert np.isnan(a1[:14]).all() and np.isfinite(a1[14])


def _with_atr(rows, atr=10.0, trend=0):
    k = CA.anatomy(synth.bars(rows))
    k["atr"] = atr
    k["size"] = k.rng / atr
    k["trend"] = trend
    return k


def test_shapes_doji_hammer_star_wicks():
    k = _with_atr([(100, 106, 94, 100.5),      # long-legged doji (size 1.2)
                   (100, 101, 90, 101),        # hammer shape: lower 0.82, body 0.09 -> doji (not hammer)
                   (100, 102, 90, 101.5),      # hammer shape: lower 0.83, body 0.125, upper 0.042
                   (100, 112, 99.8, 101.5),    # inverted shape: upper 0.86, body 0.12
                   (100, 110, 100, 110),       # marubozu green
                   (100, 105, 95, 102)], trend=-1)
    s = CA.shapes(k)
    assert s.long_legged_doji[0] and s.doji[0] and s.both_big_wicks[0]
    assert s.doji[1] and s.dragonfly_doji[1] and not s.hammer[1]
    assert s.hammer[2] and not s.hanging_man[2] and s.big_lower_wick[2]
    assert s.inverted_hammer[3] and not s.shooting_star[3] and s.big_upper_wick[3]
    assert s.marubozu_green[4] and s.small_both_wicks[4] and not s.marubozu_red[4]
    assert not s.doji[5] and s.spinning_top[5]
    up = CA.shapes(_with_atr([(100, 102, 90, 101.5), (100, 112, 99.8, 101.5)], trend=1))
    assert up.hanging_man[0] and up.shooting_star[1]


def test_shapes_need_atr():
    k = CA.anatomy(synth.bars([(100, 110, 90, 105)]))       # ATR unknown (first candle)
    assert not CA.shapes(k).any(axis=None)


def test_patterns():
    k = _with_atr([(100, 104, 96, 97),     # red
                   (96, 105, 95, 104),     # green engulfing the red body, outside bar
                   (103, 104, 100, 101),   # inside bar (red)
                   (101, 104.02, 99, 99.5)])  # red, tweezer top vs bar 1? (high 105 vs 104.02: no)
    p = CA.patterns(k)
    assert p.bull_engulfing[1] and p.outside_bar[1] and not p.bear_engulfing[1]
    assert p.inside_bar[2] and not p.inside_bar[1]
    assert not p.any(axis=None) or not p.iloc[0].any()
    k2 = _with_atr([(100, 110, 99, 109), (109, 110.01, 104, 105)])
    assert CA.patterns(k2).tweezer_top[1]


def test_patterns_do_not_cross_sessions():
    k = _with_atr([(100, 104, 96, 97), (96, 105, 95, 104)])
    k.loc[1, "day"] = pd.Timestamp("2024-01-03")
    assert not CA.patterns(k).iloc[1].any()


def test_three_candle_patterns():
    k = _with_atr([(100, 110.5, 99.5, 110), (110, 120.5, 109.5, 120), (120, 130.5, 119.5, 130)])
    assert CA.patterns(k).three_white[2]
    k = _with_atr([(110, 110.5, 99.5, 100), (100, 101, 98, 99), (99, 108, 98.5, 107)])
    assert CA.patterns(k).morning_star[2]


def test_grid_cell():
    k = _with_atr([(100, 110, 100, 110), (100, 106, 94, 100.5)])
    g = CA.grid_cell(k)
    assert g[0] == "BLU0L0GSn" and g[1] == "BDU2L2GSn"
    k["size"] = np.nan
    assert CA.grid_cell(k).isna().all()
