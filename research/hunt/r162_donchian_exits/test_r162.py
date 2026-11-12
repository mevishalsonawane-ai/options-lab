"""R162 tests: Black-Scholes delta / implied vol and the delta strike, the expiry calendar, the index-stop level and row,
scale-out arithmetic, twin windows and p, BH, the gates, the release walk (against R106's walk), and the engine on
synthetic days: B0 = R106's pricing, each rule's exit, and look-ahead walls (nothing after the decision minute changes
a strike, a level or an earlier exit). Synthetic data only: no stores, no lake. 12 Nov 2026."""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r162cfg as K  # noqa: E402
import r162lib as L  # noqa: E402

Y = pytest.importorskip("r162engine")
import engine as E  # noqa: E402
import r106engine as X  # noqa: E402
import r106walk as W  # noqa: E402
import r51cfg as K51  # noqa: E402
import r51data  # noqa: E402

XL = E.Exits(stop=0.15, target=-1.0, steps=K.L25, tmax=30, sq_row=K51.row(15, 15))
DAY = date(2025, 6, 3)


# ================================================================================================ config
def test_tests_and_prior_count():
    assert len(K.TESTS) == 102
    assert K.N_PRIOR == 1247
    assert K.lots_of("SO10") == (2, 3) and K.lots_of("BE10") == (1, 2, 3)
    assert set(K.FAMILY) == {s["kind"] for s in K.RULES.values()}


def test_exits_for_each_kind():
    assert Y.exits_for("B0", XL) is XL
    assert Y.exits_for("T40", XL).tmax == 40 and Y.exits_for("T40", XL).stop == 0.15
    n = Y.exits_for("NIP30", XL)
    assert n.tmax == 30 and n.need == 0.0
    assert Y.exits_for("IXR", XL).stop == -1.0 and Y.exits_for("IXA", XL).stop == 0.15
    lf = Y.exits_for("LF1", XL)
    assert lf.steps[:3] == K.RULES["LF1"]["add"] and len(lf.steps) == 7
    assert Y.exits_for("BE10", XL) is XL and Y.exits_for("ATM", XL) is XL and Y.exits_for("SO8", XL) is XL


# ================================================================================================ Black-Scholes
def test_put_call_parity_and_iv_round_trip():
    s, k, t, r, v = 25000.0, 24900.0, 3 / 365, 0.065, 0.14
    c, p = L.bs_price("C", s, k, t, v, r), L.bs_price("P", s, k, t, v, r)
    assert abs((c - p) - (s - k * math.exp(-r * t))) < 1e-6
    assert abs(L.implied_vol("C", c, s, k, t, r) - v) < 1e-4
    assert abs(L.implied_vol("P", p, s, k, t, r) - v) < 1e-4


def test_iv_none_below_intrinsic_or_bad_input():
    assert L.implied_vol("C", 50.0, 25000, 24800, 2 / 365, 0.065) is None       # intrinsic 200
    assert L.implied_vol("C", float("nan"), 25000, 24800, 2 / 365, 0.065) is None
    assert L.implied_vol("C", 100.0, 25000, 24800, 0.0, 0.065) is None


def test_delta_rises_with_moneyness_and_pick():
    s, t, r, v = 25000.0, 3 / 365, 0.065, 0.14
    ks = [24700.0, 24800.0, 24900.0, 25000.0, 25100.0]
    d = [L.abs_delta("C", s, k, t, v, r) for k in ks]
    assert all(a > b for a, b in zip(d, d[1:]))
    quotes = {k: L.bs_price("C", s, k, t, v, r) for k in ks}
    want = min(ks, key=lambda k: abs(L.abs_delta("C", s, k, t, v, r) - 0.7))
    assert L.pick_delta_strike("C", s, t, r, quotes, 0.7) == want
    pq = {k: L.bs_price("P", s, k, t, v, r) for k in [24900.0, 25000.0, 25100.0, 25200.0]}
    kp = L.pick_delta_strike("P", s, t, r, pq, 0.6)
    assert kp > s                                    # a 0.6-delta put is in the money (strike above spot)
    assert L.pick_delta_strike("C", s, t, r, {24000.0: 1.0}, 0.6) is None   # below intrinsic: no delta


