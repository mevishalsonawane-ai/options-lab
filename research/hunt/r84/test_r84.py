"""R84 tests: synthetic data only (no lake, no secrets), plus one data check that runs only where R51's clean stores
exist. The day walker against per-index trade-taking, every chooser on hand-made signals, the open-position cap, the
break size and shock ratio with a LOOK-AHEAD GUARD, the random twins, the pass rule and 19 Oct's cost table.

    python -m pytest research/hunt/r84/test_r84.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r72"))
sys.path.insert(0, str(HERE.parent / "r71"))
import r84rules as P  # noqa: E402
import r84day19 as D19  # noqa: E402

D0 = date(2025, 3, 3)


def cand(und, er, side="P", xr=None, net=-100.0, size=1.0, risk=2000.0, shock=1.0, fill=True, day=D0):
    res = dict(und=und, day=day, er=er, xr=er + 10 if xr is None else xr, side=side, sig=er, net=float(net)) if fill \
        else None
    return dict(und=und, day=day, er=er, side=side, sig=er, res=res, size=size, risk=risk, shock=shock)


def per_index_take(cands):
    """R64's take (one open a side, 2 a side a day) run index by index: the reference K0 must equal."""
    out = []
    for u in P.ORDER:
        busy, n = {}, {}
        for c in sorted([c for c in cands if c["und"] == u], key=lambda c: c["er"]):
            r = c["res"]
            if r is None or c["er"] <= busy.get(c["side"], -1) or n.get(c["side"], 0) >= 2:
                continue
            busy[c["side"]] = r["xr"]
            n[c["side"]] = n.get(c["side"], 0) + 1
            out.append(c)
    return out


def k0(key, el, shock):
    return P.choose("K0", el, shock)


# ================================================================================================ walker
def test_walker_full_set_equals_per_index_take():
    rng = np.random.default_rng(1)
    for _ in range(200):
        cs = []
        for er in sorted(rng.choice(np.arange(5, 290, 5), size=12, replace=False)):
            side = "P" if rng.random() < 0.5 else "C"
            for u in P.ORDER:
                if rng.random() < 0.7:
                    cs.append(cand(u, int(er), side, xr=int(er + rng.integers(1, 40)), fill=rng.random() > 0.1))
        got, _ = P.walk_day(cs, k0)
        ref = per_index_take(cs)
        key = lambda c: (c["und"], c["er"], c["side"])  # noqa: E731
        assert sorted(map(key, got)) == sorted(map(key, ref))


def test_walker_dropped_index_reenters_next_bar():
    """The finding behind amendment A1: a per-signal cap leaves the other indices free for the next bar."""
    cs = [cand(u, 20) for u in P.ORDER] + [cand(u, 25) for u in P.ORDER]
    got, sigs = P.walk_day(cs, lambda k, el, s: P.choose("K2-first", el, s))
    assert [(c["und"], c["er"]) for c in got] == [("NIFTY", 20), ("BANKNIFTY", 20), ("FINNIFTY", 25), ("MIDCPNIFTY", 25)]
    assert sigs == {(20, "P"): 5, (25, "P"): 3}


def test_open_cap_counts_open_positions():
    cs = [cand(u, 20, xr=30) for u in P.ORDER] + [cand(u, 25, xr=60) for u in P.ORDER] + \
         [cand(u, 35, xr=70) for u in P.ORDER]
    got, _ = P.walk_day(cs, lambda k, el, s: P.choose("K2-open", el, s), open_cap=2)
    # 20: NIFTY+BN (open to 30); 25: none (2 open); 35: both closed at 30 -> two more (first in order, not busy)
    assert [(c["und"], c["er"]) for c in got] == [("NIFTY", 20), ("BANKNIFTY", 20), ("NIFTY", 35), ("BANKNIFTY", 35)]
    for er in range(0, 80):
        assert sum(1 for c in got if c["er"] <= er <= c["res"]["xr"]) <= 2


