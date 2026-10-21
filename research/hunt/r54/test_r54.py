"""R54 tests: entry-time and end-time handling, the late-minute spread, the 25% trail, the 1-lot Rs 1,000 rule, R47's
stray-print guard and foreign-rows filter, metrics, paired comparisons and readings, and the look-ahead guard on real
days (skipped when the lake cache is absent).

    python -m pytest research/hunt/r54/test_r54.py -q
"""
from __future__ import annotations

import json
import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r54cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r54data as DD  # noqa: E402
import r54lib as LB  # noqa: E402
import r54liq as LQ  # noqa: E402

N = D.N
K_C = 25100.0


@pytest.fixture(autouse=True)
def no_late():
    """Every synthetic test starts with no late multiplier unless it sets one."""
    saved = dict(LB.LATE)
    LB.LATE.clear()
    yield
    LB.LATE.clear()
    LB.LATE.update(saved)


def make_day(path, d=date(2025, 6, 5), und="NIFTY", lot=75, vol=100.0, k=K_C, side="C", spot=25000.0, start=0):
    """One option whose 1-minute closes follow `path` (dict row -> price; flat between), open = previous close."""
    ix = np.full((N, 4), spot)
    a = np.full((N, 7), np.nan)
    p = path[min(path)]
    prev = p
    for r in range(start, N):
        if r in path:
            p = path[r]
        a[r, D.O] = prev
        a[r, D.H] = max(prev, p)
        a[r, D.L] = min(prev, p)
        a[r, D.C] = p
        a[r, D.V] = vol * lot
        a[r, D.OI] = 1000.0
        prev = p
    day = D.Day(d, und, ix, {(k, side): a}, np.full(N, 14.0), spot, 14.0, lot, None, 100.0)
    day._clean = True
    return day


def row(s):
    return LB.bar_close(s)


def run(day, end="1505", exit_="tr25", entry="1430", ref=None, mult=1.0):
    t = LB.ENTRY_ROW[entry]
    a = day.opts[(K_C, "C")]
    ref = float(a[t, D.C]) if ref is None else ref
    return LB.simulate(day, K_C, 1, t, ref, end, exit_, mult)


# ------------------------------------------------------------------------------------------------ times
def test_entry_and_end_rows():
    assert LB.ENTRY_ROW["1230"] == D.minute(12, 29)        # the bar that CLOSES at 12:30 starts 12:29
    assert LB.ENTRY_ROW["1430"] == D.minute(14, 29)
    assert LB.END_ROW["1505"] == R.EXIT_ROW                 # R25 / R50's 15:05 square-off, unchanged
    assert LB.END_ROW["1510"] == D.minute(15, 10) and LB.END_ROW["1515"] == D.minute(15, 15)
    assert LB.TWIN_WINDOW == (R.close_row(12, 0), R.close_row(14, 45))
    assert set(K.UNDS) == {"NIFTY", "SENSEX"}


@pytest.mark.parametrize("entry", K.ENTRIES)
def test_fill_is_the_next_minute_after_the_entry_time(entry):
    day = make_day({0: 10.0})
    tr = run(day, entry=entry, exit_="hold")
    assert tr.sig_row == LB.ENTRY_ROW[entry] and tr.fill_row == LB.ENTRY_ROW[entry] + 1


@pytest.mark.parametrize("end", K.ENDS)
def test_hold_sells_at_the_open_of_the_end_bar(end):
    p = {0: 10.0}
    for r in range(LB.ENTRY_ROW["1430"] + 2, N):
        p[r] = 10.0 + 0.05 * (r - LB.ENTRY_ROW["1430"])     # rises every minute: the end bar's open is distinct
    day = make_day(p)
    tr = run(day, end=end, exit_="hold")
    r, q, px, why = tr.exits[-1]
    e = LB.END_ROW[end]
    assert r == e and why == "end" + end and q == 75
    assert px == pytest.approx(R.sell_px(day.opts[(K_C, "C")][e, D.O], 100.0), abs=1e-9)


