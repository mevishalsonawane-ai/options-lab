"""F1 tests: the arm simulator on hand-made paths, the screen statistics, the rule signals, metrics and the holdout
lock. Run: python -m pytest research/hunt/f1 -q"""
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402
import rules as RU  # noqa: E402
import screen as S  # noqa: E402
from sim import R_LAST, R_LOCK, R_NONE, R_SQ, R_STOP, R_TIME, net_rs, simulate  # noqa: E402

LOT = 65.0


def path(prices, vol=1000.0):
    """375-minute bars from a list of (row, open, high, low, close); other rows: flat at the last close."""
    O, H, L, C = (np.full(375, np.nan) for _ in range(4))
    V = np.full(375, np.nan)
    last = None
    spec = {r: (o, h, l, c) for r, o, h, l, c in prices}
    for r in range(375):
        if r in spec:
            o, h, l, c = spec[r]
        elif last is not None:
            o = h = l = c = last
        else:
            continue
        O[r], H[r], L[r], C[r], V[r] = o, h, l, c, vol
        last = c
    return O, H, L, C, V


def sim(O, H, L, C, V, t=20, sq=355, x1=True):
    return simulate(O, H, L, C, V, t, sq, LOT, x1, K.STOP_PCT, K.LOCK_ACT, K.LOCK_GIVE, K.TIME_STOP_MIN,
                    K.TIME_STOP_GAIN)


def test_stop_at_minus_15_percent():
    O, H, L, C, V = path([(0, 100, 100, 100, 100), (25, 100, 100, 80, 82)])
    f, x, why, e, xr, _ = sim(O, H, L, C, V)
    assert why == R_STOP and e == 21 and xr == 25
    assert f == pytest.approx(100.05 + 0.05, abs=0.011)          # 5 bps + 1 tick (volume >= 20 lots)
    trig = np.floor(f * 0.85 / 0.05 + 1e-9) * 0.05
    assert x == pytest.approx(round(trig * 0.999, 2) - 0.10, abs=0.011)   # stop: 10 bps + 2 ticks


def test_gap_through_the_stop_fills_at_the_open():
    O, H, L, C, V = path([(0, 100, 100, 100, 100), (25, 70, 72, 69, 70)])
    _, x, why, *_ = sim(O, H, L, C, V)
    assert why == R_STOP and x == pytest.approx(round(70 * 0.999, 2) - 0.10, abs=0.011)


def test_lock_from_plus_1000_keeps_500_and_trails():
    # +1,000 a lot = 15.4 points at lot 65; peak 130 then fall: lock at 130 - 500/65 = 122.31 -> 122.35 (tick up)
    O, H, L, C, V = path([(0, 100, 100, 100, 100), (22, 101, 130, 101, 129), (23, 129, 129, 120, 121)])
    f, x, why, e, xr, best = sim(O, H, L, C, V, x1=False)
    assert why == R_LOCK and xr == 23
    assert x == pytest.approx(round(122.35 * 0.999, 2) - 0.10, abs=0.011)
    assert (x - f) * LOT > 500 - 30                              # keeps about Rs 500 a lot (gross, less slippage)


def test_lock_not_active_on_the_bar_that_makes_the_peak():
    # the peak and the drop in the same bar: the lock uses the best price BEFORE the bar, so bar 22 does not exit
    # (95 is above the -15% stop); the next bar opens at 96, already under the lock (122.35): out at that open
    O, H, L, C, V = path([(0, 100, 100, 100, 100), (22, 101, 130, 95, 96)])
    _, x, why, _, xr, _ = sim(O, H, L, C, V, x1=False)
    assert why == R_LOCK and xr == 23 and x == pytest.approx(round(96 * 0.999, 2) - 0.10, abs=0.011)


def test_time_stop_at_30_minutes_unless_plus_5_percent():
    O, H, L, C, V = path([(0, 100, 100, 100, 100)])
    _, x, why, e, xr, _ = sim(O, H, L, C, V)
    assert why == R_TIME and xr == e + 29
    O, H, L, C, V = path([(0, 100, 100, 100, 100), (30, 100, 108, 100, 107)])
    _, _, why, *_ = sim(O, H, L, C, V)
    assert why == R_SQ                                            # +5% at 30 minutes: held to the square-off


def test_time_stop_waits_for_the_next_traded_bar():
    O, H, L, C, V = path([(0, 100, 100, 100, 100)])
    for r in range(45, 52):
        O[r] = H[r] = L[r] = C[r] = V[r] = np.nan              # no trade around the 30-minute mark
    O[45:52] = np.nan
    _, _, why, e, xr, _ = sim(O, H, L, C, V, t=20)
    assert why == R_TIME and xr == 52 and e == 21


