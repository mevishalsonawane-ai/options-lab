"""R95 tests: the indicator against hand-made and naive Pine references, the trade rules, the twins' exits, the gates,
and the look-ahead guard (indicator values, signals, divergence pivots and trades never use later bars)."""
from __future__ import annotations

from datetime import date
from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest

from research.hunt.r95_cci_signals import cci as C
from research.hunt.r95_cci_signals import cfg as K
from research.hunt.r95_cci_signals import describe as DS
from research.hunt.r95_cci_signals import overlay as OV
from research.hunt.r95_cci_signals import price as PR
from research.hunt.r95_cci_signals import rules as R
from research.hunt.r95_cci_signals import study as S

OPEN = K.NSE_OPEN


# ------------------------------------------------------------------------------------------------ helpers
def naive_cci(src, n):
    out = np.full(len(src), np.nan)
    for i in range(n - 1, len(src)):
        w = src[i - n + 1:i + 1]
        m = sum(w) / n
        md = sum(abs(x - m) for x in w) / n
        out[i] = np.nan if md == 0 else (src[i] - m) / (0.015 * md)
    return out


def naive_atr(h, l, c, n):
    tr = [h[0] - l[0]] + [max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])) for i in range(1, len(h))]
    out = [np.nan] * len(h)
    if len(h) >= n:
        out[n - 1] = sum(tr[:n]) / n
        for i in range(n, len(h)):
            out[i] = (out[i - 1] * (n - 1) + tr[i]) / n
    return np.array(out)


def walk_data(nb, seed=0, vol=1.0):
    rng = np.random.default_rng(seed)
    c = 100 + np.cumsum(rng.normal(0, vol, nb))
    o = np.r_[c[0], c[:-1]] + rng.normal(0, vol / 4, nb)
    h = np.maximum(o, c) + rng.random(nb) * vol
    l = np.minimum(o, c) - rng.random(nb) * vol
    return o, h, l, c


def minute_panel(ndays=6, seed=1, vol=2.0, skip_minutes=()):
    """A flat NSE-like panel: ndays sessions of 375 minutes from 09:15 (minus any skipped minutes of day 0)."""
    rng = np.random.default_rng(seed)
    mt, md = [], []
    for d in range(ndays):
        t = np.arange(OPEN, OPEN + 375)
        if d == 0 and skip_minutes:
            t = np.array([x for x in t if x not in skip_minutes])
        mt.append(t)
        md.append(np.full(len(t), d))
    mt = np.concatenate(mt).astype(np.int32)
    mday = np.concatenate(md).astype(np.int32)
    n = len(mt)
    c = 20000 + np.cumsum(rng.normal(0, vol, n))
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) + rng.random(n) * vol / 2
    l = np.minimum(o, c) - rng.random(n) * vol / 2
    ms = np.array([np.flatnonzero(mday == d)[0] for d in range(ndays)], np.int64)
    me = np.array([np.flatnonzero(mday == d)[-1] + 1 for d in range(ndays)], np.int64)
    P = SimpleNamespace(mt=mt, mday=mday, mo=o, mh=h, ml=l, mc=c, ms=ms, me=me, anchor=OPEN, kind="nse",
                        days=[date(2025, 1, 6 + d) for d in range(ndays)], ndays=ndays)
    sq = np.array([ms[d] + int(np.searchsorted(mt[ms[d]:me[d]], 15 * 60 + 15)) for d in range(ndays)], np.int64)
    return P, sq


def fake_inst(P, sq):
    I = SimpleNamespace(P=P, sq=sq, day_ok=np.ones(P.ndays, bool), tick=0.05, nse=True)
    I.win = lambda: (K.NSE_FIRST, K.NSE_LAST)
    cache = {}

    def ind(tf, n=K.CCI_N, level=K.LEVEL):
        if (tf, n, level) not in cache:
            cache[(tf, n, level)] = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, tf), n, level)
        return cache[(tf, n, level)]
    I.ind = ind
    return I


def M_of(P):
    return dict(mo=P.mo, mh=P.mh, ml=P.ml, mc=P.mc, mt=P.mt, mday=P.mday)


def ind_from(o, h, l, c, n=25, level=200.0, day=None):
    nb = len(c)
    B = dict(o=np.asarray(o, float), h=np.asarray(h, float), l=np.asarray(l, float), c=np.asarray(c, float),
             day=np.zeros(nb, np.int64) if day is None else day, s=np.arange(nb), e=np.arange(nb))
    return C.indicator(B, n, level)


