"""R177-EMA tests: indicators, bars, the completed 1-hour bar, VWAP, the filters, the index exits and their order, fills,
the walk (one position a side, expiry skip), the study helpers (drawdown, months, BH, gates, twins) and the look-ahead
guard. Synthetic data only: no release files, no stores."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
pytest.importorskip("numba")
import r177cfg as K  # noqa: E402
import r177lib as Z  # noqa: E402
import r177study as S  # noqa: E402

NAN = np.nan


# ------------------------------------------------------------------------------------------------ synthetic data
def _days(n=40, seed=1, start=date(2024, 5, 1), opts=True, vol=1000.0):
    """n sessions of a random-walk index (no gaps) with ATM +- 3 strikes of synthetic options (price = intrinsic + 50)."""
    rng = np.random.default_rng(seed)
    out, px, d = [], 22000.0, start
    while len(out) < n:
        if d.weekday() < 5:
            steps = rng.normal(0, 8, K.N)
            c = px + np.cumsum(steps)
            o = np.r_[px, c[:-1]]
            ix = np.stack([o, np.maximum(o, c) + 2, np.minimum(o, c) - 2, c], axis=1)
            px = float(c[-1])
            op = {}
            if opts:
                k0 = Z.atm_strike(float(c.mean()), 50.0)
                for k in np.arange(k0 - 400, k0 + 401, 50.0):
                    for s in "CP":
                        intr = np.maximum(c - k, 0) if s == "C" else np.maximum(k - c, 0)
                        p = intr + 50.0
                        A = np.stack([p, p + 1, p - 1, p, np.full(K.N, vol)], axis=1)
                        op[(float(k), s)] = A
            out.append(SimpleNamespace(day=d, ix=ix, exp=None, opts=op, lot=75))
        d += timedelta(days=1)
    return out


def _index(days):
    return Z.from_days("NIFTY", days)


# ------------------------------------------------------------------------------------------------ indicators and bars
def test_atr_wilder():
    h = np.array([10, 11, 12, 13.0])
    l = np.array([9, 9, 10, 12.0])
    c = np.array([9.5, 10.5, 11.5, 12.5])
    a = Z.atr(h, l, c, 3)
    tr = [1.0, 2.0, 2.0, 1.5]
    assert np.isnan(a[:2]).all()
    assert a[2] == pytest.approx(np.mean(tr[:3]))
    assert a[3] == pytest.approx((a[2] * 2 + tr[3]) / 3)
    assert np.isnan(Z.atr(h[:2], l[:2], c[:2], 3)).all()           # too short
    assert len(Z.atr(np.zeros(0), np.zeros(0), np.zeros(0), 3)) == 0


def test_bars_with_gaps():
    ix = np.full((1, K.N, 4), NAN)
    ix[0, 0] = (100, 101, 99, 100.5)
    ix[0, 5] = (100.5, 103, 100, 102)                                # minutes 1-4 missing
    ix[0, 20] = (50, 51, 49, 50)                                     # bar 1 has one minute; bar 2.. none
    b = Z.bars(ix, 15)
    assert b.shape == (1, 25, 4)
    assert tuple(b[0, 0]) == (100, 103, 99, 102)
    assert tuple(b[0, 1]) == (50, 51, 49, 50)
    assert np.isnan(b[0, 2]).all()
    h = Z.bars(ix, 60)
    assert h.shape == (1, 7, 4) and tuple(h[0, 0]) == (100, 103, 49, 50)


def test_completed_h1_bar():
    valid = np.ones((2, K.NH), bool)
    pos = Z.h1_slot_index(valid)
    assert pos[0, 0] == -1 and pos[0, 2] == -1                     # day 1 before 10:15: none yet
    assert pos[0, 3] == 0                                          # 10:00-10:14 bar closes: 09:15 1-hour bar done
    assert pos[0, 6] == 0 and pos[0, 7] == 1
    assert pos[0, 22] == 4                                         # 14:45 bar: 14:15-15:14 not done yet
    assert pos[0, 23] == 5                                         # 15:00-15:14 closes it
    assert pos[0, 24] == 6                                         # the last bar completes both the 6th and the part bar
    assert pos[1, 0] == 6 and pos[1, 2] == 6                       # next day before 10:15: the previous session's last
    assert pos[1, 3] == 7
    valid[1, 0] = False                                            # a missing 1-hour bar is skipped
    pos = Z.h1_slot_index(valid)
    assert pos[1, 3] == 6 and pos[1, 7] == 7


def test_vwap_and_zero_volume():
    ix = np.full((1, K.N, 4), NAN)
    ix[0, :, :] = 100.0
    ix[0, :15, 1:4] = (103, 97, 100)                               # tp 100
    ix[0, 15:30, 1:4] = (113, 107, 110)                            # tp 110
    w = np.zeros((1, K.N))
    w[0, :15] = 1.0
    w[0, 15:30] = 3.0
    v = Z.session_vwap(ix, w)
    assert v[0, 0] == pytest.approx(100.0)
    assert v[0, 1] == pytest.approx((15 * 100 + 45 * 110) / 60)
    assert np.isnan(Z.session_vwap(ix, np.zeros((1, K.N)))[0]).all()   # no volume: no VWAP (no trade)


def test_strike_rule():
    assert Z.atm_strike(22024.9, 50) == 22000 and Z.atm_strike(22025.0, 50) == 22050
    assert Z.strike_for("C", 22010, 50, 1) == 21950 and Z.strike_for("P", 22010, 50, 1) == 22050
    assert Z.strike_for("P", 48049, 100, 0) == 48000


# ------------------------------------------------------------------------------------------------ filters
def _f(**kw):
    """A one-slot feature dict where every filter passes for a call; kw overrides."""
    f = dict(valid=np.array([[True]]), up=np.array([[True]]), dn=np.array([[False]]), close=np.array([[105.0]]),
             adx=np.array([[30.0]]), atr=np.array([[10.0]]), h1c=np.array([[104.0]]), h1e21=np.array([[100.0]]),
             h1e9=np.array([[102.0]]), vwap=np.array([[101.0]]), twap=np.array([[101.0]]), orh=np.array([103.0]),
             orl=np.array([90.0]))
    for k, v in kw.items():
        f[k] = v
    return f


def _pad(f, b):
    """Move the one slot to bar b."""
    g = {}
    for k, v in f.items():
        if v.ndim == 2:
            a = np.zeros((1, K.NB), v.dtype) if v.dtype == bool else np.full((1, K.NB), NAN)
            a[0, b] = v[0, 0]
            g[k] = a
        else:
            g[k] = v
    return g


def test_filters_each():
    f = _pad(_f(), 5)
    for r in K.RULES:
        assert Z.decide(f, 0, 5, "C", r), r
        assert not Z.decide(f, 0, 5, "P", r), r                     # no down-cross
    assert not Z.decide(_pad(_f(adx=np.array([[25.0]])), 5), 0, 5, "C", "ADX")      # > 25, not >=
    assert Z.decide(_pad(_f(adx=np.array([[25.0]])), 5), 0, 5, "C", "PLAIN")
    assert not Z.decide(_pad(_f(adx=np.array([[NAN]])), 5), 0, 5, "C", "ALL")
    assert not Z.decide(_pad(_f(h1c=np.array([[99.0]])), 5), 0, 5, "C", "TREND")
    assert Z.decide(_pad(_f(h1c=np.array([[99.0]])), 5), 0, 5, "C", "ALL-H1B")      # B reads EMA 9 vs 21 only
    assert not Z.decide(_pad(_f(h1e9=np.array([[99.0]])), 5), 0, 5, "C", "ALL-H1B")
    assert not Z.decide(_pad(_f(vwap=np.array([[106.0]])), 5), 0, 5, "C", "ALL")
    assert Z.decide(_pad(_f(vwap=np.array([[106.0]])), 5), 0, 5, "C", "ALL-TWAP")
    assert not Z.decide(_pad(_f(vwap=np.array([[NAN]])), 5), 0, 5, "C", "TREND")    # VWAP unknown: no trade
    assert not Z.decide(_pad(_f(orh=np.array([106.0])), 5), 0, 5, "C", "ALL-OR")
    assert Z.decide(_pad(_f(orh=np.array([106.0])), 5), 0, 5, "C", "ALL")
    assert not Z.decide(_pad(_f(), 0), 0, 0, "C", "ALL-OR")       # bar 0: the range is not complete
    assert Z.decide(_pad(_f(), 0), 0, 0, "C", "ALL")
    assert not Z.decide(_pad(_f(atr=np.array([[NAN]])), 5), 0, 5, "C", "ATR")
    assert Z.decide(_pad(_f(atr=np.array([[NAN]])), 5), 0, 5, "C", "PLAIN")
    assert not Z.decide(_pad(_f(), 23), 0, 23, "C", "PLAIN")       # signal bars 0..22 only


def test_filters_put_mirror():
    f = _pad(_f(up=np.array([[False]]), dn=np.array([[True]]), close=np.array([[95.0]]), h1c=np.array([[96.0]]),
                h1e9=np.array([[98.0]]), vwap=np.array([[99.0]]), twap=np.array([[99.0]]), orl=np.array([97.0])), 4)
    for r in K.RULES:
        assert Z.decide(f, 0, 4, "P", r), r
        assert not Z.decide(f, 0, 4, "C", r)


# ------------------------------------------------------------------------------------------------ exits
def _flat_ix(px=100.0):
    ix = np.full((K.N, 4), px)
    return ix


def test_exit_order():
    up, dn = np.zeros(K.NB, bool), np.zeros(K.NB, bool)
    ix = _flat_ix()
    assert Z.index_exit(ix, up, dn, 3, "C", 60, NAN, NAN) == (K.SQ_ROW, Z.R_SQ)
    dn[10] = True
    assert Z.index_exit(ix, up, dn, 3, "C", 60, NAN, NAN) == (165, Z.R_CROSS)
    assert Z.index_exit(ix, up, dn, 10, "C", 165, NAN, NAN) == (K.SQ_ROW, Z.R_SQ)     # only bars after the signal
    ix[100, 2] = 89.0                                                # low through the stop at minute 100
    assert Z.index_exit(ix, up, dn, 3, "C", 60, 90.0, NAN) == (101, Z.R_STOP)
    ix[100, 1] = 121.0                                               # and the target in the same minute: the stop
    assert Z.index_exit(ix, up, dn, 3, "C", 60, 90.0, 120.0) == (101, Z.R_STOP)
    ix2 = _flat_ix()
    ix2[80, 1] = 121.0
    assert Z.index_exit(ix2, up, dn, 3, "C", 60, 90.0, 120.0) == (81, Z.R_TARGET)
    ix3 = _flat_ix()
    ix3[164, 2] = 80.0                                               # stop at 164 -> 165 ties the cross: the stop
    assert Z.index_exit(ix3, up, dn, 3, "C", 60, 90.0, NAN) == (165, Z.R_STOP)
    ix4 = _flat_ix()
    ix4[59, 2] = 50.0                                                # before the fill: ignored
    ix4[70, 2] = NAN
    ix4[70, 1] = NAN                                                 # a missing minute triggers nothing
    assert Z.index_exit(ix4, up, dn, 3, "C", 60, 90.0, NAN) == (165, Z.R_CROSS)
    # puts: the mirror
    ix5 = _flat_ix()
    ix5[90, 1] = 111.0
    up2 = np.zeros(K.NB, bool)
    assert Z.index_exit(ix5, up2, dn, 3, "P", 60, 110.0, NAN) == (91, Z.R_STOP)
    ix5[90, 1] = 100.0
    ix5[95, 2] = 79.0
    assert Z.index_exit(ix5, up2, dn, 3, "P", 60, 110.0, 80.0) == (96, Z.R_TARGET)
    ix6 = _flat_ix()
    ix6[K.SQ_ROW - 1, 2] = 50.0                                      # stop on the last minute: sold at 15:10 as a stop
    assert Z.index_exit(ix6, np.zeros(K.NB, bool), np.zeros(K.NB, bool), 3, "C", 60, 90.0, NAN) == (K.SQ_ROW, Z.R_STOP)


def test_fill_and_exit_price():
    A = np.full((K.N, 5), NAN)
    A[62, 0] = 10.0
    assert Z.fill_row(A, 60) == 62
    assert Z.fill_row(A, 59) == -1                                   # 59, 60, 61 only
    A2 = np.full((K.N, 5), NAN)
    A2[100, 3] = 7.0
    A2[105, 0] = 9.0
    assert Z.exit_px(A2, 103) == 9.0                                 # next traded open within 3 minutes
    assert Z.exit_px(A2, 101) == 7.0                                 # else the last close before
    A2[101, 0] = 8.0
    assert Z.exit_px(A2, 101) == 8.0


def test_price_stop_is_worse_than_market():
    A = np.full((K.N, 5), 100.0)
    A[:, 4] = 0.0                                                    # no volume: 4-tick half-spread
    m = Z.price(A, 60, 100, Z.R_CROSS, 75.0, date(2025, 1, 6), "NIFTY")
    s = Z.price(A, 60, 100, Z.R_STOP, 75.0, date(2025, 1, 6), "NIFTY")
    assert m[2] == pytest.approx(100.05 + 0.2)                       # +5 bps + 4 ticks
    assert m[3] == pytest.approx(99.95 - 0.2)
    assert s[3] == pytest.approx(99.9 - 0.4)                         # -10 bps - 2 x the half-spread
    assert s[4] < m[4] < 0
    A[:, 4] = 1e6
    assert Z.price(A, 60, 100, Z.R_CROSS, 75.0, date(2025, 1, 6), "NIFTY")[2] == pytest.approx(100.1)


# ------------------------------------------------------------------------------------------------ the walk
def test_walk_one_position_a_side_and_expiry_skip():
    days = _days(45, seed=3)
    I = _index(days)
    lo, hi = days[0].day, days[-1].day
    old = (K.START, K.END)
    K.START, K.END = lo, hi
    try:
        for rule in ("PLAIN", "ATR", "ALL"):
            tr, nf = Z.walk(I, rule, lo, hi)
            for side in "CP":
                by = {}
                for t in tr:
                    if t.side == side:
                        by.setdefault(t.day, []).append(t)
                for ts in by.values():
                    ts.sort(key=lambda t: t.fill)
                    for a, b in zip(ts, ts[1:]):
                        assert b.fill >= a.exit                      # never two open on one side
            assert all(t.day >= days[K.WARM_SESSIONS].day for t in tr)
            assert all(t.exit <= K.SQ_ROW for t in tr)
        tr, _ = Z.walk(I, "PLAIN", lo, hi)
        assert tr, "the synthetic walk should trade"
        exp_day = tr[0].day
        I.expiry = {exp_day}
        tr2, _ = Z.walk(I, "PLAIN", lo, hi)
        assert all(t.day != exp_day for t in tr2)
    finally:
        K.START, K.END = old


def test_walk_no_options_counts_nofill():
    days = _days(25, seed=4, opts=False)
    I = _index(days)
    old = (K.START, K.END)
    K.START, K.END = days[0].day, days[-1].day
    try:
        tr, nf = Z.walk(I, "PLAIN", days[0].day, days[-1].day)
        assert tr == [] and nf > 0
    finally:
        K.START, K.END = old


def test_empty_index():
    I = Z.from_days("NIFTY", [])
    assert Z.walk(I, "ALL")[0] == []


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_lookahead_signal_bar():
    """Replacing every index minute after a signal bar's close (that day and all later days) leaves that bar's
    features and every rule's decision unchanged."""
    days = _days(30, seed=7)
    base = _index(days)
    rng = np.random.default_rng(9)
    checked = 0
    for di in (17, 22, 28):
        for b in (0, 3, 7, 12, 22):
            row = K.BAR * (b + 1)
            noisy = [SimpleNamespace(**vars(d)) for d in days]
            for j in range(di, len(days)):
                ix = days[j].ix.copy()
                lo = row if j == di else 0
                ix[lo:] = ix[lo:] + rng.normal(0, 50, ix[lo:].shape)
                ix[lo:, 1] = ix[lo:].max(axis=1)
                ix[lo:, 2] = ix[lo:].min(axis=1)
                noisy[j].ix = ix
            N_ = _index(noisy)
            for k in ("close", "up", "dn", "adx", "atr", "h1c", "h1e21", "h1e9", "vwap", "twap"):
                a, z = base.f[k][di, b], N_.f[k][di, b]
                assert (a == z) or (np.isnan(a) and np.isnan(z)), (k, di, b)
            if b >= 1:
                assert base.f["orh"][di] == N_.f["orh"][di]
            for r in K.RULES:
                assert Z.decide(base.f, di, b, "C", r) == Z.decide(N_.f, di, b, "C", r)
                assert Z.decide(base.f, di, b, "P", r) == Z.decide(N_.f, di, b, "P", r)
            checked += 1
    assert checked == 15


