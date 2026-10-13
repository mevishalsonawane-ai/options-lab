"""R20c unit tests on synthetic data (no release files needed): python3 -m pytest research/hunt/r20c/test_r20c.py -q

The look-ahead guard: every level a candle may cross and every event confirmed by a given minute must be the same when
everything after that minute is replaced by noise.
"""
from __future__ import annotations

import inspect
import os
import re
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import events as EV  # noqa: E402
import levels as LV  # noqa: E402
import oi as OI  # noqa: E402
import study as S  # noqa: E402


def walk(seed, n=375, p=50000.0, vol=12.0):
    r = np.random.default_rng(seed)
    c = p + np.cumsum(r.normal(0, vol, n))
    o = np.concatenate([[p], c[:-1]])
    h = np.maximum(o, c) + np.abs(r.normal(0, vol / 2, n))
    lo = np.minimum(o, c) - np.abs(r.normal(0, vol / 2, n))
    return np.column_stack([o, h, lo, c])


def flat_day(closes_by_candle, tf=5, start=100.0):
    """A day whose tf candles close at the given values (each candle flat at its close; candles after the list repeat
    the last close). Highs / lows = the close."""
    nb = 375 // tf
    cl = list(closes_by_candle) + [closes_by_candle[-1]] * (nb - len(closes_by_candle))
    c = np.repeat(np.array(cl, float), tf)
    return np.column_stack([c, c, c, c])


class FakeDay:
    def __init__(self, d, ix):
        self.day, self.ix = d, ix


def noised_after(ix, row, seed):
    out = ix.copy()
    out[row + 1:] = walk(seed, p=float(ix[row, 3]) + 300, vol=40.0)[row + 1:]
    return out


def fake_days(seed, n=8):
    d0 = date(2025, 3, 3)
    out = []
    p = 50000.0
    for j in range(n):
        ix = walk(seed * 100 + j, p=p)
        out.append(FakeDay(d0 + timedelta(days=j), ix))
        p = float(ix[-1, 3])
    return out


# ------------------------------------------------------------------------------------------------ candles and levels
def test_candles_ohlc():
    ix = walk(1)
    c5 = LV.candles(ix, 5)
    assert c5.shape == (75, 4)
    assert c5[3, 0] == ix[15, 0] and c5[3, 3] == ix[19, 3]
    assert c5[3, 1] == ix[15:20, 1].max() and c5[3, 2] == ix[15:20, 2].min()
    c15 = LV.candles(ix, 15)
    assert c15.shape == (25, 4) and c15[24, 3] == ix[374, 3]
    assert LV.candles(ix, 15, 3).shape == (3, 4)


def test_daily_levels_and_pivots():
    ix = flat_day([100, 110, 90, 105], tf=5)
    (h, s1, k1, b1), (l, s2, k2, b2) = LV.pdhl(ix)
    assert (h, l, s1, s2, k1, k2, b1, b2) == (110, 90, 1, -1, "pdh", "pdl", 0, 0)
    p = (110 + 90 + 105) / 3
    r1, sp1 = LV.piv(ix)
    assert r1[0] == pytest.approx(2 * p - 90) and sp1[0] == pytest.approx(2 * p - 110)


def test_or_edges_known_from_1005_and_1015():
    ix = walk(3)
    assert LV.or_known_bar(5) == 10 and LV.or_known_bar(15) == 4
    hi, lo = LV.or_edges(ix, 5)
    assert hi[0] == ix[:50, 1].max() and lo[0] == ix[:50, 2].min() and hi[3] == 10
    ix2 = ix.copy()
    ix2[50:, 1] += 999                                  # nothing after 10:04 moves the range
    assert LV.or_edges(ix2, 15)[0][0] == hi[0]


def test_oi_levels_and_missing_file():
    assert LV.oi_levels((np.nan, np.nan)) == []
    assert LV.oi_levels((49500.0, 50500.0)) == [(50500.0, 1, "oi_call", 0), (49500.0, -1, "oi_put", 0)]


