"""Tests for the R83 events worker (synthetic data only; no downloads needed)."""
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r83 import common as C
from research.hunt.r83 import events as E


# ------------------------------------------------------------------------------------------------ calendars
def test_eia_dst_and_exceptions():
    ev = E.eia_events(date(2025, 1, 1), date(2026, 3, 31))
    assert ev[date(2025, 7, 9)] == 20 * 60          # US summer: 10:30 ET = 20:00 IST
    assert ev[date(2025, 12, 10)] == 21 * 60        # US winter: 21:00 IST
    assert date(2025, 1, 22) not in ev and ev[date(2025, 1, 23)] == 22 * 60 + 30   # official Thu 12:00 ET
    assert date(2025, 12, 24) not in ev and date(2025, 12, 29) not in ev           # 17:00 ET = after MCX close


def test_eia_pre2025_holiday_week_skipped():
    ev = E.eia_events(date(2019, 1, 1), date(2019, 12, 31))
    assert date(2019, 9, 4) not in ev               # Labor Day Mon 2 Sep 2019
    assert date(2019, 9, 11) in ev
    assert all(pd.Timestamp(d).weekday() == 2 for d in ev)


def test_storage_thursday_and_holiday_skip():
    ev = E.storage_events(date(2024, 11, 1), date(2024, 12, 31))
    assert date(2024, 11, 28) not in ev             # Thanksgiving Thursday
    assert ev[date(2024, 12, 5)] == 21 * 60
    assert all(pd.Timestamp(d).weekday() == 3 for d in ev)


# ------------------------------------------------------------------------------------------------ synthetic books
def mk_book(prices: dict, mkt="CRUDE", atr=1.0, trend=None):
    """prices: {day: (minutes array, closes array)}; h = l = c."""
    b = E.Book(mkt=mkt, notional=lambda px, d: px * 10 * 85.0)
    for d, (m, c) in prices.items():
        c = np.asarray(c, float)
        b.days[d] = (np.asarray(m, int), c, c, c, np.arange(len(m)))
        b.atr[d] = atr
        b.trend[d] = trend or 0
    return b


D = date(2024, 7, 10)    # a Wednesday, US summer time (flat 23:15 = 1395)
T = 20 * 60


def series(spike=2.0, after=None):
    m = np.arange(19 * 60, 23 * 60 + 30)
    c = np.full(len(m), 70.0)
    c[(m >= T) & (m <= T + 15)] = 70.0 + spike
    if after is not None:
        c[m > T + 15] = after(m[m > T + 15])
    return m, c


def test_fade_enters_against_spike_and_hits_target():
    m, c = series(2.0, after=lambda x: np.where(x < T + 40, 72.0, 70.0))
    t = E.fade(mk_book({D: (m, c)}), D, T, 0.2)
    assert t["side"] == -1 and t["entry_m"] == T + 16 and t["why"] == "target"
    assert t["exit"] == 70.0 and t["gross"] > 0


def test_fade_threshold_and_stop():
    m, c = series(0.1)
    assert E.fade(mk_book({D: (m, c)}), D, T, 0.2) is None          # 0.1 < 0.2 x ATR 1.0
    m, c = series(2.0, after=lambda x: np.where(x < T + 30, 72.0, 72.5))
    t = E.fade(mk_book({D: (m, c)}), D, T, 0.2)
    assert t["why"] == "stop" and t["stop"] == 72.1


def test_follow_break_and_time_exit():
    m = np.arange(19 * 60, 23 * 60 + 30)
    c = np.where((m >= T) & (m <= T + 30), 70 + 0.05 * ((m - T) % 3), 70.0)
    c = np.where(m > T + 40, 70.12, c)               # breaks above 70.10 (target 70.20), then holds: time exit 90 min later
    t = E.follow(mk_book({D: (m, c)}), D, T, 30)
    assert t["side"] == 1 and t["sig_m"] == T + 41 and t["why"] == "time"
    assert t["entry_m"] == T + 42 and (t["exit_ts"] - t["entry_ts"]).seconds // 60 == 90


