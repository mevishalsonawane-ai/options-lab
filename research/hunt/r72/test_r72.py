"""R72 tests: synthetic five-index bars only (no lake). Each cross-index rule on a hand-made market, the look-ahead
guard for every rule and setting, and the wiring into R71's study code.

    python -m pytest research/hunt/r72/test_r72.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r72rules as P  # noqa: E402

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
D0 = date(2025, 1, 6)
TODAY0 = 150                                     # two warm days of 75 bars, then "today"


def market(today: dict, warm_days=2, seed=0):
    """today: {und: [(o, h, l, c), ...] (75 bars)}; unlisted indices get flat bars. Warm days: a gentle random walk."""
    rng = np.random.default_rng(seed)
    data = {}
    for u in UNDS:
        d, b, o, h, l, c = [], [], [], [], [], []
        px = 100.0
        for k in range(warm_days):
            for bb in range(75):
                st = rng.normal(0, 0.2)
                d.append(D0 + timedelta(days=k)), b.append(bb)
                o.append(px), h.append(max(px, px + st) + 0.1), l.append(min(px, px + st) - 0.1), c.append(px + st)
                px += st
        bars = today.get(u) or [(px, px + 0.1, px - 0.1, px)] * 75
        for bb, (o_, h_, l_, c_) in enumerate(bars):
            d.append(D0 + timedelta(days=warm_days)), b.append(bb)
            o.append(o_), h.append(h_), l.append(l_), c.append(c_)
        data[u] = (np.array(d, dtype=object), np.array(b), np.array(o, float), np.array(h, float), np.array(l, float),
                   np.array(c, float))
    return data


def last_px(data, u):
    return float(data[u][5][TODAY0 - 1])


def flat(px, n):
    return [(px, px + 0.1, px - 0.1, px)] * n


def jump_at(px, k, up=5.0):
    """A day of flat bars with a jump bar at k closing well above the channel (then flat at the new level)."""
    bars = flat(px, k) + [(px, px + up + 0.2, px - 0.1, px + up)]
    return bars + flat(px + up, 75 - len(bars))


def base_px(seed=0):
    m = market({}, seed=seed)
    return {u: last_px(m, u) for u in UNDS}


def today_sigs(sig, u):
    return [s for s in sig[u] if s[0] >= TODAY0]


# ------------------------------------------------------------------------------------------------ each rule
def test_breadth_breakout_needs_m_indices():
    px = base_px()
    four = {u: jump_at(px[u], 30) for u in UNDS[:4]}
    sig = P.breadth_breakout(P.Panel(market(four)), M=4)
    for u in UNDS[:4]:
        assert (TODAY0 + 30, 1) == today_sigs(sig, u)[0][:2]
    assert today_sigs(sig, "SENSEX") == []
    sig5 = P.breadth_breakout(P.Panel(market(four)), M=5)
    assert all(today_sigs(sig5, u) == [] for u in UNDS)


def test_laggard_catch_up():
    px = base_px()
    t = {u: jump_at(px[u], 30) for u in UNDS[:3]}                 # three break up at bar 30
    lag = flat(px["SENSEX"], 30) + [(px["SENSEX"], px["SENSEX"] + 0.08, px["SENSEX"] - 0.02, px["SENSEX"] + 0.05)]
    t["SENSEX"] = lag + flat(px["SENSEX"] + 0.05, 75 - len(lag))   # above its channel middle, no break
    sig = P.laggard(P.Panel(market(t)), K=3)
    s = today_sigs(sig, "SENSEX")
    assert s and s[0][0] == TODAY0 + 30 and s[0][1] == 1
    assert today_sigs(sig, "NIFTY") == []                          # the leaders broke: not laggards


def test_lonely_breakout_is_faded():
    px = base_px()
    sig = P.lonely_fade(P.Panel(market({"NIFTY": jump_at(px["NIFTY"], 30)})), K=3)
    s = today_sigs(sig, "NIFTY")
    assert s[0][:2] == (TODAY0 + 30, -1) and s[0][2] == pytest.approx(px["NIFTY"] + 5.2)
    both = {"NIFTY": jump_at(px["NIFTY"], 30), "BANKNIFTY": jump_at(px["BANKNIFTY"], 29)}
    assert today_sigs(P.lonely_fade(P.Panel(market(both)), K=3), "NIFTY") == []   # not alone


def test_leader_at_1015_buys_the_strongest():
    px = base_px()
    t = {}
    for k, u in enumerate(UNDS):
        rise = 0.5 + k                                             # SENSEX rises most
        bars = [(px[u], px[u] + 0.1, px[u] - 0.1, px[u])] + [(px[u] + rise, px[u] + rise + 0.1, px[u] + rise - 0.1,
                                                              px[u] + rise)] * 74
        t[u] = bars
    sig = P.leader(P.Panel(market(t)), T_bar=11)
    assert today_sigs(sig, "SENSEX") == [(TODAY0 + 11, 1, pytest.approx(px["SENSEX"] - 0.1))]
    assert all(today_sigs(sig, u) == [] for u in UNDS[:4])


def test_leader_needs_all_five_the_same_way():
    px = base_px()
    t = {u: flat(px[u], 1) + flat(px[u] + 2, 74) for u in UNDS[:4]}
    t["SENSEX"] = flat(px["SENSEX"], 1) + flat(px["SENSEX"] - 2, 74)
    sig = P.leader(P.Panel(market(t)), T_bar=11)
    assert all(today_sigs(sig, u) == [] for u in UNDS)


def test_breadth_thrust():
    px = base_px()
    t = {}
    for u in UNDS:
        bars = flat(px[u] - 3, 40) + [(px[u] - 3, px[u] + 3.1, px[u] - 3.1, px[u] + 3)]
        t[u] = bars + flat(px[u] + 3, 75 - len(bars))
    sig = P.thrust(P.Panel(market(t)), M=5)
    for u in UNDS:
        assert today_sigs(sig, u)[0][:2] == (TODAY0 + 40, 1)


def test_smt_divergence():
    px = base_px()
    p = px["NIFTY"]
    t = {"NIFTY": flat(p, 10) + [(p, p + 0.1, p - 2, p - 1.8), (p - 1.8, p - 1.5, p - 1.9, p - 1.6),
                                 (p - 1.6, p + 0.5, p - 1.7, p + 0.4)]}
    t["NIFTY"] += flat(p + 0.4, 75 - len(t["NIFTY"]))
    for u in UNDS[1:]:                                             # the others dipped early and now hold well above
        q = px[u]
        t[u] = [(q, q + 0.1, q - 3, q)] + flat(q, 74)
    sig = P.smt(P.Panel(market(t)), R_=3)
    s = today_sigs(sig, "NIFTY")
    assert s and s[0] == (TODAY0 + 12, 1, pytest.approx(p - 2))


def test_smt_needs_the_others_to_hold():
    px = base_px()
    t = {}
    for u in UNDS:                                                 # every index makes the new low together
        q = px[u]
        t[u] = flat(q, 10) + [(q, q + 0.1, q - 2, q - 1.8), (q - 1.8, q - 1.5, q - 1.9, q - 1.6),
                              (q - 1.6, q + 0.5, q - 1.7, q + 0.4)]
        t[u] += flat(q + 0.4, 75 - len(t[u]))
    sig = P.smt(P.Panel(market(t)), R_=3)
    assert all(today_sigs(sig, u) == [] for u in UNDS)


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("rule", list(P.RULES))
@pytest.mark.parametrize("param_i", [0, 1])
def test_look_ahead_guard(rule, param_i):
    """No index's signals at or before row t change when every index's bars after t are replaced."""
    rng = np.random.default_rng(3)
    data = {}
    common = rng.normal(0, 0.5, 6 * 75)
    for k, u in enumerate(UNDS):
        steps = common + rng.normal(0, 0.3, len(common))
        c = 100 * (k + 1) + np.cumsum(steps)
        o = np.concatenate([[c[0]], c[:-1]])
        h = np.maximum(o, c) + np.abs(rng.normal(0, 0.3, len(c)))
        l = np.minimum(o, c) - np.abs(rng.normal(0, 0.3, len(c)))
        d = np.array([D0 + timedelta(days=i // 75) for i in range(len(c))], dtype=object)
        b = np.array([i % 75 for i in range(len(c))])
        data[u] = (d, b, o, h, l, c)
    param = P.RULES[rule][3][param_i]
    full = P.signals_all(rule, param, P.Panel(data))
    for t in (160, 230, 301, 377):
        cut = {}
        for u, (d, b, o, h, l, c) in data.items():
            c2 = c.copy()
            c2[t + 1:] = c[t + 1:] + rng.normal(0, 4, len(c) - t - 1)
            o2 = np.concatenate([[c2[0]], c2[:-1]])
            o2[:t + 1] = o[:t + 1]
            h2, l2 = h.copy(), l.copy()
            h2[t + 1:] = np.maximum(o2[t + 1:], c2[t + 1:]) + 1
            l2[t + 1:] = np.minimum(o2[t + 1:], c2[t + 1:]) - 1
            cut[u] = (d, b, o2, h2, l2, c2)
        got = P.signals_all(rule, param, P.Panel(cut))
        for u in UNDS:
            assert [s for s in full[u] if s[0] <= t] == [s for s in got[u] if s[0] <= t], (rule, param, t, u)


def test_family():
    assert len(P.RULES) == 6
    for fn, name, orig, grid in P.RULES.values():
        assert orig == grid[0] and len(grid) == 2


def test_wiring_points_r71_code_at_r72():
    import r72cfg as R
    import r72study as X
    saved = (X.S.R, X.S.P, X.RP.R, X.RP.P, X.S.rule_signals, X.RP.TITLE)
    try:
        X.use_r72()
        assert X.S.P is P and X.S.R is R and X.RP.P is P and X.S.rule_signals is X.rule_signals
        assert R.SEED == 72 and R.RESULTS == HERE / "results" and R.START == date(2024, 11, 20)
        assert R.EXITS["XL"]["steps"][-1] == (0.25, 0.05) and R.ORIG_EXIT == "XL"
        assert X.S.gp_parse("C4", X.S.gp_name(23, "XI")) == (23, "XI")
    finally:                                                       # the R71 modules are shared: put them back
        X.S.R, X.S.P, X.RP.R, X.RP.P, X.S.rule_signals, X.RP.TITLE = saved
