"""R202 / R203 tests (PREREG.md section 8): synthetic data only, no lake, no engine. Plain-English notes, 20 Nov 2026."""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r202cfg as K  # noqa: E402
import r202lib as G  # noqa: E402

N = K.NROWS


def day_idx(seed: int = 0, drift: float = 0.0, start: float = 25000.0) -> np.ndarray:
    """A (4, 375) index day: a random walk with a drift a minute."""
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(drift, 3.0, N))
    o = np.concatenate([[start], c[:-1]])
    h = np.maximum(o, c) + rng.uniform(0, 2, N)
    l = np.minimum(o, c) - rng.uniform(0, 2, N)
    return np.vstack([o, h, l, c])


def flat_then(level: float, after: int, value: float) -> np.ndarray:
    """An index day flat at `level`, then flat at `value` from minute `after`."""
    c = np.full(N, level)
    c[after:] = value
    return np.vstack([c, c, c, c]).astype(float)


def noise_after(idx: np.ndarray, r: int, seed: int = 9) -> np.ndarray:
    out = idx.copy()
    out[:, r:] = np.random.default_rng(seed).normal(1e5, 5e3, (4, N - r))
    return out


def trade(day, er, xr, net, und="NIFTY", side="C", **kw):
    return dict(day=day, er_sig=er, er=er, xr=xr, net=net, und=und, side=side, **kw)


# ================================================================================================ bars and indicators
def test_bars_gaps_and_closed_market():
    idx = day_idx(1)
    idx[:, 5:10] = np.nan                      # a whole bar missing (market closed / data gap)
    idx[:, 12] = np.nan                        # a gap inside a bar
    b = G.bars(idx, 5)
    assert b.shape == (4, 75)
    assert np.isnan(b[:, 1]).all()
    assert b[0, 2] == idx[0, 10] and b[3, 2] == idx[3, 14]
    assert b[1, 2] == np.nanmax(idx[1, 10:15]) and b[2, 2] == np.nanmin(idx[2, 10:15])
    assert G.bars(idx, 15).shape == (4, 25)
    assert np.isnan(G.bars(np.full((4, N), np.nan), 5)).all()


def test_twap_is_running_mean_and_causal():
    idx = day_idx(2)
    t = G.twap(idx)
    tp = (idx[1] + idx[2] + idx[3]) / 3
    assert t[0] == pytest.approx(tp[0]) and t[99] == pytest.approx(tp[:100].mean())
    assert np.array_equal(t[:100], G.twap(noise_after(idx, 100))[:100])
    idx[:, :3] = np.nan
    assert np.isnan(G.twap(idx)[:3]).all() and G.twap(idx)[3] == pytest.approx(tp[3])


def test_ema_carries_gaps_and_is_causal():
    x = np.array([1.0, np.nan, 3.0, 5.0])
    e = G.ema(x, 3)
    assert e[0] == 1.0 and e[1] == 1.0 and e[2] == pytest.approx(2.0) and e[3] == pytest.approx(3.5)
    y = np.random.default_rng(3).normal(100, 1, 300)
    z = y.copy()
    z[200:] = 1e6
    assert np.array_equal(G.ema(y, 20)[:200], G.ema(z, 20)[:200])
    assert np.isnan(G.ema(np.array([np.nan, np.nan]), 5)).all()


def test_supertrend_flips_and_is_causal():
    up = np.linspace(100, 160, 60)
    down = np.linspace(160, 100, 60)
    c = np.concatenate([up, down])
    d = G.supertrend_dir(c + 0.5, c - 0.5, c)
    assert (d[:K.ST_N - 1] == 0).all()                       # unknown until ATR has 10 bars
    assert d[50] == 1 and d[-1] == -1
    c2 = c.copy()
    c2[80:] = 500.0
    assert np.array_equal(d[:80], G.supertrend_dir(c2 + 0.5, c2 - 0.5, c2)[:80])
    gap = c.copy()
    gap[30:35] = np.nan                                      # a holiday-like hole: the direction is carried
    dg = G.supertrend_dir(gap + 0.5, gap - 0.5, gap)
    assert (dg[30:35] == dg[29]).all()


# ================================================================================================ exit rows
def test_first_bar_row_after_earlier():
    assert G.first_bar(30) == 6 and G.first_bar(31) == 7 and G.first_bar(0) == 0
    assert G.row_after(6) == 35 and G.row_after(74) == -1 and G.row_after(24, 15) == -1 and G.row_after(3, 15) == 60
    assert G.earlier(-1, 40) == 40 and G.earlier(31, -1) == 31 and G.earlier(31, 40) == 31 and G.earlier(None, -1) == -1