def test_open_cap_is_per_side():
    cs = [cand(u, 20, "P", xr=50) for u in P.ORDER] + [cand(u, 25, "C", xr=50) for u in P.ORDER]
    got, _ = P.walk_day(cs, lambda k, el, s: P.choose("K2-open", el, s), open_cap=2)
    assert sum(c["side"] == "P" for c in got) == 2 and sum(c["side"] == "C" for c in got) == 2


def test_walker_empty_and_no_fill():
    assert P.walk_day([], k0) == ([], {})
    got, sigs = P.walk_day([cand("NIFTY", 20, fill=False)], k0)
    assert got == [] and sigs == {}


# ================================================================================================ choosers
EL = [cand("NIFTY", 20, size=0.5, risk=3000), cand("BANKNIFTY", 20, size=2.0, risk=2500),
      cand("FINNIFTY", 20, size=1.0, risk=2000), cand("SENSEX", 20, size=2.0, risk=1000)]


def us(lst):
    return [c["und"] for c in lst]


def test_choosers():
    assert us(P.choose("K0", EL)) == ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"]
    assert us(P.choose("K2-first", EL)) == ["NIFTY", "BANKNIFTY"]
    assert us(P.choose("K2-strong", EL)) == ["BANKNIFTY", "SENSEX"]      # tie 2.0: index order
    assert us(P.choose("K1-strong", EL)) == ["BANKNIFTY"]
    assert us(P.choose("K1-BN", EL)) == ["BANKNIFTY"]
    assert us(P.choose("K1-BN", [c for c in EL if c["und"] != "BANKNIFTY"])) == ["SENSEX"]
    assert us(P.choose("X-skip", EL, 5.01)) == [] and len(P.choose("X-skip", EL, 5.0)) == 4
    assert len(P.choose("X-skip", EL, None)) == 4                         # unknown ratio: no skip
    assert P.choose("X-K2", EL, 9.0) == [] and us(P.choose("X-K2", EL, 1.0)) == ["BANKNIFTY", "SENSEX"]
    with pytest.raises(ValueError):
        P.choose("nope", EL)


def test_rb_budget():
    # strongest first: BN 2500, SENSEX 1000 (3500), FIN 2000 -> 5500 > 5000 no, NIFTY 3000 no
    assert us(P.choose("RB", EL)) == ["BANKNIFTY", "SENSEX"]
    big = [cand("NIFTY", 20, size=3, risk=9000), cand("BANKNIFTY", 20, size=1, risk=100)]
    assert us(P.rb_pick(big)) == ["NIFTY"]                                 # the first always, then over budget
    unk = [cand("NIFTY", 20, size=3, risk=None), cand("BANKNIFTY", 20, size=2, risk=None),
           cand("SENSEX", 20, size=1, risk=4000)]
    assert us(P.rb_pick(unk)) == ["NIFTY", "SENSEX"]                       # unknown risk never added (unless first)
    assert P.rb_pick([]) == []


def test_keep_count_and_twin_same_count():
    rng = np.random.default_rng(0)
    for v in ("K2-first", "K2-strong", "K1-strong", "K1-BN", "RB"):
        f = P.twin_chooser(v, rng)
        for _ in range(50):
            got = f((D0, 20, "P"), EL, 1.0)
            assert len(got) == P.keep_count(v, EL) == len(P.choose(v, EL, 1.0))
            assert set(us(got)) <= set(us(EL))
    seen = {tuple(us(P.twin_chooser("K1-strong", np.random.default_rng(s))((D0, 20, "P"), EL, 1.0))) for s in range(40)}
    assert len(seen) == 4                                                  # random, not the strongest
    f = P.twin_chooser("X-skip", rng, {(D0, 20, "P")})
    assert f((D0, 20, "P"), EL, 99.0) == [] and len(f((D0, 25, "P"), EL, 99.0)) == 4   # skips its own random set
    g = P.twin_chooser("K2-open", rng)
    assert sorted(us(g((D0, 20, "P"), EL, 1.0))) == sorted(us(EL))         # a permutation; the walker trims it


