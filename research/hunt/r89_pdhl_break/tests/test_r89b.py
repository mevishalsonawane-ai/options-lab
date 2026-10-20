"""R89B tests: the RUN exit on a hand-worked path, ATR14 of 15m candles, breadth, opening range, gap, plan_b filters, the
look-ahead guard across indices, and the holdout guard."""
from __future__ import annotations

from datetime import date

import numpy as np
import pytest

from research.hunt.r89_pdhl_break import cfg as K
from research.hunt.r89_pdhl_break import rules, rules_b, study_b
from research.hunt.r89_pdhl_break.tests.test_r89 import flat_day, nse_panel, random_days

O = K.NSE_OPEN


def test_run_exit_breakeven_then_trail_never_loosens():
    # long, entry 100, stop 98 (R = 2)
    mh = np.array([100.5, 101.0, 102.5, 104.0, 103.0, 103.5, 102.0, 101.0])
    ml = np.array([99.5, 99.0, 101.0, 102.5, 102.6, 102.1, 101.9, 100.0])
    mc = (mh + ml) / 2
    mo = mc.copy()
    # minute 2: high 102.5 >= 102 -> stop 100.5 from minute 3; minute 3: high 104 -> stop 102 from minute 4;
    # minute 4: low 102.6 > 102 (holds); minute 5: low 102.1 > 102; minute 6: low 101.9 <= 102 -> out at 102 (moved)
    xm, why, lv = rules_b.run_exit(mo, mh, ml, mc, 0, 1, 100.0, 98.0, 2.0, 8)
    assert (xm, why, lv) == (6, 4, 102.0)
    # initial stop hit before +1R
    ml2 = ml.copy()
    ml2[1] = 97.9
    assert rules_b.run_exit(mo, mh, ml2, mc, 0, 1, 100.0, 98.0, 2.0, 8)[:2] == (1, 1)
    # session end
    assert rules_b.run_exit(mo, np.full(8, 100.5), np.full(8, 99.0), np.full(8, 99.5), 0, 1, 100.0, 98.0, 2.0, 8)[:2] == (8, 3)
    # put mirror
    xm, why, lv = rules_b.run_exit(-mo, -ml, -mh, -mc, 0, -1, -100.0, -98.0, 2.0, 8)
    assert (xm, why, lv) == (6, 4, -102.0)


def test_atr15_and_last_candle():
    P = nse_panel(random_days(3, seed=4))
    A = rules_b.atr15(P)
    C = P.C15
    assert np.isnan(A[:13]).all() and np.isfinite(A[13:]).all()
    pc = np.r_[np.nan, C["c"][:-1]]
    tr = np.nanmax(np.vstack([C["h"] - C["l"], np.abs(C["h"] - pc), np.abs(C["l"] - pc)]), axis=0)
    assert A[20] == pytest.approx(tr[7:21].mean())
    # minute 09:30 of day 1: the last closed 15m candle is 09:15-09:29 of day 1
    i = rules_b.last_c15(P, 1, O + 15)
    assert C["day"][i] == 1 and P.mt[C["s"][i]] == O


def test_breadth_opening_range_gap():
    up = flat_day(100)
    up[60] = (106, 107, 105.8, 106.5)        # closes above PDH 105 at 10:15
    prev = flat_day(100, hi=105, lo=95)
    P1 = nse_panel([prev, up])
    fb = rules_b.first_breaks(P1)
    d1 = P1.days[1]
    assert fb[d1] == (O + 60, 10 ** 6)
    fb_all = {"A": fb, "B": fb, "C": fb, "D": fb, "E": {}}
    assert rules_b.breadth_count(fb_all, d1, O + 60, 1) == 0       # the 10:15 close is known only from 10:16
    assert rules_b.breadth_count(fb_all, d1, O + 61, 1) == 4
    assert rules_b.breadth_count(fb_all, d1, O + 61, -1) == 0
    hi, lo = rules_b.opening_range(P1, 1)
    assert (hi, lo) == (100.5, 99.5)
    gap = flat_day(101)
    P2 = nse_panel([prev, gap, flat_day(101.2)])
    assert not rules_b.gap_ok(P2, 1) and rules_b.gap_ok(P2, 2)


