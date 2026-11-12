"""R161 tests: every filter, C1's choice, statistics, twins, BH, gates, and the look-ahead guard (PREREG section 6).

    python -m pytest research/hunt/r161_donchian_entries -q

Synthetic 5-minute bars only (no lake, no stores).
"""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r161feat as F  # noqa: E402
import r161lib as L  # noqa: E402


# ================================================================================================ synthetic bars
def make_bars(n_days: int = 8, seed: int = 0, start: date = date(2026, 1, 5), skip=()) -> tuple:
    """n_days sessions x 75 five-minute bars of a random walk; `skip` = (day index, bar) pairs left out (gaps)."""
    rng = np.random.default_rng(seed)
    d, b, o, h, l, c = [], [], [], [], [], []
    px = 20000.0
    day = start
    for k in range(n_days):
        while day.weekday() >= 5:
            day += timedelta(days=1)
        for bb in range(75):
            op = px
            cl = op + rng.normal(0, 8)
            hi = max(op, cl) + abs(rng.normal(0, 4))
            lo = min(op, cl) - abs(rng.normal(0, 4))
            px = cl
            if (k, bb) in skip:
                continue
            d.append(day); b.append(bb); o.append(op); h.append(hi); l.append(lo); c.append(cl)
        day += timedelta(days=1)
    return (np.array(d, dtype=object), np.array(b), np.array(o), np.array(h), np.array(l), np.array(c))


def ctx_of(bars) -> F.IndexCtx:
    return F.IndexCtx(*bars)


# ================================================================================================ indicators
def test_atr_matches_wilder_and_nan_before():
    h = np.array([10, 11, 12, 13.0]); l = np.array([9, 9, 10, 12.0]); c = np.array([9.5, 10.5, 11.5, 12.5])
    a = F.atr(h, l, c, 2)
    assert math.isnan(a[0]) and a[1] == pytest.approx((1 + 2) / 2)
    assert a[2] == pytest.approx((1.5 * 1 + 2) / 2)
    assert np.isnan(F.atr(h[:1], l[:1], c[:1], 2)).all()           # shorter than n: all NaN


def test_channel_uses_only_bars_before():
    h = np.arange(30, dtype=float); l = h - 1
    chi, clo = F.channel(h, l, 20)
    assert np.isnan(chi[19]) and chi[20] == 19 and clo[20] == -1 and chi[25] == 24


def test_prior_median_needs_all_finite():
    x = np.arange(10, dtype=float)
    m = F.prior_median(x, 4)
    assert np.isnan(m[3]) and m[4] == 1.5
    x[5] = np.nan
    m = F.prior_median(x, 4)
    assert np.isnan(m[6]) and np.isnan(m[9]) and m[4] == 1.5


def test_htf_groups_and_last_closed():
    bars = make_bars(2)
    d, b, o, h, l, c = bars
    keys, H, Lw, C = F.htf(d, b, h, l, c, 3)
    assert len(keys) == 2 * 25 and C[0] == c[2] and H[0] == h[:3].max()
    day1 = d[75]
    # at 5-min bar 2 of day 1 the first 15-min bar (bars 0..2) is closed; at bar 1 it is not -> the previous day's last
    assert keys[F.htf_last_closed(keys, day1, 2, 3)] == (day1.toordinal(), 0)
    assert keys[F.htf_last_closed(keys, day1, 1, 3)] == (d[0].toordinal(), 24)
    assert F.htf_last_closed(keys, d[0], 0, 3) == -1                       # nothing closed yet
    k60, *_ = F.htf(d, b, h, l, c, 12)
    assert len(k60) == 2 * 7                                               # 6 full hours + the short 15:15 bar


def test_htf_side_needs_n_bars():
    H = np.arange(25, dtype=float) + 1; Lw = H - 2; C = H.copy()
    assert F.htf_side(H, Lw, C, 18) is None
    assert F.htf_side(H, Lw, C, 19) == 1
    C2 = C.copy(); C2[24] = Lw[5]
    assert F.htf_side(H, Lw, C2, 24) == -1