def test_wrong_side_and_nan():
    assert G.wrong(99.0, 100.0, "C") and not G.wrong(101.0, 100.0, "C")
    assert G.wrong(101.0, 100.0, "P") and not G.wrong(99.0, 100.0, "P")
    assert not G.wrong(np.nan, 100.0, "C") and not G.wrong(99.0, np.nan, "P")


def test_x4_two_closes_through_entry_level():
    idx = flat_then(100.0, 50, 99.0)               # entry at minute 30 (bar 6); falls from minute 50 (bar 10)
    b5 = G.bars(idx)
    assert G.x4_row(idx, b5, 30, "C") == 60        # bars 10 and 11 close below 100 -> sold at minute 60
    assert G.x4_row(idx, b5, 30, "P") == -1        # a put is not hurt by a fall
    one = flat_then(100.0, 50, 99.0)
    one[:, 55:] = 100.0                            # only one wrong bar: no exit
    assert G.x4_row(one, G.bars(one), 30, "C") == -1
    assert G.x4_row(idx, b5, 372, "C") == -1       # too late for any bar to complete
    idx2 = idx.copy()
    idx2[0, 30] = np.nan                           # the entry minute is missing: the last close before it
    assert G.entry_level(idx2, 30) == 100.0
    assert math.isnan(G.entry_level(np.full((4, N), np.nan), 30))
    assert G.x4_row(np.full((4, N), np.nan), G.bars(np.full((4, N), np.nan)), 30, "C") == -1


def test_bar_before_entry_never_counts():
    idx = flat_then(100.0, 20, 99.0)               # already below at entry (minute 30): bars 6 and 7 are the first two
    idx[0, 30] = 100.0
    assert G.x4_row(idx, G.bars(idx), 30, "C") == 40
    w = np.zeros(75, bool)
    w[4:6] = True                                  # two wrong bars, both before the entry bar
    assert G.two_wrong_bar(w, 6) == -1
    w[6] = True                                    # bar 5 (before entry) + bar 6: still not two bars after entry
    assert G.two_wrong_bar(w, 6) == -1
    w[7] = True
    assert G.two_wrong_bar(w, 6) == 7


def test_flip_row_only_after_entry():
    d = np.ones(75, np.int8)
    d[20:] = -1
    assert G.flip_row(d, 1, 30, "C") == 105        # flip at bar 20 -> minute 105
    assert G.flip_row(d, 1, 30, "P") == -1
    assert G.flip_row(d, 1, 110, "C") == -1        # already against at entry: no flip after it
    d0 = -np.ones(75, np.int8)
    assert G.flip_row(d0, 1, 0, "C") == 5          # the flip is on the day's first bar (the session before was up)
    assert G.flip_row(np.zeros(75, np.int8), 0, 0, "C") == -1


def test_breadth_row_needs_four_of_five_twice():
    w = np.zeros((5, 75), bool)
    w[:4, 10:12] = True
    assert G.breadth_row(list(w), 30) == 60
    w[3, 11] = False                               # only 3 on the second bar
    assert G.breadth_row(list(w), 30) == -1
    assert G.breadth_row(list(w[:3]), 30) == -1    # an index missing that day cannot make up the four


def test_market_row_needs_five_minutes_after_entry():
    c = np.full(25, 99.0)
    e = np.full(25, 100.0)
    assert G.market_row(c, e, 30, "C") == 45       # bar 2 (minutes 30-44) closes 14 minutes after entry
    assert G.market_row(c, e, 41, "C") == 60       # bar 2 closes at minute 44 < 41 + 4: the next bar
    assert G.market_row(c, e, 40, "C") == 45
    assert G.market_row(c, e, 30, "P") == -1
    assert G.market_row(c, e, 372, "C") == -1


def test_opposite_row():
    sig = [(30, "C"), (45, "P"), (60, "P"), (20, "P")]
    assert G.opposite_row(sig, 30, "C") == 45
    assert G.opposite_row(sig, 45, "P") == -1
    assert G.opposite_row(sig, 60, "C") == -1 and G.opposite_row([], 10, "C") == -1