def test_delta_tie_takes_the_deeper_strike(monkeypatch):
    monkeypatch.setattr(L, "implied_vol", lambda *a, **k: 0.2)
    monkeypatch.setattr(L, "abs_delta", lambda side, s, k, t, v, r: {100.0: 0.55, 110.0: 0.65}[k])
    assert L.pick_delta_strike("C", 105.0, 0.01, 0.0, {100.0: 6.0, 110.0: 1.0}, 0.60) == 100.0
    monkeypatch.setattr(L, "abs_delta", lambda side, s, k, t, v, r: {100.0: 0.65, 110.0: 0.55}[k])
    assert L.pick_delta_strike("P", 105.0, 0.01, 0.0, {100.0: 1.0, 110.0: 6.0}, 0.60) == 110.0


# ================================================================================================ expiry calendar
def test_next_expiry():
    f = [date(2025, 6, 5), date(2025, 6, 12), date(2025, 6, 19)]
    assert L.next_expiry(date(2025, 6, 6), f) == date(2025, 6, 12)
    assert L.next_expiry(date(2025, 6, 5), f) == date(2025, 6, 12)          # never the day itself
    assert L.next_expiry(date(2025, 6, 20), f) == date(2025, 6, 26)         # extrapolated by the last gap
    assert L.next_expiry(date(2025, 7, 10), f) == date(2025, 7, 17)         # strictly after the day
    assert L.next_expiry(date(2025, 6, 20), [date(2025, 6, 19)]) is None


def test_years_to_expiry():
    t = L.years_to_expiry(date(2025, 6, 3), 0, date(2025, 6, 5))
    assert abs(t - (2 + 375 / 1440) / 365) < 1e-9
    assert L.years_to_expiry(date(2025, 6, 5), 374, date(2025, 6, 5)) > 0


# ================================================================================================ index stop
def test_index_level_reads_only_the_five_minutes_before_entry():
    lo = np.arange(100.0, 120.0)
    hi = lo + 2
    assert L.index_level(lo, hi, 10, "C") == 105.0 and L.index_level(lo, hi, 10, "P") == 111.0
    lo2, hi2 = lo.copy(), hi.copy()
    lo2[10:] = 0.0
    hi2[10:] = 999.0                                  # the entry minute and later never move the level
    assert L.index_level(lo2, hi2, 10, "C") == 105.0 and L.index_level(lo2, hi2, 10, "P") == 111.0
    lo3 = lo.copy()
    lo3[5:10] = np.nan
    assert math.isnan(L.index_level(lo3, hi, 10, "C"))


def test_index_exit_row():
    cl = np.array([100, 101, 102, 99, 98, 97.0])
    assert L.index_exit_row(cl, 1, 100.0, "C", 6) == 4
    assert L.index_exit_row(cl, 1, 100.0, "P", 6) == 2
    assert L.index_exit_row(cl, 1, 100.0, "C", 4) == -1          # the trigger's next minute is the square-off
    assert L.index_exit_row(cl, 1, float("nan"), "C", 6) == -1
    cl2 = cl.copy()
    cl2[3] = np.nan
    assert L.index_exit_row(cl2, 1, 100.0, "C", 6) == 5


# ================================================================================================ scale-out, twins
def test_so_net_and_round_wins():
    cb = lambda px, q: 20.0   # noqa: E731
    cs = lambda px, q: 20.0 + 0.001 * px * q   # noqa: E731
    net = L.so_net(100.0, [(110.0, 65.0), (95.0, 65.0)], cb, cs)
    assert abs(net - (10 * 65 - 5 * 65 - 20 - (20 + 7.15) - (20 + 6.175))) < 1e-9
    w, n = L.round_wins([dict(net=5.0, rounds=[10.0, -5.0]), dict(net=-1.0)])
    assert (w, n) == (1, 3)


def test_twin_window():
    assert L.twin_window(10, 20, 20) is None
    assert L.twin_window(10, 15, 30) == (11, 29, "b0")
    assert L.twin_window(10, 40, 30) == (31, 39, "rule")
    assert L.twin_window(10, 31, 30) is None                     # empty window
    assert L.twin_window(10, 12, 11) is None


def test_twin_p_and_bh():
    assert L.twin_p(10.0, [1.0, 2.0, 3.0]) == 0.25
    assert L.twin_p(2.0, [1.0, 2.0, 3.0]) == 0.75
    q = L.bh([0.01, 0.04, 0.03])
    assert np.allclose(q, [0.03, 0.04, 0.04])
    q2 = L.bh([0.01], 100)
    assert np.allclose(q2, [1.0])
    assert len(L.bh([])) == 0


