"""R93 unit tests (synthetic data only): bars, channel and segments, breadth, drivers, daily rolls, the XL exit engine on
an R53 Market, futures and daily exits, costs, R72's caps, twins, BH, gates and the holdout lock."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r53 import common as C53
from research.hunt.r53.mcx.data import Market
from research.hunt.r93_breadth_donchian_mcx import cfg as K
from research.hunt.r93_breadth_donchian_mcx import engine as E
from research.hunt.r93_breadth_donchian_mcx import rules as R
from research.hunt.r93_breadth_donchian_mcx import study as S


# ------------------------------------------------------------------------------------------------ helpers
def minutes(day, start, closes):
    ts = pd.Timestamp(day) + pd.to_timedelta(start + np.arange(len(closes)), unit="m")
    return ts.values, np.asarray(closes, float)


def market(prem, day="2025-09-02", start=600, F=None, strike=100.0):
    """One day from `start` (minute of day); the 1-ITM call (offset -1) and put (offset +1) print `prem` bars
    (o, h, l, c) per minute (None = no print). R53's own Market class."""
    n = len(prem)
    ts = pd.Timestamp(day) + pd.to_timedelta(start + np.arange(n), unit="m")
    F = np.full(n, 101.0) if F is None else np.asarray(F, float)
    rows = []
    for i, p in enumerate(prem):
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


T64 = lambda m, i: m.ts[i]  # noqa: E731


# ------------------------------------------------------------------------------------------------ session and bars
def test_session_close_by_us_time():
    assert R.close_min(date(2026, 7, 1)) == 23 * 60 + 30
    assert R.close_min(date(2026, 1, 15)) == 23 * 60 + 55
    assert R.close_min(date(2026, 3, 9)) == 23 * 60 + 30      # second Sunday of March 2026 = 8 Mar
    assert R.close_min(date(2026, 11, 2)) == 23 * 60 + 55     # first Sunday of November 2026 = 1 Nov


def test_bars5_close_only_anchor_and_min_minutes():
    ts, F = minutes("2026-07-01", 9 * 60, [10, 12, 11, 13, 9, 14, 15, 16, 17, 18, 19, 20])
    b = R.bars5(ts, F)
    assert list(b.start) == [540, 545]                      # 09:00 and 09:05; the 09:10 bar has 2 minutes: dropped
    assert b.o[0] == 10 and b.h[0] == 13 and b.l[0] == 9 and b.c[0] == 9
    assert b.o[1] == 9 and b.h[1] == 18 and b.l[1] == 9 and b.c[1] == 18       # open = the previous minute's close
    assert b.n[0] == 5 and b.last_ts[1] == pd.Timestamp("2026-07-01 09:09")


def test_bars5_drops_out_of_session_and_weekend():
    ts, F = minutes("2026-07-01", 23 * 60 + 25, np.arange(10) + 100.0)        # 23:25-23:34, close 23:30 in summer
    b = R.bars5(ts, F)
    assert list(b.start) == [23 * 60 + 25]
    ts, F = minutes("2026-07-04", 10 * 60, np.arange(10) + 100.0)             # a Saturday
    assert len(R.bars5(ts, F)) == 0
    ts, F = minutes("2026-01-15", 23 * 60 + 45, np.arange(10) + 100.0)        # winter: open to 23:55
    assert list(R.bars5(ts, F).start) == [23 * 60 + 45, 23 * 60 + 50]


def test_channel_break_and_segment_rule():
    h = np.r_[np.full(20, 10.0), 12.0, 9.0]
    l = np.r_[np.full(20, 8.0), 9.0, 7.5]
    c = np.r_[np.full(20, 9.0), 11.0, 7.6]
    chi, clo, side = R.channel(h, l, c)
    assert np.isnan(chi[19]) and chi[20] == 10 and side[20] == 1
    assert side[21] == -1 and clo[21] == 8.0
    seg = np.r_[np.zeros(15, int), np.ones(7, int)]                          # a contract change inside the window
    _, _, side2 = R.channel(h, l, c, seg)
    assert side2[20] == 0 and side2[21] == 0
    seg = np.r_[np.zeros(1, int), np.ones(21, int)]                          # bars 1..21 one segment: 21 valid
    _, _, side3 = R.channel(h, l, c, seg)
    assert side3[20] == 0 and side3[21] == -1