# ================================================================================================ inputs + look-ahead
def test_break_size():
    assert P.break_size("P", 99.0, 110.0, 100.0, 2.0) == pytest.approx(0.5)
    assert P.break_size("C", 112.0, 110.0, 100.0, 4.0) == pytest.approx(0.5)
    assert P.break_size("P", 99.0, 110.0, 100.0, float("nan")) == 0.0
    assert P.break_size("P", 99.0, 110.0, float("nan"), 2.0) == 0.0
    assert P.break_size("C", 99.0, 110.0, 100.0, 0.0) == 0.0


def series(n_days=30, seed=3, start=100.0):
    rng = np.random.default_rng(seed)
    d, b, o, h, l, c = [], [], [], [], [], []
    px = start
    for k in range(n_days):
        for bar in range(75):
            oo = px
            cc = px * (1 + rng.normal(0, 0.001))
            d.append(D0 + timedelta(days=k))
            b.append(bar)
            o.append(oo)
            c.append(cc)
            h.append(max(oo, cc) * 1.0002)
            l.append(min(oo, cc) * 0.9998)
            px = cc
    return [np.array(d, dtype=object), np.array(b), np.array(o), np.array(h), np.array(l), np.array(c)]


def test_ten_min_moves_and_usual():
    d, b, o, h, l, c = series(25)
    from r71rules import day_starts
    mv = P.ten_min_moves(o, c, day_starts(d))
    assert mv[0] == pytest.approx(abs(c[0] / o[0] - 1))                  # day's first bar: its own open
    assert mv[1] == pytest.approx(abs(c[1] / o[0] - 1))
    assert mv[75] == pytest.approx(abs(c[75] / o[75] - 1))                # next day restarts
    u = P.usual_by_day(d, mv)
    days = sorted(set(d))
    assert days[9] not in u and days[10] in u                             # needs 10 earlier sessions
    assert u[days[24]] == pytest.approx(float(np.median(mv[(24 - 20) * 75:24 * 75])))
    assert P.shock_ratio(0.01, 0.002) == pytest.approx(5.0)
    assert P.shock_ratio(0.01, None) is None and P.shock_ratio(float("nan"), 0.1) is None
    assert P.shock_ratio(0.01, 0.0) is None


def test_lookahead_shock_and_usual():
    """Changing bars after the signal bar (and the rest of the day) never changes that bar's move or the day's usual."""
    d, b, o, h, l, c = series(25)
    from r71rules import day_starts
    j = 24 * 75 + 10
    mv = P.ten_min_moves(o, c, day_starts(d))
    u = P.usual_by_day(d, mv)
    o2, c2 = o.copy(), c.copy()
    o2[j + 1:] *= 1.05
    c2[j + 1:] *= 0.95
    mv2 = P.ten_min_moves(o2, c2, day_starts(d))
    u2 = P.usual_by_day(d, mv2)
    assert mv2[j] == mv[j] and u2[d[j]] == u[d[j]]


def test_lookahead_break_size_on_panel():
    import r72rules as X
    data = {un: series(8, seed=k) for k, un in enumerate(P.ORDER)}
    Pn = X.Panel({k: tuple(v) for k, v in data.items()})
    f = Pn.f["BANKNIFTY"]
    j = 6 * 75 + 30
    s1 = P.break_size("P", f["c"][j], f["chi"][j], f["clo"][j], f["atr"][j])
    bump = {k: list(v) for k, v in data.items()}
    for i in (2, 3, 4, 5):
        arr = bump["BANKNIFTY"][i].copy()
        arr[j + 1:] *= 1.07
        bump["BANKNIFTY"][i] = arr
    Pn2 = X.Panel({k: tuple(v) for k, v in bump.items()})
    f2 = Pn2.f["BANKNIFTY"]
    s2 = P.break_size("P", f2["c"][j], f2["chi"][j], f2["clo"][j], f2["atr"][j])
    assert s1 == s2


# ================================================================================================ metrics, gate, 19 Oct
def test_signal_nets():
    t = [dict(day=D0, sig=20, side="P", net=-100.0), dict(day=D0, sig=20, side="P", net=-50.0),
         dict(day=D0, sig=25, side="P", net=10.0)]
    assert P.signal_nets(t) == {(D0, 20, "P"): -150.0, (D0, 25, "P"): 10.0}
    assert P.signal_nets([]) == {}


