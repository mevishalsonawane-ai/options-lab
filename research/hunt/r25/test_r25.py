"""R25 unit tests on synthetic days (no lake files needed): python3 -m pytest research/hunt/r25/test_r25.py -q"""
from __future__ import annotations

import math
import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r25cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r25rules as RU  # noqa: E402
from r25data import C, H, IV, L, N, O, OI, V, Day, minute  # noqa: E402

STEP = 50.0


def _bars(px: np.ndarray, vol: float = 5000.0, oi: float = 1e5) -> np.ndarray:
    a = np.full((N, 7), np.nan)
    a[:, O] = np.r_[px[0], px[:-1]]
    a[:, C] = px
    a[:, H] = np.maximum(a[:, O], a[:, C])
    a[:, L] = np.minimum(a[:, O], a[:, C])
    a[:, V] = vol
    a[:, OI] = oi
    a[:, IV] = 20.0
    return a


def _opt(spot: np.ndarray, k: float, side: str) -> np.ndarray:
    rem = np.linspace(1.0, 0.02, N)
    tv = 40 * rem * np.exp(-np.abs(spot - k) / 60.0)
    intr = np.maximum(spot - k, 0) if side == "C" else np.maximum(k - spot, 0)
    return np.round(np.maximum(intr + tv, 0.05) / 0.05) * 0.05


def make_day(spot: np.ndarray, und="NIFTY", d=date(2024, 6, 6), lot=25, width=10) -> Day:
    ix = np.c_[np.r_[spot[0], spot[:-1]], np.maximum(spot, np.r_[spot[0], spot[:-1]]),
               np.minimum(spot, np.r_[spot[0], spot[:-1]]), spot]
    base = round(spot[0] / STEP) * STEP
    opts = {}
    for i in range(-width - 6, width + 7):
        k = float(base + i * STEP)
        for s in ("C", "P"):
            opts[(k, s)] = _bars(_opt(spot, k, s))
    # keep each strike only while within ATM +- width of the minute (the lake's rolling range)
    edge = np.full((N, 2), np.nan)
    for r in range(N):
        am = round(spot[r] / STEP) * STEP
        for (k, s), a in opts.items():
            if abs(k - am) > width * STEP + 1e-9:
                a[r] = np.nan
        edge[r, 0] = opts[(am + width * STEP, "C")][r, C] if (am + width * STEP, "C") in opts else np.nan
        edge[r, 1] = opts[(am - width * STEP, "P")][r, C] if (am - width * STEP, "P") in opts else np.nan
    return Day(d, und, ix, opts, np.full(N, 14.0), spot[0], 14.0, lot, edge, 150.0)


def flat(level=25000.0):
    return np.full(N, level)


def rally(start=minute(13, 40), pts=300.0, level=25000.0, over=40.0):
    r = np.arange(N)
    return level + np.clip((r - start) / over, 0, 1) * pts


def set_path(day: Day, k: float, side: str, px: np.ndarray, vol: float = 5000.0):
    day.opts[(k, side)] = _bars(px, vol)


# ------------------------------------------------------------------------------------------------ data
def test_expiry_days_from_time_value():
    idx = [date(2024, 1, d) for d in (1, 2, 3, 4, 5, 8, 9, 10, 11)]
    tv = pd.Series([0.9, 0.7, 0.5, 0.05, 0.8, 0.6, 0.4, 0.03, 0.5], index=idx)
    assert D.expiry_days(tv) == [date(2024, 1, 4), date(2024, 1, 10)]
    # the last session is never called an expiry (no next session to confirm the roll)
    assert D.expiry_days(pd.Series([0.9, 0.02], index=idx[:2])) == []
    # a high-vol expiry (0.4% at 15:15) still counts when the next session jumps
    assert D.expiry_days(pd.Series([0.4, 1.2], index=idx[:2]) .pipe(lambda s: pd.concat([s, pd.Series([1.0], index=[idx[2]])]))) == [idx[0]]


def test_clean_caps_a_freak_print_only():
    day = make_day(flat())
    k = 25300.0
    a = day.opts[(k, "C")]
    before = a.copy()
    a[200, H] = 750.0                         # one freak print far above the nearer strike
    n = D.clean(day)
    assert n == 1
    near = day.opts[(25250.0, "C")][200, H]
    assert a[200, H] <= near * D.CLEAN_MULT + D.CLEAN_ADD + 1e-9
    assert np.allclose(np.delete(a, 200, 0), np.delete(before, 200, 0), equal_nan=True)
    assert D.clean(day) == 0                  # idempotent


def test_clean_leaves_a_monotone_chain_alone():
    day = make_day(rally())
    snap = {k: a.copy() for k, a in day.opts.items()}
    assert D.clean(day) == 0
    for k, a in day.opts.items():
        assert np.allclose(a, snap[k], equal_nan=True)


