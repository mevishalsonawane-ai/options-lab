"""R75 tests: synthetic data only (no lake, no secrets), plus one data check that runs only where R51's clean stores
exist. Every lever on hand-made data, the LOOK-AHEAD GUARD for every lever (changing anything at or after the decision
moment never changes the decision), and the gate, twin and breach maths.

    python -m pytest research/hunt/r75/test_r75.py -q
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r75cfg as R  # noqa: E402
import r75levers as L  # noqa: E402

D0 = date(2025, 1, 6)
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")


@dataclass
class Day:
    d: date
    idx: np.ndarray
    expiry: bool = False
    atm: object = None


def store(n=200, seed=0, start=20000.0):
    """{day: Day} of random-walk 1-minute candles (n sessions, weekends included: the levers only count sessions)."""
    rng = np.random.default_rng(seed)
    out, px = {}, start
    for k in range(n):
        c = px + np.cumsum(rng.normal(0, 3 + 2 * (k % 7 == 0), 375))
        o = np.concatenate([[px + rng.normal(0, 20)], c[:-1]])
        h, l = np.maximum(o, c) + 1, np.minimum(o, c) - 1
        d = D0 + timedelta(days=k)
        out[d] = Day(d, np.vstack([o, h, l, c]))
        px = c[-1]
    return out


def trade(day, und="NIFTY", er=20, xr=40, net=100.0, side="C", half=None):
    return dict(day=day, und=und, er=er, xr=xr, net=float(net), side=side, sig=er,
                net_half=float(net) / 2 - 10 if half is None else half)


def bump(bars: dict, from_day: date, k: float = 7.0) -> dict:
    """The same daily bars with every day ON or AFTER from_day changed."""
    return {d: (tuple(x * k for x in v) if d >= from_day else v) for d, v in bars.items()}


# ================================================================================================ daily bars, ATR
def test_daily_bars_nan_safe():
    s = store(3)
    d = sorted(s)[1]
    s[d].idx[:, :10] = np.nan
    s[d].idx[3, -5:] = np.nan
    o, h, l, c = L.daily_bars(s)[d]
    assert o == s[d].idx[0, 10] and c == s[d].idx[3, -6]
    assert h == np.nanmax(s[d].idx[1]) and l == np.nanmin(s[d].idx[2])
    s[d].idx[:] = np.nan
    assert d not in L.daily_bars(s)


def test_atr_wilder_and_no_look_ahead():
    bars = {D0 + timedelta(days=i): (10.0, 12.0, 8.0, 10.0) for i in range(20)}
    a = L.atr_series(bars, 14)
    ds = sorted(bars)
    assert np.isnan(a[ds[12]]) and a[ds[13]] == pytest.approx(4.0) and a[ds[19]] == pytest.approx(4.0)
    s = L.daily_bars(store(60))
    a1 = L.atr_series(s)
    cut = sorted(s)[40]
    a2 = L.atr_series(bump(s, cut))
    assert all(a1[d] == a2[d] or (np.isnan(a1[d]) and np.isnan(a2[d])) for d in s if d < cut)


# ================================================================================================ L1
def test_l1_k2_blocks_after_second_loss_in_a_row():
    d = D0
    tr = [trade(d, er=10, xr=20, net=-100), trade(d, und="BANKNIFTY", er=15, xr=30, net=-50),
          trade(d, er=30, xr=40, net=500), trade(d, er=31, xr=50, net=300)]
    assert L.l1_trigger(tr, "k", 2) == 30
    kept = L.l1_apply(tr, "k", 2)
    assert [t["er"] for t in kept] == [10, 15, 30]          # same minute as the trigger exit: allowed
    assert L.l1_trigger(tr, "k", 3) is None and len(L.l1_apply(tr, "k", 3)) == 4


def test_l1_win_resets_the_run_and_x_mode():
    d = D0
    tr = [trade(d, er=1, xr=5, net=-100), trade(d, er=6, xr=9, net=50), trade(d, er=10, xr=12, net=-100),
          trade(d, er=13, xr=20, net=-6000), trade(d, er=25, xr=30, net=10)]
    assert L.l1_trigger(tr, "k", 2) == 20
    assert L.l1_trigger(tr, "x", 5000) == 20 and L.l1_trigger(tr, "x", 10000) is None
    assert L.l1_trigger([], "k", 2) is None


def test_l1_days_are_independent():
    tr = [trade(D0, er=1, xr=2, net=-1), trade(D0, er=3, xr=4, net=-1), trade(D0, er=5, xr=6, net=9),
          trade(D0 + timedelta(1), er=1, xr=2, net=5)]
    kept = L.l1_apply(tr, "k", 2)
    assert len(kept) == 3 and kept[-1]["day"] == D0 + timedelta(1)


def test_l1_look_ahead_guard():
    """Whether a trade is kept depends only on trades that CLOSED before its entry minute: scrambling the nets of
    every trade that exits at or after its entry never changes that trade's fate."""
    rng = np.random.default_rng(3)
    for _ in range(200):
        n = int(rng.integers(2, 12))
        er = np.sort(rng.integers(5, 300, n))
        tr = [trade(D0, und=UNDS[i % 5], er=int(e), xr=int(e + rng.integers(1, 40)), net=rng.normal(-50, 400))
              for i, e in enumerate(er)]
        mode, val = [("k", 2), ("k", 3), ("x", 500.0), ("x", 1000.0)][int(rng.integers(4))]
        base = {id(t) for t in L.l1_apply(tr, mode, val)}
        for j, t in enumerate(tr):
            alt = [dict(z, net=rng.normal(0, 1000)) if z["xr"] >= t["er"] and z is not t else z for z in tr]
            alt[j] = t
            assert (id(t) in {id(z) for z in L.l1_apply(alt, mode, val)}) == (id(t) in base)