def test_lookahead_exit():
    """Replacing every index and option minute after a trade's exit row leaves its exit row and prices unchanged."""
    days = _days(30, seed=11)
    I = _index(days)
    rng = np.random.default_rng(5)
    n = 0
    for di in range(K.WARM_SESSIONS, 30):
        for b in (2, 9, 16):
            for side in "CP":
                for rule in ("PLAIN", "ATR", "ALL-2R", "ALL-ITM"):
                    t = Z.trade_at(I, di, b, side, rule)
                    if t is None or t.exit >= K.N - 1:
                        continue
                    x = t.exit
                    d2 = SimpleNamespace(**vars(days[di]))
                    ix = days[di].ix.copy()
                    ix[x + 1:] += rng.normal(0, 80, ix[x + 1:].shape)
                    d2.ix = ix
                    d2.opts = {k: v.copy() for k, v in days[di].opts.items()}
                    for v in d2.opts.values():
                        v[x + 1:, :4] = rng.uniform(1, 500, (K.N - x - 1, 1))
                    J = Z.Index(I.und, I.days, I.ix.copy(), I.chainvol, I.expiry, I.lot, list(I.opts))
                    J.ix[di] = ix
                    J.opts[di] = d2.opts
                    J.f = Z.features(J)                              # recomputed from the noisy minutes
                    u = Z.trade_at(J, di, b, side, rule)
                    assert (u.exit, u.reason, u.exit_raw, u.net) == (t.exit, t.reason, t.exit_raw, t.net)
                    n += 1
    assert n > 50


