"""R171 tests (13 Nov 2026): the strategies' pieces, the XL exits, costs, caps, the skip-only gates, the measurement, the
EXP holdout seal, the data-hole rule and the look-ahead guard. No release data is needed (synthetic sessions)."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r171arms as A  # noqa: E402
import r171data as D  # noqa: E402
import r171study as S  # noqa: E402

N = D.N


def contract(price=100.0, vol=750.0, n=N):
    a = np.zeros((n, 5))
    a[:, D.O] = a[:, D.H] = a[:, D.L] = a[:, D.C] = price
    a[:, D.V] = vol
    return a


def flat_day(d=date(2025, 5, 6), spot=24_000.0, exp=date(2025, 5, 8), lot=75):
    ix = np.full((N, 4), spot)
    return D.Day(day=d, und="NIFTY", ix=ix, exp=exp, lot=lot)


# ------------------------------------------------------------------------------------------------ data rules
def test_no_session_on_or_after_the_hole_is_usable():
    assert D.usable(date(2026, 2, 23))
    assert not D.usable(date(2026, 2, 24))
    assert not D.usable(date(2026, 6, 1))
    assert not D.usable(date(2026, 9, 7))          # after the hole: the release has none; dropped all the same


def test_expiry_days_come_from_the_stored_expiries():
    days = [flat_day(date(2025, 5, 6), exp=date(2025, 5, 8)), flat_day(date(2025, 5, 8), exp=date(2025, 5, 15))]
    assert D.expiry_days(days) == {date(2025, 5, 8), date(2025, 5, 15)}


def test_exp_never_reads_the_sealed_holdout_or_the_hole():
    files = [f"/x/{d.isoformat()}_chain.parquet" for d in
             [date(2024, 1, 4) + np.timedelta64(7 * i, "D").item() for i in range(120)]]
    got = S.exp_sessions(files)
    days = [d for _, d in got]
    from options_lab.strategy import expiry_put as XP
    _, sealed = XP.split_sessions([date.fromisoformat(Path(f).name[:10]) for f in files])
    assert not set(days) & set(sealed)
    assert all(D.usable(d) for d in days)
    assert len(days) == 120 - 30 - sum(not D.usable(d) for d in [date.fromisoformat(Path(f).name[:10]) for f in files[:-30]])


# ------------------------------------------------------------------------------------------------ signals
def test_donchian_side_breaks_and_refuses_gaps():
    b = np.tile([100.0, 101.0, 99.0, 100.0], (25, 1))
    b[21, 3] = 102.0
    assert A.donchian_side(b, 21) == 1
    b[22, 3] = 98.0
    assert A.donchian_side(b, 22) == -1
    assert A.donchian_side(b, 10) == 0                     # fewer than 20 bars before it
    b[15, 1] = np.nan
    assert A.donchian_side(b, 23) == 0                     # a missing price in the window


def test_supertrend_follows_a_trend_and_is_causal():
    up = np.array([[100 + i, 101 + i, 99 + i, 100.5 + i] for i in range(40)], float)
    dn = np.array([[140 - 3 * i, 141 - 3 * i, 138 - 3 * i, 139 - 3 * i] for i in range(40)], float)
    b = np.vstack([up, dn])
    st = A.supertrend(b)
    assert st[:9].tolist() == [0] * 9                      # no ATR yet
    assert st[35] == 1 and st[-1] == -1
    assert (A.supertrend(b[:50]) == st[:50]).all()          # bar i uses bars <= i only


def test_itm_strike_is_one_step_in_the_money():
    assert A.itm_strike(1, 24_026.0, 50.0) == 24_000.0      # ATM 24,050 (half-up); the call one step below
    assert A.itm_strike(-1, 24_026.0, 50.0) == 24_100.0
    assert A.atm_strike(24_025.0, 50.0) == 24_050.0          # half-up as OrbRules.atmStrike


# ------------------------------------------------------------------------------------------------ XL exits
def test_xl_stop_at_minus_15_percent():
    a = contract()
    a[105, D.L] = 80.0
    ef, E, xr, X, why = A.simulate_xl(a, 100, 75, date(2025, 5, 6))
    assert (ef, E, xr, X, why) == (100, 100.0, 105, 85.0, "stop")


def test_xl_gap_through_the_stop_fills_at_the_open():
    a = contract()
    a[105, D.O] = a[105, D.L] = 70.0
    assert A.simulate_xl(a, 100, 75, date(2025, 5, 6))[3] == 70.0


def test_xl_lock_counts_from_the_next_minute_and_l25_locks_plus_5():
    a = contract()
    a[102, D.H] = 126.0                                    # +26%: L25 -> lock +5% from minute 103
    a[102, D.L] = 99.0
    a[103:, [D.O, D.H, D.L, D.C]] = 110.0
    a[104, D.L] = 104.0
    ef, E, xr, X, why = A.simulate_xl(a, 100, 75, date(2025, 5, 6))
    assert (xr, X, why) == (104, 105.0, "lock")


def test_xl_time_stop_at_the_30th_minute_close():
    a = contract()
    a[129, D.C] = 103.0
    ef, E, xr, X, why = A.simulate_xl(a, 100, 75, date(2025, 5, 6))
    assert (xr, X, why) == (129, 103.0, "time")


def test_xl_square_off_and_fill_slack():
    a = contract()
    a[340:343, D.O] = np.nan                              # no trade at 14:55-14:57: the 3rd minute is too late
    assert A.simulate_xl(a, 340, 75, date(2025, 5, 6)) is None
    a2 = contract()
    a2[340, D.O] = np.nan
    r = A.simulate_xl(a2, 340, 75, date(2025, 5, 6))
    assert r[0] == 341 and r[2] == A.SQ_ROW and r[4] == "squareoff"


def test_breakeven_rung_is_above_the_fill_by_the_charges():
    a = contract()
    a[101, D.H] = 131.0                                   # +31%: breakeven + charges
    a[101, D.L] = 99.0
    a[102:, [D.O, D.H, D.C]] = 110.0
    a[102, D.L] = 100.0
    ef, E, xr, X, why = A.simulate_xl(a, 100, 75, date(2025, 5, 6))
    assert why == "lock" and xr == 102 and X > 100.0


# ------------------------------------------------------------------------------------------------ costs
def test_costs_are_never_zero_and_rise_with_the_regime():
    d = date(2025, 5, 6)
    r = [A.net_rs(100.0, 100.0, 75, d, g) for g in A.REGIMES]
    assert all(x < 0 for x in r)
    assert r[0] > r[1] > r[2]


def test_costs_are_dated():
    # STT on the sell leg rose on 1 Oct 2024 (0.0625% -> 0.1%); the exchange charge fell. Different totals either way.
    assert A.net_rs(200.0, 220.0, 75, date(2024, 9, 30), "quoted") != A.net_rs(200.0, 220.0, 75, date(2024, 10, 1), "quoted")


def test_sell_price_is_floored_at_zero():
    assert A.net_rs(1.0, 0.0, 75, date(2025, 5, 6), "stress") < 0


# ------------------------------------------------------------------------------------------------ caps
def test_caps_one_position_a_side_and_two_a_side_a_day():
    d = date(2025, 5, 6)
    cands = [dict(und="NIFTY", day=d, row=r, side=1) for r in (10, 20, 50, 80, 90)]
    exits = {10: 30, 50: 60, 80: 85}

    def sim(c):
        if c["row"] not in exits:
            return None if c["row"] == 90 else A.Trade("X", "NIFTY", d, 1, c["row"], c["row"], 40, 0, "C", None, 75, 1, 1,
                                                        "t", {})
        return A.Trade("X", "NIFTY", d, 1, c["row"], c["row"], exits[c["row"]], 0, "C", None, 75, 1, 1, "t", {})
    got = [t.entry_row for t in A._caps_walk(cands, sim)]
    assert got == [10, 50]                                # 20 is inside 10's hold; 80 is the 3rd of the side


# ------------------------------------------------------------------------------------------------ the look-ahead guard
def test_gate_inputs_ignore_everything_at_and_after_the_fill_minute():
    rng = np.random.default_rng(0)
    a = contract()
    a[:, D.C] = 100 + rng.normal(0, 1, N).cumsum()
    a[:, D.V] = rng.integers(0, 3000, N)
    e = 150
    f1 = A.gate_inputs(a, e, 75, 3)
    b = a.copy()
    b[e:] = rng.normal(500, 100, (N - e, 5))
    f2 = A.gate_inputs(b, e, 75, 3)
    for k in f1:
        if isinstance(f1[k], np.ndarray):
            assert np.array_equal(f1[k], f2[k]), k
        else:
            assert f1[k] == f2[k], k


def test_roll_normal_reads_only_earlier_sessions():
    k, r, exp = 24_000.0, "C", date(2025, 5, 15)
    days = []
    rng = np.random.default_rng(1)
    for i in range(8):
        d = flat_day(date(2025, 5, 5 + i), exp=exp)
        a = contract()
        a[:, D.C] = 100 + np.tile([0.0, 0.5], N // 2 + 1)[:N] + rng.normal(0, 0.01, N)
        d.opts[(k, r)] = a
        days.append(d)
    store = {"NIFTY": days}
    n1 = A.RollHistory(store).normal("NIFTY", days[6].day, exp, k, r, 0.0005)
    days[6].opts[(k, r)][:, D.C] = 999.0                  # today and later changed: the normal must not move
    days[7].opts[(k, r)][:, D.C] = 1.0
    n2 = A.RollHistory(store).normal("NIFTY", days[6].day, exp, k, r, 0.0005)
    assert n1 is not None and n1 == n2


def test_roll_normal_needs_the_same_expiry():
    d0 = flat_day(date(2025, 5, 5), exp=date(2025, 5, 8))
    d0.opts[(1.0, "C")] = contract()
    d1 = flat_day(date(2025, 5, 9), exp=date(2025, 5, 15))
    assert A.RollHistory({"NIFTY": [d0, d1]}).normal("NIFTY", d1.day, d1.exp, 1.0, "C", 0.001) is None


# ------------------------------------------------------------------------------------------------ gates on a trade
def trade_with(feat, strat="R51", lot=75, net=None):
    t = A.Trade(strat, "NIFTY", date(2025, 5, 6), 1, 100, 100, 130, 24_000.0, "C", date(2025, 5, 8), lot, 100.0, 100.0,
                "time", net or {g: 0.0 for g in A.REGIMES})
    t.feat = feat
    return t


def base_feat(**kw):
    f = dict(vol30=np.full(30, 750.0), vol60=np.full(60, 750.0), premium=150.0, median_lots30=10.0, roll_rel=0.005,
             roll_normal=0.005, roll_pts=0.75, dte=3)
    f.update(kw)
    return f


def test_every_gate_takes_a_liquid_cheap_to_trade_contract():
    t = trade_with(base_feat(median_lots30=100.0, premium=400.0))
    for g in S.GATES:
        if g in ("G2f",):                                  # stress spread on purpose: 2.5% is near-impossible
            continue
        assert S.gate_pass(g, t), g


def test_gates_that_cannot_judge_take_the_trade():
    t = trade_with(base_feat(premium=None, roll_rel=None, roll_normal=None, median_lots30=None, roll_pts=None, dte=None,
                             vol30=np.zeros(0), vol60=np.zeros(0)))
    assert all(S.gate_pass(g, t) for g in S.GATES)


def test_g1_skips_a_stale_contract_and_g4c_relaxes_on_1_dte():
    thin = np.r_[np.zeros(9), np.ones(21)]                 # 70% of the window traded
    assert not S.gate_pass("G1a", trade_with(base_feat(vol30=thin)))
    assert S.gate_pass("G4c", trade_with(base_feat(vol30=thin, dte=1)))
    assert not S.gate_pass("G4c", trade_with(base_feat(vol30=thin, dte=2)))


def test_g2_cost_cap_and_floor():
    assert not S.gate_pass("G2a", trade_with(base_feat(premium=30.0)))
    assert not S.gate_pass("G2d", trade_with(base_feat(premium=9.0)))
    exp_t = trade_with(base_feat(premium=5.0, dte=0), strat="EXP", lot=65)
    assert not S.gate_pass("G2c", exp_t)                   # a Rs 5 sold put costs far more than 3% of its premium


def test_g3_depth_and_roll_spike():
    assert not S.gate_pass("G3a", trade_with(base_feat(median_lots30=9.0)))
    assert not S.gate_pass("G3c", trade_with(base_feat(roll_rel=0.011, roll_normal=0.005)))
    assert S.gate_pass("G3d", trade_with(base_feat(roll_rel=0.011, roll_normal=0.005)))


def test_g4_skips_long_dated_and_never_expiry_day():
    assert not S.gate_pass("G4b", trade_with(base_feat(dte=20)))
    assert S.gate_pass("G4b", trade_with(base_feat(dte=0)))
    assert S.gate_pass("G4d", trade_with(base_feat(dte=20, roll_pts=0.05, premium=400.0)))   # a measured tight spread


# ------------------------------------------------------------------------------------------------ measurement
def test_stats_worst_day_and_drawdown():
    ts = [trade_with(base_feat(), net={g: v for g in A.REGIMES}) for v in (100.0, -300.0, 50.0)]
    ts[1].day = date(2025, 5, 7)
    ts[2].day = date(2025, 5, 8)
    s = S.stats(ts)
    assert s["n"] == 3 and s["net"] == -150.0 and s["worst_day"] == -300.0 and s["max_dd"] == -300.0
    assert s["win"] == pytest.approx(66.67, abs=0.01)
    assert S.stats([])["n"] == 0


def test_drawdown_starts_from_zero():
    ts = [trade_with(base_feat(), net={g: -100.0 for g in A.REGIMES})]
    assert S.stats(ts)["max_dd"] == -100.0


def test_random_skip_p_normal_agrees_with_monte_carlo():
    rng = np.random.default_rng(3)
    pop = rng.normal(0, 100, 600)
    kept = np.sort(pop)[150:]                              # drops the worst 150: far better than random
    assert S.skip_p_normal(pop, kept) < 1e-6
    rnd = rng.choice(pop, 450, replace=False)
    pn, pm = S.skip_p_normal(pop, rnd), S.skip_p_mc(pop, rnd.mean(), 450, draws=4000)
    assert abs(pn - pm) < 0.05
    assert S.skip_p_normal(pop, pop) == 1.0 and S.skip_p_mc(pop, 0.0, 0) == 1.0


def test_bh_counts_the_earlier_tests():
    q = S.bh_total([1e-5, 0.5], 3913)
    assert q[0] == pytest.approx(1e-5 * 3913)
    assert q[1] == 1.0
    assert S.M_TOTAL == 3841 + 72


def test_a_gate_only_ever_skips():
    rng = np.random.default_rng(4)
    ts = []
    for i in range(50):
        f = base_feat(premium=float(rng.uniform(5, 500)), dte=int(rng.integers(1, 30)),
                      median_lots30=float(rng.uniform(0, 100)), vol30=rng.integers(0, 2, 30).astype(float))
        t = trade_with(f, net={g: float(rng.normal()) for g in A.REGIMES})
        t.day = date(2025, 4, 1) + np.timedelta64(i, "D").item()
        ts.append(t)
    for g in S.GATES:
        r = S.evaluate(ts, "oos", lambda t, g=g: S.gate_pass(g, t))
        assert r["kept"]["quoted"]["n"] <= r["base"]["quoted"]["n"]


def test_verdict_needs_every_rule():
    good = dict(kept={g: dict(n=200, per_trade=10, worst_day=-100, max_dd=-500) for g in A.REGIMES},
                base={g: dict(n=300, per_trade=0, worst_day=-200, max_dd=-900) for g in A.REGIMES},
                gain={g: 10.0 for g in A.REGIMES}, p_mc=0.0001, q=0.05, design_gain=5.0)
    assert S.verdict(good)[0] == "pass"
    for k, v in (("q", 0.2), ("p_mc", 0.05), ("design_gain", -1.0)):
        bad = dict(good, **{k: v})
        assert S.verdict(bad)[0] == "fail"
    few = dict(good, kept={g: dict(n=39, per_trade=10, worst_day=-100, max_dd=-500) for g in A.REGIMES})
    assert S.verdict(few)[0] == "fail"
    roll_flip = dict(good, gain=dict(good["gain"], roll=-1.0))
    assert S.verdict(roll_flip)[0] == "fail"


# ------------------------------------------------------------------------------------------------ Amendment A (measure)
import r171measure as M  # noqa: E402


def bars_with_spread(seed=0, spread=1.0, mid=200.0):
    rng = np.random.default_rng(seed)
    a = contract(mid)
    q = rng.choice([-1.0, 1.0], (N, 4))
    px = mid + q * spread / 2
    a[:, D.O], a[:, D.H], a[:, D.L], a[:, D.C] = px[:, 0], px.max(axis=1), px.min(axis=1), px[:, 3]
    return a


def test_measure_estimates_and_raw_terms_read_only_rows_before_the_fill():
    a = bars_with_spread()
    e = 200
    m1, r1 = M.estimates(a, e), M.raw_terms(a, e)
    b = a.copy()
    b[e:, :4] = 999.0
    b[e:, D.V] = 0.0
    assert M.estimates(b, e) == m1 and M.raw_terms(b, e) == r1


def test_measure_estimates_see_a_real_bounce():
    est = M.estimates(bars_with_spread(spread=2.0), 200)
    assert est["cs"] > 0 and est["roll"] is not None and est["ar"] is not None


def test_measure_estimates_none_without_enough_traded_minutes():
    a = bars_with_spread()
    a[:, D.V] = 0.0
    assert M.estimates(a, 200) == dict(roll=None, ar=None, cs=None)
    assert M.raw_terms(a, 200) == dict(roll=None, ar=None)


def test_rel_net_is_monotone_in_the_spread_and_floored_at_a_tick():
    d = date(2025, 5, 6)
    assert M.rel_net(100.0, 100.0, 75, d, 0.0) < 0                       # charges + one tick
    assert M.rel_net(100.0, 110.0, 75, d, 0.002) > M.rel_net(100.0, 110.0, 75, d, 0.02)
    assert M.rel_net(100.0, 100.0, 75, d, 0.0) == pytest.approx(M.rel_net(100.0, 100.0, 75, d, 1e-6))


def test_band_against_the_costs_law():
    from options_lab import costs as OC
    p = 200.0
    law = {g: OC.spread_per_unit(premium=p, regime=g) for g in A.REGIMES}
    assert M.band(law["roll"] * 0.9, p) == "below roll"
    assert M.band((law["roll"] + law["quoted"]) / 2, p) == "roll-quoted"
    assert M.band((law["quoted"] + law["stress"]) / 2, p) == "quoted-stress"
    assert M.band(law["stress"] * 1.1, p) == "above stress"