def test_later_end_holds_longer_on_a_rally():
    p = {0: 10.0, row("1505"): 20.0, row("1512"): 40.0}
    day = make_day(p)
    a = run(day, end="1505", exit_="hold")
    b = run(day, end="1515", exit_="hold")
    assert b.net > a.net and b.exits[-1][0] == LB.END_ROW["1515"]


def test_end_bar_missing_uses_the_next_bar():
    day = make_day({0: 10.0})
    a = day.opts[(K_C, "C")]
    e = LB.END_ROW["1510"]
    a[e:e + 2] = np.nan                                     # no bar at 15:10 or 15:11
    tr = run(day, end="1510", exit_="hold")
    assert tr.exits[-1][0] == e + 2


def test_no_bar_left_sells_at_the_last_price():
    day = make_day({0: 10.0})
    a = day.opts[(K_C, "C")]
    a[LB.END_ROW["1515"]:] = np.nan
    tr = run(day, end="1515", exit_="hold")
    assert tr.exits[-1][3] == "end1515" and 0 < tr.exits[-1][2] < 10.0


# ------------------------------------------------------------------------------------------------ trail
def test_trail_is_also_a_25pct_stop_from_the_fill():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0, t + 5: 7.4})
    tr = run(day, exit_="tr25")
    r, q, px, why = tr.exits[-1]
    assert why == "trail" and r == t + 6                    # the close at t+5 triggers, sells at the next open
    assert px == pytest.approx(R.sell_px(7.4, 100.0), abs=1e-9)
    day2 = make_day({0: 10.0, t + 5: 7.6})                  # 24% down: no exit
    assert run(day2, exit_="tr25").exits[-1][3] == "end1505"


def test_stray_guard_takes_the_worse_price_on_a_crash():
    # R47's guard (pre-registered): when the next bar falls > 20% more, the sell takes the median close, never better
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0, t + 5: 7.4, t + 6: 5.0})
    tr = run(day, exit_="tr25")
    assert tr.exits[-1][2] == pytest.approx(R.sell_px(5.0, 100.0), abs=1e-9)
    LB.GUARD = False
    try:
        assert run(day, exit_="tr25").exits[-1][2] == pytest.approx(R.sell_px(7.4, 100.0), abs=1e-9)
    finally:
        LB.GUARD = True


def test_trail_follows_the_best_close():
    t = LB.ENTRY_ROW["1230"]
    day = make_day({0: 10.0, t + 10: 30.0, t + 20: 23.0, t + 30: 22.4})
    tr = run(day, entry="1230", exit_="tr25")
    assert tr.exits[-1][3] == "trail" and tr.exits[-1][0] == t + 31     # 22.4 <= 0.75 x 30 = 22.5; 23 is not
    assert tr.net > 0


def test_trail_reads_closes_only_to_end_minus_one():
    e = LB.END_ROW["1510"]
    day = make_day({0: 10.0, e - 1: 5.0})                   # the drop is in the bar closing AT 15:10: too late
    tr = run(day, end="1510", exit_="tr25")
    assert tr.exits[-1][3] == "end1510"
    day2 = make_day({0: 10.0, e - 2: 5.0})                  # the bar closing at 15:09 still counts
    tr2 = run(day2, end="1510", exit_="tr25")
    assert tr2.exits[-1][3] == "trail" and tr2.exits[-1][0] == e - 1


def test_hold_has_no_trail():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0, t + 5: 2.0})
    tr = run(day, exit_="hold")
    assert tr.exits[-1][3] == "end1505"


def test_gap_through_the_trail_fills_at_the_worse_open():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0, t + 5: 7.0})
    day.opts[(K_C, "C")][t + 6, D.O] = 3.0                  # gaps down after the trigger minute
    tr = run(day, exit_="tr25")
    assert tr.exits[-1][2] < 3.0