# ================================================================================================ decisions
def _find_break(cx: F.IndexCtx, side: str, min_b: int = 0):
    """First bar (after the warm-up) that closes beyond its channel: returns (day, er)."""
    for i in range(150, len(cx.c)):
        if cx.b[i] < min_b or cx.b[i] > 69:
            continue
        if (side == "C" and cx.c[i] > cx.chi[i]) or (side == "P" and cx.c[i] < cx.clo[i]):
            return cx.d[i], 5 * (int(cx.b[i]) + 1)
    raise AssertionError("no break")


def test_decide_keys_and_types():
    cx = ctx_of(make_bars(30, seed=1))
    day, er = _find_break(cx, "C")
    dec = F.decide(cx, day, er, "C", {"A": cx}, vixchg=-0.01)
    assert set(dec) == set(F.COMBO_POOL)
    assert all(v in (True, False, None) for v in dec.values())
    assert dec["E10"] is True                                              # call, VIX down
    assert F.decide(cx, day, er, "C", None, vixchg=0.01)["E10"] is False
    assert F.decide(cx, day, er, "C", None, vixchg=math.nan)["E10"] is None   # missing VIX -> unknown -> take
    assert F.decide(cx, day, er, "C", None)["E11"] is None                    # no breadth data -> unknown


def test_strong_close_and_flat_bar():
    bars = list(make_bars(3, seed=2))
    i = 100
    bars[1] = bars[1].copy()
    for arr, v in ((bars[2], 100.0), (bars[3], 110.0), (bars[4], 100.0), (bars[5], 109.0)):
        arr[i] = v
    cx = ctx_of(tuple(bars))
    day, er = cx.d[i], 5 * (int(cx.b[i]) + 1)
    assert F.decide(cx, day, er, "C")["E1"] is True                        # close at 90% of the bar
    assert F.decide(cx, day, er, "P")["E1"] is False
    for arr in (bars[3], bars[4], bars[5]):
        arr[i] = 100.0                                                     # a flat bar
    cx = ctx_of(tuple(bars))
    assert F.decide(cx, day, er, "C")["E1"] is False


def test_lunch_window_edges():
    cx = ctx_of(make_bars(3))
    day = cx.d[0]
    assert F.decide(cx, day, F.row(11, 29), "C")["E12"] is True
    assert F.decide(cx, day, F.row(11, 30), "C")["E12"] is False
    assert F.decide(cx, day, F.row(13, 29), "C")["E12"] is False
    assert F.decide(cx, day, F.row(13, 30), "C")["E12"] is True


def test_missing_signal_bar_is_unknown_except_time():
    cx = ctx_of(make_bars(3, skip={(1, 10)}))
    day = cx.d[75]
    dec = F.decide(cx, day, 5 * 11, "C")
    assert dec["E12"] is True and all(dec[k] is None for k in F.COMBO_POOL if k != "E12")


def test_first_hour_rule_untouched_before_1015_and_needs_beyond():
    cx = ctx_of(make_bars(4, seed=3))
    day = cx.d[150]
    assert F.decide(cx, day, F.row(10, 10), "C")["E13"] is True
    i = cx.pos[(day, 20)]
    er = 5 * 21
    fh_hi, fh_lo = cx.fh[day]
    assert F.decide(cx, day, er, "C")["E13"] == bool(cx.c[i] > fh_hi)
    assert F.decide(cx, day, er, "P")["E13"] == bool(cx.c[i] < fh_lo)


def test_prior_day_range_and_first_day_unknown():
    cx = ctx_of(make_bars(3, seed=4))
    assert F.decide(cx, cx.d[0], 50, "C")["E8"] is None                    # no previous session
    day = cx.d[75]
    i = cx.pos[(day, 9)]
    assert F.decide(cx, day, 50, "C")["E8"] == bool(cx.c[i] > cx.h[:75].max())


def test_squeeze_and_atrhot_need_history():
    cx = ctx_of(make_bars(2, seed=5))
    dec = F.decide(cx, cx.d[0], 50, "C")
    assert dec["E5"] is None and dec["E9"] is None and dec["E3"] is None   # first day: no ATR / median yet
    cx = ctx_of(make_bars(15, seed=5))
    day = cx.d[14 * 75]
    dec = F.decide(cx, day, 100, "C")
    assert dec["E5"] in (True, False) and dec["E9"] in (True, False)
    assert cx.atr_usual(day, 19) == pytest.approx(np.median([cx.atr[cx.pos[(dd, 19)]] for dd in cx.days[:14]][-20:]))


