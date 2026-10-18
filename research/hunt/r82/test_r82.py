"""R82 tests: indicators and signals use only bars up to t (look-ahead guard), the costs match McxCosts, the simulators'
fill rules, the windows, the final-read lock and Benjamini-Hochberg. Synthetic data only (no download needed).

    python -m pytest research/hunt/r82/test_r82.py -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r82 import daily as D
from research.hunt.r82 import intraday as I
from research.hunt.r82 import r82lib as L


# ------------------------------------------------------------------------------------------------ synthetic markets
def daily_frame(n=900, seed=1, drift=0.0004, start="2018-01-01"):
    rng = np.random.default_rng(seed)
    idx = pd.bdate_range(start, periods=n)
    c = 50000 * np.exp(np.cumsum(drift + 0.01 * rng.standard_normal(n)))
    o = c * (1 + 0.002 * rng.standard_normal(n))
    h = np.maximum(o, c) * (1 + 0.004 * rng.random(n))
    l = np.minimum(o, c) * (1 - 0.004 * rng.random(n))
    return pd.DataFrame(dict(open=o, high=h, low=l, close=c, usd_close=c / 80.0, inr=80.0), index=idx)


def market(n=900, seed=1, cut=None, dxy=True):
    g, s = daily_frame(n, seed), daily_frame(n, seed + 7, drift=-0.0002)
    if cut is not None:
        g, s = g.iloc[:cut], s.iloc[:cut]
    dx = pd.Series(100 + np.cumsum(np.random.default_rng(seed + 3).standard_normal(n)) * 0.2,
                   index=pd.bdate_range("2018-01-01", periods=n))
    if cut is not None:
        dx = dx.iloc[:cut]
    return D.Market.build({"gold": g, "silver": s}, dx if dxy else None)


def hourly_frame(days=60, seed=2, start="2025-06-02"):
    rng = np.random.default_rng(seed)
    idx = [t for t in pd.date_range(start, periods=days * 24, freq="h") if t.weekday() < 5 and t.hour != 21]
    n = len(idx)
    c = 7000 * np.exp(np.cumsum(0.002 * rng.standard_normal(n)))
    o = np.r_[c[0], c[:-1]] * (1 + 0.0003 * rng.standard_normal(n))
    h = np.maximum(o, c) * (1 + 0.002 * rng.random(n))
    l = np.minimum(o, c) * (1 - 0.002 * rng.random(n))
    df = pd.DataFrame(dict(open=o, high=h, low=l, close=c), index=pd.DatetimeIndex(idx))
    df["usd_open"], df["usd_close"] = df.open / 80, df.close / 80
    return df


# ------------------------------------------------------------------------------------------------ costs
def test_costs_match_mcxcosts_worked_example():
    # McxCosts.kt doc: a 1-lot CRUDEOIL futures round trip at Rs 8,552 (Rs 8,55,200 a side) comes to Rs 194.
    rt = L.leg_charge(True, 855200) + L.leg_charge(False, 855200)
    assert round(rt) == 194


def test_round_trip_charges_short_is_sell_then_buy():
    a = L.round_trip_charges(1, 100000, 101000, 10)
    b = L.round_trip_charges(-1, 101000, 100000, 10)
    assert a == pytest.approx(b)


def test_fill_moves_against_the_order():
    assert L.fill(1000, 1) == pytest.approx(1000.3)
    assert L.fill(1000, -1) == pytest.approx(999.7)
    assert L.fill(1000, -1, stop=True) == pytest.approx(999.0)


def test_rupee_proxy_scale_and_duty_cut():
    # GOLDM quote per 10 g: 2000 $/oz x 83 x 1.10 x 10 / 31.1035
    assert L.rs_factor("gold", "2024-07-22", 83) * 2000 == pytest.approx(2000 * 83 * 1.10 * 10 / 31.1035)
    assert L.duty("2024-07-23") == 1.06
    assert L.LOT["silver"][1] == 5 and L.LOT["gold"][1] == 10


def test_carry_sign():
    assert L.carry_rs(1, 100000, 10, "2025-01-01", "2025-12-31") < 0
    assert L.carry_rs(-1, 100000, 10, "2025-01-01", "2025-12-31") > 0


# ------------------------------------------------------------------------------------------------ indicators: no look-ahead
@pytest.mark.parametrize("cut", [300, 500, 777])
def test_daily_indicators_use_only_the_past(cut):
    full, part = market(), market(cut=cut)
    for m in D.METALS:
        a, b = full.d[m].iloc[:cut], part.d[m]
        for col in ("atr20", "sign252", "st", "dc55h", "dc55l", "dc20h", "dc20l", "vol20", "vol_p80", "ratio63",
                    "other_st", "other_sign252", "dxy63"):
            x, y = a[col].values[:-1], b[col].values[:-1]       # the last row's month_end depends on the calendar only
            assert np.allclose(np.nan_to_num(x.astype(float), nan=-9), np.nan_to_num(y.astype(float), nan=-9)), col


@pytest.mark.parametrize("rule", list(D.RULES))
def test_daily_decisions_unchanged_when_future_is_scrambled(rule):
    full = market()
    cut = 650
    g, s = daily_frame(900, 1), daily_frame(900, 8, drift=-0.0002)
    rng = np.random.default_rng(5)
    for df in (g, s):
        tail = df.iloc[cut:].copy()
        df.iloc[cut:] = tail.sample(frac=1, random_state=3).values
    dx = pd.Series(100 + np.cumsum(np.random.default_rng(4).standard_normal(900)) * 0.2,
                   index=pd.bdate_range("2018-01-01", periods=900))
    scr = D.Market.build({"gold": g, "silver": s}, dx)
    r = D.RULES[rule]()
    for m in D.METALS:
        for i in range(260, cut - 1):
            for held in (-1, 0, 1):
                a = r.decide(m, list(full.d[m].iloc[[i]].itertuples(index=False))[0], held)
                b = r.decide(m, list(scr.d[m].iloc[[i]].itertuples(index=False))[0], held)
                assert a == b


def test_trades_before_a_cut_are_unchanged_by_later_data():
    full = market()
    part = market(cut=700)
    for rule in D.RULES:
        a = [t for t in D.run(rule, full, "2019-01-01", "2020-06-30") if t.exit_t < pd.Timestamp("2020-06-01")]
        b = [t for t in D.run(rule, part, "2019-01-01", "2020-06-30") if t.exit_t < pd.Timestamp("2020-06-01")]
        assert [(t.metal, t.entry_t, t.exit_t, round(t.net, 6)) for t in a] == \
               [(t.metal, t.entry_t, t.exit_t, round(t.net, 6)) for t in b], rule


def test_supertrend_and_donchian_simple_cases():
    c = np.r_[np.linspace(100, 200, 60), np.linspace(200, 100, 60)]
    h, l = c + 1, c - 1
    st = L.supertrend_dir(h, l, c)
    assert st[55] == 1 and st[-1] == -1
    hh, ll = L.donchian(h, l, 20)
    assert np.isnan(hh[19]) and hh[20] == h[:20].max()


# ------------------------------------------------------------------------------------------------ daily simulator
def test_t0_has_no_stop_and_t1_stops():
    mk = market()
    t0 = D.run("T0", mk, "2019-01-01", "2021-06-30")
    t1 = D.run("T1", mk, "2019-01-01", "2021-06-30")
    assert all(t.why != "stop" for t in t0)
    assert all(t.why in ("stop", "flip", "signal_exit", "window_end") for t in t1)


def test_window_starts_flat_and_ends_closed():
    mk = market()
    tr = D.run("T1", mk, "2019-03-01", "2020-03-31")
    assert all(pd.Timestamp("2019-03-01") <= t.entry_t <= pd.Timestamp("2020-03-31") for t in tr)
    assert all(t.exit_t <= pd.Timestamp("2020-03-31") for t in tr)


def test_long_only_rules_never_short():
    mk = market()
    for r in ("T2", "G1", "U1"):
        assert all(t.side > 0 for t in D.run(r, mk, "2019-01-01", "2021-06-30"))


def test_g1_holds_at_most_one_metal():
    mk = market()
    tr = D.run("G1", mk, "2019-01-01", "2021-06-30")
    for a in tr:
        for b in tr:
            if a.metal != b.metal:
                assert a.exit_t <= b.entry_t or b.exit_t <= a.entry_t


def test_daily_twins_are_reproducible():
    mk = market()
    tr = D.run("T1", mk, "2019-01-01", "2020-12-31")
    a = D.twin_books("T1", tr, mk, "2019-01-01", "2020-12-31", n=20)
    b = D.twin_books("T1", tr, mk, "2019-01-01", "2020-12-31", n=20)
    assert np.allclose(a, b)


# ------------------------------------------------------------------------------------------------ intraday
def hs_pair(cut=None):
    g, s = hourly_frame(seed=2), hourly_frame(seed=9)
    if cut is not None:
        g, s = g.loc[:cut], s.loc[:cut]
    return I.build_all({"gold": g, "silver": s})


def test_entry_window_and_flat_hour():
    assert I.in_entry_window(pd.Timestamp("2025-07-01 04:00"))
    assert not I.in_entry_window(pd.Timestamp("2025-07-01 03:00"))
    assert not I.in_entry_window(pd.Timestamp("2025-07-01 17:00"))       # summer: flat at 17:00 UTC
    assert I.in_entry_window(pd.Timestamp("2025-12-01 17:00"))           # winter: flat at 18:00 UTC
    assert not I.in_entry_window(pd.Timestamp("2025-07-05 10:00"))       # Saturday


@pytest.mark.parametrize("name", list(I.TESTS))
def test_intraday_trades_unchanged_by_later_bars(name):
    full = hs_pair()
    cut = pd.Timestamp("2025-07-10 23:00")
    part = hs_pair(cut=cut)
    a = [t for t in I.TESTS[name](full, "2025-06-02", "2025-07-10") if t.exit_t < pd.Timestamp("2025-07-10")]
    b = [t for t in I.TESTS[name](part, "2025-06-02", "2025-07-10") if t.exit_t < pd.Timestamp("2025-07-10")]
    assert [(t.metal, t.side, t.entry_t, t.exit_t, round(t.net, 6)) for t in a] == \
           [(t.metal, t.side, t.entry_t, t.exit_t, round(t.net, 6)) for t in b]


def test_intraday_never_overnight_and_inside_window():
    hs = hs_pair()
    for name, f in I.TESTS.items():
        for t in f(hs, "2025-06-02", "2025-07-25"):
            assert t.entry_t.normalize() == t.exit_t.normalize(), name
            assert I.in_entry_window(t.entry_t), name
            assert t.exit_t.hour <= L.flat_hour(t.exit_t), name


def test_liquidity_acts_after_the_breaking_bar():
    hs = hs_pair()
    for t in I.TESTS["L3"](hs, "2025-06-02", "2025-07-25"):
        hd = hs[t.metal]
        j = hd.df.index.get_loc(t.entry_t)
        assert any(z.broken == j - 1 and z.kind == "pool" for z in hd.zones)


def test_l1_buys_only_and_l3_mirrors():
    hs = hs_pair()
    assert all(t.side > 0 for t in I.TESTS["L1"](hs, "2025-06-02", "2025-07-25"))
    l3 = I.TESTS["L3"](hs, "2025-06-02", "2025-07-25")
    assert {t.side for t in l3} <= {-1, 1}


def test_event_days_are_skipped():
    hs = hs_pair()
    for t in I.TESTS["E1"](hs, "2025-06-02", "2025-07-25"):
        assert I.ist_date(t.entry_t) not in I.EVENT_DAYS
    for t in I.TESTS["U3"](hs, "2025-06-02", "2025-07-25"):
        assert t.entry_t.date() in I.EVENT_DAYS


def test_every_intraday_trade_has_a_stop():
    hs = hs_pair()
    for name, f in I.TESTS.items():
        for t in f(hs, "2025-06-02", "2025-07-25"):
            assert t.stop_dist > 0, name


def test_intraday_twins_reproducible():
    hs = hs_pair()
    tr = I.TESTS["L3"](hs, "2025-06-02", "2025-07-25")
    a = I.twin_books(tr, hs, "2025-06-02", "2025-07-25", n=10)
    b = I.twin_books(tr, hs, "2025-06-02", "2025-07-25", n=10)
    assert np.allclose(a, b)


# ------------------------------------------------------------------------------------------------ stats and the lock
def test_bh_and_twin_p():
    q = L.bh({"a": 0.01, "b": 0.04, "c": 0.5})
    assert q["a"] == pytest.approx(0.03) and q["b"] == pytest.approx(0.06) and q["c"] == pytest.approx(0.5)
    assert L.twin_p(10, np.array([1, 2, 30])) == pytest.approx(0.5)


def test_final_read_refuses_a_second_time(tmp_path, monkeypatch):
    from research.hunt.r82 import study
    monkeypatch.setattr(L, "RESULTS", tmp_path)
    (tmp_path / "FINAL_READ").write_text("read once\n")
    with pytest.raises(SystemExit):
        study.final()
