"""R85 Part 2 harness tests on a synthetic R53 Market: entry, stops, profit lock, trail, underlying stop, time exit,
expiry-day skip, costs, walk-forward and twins."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r53.mcx.data import Market
from research.hunt.r85 import strategy as S


def market(prem_path, day="2025-09-02", start=600, F=None, strike=100.0, printed=None):
    """One day of minutes from `start`; the 1-ITM call (offset -1, strike 100) prints premium bars given as a list of
    (o, h, l, c) per minute (None = no print)."""
    n = len(prem_path)
    ts = pd.Timestamp(day) + pd.to_timedelta(start + np.arange(n), unit="m")
    F = np.full(n, 101.0) if F is None else np.asarray(F, float)
    rows = []
    for i, p in enumerate(prem_path):
        for cp, off in ((0, -1), (1, 1), (0, 0), (1, 0)):
            k = strike + (0 if off == -1 else 5)
            if p is None or (cp, off) not in ((0, -1), (1, 1)):
                rows.append((ts[i], cp, off, k, 1, 1, 1, 1, 0, False))
            else:
                rows.append((ts[i], cp, off, k, *p, 10, False))
    O = pd.DataFrame(rows, columns=["ts", "cp", "offset", "strike", "open", "high", "low", "close", "volume", "f1"])
    return Market("GOLDM", ts.values, F, O)


def flat(v, n):
    return [(v, v, v, v)] * n


def test_entry_is_next_printed_open_and_time_exit():
    m = market(flat(10, 3) + [(11, 11, 11, 11)] + flat(12, 30))
    t = S.sim_buy(m, m, 0, +1, hold=10, X=0.5, L=5.0)
    assert t["fill"] == 10 and t["entry_ts"] == pd.Timestamp(m.ts[1])
    assert t["why"] == "time" and t["exit"] == 12 and t["hold_min"] == 10
    assert t["gross"] == pytest.approx(20.0)                      # (12 - 10) x 10 units
    assert t["cost"] > 0 and t["net"] == pytest.approx(t["gross"] - t["cost"])


def test_no_print_within_two_minutes_means_no_trade():
    m = market([(10, 10, 10, 10), None, None, None] + flat(10, 20))
    assert S.sim_buy(m, m, 0, +1, hold=10, X=0.5, L=5.0) is None


def test_premium_stop_fills_at_level_or_worse_gap():
    m = market(flat(10, 2) + [(9.5, 9.6, 7.9, 8.0)] + flat(8, 10))
    t = S.sim_buy(m, m, 0, +1, hold=10, X=0.2, L=5.0)
    assert t["why"] == "opt_stop" and t["exit"] == pytest.approx(8.0)
    m = market(flat(10, 2) + [(7.0, 7.2, 6.8, 7.0)] + flat(7, 10))          # gaps below the stop
    t = S.sim_buy(m, m, 0, +1, hold=10, X=0.2, L=5.0)
    assert t["exit"] == pytest.approx(7.0)


def test_profit_lock_then_trail():
    # +40% peak with L = 0.3: lock to entry + 15%, trail at peak - 15%
    path = flat(10, 2) + [(10, 13, 10, 13), (13, 14, 13, 14), (14, 14, 12.4, 12.5)] + flat(12.5, 10)
    t = S.sim_buy(market(path), market(path), 0, +1, hold=20, X=0.2, L=0.3)
    assert t["why"] == "lock" and t["exit"] == pytest.approx(12.5)           # 14 - 1.5
    # lock reached but never trailed: exits at entry + L/2
    path = flat(10, 2) + [(10, 13, 10, 13), (13, 13, 11.4, 11.4)] + flat(11.4, 10)
    t = S.sim_buy(market(path), market(path), 0, +1, hold=20, X=0.2, L=0.3)
    assert t["why"] == "lock" and t["exit"] == pytest.approx(11.5)


def test_stop_checked_before_the_peak_of_the_same_minute():
    path = flat(10, 2) + [(10, 14, 7.9, 12)] + flat(12, 10)                  # wide bar: stop first
    t = S.sim_buy(market(path), market(path), 0, +1, hold=20, X=0.2, L=0.3)
    assert t["why"] == "opt_stop"


def test_underlying_stop_sells_the_option_at_that_minute():
    F = [101, 101, 101, 101, 99.0] + [99] * 10
    path = flat(10, 4) + [(9, 9, 9, 9)] + flat(9, 10)
    m = market(path, F=F)
    t = S.sim_buy(m, m, 0, +1, hold=20, X=0.5, L=5.0, stopF=100.0)
    assert t["why"] == "fut_stop" and t["exit"] == 9 and t["exit_ts"] == pd.Timestamp(m.ts[4])
    assert t["fut_gross"] == pytest.approx((99 - 101) * 10)


def test_put_side_buys_the_put_and_its_underlying_stop_is_above():
    m = market(flat(10, 15), F=[101, 101, 101, 103] + [103] * 11)
    t = S.sim_buy(m, m, 0, -1, hold=10, X=0.5, L=5.0, stopF=102.0)
    assert t["strike"] == 105.0 and t["why"] == "fut_stop" and t["fut_gross"] == pytest.approx(-2 * 10)


def test_expiry_day_and_session_cut():
    m = market(flat(10, 30))
    m.is_exp[:] = True
    assert S.sim_buy(m, m, 0, +1, hold=10, X=0.5, L=5.0) is None
    m = market(flat(10, 30), start=23 * 60)                                   # 23:00-23:29, cut 23:19 (summer)
    t = S.sim_buy(m, m, 0, +1, hold=60, X=0.5, L=5.0)
    assert t["exit_ts"].hour * 60 + t["exit_ts"].minute == 23 * 60 + 19


def test_costs_use_the_wider_spread_before_17():
    assert S.half_spread("GOLDM", 16 * 60) == 2 * S.half_spread("GOLDM", 17 * 60)
    c_day = S.opt_cost("NATURALGAS", 1000, 1000, 600, 600)
    c_eve = S.opt_cost("NATURALGAS", 1000, 1000, 1100, 1100)
    assert c_day - c_eve == pytest.approx(2 * 1000 * 0.0045)


def tr(d, net):
    return dict(entry_ts=pd.Timestamp(d), net=net, gross=net, cost=0.0, fut_gross=0.0, stale=False, why="time")


def test_walk_forward_picks_on_past_only_and_freezes_on_design():
    a = [tr("2025-09-01", 100), tr("2025-11-05", -50), tr("2026-01-05", -50), tr("2026-03-05", -50)]
    b = [tr("2025-09-01", 10), tr("2025-11-05", 30), tr("2026-01-05", 30), tr("2026-03-05", 30)]
    w = S.walk_forward({"a": a, "b": b}, "a")
    assert w["wf_picks"][0] == "a"                    # Aug-Oct: a is better
    assert w["wf_picks"] == ["a", "a", "b"] and w["wf_net"] == pytest.approx(-50 - 50 + 30)
    assert w["design_net"] == {"a": -50.0, "b": 100.0} and w["frozen"] == "b"


def test_summary_months_below_stop_and_top5():
    t = [tr("2025-09-01", -14000), tr("2025-10-01", 500)] + [tr("2025-10-02", 100)] * 6
    s = S.summary(t)
    assert s["months_below_13k"] == 1 and s["top5_removed"] == pytest.approx(-14000 + 100 * 2)


def test_twins_are_reproducible_and_counted():
    days = []
    for d in ("2025-09-01", "2025-09-02", "2025-09-03"):
        days.append(market(flat(10, 20), day=d))
    ts = np.concatenate([m.ts for m in days])
    F = np.full(len(ts), 101.0)
    O = pd.concat([pd.DataFrame({"ts": m.ts}) for m in days])
    rows = []
    for t0 in ts:
        rows.append((t0, 0, -1, 100.0, 10, 10, 10, 10, 5, False))
        rows.append((t0, 1, 1, 105.0, 10, 10, 10, 10, 5, False))
    O = pd.DataFrame(rows, columns=["ts", "cp", "offset", "strike", "open", "high", "low", "close", "volume", "f1"])
    m = Market("GOLDM", ts, F, O)
    sig = [dict(j=2, side=1, hold=5, stopF=float("nan"))]
    a = S.twins(m, m, sig, 0.5, 5.0, (date(2025, 9, 1), date(2025, 9, 3)), "GOLDM", 10.0, 10.0, n=20)
    b = S.twins(m, m, sig, 0.5, 5.0, (date(2025, 9, 1), date(2025, 9, 3)), "GOLDM", 10.0, 10.0, n=20)
    assert a == b and len(a) == 20 and all(x < 0 for x in a)               # flat premium: every twin pays costs
    assert S.twin_p(0.0, a) == pytest.approx(1 / 21)