def test_breadth_counts_indices_on_the_trade_side():
    a = ctx_of(make_bars(3, seed=6)); b = ctx_of(make_bars(3, seed=7)); c = ctx_of(make_bars(3, seed=8))
    day = a.d[75]
    er = 100
    n_up = sum(1 for x in (a, b, c) if x.c[x.idx(day, er)] > x.dopen[day])
    dec = F.decide(a, day, er, "C", {"a": a, "b": b, "c": c})
    assert dec["E11"] == (n_up >= F.BREADTH_MIN)
    assert F.decide(a, day, er, "P", {"a": a, "b": b, "c": c})["E11"] == ((3 - n_up) >= F.BREADTH_MIN)


def test_hold1_moves_entry_or_drops():
    cx = ctx_of(make_bars(30, seed=9))
    day, er = _find_break(cx, "C")
    i = cx.idx(day, er)
    r = F.hold1(cx, day, er, "C")
    assert r == (er + 5 if cx.c[i + 1] > cx.chi[i] else None)
    assert F.hold1(cx, cx.d[-1], 5 * 75, "C") is None                      # the last bar has no next bar
    assert F.hold1(cx, day, 5 * 72, "C") is None or 5 * 72 + 5 <= F.LAST_ROW   # past 15:10 is dropped
    gap = ctx_of(make_bars(3, seed=9, skip={(1, 41)}))
    assert F.hold1(gap, gap.d[75], 5 * 41, "C") is None                     # the next bar is missing


def test_keep_and_unknown_takes():
    dec = {"E1": True, "E3": False, "E4": None}
    assert F.keep(dec, "E1") and not F.keep(dec, "E3") and F.keep(dec, "E4")
    assert not F.keep(dec, ("E1", "E3")) and F.keep(dec, ("E1", "E4")) and F.keep(dec, ())


# ================================================================================================ look-ahead guard
@pytest.mark.parametrize("seed", [11, 12, 13])
def test_look_ahead_guard(seed):
    """Scrambling every bar after the last one a decision reads (signal bar; E2: the bar after it) and every VIX value
    from the entry row on changes no decision."""
    bars = make_bars(25, seed=seed)
    other = make_bars(25, seed=seed + 100)
    cx, ox = ctx_of(bars), ctx_of(other)
    rng = np.random.default_rng(seed)
    checked = 0
    for i in range(160, len(cx.c) - 2, 37):
        day, er = cx.d[i], 5 * (int(cx.b[i]) + 1)
        for side in ("C", "P"):
            want = F.decide(cx, day, er, side, {"a": cx, "o": ox}, vixchg=-0.002)
            want2 = F.hold1(cx, day, er, side)
            for last in (i, i + 1):
                sb = [x.copy() for x in bars]
                so = [x.copy() for x in other]
                for arr in sb[2:] + so[2:]:
                    arr[last + 1:] = arr[last + 1:] * rng.uniform(0.9, 1.1, len(arr) - last - 1)
                cs, os_ = ctx_of(tuple(sb)), ctx_of(tuple(so))
                got = F.decide(cs, day, er, side, {"a": cs, "o": os_}, vixchg=-0.002)
                if last == i:
                    assert got == want, (i, side)
                else:
                    assert F.hold1(cs, day, er, side) == want2 and got == want
                checked += 1
    assert checked > 50


# ================================================================================================ lib
def _t(day, net, er=10):
    return dict(day=day, net=float(net), und="NIFTY", side="C", er_sig=er)


