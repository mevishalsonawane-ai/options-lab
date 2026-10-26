"""R107 tests: the meters (GEX reading, the app's danger meter copy, event days), the paper book's capped walk, the risk
maths, the random-day twins, BH and the gates; plus the look-ahead guards (a reading never uses a row at or after the
entry; the danger meter's inputs use only earlier sessions' closes and today's first open).

    python -m pytest research/hunt/r107_meters_more -q
"""
from __future__ import annotations

import math
import random
import re
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r107cfg as K  # noqa: E402
import r107book as B  # noqa: E402
import r107meters as MT  # noqa: E402
import r107study as ST  # noqa: E402
import r106walk as W  # noqa: E402

KT = K.ROOT / "android" / "ira-core" / "src" / "main" / "kotlin" / "com" / "optionslab" / "ira" / "PreTradeChecks.kt"
NAN = float("nan")


# ------------------------------------------------------------------------------------------------ GEX meter
def test_gex_reading_slots():
    assert MT.gex_reading(5, -1.0, 2.0) == -1.0          # 09:20 entry: the 09:20 reading
    assert MT.gex_reading(44, -1.0, 2.0) == -1.0         # 09:59 entry: still 09:20's
    assert MT.gex_reading(45, -1.0, 2.0) == 2.0          # 10:00 entry: the 10:00 reading
    assert math.isnan(MT.gex_reading(4, -1.0, 2.0))      # before the 09:20 bar closed: none
    assert MT.is_neg(-0.1) and not MT.is_neg(0.0) and not MT.is_neg(NAN) and not MT.is_neg(None)


def test_lookahead_gex_reading_row_is_before_entry():
    """The bar a trade's reading comes from always closes before the trade's entry row opens."""
    for er in range(0, 375):
        v = MT.gex_reading(er, 0.0920, 0.1000)
        if math.isnan(v):
            assert er <= K.ROW_0920
            continue
        used = K.ROW_0920 if v == 0.0920 else K.ROW_1000
        assert used < er


def test_lookahead_load_gex_ignores_later_rows(tmp_path, monkeypatch):
    pd = pytest.importorskip("pandas")
    pytest.importorskip("pyarrow")
    d = date(2025, 1, 2)
    rows = [dict(und="NIFTY", day=d, minute=m, near_net_cr=float(m)) for m in range(0, 375)]
    pd.DataFrame(rows).to_parquet(tmp_path / "gex_NIFTY.parquet", index=False)
    monkeypatch.setattr(K, "GEX_DIR", tmp_path)
    a = MT.load_gex("NIFTY")
    rows2 = [dict(r, near_net_cr=(-999.0 if r["minute"] > K.ROW_1000 else r["near_net_cr"])) for r in rows]
    pd.DataFrame(rows2).to_parquet(tmp_path / "gex_NIFTY.parquet", index=False)
    b = MT.load_gex("NIFTY")
    assert a == b == {d: (float(K.ROW_0920), float(K.ROW_1000))}


# ------------------------------------------------------------------------------------------------ danger meter
def test_danger_meter_app_cases():
    # the app's own test cases (PreTradeChecksTest.dangerMeterReadsTheDayAndSaysItsRecord)
    assert MT.danger_read(0.05, 11.0, 0.0, True, True, date(2026, 10, 27))[0] == "paper-only"     # a Tuesday
    assert MT.danger_read(0.5, 13.0, 0.0, False, False, date(2026, 10, 26))[0] == "normal"        # a Monday
    assert MT.danger_read(0.05, 13.0, 0.0, False, True, date(2026, 10, 23))[0] == "careful"       # a Friday
    assert MT.danger_read(None, None, None, False, False, date(2026, 10, 21))[0] == "normal"
    assert MT.danger_buckets(-1.0, 15.0, -0.08, False, False, date(2026, 10, 26))["gap"] == "large"
    b = MT.danger_buckets(-1.0, 11.5, -0.08, False, False, date(2026, 10, 26))
    assert b["vix_chg"] == "down" and b["vix"] == "mid"
    assert len(MT.danger_buckets(-0.2, 15.0, 0.08, False, False, date(2026, 10, 24))) == 6       # a Saturday
    assert MT.danger_read(None, NAN, math.inf, False, False, date(2026, 10, 21))[1] == round(55.3 + 124.8 + 55.3, 1)