# ------------------------------------------------------------------------------------------------ study helpers
def _t(d, net, und="NIFTY", side="C", reason="cross"):
    return SimpleNamespace(day=d, net=(net, 2 * net, 3 * net), und=und, side=side, reason=reason)


def test_drawdown_and_stats():
    assert S.max_drawdown([]) == 0.0
    assert S.max_drawdown([100, -50, -100, 30, 200, -10]) == 150.0
    assert S.max_drawdown([-10, -20]) == 30.0
    s = S.stats([], 0)
    assert s["trades"] == 0 and s["net"] == 0 and s["max_dd"] == 0 and s["worst_month"] == 0
    tr = [_t(date(2025, 1, 2), 100), _t(date(2025, 1, 2), -300), _t(date(2025, 2, 3), 50, reason="stop")]
    s = S.stats(tr, 0)
    assert s["trades"] == 3 and s["win"] == pytest.approx(200 / 3) and s["net"] == -150
    assert s["worst_day"] == -200 and s["worst_month"] == -200 and s["max_dd"] == 200
    assert s["avg_win"] == 75 and s["avg_loss"] == -300 and s["exits"] == {"cross": 2, "stop": 1}
    assert s["net_wo_top5"] == 0.0
    assert S.stats(tr, 2)["net"] == -450