# ------------------------------------------------------------------------------------------------ bars
def test_bars_anchor_and_sessions():
    mt = np.r_[np.arange(OPEN, OPEN + 375), np.arange(OPEN, OPEN + 375)]
    mday = np.r_[np.zeros(375, int), np.ones(375, int)]
    x = np.arange(750, dtype=float)
    B = C.bars(mday, mt, x, x + 1, x - 1, x, OPEN, 60)
    assert len(B["o"]) == 14                         # 6 full hours + the partial 15:15 bar, per session
    assert B["e"][6] - B["s"][6] == 14               # 15:15 - 15:29
    assert B["o"][7] == 375 and B["day"][7] == 1     # never across sessions
    B3 = C.bars(mday, mt, x, x + 1, x - 1, x, OPEN, 3)
    assert len(B3["o"]) == 250 and B3["c"][0] == 2 and B3["h"][0] == 3 and B3["l"][0] == -1


def test_bars_with_missing_minutes():
    mt = np.array([OPEN, OPEN + 1, OPEN + 4, OPEN + 5, OPEN + 9])     # a gap inside a 5m bar, holiday-short day
    x = np.arange(5, dtype=float)
    B = C.bars(np.zeros(5, int), mt, x, x, x, x, OPEN, 5)
    assert list(B["s"]) == [0, 3] and list(B["e"]) == [2, 4]


# ------------------------------------------------------------------------------------------------ Pine built-ins
def test_cci_matches_naive_and_identity():
    o, h, l, c = walk_data(300, 3)
    tp = (h + l + c) / 3
    for n in (20, 25, 50):
        a, b = C.cci(tp, n), naive_cci(tp, n)
        assert np.allclose(a[n - 1:], b[n - 1:]) and np.isnan(a[:n - 1]).all()
        # the price meaning: tp - SMA = 0.015 x CCI x mean deviation
        assert np.allclose((tp - C.sma(tp, n))[n - 1:], (0.015 * a * C.mean_dev(tp, n))[n - 1:])


def test_cci_flat_window_is_nan_and_short_series():
    assert np.isnan(C.cci(np.full(30, 5.0), 25)).all()
    assert np.isnan(C.cci(np.arange(10.0), 25)).all()
    assert len(C.cci(np.zeros(0), 25)) == 0


def test_cci_hand_value():
    src = np.r_[np.full(24, 100.0), 110.0]
    m = (24 * 100 + 110) / 25
    md = (24 * abs(100 - m) + abs(110 - m)) / 25
    assert C.cci(src, 25)[-1] == pytest.approx((110 - m) / (0.015 * md))
    assert C.cci(src, 25)[-1] > 200


def test_cci_bounds_and_steady_trend():
    """Facts quoted in the write-up: |CCI(n)| <= n / 0.03; a steady trend sits near +128 (n 25), never at +200."""
    for n, steady in ((20, 126.7), (25, 128.2), (50, 130.7)):
        jump = np.r_[np.full(n - 1, 100.0), 110.0]
        assert C.cci(jump, n)[-1] == pytest.approx(n / 0.03)
        assert C.cci(np.arange(200.0), n)[-1] == pytest.approx(steady, abs=0.05)
    x = np.r_[np.full(40, 100.0), 100 + np.cumsum(np.r_[np.full(8, 3.0), np.full(30, 1.5)])]
    _, sell = C.signals(C.cci(x, 25), 200)
    i = int(np.flatnonzero(sell)[0])
    assert x[i] > x[i - 1] and x[i + 10] - x[i] == pytest.approx(15.0)   # SELL while price still rises


def test_atr_matches_pine_rma():
    o, h, l, c = walk_data(80, 5)
    assert np.allclose(C.atr(h, l, c, 14)[13:], naive_atr(h, l, c, 14)[13:])
    assert np.isnan(C.atr(h, l, c, 14)[:13]).all()


def test_sma_nan_propagates():
    x = np.r_[np.nan, np.arange(1.0, 6.0)]
    s = C.sma(x, 3)
    assert np.isnan(s[2]) and s[3] == 2.0 and s[5] == 4.0


def test_pulse():
    nb = 60
    o = np.full(nb, 100.0)
    c = o + 0.1
    h, l = o + 1, o - 1
    h[-1], l[-1], c[-1] = 106, 99, 105                # a big bar: ATR expands, body 5 >= 0.25 ATR
    p = C.pulse(o, h, l, c)
    assert p[-1] and not p[-2]
    c2 = c.copy()
    c2[-1] = 100.01                                   # same range, tiny body
    assert not C.pulse(o, h, l, c2)[-1]


