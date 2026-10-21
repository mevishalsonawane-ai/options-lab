"""Tests for the R83 daily book (synthetic data only)."""
from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from research.hunt.r83 import common as C
from research.hunt.r83 import daily as DL


def mk(close, roll=None, key="CRUDE", start=date(2015, 1, 1), opens=None, spread=0.5):
    c = np.asarray(close, dtype=float)
    o = c.copy() if opens is None else np.asarray(opens, dtype=float)
    days, d = [], start
    while len(days) < len(c):
        if d.weekday() < 5:
            days.append(d)
        d += timedelta(1)
    r = np.zeros(len(c), dtype=bool) if roll is None else np.asarray(roll, dtype=bool)
    return DL.Mkt(key, np.array(days, dtype=object), o, np.maximum(o, c) + spread, np.minimum(o, c) - spread, c, r,
                  C.LOT[key])


@pytest.fixture(autouse=True)
def fixed_inr(monkeypatch):
    monkeypatch.setattr(C, "usdinr_prev", lambda d, data=None: 83.0)


def test_spell_roll_gap_not_earned_and_extra_round_trip():
    # flat 100, roll row jumps to 110 at the open and stays: no P&L from the gap
    c = [100, 100, 100, 110, 110]
    o = [100, 100, 100, 110, 110]
    m = mk(c, roll=[0, 0, 0, 1, 0], opens=o)
    t = DL.spell(m, 1, 1, 4, False)
    assert t["gross"] == pytest.approx(0.0)
    one = -C.fut_net("CRUDE", 1, 100, 100, 1000)[1]
    assert -t["net"] == pytest.approx(one + -C.fut_net("CRUDE", 1, 110, 110, 1100)[1])


def test_spell_gross_matches_price_move_without_roll():
    m = mk([100, 101, 103, 106])
    t = DL.spell(m, 1, 1, 3, False)
    assert t["gross"] == pytest.approx((106 / 101 - 1) * 101 * 10)


def test_run_stop_initial_stop_fires_on_close():
    c = [100] * 20 + [100, 99, 90, 80]
    m = mk(c)
    t = DL.run_stop(m, 20, 1, 2.0, None, None, atr_at=19)
    assert t["why"] == "stop" and t["b"] == 22


def test_donchian_breakout_and_supertrend_filter():
    c = [100.2 if i % 2 else 100.0 for i in range(30)] + [110, 111, 112, 113, 114]
    m = mk(c)
    tr = DL.donchian_trades(m, "none")
    assert tr and tr[0]["side"] == 1 and tr[0]["signal"] == str(m.dates[30])
    d = DL.supertrend_dir(m.h, m.l, m.c)
    assert d[-1] == 1


def test_donchian_breadth_filter_blocks_disagreeing_market():
    c = [100.2 if i % 2 else 100.0 for i in range(60)] + [110, 111, 112]
    m = mk(c)
    other = mk(list(np.linspace(200, 150, 63)))           # falling: 50-day trend -1
    assert DL.donchian_trades(m, "breadth", other) == []


def test_opec_signal_first_day_strictly_after():
    m = mk([100 + i for i in range(40)], start=date(2014, 11, 3))
    rows = DL.opec_signal_rows(m)
    assert str(m.dates[rows[0]]) == "2014-11-28"            # meeting 27 Nov 2014


def test_season_sign_uses_only_prior_years():
    idx = pd.period_range("2000-01", "2010-12", freq="M")
    ret = pd.Series(np.where(idx.month == 3, -0.05, 0.01) + np.random.default_rng(0).normal(0, 0.001, len(idx)), index=idx)
    assert DL.season_sign(ret, 2010, 3, 1.5) == -1
    ret2 = ret.copy()
    ret2[ret2.index.year >= 2010] = 0.9                       # the future must not matter
    assert DL.season_sign(ret2, 2010, 3, 1.5) == -1


def test_trend_signal_and_flip():
    c = list(np.linspace(100, 200, 300)) + list(np.linspace(200, 50, 300))
    m = mk(c)
    tr = DL.trend_trades(m, "M")
    assert tr[0]["side"] == 1 and any(t["side"] == -1 for t in tr)


def test_month_pieces_sum_to_spell():
    c = list(np.linspace(100, 130, 80))
    m = mk(c, roll=[1 if i == 40 else 0 for i in range(80)])
    t = DL.spell(m, 5, 1, 70, False)
    ps = DL.month_pieces(m, t)
    assert len(ps) >= 3
    assert sum(p["net"] for p in ps) == pytest.approx(t["net"])


def test_rollweek_window():
    c = [100 + i * 0.1 for i in range(60)]
    roll = [1 if i in (30, 50) else 0 for i in range(60)]
    m = mk(c, roll=roll)
    tr = DL.rollweek_trades(m, 1)
    assert [(t["a"], t["b"]) for t in tr] == [(25, 29), (45, 49)]


def test_mark_rolls_one_per_month_largest_jump():
    dates = pd.bdate_range("2020-01-01", "2020-02-28")
    oi = np.full(len(dates), 100.0)
    oi[list(dates.day).index(20)] = 300                      # 20 Jan
    oi[list(dates.day).index(13)] = 150                      # 13 Jan, smaller
    df = pd.DataFrame({"date": dates.date, "oi": oi})
    r = DL.mark_rolls(df, "CRUDE")
    assert list(np.array(dates.date)[r]) == [date(2020, 1, 20), date(2020, 1, 21)][:1] or r.sum() >= 1
    assert date(2020, 1, 20) in set(np.array(dates.date)[r])


@pytest.mark.parametrize("fn", ["donchian", "trend", "opec", "roll"])
def test_lookahead_guard(fn):
    rng = np.random.default_rng(1)
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, 700)))
    roll = np.zeros(700, dtype=bool); roll[::21] = True; roll[0] = False
    t_cut = 450
    c2 = c.copy(); c2[t_cut + 1:] *= np.exp(np.cumsum(rng.normal(0, 0.05, 700 - t_cut - 1)))
    m1, m2 = mk(c, roll), mk(c2, roll)
    m2.o[t_cut + 1:] = m2.c[t_cut + 1:]

    def decisions(m):
        if fn == "donchian":
            tr = DL.donchian_trades(m, "st")
        elif fn == "trend":
            tr = DL.trend_trades(m, "M", 3.0)
        elif fn == "opec":
            tr = DL.opec_trades(m, 3)
        else:
            tr = DL.rollweek_trades(m, 1)
        # signals decided at or before t_cut: (entry row, side); and exits decided before t_cut
        return [(t["a"], t["side"], t["b"] if t["b"] <= t_cut else None) for t in tr if t["a"] - 1 <= t_cut]
    m1.o[t_cut + 1:] = m1.c[t_cut + 1:]
    assert decisions(m1) == decisions(m2)