def test_breadth_m_of_group_missing_member_and_side():
    idx = [("d", 1), ("d", 2), ("d", 3)]
    mk = lambda v, ix=idx: pd.Series(v, index=pd.MultiIndex.from_tuples(ix))  # noqa: E731
    sides = {"GOLD": mk([1, 1, -1]), "SILVER": mk([1, 0, -1]), "COPPER": mk([1, 1, 1]),
             "ZINC": mk([0, 1], idx[:2]), "CRUDE": mk([1, -1, 1]), "NATGAS": mk([1, 1, 1])}
    out = R.breadth(sides)
    assert list(out["GOLD"]) == [1, 1, 0]          # bar 1: 3 up; bar 2: 3 up (zinc); bar 3: only 2 down
    assert list(out["SILVER"]) == [1, 0, 0]        # silver itself must break
    assert list(out["ZINC"]) == [0, 1]
    assert list(out["CRUDE"]) == [1, 0, 1]         # energy needs 2 of 2
    assert list(out["NATGAS"]) == [1, 0, 1]
    assert "ALUMINIUM" not in out                  # no series: absent, counts as not breaking


def test_groups_count_minis_once():
    names = [m for fams in K.GROUPS.values() for m in fams]
    assert len(names) == len(set(names)) == 7
    assert not any(n in names for n in ("GOLDM", "SILVERM", "CRUDEOILM", "NATGASMINI"))
    assert K.BREADTH_M == {"metals": 3, "energy": 2}


def test_driver_confirm_same_side_same_minutes():
    ts = pd.date_range("2026-07-01 07:00", periods=200, freq="min")
    px = np.r_[np.full(150, 100.0), np.linspace(100, 110, 50)]               # breaks up at the end
    drv = R.driver_bars(ts.values, px)
    t_up = drv.t[drv.side == 1].iloc[0]
    bars = pd.DataFrame({"day": [t_up.normalize(), t_up.normalize()],
                         "start": [t_up.hour * 60 + t_up.minute, t_up.hour * 60 + t_up.minute - 60],
                         "side": [1, 1]})
    assert list(R.driver_confirm(bars, drv)) == [1, 0]
    bars["side"] = [-1, 0]
    assert list(R.driver_confirm(bars, drv)) == [0, 0]
    assert list(R.driver_confirm(bars, None)) == [0, 0]


# ------------------------------------------------------------------------------------------------ daily
def test_roll_rows_causal_greedy():
    dates = pd.date_range("2026-01-01", periods=40, freq="D")
    oi = np.full(40, 100.0)
    oi[5], oi[10], oi[30] = 200, 500, 300                                    # 10 is within 15 days of 5
    r = R.roll_rows(dates, oi)
    assert list(np.flatnonzero(r)) == [5, 30]


def test_back_adjust_removes_roll_gap_and_keeps_order():
    o = np.array([10, 11, 20, 21.0])
    c = np.array([10.5, 11.0, 20.5, 21.0])
    roll = np.array([False, False, True, False])
    ao, ah, al, ac = R.back_adjust(o, o, o, c, roll)
    assert ac[1] == pytest.approx(20.0) and ac[3] == 21 and ao[2] == 20       # gap 20 - 11 = 9 added before row 2
    assert np.all(np.diff(ac) == pytest.approx(np.diff([10.5 + 9, 11 + 9, 20.5, 21])))


def daily_frame(closes, roll_at=(), atr=1.0):
    n = len(closes)
    c = np.asarray(closes, float)
    d = pd.DataFrame({"date": pd.bdate_range("2020-01-01", periods=n), "open": c, "high": c + 0.1, "low": c - 0.1,
                      "close": c, "oi": 100.0})
    d["roll"] = False
    d.loc[list(roll_at), "roll"] = True
    d["atr"] = atr
    return d