def test_gates():
    st = lambda tr, w, n: dict(trades=tr, win=w, net=n)   # noqa: E731
    base = dict(design=st(100, 0.45, 10), wf=st(60, 0.45, 10), final=st(50, 0.45, 10))
    good = dict(design=st(100, 0.52, 11), wf=st(60, 0.50, 10), final=st(50, 0.51, 12))
    assert L.gates(good, base, 0.05)["passed"]
    g = L.gates(dict(good, wf=st(60, 0.49, 10)), base, 0.05)
    assert not g["passed"] and "wf: win rate not up 5 points" in g["fails"]
    assert "final: net lower than today's" in L.gates(dict(good, final=st(50, 0.51, 9)), base, 0.05)["fails"]
    assert not L.gates(good, base, 0.2)["passed"]
    assert "< 40 final trades" in L.gates(dict(good, final=st(39, 0.51, 12)), base, 0.05)["fails"]
    assert "design: net not positive" in L.gates(dict(good, design=st(100, 0.6, 0)), base, 0.05)["fails"]


# ================================================================================================ release walk
def _units(rng, n_units=3, n_c=6):
    us, pr = [], []
    for g in range(n_units):
        ers = sorted(rng.integers(5, 300, n_c).tolist())
        cands = [dict(er=int(e), side=("C" if rng.random() < 0.5 else "P")) for e in ers]
        rows = []
        for c in cands:
            if rng.random() < 0.1:
                rows.append(None)
            else:
                rows.append(dict(er=c["er"], xr=c["er"] + int(rng.integers(1, 40)), net=float(rng.normal()), und=f"U{g}",
                                 day=DAY, side=c["side"]))
        us.append(((f"U{g}", DAY, None), cands, "side2"))
        pr.append(rows)
    return us, pr


def test_release_no_skip_equals_r106_walk():
    rng = np.random.default_rng(1)
    for _ in range(30):
        us, pr = _units(rng)
        a = L.walk_release(us, pr, lambda g, j: False)
        b = W.walk_day(us, pr)
        assert [(t["und"], t["er_sig"], t["side"]) for t in a] == [(t["und"], t["er_sig"], t["side"]) for t in b]


def test_release_hold_equals_walk_all_then_remove_and_remove_equals_filter_first():
    rng = np.random.default_rng(2)
    for _ in range(30):
        us, pr = _units(rng)
        sk = {(g, j) for g in range(len(us)) for j in range(len(us[g][1])) if rng.random() < 0.35}
        app = L.walk_release(us, pr, lambda g, j: (g, j) in sk, "hold", None)
        allw = W.walk_day(us, pr)
        idx = {(us[g][0][0], c["er"], c["side"]): (g, j) for g in range(len(us)) for j, c in enumerate(us[g][1])}
        ref = [t for t in allw if idx[(t["und"], t["er_sig"], t["side"])] not in sk]
        assert [(t["und"], t["er_sig"]) for t in app] == [(t["und"], t["er_sig"]) for t in ref]
        rem = L.walk_release(us, pr, lambda g, j: (g, j) in sk, "remove")
        pr2 = [[None if (g, j) in sk else r for j, r in enumerate(row)] for g, row in enumerate(pr)]
        ref2 = W.walk_day(us, pr2)
        assert [(t["und"], t["er_sig"]) for t in rem] == [(t["und"], t["er_sig"]) for t in ref2]


def test_release_hold_minutes():
    us = [(("U", DAY, None), [dict(er=10, side="C"), dict(er=14, side="C"), dict(er=30, side="C")], "side2")]
    pr = [[dict(er=10, xr=40, net=1.0, und="U", day=DAY, side="C"), dict(er=14, xr=20, net=2.0, und="U", day=DAY, side="C"),
           dict(er=30, xr=35, net=3.0, und="U", day=DAY, side="C")]]
    sk = lambda g, j: j == 0   # noqa: E731
    assert [t["er_sig"] for t in L.walk_release(us, pr, sk, "hold", None)] == []          # held to its exit (40)
    assert [t["er_sig"] for t in L.walk_release(us, pr, sk, "hold", 5)] == [30]            # 14 blocked (<= 15); 30 ok
    assert [t["er_sig"] for t in L.walk_release(us, pr, sk, "hold", 0)] == [14]            # 14 taken; cap 2 reached
    assert [t["er_sig"] for t in L.walk_release(us, pr, sk, "remove")] == [14, 30]


