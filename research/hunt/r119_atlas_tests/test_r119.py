"""R119 tests: pricing arithmetic, fills and stale legs, each strategy's legs, the look-ahead guard, metrics.

    python3 -m pytest research/hunt/r119_atlas_tests -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r119cfg as K  # noqa: E402
import r119lib as L  # noqa: E402
import r119study as S  # noqa: E402
import r98data as R  # noqa: E402
import r54lib as LB  # noqa: E402
import r50lib as L50  # noqa: E402
import test_r118 as T118  # noqa: E402

LB.load_late()


def ctx_of(**kw):
    return L.DayCtx(T118.synthetic_day(**kw))


def test_round_trip_arithmetic_long_leg():
    c = ctx_of()
    k = c.k0(K.ROW_1200)
    a = c.day.opts[(k, "C")]
    a[K.ROW_1200 + 1, R.O] = 10.0
    a[K.ROW_1505, R.O] = 15.0
    tr = L.run_trade(c, "X", [L.Leg(k, "C", 1)], K.ROW_1200, K.ROW_1505)
    q = c.lot
    v5_in = L.R25.vol5_lots(a, K.ROW_1200 + 1, q)
    v5_out = L.R25.vol5_lots(a, K.ROW_1505, q)
    buy = L.R25.buy_px(10.0, v5_in, LB.late_mult(K.ROW_1200 + 1))
    sell = L.R25.sell_px(15.0, v5_out, LB.late_mult(K.ROW_1505))
    assert buy > 10.0 and sell < 15.0                                   # slippage both ways
    ch = L50.charge(True, buy, q, c.und, c.day.day) + L50.charge(False, sell, q, c.und, c.day.day)
    assert math.isclose(tr.gross, round((sell - buy) * q, 2), abs_tol=0.02)
    assert math.isclose(tr.net, round((sell - buy) * q - ch, 2), abs_tol=0.05)
    assert tr.stale == 0 and tr.margin == 0


def test_short_leg_sign_and_margin():
    c = ctx_of()
    k = c.k0(K.ROW_1200)
    a = c.day.opts[(k, "P")]
    a[K.ROW_1200 + 1, R.O] = 20.0
    a[K.ROW_1505, R.O] = 5.0
    tr = L.run_trade(c, "X", [L.Leg(k, "P", -1)], K.ROW_1200, K.ROW_1505)
    assert tr.gross > 0                                                  # sold high, bought back low
    assert tr.margin == round((K.MARGIN_PCT + K.ELM_PCT) * k * c.lot, 2)   # 2025 day: the ELM applies


def test_worthless_exit_pays_no_charges():
    c = ctx_of()
    k = max(kk for kk, sd in c.day.opts if sd == "C")              # the furthest OTM call stored
    a = c.day.opts[(k, "C")]
    a[K.ROW_1200 + 1, R.O] = 1.0
    a[K.ROW_1505:K.ROW_1505 + 3, R.O] = 0.0                              # no valid open: modelled
    a[:, R.C] = np.where(np.arange(R.N) >= K.ROW_1200 + 2, 0.01, a[:, R.C])
    tr = L.run_trade(c, "X", [L.Leg(k, "C", 1)], K.ROW_1200, K.ROW_1505)
    assert tr.charges == round(L50.charge(True, L.fill_leg(c, L.Leg(k, "C", 1), K.ROW_1200 + 1, True, 1.0, False)[0],
                                           c.lot, c.und, c.day.day), 2)


def test_fill_uses_first_open_within_three_bars_else_model():
    c = ctx_of()
    k = c.k0(K.ROW_1200)
    a = c.day.opts[(k, "C")]
    r = K.ROW_1200 + 1
    a[r, R.O] = np.nan
    a[r + 1, R.O] = 12.0
    p, rr, stale = L.fill_leg(c, L.Leg(k, "C", 1), r, True, 1.0, False)
    assert rr == r + 1 and not stale and p >= 12.0
    a[r:r + 3, R.O] = np.nan
    assert L.fill_leg(c, L.Leg(k, "C", 1), r, True, 1.0, False) is None    # entries need a real print
    f = L.fill_leg(c, L.Leg(k, "C", 1), r, True, 1.0, True)
    assert f is not None and f[2]                                        # exits may be modelled (stale)


def test_model_price_recovers_black76():
    c = ctx_of()
    k = c.k0(K.ROW_1200) + 100
    t = K.ROW_1200
    p = L.model_price(c, k, "C", t + 1)
    actual = c.day.opts[(k, "C")][t, R.C]
    assert p is not None and abs(p - actual) < max(0.15, 0.1 * actual)  # synthetic chain is Black-76 at 15%


def test_iron_fly_legs():
    c = ctx_of()
    tr = L.iron_fly(c, K.ROW_0930)
    k0 = c.k0(K.ROW_0930)
    w = min(max(round(K.WING * c.em[K.ROW_0930] / c.step), 1), 10) * c.step
    assert [(lg.k, lg.side, lg.sign) for lg in tr.legs] == [(k0, "C", -1), (k0, "P", -1), (k0 + w, "C", 1), (k0 - w, "P", 1)]
    assert tr.exit_row == K.ROW_1505


def test_longshot_picks_odds_nearest_3pct():
    c = ctx_of()
    legs = L.longshot_legs(c, K.ROW_1200)
    shorts = [lg for lg in legs if lg.sign < 0]
    assert len(legs) in (2, 4) and shorts
    for lg in shorts:
        p = L.p_itm(c, lg.k, lg.side, K.ROW_1200)
        others = [L.p_itm(c, k, s, K.ROW_1200) for (k, s) in c.day.opts if s == lg.side]
        cand = [x for x in others if x == x]
        assert abs(p - K.LONGSHOT_P) <= min(abs(x - K.LONGSHOT_P) for x in cand) + 1e-12 or c.close_ff(lg.k, lg.side)[K.ROW_1200] >= 1
    for i in range(0, len(legs), 2):
        s, lng = legs[i], legs[i + 1]
        assert lng.sign > 0 and abs(lng.k - s.k) == K.LONGSHOT_WING * c.step


def test_otm_call_strike():
    c = ctx_of()
    tr = L.otm_call(c, K.ROW_1200)
    k0 = c.k0(K.ROW_1200)
    assert tr.legs[0].side == "C" and tr.legs[0].k == k0 + max(round(K.CALL_DIST * c.em[K.ROW_1200] / c.step), 1) * c.step


def test_fade_signals_one_position_and_direction():
    c = ctx_of()
    t = K.FADE_MARKS[2]
    c.sp[t - 14:] += 200.0                                               # a jump up just before the mark
    sig = L.fade_signals(c)
    assert sig and sig[0] == (t, 1)
    tr = L.fade_trade(c, t, 1)
    assert tr.legs[0].side == "P" and tr.exit_row == t + 1 + K.FADE_HOLD and tr.extra["move"] == 1
    for (a, _), (b, _) in zip(sig, sig[1:]):
        assert b >= a + 1 + K.FADE_HOLD                                  # never two at once


def test_lookahead_guard_decisions_ignore_the_future():
    """Every strategy's decision (legs, signal rows) is unchanged when prices after the decision row change."""
    base = ctx_of(seed=5)
    for strat, t in (("P1", K.ROW_0930), ("P2", K.ROW_1200), ("P4", K.ROW_1200), ("P6", K.ROW_1200), ("P3", K.ROW_1500)):
        c2 = ctx_of(seed=5)
        rng = np.random.default_rng(3)
        for a in c2.day.opts.values():
            a[t + 1:, :4] *= rng.uniform(0.3, 3.0, (R.N - t - 1, 1))
        c2.sp[t + 1:] *= rng.uniform(0.98, 1.02, R.N - t - 1)
        a1 = L.trades_for(base, strat)
        a2 = L.trades_for(c2, strat)
        assert [[(lg.k, lg.side, lg.sign) for lg in x.legs] for x in a1] == \
               [[(lg.k, lg.side, lg.sign) for lg in x.legs] for x in a2], strat
    c1, c2 = ctx_of(seed=7), ctx_of(seed=7)
    cut = K.FADE_MARKS[6]
    c2.sp[cut + 1:] += 300.0
    s1 = [x for x in L.fade_signals(c1) if x[0] <= cut]
    s2 = [x for x in L.fade_signals(c2) if x[0] <= cut]
    assert s1 == s2