@pytest.mark.parametrize("seed", range(4))
def test_look_ahead_guard_exit_rows(seed):
    """Every exit row is unchanged when the minutes from the sale minute on are replaced by noise."""
    idx = day_idx(seed, drift=-0.4)
    er, side = 30, "C"

    def rows(a):
        b5 = G.bars(a)
        b15 = G.bars(a, 15)
        return dict(x4=G.x4_row(a, b5, er, side),
                    x5=G.two_wrong_row(b5[3], G.ema(b5[3], K.EMA_N), er, side),
                    x6=G.two_wrong_row(b5[3], G.twap(a)[4::5], er, side),
                    x7=G.flip_row(G.supertrend_dir(b5[1], b5[2], b5[3]), 1, er, side),
                    x8=G.breadth_row([G.wrong(b5[3], G.twap(a)[4::5], side)] * 5, er),
                    x9=G.market_row(b15[3], G.ema(b15[3], K.EMA_N), er, side))

    base = rows(idx)
    assert any(v >= 0 for v in base.values())
    for name, r in base.items():
        if r >= 0:
            assert rows(noise_after(idx, r, seed + 50))[name] == r, name
            assert r >= er + 5, name               # never sooner than 5 minutes after the entry minute


def test_trend_strength_reads_only_before_entry():
    idx = day_idx(5, drift=1.0)
    x = G.trend_strength(idx, 60, "C")
    assert x == pytest.approx(idx[3, 59] / idx[0, 0] - 1.0)
    assert G.trend_strength(idx, 60, "P") == pytest.approx(-x)
    assert G.trend_strength(noise_after(idx, 60), 60, "C") == x
    assert math.isnan(G.trend_strength(idx, 0, "C"))
    assert math.isnan(G.trend_strength(np.full((4, N), np.nan), 60, "C"))


# ================================================================================================ buckets and skips
def test_buckets_and_unknown():
    t = trade(date(2025, 6, 2), 5, 20, 0.0, f_vixreg="HI", f_gap=-0.003, f_rv=0.7, f_trend=0.004)
    assert G.b_hour(t) == 9 and G.b_hour(dict(t, er_sig=45)) == 10 and G.b_hour(dict(t, er_sig=350)) == 14
    assert G.b_vix(t) == "HI" and G.b_vix(dict(t, f_vixreg=None)) is None
    assert G.b_gap(t) == "0.25-0.75%" and G.b_gap(dict(t, f_gap=0.001)) == "under 0.25%"
    assert G.b_gap(dict(t, f_gap=0.02)) == "0.75% or more" and G.b_gap(dict(t, f_gap=float("nan"))) is None
    assert G.b_dow(t) == 0 and G.b_rv(t) == "high" and G.b_rv(dict(t, f_rv=None)) is None
    assert G.b_trend(t) == "0.3-0.6%" and G.b_trend(dict(t, f_trend=-0.1)) == "under 0"
    assert G.b_trend(dict(t, f_trend=float("nan"))) is None and G.b_und(t) == "NIFTY"


def _wf_trades():
    out = []
    d = date(2025, 1, 1)
    for i in range(200):                           # before the first quarter: NIFTY loses, SENSEX wins
        out.append(trade(d + timedelta(days=i % 80), 30, 40, -100.0, und="NIFTY"))
        out.append(trade(d + timedelta(days=i % 80), 30, 40, +100.0, und="SENSEX"))
    for i in range(30):                            # in the first quarter
        out.append(trade(date(2025, 4, 1) + timedelta(days=i), 30, 40, -50.0, und="NIFTY"))
        out.append(trade(date(2025, 4, 1) + timedelta(days=i), 30, 40, -500.0, und="SENSEX"))
    return out


def test_wf_plan_uses_only_earlier_trades():
    tr = _wf_trades()
    plan = G.wf_plan(tr, G.b_und)
    assert plan[date(2025, 4, 1)] == "NIFTY"       # chosen from Jan-Mar only
    assert plan[date(2025, 7, 1)] == "NIFTY"       # NIFTY mean -93, SENSEX mean +22: still NIFTY
    keep = G.wf_keep(tr, plan, G.b_und)
    assert all(k for t, k in zip(tr, keep) if t["day"] < date(2025, 4, 1))          # nothing skipped before Q1
    assert not any(k for t, k in zip(tr, keep) if t["day"] >= date(2025, 4, 1) and t["und"] == "NIFTY")
    # look-ahead guard: changing every trade on or after a quarter's first day never changes that quarter's skip
    tr2 = [dict(t, net=+9999.0) if t["day"] >= date(2025, 4, 1) else t for t in tr]
    assert G.wf_plan(tr2, G.b_und)[date(2025, 4, 1)] == plan[date(2025, 4, 1)]