def test_lot_schedule_and_holdout_split():
    assert K.lot("NIFTY", date(2022, 6, 2)) == 50 and K.lot("NIFTY", date(2026, 1, 6)) == 65
    assert K.lot("SENSEX", date(2024, 1, 5)) == 10 and K.lot("SENSEX", date(2025, 1, 7)) == 20
    assert K.lot("BANKNIFTY", date(2022, 6, 2)) == 25 and K.lot("BANKNIFTY", date(2024, 1, 3)) == 15
    assert K.is_holdout(date(2022, 12, 29)) and not K.is_holdout(date(2023, 1, 5))


def test_load_design_never_returns_holdout_days(tmp_path, monkeypatch):
    import pickle
    monkeypatch.setattr(K, "OUT", tmp_path)
    days = [make_day(flat(), d=date(2022, 12, 29)), make_day(flat(), d=date(2023, 1, 5))]
    (tmp_path / "days_NIFTY.pkl").write_bytes(pickle.dumps(days))
    assert [d.day for d in D.load("NIFTY", "design")] == [date(2023, 1, 5)]
    assert [d.day for d in D.load("NIFTY", "holdout")] == [date(2022, 12, 29)]


def test_thirteen_october_is_not_a_design_or_holdout_input():
    assert K.FORWARD_ONLY == date(2026, 10, 13)
    assert not K.is_holdout(K.FORWARD_ONLY)


# ------------------------------------------------------------------------------------------------ fills and exits
def test_entry_never_chases_above_the_cap():
    day = make_day(flat())
    k = 25300.0
    px = np.full(N, 3.0)
    px[201:] = 9.0                            # jumps right after the signal
    set_path(day, k, "C", px)
    day.opts[(k, "C")][201:, O] = 9.0         # the next bars open and trade at 9: above 3 x 1.2 + 0.10
    day.opts[(k, "C")][201:, L] = 9.0
    assert R.enter(day, k, "C", 200, 3.0) is None
    tr = R.simulate(day, k, 1, 200, 3.0, 1000.0, "hold")
    assert tr is None


def test_budget_sizing_and_zero_lots_skip():
    day = make_day(flat(), lot=75)
    k = 25300.0
    set_path(day, k, "C", np.full(N, 3.0))
    tr = R.simulate(day, k, 1, 200, 3.0, 500.0, "hold")
    assert tr.lots == math.floor(500 / (tr.entry * 75))
    assert R.simulate(day, k, 1, 200, 3.0, 100.0, "hold") is None       # one lot costs more than the budget
    one = R.simulate(day, k, 1, 200, 3.0, None, "hold")
    assert one.lots == 1 and one.qty == 75


def test_worthless_expiry_sends_no_sell_and_loses_premium_plus_buy_charges():
    day = make_day(flat())
    k = 25300.0
    px = np.full(N, 2.0)
    px[260:] = 0.05
    set_path(day, k, "C", px)
    tr = R.simulate(day, k, 1, 200, 2.0, 1000.0, "hold")
    assert all(p == 0 for _, _, p, _ in tr.exits)
    assert tr.net == pytest.approx(-tr.risked, abs=0.01)
    assert tr.per_1000 == pytest.approx(-1000, abs=0.01)


def _run_up(day, k, mult_path):
    px = 2.0 * np.asarray(mult_path, dtype=float)
    set_path(day, k, "C", np.round(px / 0.05) * 0.05)


def test_half_at_3x_then_ladder_lock_sells_the_rest():
    day = make_day(flat())
    k = 25300.0
    m = np.ones(N)
    m[205:230] = np.linspace(1, 12, 25)       # up to 12x
    m[230:] = 4.0                             # falls back below the 5x lock (10x reached -> lock at 5x)
    _run_up(day, k, m)
    tr = R.simulate(day, k, 1, 200, 2.0, 2000.0, "ladder")
    whys = [w for *_, w in tr.exits]
    assert whys[0] == "3x" and "lock" in whys
    e = tr.entry
    assert tr.exits[0][2] == R.floor_tick(3 * e)
    assert tr.net > 0


def test_app_exits_stop_at_minus_60_and_targets():
    day = make_day(flat())
    k = 25300.0
    m = np.ones(N)
    m[210:] = 0.3
    _run_up(day, k, m)
    tr = R.simulate(day, k, 1, 200, 2.0, 2000.0, "app")
    assert [w for *_, w in tr.exits] == ["stop"]
    m = np.ones(N)
    m[205:240] = np.linspace(1, 25, 35)
    m[240:] = 25
    day = make_day(flat())
    _run_up(day, k, m)
    tr = R.simulate(day, k, 1, 200, 2.0, 5000.0, "app")
    assert [w for *_, w in tr.exits] == ["5x", "20x"]


