"""R73 tests: synthetic bars only (no lake, no secrets), plus one data check that runs only where R51's clean stores
exist. The trend filters and waits on hand-made charts, the LOOK-AHEAD GUARD (every filter value at the signal bar and
every wait decision is unchanged when everything after it is scrambled), and the study's pure pieces (C's pick, skip
twins, matching of skipped trades, gates, decision).

    python -m pytest research/hunt/r73/test_r73.py -q
"""
from __future__ import annotations

import importlib.util
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r73cfg as R  # noqa: E402
import r73rules as P  # noqa: E402
import r73study as X  # noqa: E402

D0 = date(2025, 1, 6)


def walk(n_days=6, seed=1, bars=75):
    """A random-walk 5-minute series over n_days sessions (the flat arrays fam_r44.bars5 gives)."""
    rng = np.random.default_rng(seed)
    d, b, o, h, l, c = [], [], [], [], [], []
    px = 20000.0
    for k in range(n_days):
        day = D0 + timedelta(days=k)
        for j in range(bars):
            op = px
            px = px + rng.normal(0, 15)
            d.append(day)
            b.append(j)
            o.append(op)
            h.append(max(op, px) + abs(rng.normal(0, 5)))
            l.append(min(op, px) - abs(rng.normal(0, 5)))
            c.append(px)
    return np.array(d, dtype=object), np.array(b), np.array(o), np.array(h), np.array(l), np.array(c)


def minutes(seed=2, n=375, start=20000.0):
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, 3, n))
    o = np.concatenate([[start], c[:-1]])
    h = np.maximum(o, c) + 1
    l = np.minimum(o, c) - 1
    return np.vstack([o, h, l, c])


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


# ------------------------------------------------------------------------------------------------ indicators
def test_adx_matches_r44():
    pytest.importorskip("numba")
    sys.path.insert(0, str(HERE.parent / "r44"))
    ind = _load(HERE.parent / "r44" / "ind.py", "r73_test_r44_ind")
    _, _, _, h, l, c = walk(3)
    a, b = P.adx(h, l, c, 14), ind.adx(h, l, c, 14)
    assert np.allclose(a, b, equal_nan=True)
    assert np.isnan(a[:27]).all() and np.isfinite(a[27:]).all()


def test_supertrend_matches_r63():
    r63 = _load(HERE.parent / "r63" / "r63rules.py", "r73_test_r63_rules")
    _, _, _, h, l, c = walk(3, seed=5)
    assert (P.supertrend_dir(h, l, c, 10, 3.0) == r63.supertrend_dir(h, l, c, 10, 3.0)).all()


def test_channel_is_the_donchian_edge():
    _, _, _, h, l, c = walk(2, seed=3)
    hi, lo = P.channel(h, l)
    assert np.isnan(hi[:20]).all()
    assert hi[25] == h[5:25].max() and lo[25] == l[5:25].min()
    # the R44 rule: close > max(high of the prior 20 bars)
    up = np.zeros(len(c), bool)
    up[20:] = c[20:] > hi[20:]
    from numpy.lib.stride_tricks import sliding_window_view as sw
    ref = np.zeros(len(c), bool)
    ref[20:] = c[20:] > sw(h, 20).max(axis=1)[:-1]
    assert (up == ref).all()


def test_htf_slope_rising_and_falling():
    d = np.array([D0] * 12, dtype=object)
    b = np.arange(12)
    c = np.array([1, 1, 1, 2, 2, 2, 3, 3, 3, 1, 1, 1], float)      # 15-min closes 1, 2, 3, 1
    s = P.htf_slope(d, b, c, 3, n=2)
    assert s[4] == 0                                             # slot 1 not closed yet: only slot 0 known
    assert s[5] == 1                                             # slot 1 closes at bar 5: EMA 2 > EMA 1
    assert s[7] == 1 and s[8] == 1
    assert s[10] == 1 and s[11] == -1                           # slot 3 (close 1) known only at bar 11


def test_htf_slope_slot_with_missing_last_bar_counts_when_the_next_slot_starts():
    d = np.array([D0] * 5, dtype=object)
    b = np.array([0, 1, 2, 3, 4 + 2])                           # bars 4, 5 missing: slot 1 = bar 3 only
    c = np.array([1, 1, 1, 5, 9], float)
    s = P.htf_slope(d, b, c, 3, n=2)
    assert s[3] == 0                                             # bar 3 is not slot 1's last bar
    assert s[4] == 1                                             # at bar b=6 (slot 2 starts) slot 1 is closed