# ================================================================================================ L2
def test_l2_neg_and_weak():
    tr = [trade(D0, und="NIFTY", net=-10), trade(D0, und="BANKNIFTY", net=50), trade(D0, und="SENSEX", net=-30)]
    assert L.l2_drop(tr, "NEG", UNDS) == {"NIFTY", "SENSEX"}
    assert L.l2_drop(tr, "WEAK", UNDS) == {"SENSEX"}
    assert L.l2_drop([trade(D0, und="NIFTY", net=5)], "NEG", UNDS) == set()
    assert L.l2_drop([], "WEAK", UNDS) == {"SENSEX"}          # all 0: the later index (documented tie rule)


def test_l2_reads_training_days_only(monkeypatch):
    """The study passes the trades before the period; trades in or after it never reach l2_drop."""
    import r75study as X
    a = D0 + timedelta(10)
    hist = [trade(D0, und="NIFTY", net=-100), trade(D0, und="BANKNIFTY", net=100)]
    later = [trade(a, und="BANKNIFTY", net=-10 ** 6), trade(a, und="NIFTY", net=10 ** 6)]
    monkeypatch.setattr(X.S, "mods", lambda: {"K": type("K", (), {"UNDS": UNDS})})
    out = X.apply(hist + later, ("L2-NEG",), [t for t in hist + later if t["day"] < a])
    assert {t["und"] for t in out} == {"BANKNIFTY"}


# ================================================================================================ L3
def test_l3_boundaries():
    assert not L.l3_ok(14, "L3-F10") and L.l3_ok(15, "L3-F10")           # 09:29 / 09:30
    assert not L.l3_ok(29, "L3-F25") and L.l3_ok(30, "L3-F25")           # 09:44 / 09:45
    assert L.l3_ok(295, "L3-L60") and not L.l3_ok(296, "L3-L60")         # 14:10 / 14:11
    assert L.l3_ok(5, "L3-L60")


