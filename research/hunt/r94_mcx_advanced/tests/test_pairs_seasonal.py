"""R94 ideas 2 and 4 tests (PREREG 8): roll adjustment, next-open fills, the z stop, seasonal windows, the resting
stop, twins inside the year, and the look-ahead guard. No lake data needed."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r94_mcx_advanced import common as C
from research.hunt.r94_mcx_advanced import pairs as P
from research.hunt.r94_mcx_advanced import seasonal as S


def daily(dates, o, h, l, c, oi):
    return pd.DataFrame({"date": pd.to_datetime(dates), "open": o, "high": h, "low": l, "close": c,
                         "volume": 1.0, "oi": oi})


# ------------------------------------------------------------------------------------------------ idea 2
def test_roll_detection_and_adjusted_index():
    d = daily(pd.bdate_range("2020-01-01", periods=4), [100, 101, 111, 112], [102, 103, 113, 114],
              [99, 100, 110, 111], [101, 102, 112, 113], [1000, 900, 5000, 5100])
    leg = P.make_leg("GOLD", d)
    assert list(leg.roll) == [False, False, True, False]
    # row 2 is a roll: its return is close / open = 112 / 111; the 102 -> 111 switch gap is not earned
    assert leg.idx[2] / leg.idx[1] == pytest.approx(112 / 111)
    assert leg.idx_open[2] == pytest.approx(leg.idx[1])
    assert leg.idx_open[3] == pytest.approx(leg.idx[2] * 112 / 112)


def _pair_frame(r, sd=0.01):
    n = len(r)
    m = pd.DataFrame({"date": pd.bdate_range("2015-01-01", periods=n), "ia": np.exp(r), "ib": np.ones(n),
                      "ioa": np.exp(r), "iob": np.ones(n), "ra": False, "rb": False, "pa": 30000.0, "pb": 40000.0})
    m["r"] = np.log(m.ia / m.ib)
    return m


def test_pairs_fill_next_open_and_stop():
    r = np.r_[np.tile([0.0, 0.01], 30), 0.08, 0.09, 0.2, 0.2, 0.2]
    m = P.add_z(_pair_frame(r), 60)
    tr = P.simulate("GS", m, (date(2015, 1, 1), date(2030, 1, 1)))
    t = tr[0]
    assert t["e"] == 61 and t["side"] == -1                  # signal at the close of row 60, filled at the 61 open
    assert t["why"] == "stop"                                # z >= 3.5 at a close -> out at the next open
    assert m.z.values[t["x"] - 1] >= P.STOP_Z


def test_pairs_costs_count_rolls():
    m = _pair_frame(np.zeros(30))
    m["sd"] = 0.01
    m.loc[5, "ra"] = True
    t = P._trade("GS", m, 2, 10, 1, "x")
    one = P._cost("GOLD", 30000.0) + P._cost("SILVER", 40000.0)
    assert t["gross"] == 0 and t["rolls"] == 1
    assert t["costs"] == pytest.approx(one + P._cost("GOLD", 30000.0))


def test_pairs_align_carries_a_roll_on_a_missing_date():
    a = P.make_leg("GOLD", daily(pd.bdate_range("2020-01-01", periods=4), [1, 1, 2, 2], [1, 1, 2, 2], [1, 1, 2, 2],
                                 [1, 1, 2, 2], [10, 10, 100, 100]))
    db = daily(pd.bdate_range("2020-01-01", periods=4).delete(2), [1, 1, 1], [1, 1, 1], [1, 1, 1], [1, 1, 1],
               [10, 10, 10])
    b = P.make_leg("SILVER", db)
    m = P.align(a, b)
    assert len(m) == 3 and list(m.ra) == [False, False, True]


@pytest.mark.parametrize("seed", [0, 1, 2])
def test_pairs_look_ahead_guard(seed):
    rng = np.random.default_rng(seed)
    r = np.cumsum(rng.normal(0, 0.012, 1500))
    m0 = _pair_frame(r)
    full = P.add_z(m0, 60)
    w = (date(2015, 1, 1), date(2030, 1, 1))
    ft = P.simulate("GS", full, w)
    assert len(ft) >= 3
    for cut in rng.integers(300, 1400, size=4):
        cut = int(cut)
        pert = m0.copy()
        pert.loc[cut:, "r"] = pert.loc[cut:, "r"] + rng.normal(0, 0.2, len(pert) - cut)
        pert.loc[cut:, "ioa"] = np.exp(pert.loc[cut:, "r"])
        for other in (P.add_z(m0.iloc[:cut].copy(), 60), P.add_z(pert, 60)):
            assert np.allclose(full.z.values[:cut], other.z.values[:cut], equal_nan=True)
            got = [(t["e"], t["x"], t["side"], round(t["net"], 6)) for t in P.simulate("GS", other, w) if t["x"] < cut]
            exp = [(t["e"], t["x"], t["side"], round(t["net"], 6)) for t in ft if t["x"] < cut]
            assert got == exp


def test_pairs_twins_reproducible():
    rng = np.random.default_rng(4)
    m = P.add_z(_pair_frame(np.cumsum(rng.normal(0, 0.012, 800))), 60)
    w = (date(2015, 1, 1), date(2030, 1, 1))
    tr = P.simulate("GS", m, w)
    a = P.twins("GS", m, w, tr, n_sets=30, seed=1)
    assert len(a) == 30 and np.allclose(a, P.twins("GS", m, w, tr, n_sets=30, seed=1))


# ------------------------------------------------------------------------------------------------ idea 4
def _series(n=80, start="2019-10-01", o=None, h=None, l=None, c=None, oi=None, skip=()):
    dates = [d for d in pd.bdate_range(start, periods=n + len(skip)) if d.strftime("%Y-%m-%d") not in skip][:n]
    c = np.full(n, 100.0) if c is None else np.asarray(c, float)
    o = c.copy() if o is None else np.asarray(o, float)
    h = np.maximum(o, c) + 1 if h is None else np.asarray(h, float)
    l = np.minimum(o, c) - 1 if l is None else np.asarray(l, float)
    oi = np.full(n, 1000.0) if oi is None else np.asarray(oi, float)
    return S.Series("GOLD", daily(dates, o, h, l, c, oi))


def test_windows_skip_the_event_row():
    s = _series(60, start="2019-10-01")
    a, b = S.event_window(s, "2019-10-28", "pre")      # Monday 28 Oct is a row here (a Muhurat-like row)
    assert s.dates[b] == pd.Timestamp("2019-10-25") and b - a == 9
    a, b = S.event_window(s, "2019-10-27", "post")     # a Sunday date: the first row after it is 28 Oct
    assert s.dates[a] == pd.Timestamp("2019-10-28") and b - a == 9
    a2, _ = S.event_window(s, "2019-10-28", "post")
    assert s.dates[a2] == pd.Timestamp("2019-10-29")


def test_resting_stop_at_stop_price_and_at_a_gapped_open():
    n = 40
    c = np.full(n, 100.0)
    s = _series(n, c=c)                                 # TR = 2 every row -> ATR 2, stop distance 4
    lo = s.l.copy()
    lo[25] = 95.0
    s.l = lo
    t = S.trade(s, 20, 30, 1)
    assert t["why"] == "stop" and t["b"] == 25
    assert t["gross"] == pytest.approx((96.0 - 100.0) * 10)
    o = s.o.copy()
    o[22] = 90.0
    s.o = o
    t = S.trade(s, 20, 30, 1)
    assert t["why"] == "stop-gap" and t["b"] == 22 and t["gross"] == pytest.approx(-10.0 * 10)
    # short side mirror
    s = _series(n, c=c)
    hi = s.h.copy()
    hi[24] = 106.0
    s.h = hi
    t = S.trade(s, 20, 30, -1)
    assert t["why"] == "stop" and t["gross"] == pytest.approx(-4.0 * 10)


def test_roll_inside_a_hold_is_not_earned_and_costs_a_round_trip():
    n = 40
    c = np.r_[np.full(25, 100.0), np.full(15, 110.0)]
    o = c.copy()
    oi = np.r_[np.full(25, 1000.0), np.full(15, 9000.0)]
    s = _series(n, o=o, c=c, oi=oi)
    assert s.roll[25]
    t = S.trade(s, 20, 30, 1)
    assert t["gross"] == pytest.approx(0.0)
    assert t["costs"] == pytest.approx(S._cost(s, 100.0) + S._cost(s, 110.0))


def test_month_windows_complete_months_only():
    s = _series(70, start="2019-11-01")                 # 1 Nov 2019 - 6 Feb 2020: Feb is not complete
    w = S.month_windows(s)
    assert [x[2] for x in w] == ["2019-11", "2019-12", "2020-01"]
    w = S.month_windows(s, months=tuple(range(1, 13)), last_complete=pd.Timestamp("2019-12-31"))
    assert [x[2] for x in w] == ["2019-11", "2019-12"]


def test_seasonal_twins_stay_in_year():
    s = _series(600, start="2017-01-02", c=100 + np.cumsum(np.random.default_rng(0).normal(0, 1, 600)))
    wins = [(100, 109, 1, 2017, "x"), (360, 369, 1, 2018, "y")]
    rng_years = []
    orig = S.trade

    def spy(s_, a, b, side, why=""):
        rng_years.append(s_.dates[a].year)
        return orig(s_, a, b, side)
    S.trade = spy
    try:
        S.twins(s, "DW-PRE", wins, n_sets=50, seed=2)
    finally:
        S.trade = orig
    assert set(rng_years) <= {2017, 2018} and len(rng_years) > 10


@pytest.mark.parametrize("seed", [0, 1])
def test_seasonal_look_ahead_guard(seed):
    rng = np.random.default_rng(seed)
    n = 900
    c = 1000 + np.cumsum(rng.normal(0, 8, n))
    s_full = _series(n, start="2016-01-01", c=c)
    for cut in rng.integers(200, 850, size=4):
        cut = int(cut)
        c2 = c.copy()
        c2[cut:] += rng.normal(0, 50, n - cut)
        s_pert = _series(n, start="2016-01-01", c=c2)
        assert np.allclose(s_full.atr[:cut + 1], s_pert.atr[:cut + 1], equal_nan=True)   # ATR at row t uses rows < t
        for a in range(20, cut - 12, 37):
            t1, t2 = S.trade(s_full, a, a + 9, 1), S.trade(s_pert, a, a + 9, 1)
            if a + 9 < cut:
                assert t1["net"] == pytest.approx(t2["net"])


def test_metrics_bh_bootstrap_and_lock(tmp_path):
    tr = [{"entry_ts": pd.Timestamp("2026-01-05"), "exit_ts": pd.Timestamp("2026-01-05"), "net": x, "gross": x,
           "risk_rs": 1500.0} for x in (100, -50, 30, -20, 10, 5, -1)]
    s = C.summary(tr)
    assert s["trades"] == 7 and s["net"] == 74 and s["worst_day"] == 74 and s["fit_2pct_1L"] == 1.0
    assert s["net_wo_top5"] == pytest.approx(-70)          # -50 - 20
    q = C.bh({"a": 0.01, "b": 0.04, "c": 0.5})
    assert q["a"] == pytest.approx(0.03) and q["b"] == pytest.approx(0.06) and q["c"] == pytest.approx(0.5)
    assert C.block_bootstrap_p(np.r_[np.full(50, 10.0), np.full(50, 12.0)]) < 0.01
    assert C.block_bootstrap_p(np.random.default_rng(0).normal(-1, 1, 200)) > 0.5
    lock = tmp_path / "lock.json"
    C.open_holdout("idea9", ["a", "b"], lock=lock)
    with pytest.raises(C.HoldoutReadError):
        C.open_holdout("idea9", ["b"], lock=lock)
    C.open_holdout("idea9", ["c"], lock=lock)


def test_costs_match_mcxcosts_examples():
    # McxCosts.kt doc: a 1-lot CRUDEOIL futures round trip at Rs 8,552 (Rs 8,55,200 a side) comes to Rs 194;
    # a CRUDEOIL ATM option round trip on Rs 24,200 of premium to Rs 84.
    assert C.fut_leg_charges(855_200, 855_200) == pytest.approx(194, abs=1.5)
    assert C.opt_leg_charges(24_200, 24_200) == pytest.approx(84, abs=1.5)
    assert C.opt_half_spread("CRUDEOIL", 0.5, 18 * 60, 3) == C.OPT_TICK["CRUDEOIL"]
    assert C.opt_half_spread("CRUDEOIL", 100.0, 18 * 60, 0) == pytest.approx(0.30)
    assert C.opt_half_spread("CRUDEOIL", 100.0, 10 * 60, 2) == pytest.approx(1.20)
    assert C.fut_half_spread("CRUDEOILM", 8000.0) == 1.0 and C.fut_half_spread("CRUDEOILM", 8000.0, far=True) == 3.0