def _rec(net, worst, dd, ex5, wf):
    return dict(design=dict(net=net, worst_day=worst, max_dd=dd, ex_top5=ex5), wf_net=wf)


def test_gate():
    k0 = _rec(100000, -50000, 200000, 60000, 80000)
    ok = _rec(75000, -34000, 139000, 45000, 60000)
    assert P.gate(ok, k0, 0.05, 0.08) == []
    assert len(P.gate(_rec(75000, -36000, 139000, 45000, 60000), k0, 0.05, 0.08)) == 1   # worst day only -28%
    assert len(P.gate(_rec(75000, -34000, 141000, 45000, 60000), k0, 0.05, 0.08)) == 1   # DD only -29.5%
    assert len(P.gate(_rec(69000, -34000, 139000, 45000, 60000), k0, 0.05, 0.08)) == 1   # net < 70%
    assert len(P.gate(_rec(75000, -34000, 139000, 45000, 55000), k0, 0.05, 0.08)) == 1   # WF < 70%
    assert len(P.gate(ok, k0, 0.10, 0.05)) == 1 and len(P.gate(ok, k0, 0.05, 0.10)) == 1
    assert len(P.gate(_rec(75000, -34000, 139000, 41000, 60000), k0, 0.05, 0.08)) == 1   # top-5 carried
    assert len(P.gate(_rec(75000, -34000, 139000, -1, 60000), _rec(100000, -50000, 200000, -10, 80000), 0.0, 0.0)) == 1


def test_bh_from_r75():
    sys.path.insert(0, str(HERE.parent / "r75"))
    import r75levers as L
    q = L.bh([0.01, 0.04, 0.03, 0.5])
    assert q.tolist() == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], rel=1e-4)


def test_day19_costs():
    sizes = {"BANKNIFTY": 1.437, "FINNIFTY": 2.571, "MIDCPNIFTY": 1.296, "SENSEX": 2.611}
    c = D19.costs(sizes, 14.5)
    assert c["K0"]["cost"] == -9722 and c["X-skip"]["cost"] == 0 and c["X-K2"]["cost"] == 0
    assert c["K2-first"]["keep"] == ["BANKNIFTY", "FINNIFTY"] and c["K2-first"]["cost"] == -5049
    assert c["K2-strong"]["keep"] == ["SENSEX", "FINNIFTY"] and c["K2-strong"]["cost"] == -4374
    assert c["K1-strong"]["cost"] == -1765 and c["K1-BN"]["cost"] == -2440
    assert c["RB"]["keep"] == ["SENSEX", "FINNIFTY"]                       # 1765 + 2609 <= 5000, + 2440 > 5000
    assert c["K2-open"]["cost"] == -5049
    assert D19.costs(sizes, 2.0)["X-skip"]["cost"] == -9722                # a calm bar: X-skip takes all


def test_day19_bars5_matches_r44_logic():
    a = np.full((4, 375), np.nan)
    a[:, 0:4] = np.array([[10, 11, 12, 13], [12, 13, 14, 15], [9, 10, 11, 12], [11, 12, 13, 14]], float)
    d, b, o, h, l, c = D19.bars5({D0: a})
    assert list(b) == [0] and o[0] == 10 and h[0] == 15 and l[0] == 9 and c[0] == 14
    assert len(D19.bars5({})[0]) == 0


# ================================================================================================ real data (optional)
try:
    sys.path.insert(0, str(HERE.parent / "r71"))
    import r71cfg as _C
    _STORE = Path(_C.CACHE_ROOT) / "r51" / "clean" / "day_NIFTY.pkl"
except Exception:                       # pragma: no cover
    _STORE = Path("/nonexistent")


@pytest.mark.skipif(not _STORE.exists(), reason="R51 clean stores not built here")
def test_k0_walker_reproduces_r75_design():
    import r84study as St
    dc = St.day_cands(St.R75C.START, St.R75C.DESIGN_END)
    tr, _ = St.book(dc, St.variant_chooser("K0"))
    assert round(sum(t["net"] for t in tr)) == St.EXPECT["design"]