# ================================================================================================ engine (synthetic day)
def _day(rng, rise=None, fall_after=None, n=375):
    """A synthetic day: index flat-ish at 25,000; CALL contracts at 24,900 / 25,000 / 25,100 (and puts) with random
    walks; `rise` makes the 24,900 call climb that fraction over minutes 21-30 then fall below its fill."""
    idx = np.full((4, n), 25000.0) + rng.normal(0, 5, (1, n))
    idx[1] += 3
    idx[2] -= 3
    con = {}
    for k in (24800.0, 24900.0, 25000.0, 25100.0, 25200.0):
        for side in ("C", "P"):
            base = max(30.0, 120.0 + (25000 - k) * (0.6 if side == "C" else -0.6))
            c = base * np.exp(np.cumsum(rng.normal(0, 0.004, n)))
            o = np.r_[base, c[:-1]]
            h = np.maximum(o, c) * 1.002
            l_ = np.minimum(o, c) * 0.998
            v = np.full(n, 50000.0)
            con[(side, k)] = np.vstack([o, h, l_, c, v]).astype(np.float32)
    if rise is not None:
        C = con[("C", 24900.0)].astype(np.float64)
        p0 = float(C[0, 21])
        path = np.r_[np.linspace(p0, p0 * (1 + rise), 10), np.linspace(p0 * (1 + rise), p0 * 0.5, 40)]
        for i, px in enumerate(path):
            m = 21 + i
            C[0, m] = path[i - 1] if i else p0
            C[3, m] = px
            C[1, m] = max(C[0, m], px)
            C[2, m] = min(C[0, m], px)
        for m in range(21 + len(path), n):
            C[:4, m] = p0 * 0.5
        con[("C", 24900.0)] = C.astype(np.float32)
    atm = np.full(n, 25000.0)
    E._STACKS.clear()                                 # R51 caches a day's stack by (index, date); every synthetic day shares one
    return r51data.Day(DAY, idx, atm, con, False)


def _days(D):
    return {D.d: D, date(2025, 6, 5): r51data.Day(date(2025, 6, 5), D.idx, D.atm, {}, True),
            date(2025, 6, 12): r51data.Day(date(2025, 6, 12), D.idx, D.atm, {}, True)}


def test_b0_equals_r106_pricing_at_1_and_3_lots():
    rng = np.random.default_rng(5)
    for _ in range(4):
        D = _day(rng)
        cands = [dict(er=int(e), side=s, strike=E.strike_for(D, "NIFTY", s, int(e) - 1, 1))
                 for e, s in zip(rng.integers(5, 300, 6), rng.choice(["C", "P"], 6))]
        a = Y.price("NIFTY", D, cands, "B0", XL, 1)
        b = X.price("NIFTY", D, cands, XL)
        for x, y in zip(a, b):
            assert (x is None) == (y is None)
            if x is not None:
                assert abs(x["net"] - y["net"]) < 1e-9 and x["xr"] == y["xr"]
        a3 = Y.price("NIFTY", D, cands, "B0", XL, 3)
        for x, y in zip(a3, b):
            if x is not None:
                assert x["qty"] == 3 * y["qty"] and x["xr"] == y["xr"]


def test_breakeven_exit_nets_about_zero():
    rng = np.random.default_rng(7)
    D = _day(rng, rise=0.12)
    c = [dict(er=21, side="C", strike=24900.0)]
    b0 = Y.price("NIFTY", D, c, "B0", XL, 1)[0]
    be = Y.price("NIFTY", D, c, "BE10", XL, 1)[0]
    assert b0["why"] == "stop" and b0["net"] < -500
    assert be["why"] == "lock" and abs(be["net"]) < 60 and be["xr"] < b0["xr"]
    assert Y.price("NIFTY", D, c, "BE15", XL, 1)[0]["why"] == "stop"     # +12% never reached +15%