def test_sim_daily_stop_lock_time_and_roll():
    units = 10.0
    d = daily_frame([100, 100, 99, 97.9, 97, 97, 97, 97, 97, 97, 97, 97, 97])
    t = E.sim_daily(d, 0, 1, units, "gold")
    assert t["why"] == "stop" and t["b"] == 3 and t["fill"] == 100             # close 97.9 <= 100 - 2
    d = daily_frame([100, 100, 103, 106, 102.5, 102, 102, 102])               # best 106 -> lock 103
    t = E.sim_daily(d, 0, 1, units, "gold")
    assert t["why"] == "lock" and t["exit"] == 102.5
    d = daily_frame(np.full(15, 100.0))
    t = E.sim_daily(d, 0, -1, units, "gold")
    assert t["why"] == "time" and t["b"] - t["a"] == K.D_MAX_HOLD - 1
    d = daily_frame(np.full(15, 100.0), roll_at=(5,))
    t = E.sim_daily(d, 0, 1, units, "gold")
    assert t["why"] == "roll" and t["b"] == 4
    assert t["net"] == pytest.approx(t["gross"] - t["cost"]) and t["cost"] > 0


# ------------------------------------------------------------------------------------------------ option engine (XL)
def test_xl_stop_gap_and_no_same_minute_lock():
    rows = np.array([[10, 10, 10, 10], [10, 10.2, 8.4, 9.0]], float)
    assert E.xl_walk(10.0, rows, be=10.1) == (1, 8.5, "stop")
    rows = np.array([[10, 10, 10, 10], [8.0, 8.1, 7.9, 8.0]], float)          # opens below the stop
    assert E.xl_walk(10.0, rows, be=10.1)[1] == 8.0
    rows = np.array([[10, 13, 8.4, 9.0]], float)                               # +30% and -16% in one minute: stop
    assert E.xl_walk(10.0, rows, be=10.1) == (0, 8.5, "stop")


def test_xl_l25_ladder_and_breakeven():
    rows = np.array([[10, 12.6, 10, 12], [12, 12, 10.4, 10.6]], float)        # +26% -> lock +5% = 10.5
    assert E.xl_walk(10.0, rows, be=10.1) == (1, 10.5, "lock")
    rows = np.array([[10, 13.1, 10, 13], [13, 13, 10.1, 10.2]], float)        # +31% -> breakeven 10.3 (> +5%? no)
    assert E.xl_walk(10.0, rows, be=10.3)[1] == pytest.approx(10.5)           # L25's +5% (10.5) is higher
    assert E.xl_walk(10.0, rows, be=10.7)[1] == pytest.approx(10.7)           # breakeven higher than +5%
    rows = np.array([[10, 16.5, 10, 16], [16, 16, 12.0, 12.5]], float)        # +65% -> +30% = 13
    assert E.xl_walk(10.0, rows, be=10.1) == (1, 13.0, "lock")
    rows = np.array([[10, 20.5, 10, 20], [20, 20, 15, 15.5]], float)          # +105% -> +60% = 16, opens 20
    assert E.xl_walk(10.0, rows, be=10.1) == (1, 16.0, "lock")
    rows = np.array([[10, 11, 9, 10], [10, 11, 9, 10]], float)
    assert E.xl_walk(10.0, rows, be=10.1) == (None, None, None)


def test_sim_option_fill_time_exit_and_costs():
    m = market(flat(10, 5) + flat(12, 60))
    end = m.ts[5]                                                              # the bar ended; fill at minute 5's open
    t = E.sim_option(m, end, 4, 1, 10.0, "gold", m.ts[-1] + np.timedelta64(30, "m"))
    assert t["fill"] == 12 and t["entry_ts"] == pd.Timestamp(m.ts[5])
    assert t["why"] == "time" and t["exit_ts"] == pd.Timestamp(m.ts[5 + 29]) and t["exit"] == 12
    tod = int(m.tod[5])
    assert t["cost"] == pytest.approx(E.opt_cost("gold", 120, 120, tod, tod))
    assert t["net"] == pytest.approx(-t["cost"])


def test_sim_option_no_print_expiry_and_squareoff():
    m = market(flat(10, 5) + [None, None, None] + flat(12, 40))
    assert E.sim_option(m, m.ts[5], 4, 1, 10.0, "gold", m.ts[-1]) is None       # 3 silent minutes: no trade
    m = market(flat(10, 50))
    m.is_exp[:] = True
    assert E.sim_option(m, m.ts[5], 4, 1, 10.0, "gold", m.ts[-1]) is None
    m = market(flat(10, 50))
    t = E.sim_option(m, m.ts[5], 4, -1, 10.0, "gold", m.ts[15])               # square-off before 30 minutes
    assert t["why"] == "squareoff" and t["exit_ts"] == pd.Timestamp(m.ts[14]) and t["side"] == -1
    assert t["strike"] == 105.0                                                 # the put's 1-ITM (offset +1)


