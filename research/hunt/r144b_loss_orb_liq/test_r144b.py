"""Tests for R144b (PREREG.md): features (incl. the look-ahead guard), slices, the filter choice, twins, gates, BH and an
end-to-end design -> test run on synthetic trades with a planted losing pattern. No lake needed."""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r144b as R  # noqa: E402
import r144b_lib as X  # noqa: E402


class Sess:
    def __init__(self, day, ix, vix, opts, prev, expiry=False, vix_prev=np.nan):
        self.day, self.ix, self.vix, self.opts, self.prev = day, ix, vix, opts, prev
        self.expiry, self.vix_prev = expiry, vix_prev


def make_sessions(n=30, seed=1, und_step=100.0):
    rng = np.random.default_rng(seed)
    out, prev = [], (np.nan,) * 4
    d = date(2025, 1, 1)
    px = 50000.0
    for i in range(n):
        d += timedelta(days=1)
        c = px + np.cumsum(rng.normal(0, 10, X.N))
        o = np.concatenate([[px], c[:-1]])
        h, lo = np.maximum(o, c) + 3, np.minimum(o, c) - 3
        ix = np.stack([o, h, lo, c], 1)
        atm = round(px / und_step) * und_step
        opts = {}
        for k in (atm - und_step, atm, atm + und_step):
            for sd in ("C", "P"):
                base = np.maximum(5.0, 300 + (c - k if sd == "C" else k - c) * 0.5)
                a = np.stack([base, base + 2, base - 2, base, rng.integers(0, 500, X.N).astype(float),
                              1e5 + np.cumsum(rng.normal(0, 100, X.N))], 1)
                opts[(k, sd)] = a
        vix = 14 + np.cumsum(rng.normal(0, 0.01, X.N))
        out.append(Sess(d, ix, vix, opts, prev, expiry=(i % 5 == 4), vix_prev=14.0))
        prev = (o[0], h.max(), lo.min(), c[-1])
        px = c[-1]
    return out


def test_implied_vol_round_trip_and_bounds():
    p = X.bs_price(100, 100, 7 / 365, 0.2, True)
    assert abs(X.implied_vol(p, 100, 100, 7 / 365, True) - 0.2) < 1e-4
    q = X.bs_price(100, 105, 30 / 365, 0.35, False)
    assert abs(X.implied_vol(q, 100, 105, 30 / 365, False) - 0.35) < 1e-4
    assert np.isnan(X.implied_vol(0.0, 100, 100, 0.1, True))
    assert np.isnan(X.implied_vol(1000.0, 100, 100, 0.1, True))     # above any volatility
    assert np.isnan(X.implied_vol(np.nan, 100, 100, 0.1, True))


def test_history_adr_expiry_and_volume_median():
    ss = make_sessions(30)
    h = X.History(ss)
    assert np.isnan(h.adr[5]) and np.isfinite(h.adr[25])           # needs >= 10 earlier sessions
    assert h.sessions_to_expiry(4) == 0 and h.sessions_to_expiry(2) == 2
    assert h.sessions_to_expiry(29) is None or h.sessions_to_expiry(29) == 0
    assert np.isnan(h.vol_median(3, 100)) and h.vol_median(25, 100) > 0


def test_features_look_ahead_guard():
    """Scrambling every row at or after the entry minute must not change a single feature."""
    ss = make_sessions(30)
    h = X.History(ss)
    s = ss[25]
    k = sorted({k for k, _ in s.opts})[1]
    for er in (20, 90, 200):
        for side in ("C", "P"):
            f1 = X.features(s, h, er, side, k)
            ix2 = s.ix.copy()
            ix2[er:] = ix2[er:][::-1] * 1.07
            opts2 = {kk: a.copy() for kk, a in s.opts.items()}
            for a in opts2.values():
                a[er:] = a[er:] * 3 + 11
            s2 = Sess(s.day, ix2, s.vix.copy(), opts2, s.prev, s.expiry, s.vix_prev)
            s2.vix[er:] = 99.0
            f2 = X.features(s2, h, er, side, k)
            for name in X.FEATS:
                a, b = f1[name], f2[name]
                if isinstance(a, float) and np.isnan(a):
                    assert isinstance(b, float) and np.isnan(b), name
                else:
                    assert a == b, (name, er, side)