def test_tp_avg_reads_only_up_to_the_row_and_skips_gaps():
    m = minutes()
    m[:, 3] = np.nan
    want = np.mean([(m[1, r] + m[2, r] + m[3, r]) / 3 for r in (0, 1, 2, 4)])
    assert P.tp_avg(m, 4) == pytest.approx(want)
    e = np.full((4, 375), np.nan)
    assert np.isnan(P.tp_avg(e, 10))


# ------------------------------------------------------------------------------------------------ filters
def test_trend_filters_mirror_for_puts_and_fail_on_nan():
    F = dict(c=np.array([10.0]), e20=np.array([9.0]), e50=np.array([11.0]), adx=np.array([22.0]),
             st=np.array([1]), s15=np.array([1]), s60=np.array([-1]))
    assert P.trend_ok("T1", F, 0, 1) and not P.trend_ok("T1", F, 0, -1)
    assert not P.trend_ok("T2", F, 0, 1) and P.trend_ok("T2", F, 0, -1)
    assert P.trend_ok("T3", F, 0, 1) and not P.trend_ok("T3", F, 0, -1)
    assert P.trend_ok("T4", F, 0, -1) and not P.trend_ok("T4", F, 0, 1)
    assert P.trend_ok("T5", F, 0, 1) and P.trend_ok("T5", F, 0, -1) and not P.trend_ok("T6", F, 0, 1)
    assert P.trend_ok("T7", F, 0, 1) and not P.trend_ok("T7", F, 0, -1)
    assert P.trend_ok("T8", F, 0, 1, 9.5) and not P.trend_ok("T8", F, 0, -1, 9.5)
    assert not P.trend_ok("T8", F, 0, 1, float("nan"))
    Fn = dict(F, adx=np.array([np.nan]), e20=np.array([np.nan]))
    assert not P.trend_ok("T5", Fn, 0, 1) and not P.trend_ok("T1", Fn, 0, 1) and not P.trend_ok("T1", Fn, 0, -1)
    st0 = dict(F, st=np.array([0]), s15=np.array([0]))
    assert not P.trend_ok("T7", st0, 0, 1) and not P.trend_ok("T7", st0, 0, -1) and not P.trend_ok("T3", st0, 0, -1)


def test_wait_rules():
    m = np.full((4, 375), np.nan)
    b = 10                                                       # signal bar 10 -> rows 55.. after it
    m[3, 55:60] = [101, 102, 103, 104, 105]
    assert P.wait_entry(m, b, 1, 100.0, 100.5, "W5H") == 60
    assert P.wait_entry(m, b, 1, 100.0, 100.5, "W5D") == 60
    assert P.wait_entry(m, b, 1, 100.0, 100.5, "W5HD") == 60
    assert P.wait_entry(m, b, 1, 100.0, 100.5, "W2H") == 57
    m[3, 56] = 99.0                                              # a 1-minute close back inside the channel
    assert P.wait_entry(m, b, 1, 100.0, 100.5, "W5H") is None
    assert P.wait_entry(m, b, 1, 100.0, 100.5, "W5D") == 60      # the 5th close is still above the signal close
    assert P.wait_entry(m, b, 1, 100.0, 105.0, "W5D") is None    # equal is not above
    m2 = np.full((4, 375), np.nan)
    m2[3, 55:58] = [99, 98, 97]
    assert P.wait_entry(m2, b, -1, 100.0, 99.5, "W3HD") == 58     # puts: the mirror
    m2[3, 56] = np.nan
    assert P.wait_entry(m2, b, -1, 100.0, 99.5, "W3H") is None    # a missing candle in the wait: no trade
    assert P.wait_entry(m2, 74, -1, 100.0, 99.5, "W2H") is None   # past the session
    assert P.parse_wait("W5HD") == (5, True, True) and P.parse_wait("W2D") == (2, False, True)


def test_wait_put_case_comment():
    m = np.full((4, 375), np.nan)
    m[3, 55:58] = [101, 102, 103]
    assert P.wait_entry(m, 10, -1, 106.0, 106.0, "W3HD") == 58    # all below 106 and the 3rd below 106