def test_wf_plan_min_n_none_and_unknown():
    d = date(2025, 1, 1)
    few = [trade(d, 30, 40, -100.0, und="NIFTY") for _ in range(K.WF_MIN_N - 1)]
    assert G.wf_plan(few, G.b_und)[date(2025, 4, 1)] is None                         # too few trades
    good = [trade(d, 30, 40, +1.0, und="NIFTY") for _ in range(100)]
    assert G.wf_plan(good, G.b_und)[date(2025, 4, 1)] is None                        # no losing bucket
    unk = [trade(d, 30, 40, -100.0, f_vixreg=None) for _ in range(100)]
    assert G.wf_plan(unk, G.b_vix)[date(2025, 4, 1)] is None                         # unknown is never skipped
    assert G.wf_keep(unk, {date(2025, 4, 1): None}, G.b_vix).all()
    assert G.quarter_of(date(2025, 3, 31)) is None and G.quarter_of(date(2026, 10, 5)) == date(2026, 10, 1)


def test_day_cap_counts_only_closed_trades():
    d = date(2025, 6, 2)
    tr = [trade(d, 10, 30, -2000.0), trade(d, 20, 60, -2000.0, und="SENSEX"),       # second enters before the first closes
          trade(d, 40, 70, -500.0), trade(d, 65, 90, +100.0), trade(d, 75, 95, +100.0),
          trade(d + timedelta(days=1), 10, 20, +1.0)]
    keep = G.day_cap_keep(tr, 3000.0)
    # at 40 only -2,000 is closed (kept); at 65 -4,000 closed -> removed; at 75 -4,500 closed -> removed; next day resets
    assert keep.tolist() == [True, True, True, False, False, True]
    assert G.day_cap_keep(tr, 5000.0).all()
    assert G.day_cap_keep([], 3000.0).tolist() == []


def test_day_cap_removed_trade_does_not_count():
    d = date(2025, 6, 2)
    tr = [trade(d, 10, 20, -3500.0), trade(d, 30, 40, -9000.0), trade(d, 50, 60, +10.0)]
    assert G.day_cap_keep(tr, 3000.0).tolist() == [True, False, False]


def test_two_losers_rule():
    d = date(2025, 6, 2)
    tr = [trade(d, 10, 20, -10.0), trade(d, 25, 35, -10.0), trade(d, 40, 50, +5.0),          # third removed
          trade(d, 12, 22, -10.0, und="SENSEX"), trade(d, 30, 60, -10.0, und="SENSEX"),
          trade(d, 45, 55, -10.0, und="SENSEX"),                                              # second still open: kept
          trade(d, 70, 80, +1.0, und="SENSEX")]                                               # last two closed lost: removed
    assert G.two_losers_keep(tr).tolist() == [True, True, False, True, True, True, False]
    win = [trade(d, 10, 20, -10.0), trade(d, 25, 35, +10.0), trade(d, 40, 50, -5.0), trade(d, 60, 70, -5.0)]
    assert G.two_losers_keep(win).all()


# ================================================================================================ windows, stats, gate
def test_windows_and_folds():
    assert K.DESIGN[1] < K.HOLDOUT[0] and K.HOLDOUT[1] == K.SPAN[1] == K.STORE[2]
    assert all(K.DESIGN[0] <= a <= b <= K.DESIGN[1] for a, b in K.FOLDS)
    assert len(K.TESTS) == 60 and K.M_TOTAL == 4868
    assert set(K.EXIT_RULES) | set(K.SKIP_RULES) == set(K.RULES) and not set(K.EXIT_RULES) & set(K.SKIP_RULES)
    assert sum(1 for r in K.RULES if K.STUDY[r] == "R202") == 9
    tr = [trade(date(2026, 3, 31), 1, 2, 1.0), trade(date(2026, 4, 1), 1, 2, 1.0)]
    assert len(G.in_window(tr, *K.DESIGN)) == 1 and len(G.in_window(tr, *K.HOLDOUT)) == 1


def test_stats():
    tr = [trade(date(2026, 1, 5), 1, 2, 1000.0), trade(date(2026, 1, 6), 1, 2, -400.0),
          trade(date(2026, 2, 3), 1, 2, -900.0), trade(date(2026, 2, 4), 1, 2, 300.0)]
    s = G.stats(tr, date(2026, 1, 1), date(2026, 3, 31))
    assert s["trades"] == 4 and s["win"] == 50.0 and s["net"] == 0 and s["avg_loss"] == -650 and s["avg_win"] == 650
    assert s["max_dd"] == 1300 and s["worst_month"] == dict(month="2026-02", net=-600) and s["worst_day"] == -900
    assert s["losers"] == 2 and s["loss_total"] == -1300
    e = G.stats([], date(2026, 1, 1), date(2026, 3, 31))
    assert e["trades"] == 0 and e["net"] == 0 and e["max_dd"] == 0
    assert G.months_between(date(2025, 11, 24), date(2026, 1, 2)) == ("2025-11", "2025-12", "2026-01")