def test_oi_walls_nearest_expiry_on_or_after_session():
    b = pd.DataFrame({"expiry": [date(2025, 3, 5)] * 4 + [date(2025, 3, 12)] * 2,
                      "strike": [100.0, 200.0, 100.0, 200.0, 300.0, 300.0], "side": ["C", "C", "P", "P", "C", "P"],
                      "oi": [5, 9, 7, 1, 99, 99]})
    assert OI.walls(b, date(2025, 3, 5)) == (100.0, 200.0)   # expiry day: today's contract
    assert OI.walls(b, date(2025, 3, 6)) == (300.0, 300.0)   # the 5 Mar contract has expired
    assert np.isnan(OI.walls(None, date(2025, 3, 5))[0])


def test_mirror_keeps_distance():
    assert LV.mirror(105.0, 100.0, 1) == 105.0 and LV.mirror(105.0, 100.0, -1) == 95.0


def test_zone_port_is_h4s_verbatim():
    src = open(os.path.join(HERE, "..", "h4", "comps.py")).read()
    for fn in (LV.swing_zones, LV.pool_zones, LV.mark_breaks):
        m = re.search(rf"^def {fn.__name__}\(.*?(?=^def |^class |^# -)", src, re.S | re.M)
        assert m and m.group(0).strip() == inspect.getsource(fn).strip(), fn.__name__
    assert re.search(r"^L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300$", src, re.M)


# ------------------------------------------------------------------------------------------------ the event logic
def lv_fixed(levels):
    return lambda b: [x for x in levels if b >= x[3]]


def test_n_close_confirmation_and_first_event_only():
    # resistance 100: candle 2 crosses (101), 3 falls back (99), 5 crosses again and stays
    closes = [95, 98, 101, 99, 98, 102, 103, 104, 99, 105]
    C = np.array(closes + [105] * 65, float)
    O = np.concatenate([[95], C[:-1]])
    R = [(100.0, 1, "pdh", 0)]
    e1 = EV.first_break(C, O, 95.0, 1, lv_fixed(R), 1, 5)
    e2 = EV.first_break(C, O, 95.0, 1, lv_fixed(R), 2, 5)
    e3 = EV.first_break(C, O, 95.0, 1, lv_fixed(R), 3, 5)
    assert (e1["b"], e1["e"]) == (2, 2)
    assert (e2["b"], e2["e"]) == (5, 6)
    assert (e3["b"], e3["e"]) == (5, 7)
    assert EV.first_break(C, O, 95.0, -1, lv_fixed([(90.0, -1, "pdl", 0)]), 1, 5) is None


def test_support_break_and_gap_tag():
    C = np.array([95, 94, 93] + [93] * 72, float)
    O = np.array([96, 95, 94] + [93] * 72, float)
    S1 = [(97.0, -1, "pdl", 0)]
    ev = EV.first_break(C, O, 100.0, -1, lv_fixed(S1), 2, 5)
    assert ev["b"] == 0 and ev["e"] == 1 and ev["gap"]          # opened below the level: a gap break
    ev = EV.first_break(C, O, 100.0, -1, lv_fixed([(95.5, -1, "pdl", 0)]), 2, 5)
    assert ev["b"] == 0 and not ev["gap"]


def test_no_crossing_when_previous_close_already_beyond():
    C = np.array([105, 106, 107] + [107] * 72, float)
    assert EV.first_break(C, C, 104.0, 1, lv_fixed([(100.0, 1, "pdh", 0)]), 1, 5) is None


def test_level_known_from_is_respected():
    C = np.array([95, 101, 102, 103, 99, 98, 97, 96, 95, 94, 93, 101, 102] + [102] * 62, float)
    ev = EV.first_break(C, C, 95.0, 1, lv_fixed([(100.0, 1, "orh", 10)]), 2, 5)
    assert ev["b"] == 11


def test_farthest_level_is_the_events_level():
    C = np.array([95, 120, 121] + [121] * 72, float)
    lv = [(100.0, 1, "a", 0), (110.0, 1, "b", 0), (130.0, 1, "c", 0)]
    assert EV.first_break(C, C, 95.0, 1, lv_fixed(lv), 2, 5)["level"] == 110.0


def test_last_event_time_cutoff():
    C = np.array([95] * 73 + [101, 102], float)               # 5-min candles 73 and 74 close at 15:20 / 15:30
    assert EV.first_break(C, C, 95.0, 1, lv_fixed([(100.0, 1, "pdh", 0)]), 1, 5) is None
    C15 = np.array([95] * 24 + [101], float)                   # 15-min candle 24 closes at 15:30: too late
    assert EV.first_break(C15, C15, 95.0, 1, lv_fixed([(100.0, 1, "pdh", 0)]), 1, 15) is None
    C15 = np.array([95] * 23 + [101, 102], float)              # candle 23 closes at 15:15: still counts
    assert EV.first_break(C15, C15, 95.0, 1, lv_fixed([(100.0, 1, "pdh", 0)]), 1, 15)["e"] == 23
    assert EV.first_break(C15, C15, 95.0, 1, lv_fixed([(100.0, 1, "pdh", 0)]), 2, 15) is None