def test_stats_numbers_and_empty():
    d1, d2 = date(2026, 1, 5), date(2026, 1, 6)
    tr = [_t(d1, 100), _t(d1, -50), _t(d2, -200), _t(d2, 300), _t(d2, 10), _t(d2, 20), _t(d2, -10)]
    s = L.stats(tr)
    assert s["trades"] == 7 and s["win"] == round(4 / 7, 4) and s["net"] == 170
    assert s["avg_win"] == round(430 / 4) and s["avg_loss"] == round(-260 / 3)
    assert s["be_win"] == round((260 / 3) / (430 / 4 + 260 / 3), 4)
    assert s["worst_day"] == 50 and s["max_dd"] == 0
    assert s["net_ex5"] == -200 - 50                                        # the 5 best removed
    e = L.stats([])
    assert e["trades"] == 0 and e["win"] is None and e["net"] == 0
    assert L.win_net([]) == (0.0, 0.0)


def test_drawdown_daily():
    days = [date(2026, 1, 5) + timedelta(days=k) for k in range(4)]
    s = L.stats([_t(days[0], 100), _t(days[1], -300), _t(days[2], -50), _t(days[3], 500)])
    assert s["max_dd"] == 350 and s["worst_day"] == -300


def test_choose_combo():
    single = {"E1": (0.55, 100), "E3": (0.60, 50), "E4": (0.58, 120), "E5": (0.40, 500), "E6": (0.56, 110)}
    assert L.choose_combo(single, (0.50, 100), ("E1", "E3", "E4", "E5", "E6")) == ("E4", "E6")
    assert L.choose_combo(single, (0.50, 115), ("E1", "E3", "E4", "E5", "E6")) == ("E4",)
    assert L.choose_combo(single, (0.70, 0), ("E1", "E3")) == ()
    tie = {"E1": (0.6, 10), "E3": (0.6, 10)}
    assert L.choose_combo(tie, (0.5, 0), ("E1", "E3")) == ("E1", "E3")


def test_twin_p():
    r = L.twin_p(5.0, [1, 2, 3, 6])
    assert r["p"] == pytest.approx(2 / 5) and r["hits"] == 1
    r = L.twin_p(100.0, np.random.default_rng(0).normal(0, 1, 500))
    assert r["hits"] == 0 and r["p"] < 1e-10                                # normal tail when no twin reaches it
    assert L.twin_p(1.0, [1.0, 1.0])["p"] == 1.0


def test_random_drop_count():
    us = [(None, [1, 2, 3], None, None), (None, [4], None, None), (None, [5, 6], None, None)]
    idx = L.random_drop(us, 4, np.random.default_rng(1))
    assert sum(len(i) for i in idx) == 2
    assert sum(len(i) for i in L.random_drop(us, 0, np.random.default_rng(1))) == 6
    assert sum(len(i) for i in L.random_drop(us, 99, np.random.default_rng(1))) == 0


def test_bh_counts_prior():
    q = L.bh([0.001, 0.5], m_total=100)
    assert q[0] == pytest.approx(0.1) and q[1] == 1.0
    assert L.bh([0.01, 0.02]).tolist() == pytest.approx([0.02, 0.02])
    assert len(L.bh([])) == 0


def _s(trades, win, net):
    return dict(trades=trades, win=win, net=net)


def test_gates():
    ref = dict(design=_s(100, 0.45, 1000), wf=_s(60, 0.45, 500), final=_s(50, 0.46, 400))
    good = dict(design=_s(80, 0.52, 1200), wf=_s(50, 0.50, 600), final=_s(45, 0.51, 450))
    assert L.gates(good, ref, 0.001, 0.05)["passed"]
    g = L.gates(dict(good, wf=_s(50, 0.49, 600)), ref, 0.001, 0.05)
    assert not g["passed"] and "wf: win not up 5 points" in g["fails"]
    g = L.gates(dict(good, final=_s(39, 0.6, 450)), ref, 0.001, 0.05)
    assert "< 40 final trades" in g["fails"]
    g = L.gates(dict(good, final=_s(45, 0.6, 300)), ref, 0.001, 0.2)
    assert "final: net lower" in g["fails"] and any("BH q" in x for x in g["fails"])
    g = L.gates(dict(good, design=_s(80, 0.6, -1)), ref, 0.001, 0.05)
    assert "design: net not > 0" in g["fails"]
    g = L.gates(dict(wf=good["wf"], final=good["final"]), dict(wf=ref["wf"], final=ref["final"]), 0.001, 0.05,
                ("wf", "final"))
    assert g["passed"]