def _s(net, dd, wm):
    return dict(net=net, max_dd=dd, worst_month=dict(month="m", net=wm), trades=100)


def test_better_rule():
    b = _s(100000, 50000, -20000)
    assert G.better(_s(100001, 99999, -99999), b)
    assert G.better(_s(96000, 40000, -16000), b)               # within 5%, both 20% smaller
    assert not G.better(_s(94000, 10000, -1000), b)            # more than 5% under
    assert not G.better(_s(96000, 41000, -16000), b)           # drawdown not 20% smaller
    assert not G.better(_s(96000, 40000, -17000), b)           # worst month not 20% smaller
    assert not G.better(_s(-5, 0, 0), _s(-1, 10, -1))          # B at or under 0: only a higher net counts
    assert G.better(_s(5, 0, 0), _s(-1, 10, -1))


def _gate_inputs():
    b = dict(design=_s(100000, 50000, -20000), holdout=_s(50000, 30000, -10000))
    v = dict(design=_s(110000, 40000, -15000), holdout=_s(60000, 20000, -8000))
    fb = [_s(1000, 500, -100)] * 4
    fv = [_s(1100, 400, -100)] * 4
    return v, b, fv, fb, dict(design=80, holdout=50)


def test_gate_passes_and_each_item_fails_alone():
    v, b, fv, fb, ch = _gate_inputs()
    assert G.gate(v, b, fv, fb, ch, 0.01, 0.05)["passes"]
    cases = {
        "design: net": (dict(v, design=_s(90000, 40000, -15000)), b, fv, fb, ch, 0.01, 0.05),
        "holdout: net": (dict(v, holdout=_s(40000, 20000, -8000)), b, fv, fb, ch, 0.01, 0.05),
        "design: max drawdown": (dict(v, design=_s(110000, 60000, -15000)), b, fv, fb, ch, 0.01, 0.05),
        "holdout: 39 trades": (v, b, fv, fb, dict(design=80, holdout=39), 0.01, 0.05),
        "walk-forward": (v, b, [_s(900, 400, -100)] * 2 + fv[:2], fb, ch, 0.01, 0.05),
        "holdout twin p": (v, b, fv, fb, ch, 0.05, 0.05),
        "BH q": (v, b, fv, fb, ch, 0.01, 0.11),
    }
    for item, args in cases.items():
        g = G.gate(*args)
        assert not g["passes"] and len(g["fails"]) == 1 and item in g["fails"][0], (item, g["fails"])
    g = G.gate(v, b, fv, fb, ch, None, None)
    assert not g["passes"] and len(g["fails"]) == 2


def test_twin_p_normal_p_skip_twins_bh():
    tw = np.arange(100.0)
    assert G.twin_p(99.5, tw) == pytest.approx(1 / 101) and G.twin_p(-1.0, tw) == 1.0
    assert G.normal_p(49.5, tw) == pytest.approx(0.5) and G.normal_p(1e6, tw) < 1e-12
    assert G.normal_p(5.0, np.full(10, 3.0)) == 0.0 and G.normal_p(3.0, np.full(10, 3.0)) == 1.0
    nets = np.array([10.0, -5.0, 3.0, -8.0])
    rng = np.random.default_rng(1)
    t = G.skip_twins(nets, 2, 500, rng)
    assert t.min() >= nets.sum() - 13.0 - 1e-9 and t.max() <= nets.sum() + 13.0 + 1e-9 and len(set(np.round(t, 6))) > 1
    assert (G.skip_twins(nets, 0, 5, rng) == nets.sum()).all()
    assert (G.skip_twins(nets, 9, 5, rng) == 0.0).all()        # more than there are: all removed
    q = G.bh_total([1e-9, 0.5], 4868)
    assert q[0] == pytest.approx(1e-9 * 4868) and q[1] == 1.0
    assert G.bh_total([0.01, 0.02], 2) == pytest.approx([0.02, 0.02])


def test_no_trade_changed_means_no_twin_test():
    assert G.p_for_bh(0, 0.0) == 1.0 and G.p_for_bh(0, 0.3) == 1.0      # Amendment A
    assert G.p_for_bh(5, 0.0) == 0.0 and G.p_for_bh(41, 0.02) == 0.02