def test_twin_options_shapes():
    c = ctx_of()
    tr = L.trades_for(c, "P2")
    opts = L.twin_options(c, "P2", tr)
    assert len(opts) == len(tr) and len(opts[0]) > 100
    t = K.FADE_MARKS[2]
    c.sp[t - 14:] += 200.0
    f = L.trades_for(c, "P5")
    o = L.twin_options(c, "P5", f)
    assert len(o) == len(f) and all(len(x) == 2 for x in o)


def test_metrics():
    tr = []
    for i, n in enumerate([1000, -15000, 200, 3000, -500]):
        x = L.Trade("P1", "NIFTY", date(2026, 1 + i // 3, 1 + i), 0, 1, [])
        x.net = n
        tr.append(x)
    m = S.metrics(tr)
    assert m["trades"] == 5 and m["total"] == -11300 and m["worst_day"] == -15000
    assert m["months_at_stop"] == 1 and m["ex3"] == -11300 - 3000 - 1000 - 200
    assert m["max_dd"] == 15000
    assert S.metrics([]) == dict(trades=0)


def test_six_strategies_and_periods():
    assert list(K.STRATS) == ["P1", "P2", "P3", "P4", "P5", "P6"]
    assert K.period(date(2023, 12, 31)) == "design" and K.period(date(2026, 1, 1)) == "final"
    assert "NON-DEFAULT" in K.STRATS["P4"]["source"]
