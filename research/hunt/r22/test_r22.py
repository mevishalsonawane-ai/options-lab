"""R22 tests (PREREG.md "Tests"): levels, events, the mirror twin, the exits, the baseline = R20's V0, look-ahead guards.

    python -m pytest research/hunt/r22/test_r22.py -q
"""
from __future__ import annotations

import os
import sys
from datetime import date
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r22cfg  # noqa: E402,F401
import arms as A  # noqa: E402
import r20lib as L20  # noqa: E402
import r22events as EV  # noqa: E402
import r22levels as LV  # noqa: E402
import r22trades as T  # noqa: E402


# ------------------------------------------------------------------------------------------------ helpers
def ix_from(close, spread=0.0):
    """(375, 4) OHLC from a 1-minute close path: open = previous close, high / low = max / min of open, close +- spread."""
    c = np.asarray(close, dtype=float)
    o = np.concatenate([[c[0]], c[:-1]])
    return np.stack([o, np.maximum(o, c) + spread, np.minimum(o, c) - spread, c], axis=1)


def path5(levels5, start=100.0):
    """A 375-minute path that moves linearly to each 5-minute bar's close in levels5 (75 values)."""
    out, prev = [], start
    for v in levels5:
        out += list(np.linspace(prev, v, 6)[1:])
        prev = v
    return np.array(out)


def morning_then(after5, oh=110.0, ol=90.0):
    """A day whose 15-minute range is exactly [ol, oh] (P10 = 100), then 5-minute closes after5 (66 values, 10:00 on)."""
    first = [100, 105, oh, 100, 95, ol, 95, 100, 100]               # 9 five-minute bars: 09:15 .. 09:55
    return ix_from(path5(first + list(after5)))


def random_day(rng, n=375, start=1000.0):
    return ix_from(start + np.cumsum(rng.normal(0, 3, n)), spread=1.0)


# ------------------------------------------------------------------------------------------------ levels
def test_levels_basic():
    ix = morning_then([100] * 66)
    lv = LV.morning_levels(ix)
    assert lv["OH"] == pytest.approx(110) and lv["OL"] == pytest.approx(90) and lv["OM"] == pytest.approx(100)
    assert lv["R"] == pytest.approx(20) and lv["P10"] == pytest.approx(100)
    assert lv["X+1"] == pytest.approx(130) and lv["X-0.5"] == pytest.approx(80)
    assert len(lv["candles"]) == 3
    assert lv["candles"][0][0] == pytest.approx(110) and lv["candles"][1][1] == pytest.approx(90)
    assert all(lv["OL"] < x < lv["OH"] for x in lv["inner"])


def test_levels_flat_morning_no_inner_and_zero_range():
    ix = ix_from(np.full(375, 100.0))
    lv = LV.morning_levels(ix)
    assert lv["R"] == 0 and lv["inner"] == []


def test_levels_lookahead_guard():
    rng = np.random.default_rng(1)
    for _ in range(25):
        ix = random_day(rng)
        noisy = ix.copy()
        noisy[LV.OR_ROWS:] = random_day(rng)[LV.OR_ROWS:] * 3
        assert LV.morning_levels(ix) == LV.morning_levels(noisy)


def test_mirror_twin():
    rng = np.random.default_rng(2)
    lv = LV.morning_levels(random_day(rng))
    m = LV.mirror(lv)
    p = lv["P10"]
    assert m["OH"] == pytest.approx(2 * p - lv["OL"]) and m["OL"] == pytest.approx(2 * p - lv["OH"])
    assert m["R"] == pytest.approx(lv["R"]) and m["OH"] - m["OL"] == pytest.approx(lv["R"])
    assert m["OH"] - p == pytest.approx(p - lv["OL"])                   # twin OH at OL's distance
    assert m["X+1"] == pytest.approx(m["OH"] + m["R"])
    mm = LV.mirror(m)
    for k in ("OH", "OL", "OM", "X+0.5", "X-1"):
        assert mm[k] == pytest.approx(lv[k])


def test_bars():
    ix = ix_from(np.arange(375, dtype=float))
    o, h, lo, c = LV.bars(ix, 15)
    assert len(c) == 25 and c[0] == 14 and h[1] == 29 and lo[1] == 14 and o[2] == 29


# ------------------------------------------------------------------------------------------------ events
def kinds(evs, edge=None):
    return [(e["kind"], e["k"]) for e in evs if edge is None or e.get("edge") == edge]