def test_signals():
    x = np.array([np.nan, 150, 210, 200, 199, 250, 260, 100, -150, -200, -199, -250, -201, -180])
    buy, sell = C.signals(x, 200)
    assert list(np.flatnonzero(sell)) == [4, 7]       # 200 -> 199 counts (was at the level), 260 -> 100
    assert list(np.flatnonzero(buy)) == [10, 13]
    assert C.episode_start(x, 7, -1, 200) == 5
    assert C.episode_start(x, 13, 1, 200) == 11
    assert C.episode_start(x, 4, -1, 200) == 2


# ------------------------------------------------------------------------------------------------ pivots and divergence
def test_pivots_strict_right_nonstrict_left():
    l = np.array([10, 9, 8, 7, 6, 5, 6, 7, 8, 9, 10, 11], float)
    _, pl = C.pivots(l + 20, l, 5, 5)
    assert list(np.flatnonzero(pl)) == [5]
    l2 = l.copy()
    l2[4] = 5                                         # an equal low on the left: still a pivot (non-strict left)
    assert C.pivots(l2 + 20, l2, 5, 5)[1][5]
    l3 = l.copy()
    l3[6] = 5                                         # an equal low on the right: not a pivot (strict right)
    assert not C.pivots(l3 + 20, l3, 5, 5)[1][5]


def _div_case():
    nb = 40
    h = np.full(nb, 120.0)
    l = np.full(nb, 110.0)
    l[10], l[25] = 100.0, 95.0                         # two pivot lows, the second lower
    x = np.zeros(nb)
    x[10], x[25] = -260.0, -150.0                      # CCI higher at the second: bullish divergence
    return h, l, x


def test_bullish_divergence_known_five_bars_later():
    h, l, x = _div_case()
    dv = C.divergences(h, l, x, 200)
    assert len(dv) == 1 and dv[0].side == 1 and dv[0].p == 25 and dv[0].q == 30 and dv[0].prev == 10
    x2 = x.copy()
    x2[10] = -150.0                                   # neither CCI at / beyond -200: no label
    assert C.divergences(h, l, x2, 200) == []
    x3 = x.copy()
    x3[25] = -300.0                                   # CCI lower too: no divergence
    assert C.divergences(h, l, x3, 200) == []


def test_bearish_divergence():
    h, l, x = _div_case()
    h2, l2 = 200 - l, 200 - h                         # mirror the prices
    dv = C.divergences(h2, l2, -x, 200)
    assert len(dv) == 1 and dv[0].side == -1 and dv[0].p == 25 and dv[0].q == 30


def test_divergence_nan_cci_never_qualifies():
    h, l, x = _div_case()
    x[10] = np.nan
    assert C.divergences(h, l, x, 200) == []


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_lookahead_indicator_values_and_signals():
    o, h, l, c = walk_data(400, 11, vol=3.0)
    full = ind_from(o, h, l, c)
    for cut in (30, 77, 150, 251, 399):
        part = ind_from(o[:cut + 1], h[:cut + 1], l[:cut + 1], c[:cut + 1])
        assert np.allclose(part.cci, full.cci[:cut + 1], equal_nan=True)
        assert np.allclose(part.atr, full.atr[:cut + 1], equal_nan=True)
        assert (part.buy == full.buy[:cut + 1]).all() and (part.sell == full.sell[:cut + 1]).all()
        assert (part.pulse == full.pulse[:cut + 1]).all()


def test_lookahead_divergence_pivots():
    o, h, l, c = walk_data(600, 12, vol=4.0)
    full = ind_from(o, h, l, c, level=100.0)            # +-100 so the random walk has some divergences
    assert len(full.divs) >= 3
    for cut in range(40, 600, 23):
        part = ind_from(o[:cut + 1], h[:cut + 1], l[:cut + 1], c[:cut + 1], level=100.0)
        seen = {(d.side, d.p, d.q) for d in part.divs}
        assert seen == {(d.side, d.p, d.q) for d in full.divs if d.q <= cut}
    for d in full.divs:                                # a label is never known before its pivot + 5
        part = ind_from(o[:d.q], h[:d.q], l[:d.q], c[:d.q], level=100.0)
        assert (d.side, d.p) not in {(x.side, x.p) for x in part.divs}


def _cut_panel(P, sq, ndays):
    m = int(P.me[ndays - 1])
    Q = SimpleNamespace(**{k: getattr(P, k) for k in ("anchor", "kind")})
    for k in ("mt", "mday", "mo", "mh", "ml", "mc"):
        setattr(Q, k, getattr(P, k)[:m])
    Q.ms, Q.me, Q.days, Q.ndays = P.ms[:ndays], P.me[:ndays], P.days[:ndays], ndays
    return Q, sq[:ndays]