# ------------------------------------------------------------------------------------------------ the look-ahead guard
@pytest.mark.parametrize("seed", [11, 12])
def test_look_ahead_guard_trend_features(seed):
    """Every feature at bar i, computed on the whole series, equals the one computed on bars 0..i only, and on bars
    0..i followed by scrambled future bars."""
    d, b, o, h, l, c = walk(5, seed=seed)
    full = P.features(d, b, o, h, l, c)
    rng = np.random.default_rng(seed)
    for i in sorted(rng.choice(np.arange(60, len(c) - 1), size=12, replace=False).tolist()):
        cut = P.features(d[:i + 1], b[:i + 1], o[:i + 1], h[:i + 1], l[:i + 1], c[:i + 1])
        hh, ll, cc = h.copy(), l.copy(), c.copy()
        cc[i + 1:] += rng.normal(0, 400, len(c) - i - 1)
        hh[i + 1:] = np.maximum(hh[i + 1:], cc[i + 1:]) + 50
        ll[i + 1:] = np.minimum(ll[i + 1:], cc[i + 1:]) - 50
        scr = P.features(d, b, o, hh, ll, cc)
        for k in full:
            for alt in (cut, scr):
                a, z = full[k][i], alt[k][i]
                assert (np.isnan(a) and np.isnan(z)) or a == pytest.approx(z), (k, i)
                for s in (1, -1):
                    for rule in ("T1", "T2", "T3", "T4", "T5", "T6", "T7"):
                        assert P.trend_ok(rule, full, i, s) == P.trend_ok(rule, alt, i, s)


def test_look_ahead_guard_t8_and_waits():
    """T8 reads 1-minute candles up to the signal bar's last minute only; a wait reads the N candles before its entry
    row only: scrambling every minute from the entry row on changes nothing."""
    rng = np.random.default_rng(7)
    for _ in range(40):
        m = minutes(seed=int(rng.integers(1e6)))
        b = int(rng.integers(5, 60))
        s = int(rng.choice([1, -1]))
        lvl = m[3, 5 * b + 4] - s * float(rng.uniform(0, 10))
        sc = m[3, 5 * b + 4]
        tp = P.tp_avg(m, 5 * b + 4)
        m2 = m.copy()
        m2[:, 5 * b + 5:] = rng.normal(20000, 500, m2[:, 5 * b + 5:].shape)
        assert P.tp_avg(m2, 5 * b + 4) == tp
        for rule in P.WAITS:
            n = P.parse_wait(rule)[0]
            e = P.wait_entry(m, b, s, lvl, sc, rule)
            m3 = m.copy()
            m3[:, 5 * (b + 1) + n:] = rng.normal(20000, 500, m3[:, 5 * (b + 1) + n:].shape)
            m3[:, :5 * (b + 1)] = np.nan                         # nor anything before the wait
            assert P.wait_entry(m3, b, s, lvl, sc, rule) == e, rule
            if e is not None:
                assert e == 5 * (b + 1) + n


def test_features_on_empty_and_short_series():
    e = np.array([], float)
    F = P.features(np.array([], dtype=object), np.array([], int), e, e, e, e)
    assert all(len(v) == 0 for v in F.values())
    d, b, o, h, l, c = walk(1, bars=10)
    F = P.features(d, b, o, h, l, c)
    assert np.isnan(F["adx"]).all() and np.isnan(F["hi"]).all()


# ------------------------------------------------------------------------------------------------ study pieces
def test_registered_family():
    assert len(X.VARIANTS) == 18 and len(X.TESTS) == 36
    assert X.VARIANTS[:8] == P.TRENDS and len(P.WAITS) == 9 and X.VARIANTS[-1] == "C"
    assert set(R.EXIT_SETS) == {"P", "Q"}
    p, q = R.EXIT_SETS["P"], R.EXIT_SETS["Q"]
    assert p["stop"] == 0.15 and p["target"] is None and p["tmax"] == 30 and p["sq"] == (15, 15)
    assert len(p["steps"]) == 4 and p["steps"][-1] == (0.25, 0.05) and q["steps"] == p["steps"][:3]
    assert R.SEED == 72 and R.TWINS == 500
    assert X.spec_of("T3") == ("T3", None) and X.spec_of("W5HD") == (None, "W5HD") and X.spec_of("base") == (None, None)
    assert X.spec_of("C", {"T": "T1", "W": "W2H"}) == ("T1", "W2H")


def _tr(day, net, und="NIFTY", side="C", sig=50, ret=0.0, er=None):
    return dict(und=und, day=day, side=side, sig=sig, net=net, ret=ret, er=er or sig, why="time")