def test_no_fill_when_the_contract_does_not_trade():
    O, H, L, C, V = path([(0, 100, 100, 100, 100)])
    O[21:24] = np.nan
    f, x, why, *_ = sim(O, H, L, C, V)
    assert why == R_NONE and np.isnan(f) and np.isnan(x)
    assert np.isnan(net_rs(np.array([f]), np.array([x]), 65)[0])


def test_square_off_and_last_trade_when_the_strike_leaves_the_chain():
    O, H, L, C, V = path([(0, 100, 100, 100, 100), (30, 100, 110, 100, 110)])
    _, _, why, _, xr, _ = sim(O, H, L, C, V, x1=False, sq=350)
    assert why == R_SQ and xr == 350
    O[200:], H[200:], L[200:], C[200:] = np.nan, np.nan, np.nan, np.nan
    _, x, why, *_ = sim(O, H, L, C, V, x1=False)
    assert why == R_LAST and x == pytest.approx(110 * 0.9995 - 0.20, abs=0.02)   # 4 ticks: no volume known


def test_thin_contract_pays_more_spread():
    thick = sim(*path([(0, 100, 100, 100, 100)], vol=10000))[0]
    thin = sim(*path([(0, 100, 100, 100, 100)], vol=1.0))[0]
    assert thin - thick == pytest.approx(0.05, abs=1e-6)         # 2 ticks vs 1


def test_net_rs_charges_bse_differently():
    a = net_rs(np.array([100.0]), np.array([110.0]), 20, False)[0]
    b = net_rs(np.array([100.0]), np.array([110.0]), 20, True)[0]
    assert a < 200 and b < 200 and a != b


def test_bh_and_ic_test():
    q = S.bh(np.array([0.001, 0.02, 0.5, np.nan]))
    assert q[0] == pytest.approx(0.003) and q[1] == pytest.approx(0.03) and np.isnan(q[3])
    rng = np.random.default_rng(0)
    x = rng.normal(size=4000)
    y = x * 0.3 + rng.normal(size=4000)
    cl = np.repeat(np.arange(200), 20)
    ic, t, p, n, nd = S.ic_test(x, y, cl, 1.0)
    assert ic > 0.2 and p < 1e-6 and nd == 200
    ic2, t2, p2, *_ = S.ic_test(x, y, cl, -1.0)                   # the wrong direction is not significant
    assert ic2 < 0 and p2 > 0.99
    y0 = rng.normal(size=4000)
    assert S.ic_test(x, y0, cl, 1.0)[2] > 0.001


def _X(n_days=30, und="NIFTY"):
    rows = []
    for d in range(n_days):
        for r in K.RULE_ROWS:
            rows.append(dict(day=pd.Timestamp("2025-01-01") + pd.Timedelta(days=d), row=r, und=und, f=float(r),
                             **{f"net_{s}_itm1_{x}": 100.0 if r == 20 else -10.0 for s in ("ce", "pe") for x in ("x1", "x2")},
                             **{f"cut_{s}_itm1_{x}": 0.0 for s in ("ce", "pe") for x in ("x1", "x2")},
                             **{f"why_{s}_itm1_{x}": 4.0 for s in ("ce", "pe") for x in ("x1", "x2")}))
    return pd.DataFrame(rows)


def test_signals_quintiles_from_the_reference_and_first_filled_entry():
    X = _X()
    rule = dict(id="S1", kind="single", feature="f", level=None, sign=1.0, target="ret30", exit="x1",
                sides=["ce", "pe"], scope=["NIFTY"])
    sig = RU.signals(rule, X, X)
    hi = np.quantile(X.f, 0.8)
    assert (sig[X.f.values >= hi] == 1).all() and (sig[X.f.values <= np.quantile(X.f, 0.2)] == -1).all()
    X.loc[(X.row == 255), "net_ce_itm1_x1"] = np.nan               # the first call signal does not fill
    T = RU.trades(rule, X, sig)
    ce = T[T.side == "ce"]
    assert len(ce) == 30 and (ce.row > 255).all()                  # one a day, the first FILLED signal
    pe = T[T.side == "pe"]
    assert len(pe) == 30 and (pe.row == 15).all()


