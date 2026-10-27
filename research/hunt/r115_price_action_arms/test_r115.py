"""R115 tests (synthetic, no lake): the bar features, the failed-break exit row, statistics, twins' p, BH, the gates,
the count of earlier tests, and the look-ahead guards (no feature moves when any bar after the signal bar changes; the
exit never sells before the next bar has closed).

    python -m pytest research/hunt/r115_price_action_arms/test_r115.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r115cfg as K  # noqa: E402
import r115lib as L  # noqa: E402


# ------------------------------------------------------------------------------------------------ bar quality
def test_quality_strong_call_and_put():
    assert L.bar_quality(100, 110, 99, 109, "C")                 # body 9 / 11, close top 9%
    assert L.bar_quality(110, 111, 100, 101, "P")
    assert not L.bar_quality(100, 110, 99, 109, "P")              # wrong colour for a put


def test_quality_weak_bars():
    assert not L.bar_quality(100, 110, 95, 104, "C")              # body 4 / 15 < 60%
    assert not L.bar_quality(100, 112, 99, 108, "C")              # body 8 / 13 = 62%, close at 69% (< 75%)
    assert not L.bar_quality(105, 110, 100, 101, "C")             # red bar for a call


def test_quality_edges_never_confirm():
    assert not L.bar_quality(100, 100, 100, 100, "C")             # zero range
    assert not L.bar_quality(np.nan, 110, 99, 109, "C")           # missing value
    # body 6/10 = 60% passes the body test, but the close sits at 60% of the range (< 75%)
    assert not L.bar_quality(100, 110, 100, 106, "C")


def test_quality_boundary_close_zone():
    # body 7.5 / 10 = 75%, close exactly at 75% of the range -> passes (>=)
    assert L.bar_quality(100, 110, 100, 107.5, "C")
    assert not L.bar_quality(100, 110, 100, 107.4, "C")


# ------------------------------------------------------------------------------------------------ outside, fresh, compression
def test_outside():
    h = np.array([10.0, 11, 10.5, 12])
    l = np.array([9.0, 8, 9, 7])
    assert L.is_outside(h, l, 0) is None
    assert L.is_outside(h, l, 1) is True
    assert L.is_outside(h, l, 2) is False
    assert L.is_outside(h, l, 3) is True


def test_fresh():
    h = np.array([100.0, 106, 104, 108])
    l = np.array([95.0, 99, 97, 101])
    # day starts at bar 0; PDH 105, PDL 96
    assert L.fresh("C", h, l, 0, 0, 105, 96)                      # the first bar is always fresh
    assert L.fresh("C", h, l, 0, 1, 105, 96)                      # only bar 0 before it (100 <= 105)
    assert not L.fresh("C", h, l, 0, 2, 105, 96)                  # bar 1 already traded 106 > PDH
    assert not L.fresh("P", h, l, 0, 1, 105, 96)                  # bar 0's low 95 < PDL already
    assert not L.fresh("C", h, l, 0, 1, np.nan, 96)               # unknown previous session never confirms


def test_compression():
    hi = np.array([110.0, 112, 111, 109, 120])
    lo = np.array([100.0, 101, 103, 105, 100])
    # session 3 (range 4) is the smallest of sessions 0-3 -> NR4 -> session 4 qualifies
    assert L.compression(4, hi, lo)
    assert not L.compression(2, hi, lo)                           # too few sessions before
    hi2 = np.array([110.0, 115, 116, 114, 130])
    lo2 = np.array([100.0, 100, 99, 101, 90])
    assert L.compression(4, hi2, lo2)                             # inside day (114 <= 116, 101 >= 99)
    hi3 = np.array([110.0, 115, 116, 118, 130])
    lo3 = np.array([108.0, 100, 99, 101, 90])
    assert not L.compression(4, hi3, lo3)                         # neither


# ------------------------------------------------------------------------------------------------ failed-break exit
def _series(n=30):
    b = np.arange(n) % 75
    h = np.full(n, 100.0)
    l = np.full(n, 90.0)
    c = np.full(n, 95.0)
    return b, h, l, c


def test_channel_level_and_failed_break():
    b, h, l, c = _series(30)
    i = 22
    assert L.channel_level(h, l, i, "C") == 100.0
    assert L.channel_level(h, l, i, "P") == 90.0
    assert np.isnan(L.channel_level(h, l, 5, "C"))
    c[i] = 101                                                    # the break
    c[i + 1] = 100.5                                              # still outside
    c[i + 2] = 99.0                                               # back inside -> sold at that bar's end
    assert L.failed_break_row("C", i, 100.0, b, c, 30) == 5 * (int(b[i + 2]) + 1)
    assert L.failed_break_row("C", i, 100.0, b, c, i + 2) == -1   # the back-inside bar is the next session's
    assert L.failed_break_row("P", i, 90.0, b, c, 30) == 5 * (int(b[i + 1]) + 1)   # 100.5 > 90: back inside at once
    assert L.failed_break_row("C", i, np.nan, b, c, 30) == -1


def test_failed_break_past_session_end():
    b = np.array([70, 71, 72, 73, 74])
    c = np.array([101.0, 101, 101, 101, 99])
    assert L.failed_break_row("C", 0, 100.0, b, c, 5) == -1       # bar 74 ends at row 375: no exit row


def test_minute_bar():
    a = np.full((4, 375), np.nan)
    assert np.isnan(L.minute_bar(a, 0, 5)[0])
    a[:, 1:4] = [[10, 11, 12], [12, 13, 14], [9, 10, 11], [11, 12, 13]]
    assert L.minute_bar(a, 0, 5) == (10.0, 14.0, 9.0, 13.0)
    assert L.minute_bar(a, -3, 2) == (10.0, 12.0, 9.0, 11.0)      # a window starting before 09:15 is clipped


# ------------------------------------------------------------------------------------------------ the look-ahead guard
def _synthetic_bars(seed=1, ndays=8):
    rng = np.random.default_rng(seed)
    d, b = [], []
    day0 = date(2025, 1, 6)
    for k in range(ndays):
        for x in range(75):
            d.append(day0 + timedelta(days=k))
            b.append(x)
    n = len(d)
    c = 20000 + np.cumsum(rng.normal(0, 8, n))
    o = c + rng.normal(0, 4, n)
    h = np.maximum(o, c) + rng.uniform(0, 6, n)
    l = np.minimum(o, c) - rng.uniform(0, 6, n)
    return np.array(d, dtype=object), np.array(b), o, h, l, c


def _install(monkeypatch, S, arrays):
    d, b, o, h, l, c = arrays

    class FR:
        @staticmethod
        def bars5(und):
            return d, b, o, h, l, c

    S._BARS.clear()
    monkeypatch.setitem(S._M, "fam_r44", FR)
    monkeypatch.setitem(S._M, "r51data", None)


@pytest.mark.parametrize("rule", ["quality", "no_outside", "fresh", "compression"])
def test_lookahead_filters(monkeypatch, rule):
    import r115study as S
    arrays = _synthetic_bars()
    d = arrays[0]
    for i_sig in (5 * 75 + 0, 5 * 75 + 1, 6 * 75 + 30, 7 * 75 + 60):
        day, bar = d[i_sig], int(arrays[1][i_sig])
        cands = [dict(er=5 * (bar + 1), side=s) for s in ("C", "P")]
        _install(monkeypatch, S, arrays)
        before = [S.keep(rule, arm, (None, day, None), c) for arm in ("R72", "R51") for c in cands]
        for x in range(3):                                       # change every bar after the signal bar
            moved = [a.copy() if k >= 2 else a for k, a in enumerate(arrays)]
            rng = np.random.default_rng(x)
            for k in range(2, 6):
                moved[k][i_sig + 1:] += rng.normal(0, 50, len(d) - i_sig - 1)
            und = None
            _install(monkeypatch, S, moved)
            after = [S.keep(rule, arm, (und, day, None), c) for arm in ("R72", "R51") for c in cands]
            assert before == after, (rule, i_sig)
    S._BARS.clear()


def test_lookahead_exit_row(monkeypatch):
    """The failed-break row only depends on bars after the fill, and is never before the next bar's close."""
    import r115study as S
    arrays = _synthetic_bars(seed=3)
    d, b = arrays[0], arrays[1]
    _install(monkeypatch, S, arrays)
    for i_sig in range(5 * 75 + 20, 5 * 75 + 70, 3):
        c = dict(er=5 * (int(b[i_sig]) + 1), side="C" if i_sig % 2 else "P")
        fx = S.failed_break_fx(None, d[i_sig], c)
        assert fx == -1 or fx >= 5 * (int(b[i_sig]) + 2)
    # bars before the signal bar's channel window and the next sessions do not move the row
    i_sig = 5 * 75 + 40
    c = dict(er=5 * (int(b[i_sig]) + 1), side="C")
    fx0 = S.failed_break_fx(None, d[i_sig], c)
    moved = [a.copy() if k >= 2 else a for k, a in enumerate(arrays)]
    for k in range(2, 6):
        moved[k][6 * 75:] += 500.0                               # the next sessions only
    _install(monkeypatch, S, moved)
    assert S.failed_break_fx(None, d[i_sig], c) == fx0
    S._BARS.clear()