def test_a_freak_high_fills_no_target():
    day = make_day(flat())
    k = 25300.0
    set_path(day, k, "C", np.full(N, 2.0))
    day.opts[(k, "C")][220, H] = 50.0        # one print at 25x; close and next open stay at 2
    tr = R.simulate(day, k, 1, 200, 2.0, 2000.0, "app")
    assert [w for *_, w in tr.exits] == ["1505"]


def test_trail_arms_at_plus_50pct_and_gives_back_half():
    day = make_day(flat())
    k = 25300.0
    m = np.ones(N)
    m[205:215] = np.linspace(1, 2.5, 10)
    m[215:] = 1.6                             # below e + 50% of the best open profit
    _run_up(day, k, m)
    tr = R.simulate(day, k, 1, 200, 2.0, 2000.0, "trail")
    assert tr.exits[-1][3] == "trail"


def test_out_of_range_prices_further_otm_edge_or_zero_and_itm_intrinsic():
    sp = flat()
    sp[250:] = 24500.0                        # a crash: the 25300 CE leaves the stored range further OTM
    day = make_day(sp, width=10)
    k = 25300.0
    o, h, c, px = R.path_px(day, k, "C", 300)
    assert px and c == pytest.approx(day.edge[300, 0])
    assert R.path_px(day, k, "C", 300, oor_zero=True)[2] == 0.0
    tr = R.simulate(day, k, 1, 200, float(day.opts[(k, "C")][200, C]), 5000.0, "hold")
    assert tr.proxy
    sp = flat()
    sp[250:] = 25900.0                        # a rally: the 25300 CE goes deep ITM, beyond the range
    day = make_day(sp, width=10)
    o, h, c, px = R.path_px(day, 25300.0, "C", 300)
    assert px and c == pytest.approx(25900.0 - 25300.0)


def test_charges_bse_lower_exchange():
    assert R.charge(False, 100.0, 100, "SENSEX") < R.charge(False, 100.0, 100, "NIFTY")
    assert R.charge(True, 0.0, 100, "NIFTY") == 0.0


# ------------------------------------------------------------------------------------------------ rules
def test_lottery_picks_the_price_closest_to_3():
    day = make_day(flat())
    t = R.close_row(13, 30)
    s = RU.signals(day, RU.L1)
    assert len(s) == 2 and {x.side for x in s} == {1, -1}
    for x in s:
        el = R.eligible(day, x.side, t, (1.0, 5.0))
        assert abs(x.ref - 3) == min(abs(p - 3) for _, p in el)


def test_market_closed_or_empty_day_gives_no_signals():
    day = make_day(flat())
    day.ix[:] = np.nan
    for rule in RU.FIXED:
        assert RU.signals(day, rule) == []
    day = make_day(flat())
    day.opts = {}
    for rule in RU.FIXED:
        assert RU.signals(day, rule) == []


def test_hero_is_nifty_only():
    day = make_day(rally(), und="SENSEX")
    assert RU.signals(day, RU.H0) == []


def test_stale_option_fails_the_traded_rule():
    day = make_day(flat())
    t = R.close_row(13, 30)
    for k, a in day.opts.items():
        a[t - 10: t + 1, V] = 0               # nothing traded for 10 minutes
    assert RU.signals(day, RU.L1) == []


def test_look_ahead_guard_rules():
    """Every rule's decision at every window minute is the same on a day whose future is blanked or absurd."""
    for sp in (rally(), rally(pts=-300.0), flat()):
        day = make_day(sp)
        for rule in RU.FIXED:
            a, b = rule.window()
            full = RU.signals(day, rule)
            for t in range(a, b + 1, 7):
                for fut in (R.masked(day, t), R.poisoned(day, t)):
                    got = RU.signals(fut, rule)
                    want = [s for s in full if s.row <= t]
                    if rule.kind == "lottery":
                        if t >= rule.at:
                            assert [(s.row, s.side, s.strike, s.ref) for s in got] == [(s.row, s.side, s.strike, s.ref) for s in full]
                    else:
                        assert [(s.row, s.side, s.strike) for s in got if s.row <= t] == [(s.row, s.side, s.strike) for s in want]


def test_look_ahead_guard_anatomy_features():
    import anatomy as A
    assert A.__file__.endswith(os.path.join("r25", "anatomy.py"))
    for sp in (rally(), rally(pts=-250.0, start=minute(12, 0))):
        day = make_day(sp)
        for t in A.DECISIONS[::4]:
            want = A.features(day, t)
            for fut in (R.masked(day, t), R.poisoned(day, t)):
                got = A.features(fut, t)
                assert len(got) == len(want)
                for x, y in zip(want, got):
                    for k, v in x.items():
                        w = y[k]
                        if isinstance(v, float) and math.isnan(v):
                            assert isinstance(w, float) and math.isnan(w), k
                        else:
                            assert v == w, (t, k, v, w)