def test_break_hold_retest_hold():
    after = [105, 112, 115, 118, 120, 111, 116] + [116] * 59        # break at bar 10, hold at 12, retest at 14 holds
    ix = morning_then(after)
    lv = LV.morning_levels(ix)
    evs = EV.edge_events(LV.bars(ix, 5), 5, lv["OH"], 1, lv["R"], 9, 59)
    ks = kinds(evs)
    assert ("touch", 10) in ks and ("BREAK", 10) in ks and ("HOLD", 12) in ks and ("RETEST-HOLD", 14) in ks
    assert not any(k == "REJECT" for k, _ in ks)


def test_break_fail():
    ix = morning_then([105, 112, 108] + [100] * 63)
    lv = LV.morning_levels(ix)
    ks = kinds(EV.edge_events(LV.bars(ix, 5), 5, lv["OH"], 1, lv["R"], 9, 59))
    assert ("BREAK", 10) in ks and ("FAIL", 11) in ks and not any(k == "HOLD" for k, _ in ks)


def test_retest_fail_and_direction():
    ix = morning_then([105, 112, 115, 118, 120, 109] + [100] * 60)
    lv = LV.morning_levels(ix)
    evs = EV.edge_events(LV.bars(ix, 5), 5, lv["OH"], 1, lv["R"], 9, 59)
    rf = [e for e in evs if e["kind"] == "RETEST-FAIL"]
    assert len(rf) == 1 and rf[0]["k"] == 14 and rf[0]["d"] == -1


def test_touch_reject_and_support_side():
    # the 10:05 bar's high reaches OL - 1 (a wick through support) but every close stays inside: reject (direction up)
    ix = morning_then([100] * 66)
    r = 5 * 10 + 2
    ix[r, 2] = 89.0
    lv = LV.morning_levels(ix)
    evs = EV.edge_events(LV.bars(ix, 5), 5, lv["OL"], -1, lv["R"], 9, 59)
    assert kinds(evs) == [("touch", 10), ("REJECT", 12)]
    assert [e["d"] for e in evs if e["kind"] == "REJECT"] == [1]


def test_no_reject_when_window_runs_out():
    ix = morning_then([100] * 66)
    ix[5 * 58 + 2, 1] = 111.0                                           # touch at 14:50: the 3rd close would be 15:00+
    lv = LV.morning_levels(ix)
    ks = kinds(EV.edge_events(LV.bars(ix, 5), 5, lv["OH"], 1, lv["R"], 9, 59))
    assert ks == [("touch", 58)]


def test_mid_and_inner_reject():
    ix = morning_then([104, 103, 101, 102, 104, 106] + [106] * 60)
    ix[5 * 11 + 2, 2] = 99.5                                            # bar 11 wicks to the mid from above, closes above
    lv = LV.morning_levels(ix)
    evs = EV.level_reject(LV.bars(ix, 5), 9, 59, lv["OM"])
    assert kinds(evs) == [("touch", 11), ("REJECT", 13)] and evs[1]["d"] == 1
    evs2 = EV.level_reject(LV.bars(ix, 5), 9, 59, 50.0)                 # never touched
    assert evs2 == []


def test_ext_reach():
    after = [105, 112, 121, 131] + [131] * 62
    ix = morning_then(after)
    lv = LV.morning_levels(ix)
    bs = LV.bars(ix, 5)
    out = EV.ext_reach(ix, 5, 10, lv["OH"], 1, {"0.5": lv["X+0.5"], "1": lv["X+1"]}, bs[3])
    assert out == {"reach_0.5": 1.0, "clean_0.5": 1.0, "reach_1": 1.0, "clean_1": 1.0}
    after2 = [105, 112, 100, 131] + [131] * 62                          # back inside first, then up: not clean
    ix2 = morning_then(after2)
    out2 = EV.ext_reach(ix2, 5, 10, lv["OH"], 1, {"1": lv["X+1"]}, LV.bars(ix2, 5)[3])
    assert out2 == {"reach_1": 1.0, "clean_1": 0.0}


def test_15min_break_event_and_window():
    ix = morning_then([100] * 3 + [112] * 63)                           # 15-min bar 3 (10:00) closes 100, bar 4 > OH
    lv = LV.morning_levels(ix)
    evs = EV.edge_events(LV.bars(ix, 15), 15, lv["OH"], 1, lv["R"], 3, 19, retest=False, touch=False)
    assert ("BREAK", 4) in kinds(evs) and ("HOLD", 6) in kinds(evs)
    assert EV.row_of(4, 15) == 74