@pytest.mark.parametrize("vname", [v.name for v in K.VARIANTS])
def test_lookahead_trades_by_session(vname):
    v = K.BY_NAME[vname]
    P, sq = minute_panel(8, seed=21, vol=3.0)
    lvl = 100.0 if v.level == 200.0 else v.level        # more signals on synthetic data
    v = K.Variant(v.name, n=v.n, level=lvl, pulse=v.pulse, div=v.div, exit=v.exit)
    full_ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 5), v.n, v.level)
    full, _ = R.run(full_ind, v, M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, np.ones(P.ndays, bool), 0.05)
    assert full                                         # the guard is not vacuous
    for nd in (3, 5, 7):
        Q, sq2 = _cut_panel(P, sq, nd)
        I2 = C.indicator(C.bars(Q.mday, Q.mt, Q.mo, Q.mh, Q.ml, Q.mc, OPEN, 5), v.n, v.level)
        part, _ = R.run(I2, v, M_of(Q), sq2, K.NSE_FIRST, K.NSE_LAST, np.ones(nd, bool), 0.05)
        keep = [t for t in full if t["day"] < nd]
        assert [(t["t"], t["side"], t["stop"], t["xm"], t["why"]) for t in part] == \
               [(t["t"], t["side"], t["stop"], t["xm"], t["why"]) for t in keep]


def test_entry_after_signal_bar_close_and_stop_from_known_bars():
    P, sq = minute_panel(6, seed=4, vol=3.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 5), 25, 100.0)
    T, _ = R.run(Ind, K.Variant("x", level=100.0), M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, np.ones(6, bool), 0.05)
    assert T
    for t in T:
        assert t["t"] == Ind.B["e"][t["j"]] + 1 and t["ts"] == t["t"] - 1
        assert t["entry"] == P.mo[t["t"]]
        a = C.episode_start(Ind.cci, t["i"], t["side"], 100.0)
        if t["side"] > 0:
            assert t["stop"] == pytest.approx(Ind.B["l"][a:t["j"] + 1].min() - 0.05)
        else:
            assert t["stop"] == pytest.approx(Ind.B["h"][a:t["j"] + 1].max() + 0.05)


# ------------------------------------------------------------------------------------------------ the trade
def test_walk_stop_first_gap_target_bar_flat():
    mo = np.array([100, 100, 100, 100, 100, 100.0])
    mh = np.array([101, 101, 106, 101, 101, 101.0])
    ml = np.array([99, 99, 94, 99, 99, 99.0])
    mc = np.array([100, 100, 100, 100, 100.5, 100.0])
    assert R.walk(mo, mh, ml, mc, 1, 1, 95.0, 105.0, -1, 5) == (2, 1, 95.0)      # both in one minute: the stop first
    mo2 = mo.copy()
    mo2[2] = 93.0
    assert R.walk(mo2, mh, ml, mc, 1, 1, 95.0, 105.0, -1, 5)[2] == 93.0           # gapped past the stop: its open
    ml2 = ml.copy()
    ml2[2] = 99
    assert R.walk(mo, mh, ml2, mc, 1, 1, 95.0, 105.0, -1, 5) == (2, 2, 105.0)
    assert R.walk(mo, mh, ml2, mc, 1, 1, 95.0, np.nan, 4, 5) == (4, 3, 100.5)     # the bar-close exit at its close
    assert R.walk(mo, mh, ml2, mc, 3, 1, 95.0, np.nan, -1, 5) == (5, 4, 100.0)    # flat at the flat minute's open
    assert R.walk(mo, mh, ml, mc, 1, -1, 105.5, 94.5, -1, 5) == (2, 1, 105.5)     # SELL: the stop above


def test_next_cross():
    x = np.array([np.nan, -250, -100, 5, 50, -3, 0])
    up, dn = R._next_cross(x)
    assert up[1] == 3 and up[3] == 4 and up[4] == 6 and dn[3] == 5 and dn[5] == 6 and up[6] == 7


def test_window_skips_open_and_late_entries():
    P, sq = minute_panel(6, seed=8, vol=3.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 3), 25, 100.0)
    T, F = R.run(Ind, K.Variant("x", level=100.0), M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, np.ones(6, bool), 0.05)
    assert F["window"] > 0
    for t in T:
        assert K.NSE_FIRST <= P.mt[t["t"]] <= K.NSE_LAST
        assert t["xm"] <= sq[t["day"]]