# ================================================================================================ L4
def test_vix_low_days_and_guard():
    ds = [D0 + timedelta(i) for i in range(200)]
    v = {d: 20.0 + (i % 10) for i, d in enumerate(ds)}
    low = L.vix_low_days(v, look=120, q=0.30)
    assert all(d >= ds[120] for d in low)
    assert ds[121] in low                    # prev close (session 120) = 20, the lowest value
    assert ds[126] not in low                # prev close 25
    for cut in (ds[130], ds[150], ds[199]):
        v2 = {d: (x * 3 if d >= cut else x) for d, x in v.items()}
        l2 = L.vix_low_days(v2, look=120, q=0.30)
        assert (cut in low) == (cut in l2) and {d for d in low if d <= cut} == {d for d in l2 if d <= cut}


def test_or_narrow_reads_first_30_minutes_only():
    idx = np.full((4, 375), 100.0)
    idx[1, :30], idx[2, :30] = 101.0, 99.0                  # range 2
    assert L.or_narrow(idx, 5.0) and not L.or_narrow(idx, 3.0)
    idx2 = idx.copy()
    idx2[:, 30:] = np.random.default_rng(1).normal(100, 50, (4, 345))
    assert L.or_narrow(idx2, 5.0) == L.or_narrow(idx, 5.0)
    assert not L.or_narrow(idx, np.nan)
    assert L.l4_or_ok(29, True) and not L.l4_or_ok(30, True) and L.l4_or_ok(200, False)


def test_pd_narrow_and_guard():
    ds = [D0 + timedelta(i) for i in range(30)]
    bars = {d: (100.0, 102.0, 98.0, 100.0) for d in ds}
    bars[ds[20]] = (100.0, 100.5, 99.5, 100.0)              # narrow day (range 1 vs ATR 4)
    atr = L.atr_series(bars)
    s = L.pd_narrow_days(bars, atr)
    assert ds[21] in s and ds[20] not in s and ds[22] not in s
    big = L.daily_bars(store(80))
    a1 = L.pd_narrow_days(big, L.atr_series(big))
    for cut in sorted(big)[30::10]:
        b2 = bump(big, cut)
        a2 = L.pd_narrow_days(b2, L.atr_series(b2))
        assert {d for d in a1 if d <= cut} == {d for d in a2 if d <= cut}


# ================================================================================================ L6
def test_gap_days_and_guard():
    ds = [D0 + timedelta(i) for i in range(30)]
    bars = {d: (100.0, 102.0, 98.0, 100.0) for d in ds}
    bars[ds[20]] = (103.0, 104.0, 102.0, 103.0)             # gap 3 > 0.5 x ATR 4
    s = L.gap_days(bars, L.atr_series(bars))
    assert ds[20] in s and ds[19] not in s
    big = L.daily_bars(store(80))
    g1 = L.gap_days(big, L.atr_series(big))
    for cut in sorted(big)[20::10]:
        # day `cut`: only its open is known at the decision; change its high / low / close and every later day
        b2 = {d: ((v[0], v[1] * 2, v[2] / 2, v[3] * 3) if d == cut else (tuple(x * 5 for x in v) if d > cut else v))
              for d, v in big.items()}
        g2 = L.gap_days(b2, L.atr_series(b2))
        assert {d for d in g1 if d <= cut} == {d for d in g2 if d <= cut}


# ================================================================================================ L7
def test_high_vol_days_and_guard():
    ds = [D0 + timedelta(i) for i in range(120)]
    rng = np.random.default_rng(0)
    c = 100 * np.exp(np.cumsum(np.r_[rng.normal(0, 0.005, 90), rng.normal(0, 0.03, 30)]))
    bars = {d: (x, x, x, x) for d, x in zip(ds, c)}
    hv = L.high_vol_days(bars)
    assert any(d >= ds[92] for d in hv) and not any(d < ds[70] for d in hv)     # needs 10 + 60 sessions first
    for cut in ds[75::9]:
        b2 = {d: (tuple(x * (1 + 0.2 * ((d - cut).days % 2)) for x in v) if d >= cut else v) for d, v in bars.items()}
        h2 = L.high_vol_days(b2)
        assert {d for d in hv if d <= cut} == {d for d in h2 if d <= cut}