def test_select_and_period():
    tr = [_t(date(2025, 1, 2), 1), _t(date(2025, 9, 1), 1, und="BANKNIFTY", side="P"), _t(date(2024, 1, 1), 1)]
    assert len(S.select(tr, "both", "both", "full")) == 2
    assert len(S.select(tr, "NIFTY", "both", "design")) == 1
    assert len(S.select(tr, "BANKNIFTY", "P", "final")) == 1
    assert len(S.select(tr, "BANKNIFTY", "C", "final")) == 0
    assert K.in_period(K.DESIGN_END, "design") and not K.in_period(K.FINAL_START, "design")


def test_bh_total():
    q = S.bh_total([0.001, 0.02, 0.5], 3)
    assert q[0] == pytest.approx(0.003) and q[1] == pytest.approx(0.03) and q[2] == pytest.approx(0.5)
    q = S.bh_total([1e-6], K.M_TOTAL)
    assert q[0] == pytest.approx(1e-6 * 4950)
    assert len(S.bh_total([], 10)) == 0
    assert K.M_TOTAL == 4950 and len(K.TESTS) == 270


def test_twin_p():
    tw = np.arange(100, dtype=float)
    pe, pn = S.twin_p(98.5, tw)
    assert pe == pytest.approx(2 / 101)
    assert 0 < pn < 0.05
    pe, pn = S.twin_p(-5, tw)
    assert pe == 1.0 and pn > 0.95
    assert S.twin_p(1.0, np.zeros(10)) == (1 / 11, 0.0)