def test_one_position_and_daily_cap():
    P, sq = minute_panel(6, seed=9, vol=3.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 3), 20, 100.0)
    T, F = R.run(Ind, K.Variant("x", n=20, level=100.0), M_of(P), sq, K.NSE_FIRST, K.NSE_LAST,
                 np.ones(6, bool), 0.05)
    for a, b in zip(T, T[1:]):
        assert b["ts"] > a["xm"]
    per = pd.Series([t["day"] for t in T]).value_counts()
    assert per.max() <= K.MAX_PER_DAY


def test_day_off_and_expiry_flat():
    P, sq = minute_panel(4, seed=10, vol=3.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 5), 25, 100.0)
    ok = np.array([True, False, True, False])
    T, F = R.run(Ind, K.Variant("x", level=100.0), M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, ok, 0.05)
    assert all(ok[t["day"]] for t in T) and F["day_off"] >= 0
    sq2 = sq.copy()
    sq2[2] = P.ms[2] + int(np.searchsorted(P.mt[P.ms[2]:P.me[2]], 15 * 60 + 5))     # expiry day: flat 15:05
    T2, _ = R.run(Ind, K.Variant("x", level=100.0), M_of(P), sq2, K.NSE_FIRST, K.NSE_LAST, ok, 0.05)
    assert all(t["xm"] <= sq2[t["day"]] for t in T2)


def test_exit_styles():
    P, sq = minute_panel(6, seed=13, vol=3.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 5), 25, 100.0)
    ok = np.ones(6, bool)
    for ex in ("Z", "R", "T"):
        T, _ = R.run(Ind, K.Variant("x", level=100.0, exit=ex), M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, ok, 0.05)
        assert T
        for t in T:
            if ex == "R":
                assert t["target"] == pytest.approx(t["entry"] + t["side"] * 2 * t["risk"])
                assert t["why"] in ("stop", "target", "flat")
            else:
                assert np.isnan(t["target"]) and t["why"] in ("stop", "bar", "flat")
            if t["why"] == "bar":
                k = t["k"]
                assert t["xm"] == Ind.B["e"][k]
                if ex == "Z":
                    assert t["side"] * Ind.cci[k] >= 0
                    assert all(not (t["side"] * Ind.cci[m] >= 0) for m in range(t["i"] + 1, k))
                else:
                    assert k == t["i"] + K.TIME_BARS


def test_dv_entry_at_later_of_signal_and_confirmation():
    P, sq = minute_panel(8, seed=17, vol=4.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 3), 25, 100.0)
    v = K.Variant("DV", level=100.0, div=True)
    cands = R.candidates(Ind, v)
    assert cands
    for i, j, s, a in cands:
        assert j >= i and j - i <= K.DV_WINDOW
        assert any(d.side == s and abs(d.q - i) <= K.DV_WINDOW and j == max(i, d.q) for d in Ind.divs)
    T, F = R.run(Ind, v, M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, np.ones(8, bool), 0.05)
    for t in T:
        assert t["j"] >= t["i"]


def test_dv_one_divergence_confirms_one_signal():
    nb = 60
    x = np.zeros(nb)
    x[19], x[20] = -250, -150                       # BUY at 20
    x[22], x[23] = -230, -120                       # BUY at 23
    B = dict(o=np.full(nb, 100.0), h=np.full(nb, 101.0), l=np.full(nb, 99.0), c=np.full(nb, 100.0),
             day=np.zeros(nb, np.int64), s=np.arange(nb), e=np.arange(nb))
    buy, sell = C.signals(x, 200)
    Ind = C.Ind(B, B["c"], x, buy, sell, np.ones(nb, bool), np.ones(nb), [C.Div(1, 16, 21, 5)], 200.0, 25)
    c = R.candidates(Ind, K.Variant("DV", div=True))
    assert len(c) == 1 and c[0][0] == 20 and c[0][1] == 21 and c[0][3] == 16


# ------------------------------------------------------------------------------------------------ twins and pricing
def test_twin_exits():
    mo = np.full(10, 100.0)
    mh = np.full(10, 101.0)
    ml = np.full(10, 99.0)
    ml[6] = 94
    xm, why = PR.twin_exits(mo, mh, ml, np.array([1, 2, 7]), 1, 5.0, -1.0, 3, 9)
    assert list(xm) == [4, 5, 9] and list(why) == [3, 3, 4]
    xm, why = PR.twin_exits(mo, mh, ml, np.array([3]), 1, 5.0, -1.0, 5, 9)
    assert xm[0] == 6 and why[0] == 1
    mh[4] = 112
    xm, why = PR.twin_exits(mo, mh, ml, np.array([2]), 1, 5.0, 10.0, -1, 9)
    assert xm[0] == 4 and why[0] == 2