# ------------------------------------------------------------------------------------------------ late liquidity
def test_late_multiplier_widens_only_from_1500():
    LB.LATE.update({LB.bar_start("1500"): 2.25, LB.bar_start("1510"): 2.75})
    assert LB.late_mult(LB.bar_start("1459")) == 1.0
    assert LB.late_mult(LB.bar_start("1500")) == 2.25 and LB.late_mult(LB.bar_start("1504")) == 2.25
    assert LB.late_mult(LB.bar_start("1505")) == 1.0        # a bucket with no entry stays 1
    assert LB.late_mult(LB.bar_start("1513")) == 2.75


def test_late_multiplier_costs_more_at_the_end():
    day = make_day({0: 10.0}, vol=1.0)
    a = run(day, end="1515", exit_="hold")
    LB.LATE.update({LB.bar_start("1515"): 3.0})
    b = run(day, end="1515", exit_="hold")
    assert b.exits[-1][2] < a.exits[-1][2] and b.net < a.net
    assert b.entry == a.entry                               # the 14:30 entry is not touched


def test_spread_stress_costs_more():
    day = make_day({0: 10.0}, vol=1.0)
    assert run(day, mult=2.0).net < run(day).net


def test_round_up_quarter():
    assert LB.round_up_quarter(1.0) == 1.0 and LB.round_up_quarter(1.01) == 1.25
    assert LB.round_up_quarter(2.19) == 2.25 and LB.round_up_quarter(2.5) == 2.5


def test_liquidity_measure_on_a_synthetic_day():
    p = {0: 10.0}
    for r in range(LB.bar_start("1400"), N):
        late = r >= LB.bar_start("1500")
        p[r] = 10.0 if r % 2 else (12.0 if late else 10.5)  # 5% bars at 14:00-14:59, 20% from 15:00
    day = make_day(p)
    out = LQ.measure([day])
    assert out["late_mult"][str(LB.bar_start("1500"))] == 3.5   # medians ~18.3% / ~4.9% = 3.4x, rounded up to 0.25
    b14 = next(b for b in out["buckets"] if b["bucket"] == "14:00")
    assert b14["mult"] == 1.0 and b14["no_trade_pct"] == 0.0


def test_load_late_reads_the_json(tmp_path):
    f = tmp_path / "liq.json"
    f.write_text(json.dumps({"late_mult": {"345": 2.25}}))
    LB.load_late(f)
    assert LB.late_mult(347) == 2.25


# ------------------------------------------------------------------------------------------------ the ticket
def test_one_lot_and_the_rs_1000_cap():
    day = make_day({0: 10.0})                               # 10 x 75 = Rs 750 + spread: one lot
    tr = run(day, exit_="hold")
    assert tr.lots == 1 and tr.qty == 75
    day2 = make_day({0: 13.3})                              # the fill (13.3 + spread) x 75 > Rs 1,000: skipped
    assert run(day2, exit_="hold") is None


def test_band_capped_by_the_lot():
    assert LB.band(make_day({0: 10.0}, lot=75)) == (5.0, pytest.approx(1000 / 75))
    assert LB.band(make_day({0: 10.0}, lot=65)) == (5.0, pytest.approx(1000 / 65))
    assert LB.band(make_day({0: 10.0}, und="SENSEX", lot=20)) == (5.0, 30.0)


def test_pick_nearest_eligible_in_the_band():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 12.0})
    day.opts[(25200.0, "C")] = make_day({0: 6.0}).opts[(K_C, "C")]
    day.opts[(25300.0, "C")] = make_day({0: 2.0}).opts[(K_C, "C")]
    assert LB.pick(day, 1, t) == (K_C, 12.0)
    day.lot = 100                                           # band Rs 5-10: 25100 at Rs 12 is out
    assert LB.pick(day, 1, t) == (25200.0, 6.0)
    assert LB.pick(day, -1, t) is None                      # no puts stored
    day.lot = 0
    assert LB.pick(day, 1, t) is None