def test_day_events_rows_and_twin_runs():
    rng = np.random.default_rng(3)
    ix = random_day(rng)
    lv = LV.morning_levels(ix)
    for L in (lv, LV.mirror(lv)):
        for e in EV.day_events(ix, L):
            assert e["row"] == (e["k"] + 1) * e["tf"] - 1
            assert e["row"] <= 299                                      # decisions by 15:00
            assert e["row"] >= 45 + 4 or e["tf"] == 15


def test_events_lookahead_guard():
    """Every event decided at or before a cut minute is the same when every minute after the cut is noise."""
    rng = np.random.default_rng(4)
    for _ in range(60):
        ix = random_day(rng)
        cut_row = int(rng.integers(50, 300))
        noisy = ix.copy()
        noisy[cut_row + 1:] = random_day(rng, start=float(ix[cut_row, 3]))[cut_row + 1:] * rng.uniform(0.9, 1.1)
        lv, lvn = LV.morning_levels(ix), LV.morning_levels(noisy)
        assert lv == lvn
        key = lambda evs: sorted((e["kind"], e["edge"], e["tf"], e["k"], e["d"], round(e["level"], 6))  # noqa: E731
                                 for e in evs if e["row"] <= cut_row)
        assert key(EV.day_events(ix, lv)) == key(EV.day_events(noisy, lvn))
        for rule in T.RULES.values():
            a = [s for s in T.signals(rule, ix, lv, 0.5) if s[1] <= cut_row]
            b = [s for s in T.signals(rule, noisy, lvn, 0.5) if s[1] <= cut_row]
            assert a == b


# ------------------------------------------------------------------------------------------------ rules and exits
def test_signals_filters_and_first_retest_must_hold():
    ix = morning_then([100] * 3 + [112] * 63)
    lv = LV.morning_levels(ix)
    assert T.signals(T.RULES["r22-brk15"], ix, lv, 0.5) == [(1, 74, "OH")]
    assert T.signals(T.RULES["r22-brk15-nowide"], ix, lv, 0.6) == []
    assert T.signals(T.RULES["r22-brk15-nowide"], ix, lv, 0.5) != []
    assert T.signals(T.RULES["r22-brk15-nonarrow"], ix, lv, 0.3) == []
    assert T.signals(T.RULES["r22-brk15-nonarrow"], ix, lv, T.W_NARROW) != []
    # first retest fails, a later one would hold: no retest trade
    ix2 = morning_then([105, 112, 115, 118, 120, 109, 115, 111, 116] + [116] * 57)
    assert T.signals(T.RULES["r22-retest"], ix2, LV.morning_levels(ix2), 0.5) == []
    ix3 = morning_then([105, 112, 115, 118, 120, 111, 116] + [116] * 59)
    assert T.signals(T.RULES["r22-retest"], ix3, LV.morning_levels(ix3), 0.5) == [(1, 74, "OH")]


def test_break_after_window_is_no_trade():
    ix = morning_then([100] * 50 + [112] * 16)                          # first close beyond at 5-min bar 59 (14:10 on 15-min)
    lv = LV.morning_levels(ix)
    assert T.signals(T.RULES["r22-brk15"], ix, lv, 0.5) == []


def opt_path(rng, n=375, start=100.0):
    c = np.maximum(start + np.cumsum(rng.normal(0, 1.5, n)), 1.0)
    o = np.concatenate([[c[0]], c[:-1]])
    a = np.stack([o, np.maximum(o, c) + 0.5, np.maximum(np.minimum(o, c) - 0.5, 0.05), c, np.full(n, 1e5)], axis=1)
    return a


def test_sim_defaults_equal_r17_orb_exits():
    rng = np.random.default_rng(5)
    for _ in range(40):
        a = opt_path(rng)
        ix = random_day(rng)
        c0 = int(rng.integers(60, 280))
        e = float(a[c0, 0])
        cfg = T.ARM["BANKNIFTY"]
        assert T.sim(a, ix, c0, e, cfg, 30, 1) == A.sim_fixed_pts(a, c0, e, 40.0, 40.0, 30, 30)