def test_danger_meter_matches_the_app_source():
    """The Python copy carries exactly the app's buckets and cut points (guards against drift)."""
    if not KT.exists():
        pytest.skip("android source not in this checkout")
    src = KT.read_text()
    model = {(f, b): (int(n), float(m), float(dv)) for f, b, n, m, dv in
             re.findall(r'Bucket\("(\w+)", "(\w+)", (\d+), (-?[\d.]+), (-?[\d.]+)\)', src)}
    assert model == MT.MODEL
    assert f"CAREFUL_AT = {MT.CAREFUL_AT}" in src and f"PAPER_ONLY_AT = {MT.PAPER_ONLY_AT}" in src
    assert f"GAP_CUTS = {MT.GAP_CUTS[0]} to {MT.GAP_CUTS[1]}" in src
    assert f"VIX_CUTS = {MT.VIX_CUTS[0]} to {MT.VIX_CUTS[1]}" in src
    assert f"VIX_MOVE = {MT.VIX_MOVE}" in src


def _sessions(n=40, start=date(2025, 1, 1)):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def test_lookahead_danger_inputs_use_only_earlier_closes():
    s = _sessions(10)
    o = {d: 100.0 + i for i, d in enumerate(s)}
    c = {d: 101.0 + i for i, d in enumerate(s)}
    v = {d: 12.0 + 0.1 * i for i, d in enumerate(s)}
    a = MT.danger_inputs(s, o, c, v)
    k = s[5]
    c2 = {**c, **{d: 999.0 for d in s[5:]}}           # today's and later closes change
    v2 = {**v, **{d: 99.0 for d in s[5:]}}
    o2 = {**o, **{d: 1.0 for d in s[6:]}}             # later opens change
    b = MT.danger_inputs(s, o2, c2, v2)
    assert a[k] == b[k]
    gap, vp, vc = a[k]
    assert gap == pytest.approx((o[k] - c[s[4]]) / c[s[4]] * 100)
    assert vp == v[s[4]] and vc == pytest.approx(v[s[4]] / v[s[3]] - 1)
    assert a[s[0]] == (None, None, None) and a[s[1]][2] is None


def test_event_days():
    s = [d for d in _sessions(400, date(2024, 11, 20)) if d <= date(2026, 3, 13)]
    ev = MT.event_days(s)
    assert date(2025, 2, 1) in ev and date(2025, 4, 9) in ev and date(2026, 4, 8) in ev       # budget, RBI, 2026 bundle
    reb = MT.rebalance_days(s)
    assert date(2025, 3, 31) in reb and date(2025, 9, 30) in reb and date(2025, 2, 28) in reb
    assert date(2025, 4, 30) not in reb                                                         # April is no rebalance month
    assert not any(d.month == 3 and d.year == 2026 for d in reb)                                # a month cut short by the data


# ------------------------------------------------------------------------------------------------ the capped walk
def _rand_day(rng, n_units=6):
    units, priced = [], []
    for g in range(n_units):
        how = rng.choice(["book", "side2", "one"])
        cands, res = [], []
        er = rng.randint(5, 40)
        for _ in range(rng.randint(1, 6)):
            er += rng.randint(0, 30)
            side = rng.choice("CP")
            c = dict(er=er, side=side, und=K.UNDS[g % 5], book=rng.choice([5, 15]) if how == "book" else None)
            cands.append(c)
            res.append(None if rng.random() < 0.15 else dict(und=c["und"], er=er, xr=er + rng.randint(1, 60),
                                                             net=rng.uniform(-3000, 3000), side=side))
        units.append(((c["und"], date(2025, 1, 2), g), cands, how))
        priced.append(res)
    return units, priced