def test_quality_ex0920_keeps_the_opening_break(monkeypatch):
    import r115study as S
    arrays = _synthetic_bars()
    _install(monkeypatch, S, arrays)
    assert S.keep("quality_ex0920", "R72", (None, arrays[0][5 * 75], None), dict(er=5, side="C"))
    S._BARS.clear()


# ------------------------------------------------------------------------------------------------ statistics, p, BH, gates
def _t(day, net, er=5, und="NIFTY", side="C", xr=30, er_sig=None, book=None):
    return dict(day=day, net=net, er=er, und=und, side=side, xr=xr, er_sig=er if er_sig is None else er_sig, book=book)


def test_stats():
    d1, d2, d3 = date(2025, 1, 1), date(2025, 1, 2), date(2025, 2, 3)
    s = L.stats([_t(d1, 1000), _t(d1, -500), _t(d2, -15000), _t(d3, 2000)])
    assert s["trades"] == 4 and s["net"] == -12500 and s["win"] == 0.5
    assert s["worst_day"] == -15000 and s["max_dd"] == 15000 and s["bad_months"] == 1
    assert s["avg_win"] == 1500 and s["avg_loss"] == -7750
    assert L.stats([])["trades"] == 0


def test_max_dd_from_zero():
    s = L.stats([_t(date(2025, 1, 1), -3000)])
    assert s["max_dd"] == 3000