def test_scale_out_sells_one_lot_at_target():
    rng = np.random.default_rng(8)
    D = _day(rng, rise=0.12)
    c = [dict(er=21, side="C", strike=24900.0)]
    so = Y.price("NIFTY", D, c, "SO10", XL, 2)[0]
    b0 = Y.price("NIFTY", D, c, "B0", XL, 2)[0]
    assert so["why"].startswith("so:target/") and so["xr1"] < so["xr"]
    assert abs(sum(so["rounds"]) - so["net"]) < 1e-6 and so["rounds"][0] > 0 > so["rounds"][1]
    assert so["net"] > b0["net"]
    assert Y.price("NIFTY", D, c, "SO15", XL, 2)[0]["why"].startswith("so:stop/")


def test_time_rules_and_finer_locks():
    rng = np.random.default_rng(9)
    D = _day(rng)
    c = [dict(er=100, side="C", strike=24900.0)]
    for rule, tm in (("T20", 20), ("T40", 40), ("T45", 45)):
        t = Y.price("NIFTY", D, c, rule, XL, 1)[0]
        assert t["xr"] <= t["er"] + tm - 1
    D2 = _day(np.random.default_rng(10), rise=0.12)
    c2 = [dict(er=21, side="C", strike=24900.0)]
    lf = Y.price("NIFTY", D2, c2, "LF2", XL, 1)[0]
    assert lf["why"] == "lock" and lf["net"] > 0                 # +10% peak locked +2%


def test_index_stop_rules_and_level_wall():
    rng = np.random.default_rng(11)
    D = _day(rng)
    D.idx[3, 60] = 24000.0                            # the index closes far below the signal bar's low at minute 60
    c = [dict(er=50, side="C", strike=24900.0)]
    t = Y.price("NIFTY", D, c, "IXA", XL, 1)[0]
    assert t["xr"] <= 61 and t["p_fx"] == 61
    r = Y.price("NIFTY", D, c, "IXR", XL, 1)[0]
    assert r["p_stop"] == -1.0 and r["p_fx"] == 61


def test_strike_rules_and_lookahead_wall():
    D = _day(np.random.default_rng(12))
    days = _days(D)
    c = [dict(er=40, side="C", strike=24900.0)]
    assert Y.price("NIFTY", D, c, "ATM", XL, 1, days)[0]["strike"] == 25000.0
    assert Y.price("NIFTY", D, c, "ITM2", XL, 1, days)[0]["strike"] == 24900.0
    assert Y.price("NIFTY", D, c, "B0", XL, 1, days)[0]["strike"] == 24900.0         # the candidate's own 1-ITM
    Y._DSTRIKE.clear()
    k1 = Y.strike_of("D70", "NIFTY", D, "C", 40, days)
    assert k1 in {24800.0, 24900.0, 25000.0, 25100.0, 25200.0}
    D3 = _day(np.random.default_rng(12))             # the same day, then everything at or after the entry row changes
    for key in D3.con:
        D3.con[key][:, 40:] *= 5.0
    D3.idx[:, 40:] = 30000.0
    Y._DSTRIKE.clear()
    assert Y.strike_of("D70", "NIFTY", D3, "C", 40, _days(D3)) == k1
    Y._DSTRIKE.clear()
    assert Y.strike_of("D60", "NIFTY", D, "C", 40, days) >= k1                       # lower delta, not deeper


def test_twin_nets_shape_and_none():
    rng = np.random.default_rng(13)
    D = _day(rng, rise=0.12)
    c = [dict(er=21, side="C", strike=24900.0)]
    b0 = dict(Y.price("NIFTY", D, c, "B0", XL, 1)[0], er_sig=21)
    be = dict(Y.price("NIFTY", D, c, "BE10", XL, 1)[0], er_sig=21)
    tw = Y.twin_nets("NIFTY", D, be, b0, 50, np.random.default_rng(0))
    assert tw is not None and tw.shape == (50,) and np.isfinite(tw).all()
    assert Y.twin_nets("NIFTY", D, b0, b0, 50, np.random.default_rng(0)) is None
    so = dict(Y.price("NIFTY", D, c, "SO10", XL, 2)[0], er_sig=21)
    b02 = dict(Y.price("NIFTY", D, c, "B0", XL, 2)[0], er_sig=21)
    tws = Y.twin_nets("NIFTY", D, so, b02, 40, np.random.default_rng(0))
    assert tws is not None and tws.shape == (40,)
