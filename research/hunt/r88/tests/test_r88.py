"""R88 tests: candles, targets, factors, expiries, statistics and the look-ahead guard (20 Oct 2026)."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r88 import candles as C
from research.hunt.r88 import cfg, data, stats


def synth(days=30, seed=1, mcx=False):
    """Index-like minutes, VIX minutes and a chain state per minute for `days` weekdays."""
    rng = np.random.default_rng(seed)
    om, n = (cfg.MCX_OPEN, 600) if mcx else (cfg.NSE_OPEN, cfg.NSE_ROWS)
    ds = pd.bdate_range("2026-01-05", periods=days)
    ts = np.concatenate([d + pd.to_timedelta(om + np.arange(n), unit="min") for d in ds])
    c = 20000 * np.exp(np.cumsum(rng.normal(0, 3e-4, len(ts))))
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) * (1 + rng.uniform(0, 2e-4, len(ts)))
    l = np.minimum(o, c) * (1 - rng.uniform(0, 2e-4, len(ts)))
    mins = pd.DataFrame(dict(ts=pd.to_datetime(ts), o=o, h=h, l=l, c=c))
    vix = mins.assign(c=14 + np.cumsum(rng.normal(0, 0.01, len(ts))), o=0, h=0, l=0)
    chain = pd.DataFrame(dict(ts=mins.ts, spot=c, oi_c=1e6 + rng.integers(0, 1000, len(ts)).cumsum(),
                              oi_p=1e6 + rng.integers(0, 1000, len(ts)).cumsum(), atm_iv=12 + rng.normal(0, 0.5, len(ts)),
                              straddle=200 + rng.normal(0, 5, len(ts)), skew=rng.normal(1, 0.2, len(ts)),
                              vol_c=rng.integers(1, 1000, len(ts)).astype(float),
                              vol_p=rng.integers(1, 1000, len(ts)).astype(float)))
    chain["pcr"] = chain.oi_p / chain.oi_c
    chain["vol"] = chain.vol_c + chain.vol_p
    return mins, vix, chain, set(ds[[4, 9, 14, 19, 24]].date)


def test_build_ohlc_and_partial_candles():
    m = pd.DataFrame(dict(ts=pd.to_datetime(["2026-01-05 09:15", "2026-01-05 09:16", "2026-01-05 09:19",
                                             "2026-01-05 09:20"]), o=[1, 2, 3, 9], h=[5, 6, 7, 9], l=[0.5, 1, 2, 9],
                          c=[2, 3, 4, 9]))
    k = C.build(m, 5, cfg.NSE_OPEN)
    assert len(k) == 1                                     # 09:20 candle has 1 of 5 minutes: dropped
    r = k.iloc[0]
    assert (r.o, r.h, r.l, r.c, r.n) == (1, 7, 0.5, 4, 3) and str(r.ts) == "2026-01-05 09:15:00"


def test_baseline_uses_previous_sessions_only():
    mins, vix, chain, exp = synth(days=25)
    raw = C.build(mins, 15, cfg.NSE_OPEN)
    k = C.targets(raw)
    raw2 = raw.copy()
    raw2.loc[raw2.day == raw2.day.max(), "h"] *= 1.5     # change only the last day's ranges
    k2 = C.targets(raw2)
    assert np.allclose(k.B.to_numpy(), k2.B.to_numpy(), equal_nan=True)
    assert k[k.day < k.day.unique()[10]].B.isna().all()   # fewer than 10 earlier sessions: no baseline


def test_dte_and_categories():
    days = pd.Series(pd.bdate_range("2026-01-05", periods=8))
    d = C.dte(days, {date(2026, 1, 7), date(2026, 1, 14)})
    assert list(d.values) == [2, 1, 0, 4, 3, 2, 1, 0]
    assert [C.dte_cat(x) for x in (0, 1, 2, 4, 9, np.nan)][:5] == ["0", "1", "2", "3-5", "6+"]
    assert C.dte(days, set()).isna().all()


def test_detect_expiries_from_straddle_jump():
    rows = []
    for i, d in enumerate(pd.bdate_range("2026-01-05", periods=12)):
        exp_day = i in (3, 8)
        for m in range(30):
            s = 100 - 3 * m if exp_day else (300 if i in (4, 9) and m < 15 else 150)
            rows.append(dict(ts=d + pd.Timedelta(minutes=555 + m), straddle=max(s, 5)))
    ex = data.detect_expiries(pd.DataFrame(rows))
    assert ex == {date(2026, 1, 8), date(2026, 1, 15)}


def test_drop_foreign_rows():
    idx = pd.DataFrame(dict(ts=pd.to_datetime(["2026-01-05 09:15", "2026-01-05 09:16"]), c=[100.0, 101.0]))
    book = pd.DataFrame(dict(ts=pd.to_datetime(["2026-01-05 09:15", "2026-01-05 09:16", "2026-01-05 09:17",
                                                "2026-01-06 09:15"]), spot=[100.5, 120.0, 101.2, 101.0]))
    out = data.drop_foreign(book, idx)
    assert list(out.spot) == [100.5, 101.2]               # 120 is foreign; next day has no index close


def test_chain_minute_features():
    book = pd.DataFrame(dict(ts=pd.to_datetime(["2026-01-05 09:15"] * 6), offset=[0, 0, -2, 2, -9, 9],
                             side=["C", "P", "P", "C", "P", "C"], close=[50, 40, 10, 12, 1, 1],
                             iv=[12, 14, 16, 11, np.nan, np.nan], volume=[100, 50, 10, 30, 7, 7],
                             oi=[1000, 2000, 300, 100, 5, 5], spot=[100.0] * 6))
    c = data.chain_minute(book).iloc[0]
    assert c.pcr == pytest.approx(2305 / 1105) and c.atm_iv == 13 and c.straddle == 90
    assert c["skew"] == pytest.approx(16 - 11) and c.vol_c == 130 and c.vol_p == 60 and c.vol == 190


def test_cluster_diff_and_bh():
    y = np.r_[np.ones(50), np.zeros(50)] + np.random.default_rng(0).normal(0, 0.1, 100)
    g = np.r_[np.ones(50, bool), np.zeros(50, bool)]
    r = stats.cluster_diff(y, g, np.repeat(np.arange(20), 5))
    assert r["diff"] == pytest.approx(y[:50].mean() - y[50:].mean()) and r["p"] < 1e-6 and r["sessions"] == 20
    # identical copies within a cluster must not shrink the SE (clustering)
    r1 = stats.cluster_diff(np.r_[y, y], np.r_[g, g], np.r_[np.arange(100), np.arange(100)])
    r2 = stats.cluster_diff(np.r_[y, y], np.r_[g, g], np.arange(200))
    assert r1["se"] > r2["se"]
    assert np.allclose(stats.bh([0.01, 0.04, 0.03, 0.5]), [0.04, 0.16 / 3, 0.16 / 3, 0.5])
    assert np.isnan(stats.bh([np.nan, 0.2])[0]) and stats.bh([np.nan, 0.2])[1] == pytest.approx(0.2)
    assert stats.quintile_cuts(np.ones(500)) is None
    assert stats.one_sided(0.04, 2.0, +1) == pytest.approx(0.02) and stats.one_sided(0.04, 2.0, -1) == pytest.approx(0.98)


def test_factor_tests_finds_a_planted_effect():
    rng = np.random.default_rng(3)
    n = 4000
    x = rng.normal(size=n)
    k = pd.DataFrame(dict(day=np.repeat(np.arange(100), 40), F01=x, S=0.3 * x + rng.normal(size=n),
                          D=rng.normal(size=n), up=rng.integers(0, 2, n).astype(float),
                          F19=np.where(np.arange(n) % 5 == 0, "Mon", "Tue")))
    rows, cuts = stats.factor_tests(k, ["F01"], ["F19"])
    s = [r for r in rows if r["factor"] == "F01" and r["target"] == "S"][0]
    d = [r for r in rows if r["factor"] == "F01" and r["target"] == "D"][0]
    assert s["p"] < 1e-10 and d["p"] > 0.001 and "F01" in cuts
    rows2, _ = stats.factor_tests(k, ["F01"], [], cuts=cuts)
    assert rows2[0]["diff"] == pytest.approx(s["diff"])


@pytest.mark.parametrize("tf", [5, 15])
def test_lookahead_guard(tf):
    """Every factor of the candle starting at t0 is unchanged when every row from t0 on is perturbed, and the factors
    of earlier candles are unchanged when those rows are deleted."""
    mins, vix, chain, exp = synth(days=26, seed=tf)
    leader = C.targets(C.build(synth(days=26, seed=99)[0], tf, cfg.NSE_OPEN))
    full = C.factors("NIFTY", tf, mins, chain, vix, exp, leader=leader, events={date(2026, 1, 30)})
    cols = C.CONT + C.CAT
    rng = np.random.default_rng(7)
    for t0 in full.ts[full.day == full.day.unique()[-2]].iloc[[0, 3, 10]]:
        late = mins.ts >= t0
        m2, v2, c2 = mins.copy(), vix.copy(), chain.copy()
        for c in ("o", "h", "l", "c"):
            m2.loc[late, c] *= rng.uniform(0.5, 1.5, late.sum())
        v2.loc[vix.ts >= t0, "c"] = rng.uniform(5, 50, (vix.ts >= t0).sum())
        lc = chain.ts >= t0
        for c in ("pcr", "oi_c", "oi_p", "atm_iv", "straddle", "skew", "vol_c", "vol_p", "vol", "spot"):
            c2.loc[lc, c] = rng.uniform(1, 1e6, lc.sum())
        pert = C.factors("NIFTY", tf, m2, c2, v2, exp, leader=leader, events={date(2026, 1, 30)})
        a = full[full.ts == t0][cols].iloc[0]
        b = pert[pert.ts == t0][cols].iloc[0]
        for c in cols:
            assert (pd.isna(a[c]) and pd.isna(b[c])) or a[c] == pytest.approx(b[c], rel=1e-9), (t0, c)
        cut = C.factors("NIFTY", tf, mins[~late], chain[~lc], vix[vix.ts < t0], exp, leader=leader,
                        events={date(2026, 1, 30)})
        early = full[full.ts < t0].set_index("ts")[cols]
        cc = cut.set_index("ts")[cols].reindex(early.index)
        # F18 (days to expiry) counts towards the next expiry date, which is public in advance (PREREG section 5);
        # deleting the future sessions removes that date, so only the perturbation half above checks F18
        for c in [c for c in cols if c != "F18"]:
            x, y = early[c], cc[c]
            same = (x.isna() & y.isna()) | (x.astype(object) == y.astype(object))
            if x.dtype.kind == "f":
                same = same | np.isclose(x.astype(float), y.astype(float), rtol=1e-9, equal_nan=True)
            assert same.all(), (t0, c)


def test_mcx_minutes_session_and_close_only():
    ts = pd.to_datetime(["2026-10-19 08:59", "2026-10-19 09:00", "2026-10-19 23:29", "2026-10-19 23:30"])
    ch = pd.DataFrame(dict(ts=ts, spot=[1.0, 2.0, 3.0, 4.0]))
    d = data.mcx_minutes(pd.concat([ch] + [ch.assign(ts=ch.ts.dt.floor("D") + pd.Timedelta(minutes=540 + i),
                                                       spot=2.0 + i) for i in range(1, 200)]).drop_duplicates("ts"))
    assert d.ts.min() == pd.Timestamp("2026-10-19 09:00") and pd.Timestamp("2026-10-19 23:30") not in set(d.ts)
    assert (d.o == d.c).all() and (d.h == d.c).all()