def test_walk_cap_without_cap_is_r106_walk():
    rng = random.Random(7)
    for _ in range(300):
        units, priced = _rand_day(rng)
        a = B.walk_cap(units, priced, None)
        b = W.walk_day(units, priced)
        assert [(t["und"], t["er"], t["net"]) for t in a] == [(t["und"], t["er"], t["net"]) for t in b]


def test_walk_cap_refuses_and_frees():
    def u(g, cands, how="side2"):
        return ((K.UNDS[g], date(2025, 1, 2)), cands, how)
    c = [dict(er=10, side="P", und=K.UNDS[g]) for g in range(4)]
    r = [dict(er=10, xr=30, net=1.0 * g) for g in range(4)]
    units = [u(g, [c[g]]) for g in range(4)]
    pr = [[r[g]] for g in range(4)]
    assert len(B.walk_cap(units, pr, lambda c_, r_: 2)) == 2
    assert len(B.walk_cap(units, pr, lambda c_, r_: 3)) == 3
    assert [t["und"] for t in B.walk_cap(units, pr, lambda c_, r_: 2)] == ["NIFTY", "BANKNIFTY"]   # tie order
    # an opposite bet is not counted; a closed position frees its place
    c5 = dict(er=12, side="C", und="SENSEX")
    c6 = dict(er=31, side="P", und="SENSEX")
    units2 = units + [u(4, [c5, c6])]
    pr2 = pr + [[dict(er=12, xr=20, net=5.0), dict(er=31, xr=40, net=6.0)]]
    t = B.walk_cap(units2, pr2, lambda c_, r_: 2)
    assert {(x["und"], x["side"], x["er"]) for x in t} == {("NIFTY", "P", 10), ("BANKNIFTY", "P", 10),
                                                         ("SENSEX", "C", 12), ("SENSEX", "P", 31)}
    # a refused entry leaves the arm's book free: its next signal is taken
    c7 = [dict(er=10, side="P", und="FINNIFTY"), dict(er=31, side="P", und="FINNIFTY")]
    units3 = units[:2] + [u(2, c7, "one")]
    pr3 = pr[:2] + [[dict(er=10, xr=50, net=1.0), dict(er=31, xr=50, net=2.0)]]
    t3 = B.walk_cap(units3, pr3, lambda c_, r_: 2)
    assert ("FINNIFTY", 31) in {(x["und"], x["er"]) for x in t3}
    assert ("FINNIFTY", 10) not in {(x["und"], x["er"]) for x in t3}


def test_direction():
    assert B.direction("C") == 1 and B.direction("CE") == 1 and B.direction("P") == -1 and B.direction("PE") == -1


# ------------------------------------------------------------------------------------------------ risk maths
def test_risk_and_account_rule():
    s = _sessions(6, date(2025, 1, 27))           # Mon 27 Jan .. Mon 3 Feb
    v = np.array([1000.0, -15000.0, -30000.0, 2000.0, 50000.0, -45000.0])
    r = B.risk(v, s)
    assert r["net"] == -37000 and r["worst_day"] == -45000 and r["max_dd"] == 45000
    assert r["r_dd"] == pytest.approx(-37000 / 45000) and r["r_wd"] == pytest.approx(-37000 / 45000)
    assert r["months"] == 2 and r["worst_month"] == -45000 and r["months_below"] == 1
    assert r["dd_over"] and r["dd_breaches"] == 2
    assert B.ratio(5.0, 0.0) == math.inf and B.ratio(-5.0, 0.0) == 0.0
    assert B.max_dd(np.array([1.0, 2.0])) == 0.0


def test_matrix_ratios_match_risk():
    rng = np.random.default_rng(1)
    M = rng.normal(100, 2000, size=(50, 30))
    M[0] = np.abs(M[0])
    a, b = B.matrix_ratios(M)
    for i in range(50):
        r = B.risk(M[i])
        assert a[i] == pytest.approx(r["r_dd"]) and b[i] == pytest.approx(r["r_wd"])