def test_open_slice_and_changed():
    d = date(2025, 1, 1)
    a = [_t(d, 100, er=5), _t(d, 200, er=20)]
    assert len(L.open_slice(a)) == 1
    b = [_t(d, 100, er=5), _t(d, 250, er=20)]
    assert L.changed(a, b) == 1
    assert L.changed(a, a[:1]) == 1
    assert L.changed([], []) == 0


def test_twin_p():
    tw = np.array([0.0, 1, 2, 3, 4] * 100)
    r = L.twin_p(3.5, tw)
    assert r["hits"] == 100 and r["p"] == r["p_mc"] == pytest.approx(101 / 501)
    r2 = L.twin_p(100.0, tw)
    assert r2["hits"] == 0 and r2["p"] < r2["p_mc"] and r2["p"] == r2["p_norm"]
    r3 = L.twin_p(5.0, np.zeros(10))
    assert r3["p"] == 0.0 and r3["p_mc"] == pytest.approx(1 / 11)


def test_bh():
    q = L.bh([0.01, 0.04, 0.5])
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.06) and q[2] == pytest.approx(0.5)
    q2 = L.bh([1e-9], K.M_ALL)
    assert q2[0] == pytest.approx(1e-9 * K.M_ALL)
    assert L.bh([0.5], 10)[0] == 1.0 or L.bh([0.5], 10)[0] == pytest.approx(1.0)


def test_exit_twin_totals():
    rng = np.random.default_rng(0)
    tot = L.exit_twin_totals(10.0, [np.array([1.0, 1.0]), np.array([])], 5, rng)
    assert np.allclose(tot, 11.0)


def _per(net, worst=-1000, dd=2000, trades=100):
    return dict(net=net, worst_day=worst, max_dd=dd, trades=trades)


def test_gates():
    base = {p: _per(1000) for p in ("design", "wf", "final")}
    good = {p: _per(2000) for p in ("design", "wf", "final")}
    assert L.gates(good, base, 0.05, 50)["passed"]
    g = L.gates(good, base, 0.2, 50)
    assert not g["passed"] and any("BH" in x for x in g["fails"])
    bad = dict(good, wf=_per(500))
    assert "wf: net not above today's" in L.gates(bad, base, 0.05, 50)["fails"]
    worse = dict(good, final=_per(2000, worst=-2000))
    assert "final: worst day worse" in L.gates(worse, base, 0.05, 50)["fails"]
    deeper = dict(good, design=_per(2000, dd=5000))
    assert "design: drawdown worse" in L.gates(deeper, base, 0.05, 50)["fails"]
    few = dict(good, final=_per(2000, trades=10))
    assert any("final trades" in x for x in L.gates(few, base, 0.05, 50)["fails"])
    assert any("differ" in x for x in L.gates(good, base, 0.05, 5)["fails"])


# ------------------------------------------------------------------------------------------------ settings
def test_family_and_counts():
    assert len(K.TESTS) == 12 and K.M_ALL == 9_300_046 and K.M_R64 == 5853
    assert set(v[0] for v in K.TESTS.values()) == set(K.ARMS)
    # the smallest p a twin test can give under the full count is far above the gate (said in the PREREG)
    assert L.bh([1 / (K.TWINS + 1)], K.M_ALL)[0] > K.GATE_Q


def test_count_prior_matches_frozen():
    pytest.importorskip("yaml")
    entries = Path(__file__).resolve().parents[2] / "experiments" / "entries"
    assert L.count_prior(entries) == K.N_PRIOR_ALL


def test_periods():
    assert K.in_period(date(2025, 4, 1), "wf") and K.in_period(date(2025, 4, 1), "design")
    assert not K.in_period(date(2026, 4, 1), "design") and K.in_period(date(2026, 4, 1), "final")
    with pytest.raises(ValueError):
        K.in_period(date(2026, 4, 1), "post")