def test_option_costs_before_and_after_17():
    a = E.opt_cost("gold", 10000, 10000, 16 * 60, 16 * 60)
    b = E.opt_cost("gold", 10000, 10000, 18 * 60, 18 * 60)
    ch = C53.mcx_opt_charges(10000, 10000)
    assert a == pytest.approx(ch + 0.006 * 20000) and b == pytest.approx(ch + 0.003 * 20000)
    assert E.opt_cost("natgas", 10000, 10000, 18 * 60, 18 * 60, slip=2) == pytest.approx(ch + 0.009 * 20000)


def test_sim_future_stop_time_and_costs():
    ts, F = minutes("2026-07-01", 600, np.r_[100, 101, 102, 103, 99.5, 99, np.full(40, 99.0)])
    t = E.sim_future(ts, F, ts[1], 1, 100.0, 2500.0, "copper", ts[-1] + np.timedelta64(30, "m"))
    assert t["fill"] == 101 and t["why"] == "fut_stop" and t["exit"] == 99.5
    assert t["gross"] == pytest.approx(-1.5 * 2500)
    exp_cost = E.fut_cost("copper", 101 * 2500, 99.5 * 2500, True)
    assert t["cost"] == pytest.approx(exp_cost)
    ts, F = minutes("2026-07-01", 600, np.r_[100, np.linspace(101, 110, 45)])
    t = E.sim_future(ts, F, ts[1], 1, 95.0, 1000.0, "zinc", ts[-1] + np.timedelta64(30, "m"))
    assert t["why"] == "time" and t["exit_ts"] == pd.Timestamp(ts[1 + 29])
    t = E.sim_future(ts, F, ts[1], -1, 120.0, 1000.0, "zinc", ts[-1] + np.timedelta64(30, "m"))
    assert t["gross"] < 0 and t["side"] == -1


# ------------------------------------------------------------------------------------------------ study pieces
def test_take_one_open_a_side_and_two_a_day():
    def tr(a, b):
        return dict(entry_ts=pd.Timestamp("2026-07-01") + pd.Timedelta(minutes=a),
                    exit_ts=pd.Timestamp("2026-07-01") + pd.Timedelta(minutes=b), net=1.0)
    cache = {(1, 1): tr(600, 630), (2, 1): tr(610, 640), (3, 1): tr(631, 660), (4, -1): tr(612, 620),
             (5, 1): tr(700, 720), (6, 1): None}
    out = S.take([(1, 1), (2, 1), (4, -1), (3, 1), (6, 1), (5, 1)], cache)
    assert [t["row"] for t in out] == [1, 4, 3]                 # 2 overlaps 1; 6 no fill; 5 is the 3rd call that day


def test_twins_bh_and_gates():
    pools = {("GOLD", date(2026, 7, 1), 1): np.array([0.0, 10.0])}
    trades = [dict(fam="GOLD", entry_ts=pd.Timestamp("2026-07-01 10:00"), side=1, net=10.0)]
    tw = S.twin_totals(trades, pools, daily=False, n=1000)
    assert set(np.unique(tw)) <= {0.0, 10.0} and 0.35 < tw.mean() / 10 < 0.65
    assert S.twin_p(10.0, tw) == pytest.approx((1 + np.sum(tw >= 10)) / 1001)
    q = C53.bh({"a": 0.01, "b": 0.04, "c": 0.5})
    assert q["a"] == pytest.approx(0.03) and q["b"] == pytest.approx(0.06) and q["c"] == pytest.approx(0.5)
    h = dict(net=100.0, trades=50, net_wo_top5=10.0, net_wo_best_family=5.0, net_2x_slip=1.0)
    assert S.gates(h, 0.01, 0.05, 1.0, 1.0)["PASS"]
    assert not S.gates(dict(h, trades=39), 0.01, 0.05, 1.0, 1.0)["PASS"]
    assert not S.gates(h, 0.01, 0.2, 1.0, 1.0)["PASS"]
    assert not S.gates(dict(h, net_wo_best_family=-1.0), 0.01, 0.05, 1.0, 1.0)["PASS"]


