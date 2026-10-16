"""R74 tests: synthetic five-index bars, VIX and stocks only (no lake). The context builders, each rule on a hand-made
market, the look-ahead guard for every rule and setting, and the wiring into R71's study code.

    python -m pytest research/hunt/r74/test_r74.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r74data as DATA  # noqa: E402
import r74rules as P  # noqa: E402

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
D0 = date(2025, 1, 6)


# ------------------------------------------------------------------------------------------------ synthetic markets
def walk(n_days, seed=0, step=0.1):
    """{und: (d, b, o, h, l, c)}: n_days of 75 bars, a gentle random walk per index (small, even bars)."""
    rng = np.random.default_rng(seed)
    data = {}
    for u in UNDS:
        d, b, o, h, l, c = [], [], [], [], [], []
        px = 100.0
        for k in range(n_days):
            for bb in range(75):
                st = rng.normal(0, step)
                d.append(D0 + timedelta(days=k)), b.append(bb)
                o.append(px), h.append(max(px, px + st) + 0.05), l.append(min(px, px + st) - 0.05), c.append(px + st)
                px += st
        data[u] = [np.array(d, dtype=object), np.array(b), np.array(o, float), np.array(h, float), np.array(l, float),
                   np.array(c, float)]
    return data


def set_day(data, u, k, bars):
    """Replace day k's 75 bars of index u by `bars` [(o, h, l, c)]."""
    s = 75 * k
    for j, (o, h, l, c) in enumerate(bars):
        for a, v in zip(data[u][2:], (o, h, l, c)):
            a[s + j] = v


def flat_vix(n_days, level=15.0):
    return {D0 + timedelta(days=k): (np.full(75, level), level) for k in range(n_days)}


def flat_stocks(n_days, share=0.5, vol=1000.0):
    return {D0 + timedelta(days=k): (np.full(75, share), np.full(75, vol), 59) for k in range(n_days)}


def ctx(data, vix, stocks):
    return P.Ctx(P.Panel({u: tuple(v) for u, v in data.items()}), vix, stocks)


def last_close(data, u, k):
    return float(data[u][5][75 * (k + 1) - 1])


def jump_day(px, k, up):
    """Flat bars at px, a jump bar at k closing at px + up (well beyond the channel), then flat at the new level."""
    bars = [(px, px + 0.01, px - 0.01, px)] * k + [(px, max(px, px + up) + 0.01, min(px, px + up) - 0.01, px + up)]
    return bars + [(px + up, px + up + 0.01, px + up - 0.01, px + up)] * (75 - len(bars))


def sigs_on_day(sig, u, k):
    return [s for s in sig[u] if 75 * k <= s[0] < 75 * (k + 1)]


# ------------------------------------------------------------------------------------------------ data builders
def test_vix5_last_print_at_bar_end():
    c = np.full(375, np.nan)
    c[0], c[3], c[7] = 10.0, 11.0, 12.0
    v5, first = DATA.vix5_of_day(c)
    assert first == 10.0
    assert v5[0] == 11.0 and v5[1] == 12.0 and v5[2] == 12.0          # carried forward
    c2 = c.copy()
    c2[5:] = 99.0                                                     # minutes after bar 0 never change bar 0
    assert DATA.vix5_of_day(c2)[0][0] == 11.0


def test_vix5_empty_day():
    v5, first = DATA.vix5_of_day(np.full(375, np.nan))
    assert np.isnan(first) and np.isnan(v5).all()


def test_stock5_share_above_vwap_and_volume():
    n = 4
    c = np.full((n, 375), 100.0)
    v = np.ones((n, 375))
    c[:2, 4] = 101.0                                                  # 2 of 4 stocks end bar 0 above their VWAP
    sh, v5 = DATA.stock5_of_day(c, c, c, v)
    assert sh[0] == pytest.approx(0.5)
    assert v5[0] == pytest.approx(4 * 5)
    c2 = c.copy()
    c2[:, 5:] = 200.0                                                 # later minutes never change bar 0
    assert DATA.stock5_of_day(c2, c2, c2, v)[0][0] == pytest.approx(0.5)


def test_stock5_missing_stock_is_left_out():
    c = np.full((3, 375), 100.0)
    c[2, :] = np.nan                                                  # a stock with no minute today
    c[0, 4] = 101.0
    v = np.ones((3, 375))
    sh, _ = DATA.stock5_of_day(c, c, c, v)
    assert sh[0] == pytest.approx(0.5)                                # 1 of the 2 stocks that traded