def test_twin_pool_starts_at_entry_and_stays_in_window():
    P, sq = minute_panel(2, seed=3)
    t0 = int(P.ms[1]) + 100
    pl = PR.pool(P, 1, t0, K.NSE_FIRST, K.NSE_LAST, int(sq[1]))
    assert pl.min() == t0 and P.mt[pl].max() <= K.NSE_LAST and pl.max() < sq[1]
    assert (P.mday[pl] == 1).all()


def test_rows_flat_and_next_minute():
    P, sq = minute_panel(1)
    rows, fx = PR._rows(P, [20, 30], [40, 50], [False, True])
    assert list(rows) == [20, 30] and list(fx) == [41, -1]


# ------------------------------------------------------------------------------------------------ study pieces
def test_holdout_guard():
    with pytest.raises(PermissionError):
        S.check(date(2026, 1, 1), date(2026, 4, 1))
    S.check(date(2025, 1, 1), date(2026, 3, 31))
    P = SimpleNamespace(days=[date(2026, 3, 30), date(2026, 3, 31), date(2026, 4, 1)])
    assert S.day_range(P, date(2026, 3, 1), date(2026, 3, 31)) == (0, 1)
    with pytest.raises(PermissionError):
        S.day_range(P, date(2026, 3, 1), date(2026, 4, 2))


def test_final_refuses_second_read(tmp_path, monkeypatch):
    flag = tmp_path / "FINAL_READ"
    flag.write_text("x")
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        S.run_final()


def test_cell_stats_and_payoff():
    T = pd.DataFrame(dict(net=[1000.0, -400, -400, 3000, -400], date=[date(2025, 1, d) for d in (6, 6, 7, 8, 9)]))
    tw = np.r_[np.zeros(999), 5000.0]
    o = S.cell_stats(T, 5, tw)
    assert o["trades"] == 5 and o["win"] == 0.4 and o["net"] == 2800
    assert o["payoff"] == pytest.approx(2000 / 400)
    assert o["p_emp"] == pytest.approx(2 / 1001, abs=1e-5)
    assert 0 < o["p_norm"] < 0.5
    assert S.payoff(np.array([1.0, 2.0])) is None


def test_design_gates_and_bh_floor():
    r = dict(inst="NIFTY", opt=dict(trades=50, net=1.0, p_emp=0.01, ex_top5=1.0, worst_month=-1000.0), q=0.05,
             folds=[dict(net=1), dict(net=1), dict(net=1), dict(net=-1)])
    assert S.design_gates(r)["pass"]
    r2 = dict(r, folds=[dict(net=1), dict(net=1), dict(net=-1), dict(net=-1)])
    assert not S.design_gates(r2)["pass"]
    r3 = dict(r, opt=dict(r["opt"], worst_month=-13001.0))
    assert not S.design_gates(r3)["G7"]
    cells = {("A", 3, "B"): dict(opt=dict(p_norm=0.0)), ("A", 5, "B"): dict(opt=dict(p_norm=0.5))}
    q = S.bh_q(cells, sorted(cells))
    assert q[0] == pytest.approx(2e-6) and q[1] == pytest.approx(0.5)


def test_variants_declared():
    assert [v.name for v in K.VARIANTS] == ["B", "XR", "XT", "PU", "DV", "L20", "L50", "K100", "MO"]
    assert [v.name for v in K.FADE] == ["B", "XR", "XT", "PU", "DV", "L20", "L50", "K100"]
    assert len(K.INSTRUMENTS) * len(K.TIMEFRAMES) * len(K.FADE) == 288
    assert K.N_CELLS == 288 + 36 + 4 == 328
    mo = K.BY_NAME["MO"]
    assert (mo.n, mo.level, mo.pulse, mo.div, mo.exit, mo.mode) == (25, 200.0, False, False, "M", "mo")
    b = K.BY_NAME["B"]
    assert (b.n, b.level, b.pulse, b.div, b.exit) == (25, 200.0, False, False, "Z")