def test_flat_by_close():
    m = np.arange(22 * 60, 23 * 60 + 30)
    c = np.where(m > 22 * 60 + 40, 70.12, 70.0)
    c = np.where((m >= 22 * 60 + 30) & (m <= 22 * 60 + 40), 70 + 0.1 * (m % 2), c)
    t = E.follow(mk_book({D: (m, c)}), D, 22 * 60 + 30, 10)
    assert t["why"] == "flat" and t["exit_ts"].hour * 60 + t["exit_ts"].minute == C.flat_min(D)


def test_orb_filters():
    O = C.et_to_ist_min(D, 9, 0)                     # 18:30 IST
    m = np.arange(17 * 60, 23 * 60)
    c = np.where((m >= O) & (m <= O + 14), 70 + 0.01 * (m % 2), 70.0)
    c = np.where(m >= O + 20, 70.2, c)
    up = mk_book({D: (m, c)}, trend=1)
    assert E.orb(up, D)["side"] == 1
    assert E.orb(up, D, "sma")["side"] == 1
    assert E.orb(mk_book({D: (m, c)}, trend=-1), D, "sma") is None
    other_dn = mk_book({D: (m, 3.0 - 0.001 * (m - m[0]))}, mkt="NG")
    other_up = mk_book({D: (m, 3.0 + 0.001 * (m - m[0]))}, mkt="NG")
    assert E.orb(up, D, "breadth", other_dn) is None
    assert E.orb(up, D, "breadth", other_up)["side"] == 1


def test_lookahead_guard_decisions_ignore_later_bars():
    """Perturbing every bar after the decision minute must not change the signal, side, entry or stop."""
    m, c = series(2.0, after=lambda x: np.where(x < T + 40, 72.0, 70.0))
    t0 = E.fade(mk_book({D: (m, c)}), D, T, 0.2)
    c2 = c.copy()
    rng = np.random.default_rng(1)
    later = m > t0["sig_m"] + 1                       # after the entry minute
    c2[later] = 71.5 + rng.normal(0, 0.3, later.sum())
    t1 = E.fade(mk_book({D: (m, c2)}), D, T, 0.2)
    for k in ("side", "sig_m", "entry_m", "entry", "stop", "tgt"):
        assert t0[k] == t1[k]
    # ORB: same
    O = C.et_to_ist_min(D, 9, 0)
    mm = np.arange(17 * 60, 23 * 60)
    cc = np.where((mm >= O) & (mm <= O + 14), 70 + 0.01 * (mm % 2), 70.0)
    cc = np.where(mm >= O + 20, 70.2, cc)
    a = E.orb(mk_book({D: (mm, cc)}), D)
    cc2 = cc.copy()
    cc2[mm > a["entry_m"]] = 69.0
    b = E.orb(mk_book({D: (mm, cc2)}), D)
    assert (a["side"], a["entry_m"], a["stop"], a["tgt"]) == (b["side"], b["entry_m"], b["stop"], b["tgt"])


def test_atr_and_trend_are_previous_day():
    days = [date(2024, 1, 1) + pd.Timedelta(days=i) for i in range(30)]
    daily = pd.DataFrame({"high": np.arange(30) + 1.0, "low": np.arange(30) * 1.0, "close": np.arange(30) + 0.5},
                         index=[d.date() if hasattr(d, "date") else d for d in days])
    df = pd.DataFrame({"day": [daily.index[-1]] * 3, "m": [600, 601, 602], "h": 1.0, "l": 1.0, "c": 1.0})
    b = E.book_from_frame("CRUDE", df, daily, lambda p, d: p)
    last = daily.index[-1]
    exp = C.atr(daily.iloc[:-1], 14).iloc[-1]        # computed without the last day
    assert abs(b.atr[last] - exp) < 1e-12 and b.trend[last] == 1


def test_twin_replay_same_side_and_fractions():
    m, c = series(2.0, after=lambda x: np.where(x < T + 40, 72.0, 70.0))
    b = mk_book({D: (m, c), date(2024, 7, 12): (m, np.full(len(m), 70.0))})
    t = E.fade(b, D, T, 0.2)
    net = E.replay_twin(b, date(2024, 7, 12), t["tpl"], t["side"])
    assert net < 0                                      # flat day: time exit, pays costs only