def test_entry_never_chases():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 8.0, t + 1: 12.0})
    day.opts[(K_C, "C")][t + 1:t + 4, [D.O, D.L]] = 12.0
    assert run(day, ref=8.0) is None


def test_worthless_end_pays_no_sell_charges():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0, t + 10: 0.0})
    tr = run(day, exit_="hold")
    assert tr.exits[-1][2] == 0.0
    assert tr.charges == pytest.approx(LB.L50.charge(True, tr.entry, tr.qty, "NIFTY", day.day), abs=0.01)


# ------------------------------------------------------------------------------------------------ R47's guards
def test_stray_open_never_helps_a_sell():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0})
    a = day.opts[(K_C, "C")]
    e = LB.END_ROW["1505"]
    a[e, D.O] = 50.0                                        # a stray 15:05 open
    tr = run(day, exit_="hold")
    assert tr.exits[-1][2] == pytest.approx(R.sell_px(10.0, 100.0), abs=1e-9)
    assert t < e


def test_stray_open_never_helps_a_buy():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 10.0})
    a = day.opts[(K_C, "C")]
    a[t + 1, D.O] = 2.0                                     # a stray low open: paid the median close, not the print
    a[t + 1, D.L] = 2.0
    tr = run(day, exit_="hold", ref=10.0)
    assert tr.entry >= 10.0


def test_stray_high_does_not_count_as_a_multiple():
    t = LB.ENTRY_ROW["1430"]
    day = make_day({0: 6.0})
    a = day.opts[(K_C, "C")]
    a[t + 10, D.H] = 60.0
    a[t + 11, D.O] = 60.0                                   # even with a "confirming" stray next open
    tr = run(day, exit_="hold")
    assert tr.peak_mult < 2.0


def test_guard_helpers():
    a = np.full((5, 7), np.nan)
    a[:, D.C] = [10, 10, 10, 10, 10]
    assert LB.stray(50.0, 10.0) and not LB.stray(10.5, 10.0) and not LB.stray(0.15, 0.05)   # 2 ticks: not stray
    assert LB.guard_sell(a, 2, 50.0) == 10.0 and LB.guard_buy(a, 2, 2.0) == 10.0
    assert LB.guard_sell(a, 2, 9.0) == 9.0 and LB.guard_high(a, 2, 30.0) == pytest.approx(12.0)
    assert math.isnan(LB.ref_close(np.full((3, 7), np.nan), 1))


def test_foreign_rows_filter():
    ixc = np.full(N, np.nan)
    ixc[10] = 25000.0
    spot = np.array([25000.0, 12500.0, 25600.0, 25000.0, 25000.0])
    m = np.array([10, 10, 12, 12, 5])                       # m=12 uses the 10th's close; m=5 has none before it
    assert list(DD.foreign_mask(spot, m, ixc)) == [False, True, False, False, True]


# ------------------------------------------------------------------------------------------------ metrics and readings
class _T:
    def __init__(self, und, d, net, peak=1.0, risked=1000.0):
        self.und, self.day, self.net, self.peak_mult, self.risked = und, d, net, peak, risked


class _D:
    def __init__(self, und, d):
        self.und, self.day = und, d


def test_metrics():
    d1, d2, d3 = date(2025, 1, 2), date(2025, 1, 9), date(2025, 1, 16)
    days = [_D("NIFTY", d1), _D("NIFTY", d2), _D("SENSEX", d3)]
    ts = [_T("NIFTY", d1, 5000, 12), _T("NIFTY", d1, -800, 0.5), _T("NIFTY", d2, -900), _T("SENSEX", d3, 300, 2.5)]
    m = LB.metrics(ts, days)
    assert m["tickets"] == 4 and m["net"] == 3600 and m["hit"] == 50.0
    assert (m["x2"], m["x5"], m["x10"]) == (2, 1, 1)
    assert m["per1000"] == 900 and m["worst_day"] == -900 and m["max_dd"] == 900
    assert m["top1"] == pytest.approx(5000 / 3600 * 100, abs=1) and m["ex5"] == 0
    assert m["by_index"]["SENSEX"] == [1, 300.0]
    assert list(LB.daily(ts, days)) == [4200, -900, 300]
    e = LB.metrics([], days)
    assert e["net"] == 0 and e["tickets"] == 0 and e["top5"] is None


