"""R63 tests (PREREG.md section 9): bars from gapped minutes, the four rules against hand values, the look-ahead guard,
the 5-minute rule, T3's both-bars-open rule, act rows, combining with the strategy's own forced exit, a session with no
bars, the gates and BH. No lake needed.

    python -m pytest research/hunt/r63/test_r63.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r63cfg as K  # noqa: E402
import r63rules as RR  # noqa: E402
import r63study as ST  # noqa: E402


def day_from(close: np.ndarray, spread: float = 1.0) -> np.ndarray:
    """(4, 375) O H L C with open = previous close, high / low = max / min(open, close) +- spread."""
    c = np.asarray(close, float)
    o = np.concatenate([[c[0]], c[:-1]])
    return np.vstack([o, np.maximum(o, c) + spread, np.minimum(o, c) - spread, c])


def days_list(n: int, start=date(2025, 1, 6)):
    return [start + timedelta(days=i) for i in range(n)]


# ------------------------------------------------------------------------------------------------ bars
def test_bars_gaps_and_partial():
    a = day_from(np.linspace(100, 110, 375))
    a[:, 5:10] = np.nan                              # a whole 5-minute bar missing
    a[:, 12] = np.nan                                # one minute missing inside a bar
    B = RR.bars([date(2025, 1, 6)], [a], 5)
    assert 1 not in B["b"]                           # the empty bar does not exist
    assert len(B["c"]) == 74
    i = list(B["b"]).index(2)                        # bar 10..14, minute 12 missing
    assert B["act"][i] == 15
    assert B["c"][i] == a[3, 14]
    assert B["h"][i] == np.nanmax(a[1, 10:15])


def test_bars_last_minute_missing_still_acts_at_bar_end():
    a = day_from(np.linspace(100, 110, 375))
    a[:, 4] = np.nan
    B = RR.bars([date(2025, 1, 6)], [a], 5)
    assert B["act"][0] == 5 and B["c"][0] == a[3, 3]


def test_no_minutes_no_bars():
    a = np.full((4, 375), np.nan)
    B = RR.bars([date(2025, 1, 6)], [a], 15)
    assert len(B["c"]) == 0
    t = RR.turn_rows("T1", [date(2025, 1, 6)], [a])
    assert t[date(2025, 1, 6)]["C"] == []


def test_twap_is_session_mean_of_typical_price():
    a = day_from(np.linspace(100, 110, 375))
    B = RR.bars([date(2025, 1, 6)], [a], 5)
    typ = (a[1] + a[2] + a[3]) / 3
    assert B["twap"][3] == pytest.approx(typ[:20].mean())


# ------------------------------------------------------------------------------------------------ indicators
def test_ema_hand_values():
    e = RR.ema(np.array([10.0, 20.0, 30.0]), 3)      # alpha 0.5
    assert list(e) == [10.0, 15.0, 22.5]


def test_supertrend_flips_down_after_a_rally_then_a_fall():
    c = np.concatenate([np.linspace(100, 200, 40), np.linspace(200, 120, 30)])
    d = RR.supertrend_dir(c + 1, c - 1, c)
    assert d[:9].tolist() == [0] * 9                 # no ATR before 10 bars
    assert d[20] == 1 and d[-1] == -1
    flips = np.flatnonzero((d[:-1] == 1) & (d[1:] == -1))
    assert len(flips) == 1 and flips[0] >= 40


def test_t1_cross_down_against_call_only():
    # 15-minute closes: up for 30 bars (several days), then down hard
    c = np.concatenate([np.linspace(100, 160, 25 * 15), np.linspace(160, 100, 25 * 15)])
    ds = days_list(2)
    idx = [day_from(c[:375]), day_from(c[375:])]
    t = RR.turn_rows("T1", ds, idx)
    assert t[ds[0]]["C"] == [] and t[ds[1]]["C"]     # the cross down comes on day 2
    assert t[ds[1]]["P"] == []


def test_t4_donchian_break_needs_10_bars():
    c = np.full(375, 100.0)
    c[60:] = 90.0                                    # a drop at row 60 (bar 12)
    t = RR.turn_rows("T4", [date(2025, 1, 6)], [day_from(c, 0.5)])
    assert t[date(2025, 1, 6)]["C"][0] == 65         # bar 12 (rows 60-64) closes below the last 10 lows: act at 65
    assert t[date(2025, 1, 6)]["P"] == []


def test_t3_two_closes_and_both_open():
    c = np.full(375, 100.0)
    c[100:] = 95.0                                   # below the TWAP from bar 20 on
    d = date(2025, 1, 6)
    t = RR.turn_rows("T3", [d], [day_from(c, 0.1)])
    first = t[d]["C"][0]
    assert first == 110 and t[d]["Cf"][0] == 105     # bars 20 and 21 below: acts at 110, pair's first bar acted at 105
    # a trade filled at 104: the pair (105, 110) completed while open and 110 >= 104 + 5
    assert RR.exit_row(t[d], "C", 104, 5, "T3") == 110
    # a trade filled at 106: the first bar of that pair closed before it was open; the next pair (110, 115) acts
    assert RR.exit_row(t[d], "C", 106, 5, "T3") == 115


def test_t3_pair_never_spans_two_sessions():
    c = np.full(375, 100.0)
    c[370:] = 90.0
    d0, d1 = days_list(2)
    c2 = np.full(375, 80.0)
    t = RR.turn_rows("T3", [d0, d1], [day_from(c, 0.1), day_from(c2, 0.1)])
    assert all(x > 5 for x in t[d1]["C"])            # the first bar of a session never pairs with yesterday's last


def test_min_open_rule():
    turns = {"C": [100, 120], "P": [], "Cf": [-1, -1], "Pf": []}
    assert RR.exit_row(turns, "C", 96, 15, "T1") == 120      # 100 is only 4 minutes after the fill
    assert RR.exit_row(turns, "C", 95, 15, "T1") == 100
    assert RR.exit_row(turns, "C", 97, 15, "T1") == 120
    assert RR.exit_row(turns, "P", 10, 15, "T1") == -1
    assert RR.exit_row({}, "C", 10, 15, "T1") == -1


def test_min_open_boundary_exact():
    turns = {"C": [100], "P": [], "Cf": [-1], "Pf": []}
    assert RR.exit_row(turns, "C", 95, 15, "T2") == 100
    assert RR.exit_row(turns, "C", 96, 15, "T2") == -1


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("rule", RR.RULES)
def test_look_ahead_guard(rule):
    rng = np.random.default_rng(1)
    ds = days_list(4)
    c = 20000 + np.cumsum(rng.normal(0, 8, 4 * 375))
    idx = [day_from(c[i * 375:(i + 1) * 375], 3.0) for i in range(4)]
    full = RR.turn_rows(rule, ds, idx)
    cut_day, cut_row = 2, 200                        # change / delete every minute after (day 2, row 199)
    for mode in ("change", "delete"):
        idx2 = [a.copy() for a in idx]
        tail = idx2[cut_day][:, cut_row:]
        idx2[cut_day][:, cut_row:] = np.nan if mode == "delete" else tail * 1.05 + 50
        idx2[3][:] = np.nan if mode == "delete" else idx2[3] * 0.9
        part = RR.turn_rows(rule, ds, idx2)
        for d in ds[:cut_day]:
            assert part[d] == full[d]
        for s in ("C", "P"):
            keep = [x for x in full[ds[cut_day]][s] if x <= cut_row]
            got = [x for x in part[ds[cut_day]][s] if x <= cut_row]
            assert got == keep, (rule, mode, s)


# ------------------------------------------------------------------------------------------------ combine / gates / BH
def test_combine_forced_rows():
    combine = RR.combine
    assert combine(-1, -1) == -1 and combine(50, -1) == 50 and combine(-1, 40) == 40 and combine(50, 40) == 40
    assert combine(30, 40) == 30                     # the strategy's own earlier exit wins


def test_bh_and_p():
    q = ST.bh([0.01, 0.04, 0.03, 0.5])
    assert q.tolist() == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], rel=1e-4)
    assert ST.p_value(10.0, np.array([0.0] * 500)) == pytest.approx(1 / 501)
    assert ST.p_value(10.0, np.array([10.0] * 500)) == 1.0


def _table(base, new, chg, days=None):
    n = len(base)
    days = days or [date(2026, 4, 1) + timedelta(days=i) for i in range(n)]
    return pd.DataFrame(dict(und="NIFTY", day=days, net=base, net_T1=new, chg_T1=chg, why_T1=["rule_exit"] * n))


def test_cell_saved_and_given_up():
    T = _table([-100.0, 200.0, 50.0], [-40.0, 120.0, 50.0], [True, True, False])
    c = ST.cell(T, "T1", np.zeros(500))
    assert c["changed"] == 2 and c["improvement"] == -20
    assert c["saved_on_losers"] == 60 and c["given_up_on_winners"] == 80
    assert c["plain_net"] == 150 and c["rule_net"] == 130
    assert c["impr_ex_top5"] == 0


def test_cell_empty():
    c = ST.cell(pd.DataFrame(), "T1", None)
    assert c["trades"] == 0 and c["changed"] == 0


def test_gates_all_rules():
    f = dict(improvement=1000.0, p=0.01, changed=50, impr_ex_top5=10.0)
    d = dict(improvement=5.0, wf_net=1.0)
    assert ST.gates(f, d, 0.05)["passed"]
    assert "q > 0.10" in ST.gates(f, d, 0.2)["fails"]
    assert "fewer than 40 changed trades" in ST.gates(dict(f, changed=39), d, 0.05)["fails"]
    assert "top-5 >= improvement" in ST.gates(dict(f, impr_ex_top5=0.0), d, 0.05)["fails"]
    assert "walk-forward <= 0" in ST.gates(f, dict(d, wf_net=0.0), 0.05)["fails"]
    assert "design improvement <= 0" in ST.gates(f, dict(d, improvement=-1.0), 0.05)["fails"]
    assert "p >= 0.05" in ST.gates(dict(f, p=0.05), d, 0.05)["fails"]
    assert "final improvement <= 0" in ST.gates(dict(f, improvement=0.0), d, 0.05)["fails"]


def test_final_guard():
    if K.FINAL_FLAG.exists():
        pytest.skip("final already read")
    with pytest.raises(PermissionError):
        K.check_final_allowed("final")
    K.check_final_allowed("design")


def test_quarters_and_max_dd():
    assert ST.max_dd(np.array([100.0, -300.0, 50.0, -100.0])) == 350.0
    T = _table([0.0, 0.0], [10.0, -5.0], [True, True], days=[date(2025, 5, 2), date(2026, 2, 2)])
    assert ST.quarters(T, "T1") == [10.0, 0.0, 0.0, -5.0]


# ------------------------------------------------------------------------------------------------ engine re-pricing
def _engine():
    sys.path.insert(0, str(HERE.parent / "r51"))
    import engine as E
    import r51data
    import r63core as X
    return E, r51data, X


def _fake_day(d=date(2025, 6, 3), expiry=False):
    E, r51data, X = _engine()
    idx = day_from(np.linspace(24000, 24100, 375))
    D = r51data.Day(d, idx, atm=np.full(375, 24000.0), expiry=expiry)
    px = np.linspace(100, 160, 375)
    c = np.vstack([px, px + 1, px - 1, px, np.full(375, 5000.0)]).astype(np.float32)
    D.con[("C", 24000.0)] = c
    return D


def test_resim_reproduces_run_day_and_forced_row_sells_at_open():
    E, r51data, X = _engine()
    D = _fake_day()
    r51data._CACHE["NIFTY"] = {D.d: D}
    E._STACKS.clear()
    X.record()
    ex = E.Exits(stop=0.5, tmax=200)
    out = E.run_day("NIFTY", D, ["C"], [24000.0], [30], ex, [-1])[0]
    assert out["e_sig"] == 30 and out["fx_own"] == -1 and out["why"] == "time"
    T = pd.DataFrame([dict(out, exk="A")])
    net, xr, why = X.resim(T, np.array([-1]), {"A": ex})
    assert net[0] == pytest.approx(out["net"]) and xr[0] == out["xr"]
    net2, xr2, why2 = X.resim(T, np.array([100]), {"A": ex})
    assert xr2[0] == 100 and why2[0] == "rule_exit" and net2[0] < out["net"]   # a rising option sold earlier
    # the strategy's own earlier exit wins: tmax ends at 229, a forced row after it changes nothing
    net3, xr3, _ = X.resim(T, np.array([300]), {"A": ex})
    assert xr3[0] == out["xr"] and net3[0] == pytest.approx(out["net"])


def test_resim_expiry_square_off_beats_a_late_trend_row():
    E, r51data, X = _engine()
    D = _fake_day(expiry=True)
    r51data._CACHE["NIFTY"] = {D.d: D}
    E._STACKS.clear()
    X.record()
    ex = E.Exits()
    out = E.run_day("NIFTY", D, ["C"], [24000.0], [300], ex, [-1])[0]
    T = pd.DataFrame([dict(out, exk="A")])
    _, xr, why = X.resim(T, np.array([360]), {"A": ex})
    assert xr[0] == E.sq_row(D, ex) == 350 and why[0] == "squareoff"


def test_twins_draw_inside_the_rules_window():
    E, r51data, X = _engine()
    D = _fake_day()
    r51data._CACHE["NIFTY"] = {D.d: D}
    E._STACKS.clear()
    X.record()
    ex = E.Exits(tmax=200)
    out = E.run_day("NIFTY", D, ["C"], [24000.0], [30], ex, [-1])[0]
    T = pd.DataFrame([dict(out, exk="A")])
    tw = X.twin_impr(T, np.array([True]), {"A": ex}, 200, np.random.default_rng(0))
    assert (tw < 0).all()                            # every random cut of a steadily rising option gives some up
    assert len(tw) == 200