def test_sim_index_stop_and_target():
    a = np.zeros((375, 5))
    a[:, :4] = 100.0
    a[:, 4] = 1e5
    ix = ix_from(np.full(375, 115.0))
    ix[100:, 3] = 109.0                                                 # back inside OH = 110 from row 100
    x, xr, why = T.sim(a, ix, 80, 100.0, T.ARM["BANKNIFTY"], 30, 1, idx_stop=110.0)
    assert why == "idx_stop" and xr == 105                              # the 5-min close at row 104, sold at 105's open
    ix2 = ix_from(np.full(375, 115.0))
    ix2[150, 1] = 130.5
    x, xr, why = T.sim(a, ix2, 80, 100.0, T.ARM["BANKNIFTY"], 30, 1, idx_tgt=130.0)
    assert why == "idx_target" and xr == 151
    # puts: the low must reach the target
    ix3 = ix_from(np.full(375, 85.0))
    ix3[150, 2] = 69.0
    assert T.sim(a, ix3, 80, 100.0, T.ARM["BANKNIFTY"], 30, -1, idx_tgt=70.0)[2] == "idx_target"
    # no premium stop: a premium crash does not exit
    a2 = a.copy()
    a2[120:, :4] = 20.0
    assert T.sim(a2, ix2 * 0 + 115, 80, 100.0, T.ARM["BANKNIFTY"], 30, 1, prem_stop=False)[2] == "square_off"
    assert T.sim(a2, ix2 * 0 + 115, 80, 100.0, T.ARM["BANKNIFTY"], 30, 1)[2] == "stop"


def fake_day(rng, und="BANKNIFTY"):
    ix = random_day(rng, start=50000.0 if und == "BANKNIFTY" else 24000.0)
    ix = ix * 1.0
    d = date(2025, 3, 3)
    st = T.ARM[und]["step"]
    strike = float(np.floor(ix[9, 3] / st + 0.5) * st)
    opts = {(date(2025, 3, 6), strike, s): opt_path(rng, start=200.0).astype(np.float32) for s in ("C", "P")}
    day = SimpleNamespace(day=d, und=und, ix=ix, opts=opts, lot=30)
    hlc = [L20.daily_hlc(ix)] * 16
    atr = L20.atr14(hlc, 15)
    ctx = SimpleNamespace(und=und, day=d, ix=ix, atr=atr, lv=LV.morning_levels(ix), w=LV.morning_levels(ix)["R"] / atr)
    return day, ctx


def test_baseline_matches_r20_v0():
    import r20run as R20
    rng = np.random.default_rng(6)
    for _ in range(15):
        d, c = fake_day(rng)
        mine = T.baseline_day(d, c)
        dc = R20.DayCtx(d, c.atr)
        p = L20.plan_for(L20.VARIANTS[0], dc.bh, dc.bl, dc.atr)
        ref = L20.take(L20.signals(p, dc.bh, dc.bl, dc.bc), p, dc.trade) if p else []
        assert [(t["row"], t["side"], round(t["net"], 6)) for t in mine] == \
               [(5 * r["k"] + 4, r["side"], round(r["net"], 6)) for r in ref]


def test_rule_day_one_trade_per_side_and_mirror_runs():
    rng = np.random.default_rng(7)
    for _ in range(10):
        d, c = fake_day(rng)
        for rid, rule in T.RULES.items():
            tr = T.rule_day(rule, d, c)
            assert len({t["side"] for t in tr}) == len(tr) <= 2
            for t in tr:
                assert t["row"] <= 299 - 60 + 1 and t["exit_row"] >= t["row"]
            T.rule_day(rule, d, c, LV.mirror(c.lv))


def test_no_contract_or_late_print_refused():
    rng = np.random.default_rng(8)
    d, c = fake_day(rng)
    d.opts = {}
    assert T.trade(d, c, 1, 100) is None
    d, c = fake_day(rng)
    for k in d.opts:
        d.opts[k][101:110, :] = np.nan                                  # no print within 3 minutes of the decision
    assert T.trade(d, c, 1, 100) is None


def test_bh_and_scores():
    out = T.bh({"a": 0.001, "b": 0.02, "c": 0.5})
    assert out["a"][2] and out["b"][2] and not out["c"][2]
    trs = [dict(day=date(2025, 1, 1), net=100.0, entry=10, exit=12, peak=100, tgt_pts=40, lot=30),
           dict(day=date(2025, 1, 2), net=-50.0, entry=10, exit=12, peak=95, tgt_pts=40, lot=30)]
    s = T.score(trs, [date(2025, 1, 1), date(2025, 1, 2), date(2025, 1, 3)])
    assert s["net"] == 50 and s["sessions"] == 3 and s["trade_days"] == 2 and s["green_trade_days"] == 0.5
    assert s["worst_day"] == -50 and s["max_dd"] == -50 and s["big"] == 2 and s["cut"] == 2