def test_stock5_no_stocks_is_nan():
    sh, v5 = DATA.stock5_of_day(np.full((2, 375), np.nan), np.full((2, 375), np.nan), np.full((2, 375), np.nan),
                                np.zeros((2, 375)))
    assert np.isnan(sh).all() and (v5 == 0).all()


# ------------------------------------------------------------------------------------------------ M1
def m1_market(move, rng_big=True, n=24):
    data = walk(n)
    k = n - 1
    for u in UNDS:
        px = last_close(data, u, k - 1)
        amp = 3.0 if rng_big else 0.0
        bars = [(px, px + amp, px - amp, px)] * 60 + [(px, px + 0.01, px - 0.01, px)] * 5
        bars += [(px, max(px, px + move) + 0.01, min(px, px + move) - 0.01, px + move)] * 10
        set_day(data, u, k, bars)
    return data, k


def test_m1_buys_the_move_on_a_wide_day():
    data, k = m1_market(+2.0)
    sig = P.late_momentum(ctx(data, flat_vix(24), flat_stocks(24)), k=0.5)
    for u in UNDS:
        s = sigs_on_day(sig, u, k)
        assert len(s) == 1 and s[0][0] == 75 * k + P.M1_BAR and s[0][1] == 1
        assert s[0][2] == pytest.approx(last_close(data, u, k - 1))   # index stop: the previous close
    data, k = m1_market(-2.0)
    sig = P.late_momentum(ctx(data, flat_vix(24), flat_stocks(24)), k=0.5)
    assert all(sigs_on_day(sig, u, k)[0][1] == -1 for u in UNDS)


def test_m1_needs_a_big_enough_move_and_a_wide_day():
    data, k = m1_market(+0.01)                                        # move far below k x sigma
    assert all(sigs_on_day(P.late_momentum(ctx(data, {}, {}), k=0.5), u, k) == [] for u in UNDS)
    data, k = m1_market(+2.0, rng_big=False)
    data2 = walk(24)                                                  # a narrow day: range below the 20-day median
    for u in UNDS:
        px = last_close(data2, u, 22)
        set_day(data2, u, 23, [(px, px + 0.001, px - 0.001, px)] * 75)
    assert all(sigs_on_day(P.late_momentum(ctx(data2, {}, {}), k=0.0001), u, 23) == [] for u in UNDS)


def test_m1_needs_21_earlier_sessions():
    data, k = m1_market(+2.0, n=21)                                   # only 20 earlier sessions
    assert all(sigs_on_day(P.late_momentum(ctx(data, {}, {}), k=0.5), u, k) == [] for u in UNDS)


# ------------------------------------------------------------------------------------------------ V1
def v1_market(ri, rv, n=3):
    data = walk(n)
    k = n - 1
    for u in UNDS:
        px = last_close(data, u, k - 1)
        set_day(data, u, k, [(px, px + 0.01, px - 0.01, px)] * 14 + [(px, px * (1 + ri) + 0.01, px - 0.01, px * (1 + ri))]
                + [(px * (1 + ri), px * (1 + ri) + 0.01, px * (1 + ri) - 0.01, px * (1 + ri))] * 60)
    vix = flat_vix(n)
    vc = np.full(75, 15.0)
    vc[14:] = 15.0 * (1 + rv)
    vix[D0 + timedelta(days=k)] = (vc, 15.0)
    return data, vix, k


def test_v1_fades_a_rally_on_rising_vix_once_a_day():
    data, vix, k = v1_market(+0.005, +0.03)
    sig = P.vix_divergence(ctx(data, vix, {}), k=0.002)
    for u in UNDS:
        s = sigs_on_day(sig, u, k)
        assert len(s) == 1 and s[0][0] == 75 * k + 14 and s[0][1] == -1   # 10:30 check, the put; first only
    data, vix, k = v1_market(-0.005, -0.03)
    assert all(sigs_on_day(P.vix_divergence(ctx(data, vix, {}), k=0.002), u, k)[0][1] == 1 for u in UNDS)


def test_v1_no_signal_without_divergence_or_vix():
    data, vix, k = v1_market(+0.005, -0.03)                           # rally on falling VIX: agreement, no trade
    assert all(sigs_on_day(P.vix_divergence(ctx(data, vix, {}), k=0.002), u, k) == [] for u in UNDS)
    data, vix, k = v1_market(+0.003, +0.03)
    assert all(sigs_on_day(P.vix_divergence(ctx(data, vix, {}), k=0.004), u, k) == [] for u in UNDS)
    data, vix, k = v1_market(+0.005, +0.03)
    del vix[D0 + timedelta(days=k)]                                   # no VIX that day
    assert all(sigs_on_day(P.vix_divergence(ctx(data, vix, {}), k=0.002), u, k) == [] for u in UNDS)