def test_paired_and_reading():
    a = np.array([10.0, 20, 30, 40])
    b = np.zeros(4)
    p = LB.paired(a, b)
    assert p["mean"] == 25 and p["lo"] > 0 and p["n"] == 4 and p["total"] == 100
    assert LB.reading(p, {"mean": 5}) == "better"
    assert LB.reading(p, {"mean": -5}) == "no clear difference"
    neg = LB.paired(-a, b)
    assert LB.reading(neg, {"mean": -1}) == "worse"
    wide = LB.paired(np.array([100.0, -90, 5, -10]), b)
    assert LB.reading(wide, {"mean": 50}) == "no clear difference"
    assert LB.paired(np.array([]), np.array([]))["n"] == 0


def test_twins_and_p():
    d = date(2025, 1, 2)
    a, b = LB.TWIN_WINDOW
    table = {("NIFTY", d): {(s, t): (100.0 if t % 2 else None) for s in (1, -1) for t in range(a, b + 1)}}
    tr = _T("NIFTY", d, 0)
    tr.side = 1
    tw = LB.twins([tr], table, n=50)
    assert set(tw) <= {0.0, 100.0}
    assert (tw == 100.0).mean() > 0.9                       # 20 draws almost always find a minute that trades
    none = {("NIFTY", d): {k: None for k in table[("NIFTY", d)]}}
    assert (LB.twins([tr], none, n=5) == 0).all()           # no minute trades: the twin counts 0
    assert LB.p_value(150.0, tw) == pytest.approx(1 / 51)


def test_gates():
    fm = {"net": 100, "tickets": 50, "ex5": 10}
    assert LB.gates(fm, 0.01, 0.05, 1.0)["pass"]
    assert not LB.gates(fm, 0.01, 0.05, -1.0)["pass"]
    assert not LB.gates(dict(fm, tickets=39), 0.01, 0.05, 1.0)["pass"]
    assert not LB.gates(dict(fm, ex5=-1), 0.01, 0.05, 1.0)["pass"]


def test_window_split():
    assert K.period(date(2024, 11, 19)) is None and K.period(date(2024, 11, 20)) == "design"
    assert K.period(date(2026, 3, 31)) == "design" and K.period(date(2026, 4, 1)) == "final"
    assert K.period(date(2026, 10, 14)) is None


# ------------------------------------------------------------------------------------------------ real days
def _real_days():
    if not DD.days_path("NIFTY").exists():
        pytest.skip("lake cache absent")
    return DD.load("NIFTY", "design")[:8] + DD.load("SENSEX", "design")[:8]


def test_no_look_ahead_in_the_pick():
    seen = 0
    for day in _real_days():
        for e in K.ENTRIES:
            t = LB.ENTRY_ROW[e]
            for sgn in (1, -1):
                pk = LB.pick(day, sgn, t)
                for cut in (R.masked(day, t), R.poisoned(day, t)):
                    cut.lot = day.lot
                    assert LB.pick(cut, sgn, t) == pk, (day.und, day.day, e, sgn)
                seen += pk is not None
    assert seen > 0


def test_real_days_in_window_and_lots():
    days = _real_days()
    assert all(K.period(d.day) == "design" for d in days)
    assert all(d.lot == K.lot(d.und, d.day) for d in days)
    assert DD.check_against_r50()["NIFTY"]["only_r54"] == []