def test_walk_forward_selection_uses_only_earlier_days():
    def t(d, net):
        return dict(entry_ts=pd.Timestamp(d), net=net)
    trades = {"I-A": [t("2025-09-01", 10), t("2025-11-05", -5), t("2026-01-05", -7)],
              "I-B": [t("2025-09-01", 5), t("2025-11-05", 50), t("2026-01-05", 3)], "I-C": []}
    wf = S.walk_forward(trades, ("I-A", "I-B", "I-C"), K.I_DESIGN, K.I_FOLDS)
    assert wf["selection_picks"][:2] == ["I-A", "I-B"]           # Nov fold: A led; Jan fold: B led after November
    assert wf["selection_oos"] == pytest.approx(-5 + 3)
    assert wf["fold_sum"]["I-B"] == pytest.approx(53)


def test_holdout_lock_reads_once(tmp_path, monkeypatch):
    flag = tmp_path / "HOLDOUT_READ"
    flag.write_text("2026-10-21\n")
    monkeypatch.setattr(K, "HOLDOUT_FLAG", flag)
    with pytest.raises(RuntimeError):
        S.run_holdout()


def test_families_and_instruments_as_preregistered():
    assert {f: v[2] for f, v in K.FAMILIES.items()} == {
        "GOLD": "GOLDM", "SILVER": "SILVERM", "COPPER": "COPPER", "ZINC": "ZINCMINI", "ALUMINIUM": "ALUMINI",
        "CRUDE": "CRUDEOILM", "NATGAS": "NATGASMINI"}
    assert {f for f, v in K.FAMILIES.items() if v[3] == "opt"} == {"GOLD", "SILVER", "CRUDE", "NATGAS"}
    assert set(K.DRIVERS) == {"GOLD", "SILVER", "CRUDE", "NATGAS", "COPPER"}
    assert K.STOP == 0.15 and K.TMAX == 30 and K.MAX_PER_SIDE == 2 and K.N_CH == 20


def test_twin_pools_keep_only_filled_entries():
    bars = {"GOLD": pd.DataFrame({"day": pd.to_datetime(["2026-07-01"] * 3)})}
    caches = {"GOLD": {(0, 1): dict(net=5.0), (1, 1): None, (2, 1): dict(net=-3.0), (0, -1): None}}
    daily = {"GOLD": pd.DataFrame({"date": pd.to_datetime(["2025-12-31", "2026-01-02", "2026-01-05"])})}
    dcache = {"GOLD": {(0, 1): dict(net=1.0), (1, 1): None}}
    P = S.make_pools(bars, caches, daily, dcache)
    assert list(P[("GOLD", date(2026, 7, 1), 1)]) == [5.0, -3.0]
    assert ("GOLD", date(2026, 7, 1), -1) not in P                # no fillable bar: the twin adds 0
    assert list(P[("D", "GOLD", 2026, 1)]) == [1.0]               # keyed by the entry (next session) year
    tw = S.twin_totals([dict(fam="GOLD", entry_ts=pd.Timestamp("2026-07-01 10:00"), side=-1, net=1.0)], P, False, n=5)
    assert list(tw) == [0.0] * 5


def test_confirmed_split_per_group_and_family():
    def t(fam, grp, net, gross, ib):
        return dict(fam=fam, group=grp, net=net, gross=gross, ib=ib, entry_ts=pd.Timestamp("2026-07-01 10:00"),
                    cost=gross - net, net2=net, why="time", stale=False, side=1)
    tt = [t("GOLD", "metals", 10, 20, 1), t("GOLD", "metals", -30, -20, 0), t("CRUDE", "energy", 5, 8, 1),
          t("CRUDE", "energy", -1, 1, 0), t("CRUDE", "energy", -3, 0, 0)]
    sp = S.confirmed_split(tt, "ib")
    assert sp["confirmed"]["trades"] == 2 and sp["confirmed"]["net"] == 15
    assert sp["unconfirmed"]["energy"] == dict(trades=2, net=-4.0, net_per_trade=-2.0)
    assert sp["unconfirmed"]["by_family"]["CRUDE"]["gross_per_trade"] == pytest.approx(0.5)
    assert sp["confirmed"]["by_family"]["GOLD"]["win_rate"] == 1.0
    assert sp["confirmed"]["gross_per_trade"] == pytest.approx(14)