def test_measure_on_a_known_path():
    c = np.full(375, 100.0)
    c[11:] = np.linspace(101, 140, 364)
    ix = np.column_stack([c, c + 1, c - 1, c])
    m = EV.measure(ix, 10, 100.0, 1, 99.5, 20.0)
    assert m["mv15"] == pytest.approx(c[25] - 100) and m["mv30_a"] == pytest.approx((c[40] - 100) / 20)
    assert m["mvc"] == pytest.approx(40.0) and m["fake30"] == 0.0
    assert m["mfe60"] == pytest.approx(c[70] + 1 - 100) and m["mae60"] == 0.0
    m = EV.measure(ix, 10, 100.0, -1, 100.5, 20.0)             # a down break on an up path: fake-out, adverse
    assert m["fake30"] == 1.0 and m["mvc"] == pytest.approx(-40.0) and m["mae60"] < 0
    m = EV.measure(ix, 350, float(c[350]), 1, 0.0, 20.0)
    assert np.isnan(m["mv30"]) and np.isfinite(m["mv15"]) and np.isfinite(m["mvc"])


def test_wait_cost_sign():
    # up break: 1st close 101, 2nd close 104 -> the wait cost 3 points
    ix = flat_day([95, 101, 104, 106], tf=5)
    ctx = S.Ctx("BANKNIFTY", date(2025, 3, 3), 20, ix, 95.0, 10.0, 14.0, False,
                {"PDHL": [(100.0, 1, "pdh", 0)], "PIV": [], "OI": []}, {5: np.empty((0, 4)), 15: np.empty((0, 4))})
    base = {h: np.zeros(375) for h in ("15", "30", "60", "c")}
    ev = pd.DataFrame(S.day_events(ctx, base))
    r = ev[(ev.type == "PDHL") & (ev.tf == 5) & (ev.n == 2)].iloc[0]
    assert r.wait == pytest.approx(3.0) and r.pe == 104.0 and r.row == 14
    r1 = ev[(ev.type == "PDHL") & (ev.tf == 5) & (ev.n == 1)].iloc[0]
    assert r1.wait == 0.0 and r1.row == 9