def test_best_of_ties_on_drawdown():
    assert X.best_of({"T1": (100, 50), "T2": (100, 40), "T3": (90, 1)}) == "T2"
    assert X.best_of({"T1": (100, 40), "T2": (100, 40)}) == "T1"


def test_pick_c_uses_only_days_before():
    d1, d2 = date(2025, 1, 2), date(2025, 5, 2)
    tp = {v: [] for v in P.TRENDS + P.WAITS}
    tp["T2"] = [_tr(d1, 100), _tr(d2, -500)]
    tp["T5"] = [_tr(d1, 50), _tr(d2, 900)]
    tp["W3D"] = [_tr(d1, 10)]
    assert X.pick_c(tp) == {"T": "T5", "W": "W3D"}
    assert X.pick_c(tp, date(2025, 4, 1)) == {"T": "T2", "W": "W3D"}


def test_skip_compare_counts_losers_and_big_winners():
    d = date(2025, 2, 3)
    base = [_tr(d, -300, sig=50), _tr(d, 800, sig=60, ret=0.45), _tr(d, 100, sig=70, ret=0.1), _tr(d, -50, sig=80)]
    var = [_tr(d, 100, sig=70, ret=0.1), _tr(d, 200, sig=90)]
    r = X.skip_compare(base, var)
    assert r["skipped"] == 3 and r["losers_avoided"] == 2 and r["losers_avoided_rs"] == 350
    assert r["big_missed"] == 1 and r["big_missed_rs"] == 800 and r["winners_missed"] == 1
    assert r["extra"] == 1 and r["extra_net"] == 200 and r["skipped_net"] == 450


def test_skip_twins_and_p():
    nets = np.array([100.0, -50.0, 30.0, -10.0])
    t0 = X.skip_twins(nets, 4, 10, np.random.default_rng(1))
    assert (t0 == 70.0).all()
    t2 = X.skip_twins(nets, 2, 200, np.random.default_rng(1))
    pairs = {round(70.0 - nets[i] - nets[j]) for i in range(4) for j in range(i + 1, 4)}
    assert set(np.round(t2).tolist()) <= pairs and len(set(np.round(t2).tolist())) > 1
    assert X.skip_twins(nets, 9, 5, np.random.default_rng(1)).tolist() == [70.0] * 5
    assert X.skip_twins([], 0, 3, np.random.default_rng(1)).tolist() == [0.0] * 3
    assert X.p_of([1, 2, 3, 4], 3.0) == round(3 / 5, 4)
    assert X.p_of([1, 2, 3, 4], 10.0) == round(1 / 5, 4)


def test_skip_twins_drop_exactly_k():
    nets = np.arange(10, dtype=float)
    tw = X.skip_twins(nets, 7, 50, np.random.default_rng(3))
    assert ((45 - tw) >= 0 + 1 + 2).all() and ((45 - tw) <= 7 + 8 + 9).all()


def _test(net, dnet, wnet, verdict="PASS", skip_q=0.05, dd=100, wd=-50, base=(10, 10, 10, 200, -100)):
    return dict(verdict=verdict, skip_q=skip_q, design_net=dnet, wf_net=wnet,
                final=dict(trades=50, net=net, max_dd=dd, worst_day=wd),
                base=dict(design_net=base[0], wf_net=base[1],
                          final=dict(trades=60, net=base[2], max_dd=base[3], worst_day=base[4])))


def test_decide():
    tests = {f"{v}-{x}": _test(5, 5, 5, verdict="FAIL") for v in X.VARIANTS for x in R.EXIT_SETS}
    out = X.decide(tests)
    assert out["recommended"] == {"P": None, "Q": None}
    assert "T1-P" in out["tradeoffs"]                     # lower net, smaller DD and better worst day
    tests["T3-P"] = _test(50, 40, 30)
    tests["W5H-P"] = _test(60, 60, 30, skip_q=0.2)        # skip twins not beaten -> not recommended
    tests["T7-P"] = _test(50, 20, 30)
    out = X.decide(tests)
    assert out["recommended"]["P"] == "T3-P"
    tests["T3-P"] = _test(50, 40, 5)                      # walk-forward below the baseline's
    assert X.decide(tests)["recommended"]["P"] == "T7-P"


