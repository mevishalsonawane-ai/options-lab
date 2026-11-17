"""R193 tests (PREREG.md section 8, synthetic, no data): the look-ahead guard, FB-R72-A10 (holidays, too little history,
missing minutes), windows, statistics, funded lots, twins, BH, the gate, and engine edge cases through R186's simulator
(expiry-day 15:05 square-off, a 15:20 square-off, missing minutes, a stop and a lock in the same minute, a lock that only
rises).

    python -m pytest research/hunt/r193_arms_nov24 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r193cfg as K  # noqa: E402
import r193lib as G  # noqa: E402


# ================================================================================================ look-ahead guard
def _ranges(n=60, seed=0):
    rng = np.random.default_rng(seed)
    lo = 100 + np.cumsum(rng.normal(0, 1, n))
    hi = lo + rng.uniform(0.5, 3.0, n)
    return hi, lo


def test_a10_ignores_today_and_later_sessions():
    hi, lo = _ranges()
    a = G.a10_series(hi, lo)
    rng = np.random.default_rng(9)
    for d in (25, 40, 59):
        h2, l2 = hi.copy(), lo.copy()
        h2[d:] = rng.normal(5000, 100, len(h2) - d)          # today and the future replaced by noise
        l2[d:] = h2[d:] - rng.uniform(0, 500, len(h2) - d)
        b = G.a10_series(h2, l2)
        assert np.array_equal(a[:d + 1], b[:d + 1], equal_nan=True)


def test_a10_value_is_yesterday_over_20_session_mean():
    hi, lo = _ranges()
    a = G.a10_series(hi, lo)
    r = hi - lo
    for d in (20, 33, 59):
        assert a[d] == pytest.approx(r[d - 1] / r[d - 20:d].mean())
    assert np.isnan(a[:20]).all()                              # fewer than 20 earlier sessions: unknown


def test_a10_holiday_is_just_the_previous_session():
    """A holiday is not a session: the day after it reads the last trading session as 'yesterday'."""
    hi, lo = _ranges(40)
    with_holiday_removed = G.a10_series(np.delete(hi, 30), np.delete(lo, 30))
    r = np.delete(hi - lo, 30)
    assert with_holiday_removed[30] == pytest.approx(r[29] / r[10:30].mean())


def test_a10_missing_days_and_too_little_history():
    hi, lo = _ranges(40)
    hi[5:16] = np.nan                                          # 11 sessions with no index minutes
    a = G.a10_series(hi, lo)
    assert np.isnan(a[25])                                     # window 5..24 has only 9 known ranges (< 10)
    assert np.isfinite(a[35])
    hi2, lo2 = _ranges(40)
    hi2[29] = np.nan                                           # yesterday unknown
    assert np.isnan(G.a10_series(hi2, lo2)[30])
    assert not G.a10_skip(np.nan) and not G.a10_skip(None)     # unknown never skips
    assert G.a10_skip(K.A10_CUT) and not G.a10_skip(K.A10_CUT - 1e-9)


# ================================================================================================ windows / stats
def test_windows_split():
    assert K.window_of(date(2024, 10, 31)) == ""
    assert K.window_of(date(2024, 11, 1)) == "W1"
    assert K.window_of(date(2026, 3, 31)) == "W1"
    assert K.window_of(date(2026, 4, 1)) == "W2"
    assert K.window_of(date(2026, 11, 13)) == "W2"
    assert K.window_of(date(2026, 11, 16)) == ""
    tr = [dict(day=date(2026, 3, 31), net=1.0), dict(day=date(2026, 4, 1), net=2.0)]
    assert [t["net"] for t in G.in_window(tr, "W1")] == [1.0] and len(G.in_window(tr, "total")) == 2


def test_stats_drawdown_months_top5():
    d = date
    tr = [dict(day=d(2025, 1, 2), net=1000.0), dict(day=d(2025, 1, 2), net=-300.0), dict(day=d(2025, 1, 3), net=-2000.0),
          dict(day=d(2025, 3, 3), net=500.0), dict(day=d(2025, 3, 4), net=4000.0)]
    s = G.stats(tr, d(2025, 1, 1), d(2025, 3, 31))
    assert s["trades"] == 5 and s["net"] == 3200 and s["win"] == 60.0
    assert s["max_dd"] == 2000                                # day equity 700 -> -1300
    assert s["months"]["2025-02"] == 0 and s["worst_month"] == dict(month="2025-01", net=-1300)
    assert s["net_wo_top5"] == 0                              # only 5 trades: all of them are the top 5
    assert s["avg_win"] == round((1000 + 500 + 4000) / 3) and s["avg_loss"] == round((-300 - 2000) / 2)
    assert s["account_stop_ok"]
    bad = G.stats([dict(day=d(2025, 1, 2), net=-13001.0)], d(2025, 1, 1), d(2025, 1, 31))
    assert not bad["account_stop_ok"]
    e = G.stats([], d(2025, 1, 1), d(2025, 2, 28))
    assert e["trades"] == 0 and e["net"] == 0 and e["max_dd"] == 0


# ================================================================================================ funded lots
def test_funded_lots():
    assert G.funded_lots(100.0, 75) == 3                       # 7 lots fit, capped at 3
    assert G.funded_lots(300.0, 75) == 2                       # Rs 22,500 a lot
    assert G.funded_lots(560.0, 100) == 1                      # exactly Rs 56,000
    assert G.funded_lots(800.0, 75) == 0                       # not even one lot: skipped
    assert G.funded_lots(float("nan"), 75) == 0 and G.funded_lots(0.0, 75) == 0


# ================================================================================================ twins, BH, gate
def test_twin_p_and_normal_p():
    tw = np.arange(100, dtype=float)
    assert G.twin_p(1000.0, tw) == pytest.approx(1 / 101)
    assert G.twin_p(-1.0, tw) == pytest.approx(1.0)
    assert G.normal_p(float(tw.mean()), tw) == pytest.approx(0.5)
    assert G.normal_p(1000.0, tw) < 1e-6
    assert G.normal_p(5.0, np.full(10, 1.0)) == 0.0 and G.normal_p(0.0, np.full(10, 1.0)) == 1.0


def test_random_skip_mask():
    rng = np.random.default_rng(1)
    m = G.random_skip_mask(50, 12, rng)
    assert m.sum() == 38
    assert G.random_skip_mask(5, 0, rng).all()
    assert not G.random_skip_mask(3, 9, rng).any()


def test_bh_counts_earlier_entries():
    q = G.bh_total([1e-7, 0.5], m_total=K.M_TOTAL)
    assert q[0] == pytest.approx(1e-7 * K.M_TOTAL) and q[1] == 1.0
    assert K.M_TOTAL == 4755 + 10 == 4765 and len(K.TESTS) == 10


def _w(net, dd, n=100, top=None):
    return dict(net=net, max_dd=dd, trades=n, net_wo_top5=net if top is None else top)


def _pair():
    base = {w: _w(1000, 5000) for w in ("W1", "W2", "total")}
    var = {w: _w(2000, 4000) for w in ("W1", "W2", "total")}
    return var, base


def test_gate_passes_and_each_item_fails_alone():
    var, base = _pair()
    assert G.gate(var, base, 0.01, 0.05)["helps"]
    for w in ("W1", "W2"):
        v, b = _pair()
        v[w]["net"] = 1000                                     # equal net is not higher
        assert not G.gate(v, b, 0.01, 0.05)["helps"]
        v, b = _pair()
        v[w]["max_dd"] = 5000                                  # equal drawdown is not lower
        assert not G.gate(v, b, 0.01, 0.05)["helps"]
        v, b = _pair()
        v[w]["trades"] = 39
        assert not G.gate(v, b, 0.01, 0.05)["helps"]
    v, b = _pair()
    v["total"]["net_wo_top5"] = 1000
    assert not G.gate(v, b, 0.01, 0.05)["helps"]
    v, b = _pair()
    assert not G.gate(v, b, 0.05, 0.05)["helps"]               # p must be below 0.05
    assert not G.gate(v, b, 0.01, 0.11)["helps"]
    assert G.gate(v, b, 0.01, 0.10)["helps"]                   # q <= 0.10 passes


# ================================================================================================ engine edge cases
sys.path.insert(0, str(K.HUNT / "r186_fixed_profit"))
T = pytest.importorskip("r186study")
T.R7.mods()                                                    # resolves the R176 / R106 / R51 paths (no data read)
import r186engine as Z  # noqa: E402
import r106engine as RX  # noqa: E402
import engine as E  # noqa: E402
import r51cfg as K51  # noqa: E402

QTY = 65.0
DAY = date(2026, 5, 4)


def _ch(a, b):
    return float(E.charges("NIFTY", np.array([a]), np.array([b]), QTY, DAY)[0])


def _flat(px=100.0, n=375):
    C = np.full((5, n), px)
    C[4] = 1e7
    return C


def _sim(C, sq=360, mode="", be=True, stop=0.15):
    raw, real, ef, tks = RX.entry_info(C, 10, sq, QTY)
    lkt, lkk = -1.0, 0.0
    if be:
        s0 = RX.stop_price(real, QTY, 0.0, _ch, tks, E.SLIP[2], E.SLIP[3])
        lkt, lkk = 0.10 * raw * QTY * (1 - 1e-12), (s0 - raw) * QTY
    rt, rl = Z.lock_levels(mode, real, QTY, 1, _ch, tks, float(np.nanmax(C[1][ef:sq])))
    ex = E.Exits(stop=stop, target=-1.0, tmax=0, sq_row=sq)
    r = Z.simulate(C, 10, sq, -1, stop, lkt, lkk, 0.0, ex.steps_arr(), 0, -1.0, -1.0, 0.05, QTY, 0.0, -1.0, rt, rl,
                   Z.NONE, np.nan, -1.0, 1.0, *E.SLIP)
    return real, rt, rl, r


class _Day:
    def __init__(self, expiry):
        self.expiry = expiry


def test_expiry_day_squares_off_at_1505():
    ex = E.Exits(stop=0.15, target=-1.0, tmax=0, sq_row=K51.row(15, 10))
    assert E.sq_row(_Day(True), ex) == K51.row(15, 5) == K51.SQ_ROW_EXPIRY
    assert E.sq_row(_Day(False), ex) == K51.row(15, 10)


@pytest.mark.parametrize("hhmm", [(15, 5), (15, 20)])
def test_trade_open_at_square_off_is_sold_there(hhmm):
    sq = K51.row(*hhmm)
    _, _, _, r = _sim(_flat(), sq=sq, mode="L500")
    assert r[6] == E.R_SQ and r[5] == sq                       # sold at the square-off minute, never later


def test_missing_minutes_do_not_fire_a_stop():
    C = _flat()
    C[:4, 30:60] = np.nan                                      # 30 minutes with no print
    C[2, 70] = 80.0                                            # then a real -20% low
    _, _, _, r = _sim(C, be=False)
    assert r[6] == E.R_STOP and r[5] == 70


def test_stop_and_lock_trigger_in_the_same_minute_takes_the_stop():
    C = _flat()
    real, rt, rl, _ = _sim(_flat(), mode="L500", be=False)
    C[1, 20] = rt[0] + 1.0                                     # the +Rs 1,000 trigger is touched ...
    C[2, 20] = 80.0                                            # ... in the minute the -15% stop is hit
    _, _, _, r = _sim(C, mode="L500", be=False)
    assert r[6] == E.R_STOP and r[5] == 20


def test_lock_only_rises():
    C = _flat()
    C[1, 15] = 160.0                                           # the best: several levels reached in minute 15
    C[:4, 16:21] = 150.0                                       # lower later minutes never lower the stop
    real, rt, rl, _ = _sim(C, mode="L500", be=False)
    j = int(np.searchsorted(rt, 160.0, side="right")) - 1      # the highest level reached
    assert j >= 1
    C[2, 20] = rl[j] - 0.5                                     # minute 20 trades through that level's stop
    _, _, _, r = _sim(C, mode="L500", be=False)
    assert r[6] == Z.R_RLOCK and r[5] == 20 and r[1] == pytest.approx(rl[j])
