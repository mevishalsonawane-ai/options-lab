"""R20 unit tests on synthetic data (no release files needed): python3 -m pytest research/hunt/r20/test_r20.py -q"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r20lib as L  # noqa: E402
import r20run as R  # noqa: E402


def flat_ix(price=50000.0):
    ix = np.full((375, 4), price)
    return ix


def set_bar(ix, k, o, h, lo, c):
    r = slice(5 * k, 5 * k + 5)
    ix[r, 0], ix[r, 1], ix[r, 2], ix[r, 3] = o, (o + c) / 2, (o + c) / 2, c
    ix[5 * k + 1, 1] = h
    ix[5 * k + 2, 2] = lo
    ix[5 * k + 4, 3] = c


def bars_from(ix):
    return L.bars5(ix)


# ------------------------------------------------------------------------------------------------ constants
def test_bar_labels_match_the_app():
    assert (L.K_0930, L.K_0945, L.K_1000) == (3, 6, 9)       # 15-, 30-, 45-minute ranges end on these bars
    assert L.K_1355 == 56 and L.K_1425 == 62 and L.K_1030 == 15  # R17's ORB_ARMS windows
    assert (L.K_1400, L.K_1500) == (57, 69)
    v = {x.name: x for x in L.VARIANTS}
    assert len(v) == 8 and set(L.ENTRY_ID) == set(v) - {"V0"}   # 7 tested + the baseline


def test_bars5_builds_five_minute_bars():
    ix = flat_ix()
    set_bar(ix, 2, 100, 130, 90, 120)
    o, h, lo, c = bars_from(ix)
    assert (o[2], h[2], lo[2], c[2]) == (100, 130, 90, 120)
    assert len(o) == 75


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_range_never_reads_a_bar_after_its_end():
    ix = flat_ix()
    set_bar(ix, 3, 50000, 50100, 49900, 50000)
    _, h, lo, _ = bars_from(ix)
    base = L.opening_range(h, lo, L.K_0930)
    for k in range(L.K_0930 + 1, 75):                         # anything after 09:30 changes nothing
        h2, l2 = h.copy(), lo.copy()
        h2[k], l2[k] = 99999, 1
        assert L.opening_range(h2, l2, L.K_0930) == base
    h3 = h.copy()
    h3[L.K_0930] = 50500                                      # the end bar itself is part of the range
    assert L.opening_range(h3, lo, L.K_0930)[0] == 50500


def test_minute_after_the_range_end_does_not_leak_into_the_range():
    ix = flat_ix()
    ix[5 * (L.K_1000 + 1), 1] = 51000                         # the 10:05 bar's first minute
    _, h, lo, _ = bars_from(ix)
    assert L.opening_range(h, lo, L.K_1000)[0] == 50000


def test_adaptive_plan_reads_no_bar_after_10_00():
    ix = flat_ix()
    set_bar(ix, 1, 50000, 50300, 49900, 50000)
    _, h, lo, _ = bars_from(ix)
    v = L.Variant("B", "adaptive", L.K_1000, L.K_1355, 0.5)
    p = L.plan_for(v, h, lo, 1000.0)
    h2, l2 = h.copy(), lo.copy()
    h2[L.K_1000 + 1:], l2[L.K_1000 + 1:] = 70000, 30000
    assert L.plan_for(v, h2, l2, 1000.0) == p


def test_a_decision_reads_only_its_own_bar():
    ix = flat_ix()
    set_bar(ix, 0, 50000, 50100, 49900, 50000)
    set_bar(ix, 12, 50000, 50200, 50000, 50150)               # a break at 10:15
    _, h, lo, c = bars_from(ix)
    p = L.plan_for(L.VARIANTS[0], h, lo, 1000.0)
    first = L.signals(p, h, lo, c)[0]
    c2 = c.copy()
    c2[13:] = 1.0                                              # later bars cannot change an earlier decision
    assert L.signals(p, h, lo, c2)[0] == first == (12, 1)


def test_atr14_needs_15_sessions_and_ignores_the_day_itself():
    hlc = [(110.0, 90.0, 100.0)] * 15 + [(1e6, 0.0, 5e5)]
    assert np.isnan(L.atr14(hlc, 14))
    assert L.atr14(hlc, 15) == pytest.approx(20.0)            # the 15th session's huge range is not read
    hlc2 = [(110.0, 90.0, 100.0)] * 14 + [(130.0, 125.0, 128.0), (0, 0, 0)]
    assert L.atr14(hlc2, 15) == pytest.approx((13 * 20 + 30) / 14)   # true range uses the gap from the previous close


# ------------------------------------------------------------------------------------------------ plans
def _range_bars(width45, width15):
    ix = flat_ix()
    set_bar(ix, 0, 50000, 50000 + width15 / 2, 50000 - width15 / 2, 50000)
    set_bar(ix, 7, 50000, 50000 + width45 / 2, 50000 - width45 / 2, 50000)
    return bars_from(ix)


def test_plans_for_each_variant():
    _, h, lo, _ = _range_bars(600, 200)
    v = {x.name: x for x in L.VARIANTS}
    p0 = L.plan_for(v["V0"], h, lo, 1000.0)
    assert (p0.rule, p0.orh - p0.orl, p0.k0, p0.k1, p0.cap, p0.side_cap) == ("break", 600, 10, 56, 99, 1)
    assert L.plan_for(v["A15"], h, lo, 1000.0).k0 == 4 and L.plan_for(v["A15"], h, lo, 1000.0).orh - 50000 == 100
    assert L.plan_for(v["A30"], h, lo, 1000.0).k0 == 7
    assert L.plan_for(v["C1430"], h, lo, 1000.0).k1 == 62
    wide = L.plan_for(v["B0.5"], h, lo, 1000.0)                # 600 > 500: the 15-minute range, from 10:05
    assert wide.wide and wide.orh - wide.orl == 200 and wide.k0 == 10
    narrow = L.plan_for(v["B1.0"], h, lo, 1000.0)              # 600 <= 1000: the plain range
    assert not narrow.wide and narrow.orh - narrow.orl == 600
    edge = L.plan_for(v["B0.75"], h, lo, 800.0)                # 600 <= 0.75 x 800 exactly: not wide
    assert not edge.wide
    d = L.plan_for(v["D"], h, lo, 700.0)
    assert (d.rule, d.cap, d.side_cap, d.orh - d.orl) == ("fade", 2, 0, 200)


def test_width_rules_need_atr_and_ranges_need_width():
    _, h, lo, _ = _range_bars(600, 200)
    for name in ("B0.5", "D"):
        assert L.plan_for(next(x for x in L.VARIANTS if x.name == name), h, lo, float("nan")) is None
    flat = np.full(75, 50000.0)
    assert L.plan_for(L.VARIANTS[0], flat, flat, 1000.0) is None   # a frozen / stale feed: no range, no trade


def test_signal_rules():
    assert L.signal("break", 110, 90, 112, 100, 111) == 1
    assert L.signal("break", 110, 90, 105, 85, 89) == -1
    assert L.signal("break", 110, 90, 112, 88, 110) == 0          # a close on the edge is not a break
    assert L.signal("fade", 110, 90, 108.5, 100, 105) == -1        # reached the top tenth, closed below the high
    assert L.signal("fade", 110, 90, 105, 91, 95) == 1
    assert L.signal("fade", 110, 90, 105, 95, 100) == 0
    assert L.signal("fade", 100, 100, 101, 99, 100) == 0           # no width


def test_take_applies_caps_cooldown_and_refusals():
    p = L.Plan("break", 110, 90, 10, 56, 99, 1)
    calls = []

    def trade(k, s):
        calls.append((k, s))
        if k == 11:
            return None                                            # refused (e.g. premium too low): no cap used
        return dict(k=k, s=s, net=1.0), 5 * k + 20                 # exits in the fourth bar after
    sigs = [(11, 1), (12, 1), (13, 1), (16, -1), (17, -1), (30, 1), (40, -1)]
    got = L.take(sigs, p, trade)
    # 12 exits in bar 16 (row 80): 13 and 16 are in the cooldown, 17 is the first bar after it; then both sides are used
    assert [(t["k"], t["s"]) for t in got] == [(12, 1), (17, -1)]
    assert L.take([], p, trade) == []
    fade = L.Plan("fade", 110, 90, 10, 56, 2, 0)
    assert len(L.take([(12, 1), (20, 1), (30, 1)], fade, lambda k, s: (dict(k=k, s=s), 5 * k + 4))) == 2


# ------------------------------------------------------------------------------------------------ facts
def test_day_facts_inside_day_with_a_late_break():
    ix = flat_ix()
    set_bar(ix, 0, 50000, 50300, 49700, 50000)
    set_bar(ix, 60, 50000, 50400, 50000, 50350)                 # first close above the range at 14:15
    _, h, lo, c = bars_from(ix)
    f = L.day_facts(h, lo, c)
    assert not f["orb"] and f["late_k"] == 60 and f["late_s"] == 1 and f["w45"] == 600
    set_bar(ix, 30, 50000, 50400, 50000, 50350)                 # an in-window break: ORB trades, no late break counted
    f2 = L.day_facts(*bars_from(ix)[1:])
    assert f2["orb"] and f2["late_k"] is None


def test_day_facts_never_breaks():
    ix = flat_ix()
    set_bar(ix, 0, 50000, 50300, 49700, 50000)
    f = L.day_facts(*bars_from(ix)[1:])
    assert not f["orb"] and not f["sweep"] and f["late_k"] is None


# ------------------------------------------------------------------------------------------------ scores
def test_metrics_and_drawdown():
    m = L.metrics(np.array([0.0, 100.0, -300.0, 0.0, 50.0]), 4)
    assert m["trade_days"] == 3 and m["net"] == -150 and m["worst_day"] == -300
    assert m["max_dd"] == 300 and m["green_pct"] == pytest.approx(200 / 3)
    e = L.metrics(np.array([]), 0)
    assert e["sessions"] == 0 and e["max_dd"] == 0 and np.isnan(e["green_pct"])
    assert L.metrics(np.array([-10.0, -10.0]), 2)["max_dd"] == 20     # drawdown from the start


def test_bh_and_blocks():
    q = L.bh_adjust([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], rel=1e-4)
    assert L.blocks(10) == [(0, 2), (2, 5), (5, 8), (8, 10)]
    assert sum(b - a for a, b in L.blocks(361)) == 361


# ------------------------------------------------------------------------------------------------ the option trade
class FakeDay:
    def __init__(self, opts, und="BANKNIFTY"):
        self.ix = flat_ix()
        self.und = und
        self.day = date(2025, 1, 6)
        self.lot = 30
        self.opts = opts
        self.expiry = date(2025, 1, 8)
        self.vix_prev = 14.0


def opt(price, gap_rows=()):
    a = np.full((375, 5), np.nan, dtype=np.float32)
    a[:, 0] = a[:, 1] = a[:, 2] = a[:, 3] = price
    a[:, 4] = 1000
    for r in gap_rows:
        a[r] = np.nan
    return a


def test_trade_refusals_and_a_square_off():
    k = 20
    ctx = R.DayCtx(FakeDay({}), 500.0)
    assert ctx.trade(k, 1) is None                              # no contract (holiday chain missing)
    cheap = R.DayCtx(FakeDay({(date(2025, 1, 8), 50000.0, "C"): opt(30.0)}), 500.0)
    assert cheap.trade(k, 1) is None                            # premium at or below 40: refused
    stale = R.DayCtx(FakeDay({(date(2025, 1, 8), 50000.0, "C"): opt(200.0, gap_rows=range(5 * k + 5, 5 * k + 9))}), 500.0)
    assert stale.trade(k, 1) is None                            # no print within 3 minutes of the decision
    same_day = R.DayCtx(FakeDay({(date(2025, 1, 6), 50000.0, "C"): opt(200.0)}), 500.0)
    assert same_day.trade(k, 1) is None                         # the release's rule: the next expiry, never today's
    ok = R.DayCtx(FakeDay({(date(2025, 1, 8), 50000.0, "C"): opt(200.0)}), 500.0)
    rec, xr = ok.trade(k, 1)
    assert rec["why"] == "square_off" and xr == 355 and rec["net"] < 0   # flat premium: charges and slippage only
    assert ok.trade(k, 1)[0] is rec                             # cached


def test_nifty_uses_half_points_and_its_strike_step():
    d = FakeDay({(date(2025, 1, 8), 50000.0, "P"): opt(25.0)}, und="NIFTY")
    ctx = R.DayCtx(d, 200.0)
    assert ctx.r["stop"] == 20 and ctx.strike == 50000.0
    assert ctx.trade(20, -1) is not None                        # 25 > NIFTY's floor of 20


def test_holdout_is_read_once(tmp_path, monkeypatch):
    flag = tmp_path / "r20_holdout_read"
    flag.write_text("2026-10-13")
    monkeypatch.setattr(R, "FLAG", flag)
    with pytest.raises(SystemExit):
        R.main(["r20run.py", "holdout", "BANKNIFTY"])