# ------------------------------------------------------------------------------------------------ describe
def test_forward_signed_and_session_cut():
    P, sq = minute_panel(3, seed=5, vol=2.0)
    I = fake_inst(P, sq)
    Ind = I.ind(15)
    B = Ind.B
    i = 5                                             # a 15m bar on day 0 (10:30 - 10:44)
    F = DS.forward(I, B, np.array([i, i]), np.array([1, -1]), Ind.atr, 5)
    E = P.mo[B["e"][i] + 1]
    assert F.ret.iloc[0] == pytest.approx(B["c"][i + 5] - E) and F.ret.iloc[1] == pytest.approx(E - B["c"][i + 5])
    assert F.mfe.iloc[0] == pytest.approx(B["h"][i + 1:i + 6].max() - E)
    F2 = DS.forward(I, B, np.array([20]), np.array([1]), Ind.atr, 5)     # 14:15 bar + 5 crosses the session
    assert bool(F2.cross.iloc[0]) and bool(F2.s_cut.iloc[0])
    assert F2.s_ret.iloc[0] == pytest.approx(P.mo[sq[0]] - P.mo[B["e"][20] + 1])


def test_describe_runs_on_synthetic_panel():
    P, sq = minute_panel(6, seed=6, vol=3.0)
    I = fake_inst(P, sq)
    I.P.days = [date(2025, 1, 6 + d) for d in range(6)]
    PR_option = PR.option
    try:
        PR.option = lambda I_, T: [dict(t, net=1.0, gross=1.0, filled=True, qty=1.0) for t in T]
        out = DS.describe(I, date(2025, 1, 6), date(2025, 1, 11), S.day_range)
    finally:
        PR.option = PR_option
    assert out["sessions"] == 6
    for tf in K.TIMEFRAMES:
        r = out[str(tf)]
        assert r["signals"] == r["buy"] + r["sell"]
        assert set(r["forward"]) == {str(h) for h in K.HORIZONS}
        assert set(r["repaint"]) == {"label", "confirm", "inflation"}


# ------------------------------------------------------------------------------------------------ amendment A1: MO
def test_breakouts_and_next_inside():
    x = np.array([np.nan, 150, 210, 260, 199, -150, -220, -250, -150, 205])
    up, dn = C.breakouts(x, 200)
    assert list(np.flatnonzero(up)) == [2, 9] and list(np.flatnonzero(dn)) == [6]
    lo, hi = R._next_inside(x, 200)
    assert lo[2] == 4 and lo[3] == 4 and hi[6] == 8 and lo[9] == 10


def test_mo_entries_stops_and_exits():
    P, sq = minute_panel(6, seed=31, vol=3.0)
    Ind = C.indicator(C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, OPEN, 5), 25, 100.0)
    v = K.Variant("MO", level=100.0, exit="M", mode="mo")
    up, dn = C.breakouts(Ind.cci, 100.0)
    T, F = R.run(Ind, v, M_of(P), sq, K.NSE_FIRST, K.NSE_LAST, np.ones(6, bool), 0.05)
    assert T
    for t in T:
        i = t["i"]
        assert (up[i] and t["side"] == 1) or (dn[i] and t["side"] == -1)
        assert t["j"] == i and t["t"] == Ind.B["e"][i] + 1
        ext = Ind.B["l"][i] - 0.05 if t["side"] > 0 else Ind.B["h"][i] + 0.05
        assert t["stop"] == pytest.approx(ext)
        assert np.isnan(t["target"]) and t["why"] in ("stop", "bar", "flat")
        if t["why"] == "bar":
            k = t["k"]
            assert (Ind.cci[k] < 100.0) if t["side"] > 0 else (Ind.cci[k] > -100.0)
            assert all((Ind.cci[m] >= 100.0) if t["side"] > 0 else (Ind.cci[m] <= -100.0) for m in range(i + 1, k))


# ------------------------------------------------------------------------------------------------ amendment A1: EX overlay
def test_overlay_exit_row():
    rows = np.array([4, 9, 14, 19, 24])                # bar close rows (5m bars ending 09:19, 09:24, ...)
    buy = np.array([False, False, False, True, False])
    sell = np.array([False, True, False, False, True])
    assert OV.exit_row(rows, buy, sell, er=6, xr=40, side=1) == 10       # first SELL closing at / after entry
    assert OV.exit_row(rows, buy, sell, er=10, xr=40, side=1) == 25      # the 09:24 bar closed before the entry
    assert OV.exit_row(rows, buy, sell, er=6, xr=10, side=1) == -1       # not before the arm's own exit
    assert OV.exit_row(rows, buy, sell, er=6, xr=40, side=-1) == 20      # a put exits on a BUY
    assert OV.exit_row(rows, buy, sell, er=9, xr=40, side=1) == 10       # a bar closing on the entry row counts
    assert OV.exit_row(rows, np.zeros(5, bool), np.zeros(5, bool), 0, 99, 1) == -1