# ------------------------------------------------------------------------------------------------ V2 and B2 (filters)
def break_market(up=5.0, at=30, n=3):
    data = walk(n)
    k = n - 1
    for u in UNDS:
        set_day(data, u, k, jump_day(last_close(data, u, k - 1), at, up))
    return data, k


def test_v2_takes_call_breaks_only_on_falling_vix():
    data, k = break_market(+5.0)
    vix = flat_vix(3)
    vc = np.full(75, 15.0)
    vc[30:] = 14.5                                                    # VIX down over the last 6 bars at bar 30
    vix[D0 + timedelta(days=k)] = (vc, 15.0)
    sig = P.vix_break(ctx(data, vix, {}), x=0.0)
    for u in UNDS:
        assert (75 * k + 30, 1) == sigs_on_day(sig, u, k)[0][:2]
    assert all(sigs_on_day(P.vix_break(ctx(data, vix, {}), x=0.05), u, k) == [] for u in UNDS)   # needs > 5%
    assert all(sigs_on_day(P.vix_break(ctx(data, flat_vix(3), {}), x=0.0), u, k) == [] for u in UNDS)  # flat VIX


def test_v2_put_breaks_need_rising_vix():
    data, k = break_market(-5.0)
    vix = flat_vix(3)
    vc = np.full(75, 15.0)
    vc[30:] = 15.5
    vix[D0 + timedelta(days=k)] = (vc, 15.0)
    sig = P.vix_break(ctx(data, vix, {}), x=0.0)
    assert all((75 * k + 30, -1) == sigs_on_day(sig, u, k)[0][:2] for u in UNDS)


def test_b2_needs_volume_above_its_time_of_day_mean_and_large_caps_only():
    n = 23
    data, k = break_market(+5.0, n=n)
    st = flat_stocks(n, vol=1000.0)
    sh, v5, nn = st[D0 + timedelta(days=k)]
    v5 = v5.copy()
    v5[30] = 1600.0                                                   # 1.6x the 20-day mean at bar 30
    st[D0 + timedelta(days=k)] = (sh, v5, nn)
    sig = P.volume_break(ctx(data, {}, st), m=1.5)
    for u in P.LARGE:
        assert (75 * k + 30, 1) == sigs_on_day(sig, u, k)[0][:2]
    assert sigs_on_day(sig, "MIDCPNIFTY", k) == []
    assert all(sigs_on_day(P.volume_break(ctx(data, {}, st), m=2.0), u, k) == [] for u in UNDS)


def test_b2_needs_20_earlier_stock_sessions():
    data, k = break_market(+5.0, n=20)
    st = flat_stocks(20, vol=1000.0)
    sh, v5, nn = st[D0 + timedelta(days=k)]
    st[D0 + timedelta(days=k)] = (sh, v5 * 5, nn)
    assert all(sigs_on_day(P.volume_break(ctx(data, {}, st), m=1.5), u, k) == [] for u in UNDS)


# ------------------------------------------------------------------------------------------------ B1
def test_b1_thrust_up_and_down_once_per_side():
    n = 3
    data = walk(n)
    k = n - 1
    st = flat_stocks(n)
    sh = np.full(75, 0.2)
    sh[20:] = 0.75                                                    # 20% -> 75% above VWAP at bar 20
    sh[40:] = 0.8
    sh[50:] = 0.25                                                    # 80% -> 25% at bar 50: the put
    st[D0 + timedelta(days=k)] = (sh, np.full(75, 1.0), 59)
    sig = P.breadth_thrust(ctx(data, {}, st), T=0.7)
    for u in P.LARGE:
        s = sigs_on_day(sig, u, k)
        assert [(x[0], x[1]) for x in s] == [(75 * k + 20, 1), (75 * k + 50, -1)]
    assert sigs_on_day(sig, "MIDCPNIFTY", k) == []
    assert all(sigs_on_day(P.breadth_thrust(ctx(data, {}, st), T=0.8), u, k) == [] for u in UNDS)