# ------------------------------------------------------------------------------------------------ liquidity zones
@pytest.mark.parametrize("seed,tf", [(1, 5), (2, 15), (3, 5)])
def test_liq_one_pass_equals_truncated_recompute(seed, tf):
    days = fake_days(seed)
    i = len(days) - 1
    hist = LV.liq_history(days, i, tf)
    m = LV.LiqMap(hist, days[i].ix, tf)
    today = LV.candles(days[i].ix, tf)
    for b in range(0, 375 // tf, 3):
        bars = np.vstack([hist, today[:b]]) if b else hist
        O, H, Lo, Cl = (np.ascontiguousarray(bars[:, j]) for j in range(4))
        zs = LV.swing_zones(H, Lo, Cl) + LV.pool_zones(O, H, Lo, Cl)
        g = len(bars) - 1                                      # the last bar before candle b
        want = sorted((float(z.edge), z.side) for z in zs if z.known <= g and z.broken < 0)
        got = sorted((e, s) for e, s, k, r in m.active(b))
        assert got == want, (b, len(got), len(want))


def test_liq_mirror_recomputes_taken():
    days = fake_days(4)
    m = LV.LiqMap(LV.liq_history(days, 7, 5), days[7].ix, 5)
    assert m.zones
    flip = m.mirrored({k: -1 for k in m.keys()})
    for (e, s, k, kn, br, r), (e2, s2, k2, kn2, br2, r2) in zip(m.zones, flip.zones):
        assert e2 == pytest.approx(2 * r - e) and (s, k, kn) == (s2, k2, kn2)
    same = m.mirrored({k: 1 for k in m.keys()})
    assert [z[:5] for z in same.zones] == [z[:5] for z in m.zones]


# ------------------------------------------------------------------------------------------------ the look-ahead guard
@pytest.mark.parametrize("seed", [1, 2, 3, 4])
def test_lookahead_guard_levels_and_events(seed):
    days = fake_days(seed, n=9)
    i = len(days) - 1
    full = days[i].ix
    base = {h: np.zeros(375) for h in ("15", "30", "60", "c")}

    def ctx_for(ix):
        dd = list(days[:i]) + [FakeDay(days[i].day, ix)]
        prev = dd[i - 1]
        lv = {"PDHL": LV.pdhl(prev.ix), "PIV": LV.piv(prev.ix), "OI": LV.oi_levels((49900.0, 50100.0))}
        return S.Ctx("BANKNIFTY", dd[i].day, i, ix, float(prev.ix[-1, 3]), 100.0, 14.0, False, lv,
                     {tf: LV.liq_history(dd, i, tf) for tf in S.TFS})

    cf = ctx_for(full)
    ev_full = pd.DataFrame(S.day_events(cf, base))
    for cut in (59, 104, 149, 209, 299):
        cn = ctx_for(noised_after(full, cut, seed + cut))
        # levels a candle may cross: every candle that starts by the cut
        for tf in S.TFS:
            lf, ln = LV.LiqMap(cf.hist[tf], full, tf), LV.LiqMap(cn.hist[tf], cn.ix, tf)
            for b in range(0, (cut + 1) // tf + 1):
                assert lf.active(b) == ln.active(b)
                for t in ("PDHL", "PIV", "OI", "OR"):
                    if t == "OR" and (cut + 1) < LV.OR_ROWS:
                        continue
                    assert S.level_fn(cf, t, tf)(b) == S.level_fn(cn, t, tf)(b)
        # events confirmed by the cut, with what was known at them
        ev_cut = pd.DataFrame(S.day_events(cn, base))
        keys = ["type", "tf", "n", "side", "b", "e", "row", "level", "pe", "gap", "wait"]
        a = ev_full[ev_full.row <= cut][keys].sort_values(keys[:4]).reset_index(drop=True)
        b2 = ev_cut[ev_cut.row <= cut][keys].sort_values(keys[:4]).reset_index(drop=True)
        pd.testing.assert_frame_equal(a, b2)


def test_baseline_is_mean_forward_move_in_atr():
    c1 = S.Ctx("X", date(2025, 1, 1), 0, np.column_stack([np.arange(375.0)] * 4), 0, 10.0, 0, False, {}, {})
    c2 = S.Ctx("X", date(2025, 1, 2), 0, np.column_stack([np.zeros(375)] * 4), 0, 10.0, 0, False, {}, {})
    b = S.baseline([c1, c2])
    assert b["15"][0] == pytest.approx(0.75) and np.isnan(b["15"][370]) and b["c"][374] == 0.0


def test_twin_levels_keep_distance_from_open():
    ix = walk(5)
    ctx = S.Ctx("BANKNIFTY", date(2025, 3, 3), 20, ix, 50000.0, 100.0, 14.0, False,
                {"PDHL": [(50100.0, 1, "pdh", 0), (49900.0, -1, "pdl", 0)], "PIV": [], "OI": []}, {})
    o = ix[0, 0]
    tw = S.level_fn(ctx, "PDHL", 5, twin_signs={"pdh": -1, "pdl": 1})(0)
    assert tw[0][0] == pytest.approx(2 * o - 50100.0) and tw[1][0] == pytest.approx(49900.0)
    assert [x[1] for x in tw] == [1, -1]


# ------------------------------------------------------------------------------------------------ the trade engine
import trades as TR  # noqa: E402


def opt(prices, start=0):
    """An option minute array (375, 5) flat at the given closes from `start` (volume 100 lots of 30)."""
    a = np.full((375, 5), np.nan)
    p = np.array(prices, float)
    a[start:start + len(p), 0] = a[start:start + len(p), 1] = a[start:start + len(p), 2] = a[start:start + len(p), 3] = p
    a[start:start + len(p), 4] = 3000
    return a


def test_strike_families():
    assert TR.strike_for({"exit": "orb"}, "BANKNIFTY", 50049.0, 1) == 50000.0
    assert TR.strike_for({"exit": "liq"}, "BANKNIFTY", 50049.0, 1) == 49900.0      # 1-ITM call
    assert TR.strike_for({"exit": "liq"}, "NIFTY", 22010.0, -1) == 22050.0         # 1-ITM put


def test_sim_liq_exits():
    ix = np.full((375, 4), 100.0)
    a = opt([100.0] * 375)
    a[40, 2] = 80.0                                                                # a low through the 15% stop, later
    x, r, why = TR.sim_liq(a, ix, 11, 100.0, 1, 99.0, 5, 30)
    assert why == "time" and r == 31                                                # flat: the 20-min time stop first
    a = opt(list(100.0 + np.arange(375.0)))                                        # +1 a minute: passes the time stop
    a[40, 2] = 80.0
    x, r, why = TR.sim_liq(a, ix, 11, 100.0, 1, 99.0, 5, 30)
    assert why == "stop" and r == 40 and x <= 85.0
    ix2 = ix.copy()
    ix2[24, 3] = 98.0                                                               # candle close (row 24) back below
    a = opt(list(100.0 + np.arange(375.0)))
    x, r, why = TR.sim_liq(a, ix2, 11, 100.0, 1, 99.0, 5, 30)
    assert why == "failed_break" and r == 25
    ix3 = ix.copy()
    ix3[22, 3] = 98.0                                                               # not a candle close: ignored
    x, r, why = TR.sim_liq(a, ix3, 11, 100.0, 1, 99.0, 5, 30)
    assert why == "square_off" and r == TR.A.SQ_ROW


def test_signals_window_and_vix_filter():
    ix = flat_day([95, 101, 104, 106], tf=5)
    ctx = S.Ctx("BANKNIFTY", date(2025, 3, 3), 20, ix, 95.0, 10.0, 14.0, False,
                {"PDHL": [], "PIV": [(100.0, 1, "r1", 0), (50.0, -1, "s1", 0)], "OI": []}, {5: np.empty((0, 4))})
    rule = dict(types=["PIV"], tf=5, n=2, last_entry=(14, 0), sides=(1, -1), exit="orb")
    lv = TR.DayLv(ctx, 5, ["PIV"])
    assert TR.signals(rule, ctx, lv) == [(1, 14, 100.0)]
    assert TR.signals(dict(rule, last_entry=(9, 29)), ctx, lv) == []               # candle closes 09:30 > 09:29
    assert TR.signals(dict(rule, last_entry=(9, 30)), ctx, lv) == [(1, 14, 100.0)]
    assert TR.signals(dict(rule, filters={"vix": ["<13"]}), ctx, lv) == []
    ctx.vix = 12.0
    assert TR.signals(dict(rule, filters={"vix": ["<13"]}), ctx, lv) == [(1, 14, 100.0)]


def test_daylv_twin_signs_mirror_around_open():
    ix = walk(9)
    ctx = S.Ctx("BANKNIFTY", date(2025, 3, 3), 20, ix, 50000.0, 100.0, 14.0, False,
                {"PDHL": [], "PIV": [(50200.0, 1, "r1", 0)], "OI": []}, {})
    lv = TR.DayLv(ctx, 5, ["PIV"], signs={("PIV", "r1"): -1})
    assert lv(0)[0][0] == pytest.approx(2 * ix[0, 0] - 50200.0) and lv(0)[0][1] == 1


def test_score_and_blocks():
    d = [date(2025, 1, k) for k in range(1, 9)]
    tr = [dict(day=d[0], net=100.0, und="X", why="t", side=1), dict(day=d[0], net=-50.0, und="X", why="t", side=1),
          dict(day=d[3], net=-200.0, und="X", why="t", side=1)]
    s = TR.score(tr, d)
    assert s["net"] == -150.0 and s["sessions"] == 8 and s["trade_days"] == 2 and s["worst_day"] == -200.0
    assert s["green_trade_days"] == 0.5 and s["max_dd"] == -200.0
    b = TR.blocks(tr, d)
    assert [x["net"] for x in b] == [50.0, -200.0, 0.0, 0.0]


def test_bh():
    r = TR.bh({"a": 0.001, "b": 0.02, "c": 0.04, "d": 0.5})
    assert r["a"][2] and r["b"][2] and not r["c"][2] and not r["d"][2]
    assert r["d"][1] == pytest.approx(0.5)


def test_prior_pvalues_are_r20_and_r20b_28():
    p = TR.prior_pvalues()
    assert len(p) == 28 and sum(k.startswith("R20b ") for k in p) == 14