def test_gates_are_r51s():
    ok = dict(trades=50, net=1000, ex_top5=10)
    assert X.gates(ok, 1, 1, 0.01, 0.05) == []
    assert set(X.gates(dict(trades=30, net=-1, ex_top5=-5), -1, -1, 0.2, 0.5)) == {
        "final net <= 0", "twin p >= 0.05", "BH q > 0.10", "fewer than 40 trades", "top 5 >= net", "design net <= 0",
        "walk-forward net <= 0"}


def test_periods_are_donchians():
    assert R.START == date(2024, 11, 20) and R.DESIGN_END == date(2026, 3, 31) and R.FINAL_START == date(2026, 4, 1)
    assert R.FIRST_ROW == 5 and R.LAST_ROW_HM == (15, 10) and R.MAX_PER_SIDE == 2 and R.MONEY == 1


def test_report_renders_from_small_results(tmp_path, monkeypatch):
    import r73report as RP
    tests = {}
    st = dict(trades=50, days=30, net=1000, per_trade=20.0, win=0.5, avg_win=300, avg_loss=-200, worst_day=-400,
              best_day=500, max_dd=900, green_days=0.5, ex_top5=100, per_month=8.0, why={})
    vb = dict(skipped=3, skipped_net=-10, losers_avoided=2, losers_avoided_rs=300, winners_missed=1,
              winners_missed_rs=290, big_missed=1, big_missed_rs=290, extra=0, extra_net=0)
    for t in X.TESTS:
        tests[t] = dict(variant=t.split("-")[0], exits=t.split("-")[1], final=st, p=0.5, twin_mean=0, skip_p=0.5,
                        skip_q=0.9, q=0.9, skip_twin_mean=0, skip_p_design=0.5, design=st, design_net=10, wf_net=5,
                        vs_base=vb, wait_cost=None, fails=["twin p >= 0.05"], verdict="FAIL",
                        base=dict(design_net=1, wf_net=1, final=st))
    fin = dict(tests=tests, base={"P": st, "Q": st}, decision=dict(recommended={"P": None, "Q": None}, tradeoffs=[]),
               check={}, sessions=133, end="2026-10-13", read="x",
               extra_c1=dict(variant="T1", c1=st, c1_plus=st, vs_c1=vb, skip_p=0.5, c1_expect=1))
    des = dict(base={x: dict(design=st, wf=st, wf_net=5) for x in R.EXIT_SETS}, c_pick={"T": "T1", "W": "W2H"},
               c_wf_picks=[{"T": "T1", "W": "W2H"}] * 4, variants={t: dict(design=st, wf_net=5) for t in X.TESTS},
               extra_c1=dict(variant="T1", c1=st, c1_plus=st, vs_c1=vb, c1_wf_net=1, c1_plus_wf_net=1))
    md = RP.render(des, fin)
    assert "T1" in md and "W5HD" in md and "Rs 1,000" in md


# ------------------------------------------------------------------------------------------------ data check
STORES = (R.CACHE_ROOT / "r51" / "clean" / "day_NIFTY.pkl").exists()


@pytest.mark.skipif(not STORES, reason="R51's clean stores are not on this machine")
def test_baseline_reproduces_the_paper_arm_and_filters_only_remove_signals():
    pytest.importorskip("numba")
    M = X.S.mods()
    lo, hi = date(2025, 1, 1), date(2025, 2, 28)
    ex = X.exits("P")
    ref = M["A"].donchian_trades(M["C"], M["E"], M["K"], M["fam_r44"], M["r51data"], M["G"], ex, lo, hi)
    mine = X.trades("base", "P", lo, hi)
    assert [(t["und"], t["day"], t["side"], t["er"], round(t["net"], 6)) for t in ref] == \
           [(t["und"], t["day"], t["side"], t["er"], round(t["net"], 6)) for t in mine]
    sig_b = {X.key(t) for t in mine}
    for v in ("T1", "T8", "W5HD"):
        t = X.trades(v, "P", lo, hi)
        assert len(t) <= len(mine) + 5
        # a filter only drops signals: every trade it takes is one of Donchian's signals
        allsig = {(u, d, s, 5 * (b + 1)) for u in M["K"].UNDS for d, v_ in X.don_signals(u).items()
                  for b, s in v_ if lo <= d <= hi}
        assert {X.key(z) for z in t} <= allsig
        if v == "W5HD":
            assert all(z["er"] >= z["sig"] + 5 for z in t)
    assert sig_b
