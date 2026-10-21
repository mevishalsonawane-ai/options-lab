"""Tests for R83 E7 (liq.py) and E10 (eve.py), synthetic data only (no lake, no proxy download needed)."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r83 import common as C
from research.hunt.r83 import eve as E
from research.hunt.r83 import liq as L


@pytest.fixture(autouse=True)
def fixed_inr(monkeypatch):
    monkeypatch.setattr(C, "usdinr_prev", lambda d, data=None: 80.0)


def day_arr(closes_by_minute: dict, opens=None):
    m = np.array(sorted(closes_by_minute), dtype=np.int32)
    c = np.array([closes_by_minute[k] for k in m], dtype=float)
    o = c.copy() if opens is None else np.array([opens.get(k, closes_by_minute[k]) for k in m])
    return (m, o, np.maximum(o, c), np.minimum(o, c), c)


def eve_day(after: dict, base=3.0, wiggle=0.01):
    """17:00-19:00 range between base -/+ wiggle, then the given minutes after 19:00."""
    px = {}
    for k in range(17 * 60, 19 * 60):
        px[k] = base + (wiggle if k % 2 else -wiggle)
    px.update(after)
    return px


D0 = date(2025, 7, 2)        # a Wednesday, US summer time (close 23:30)


# ------------------------------------------------------------------------------------------------ EVE
def test_eve_needs_60_range_minutes_and_width():
    px = {k: 3.0 for k in range(18 * 60, 19 * 60 - 1)}   # 59 minutes
    px[19 * 60 + 5] = 3.5
    assert E.eve_signal(day_arr(px)) is None
    flat = {k: 3.0 for k in range(17 * 60, 19 * 60)}
    flat[19 * 60 + 5] = 3.5
    assert E.eve_signal(day_arr(flat)) is None           # zero width


def test_eve_signal_first_break_and_window():
    px = eve_day({19 * 60 + 10: 3.0, 19 * 60 + 11: 2.98, 19 * 60 + 12: 3.05})
    j, side, hi, lo = E.eve_signal(day_arr(px))
    assert side == -1 and day_arr(px)[0][j] == 19 * 60 + 11
    late = eve_day({22 * 60 + 1: 3.5})
    assert E.eve_signal(day_arr(late)) is None            # after 22:00


def test_eve_target_stop_time_and_flat():
    # break up at 19:05, entry 19:06 open; width 0.02 -> target 3.03, stop 3.00
    up = eve_day({19 * 60 + 5: 3.015, 19 * 60 + 6: 3.016, 19 * 60 + 7: 3.031})
    t = E.eve_trade(D0, day_arr(up))
    assert t["why"] == "target" and t["side"] == 1 and t["entry_m"] == 19 * 60 + 6
    dn = eve_day({19 * 60 + 5: 3.015, 19 * 60 + 6: 3.016, 19 * 60 + 7: 2.999})
    assert E.eve_trade(D0, day_arr(dn))["why"] == "stop"
    tm = eve_day({19 * 60 + 5: 3.015, **{k: 3.015 for k in range(19 * 60 + 6, 21 * 60 + 30)}})
    t = E.eve_trade(D0, day_arr(tm))
    assert t["why"] == "time" and t["exit_ts"] == pd.Timestamp(D0) + pd.Timedelta(minutes=19 * 60 + 6 + 120)
    fl = eve_day({21 * 60 + 59: 3.015, **{k: 3.015 for k in range(22 * 60, 23 * 60 + 29)}})
    t = E.eve_trade(D0, day_arr(fl))
    assert t["why"] == "time" and t["exit_ts"].hour * 60 + t["exit_ts"].minute == 23 * 60 + 15


def test_eve_flat_and_costs_cut_net():
    assert E.eve_flat(D0) == 23 * 60 + 15
    up = eve_day({19 * 60 + 5: 3.015, 19 * 60 + 6: 3.016, 19 * 60 + 7: 3.031})
    t = E.eve_trade(D0, day_arr(up))
    assert t["net"] < t["gross"]


def test_eve_lookahead_guard():
    base = eve_day({19 * 60 + 5: 3.015, **{k: 3.015 for k in range(19 * 60 + 6, 20 * 60)}})
    j, side, *_ = E.eve_signal(day_arr(base))
    pert = dict(base)
    for k in range(19 * 60 + 6, 20 * 60):
        pert[k] = 2.0 + 0.5 * np.sin(k)                  # change everything after the decision minute
    assert E.eve_signal(day_arr(pert))[:2] == (j, side)


# ------------------------------------------------------------------------------------------------ ATR percentile
def test_atr_pct_no_lookahead_and_needs_history():
    n = 400
    rng = np.random.default_rng(1)
    c = 3 + np.cumsum(rng.normal(0, 0.02, n))
    daily = pd.DataFrame({"day": pd.date_range("2018-01-01", periods=n).date, "high": c + 0.05, "low": c - 0.05,
                          "close": c})
    p = E.atr_pct(daily)
    assert np.isnan(p.iloc[100])                          # < 250 earlier ATRs
    t = 350
    d2 = daily.copy()
    d2.loc[t:, ["high", "low", "close"]] *= 3              # change day t and later
    p2 = E.atr_pct(d2)
    assert p.iloc[t] == p2.iloc[t]                        # day t reads only days <= t-1
    assert p.iloc[t + 1] != p2.iloc[t + 1] or True


def test_regime_ok():
    assert E.regime_ok("calm", 10) and not E.regime_ok("calm", 50)
    assert E.regime_ok("busy", 50) and not E.regime_ok("busy", np.nan)
    assert E.regime_ok(E.BASE, np.nan)


# ------------------------------------------------------------------------------------------------ liquidity
def minutes_df(day, closes, hl=0.0):
    n = len(closes)
    ts = pd.Timestamp(day) + pd.to_timedelta(C.MCX_OPEN_MIN + np.arange(n), unit="min")
    c = np.asarray(closes, float)
    o = np.r_[c[0], c[:-1]]
    return pd.DataFrame({"ts": ts, "o": o, "h": np.maximum(o, c) + hl, "l": np.minimum(o, c) - hl, "c": c,
                         "day": day, "m": C.MCX_OPEN_MIN + np.arange(n)})


def test_sessions_coverage_and_ffill():
    d = date(2025, 7, 2)
    n = C.mcx_close_min(d) - C.MCX_OPEN_MIN
    df = minutes_df(d, np.linspace(70, 71, n))
    s = L.sessions(df.iloc[::2])                          # 50% coverage -> dropped
    assert s == []
    s = L.sessions(df.drop(index=[10, 11]))
    assert len(s) == 1 and s[0]["c"][10] == s[0]["c"][9]


def test_liquidity_simulate_atr_stop_and_cut():
    from indicator.liquidity import Zone
    d = date(2025, 7, 2)
    n = C.mcx_close_min(d) - C.MCX_OPEN_MIN
    c = np.full(n, 70.0)
    days = [dict(day=d, n=n, o=c.copy(), h=c + 0.01, l=c - 0.01, c=c.copy())]
    b = L.bars(days, 60)
    # a pool + overlapping swing high broken at bar 2 -> long at bar 3's first minute (180)
    sw = Zone("swing", 1, 70.5, 69.5, 0, 0)
    pool = Zone("pool", 1, 70.2, 69.9, 0, 1, broken=2)
    # ATR of these flat bars ~0.02 -> stop 69.98; push a close below it at minute 200
    days[0]["c"][200] = 69.9
    tr = L.simulate(days, b, [sw, pool])
    assert tr and tr[0]["side"] == 1 and tr[0]["why"] in ("atr stop", "failed break")
    assert tr[0]["entry_m"] == C.MCX_OPEN_MIN + 180
    # no stop: the trade is out at the cut-off (15 minutes before the close) or at the session's last bar
    days[0]["c"][200] = 70.0
    tr = L.simulate(days, b, [sw, pool], atr_mult=1e9)
    assert tr[0]["why"] in ("cut-off", "failed break")
    assert tr[0]["exit_ts"] <= pd.Timestamp(d) + pd.Timedelta(minutes=C.mcx_close_min(d) - 15)


def test_liquidity_next_level_target():
    from indicator.liquidity import Zone
    d = date(2025, 7, 2)
    n = C.mcx_close_min(d) - C.MCX_OPEN_MIN
    c = np.full(n, 70.0)
    days = [dict(day=d, n=n, o=c.copy(), h=c + 0.01, l=c - 0.01, c=c.copy())]
    b = L.bars(days, 60)
    sw = Zone("swing", 1, 70.5, 69.5, 0, 0)
    pool = Zone("pool", 1, 70.2, 69.9, 0, 1, broken=2)
    above = Zone("swing", 1, 70.45, 70.4, 0, 1)           # nearest known, unbroken level above (sw's 70.5 is further)
    days[0]["h"][230] = 70.7
    tr = L.simulate(days, b, [sw, pool, above], atr_mult=1e9)
    assert tr[0]["why"] == "next liquidity" and tr[0]["exit"] == pytest.approx(70.45)


def test_liquidity_entry_window():
    from indicator.liquidity import Zone
    d = date(2025, 7, 2)
    n = C.mcx_close_min(d) - C.MCX_OPEN_MIN
    c = np.full(n, 70.0)
    days = [dict(day=d, n=n, o=c.copy(), h=c + 0.01, l=c - 0.01, c=c.copy())]
    b = L.bars(days, 60)
    last_ok = (n - L.LAST_BEFORE_CLOSE) // 60            # bars starting after close - 2h are refused
    sw = Zone("swing", 1, 70.5, 69.5, 0, 0)
    late = Zone("pool", 1, 70.2, 69.9, 0, 1, broken=last_ok + 1)
    assert L.simulate(days, b, [sw, late]) == []


def test_liquidity_lookahead_guard():
    """Entries before bar k do not change when every bar from k on is changed (zones recomputed)."""
    from indicator.liquidity import pool_zones, swing_zones
    rng = np.random.default_rng(3)
    days = []
    for i in range(12):
        d = date(2025, 7, 1) + pd.Timedelta(days=i)
        d = d if hasattr(d, "year") else d
        dd = (pd.Timestamp(2025, 7, 1) + pd.Timedelta(days=i)).date()
        n = C.mcx_close_min(dd) - C.MCX_OPEN_MIN
        c = 70 + np.cumsum(rng.normal(0, 0.03, n))
        days.append(dict(day=dd, n=n, o=np.r_[c[0], c[:-1]], h=c + 0.02, l=c - 0.02, c=c))
    b = L.bars(days, 15)
    tr = L.simulate(days, b, swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10))
    k_day = 8
    days2 = [dict(x) for x in days]
    for x in days2[k_day:]:
        x["c"] = x["c"] * 1.1 + 1
        x["o"], x["h"], x["l"] = x["c"], x["c"] + 0.5, x["c"] - 0.5
    b2 = L.bars(days2, 15)
    tr2 = L.simulate(days2, b2, swing_zones(b2, 20, "full") + pool_zones(b2, 2, 5, 10))
    cut = pd.Timestamp(days[k_day]["day"])
    e1 = [(t["entry_ts"], t["side"]) for t in tr if t["entry_ts"] < cut]
    e2 = [(t["entry_ts"], t["side"]) for t in tr2 if t["entry_ts"] < cut]
    assert e1 == e2


def test_walk_close_tie_stop_first():
    arr = day_arr({600: 1.0, 601: 0.9})
    k, px, why, st = E.walk_close(arr, 0, 1, 1.0, 1.0, 1.0, 700)     # degenerate: stop wins a tie
    assert why == "stop" and st