def test_dd_weights_sequential_and_guard():
    days = [D0 + timedelta(i) for i in range(6)]
    tr = [trade(days[0], net=-15000, half=-7600), trade(days[1], net=-10000, half=-5100),
          trade(days[2], net=-1000, half=-600), trade(days[3], net=20000, half=9900), trade(days[4], net=100)]
    w = L.dd_weights(tr, 20000)
    assert w[days[0]] == 1.0 and w[days[1]] == 1.0 and w[days[2]] == 0.5   # -25,000 after day 1
    assert w[days[3]] == 0.5                                               # -25,600 (day 2 sized at half)
    assert w[days[4]] == 1.0                                               # -15,700: back above the cut
    for j in range(1, 5):
        alt = [dict(t, net=t["net"] * -3, net_half=t["net_half"] * -3) if t["day"] >= days[j] else t for t in tr]
        assert L.dd_weights(alt, 20000)[days[j]] == w[days[j]]


def test_sized_and_half_twins():
    tr = [trade(D0, net=100, half=40), trade(D0, net=-100, half=-60)]
    s = L.sized(tr, lambda t: t["net"] < 0)
    assert [t["net"] for t in s] == [100, -60] and [t["w"] for t in s] == [1.0, 0.5]
    assert tr[1]["net"] == -100                                            # the input is not changed
    tw = L.half_twins([100, -100], [40, -60], 2, 10, np.random.default_rng(0))
    assert np.allclose(tw, -20)
    assert np.allclose(L.half_twins([100], [40], 0, 3, np.random.default_rng(0)), 100)


# ================================================================================================ gate maths
def test_better_rules():
    b = dict(net=10000, max_dd=50000, worst_day=-20000)
    assert L.better(dict(net=12000, max_dd=54000, worst_day=-25000), b)        # (a)
    assert not L.better(dict(net=12000, max_dd=56000, worst_day=-25000), b)    # (a) DD too big, (b) no cut
    assert L.better(dict(net=5500, max_dd=47000, worst_day=-20000), b)         # (b): give <= Rs 5,000
    assert not L.better(dict(net=4900, max_dd=40000, worst_day=-10000), b)     # gave more than Rs 5,000
    assert L.better(dict(net=6000, max_dd=50000, worst_day=-18900), b)         # (b) worst day
    big = dict(net=200000, max_dd=100000, worst_day=-50000)
    assert L.better(dict(net=181000, max_dd=90000, worst_day=-50000), big)     # 10% of 2 lakh
    assert not L.better(dict(net=179000, max_dd=90000, worst_day=-50000), big)
    neg = dict(net=-20000, max_dd=60000, worst_day=-9000)
    assert L.better(dict(net=-24000, max_dd=50000, worst_day=-9000), neg)      # |base| for a losing base


def test_breaches():
    ds = [date(2025, 1, 2), date(2025, 1, 3), date(2025, 2, 3), date(2025, 2, 4), date(2025, 3, 3), date(2025, 3, 4)]
    nets = [-14000, 0, -30000, 5000, 60000, -39000]
    tr = [trade(d, net=n) for d, n in zip(ds, nets)]
    b = L.breaches(tr)
    assert b["months_13k"] == 2 and b["months"] == 3
    assert b["dd_40k"] == 1                    # 44,000 deep in Feb; new peak in March, then 39,000 (not > 40,000)
    tr.append(trade(date(2025, 3, 5), net=-2000))
    assert L.breaches(tr)["dd_40k"] == 2