def test_b1_slow_drift_is_not_a_thrust():
    n = 3
    data = walk(n)
    st = flat_stocks(n)
    st[D0 + timedelta(days=2)] = (np.linspace(0.2, 0.9, 75), np.ones(75), 59)   # 1% a bar: 6 bars never span 40 pts
    assert all(sigs_on_day(P.breadth_thrust(ctx(data, {}, st), T=0.7), u, 2) == [] for u in UNDS)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def random_ctx(seed, n=26):
    rng = np.random.default_rng(seed)
    data = walk(n, seed=seed, step=0.6)
    vix, st = {}, {}
    for k in range(n):
        d = D0 + timedelta(days=k)
        vc = 15 + np.cumsum(rng.normal(0, 0.3, 75))
        vix[d] = (vc, float(vc[0] + rng.normal(0, 0.2)))
        st[d] = (np.clip(0.5 + np.cumsum(rng.normal(0, 0.15, 75)), 0, 1), rng.lognormal(7.0, 0.7, 75), 59)
    return data, vix, st


def perturb_after(data, vix, st, k_cut, b_cut, seed):
    """A copy where every index bar, VIX close and stock value after (day k_cut, bar b_cut) is replaced by noise."""
    rng = np.random.default_rng(seed + 1000)
    d2 = {}
    for u, arrs in data.items():
        a = [x.copy() for x in arrs]
        cut = 75 * k_cut + b_cut + 1
        m = len(a[0]) - cut
        if m > 0:
            c = a[5][cut - 1] + np.cumsum(rng.normal(0, 3.0, m))
            a[2][cut:], a[5][cut:] = c - rng.normal(0, 1, m), c
            a[3][cut:] = np.maximum(a[2][cut:], c) + 0.5
            a[4][cut:] = np.minimum(a[2][cut:], c) - 0.5
        d2[u] = a
    v2, s2 = {}, {}
    for d in vix:
        k = (d - D0).days
        vc, f = vix[d][0].copy(), vix[d][1]
        sh, vv = st[d][0].copy(), st[d][1].copy()
        if k > k_cut:
            vc[:], f = rng.uniform(5, 40, 75), float(rng.uniform(5, 40))
            sh[:], vv[:] = rng.uniform(0, 1, 75), rng.uniform(0, 1e5, 75)
        elif k == k_cut:
            vc[b_cut + 1:] = rng.uniform(5, 40, 74 - b_cut)
            sh[b_cut + 1:] = rng.uniform(0, 1, 74 - b_cut)
            vv[b_cut + 1:] = rng.uniform(0, 1e5, 74 - b_cut)
        v2[d], s2[d] = (vc, f), (sh, vv, 59)
    return d2, v2, s2


@pytest.mark.parametrize("rule", list(P.RULES))
@pytest.mark.parametrize("seed", [1, 2])
def test_no_look_ahead_every_rule_and_setting(rule, seed):
    data, vix, st = random_ctx(seed)
    for param in P.RULES[rule][3]:
        base = P.signals_all(rule, param, ctx(data, vix, st))
        assert sum(len(v) for v in base.values()) > 0 or rule in ("M1", "V1"), "the guard must see signals"
        for k_cut, b_cut in ((22, 14), (23, 40), (24, 65), (25, 5)):
            cut = 75 * k_cut + b_cut
            pert = P.signals_all(rule, param, ctx(*perturb_after(data, vix, st, k_cut, b_cut, seed)))
            for u in UNDS:
                assert [s for s in base[u] if s[0] <= cut] == [s for s in pert[u] if s[0] <= cut], (rule, param, u)


def test_guard_sees_m1_and_v1_signals():
    """M1 / V1 fire rarely on random data: check that the guard's markets do contain some, at the loosest setting."""
    hits = {"M1": 0, "V1": 0}
    for seed in (1, 2):
        data, vix, st = random_ctx(seed)
        X = ctx(data, vix, st)
        hits["M1"] += sum(len(v) for v in P.late_momentum(X, k=0.5).values())
        hits["V1"] += sum(len(v) for v in P.vix_divergence(X, k=0.002).values())
    assert hits["M1"] > 0 and hits["V1"] > 0


# ------------------------------------------------------------------------------------------------ wiring
def test_rules_table_and_param_round_trip():
    sys.path.insert(0, str(HERE.parent / "r71"))
    import r71study as S
    import r74study as X
    X.use_r74()
    assert S.P is P and S.R.SEED == 74 and S.rule_signals is X.rule_signals
    for rule, (_, _, orig, grid) in P.RULES.items():
        assert orig in grid and len(grid) == 2
        for g in grid:
            for ex in S.R.EXIT_ORDER:
                assert S.gp_parse(rule, S.gp_name(g, ex)) == (g, ex)
    assert S.R.FINAL_FLAG.parent.name == "results" and S.R.FINAL_FLAG.parent.parent.name == "r74"