def test_plan_b_filters_hold():
    days = random_days(25, seed=21)
    Ps = {u: nse_panel(random_days(25, seed=21 + i), name="NIFTY") for i, u in enumerate("ABCDE")}
    P = nse_panel(days)
    fb_all = {u: rules_b.first_breaks(x) for u, x in Ps.items()}
    fb_all["self"] = rules_b.first_breaks(P)
    A = rules_b.atr15(P)
    for v in rules_b.VARIANTS_B:
        G = rules.gen_triggers(P, "C", v.tf, False, False)
        T = rules_b.plan_b(P, v, G, rules.Swings(G["C"], 5), A, fb_all, 0, P.ndays - 1)
        for x in T:
            tod = int(P.mt[x["t"]])
            assert tod >= rules_b.window_first(v) and tod <= K.NSE_LAST and x["risk"] > 0
            if v.breadth:
                assert rules_b.breadth_count(fb_all, P.days[x["day"]], tod, x["side"]) >= 4
            if v.gap:
                assert rules_b.gap_ok(P, x["day"])
            if v.orf:
                hi, lo = rules_b.opening_range(P, x["day"])
                assert (x["entry"] > hi) if x["side"] > 0 else (x["entry"] < lo)
            if v.stop == "atr":
                assert x["risk"] == pytest.approx(A[rules_b.last_c15(P, x["day"], tod)])
            if v.exit == "run":
                assert x["why"] in ("stop", "trail", "time") and not np.isfinite(x["target"])


@pytest.mark.parametrize("name", ["B2", "B3", "B6", "B8"])
def test_look_ahead_guard_b(name):
    v = rules_b.BY_NAME_B[name]
    rng = np.random.default_rng(5)
    base = {u: random_days(10, seed=40 + i) for i, u in enumerate(K.INDICES)}
    cut_day, cut_min = 8, 200
    noisy = {}
    for u, dd in base.items():
        nd = [list(b) for b in dd]
        for d in range(cut_day, len(nd)):
            for i in range(cut_min + 1 if d == cut_day else 0, 375):
                o = 80 + rng.normal(0, 5)
                c = o + rng.normal(0, 3)
                nd[d][i] = (o, max(o, c) + 1, min(o, c) - 1, c)
        noisy[u] = nd
    res = []
    for world in (base, noisy):
        Ps = {u: nse_panel(dd) for u, dd in world.items()}
        fb_all = {u: rules_b.first_breaks(x) for u, x in Ps.items()}
        P = Ps["NIFTY"]
        A = rules_b.atr15(P)
        G = rules.gen_triggers(P, "C", v.tf, False, False)
        T = rules_b.plan_b(P, v, G, rules.Swings(G["C"], 5), A, fb_all, 0, P.ndays - 1)
        cut = P.ms[cut_day] + cut_min
        res.append({x["t"]: (x["side"], x["entry"], x["stop"], x["risk"]) for x in T if x["t"] <= cut})
        # breadth counts before the cut
        res.append([rules_b.breadth_count(fb_all, P.days[cut_day], O + m, s) for m in range(1, cut_min + 1)
                    for s in (1, -1)])
    assert res[1] == res[3]
    for t in set(res[0]) & set(res[2]):
        assert res[0][t] == res[2][t]


def test_holdout_guard_b():
    with pytest.raises(PermissionError):
        study_b.check(date(2023, 1, 1), date(2023, 6, 1))
    study_b.check(date(2024, 11, 20), date(2026, 10, 19))


def test_variants_b_declared():
    assert [v.name for v in rules_b.VARIANTS_B] == [f"B{i}" for i in range(9)]
    b8 = rules_b.BY_NAME_B["B8"]
    assert b8.breadth and b8.orf and b8.gap and b8.tf == 15 and b8.exit == "run"