def test_twins_p_bh():
    tw = L.skip_twins([1, 2, 3, 4], 0, 5, np.random.default_rng(0))
    assert np.allclose(tw, 10)
    tw = L.skip_twins([1, 2, 3, 4], 4, 5, np.random.default_rng(0))
    assert np.allclose(tw, 0)
    assert L.p_of(np.zeros(499), 1.0) == pytest.approx(1 / 500)
    assert L.p_of(np.ones(500), 1.0) == pytest.approx(1.0)
    sf = L.signflip([0, 0, 0], 50, np.random.default_rng(0))
    assert np.allclose(sf, 0) and L.p_of(sf, 0.0) == 1.0
    sf = L.signflip([5, 5, 5, 5, 5, 5, 5, 5, 5, 5], 500, np.random.default_rng(0))
    assert L.p_of(sf, 50.0) < 0.01
    q = L.bh([0.01, 0.04, 0.03, 0.5])
    assert np.allclose(q, [0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])


def test_gate_needs_every_part():
    ok = dict(design_better=True, wf_better=3, final_better=True, p=0.01, q=0.05, affected=40, slip2_better=True,
              slip2_net=1)
    assert L.gate(ok) == []
    for k, v in (("design_better", False), ("wf_better", 2), ("final_better", False), ("p", 0.05), ("q", 0.11),
                 ("affected", 39), ("slip2_better", False), ("slip2_net", 0), ("p", None)):
        assert L.gate(dict(ok, **{k: v})), k


def test_config_is_the_preregistered_grid():
    assert len(R.LEVERS) == 20 and len(R.TESTS) == 40 and set(R.NAMES) == set(R.LEVERS)
    assert R.SEED == 75 and R.TWINS == 500 and R.GATE_Q == 0.10 and R.GATE_AFFECTED == 40
    assert R.L3_SPEC["L3-L60"][1] == 14 * 60 + 10 - (9 * 60 + 15)


# ================================================================================================ study wiring
def test_entry_filter_or_only_after_0945(monkeypatch):
    import r75study as X
    d = D0 + timedelta(1)
    idx = np.full((4, 375), 100.0)
    idx[1, :30], idx[2, :30] = 100.5, 99.5
    monkeypatch.setattr(X, "_CTX", dict(prev={"NIFTY": {d: D0}}, atr={"NIFTY": {D0: 10.0}}, vix_low={d},
                                        pdn={"NIFTY": set()}, exp_any=set(), gap={"NIFTY": set()}))
    D = Day(d, idx)
    f = X.entry_filter(("L4-OR",), "NIFTY", D)
    assert f(29, "C") and not f(30, "C")
    assert not X.entry_filter(("L4-VIX",), "NIFTY", D)(10, "C")
    assert X.entry_filter(("L4-PD", "L6-GAP", "L6-EXP"), "NIFTY", D)(10, "P")
    assert X.entry_filter(("L3-F10",), "NIFTY", D)(15, "C") and not X.entry_filter(("L3-F10",), "NIFTY", D)(14, "C")


def test_compare_counts_skips_and_halves():
    import r75study as X
    base = [trade(D0, er=10, net=-100), trade(D0, er=20, net=200), trade(D0, er=30, net=-50, half=-30)]
    lv = [dict(base[1], w=1.0), dict(base[2], net=-30, w=0.5), trade(D0, er=40, net=7)]
    c = X.compare(base, lv)
    assert (c["skipped"], c["losers_avoided"], c["losers_avoided_rs"], c["winners_missed"]) == (1, 1, 100, 0)
    assert (c["halved"], c["halved_losers"], c["halved_saved_rs"], c["extra"]) == (1, 1, 20, 1)
    assert X.affected("L1-K2", base, lv, c) == 3 and X.affected("L5-ATM", base, lv, c) == 3


# ================================================================================================ real data (optional)
_STORES = Path(R.CACHE_ROOT) / "r51" / "clean" / "day_NIFTY.pkl"


@pytest.mark.skipif(not _STORES.exists(), reason="R51 clean stores not built here")
def test_baselines_reproduce_design():
    import r75study as X
    for b in ("A", "B", "C"):
        t = X.priced(b, R.START, R.DESIGN_END)
        assert round(sum(z["net"] for z in t)) == R.EXPECT[(b, "design")]