def test_features_signs_and_categories():
    ss = make_sessions(30)
    h = X.History(ss)
    s = ss[25]
    k = sorted({k for k, _ in s.opts})[1]
    fc, fp = X.features(s, h, 120, "C", k), X.features(s, h, 120, "P", k)
    assert fc["P4"] == pytest.approx(-fp["P4"]) and fc["P7"] == pytest.approx(-fp["P7"])
    assert fc["P11"] == 120.0 and fc["P10"] in ("0", "1", "2", "3")
    assert fc["F2"] in X.QUADS and fc["P12"] in ("0 (expiry day)", "1", "2-4", "5+")
    assert 0 <= fc["L2"] <= 15 and fc["O2"] > 0
    unknown = X.features(s, h, 0, "C", k)                            # nothing closed yet
    assert np.isnan(unknown["P1"]) and unknown["P10"] is None
    early = X.features(ss[2], h, 120, "C", k)                        # no ADR yet: ADR features unknown
    assert np.isnan(early["P3"]) and np.isnan(early["O7"])
    missing = X.features(s, h, 120, "C", 1.0)                        # strike not stored: option features unknown
    assert np.isnan(missing["L1"]) and missing["F2"] is None


def test_quadrant_dte_and_fifths():
    assert X.quadrant(1, 1) == "long build-up" and X.quadrant(-1, 1) == "short build-up"
    assert X.quadrant(1, -1) == "short covering" and X.quadrant(-1, -1) == "long unwinding"
    assert X.quadrant(0, 5) == "flat" and X.quadrant(np.nan, 1) == "flat"
    assert X.dte_bucket(0).startswith("0") and X.dte_bucket(3) == "2-4" and X.dte_bucket(None) is None
    ed = X.edges(list(range(100)))
    assert len(ed) == 4 and X.fifth(0, ed) == "Q1" and X.fifth(99, ed) == "Q5"
    assert X.fifth(np.nan, ed) is None and len(X.edges([1.0] * 10)) == 0


def T(day, net, er=60, und="BANKNIFTY", side="C", arm="LIQ", why="stop"):
    return dict(arm=arm, und=und, day=day, er=er, xr=er + 10, side=side, net=float(net), why=why, k=None)


def test_summary_steady_clustering_exits():
    d = date(2025, 1, 2)
    tr = [T(d, -100), T(d, -50, er=70), T(d + timedelta(1), 200)]
    s = X.summary(tr)
    assert s["n"] == 3 and s["losers"] == 2 and s["avg"] == round(50 / 3)
    c = X.clustering(tr)
    assert c["all_loss_index_days"] == 1 and c["losers_on_all_loss_days"] == 1.0 and c["longest_losing_streak"] == 2
    m = X.money_by_exit(tr)
    assert m["stop"]["n"] == 3 and m["stop"]["lost"] == -150
    base = {"a": dict(n=100, loser_rate=0.5, avg=10)}
    assert X.steady({"a": dict(n=30, loser_rate=0.6, avg=-5)}, base, ("a",))
    assert not X.steady({"a": dict(n=29, loser_rate=0.6, avg=-5)}, base, ("a",))
    assert not X.steady({"a": dict(n=30, loser_rate=0.6, avg=1)}, base, ("a",))
    assert not X.steady({}, base, ())
    assert X.summary([]) == dict(n=0)


def test_loss_stats_gates_twins_bh():
    d = date(2025, 1, 1)
    tr = [T(d + timedelta(i), (-500 if i % 3 == 0 else 300)) for i in range(90)]
    st = X.loss_stats(tr)
    assert st["trades"] == 90 and st["loss_sum"] == -15000 and st["worst_day"] == -500 and st["max_dd"] == 500
    rule = [t for t in tr if t["net"] > 0]
    rs = X.loss_stats(rule)
    rng = np.random.default_rng(0)
    p = X.twin_p(tr, len(tr) - len(rule), X.loss_sum(rule), 200, rng)
    assert p < 0.05                                       # dropping exactly the losers beats random drops
    assert X.twin_p(tr, 0, 0, 10, rng) == 1.0
    g = X.gates(rs, st, 30, p, 0.01)
    assert g["passed"], g
    assert not X.gates(st, st, 0, 0.5, 0.5)["passed"]
    q = X.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])   # 0.03 * 4 / 2 = 0.06 > 0.0533: monotone
    assert X.bh([]) == []