def test_twins_and_metrics():
    X = _X()
    rule = dict(id="S1", kind="single", feature="f", level=None, sign=-1.0, target="ret30", exit="x1",
                sides=["ce"], scope=["NIFTY"])
    T = pd.DataFrame(dict(day=X.day.unique(), und="NIFTY", side="ce", net=100.0, cut=0.0))
    tw = RU.twins(rule, T, X, np.random.default_rng(1))
    assert tw.shape == (K.N_TWINS,) and tw.mean() < 0              # random minutes mostly lose 10
    m = RU.metrics(T, sorted(X.day.unique()), tw)
    assert m["p_twin"] < 0.01 and m["win_rate"] == 1.0 and m["max_dd"] == 0.0 and m["trades_per_day"] == 1.0
    m2 = RU.metrics(T.assign(net=[-50.0] * 10 + [100.0] * 20), sorted(X.day.unique()))
    assert m2["max_dd"] == -500.0 and m2["worst_day"] == -50.0


def test_metrics_empty():
    m = RU.metrics(pd.DataFrame(columns=["day", "und", "side", "net", "cut"]), [pd.Timestamp("2025-01-01")])
    assert m["trades"] == 0 and m["net"] == 0.0 and m["max_dd"] == 0.0


def test_level_rule_negative_arm_sign_is_dropped():
    R = pd.DataFrame([dict(scope="NIFTY", target="net_ce_atm_x1", feature="squeeze_on", level=1.0, kind="single",
                           sign=-1.0, ic_sel=-0.05, t_sel=4.0, q=0.01, survivor=True, a=None, b=None)])
    assert RU.screen_rules(R, 18) == []
    R.loc[0, "sign"] = 1.0
    r = RU.screen_rules(R, 18)
    assert r[0]["sides"] == ["ce"] and r[0]["exit"] == "x1" and r[0]["kind"] == "level"


def test_periods_disjoint_and_holdout_locked(tmp_path, monkeypatch):
    assert K.part(date(2023, 6, 1)) == "holdout" and K.part(date(2022, 12, 20)) == "none"
    assert K.part(date(2024, 7, 10)) == "none" and K.part(date(2026, 4, 20)) == "none"
    assert K.part(date(2025, 3, 3)) == "design" and K.part(date(2021, 9, 1)) == "design"
    for f, (a, b) in K.FOLDS.items():
        assert K.part(a) == "design" and K.part(b) == "design"
    assert not any(K.within(d, K.SELECT) for d in (date(2022, 6, 1), date(2024, 8, 1)))
    monkeypatch.setattr(K, "HOLD_FLAG", tmp_path / "HOLDOUT_READ")
    import data as D
    with pytest.raises(AssertionError):
        D.load("NIFTY", "holdout")
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    (tmp_path / "holdout.json").write_text(json.dumps([]))
    with pytest.raises(AssertionError):
        RU.holdout()                                               # read once: never again


def test_breadth_point_in_time_and_shares():
    import breadth as BR
    rows = []
    for d, base in (("2025-01-01", 100.0), ("2025-01-02", 100.0)):
        for k in range(20):
            ts = pd.Timestamp(f"{d} 09:15") + pd.Timedelta(minutes=k)
            for sym, px in (("HDFCBANK", base + (1 if d == "2025-01-02" else 0)), ("INFY", base - k * 0.1),
                            ("TCS", base + k * 0.1)):
                rows.append(dict(symbol=sym, open=px, high=px, low=px, close=px, volume=10.0, ts=ts))
    B = BR.compute(pd.DataFrame(rows)).set_index("ts")
    day1 = B[B.index.date == date(2025, 1, 1)]
    assert day1.br_adv_share.isna().all()                         # no previous session yet
    d2 = B[B.index.date == date(2025, 1, 2)]
    # day 2 at 09:34: HDFCBANK 101 > 100, INFY 98.1 < 98.1? (prev close 98.1) -> equal: not above; TCS 101.9 = prev
    last = d2.iloc[-1]
    assert last.br_adv_share == pytest.approx(1 / 3)
    assert last.br_bank_adv_share == 1.0
    assert np.isnan(d2.br_adv_share_chg15.iloc[14]) and np.isfinite(d2.br_adv_share_chg15.iloc[15])
    # a change after t never moves the value at t
    cut = pd.DataFrame(rows)
    cut = cut[cut.ts <= pd.Timestamp("2025-01-02 09:25")]
    B2 = BR.compute(cut).set_index("ts")
    common = B2.index
    pd.testing.assert_frame_equal(B.loc[common], B2, check_dtype=False)