def test_daily_vector_and_lots():
    s = _sessions(3)
    tr = [dict(day=s[0], net=100.0, net2=190.0), dict(day=s[2], net=-50.0, net2=-110.0), dict(day=date(2020, 1, 1), net=9.0, net2=9.0)]
    assert list(B.daily_vector(tr, s)) == [100.0, 0.0, -50.0]
    assert list(B.daily_vector(tr, s, lots_of=lambda t: t["net"] < 0)) == [100.0, 0.0, -110.0]


# ------------------------------------------------------------------------------------------------ twins
def test_twin_day_size_identity_and_counts():
    rng = np.random.default_rng(2)
    base = rng.normal(0, 100, 20)
    delta = rng.normal(0, 100, 20)
    flags = rng.random(20) < 0.3
    ident = np.tile(np.arange(20), (3, 1))
    T = B.twin_day_size(base, delta, flags, ident)
    assert np.allclose(T, base + flags * delta)
    P = B.perm_matrix(500, 20, rng)
    assert all(sorted(row) == list(range(20)) for row in P[:20])
    assert (flags[P].sum(axis=1) == flags.sum()).all()


def test_twin_index_size_identity():
    rng = np.random.default_rng(3)
    n = 15
    base = rng.normal(0, 100, n)
    flags = {u: rng.random((n, 2)) < 0.4 for u in K.UNDS}
    deltas = {(u, s): rng.normal(0, 50, n) for u in K.UNDS for s in (0, 1)}
    perms = {u: np.tile(np.arange(n), (2, 1)) for u in K.UNDS}
    T = B.twin_index_size(base, deltas, flags, perms)
    want = base + sum(flags[u][:, s] * deltas[(u, s)] for u in K.UNDS for s in (0, 1))
    assert np.allclose(T[0], want) and np.allclose(T[1], want)


def test_twin_p_and_bh():
    assert B.twin_p(5.0, np.array([1.0, 2.0, 6.0])) == pytest.approx(2 / 4)
    assert B.twin_p(9.0, np.zeros(9999)) == pytest.approx(1 / 10000)
    q = B.bh([0.001, 0.02, 0.5])
    assert q[0] == pytest.approx(0.003) and q[1] == pytest.approx(0.03) and q[2] == pytest.approx(0.5)
    q2 = B.bh([0.0001] * 11, 584)
    assert q2.max() == pytest.approx(0.0001 * 584 / 11)
    assert B.bh([0.0002], 584)[0] > K.GATE_Q          # with 584 tests one p of 0.0002 cannot pass


def test_code_flags_roundtrip_and_slots():
    for x in range(4 ** 5):
        f = ST.code_flags(x)
        back = sum((2 * int(f[u][0]) + int(f[u][1])) * 4 ** i for i, u in enumerate(K.UNDS))
        assert back == x
    assert ST.slot_of(44) == 0 and ST.slot_of(45) == 1


# ------------------------------------------------------------------------------------------------ gates
def _r(net, rdd, rwd):
    return dict(net=net, r_dd=rdd, r_wd=rwd)


def test_period_checks_and_gates():
    v, b, a, t = _r(10, 2.0, 1.0), _r(8, 1.5, 0.8), _r(16, 1.4, 0.7), _r(9, 1.6, 0.9)
    assert B.period_checks(v, b, a, t) == []
    assert "net not > 0" in B.period_checks(_r(-1, 2.0, 1.0), b, a, t)
    assert "net / max DD not above the twins' median" in B.period_checks(_r(10, 1.55, 1.0), b, a, _r(9, 1.6, 0.9))
    per = {p: (v, b, a, t) for p in K.PERIODS}
    assert B.gates(per, 0.05, 40)["passed"]
    g = B.gates(per, 0.2, 40)
    assert not g["passed"] and any("BH" in x for x in g["fails"])
    assert not B.gates(per, 0.05, 10)["passed"]
    per2 = dict(per, wf=(_r(10, 1.0, 1.0), b, a, t))
    assert any(x.startswith("wf:") for x in B.gates(per2, 0.05, 40)["fails"])


def test_family_size():
    assert len(K.TEST_IDS) == 11 <= 12
    assert K.N_PRIOR == 573