def test_twin_draws_same_counts_and_seed():
    T = np.zeros((3, 23, 3))
    T[0, :, 0] = np.arange(23)
    T[1, 5, 0] = 1000.0
    counts = np.array([2, 0, 23])
    a = S.twin_draws(T, counts, 50, np.random.default_rng(1))
    b = S.twin_draws(T, counts, 50, np.random.default_rng(1))
    assert np.array_equal(a, b)
    assert a.shape == (50, 3)
    assert (a[:, 0] >= 1).all() and (a[:, 0] <= 21 + 22).all()      # two distinct bars of day 0; day 1 never drawn
    T2 = np.ones((1, 23, 3))
    assert (S.twin_draws(T2, np.array([30]), 5, np.random.default_rng(0))[:, 0] == 23).all()   # capped at 23


def test_gate():
    good = dict(net=5000.0, trades=60, net_wo_top5=100.0, max_dd=10000.0, worst_month=-5000.0)
    assert S.gate(good, good, 0.01, 0.05) == []
    f = S.gate(dict(good, net=-1.0, trades=10, net_wo_top5=-5.0), dict(good, net=-1.0, max_dd=50000.0), 0.2, 0.5)
    assert len(f) == 7
    assert S.gate(good, dict(good, worst_month=-13000.0), 0.01, 0.10) == []
    assert S.gate(good, dict(good, worst_month=-13000.01), 0.01, 0.10) != []