def test_skips_and_choose():
    f = dict(feature="P1", cut=dict(kind="low", below=0.5))
    assert X.skips(f, {"P1": 0.1}) and not X.skips(f, {"P1": 0.9}) and not X.skips(f, {"P1": np.nan})
    assert not X.skips(f, {})
    g = dict(feature="F2", cut=dict(kind="cat", value="long unwinding"))
    assert X.skips(g, {"F2": "long unwinding"}) and not X.skips(g, {"F2": None})
    h = dict(feature="M1", cut=dict(kind="high", at_or_above=20))
    assert X.skips(h, {"M1": 20}) and not X.skips(h, {"M1": 19.9})
    a = dict(feature="A", design_net=-10, keys=[1, 2, 3, 4])
    b = dict(feature="B", design_net=-9, keys=[1, 2, 3, 9])          # 75% overlap: skipped
    c = dict(feature="C", design_net=-8, keys=[7, 8])
    d = dict(feature="D", design_net=-7, keys=[10])
    e = dict(feature="E", design_net=-6, keys=[11])
    ch = X.choose([a, b, c, d, e])
    assert [x["feature"] for x in ch] == ["A", "C", "D"] and "keys" not in ch[0]


def planted(period_lo, n_days, seed):
    """LIQ trades where a high VIX (M1 >= 20) loses 85% of the time and the rest win 60%."""
    rng = np.random.default_rng(seed)
    trades, feats = [], {}
    for i in range(n_days):
        d = period_lo + timedelta(days=i)
        for er in (30, 120):
            hi = rng.random() < 0.3
            lose = rng.random() < (0.85 if hi else 0.4)
            t = T(d, -800 if lose else 1000, er=er)
            trades.append(t)
            f = {k: np.nan for k in X.FEATS}
            f.update(P10=None, P12=None, F2=None, M1=float(rng.uniform(20, 30) if hi else rng.uniform(10, 19)))
            feats[X.key(t)] = f
    return trades, feats


def test_end_to_end_design_then_test(tmp_path, monkeypatch):
    monkeypatch.setattr(R, "OUT", tmp_path / "cache")
    monkeypatch.setattr(R, "RESULTS", tmp_path / "results")
    monkeypatch.setattr(R, "FINAL_FLAG", tmp_path / "results" / "FINAL_READ")
    monkeypatch.setattr(R, "TWINS", 200)
    (tmp_path / "cache").mkdir()
    for per, seed in (("design", 1), ("final", 2), ("unseen", 3)):
        lo, hi = R.PERIODS[per]
        tr, fe = planted(lo, (hi - lo).days + 1, seed)
        R.cache_path("liq", per).write_bytes(pickle.dumps(dict(trades=tr, feats=fe, extra={})))
    with pytest.raises(SystemExit):
        R.test()                                                       # no design yet
    d = R.design()
    liq = d["arms"]["LIQ"]
    assert liq["rebuilt"] and d["arms"]["ORB"] == dict(rebuilt=False)
    assert liq["filters"][0]["feature"] == "M1" and liq["filters"][0]["cut"]["kind"] == "high"
    assert any(s["feature"] == "M1" for s in liq["anatomy"]["steady_losing"])
    out = R.test()
    f0 = out["arms"]["LIQ"]["filters"][0]
    assert f0["passed"], out["arms"]["LIQ"]["tests"]
    assert f0["win_rate"]["final"]["after"] > f0["win_rate"]["final"]["before"]
    pv = json.loads((tmp_path / "results" / "pvalues.json").read_text())
    assert pv["study"] == "R144b" and all(0 < t["raw_p"] <= 1 for t in pv["tests"])
    assert any(t["id"].startswith("r144b-LIQ-f0-final") for t in pv["tests"])
    with pytest.raises(SystemExit):
        R.test()                                                       # the final is read once


def test_collect_refuses_tests_before_design(tmp_path, monkeypatch):
    monkeypatch.setattr(R, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        R.collect("orb", "final")


def test_norm_and_strike():
    t = R.norm("ORB", dict(und="BANKNIFTY", day=date(2025, 1, 2), side=-1, entry_row=50, exit_row=80, net=-10.0,
                           why="stop", k=50000.0))
    assert t["side"] == "P" and t["er"] == 50 and t["xr"] == 80 and t["k"] == 50000.0
    ss = make_sessions(3)
    lt = dict(arm="LIQ", und="BANKNIFTY", er=100, side="C", k=None)
    k = R.strike_of(lt, ss[1])
    spot = ss[1].ix[99, 3]
    assert k == np.floor(spot / 100 + 0.5) * 100 - 100                 # one strike in the money for a call
