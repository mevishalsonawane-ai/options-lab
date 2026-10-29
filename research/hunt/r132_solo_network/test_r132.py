"""R132 tests: the Solo (midday) port against hand-made cases, the look-ahead guard, the network's vetoes and re-pick, F2's
point-in-time threshold, the option trade and the twins.

    python -m pytest research/hunt/r132_solo_network/test_r132.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r132 as S  # noqa: E402

N = S.K.N


def _day(open_=100.0, close_1159=106.0, hi=106.5, lo=99.5, first=0, n_pre=None):
    """A session whose morning runs from `open_` to `close_1159`, with the given range; flat 100 bars after."""
    o, h, l, c = (np.full(N, np.nan) for _ in range(4))
    rows = range(first, S.ROW_1159 + 1) if n_pre is None else range(S.ROW_1159 + 1 - n_pre, S.ROW_1159 + 1)
    rows = list(rows)
    path = np.linspace(open_, close_1159, len(rows))
    for k, r in enumerate(rows):
        o[r] = path[k - 1] if k else open_
        c[r] = path[k]
        h[r] = max(o[r], c[r])
        l[r] = min(o[r], c[r])
    h[rows[len(rows) // 2]] = hi
    l[rows[len(rows) // 3]] = lo
    for r in range(S.ROW_1200, N):
        o[r] = h[r] = l[r] = c[r] = close_1159
    return o, h, l, c


# ------------------------------------------------------------------------------------------------ the signal
def test_up_signal_reads_the_morning_and_the_1200_open():
    o, h, l, c = _day()
    o[S.ROW_1200] = 106.2
    s = S.signal(o, h, l, c, atr=10.0)
    assert s["side"] == 1 and s["strength"] == pytest.approx(0.6) and s["index"] == 106.2
    assert s["high"] == 106.5 and s["low"] == 99.5
    assert s["position"] == pytest.approx(0.5 / 7.0)


def test_down_signal_and_its_outer_quarter():
    o, h, l, c = _day(open_=100.0, close_1159=94.0, hi=100.5, lo=93.5)
    s = S.signal(o, h, l, c, atr=10.0)
    assert s["side"] == -1 and s["position"] == pytest.approx(0.5 / 7.0)


def test_no_signal_when_small_mid_range_or_incomplete():
    o, h, l, c = _day(close_1159=104.0, hi=104.5)                 # 0.4 ATR < 0.5
    assert S.signal(o, h, l, c, atr=10.0) is None
    o, h, l, c = _day(close_1159=106.0, hi=110.0)                 # (110-106)/(110-99.5) = 0.38 > 0.25: mid-range
    assert S.signal(o, h, l, c, atr=10.0) is None
    o, h, l, c = _day(close_1159=106.0, hi=108.0, lo=99.5)        # (108-106)/8.5 = 0.235 <= 0.25: signals
    assert S.signal(o, h, l, c, atr=10.0)["side"] == 1
    o, h, l, c = _day()
    c[S.ROW_1159] = np.nan                                        # no 11:59 bar
    assert S.signal(o, h, l, c, atr=10.0) is None
    assert S.signal(*_day(first=2), atr=10.0) is None             # first bar after 09:16
    assert S.signal(*_day(first=1), atr=10.0) is not None         # 09:16 is allowed
    assert S.signal(*_day(n_pre=99), atr=10.0) is None            # fewer than 100 minutes
    assert S.signal(*_day(), atr=np.nan) is None                  # no ATR


def test_1200_price_falls_back_to_the_1159_close():
    o, h, l, c = _day()
    o[S.ROW_1200] = np.nan
    assert S.signal(o, h, l, c, atr=10.0)["index"] == 106.0


def test_look_ahead_guard_the_decision_ignores_every_bar_after_1200():
    o, h, l, c = _day()
    base = S.signal(o, h, l, c, atr=10.0)
    rng = np.random.default_rng(0)
    for arr in (h, l, c):
        arr[S.ROW_1200:] = rng.normal(50, 30, N - S.ROW_1200)
    o[S.ROW_1200 + 1:] = rng.normal(50, 30, N - S.ROW_1200 - 1)
    assert S.signal(o, h, l, c, atr=10.0) == base


# ------------------------------------------------------------------------------------------------ ATR14
def test_atr14_uses_the_14_sessions_before_today_only():
    D = 20
    days = [date(2025, 1, 1) + timedelta(days=i) for i in range(D)]
    h, l, c, o = (np.full((D, N), np.nan) for _ in range(4))
    for i in range(D):
        h[i, 0], l[i, 0], c[i, 0], o[i, 0] = 110.0 + i, 100.0, 105.0, 105.0     # TR = 10 + i (H - L; |H - pc| smaller)
    g = S.D3.Grid(days, o, h, l, c)
    a = S.daily_atr(g)
    assert np.isnan(a[:15]).all()
    assert a[15] == pytest.approx(np.mean([10.0 + i for i in range(1, 15)]))
    h[19, 0] = 999.0                                               # today's own bar never enters today's ATR
    assert S.daily_atr(S.D3.Grid(days, o, h, l, c))[19] == pytest.approx(a[19])


def test_atr14_true_range_includes_the_gap():
    D = 16
    days = [date(2025, 1, 1) + timedelta(days=i) for i in range(D)]
    h, l, c, o = (np.full((D, N), np.nan) for _ in range(4))
    for i in range(D):
        h[i, 0], l[i, 0], c[i, 0], o[i, 0] = 101.0, 99.0, 100.0, 100.0
    h[14, 0], l[14, 0], c[14, 0] = 121.0, 119.0, 120.0            # a 20-point gap up: TR = 21
    a = S.daily_atr(S.D3.Grid(days, o, h, l, c))
    assert a[15] == pytest.approx((13 * 2.0 + 21.0) / 14)


# ------------------------------------------------------------------------------------------------ the walk
def _flat(level=100.0):
    h, l, c = (np.full(N, level) for _ in range(3))
    return h, l, c


def test_index_stop_on_a_close_not_a_wick():
    h, l, c = _flat()
    l[170] = 96.0                                                  # a wick through 97 (stop at 100 - 0.3 x 10)
    c[180] = 97.0                                                  # a close at the stop
    assert S.walk(1, 100.0, 10.0, h, l, c) == ("index_stop", 180)
    h, l, c = _flat()
    c[190] = 103.0
    assert S.walk(-1, 100.0, 10.0, h, l, c) == ("index_stop", 190)


def test_lock_is_earned_by_earlier_bars_and_exits_at_entry():
    h, l, c = _flat(101.0)
    h[200] = 104.5                                                 # 4.5 = 0.45 ATR: earned
    l[200] = 99.0                                                  # the same bar touching entry does not exit
    assert S.walk(1, 100.0, 10.0, h, l, c)[0] == "time"
    l[210] = 100.0                                                 # a later bar touches entry
    assert S.walk(1, 100.0, 10.0, h, l, c) == ("lock", 210)
    h, l, c = _flat(101.0)
    h[200] = 104.4                                                 # not quite 0.45 ATR
    l[210] = 100.0
    assert S.walk(1, 100.0, 10.0, h, l, c)[0] == "time"


def test_stop_is_checked_before_the_lock_and_time_is_1430():
    h, l, c = _flat(101.0)
    h[200] = 105.0
    l[210], c[210] = 96.0, 96.5                                    # touches entry AND closes through the stop
    assert S.walk(1, 100.0, 10.0, h, l, c) == ("index_stop", 210)
    h, l, c = _flat(101.0)
    c[S.ROW_EXIT] = 50.0                                           # the 14:30 bar is never read
    assert S.walk(1, 100.0, 10.0, h, l, c) == ("time", S.ROW_EXIT - 1)


# ------------------------------------------------------------------------------------------------ strikes and the trade
def test_strikes_4_itm_then_less_itm_then_more():
    assert S.strikes_to_try("NIFTY", 1, 25012.0) == [24800.0, 24850.0, 24750.0]
    assert S.strikes_to_try("NIFTY", -1, 25025.0) == [25250.0, 25200.0, 25300.0]     # half up: 25050 ATM
    assert S.strikes_to_try("BANKNIFTY", 1, 51049.0) == [50600.0, 50700.0, 50500.0]


def _contract(price=200.0, vol=1000.0):
    a = np.full((N, 5), np.nan)
    a[:, S.O_] = a[:, S.H_] = a[:, S.L_] = a[:, S.C_] = price
    a[:, S.V_] = vol
    return a


def test_option_trade_buys_1200_open_and_sells_next_open_with_costs():
    a = _contract()
    a[S.ROW_1200, S.O_] = np.nan                                   # no print at 12:00: buy at 12:01
    a[181, S.O_] = 230.0                                           # exit decided on 180: sold at 181's open
    t = S.option_trade(a, 180, 65, date(2025, 3, 4), "NIFTY")
    assert t["buy_row"] == S.ROW_1200 + 1 and t["sell_row"] == 181 and t["sell"] == 230.0
    assert t["net"] < 30 * 65 and t["net"] > 0 and t["netx2"] < t["net"]
    a = _contract()
    a[S.ROW_1200:S.ROW_1200 + 4, S.O_] = np.nan                    # nothing 12:00-12:03: untradable
    assert S.option_trade(a, 180, 65, date(2025, 3, 4), "NIFTY") is None


def test_time_exit_sells_at_1430_open():
    a = _contract()
    a[S.ROW_EXIT, S.O_] = 150.0
    t = S.option_trade(a, S.ROW_EXIT - 1, 65, date(2025, 3, 4), "NIFTY")
    assert t["sell_row"] == S.ROW_EXIT and t["sell"] == 150.0 and t["net"] < 0


# ------------------------------------------------------------------------------------------------ the network's ideas
def _cands():
    d1, d2, d3 = date(2025, 1, 2), date(2025, 1, 3), date(2025, 1, 6)
    rows = [
        dict(und="NIFTY", day=d1, side=1, strength=0.9, ev_call=-50.0, ev_put=100.0, quiet=False, pdir60=0.55, net=500.0),
        dict(und="BANKNIFTY", day=d1, side=-1, strength=0.7, ev_call=0.0, ev_put=200.0, quiet=False, pdir60=0.45, net=-100.0),
        dict(und="FINNIFTY", day=d2, side=-1, strength=0.6, ev_call=10.0, ev_put=5.0, quiet=True, pdir60=0.55, net=-300.0),
        dict(und="NIFTY", day=d3, side=1, strength=0.8, ev_call=0.0, ev_put=-1.0, quiet=False, pdir60=0.50, net=50.0),
    ]
    C = pd.DataFrame(rows).assign(signal=True, expiry=False, tradable=True, netx2=0.0, fold="F3")
    return S.candidates(C)


def test_plain_takes_the_strongest_and_vetoes_never_pass_the_turn():
    C = _cands()
    assert list(S.picks(C, "plain").und) == ["NIFTY", "FINNIFTY", "NIFTY"]
    assert list(S.picks(C, "F1").day) == [date(2025, 1, 3), date(2025, 1, 6)]      # d1: NIFTY call EV -50 -> no trade
    assert list(S.picks(C, "F2").und) == ["NIFTY", "NIFTY"]                          # FINNIFTY quiet
    assert list(S.picks(C, "F3").day) == [date(2025, 1, 2)]                          # 0.50 does not agree; 0.55 vs a put no
    assert list(S.picks(C, "F5").day) == [date(2025, 1, 6)]
    assert list(S.picks(C, "F4").und) == ["BANKNIFTY", "FINNIFTY", "NIFTY"]          # d1: put EV 200 beats call EV -50


def test_expiry_and_untradable_indices_pass_the_turn_in_solo_itself():
    C = pd.DataFrame([
        dict(und="NIFTY", day=date(2025, 1, 2), side=1, strength=0.9, signal=True, expiry=True, tradable=False),
        dict(und="BANKNIFTY", day=date(2025, 1, 2), side=1, strength=0.8, signal=True, expiry=False, tradable=False),
        dict(und="FINNIFTY", day=date(2025, 1, 2), side=1, strength=0.6, signal=True, expiry=False, tradable=True),
    ]).assign(ev_call=0.0, ev_put=0.0, quiet=False, pdir60=0.5, net=1.0, netx2=1.0, fold="F3")
    assert list(S.picks(S.candidates(C), "plain").und) == ["FINNIFTY"]


def test_quiet_threshold_reads_earlier_sessions_only():
    days = [date(2024, 1, 1) + timedelta(days=i) for i in range(60)]
    v = np.linspace(-2, 0, 60)
    Nw = pd.DataFrame(dict(und="NIFTY", day=days, rg60=v))
    q = S.quiet_flags(Nw)
    assert not q.iloc[:S.Q_MIN].any()                              # fewer than 20 earlier sessions: never quiet
    assert not q.iloc[S.Q_MIN:].any()                              # a rising forecast is never in its own past's bottom third
    Nw.loc[40, "rg60"] = -5.0
    q2 = S.quiet_flags(Nw)
    assert q2.iloc[40] and (q2.iloc[:40] == q.iloc[:40]).all()     # a later value cannot change an earlier flag
    Nw2 = Nw.copy()
    Nw2.loc[50:, "rg60"] = 99.0
    assert (S.quiet_flags(Nw2).iloc[:50] == q2.iloc[:50]).all()


def test_network_rows_pick_the_best_cell_per_side_at_1200():
    rec = dict(und="NIFTY", day=date(2025, 1, 2), row=S.ROW_1159, rg60=-1.0, pdir60=0.5)
    rec.update({f"ev_{j}": float(j) for j in range(S.NJ)})
    other = dict(rec, row=S.ROW_1159 - 5)
    X = S.network_rows(pd.DataFrame([rec, other, dict(rec, und="SENSEX")]))
    assert len(X) == 1 and X.ev_call.iloc[0] == 14.0 and X.ev_put.iloc[0] == 15.0
    assert S.ROW_1159 == 164 and S.ROW_1159 in S.K.DECISION_ROWS


# ------------------------------------------------------------------------------------------------ twins and gates
def test_twins_veto_and_repick():
    rng = np.random.default_rng(0)
    C = _cands()
    plain = S.picks(C, "plain")
    T = S.picks(C, "F2")
    p, m = S.twin_p(C, plain, T, "F2", rng)
    assert 0 < p <= 1 and m == pytest.approx(plain.net.sum() * 2 / 3, rel=0.2)
    p4, m4 = S.twin_p(C, plain, S.picks(C, "F4"), "F4", rng)
    assert m4 == pytest.approx((500 - 100) / 2 - 300 + 50, abs=40)


def test_paired_difference_and_gates():
    rng = np.random.default_rng(0)
    C = _cands()
    plain, T = S.picks(C, "plain"), S.picks(C, "F2")
    pr = S.paired(plain, T, sorted(C.day.unique()), rng)
    assert pr["total"] == pytest.approx(300.0) and pr["changed_days"] == 1
    r = dict(summary=S.summary(T.assign(exit_why="time")), folds_positive=1)
    g = S.gates_wf(r, 0.05)
    assert g == dict(n=False, net=False, folds=False, q=True)       # netx2 is 0 in this toy: the 2x gate fails