def test_overlay_summary_and_gates():
    days = [date(2025, m, 10) for m in (4, 5, 7, 8, 10, 11, 1, 2)]
    days[6], days[7] = date(2026, 1, 10), date(2026, 2, 10)
    rows = []
    for k, d in enumerate(days * 6):
        ch = k % 2 == 0
        rows.append(dict(und="BANKNIFTY", day=d, date=d, side=1, er=10, xr=200, net=100.0, changed=ch,
                         x=50 if ch else -1, net0=-200.0, net1=-200.0 + (300.0 if ch else 0.0),
                         diff=300.0 if ch else 0.0))
    F = pd.DataFrame(rows)
    r = OV.summarise("liquidity", 5, F, np.zeros(1000))
    assert r["inst"] == "ARM:liquidity" and r["opt"]["trades"] == 24 and r["opt"]["net"] == 7200
    assert r["opt"]["ex_top5"] == 7200 - 1500 and r["opt"]["p_emp"] == pytest.approx(1 / 1001, abs=1e-5)
    assert r["as_is"]["net"] == -9600 and r["with_ex"]["net"] == -2400 and r["changed_share"] == 0.5
    r["q"] = 0.01
    g = OV.gates(r)
    assert not g["G1"] and g["G2"] and g["G3"] and g["G5"] and g["G6"] and not g["pass"]   # 24 changed < 40
    assert OV.summarise("liquidity", 5, F.iloc[:0], np.zeros(10))["opt"]["trades"] == 0


def test_overlay_run_cell_with_fake_arm_and_pricer(monkeypatch):
    """The cell loop: unchanged trades add 0, changed trades their difference, twins exit at random rows inside."""
    P, sq = minute_panel(3, seed=41, vol=3.0)
    I = fake_inst(P, sq)
    I.store = {dt: None for dt in P.days}
    I.day_index_of = lambda dt: P.days.index(dt) if dt in P.days else None
    Ind = I.ind(5, 25, 200.0)
    d1 = P.days[1]
    br, b, s = OV.day_bars(Ind, P, 1)
    seen = []

    def fake_price(und, D, side, er, fx, lot):
        fx = np.asarray(fx)
        seen.append(fx.copy())
        return 1000.0 - 2.0 * fx                       # an earlier exit is worth more, by construction

    trades = [dict(und="BANKNIFTY", day=d1, side=1, er=20, xr=300, net=5.0),
              dict(und="BANKNIFTY", day=d1, side=-1, er=20, xr=300, net=5.0),
              dict(und="BANKNIFTY", day=date(2030, 1, 1), side=1, er=20, xr=300, net=5.0)]   # not in the panel
    monkeypatch.setattr(OV, "arm_trades", lambda arm, lo, hi: trades)
    monkeypatch.setattr(OV, "price_rows", fake_price)
    monkeypatch.setattr(OV.P90.K51, "lot", lambda und, dt: 30)
    r, F = OV.run_cell("liquidity", 5, {"BANKNIFTY": I}, None, None, twins=200)
    assert len(F) == 2
    for _, row in F.iterrows():
        x = OV.exit_row(br, b, s, 20, 300, int(row.side))
        assert row.changed == (x >= 0)
        assert row["diff"] == pytest.approx(2.0 * (300 - x) if x >= 0 else 0.0)
    assert F.changed.any()                             # not vacuous
    twin_draws = [a for a in seen if len(a) == 200]
    assert len(twin_draws) == int(F.changed.sum())
    for a in twin_draws:
        assert a.min() >= 21 and a.max() <= 299


def test_save_split_keeps_every_key_and_moves_only_the_descriptive_block(tmp_path, monkeypatch):
    import json
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    out = dict(written="2026-10-21T00:00:00+00:00", n_cells=328, cells=[{"inst": "NIFTY"}], passers=["A|5|B"],
               describe={"NIFTY": {"sessions": 3}})
    S._save_split("design", out)
    main = json.loads((tmp_path / "design.json").read_text())
    desc = json.loads((tmp_path / "design_describe.json").read_text())
    assert "describe" not in main and main["describe_file"] == "design_describe.json"
    assert main["n_cells"] == 328 and main["passers"] == ["A|5|B"] and main["cells"] == [{"inst": "NIFTY"}]
    assert desc == dict(written=out["written"], describe={"NIFTY": {"sessions": 3}})
    assert "describe" in out                          # the caller's dict is not changed
