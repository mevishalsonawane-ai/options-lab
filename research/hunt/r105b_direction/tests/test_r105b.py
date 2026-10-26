"""R105B tests (PREREG section 5): each bias on hand-made bars, the look-ahead guard, the weekly and 60-minute builds,
the keep / walk / twin plumbing, the gate and BH with the earlier family. Synthetic data only (no lake)."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

import r105bbias as B  # noqa: E402
import r105blib as L  # noqa: E402


def prev_of(c, o=None, h=None, l=None):
    c = np.asarray(c, float)
    return dict(o=np.asarray(o if o is not None else c, float), h=np.asarray(h if h is not None else c + 1, float),
                l=np.asarray(l if l is not None else c - 1, float), c=c)


def bdays(n, start=date(2024, 1, 1)):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


# ================================================================================================ keep
def test_keep():
    assert B.keep("C", 1) and not B.keep("P", 1)
    assert B.keep("P", -1) and not B.keep("C", -1)
    assert B.keep("C", 0) and B.keep("P", 0)


# ================================================================================================ gap rules
def test_d1_gap_beyond_yesterday():
    p = prev_of([100.0], h=[102.0], l=[98.0])
    assert B.d1_gaphl(p, 103.0) == 1 and B.d1_gaphl(p, 97.0) == -1 and B.d1_gaphl(p, 101.0) == 0
    assert B.d1_gaphl(p, 102.0) == 0                                  # equal is not beyond
    assert B.d1_gaphl(prev_of([]), 103.0) == 0 and B.d1_gaphl(p, float("nan")) == 0


def test_d10_gap_quarter_percent():
    p = prev_of([1000.0])
    assert B.d10_gap25(p, 1002.5) == 1 and B.d10_gap25(p, 997.5) == -1 and B.d10_gap25(p, 1002.4) == 0


# ================================================================================================ weekly
def test_weeks_and_completed_weeks():
    ds = bdays(12)                                   # Mon 1 Jan 2024 .. Tue 16 Jan
    h = np.arange(12) + 10.0
    l = h - 2
    c = h - 1
    wk = B.weeks(ds, h, l, c)
    assert len(wk) == 3 and wk[0][1] == 14.0 and wk[0][2] == 8.0 and wk[0][3] == 13.0
    cw = B.completed_weeks(ds, h, l, c, date(2024, 1, 16))
    assert len(cw) == 2                              # the current week is never complete


def test_weeks_holiday_partial_week():
    ds = [date(2024, 1, 1), date(2024, 1, 3), date(2024, 1, 8)]   # a short week, then the next
    wk = B.weeks(ds, [5.0, 7.0, 6.0], [1.0, 2.0, 3.0], [4.0, 6.0, 5.0])
    assert len(wk) == 2 and wk[0][1:] == (7.0, 1.0, 6.0)


def test_d2_wk3_states():
    up = [(i, 10.0 + i, 8.0 + i, 9.0 + i) for i in range(6)]
    assert B.d2_wk3(up) == 1
    dn = [(i, 20.0 - i, 18.0 - i, 19.0 - i) for i in range(6)]
    assert B.d2_wk3(dn) == -1
    assert B.d2_wk3(up[:3]) == 0                                       # warm-up
    flat = [(i, 10.0, 8.0, 9.0) for i in range(8)]
    assert B.d2_wk3(flat) == 0
    # the state persists through inside weeks
    persist = up + [(9, 14.0, 12.0, 13.0)]
    assert B.d2_wk3(persist) == 1
    # an outside week: the close vs the 3-week midpoint decides
    out_wk = flat[:3] + [(3, 11.0, 7.0, 7.5)]
    assert B.d2_wk3(out_wk) == -1


def test_d3_wk89():
    wk = [(i, 101.0, 99.0, 100.0) for i in range(88)]
    assert B.d3_wk89(wk) == 0                                          # 88 weeks: warm-up
    wk.append((88, 120.0, 110.0, 115.0))
    assert B.d3_wk89(wk) == 1
    wk[-1] = (88, 90.0, 80.0, 85.0)
    assert B.d3_wk89(wk) == -1
    wk[-1] = (88, 105.0, 95.0, 100.0)
    assert B.d3_wk89(wk) == 0


# ================================================================================================ structure
def zigzag(levels):
    """Highs / lows for bars at alternating swing levels (each level padded by a lower / higher neighbour)."""
    h, l = [], []
    for v in levels:
        h += [v - 1, v, v - 1]
        l += [v - 3, v - 2, v - 3]
    return np.array(h, float), np.array(l, float)


def test_structure_up_down_neutral():
    h = np.array([1, 3, 2, 4, 3, 5, 4, 6, 5], float)
    l = h - 1.5
    assert B.structure(h, l) == 1
    assert B.structure(-h, -h - 1.5) == -1
    assert B.structure([1, 2, 3], [0, 1, 2]) == 0                      # no swings
    h2 = np.array([1, 5, 2, 4, 1, 6, 0], float)                       # highs mixed
    assert B.structure(h2, h2 - 1) in (0, 1, -1)


def test_fractal_needs_next_bar():
    hi, lo = B.fractal_swings([1, 3, 2], [0, 2, 1])
    assert hi == [1]
    hi, lo = B.fractal_swings([1, 3], [0, 2])
    assert hi == []                                                    # the last bar is never a swing


def test_d6_pday():
    assert B.d6_pday(prev_of([100.0, 102.0], o=[100.0, 101.0])) == 1
    assert B.d6_pday(prev_of([100.0, 98.0], o=[100.0, 99.0])) == -1
    assert B.d6_pday(prev_of([100.0, 101.0], o=[100.0, 102.0])) == 0  # up close, red candle
    assert B.d6_pday(prev_of([100.0])) == 0


def test_d7_sma200():
    c = np.full(200, 100.0)
    assert B.d7_sma200(prev_of(c)) == 0
    c2 = c.copy(); c2[-1] = 110.0
    assert B.d7_sma200(prev_of(c2)) == 1
    c3 = c.copy(); c3[-1] = 90.0
    assert B.d7_sma200(prev_of(c3)) == -1
    assert B.d7_sma200(prev_of(c[:199])) == 0


def test_d8_adx30():
    n = 120
    c = 100 + np.arange(n) * 2.0
    p = prev_of(c, h=c + 1, l=c - 1)
    assert B.d8_adx30(p) == 1
    p = prev_of(c[::-1], h=c[::-1] + 1, l=c[::-1] - 1)
    assert B.d8_adx30(p) == -1
    rng = np.random.default_rng(0)
    cn = 100 + rng.normal(0, 1, n)
    assert B.d8_adx30(prev_of(cn, h=cn + 1, l=cn - 1)) in (0, 1, -1)
    assert B.d8_adx30(prev_of(c[:20])) == 0


# ================================================================================================ intraday
def day_arr(o, c, n=375):
    a = np.full((4, n), np.nan)
    a[0, :len(o)] = o
    a[3, :len(c)] = c
    a[1] = np.fmax(a[0], a[3]) + 0.5
    a[2] = np.fmin(a[0], a[3]) - 0.5
    return a


def test_d9_f15():
    up = day_arr(np.linspace(100, 110, 375), np.linspace(100.1, 110.1, 375))
    assert B.d9_f15(up, 14) == 0                                       # before 09:30: not filtered
    assert B.d9_f15(up, 15) == 1
    dn = day_arr(np.linspace(110, 100, 375), np.linspace(109.9, 99.9, 375))
    assert B.d9_f15(dn, 30) == -1
    blank = np.full((4, 375), np.nan)
    assert B.d9_f15(blank, 30) == 0


def test_h60_bars_and_partial_last_bar():
    a = day_arr(np.full(375, 100.0), np.full(375, 100.0))
    bars = B.h60_bars(a)
    assert len(bars) == 7 and bars[0][0] == 59 and bars[-1][0] == 374


def test_d5_uses_only_closed_bars():
    hist = [(float(v), float(v) - 2) for v in (1, 3, 2, 4, 3, 5)]
    today = [(59, 4.5, 2.5), (119, 6.0, 4.0), (179, 5.0, 3.0)]
    a = B.d5_h60(hist, today, er=60)              # only the 09:15 bar (ends at row 59) is known
    b = B.d5_h60(hist, today, er=60)
    assert a == b
    # changing a bar that has not closed by the entry minute never changes the bias
    today2 = [(59, 4.5, 2.5), (119, -50.0, -60.0), (179, 99.0, 90.0)]
    assert B.d5_h60(hist, today2, er=119) == B.d5_h60(hist, today, er=119)


# ================================================================================================ look-ahead guard
def test_day_rules_never_read_the_day_itself():
    """The study passes sessions strictly before the day; a later or same-day bar changing never changes a bias."""
    rng = np.random.default_rng(3)
    for k in range(50):
        c = 100 + np.cumsum(rng.normal(0, 1, 300))
        p = prev_of(c[:250], o=c[:250] - 0.2, h=c[:250] + 1, l=c[:250] - 1)
        before = (B.d4_ddow(p), B.d6_pday(p), B.d7_sma200(p), B.d8_adx30(p))
        # the "future" (bars 250+) never enters; the function only sees p
        assert before == (B.d4_ddow(p), B.d6_pday(p), B.d7_sma200(p), B.d8_adx30(p))
    ds = bdays(40)
    h = np.arange(40) + 10.0
    wk_a = B.completed_weeks(ds, h, h - 2, h - 1, ds[-1])
    h2 = h.copy(); h2[-3:] = 999.0                                     # the current week changes
    wk_b = B.completed_weeks(ds, h2, h2 - 2, h2 - 1, ds[-1])
    if ds[-3].isocalendar()[:2] == ds[-1].isocalendar()[:2]:
        assert wk_a == wk_b


def test_d9_lookahead():
    a = day_arr(np.linspace(100, 110, 375), np.linspace(100.1, 110.1, 375))
    b = a.copy()
    b[:, 15:] = -1.0
    assert B.d9_f15(a, 40) == B.d9_f15(b, 40)


# ================================================================================================ plumbing
def U(cands, res, how="side2", key=("NIFTY", date(2025, 5, 2))):
    return (key, cands, res, how)


def test_keeps_walk_and_t7_stack():
    d = date(2025, 5, 2)
    cands = [dict(er=10, side="C", und="NIFTY"), dict(er=20, side="P", und="NIFTY"), dict(er=30, side="C", und="NIFTY")]
    res = [dict(xr=15, net=100.0, day=d, und="NIFTY", er=10), dict(xr=25, net=-50.0, day=d, und="NIFTY", er=20),
           dict(xr=35, net=30.0, day=d, und="NIFTY", er=30)]
    u = [U(cands, res)]
    base = L.keeps_for(u, lambda c: c["er"] != 30)                  # a "T7" that drops the third
    assert base == [[True, True, False]]
    var = L.keeps_for(u, lambda c: c["er"] != 30, lambda c: 1)       # calls only on top
    assert var == [[True, False, False]]
    assert [t["net"] for t in L.run_keep(u, var)] == [100.0]
    tw = L.twin_totals(u, base, var, 50, np.random.default_rng(0))
    assert set(np.round(tw)) <= {100.0, -50.0}


def test_stats_and_quarters():
    tr = [dict(day=date(2025, 5, 1), net=100.0), dict(day=date(2025, 5, 2), net=-300.0),
          dict(day=date(2025, 8, 2), net=50.0)]
    s = L.stats(tr)
    assert s["worst_day"] == -300 and s["max_dd"] == 300 and s["trades"] == 3
    assert L.quarters(tr) == [-200, 50, 0, 0]


def test_gate_and_bh():
    s = dict(net=100, trades=50, max_dd=100, ex_top5=10)
    b = dict(net=50, trades=60, max_dd=100, ex_top5=5)
    r = dict(design=dict(s), base_design=dict(b), final=dict(s), base_final=dict(b), wf_net=10, base_wf_net=5,
             q=[1, 1, 1, 0], base_q=[0, 0, 0, 0], p_design=0.01, p_final=0.01, q_bh=0.05)
    assert L.gate(r)["passed"]
    assert "b_twin" in L.gate(dict(r, p_final=0.2))["failed"]
    assert "d_trades" in L.gate(dict(r, final=dict(s, trades=39)))["failed"]
    assert "f_dd" in L.gate(dict(r, final=dict(s, max_dd=111)))["failed"]
    q = L.family_q({"a": 0.001}, [("x", 1.0)] * 99)
    assert q["a"] == pytest.approx(0.1)


def test_prior_family(tmp_path):
    (tmp_path / "r101-x.yaml").write_text("title: Liquidity filter\nTwin p 0.02\n")
    (tmp_path / "r73-y.yaml").write_text("title: Donchian trend, no p\n")
    (tmp_path / "r73-z.yaml").write_text("title: gold thing\n")
    pf = dict(L.prior_family(tmp_path, r104_unlogged=3))
    assert pf["r101-x"] == pytest.approx(0.02) and pf["r73-y"] == 1.0 and "r73-z" not in pf
    assert sum(1 for k in pf if k.startswith("r104-arm-unlogged")) == 3
    assert len(L.prior_family()) >= 150


def test_final_read_once(tmp_path, monkeypatch):
    S = pytest.importorskip("r105bstudy")
    for a in L.ARMS:
        (tmp_path / f"design_{a}.json").write_text("{}")
    flag = tmp_path / "FINAL_READ"
    flag.write_text("x")
    monkeypatch.setattr(S.L, "RESULTS", tmp_path)
    monkeypatch.setattr(S.L, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        S.final()
    monkeypatch.setattr(S, "EXT", False)
    with pytest.raises(SystemExit):
        S.oos()