# ------------------------------------------------------------------------------------------------ stats
def test_bh_and_bootstrap():
    q = RU.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    assert RU.boot_p(np.array([10.0] * 50 + [-1.0] * 50), n=2000) < 0.01
    assert RU.boot_p(np.array([-10.0] * 50 + [1.0] * 50), n=2000) > 0.9
    assert RU.boot_p(np.array([])) == 1.0


def test_metrics_drawdown_streak_and_lottery_dependence():
    days = [make_day(flat(), d=date(2024, 1, i)) for i in (1, 2, 3, 4)]

    class T:
        def __init__(s, d, net):
            s.und, s.day, s.net, s.risked, s.fill_row, s.proxy = "NIFTY", d, net, 1000.0, 0, False
    tr = [T(date(2024, 1, 1), -100), T(date(2024, 1, 2), -200), T(date(2024, 1, 3), 1000), T(date(2024, 1, 4), -50)]
    m = RU.metrics(tr, days, RU.L1)
    assert m["net"] == 650 and m["max_dd"] == -300 and m["losing_streak_trades"] == 2
    assert m["best_day_share_pct"] == pytest.approx(1000 / 650 * 100, abs=0.1)
    assert m["net_without_best_day"] == -350 and m["green_expiries_pct"] == 25.0


def test_census_counts_a_10x_unit():
    import census as CE
    day = make_day(rally(start=minute(13, 40), pts=400.0))
    rows, cov = CE.day_units(day)
    assert rows and any(r["x10"] for r in rows)
    assert all(1 <= r["ref"] <= 10 for r in rows)
    assert cov["C_min"] > 0


def test_masked_day_shares_no_cache():
    import triggers as TG
    day = make_day(rally())
    RU.signals(day, TG.make(TG.SPECS[0], {"dist_em_max": 1.44}, "hold"))
    R.stored_range(day)
    m = R.masked(day, 250)
    assert "_tg" not in m.__dict__ and getattr(m, "_rng", None) is None


def test_look_ahead_guard_triggers():
    import triggers as TG
    for sp in (rally(start=minute(12, 30)), rally(pts=-300.0, start=minute(13, 10)), flat()):
        day = make_day(sp)
        for spec in TG.SPECS:
            mid = {k: v[len(v) // 2] for k, v in spec["grid"].items()}
            for p in (mid, {k: v[0] for k, v in spec["grid"].items()}):
                rule = TG.make(spec, p, "hold")
                full = [(s.row, s.side, s.strike) for s in RU.signals(day, rule)]
                for t in range(rule.first, rule.last + 1, 9):
                    for fut in (R.masked(day, t), R.poisoned(day, t)):
                        got = [(s.row, s.side, s.strike) for s in RU.signals(fut, rule) if s.row <= t]
                        assert got == [x for x in full if x[0] <= t], (spec["id"], t)


def test_trigger_fires_on_a_breakout_and_choose_respects_minimum_trades():
    import triggers as TG
    day = make_day(rally(start=minute(12, 30), pts=300.0))
    t1 = TG.make(TG.SPECS[0], {"dist_em_max": 1.44}, "hold")
    s = RU.signals(day, t1)
    assert s and all(x.side == 1 for x in s)           # only the up side broke out
    r = TG.choose(TG.SPECS[0], [day])                   # 1 day: no combination reaches 15 trades -> the strictest, held
    assert r.exits == "hold" and dict((k, v) for k, _, v in r.params) == {"dist_em_max": 1.0}


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake day files are not built here")
def test_look_ahead_guard_on_real_design_days():
    """The rules and the anatomy features on real design days (one per index), every 10th window minute."""
    import anatomy as A
    import triggers as TG
    rules = list(RU.FIXED) + [TG.make(s, {k: v[1] for k, v in s["grid"].items()}, "app") for s in TG.SPECS]
    n = 0
    for u in K.UNDS:
        ds = D.load(u, "design")
        day = ds[len(ds) // 2]
        for rule in rules:
            full = [(s.row, s.side, s.strike) for s in RU.signals(day, rule)]
            a, b = rule.window()
            for t in range(a, b + 1, 10):
                got = [(s.row, s.side, s.strike) for s in RU.signals(R.masked(day, t), rule) if s.row <= t]
                assert got == [x for x in full if x[0] <= t], (u, rule.id, t)
                n += 1
        for t in A.DECISIONS[::6]:
            want, got = A.features(day, t), A.features(R.masked(day, t), t)
            assert len(want) == len(got)
            for x, y in zip(want, got):
                for k, v in x.items():
                    assert (v == y[k]) or (isinstance(v, float) and math.isnan(v) and math.isnan(y[k])), (u, t, k)
            n += 1
    assert n > 100
