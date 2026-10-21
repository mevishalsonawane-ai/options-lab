"""R94 idea 3 tests (PREREG section 8, NOTES_idea3 N3.1): hand-made option books, no lake data."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r94_mcx_advanced import common as C
from research.hunt.r94_mcx_advanced import options as O

STEP = 10.0
BOOK = "CRUDEOILM"                     # units 10, tick 0.10


def tv(cp, k, f):
    """Hand-made option price: intrinsic + a time value that falls with distance from the money."""
    intr = max(0.0, f - k) if cp == 0 else max(0.0, k - f)
    return round(intr + max(1.0, 20.0 - 4.0 * abs(k - f) / STEP), 2)


def make_raw(days, spot_fn, price_fn=tv, vol_fn=None, iv_fn=None, t0=17 * 60, t1=18 * 60):
    """Rows for every minute t0..t1 of each day, offsets -3..+3, both sides. spot_fn(day_i, minute) -> spot;
    price_fn(cp, strike, spot) -> close; vol_fn(day_i, minute, cp, strike) -> volume (default 1)."""
    rows = []
    for di, d in enumerate(days):
        for m in range(t0, t1 + 1):
            ts = pd.Timestamp(d) + pd.Timedelta(minutes=m)
            f = spot_fn(di, m)
            atm = round(f / STEP) * STEP
            for off in range(-3, 4):
                k = atm + off * STEP
                for cp, side in ((0, "C"), (1, "P")):
                    v = 1 if vol_fn is None else vol_fn(di, m, cp, k)
                    iv = 30.0 if iv_fn is None else iv_fn(di, m)
                    p = price_fn(cp, k, f)
                    rows.append((ts, off, side, k, p, p, p, p, iv, v, f))
    return pd.DataFrame(rows, columns=["ts", "offset", "side", "strike", "open", "high", "low", "close", "iv", "volume",
                                       "spot"])


def book(raw, exp=(), sym=BOOK):
    return O.Book(sym, raw, exp_days=list(exp), flag=False)


DAYS = ["2025-09-01", "2025-09-02", "2025-09-03", "2025-09-04"]


# ------------------------------------------------------------------------------------------------ basics
def test_half_spread_vector_matches_scalar():
    for tod in (16 * 60, 18 * 60):
        for off in (0, 1, 3):
            for v in (0.05, 3.0, 250.0):
                assert O._hs_vec(BOOK, np.array([v]), np.array([tod]), off)[0] == pytest.approx(
                    C.opt_half_spread(BOOK, v, tod, off))


def test_ic_credit_margin_and_time_exit():
    raw = make_raw(DAYS[:2], lambda di, m: 1000.0)
    bk = book(raw)
    t, why = O.simulate(bk, "IC", 0, 1)
    assert why is None and t["reason"] == "time"
    u = C.OPT_UNITS[BOOK]
    # entry at 17:15 (after 17:00: ATM rate); short 1010C / 990P at 16.0, long 1030C / 970P at 8.0
    hs = lambda p, off: C.opt_half_spread(BOOK, p, 17 * 60 + 15, off)
    credit = 2 * (16.0 - hs(16.0, 1)) - 2 * (8.0 + hs(8.0, 3))
    assert t["credit"] == pytest.approx(credit * u)
    assert t["margin_rs"] == pytest.approx(20.0 * u - credit * u)              # wing width 2 strikes, minus credit
    assert t["exit_ts"] == pd.Timestamp("2025-09-02 17:15")
    assert t["gross"] == pytest.approx(0.0)                                     # prices never moved
    assert t["net"] < 0                                                         # spreads and charges only


def test_leg_tracking_across_offsets():
    # spot rises one strike after entry: the held 1010 call is now ATM (offset 0); its price must be 1010's
    raw = make_raw(DAYS[:2], lambda di, m: 1000.0 if (di == 0 and m <= 17 * 60 + 15) else 1010.0)
    bk = book(raw)
    j = bk.first_at(0, 17 * 60 + 15)
    J = np.array([j + 1])
    v, oow, stale, miss = bk.value(bk.seg[j], 0, 1010.0, J, short=True)
    assert not oow[0] and v[0] == pytest.approx(tv(0, 1010.0, 1010.0))
    assert bk.K[(0, 0)][j + 1] == 1010.0 and bk.K[(0, 1)][j + 1] == 1020.0


def test_out_of_window_bounds():
    # spot jumps 6 strikes up: the 1010 call is ATM-5 (out of window); short = intrinsic + edge time value; long = intrinsic
    raw = make_raw(DAYS[:2], lambda di, m: 1000.0 if (di == 0 and m <= 17 * 60 + 15) else 1070.0)
    bk = book(raw)
    j = bk.first_at(0, 17 * 60 + 15)
    J = np.array([j + 1])
    v, oow, _, miss = bk.value(bk.seg[j], 0, 1010.0, J, short=True)
    edge = 1040.0                                                               # offset -3 at spot 1070
    assert oow[0] and miss == 0
    assert v[0] == pytest.approx(60.0 + (tv(0, edge, 1070.0) - 30.0))
    vl, oowl, _, _ = bk.value(bk.seg[j], 0, 1010.0, J, short=False)
    assert oowl[0] and vl[0] == pytest.approx(60.0)
    # the true (hand-made) price lies between the bounds
    assert vl[0] <= tv(0, 1010.0, 1070.0) <= v[0]


def test_stale_minutes_counted():
    vol = lambda di, m, cp, k: 1 if (di == 0 and m <= 17 * 60 + 15) else 0     # nothing prints after entry
    raw = make_raw(DAYS[:2], lambda di, m: 1000.0, vol_fn=vol, t1=19 * 60)
    bk = book(raw)
    t, _ = O.simulate(bk, "NS", 0, 1)
    assert t["stale_min"] > 0


# ------------------------------------------------------------------------------------------------ stop / lock / tp
def _shift_price(after_m, mult, day=0):
    """Short legs' prices are multiplied by mult from minute after_m of the given day on."""
    state = {}

    def spot(di, m):
        state["now"] = (di, m)
        return 1000.0

    def price(cp, k, f):
        di, m = state["now"]
        p = tv(cp, k, f)
        return round(p * mult(di, m), 2)
    return spot, price


def test_hard_stop():
    spot, price = _shift_price(None, lambda di, m: 3.0 if (di == 0 and m >= 17 * 60 + 30) else 1.0)
    bk = book(make_raw(DAYS[:2], spot, price_fn=price))
    t, _ = O.simulate(bk, "NS", 0, 1)
    assert t["reason"] == "stop" and t["exit_ts"] == pd.Timestamp("2025-09-01 17:30")
    assert t["net"] < -t["credit"] * 0.9


def test_take_profit():
    spot, price = _shift_price(None, lambda di, m: 0.1 if (di == 0 and m >= 17 * 60 + 40) else 1.0)
    bk = book(make_raw(DAYS[:2], spot, price_fn=price))
    t, _ = O.simulate(bk, "NS", 0, 1)
    assert t["reason"] == "tp" and t["exit_ts"] == pd.Timestamp("2025-09-01 17:40")


def test_profit_lock_trails_and_exits():
    def mult(di, m):
        if di == 0 and 17 * 60 + 20 <= m < 17 * 60 + 35:
            return 0.4                    # profit ~ 55-60% of credit: lock armed
        if di == 0 and m >= 17 * 60 + 35:
            return 0.85                   # profit falls back below the floor
        return 1.0
    spot, price = _shift_price(None, mult)
    bk = book(make_raw(DAYS[:2], spot, price_fn=price))
    t, _ = O.simulate(bk, "NS", 0, 1)
    assert t["reason"] == "lock" and t["exit_ts"] == pd.Timestamp("2025-09-01 17:35")


def test_covered_call_stop_on_notional():
    spot = lambda di, m: 1000.0 if (di == 0 and m < 17 * 60 + 30) else 985.0      # -1.5% on the future
    bk = book(make_raw(DAYS[:2], spot))
    t, _ = O.simulate(bk, "CC", 0, 1)
    assert t["reason"] == "stop" and t["exit_ts"] == pd.Timestamp("2025-09-01 17:30")
    assert t["margin_rs"] == C.FUT_MARGIN[BOOK]


# ------------------------------------------------------------------------------------------------ expiry, entry
def test_no_hold_across_expiry():
    days = [f"2025-09-{d:02d}" for d in range(1, 16)]
    bk = book(make_raw(days, lambda di, m: 1000.0), exp=["2025-09-12"])
    plan = O.entry_plan(bk, "D1", (pd.Timestamp("2025-09-01"), pd.Timestamp("2025-09-30")))
    e = bk.day_index("2025-09-12")
    assert all(a != e and b != e for a, b in plan)                               # no entry on / into the expiry day
    assert (e - 1, e) not in plan
    cy = O.entry_plan(bk, "CY", (pd.Timestamp("2025-09-01"), pd.Timestamp("2025-09-30")))
    assert cy == [(e - 10, e - 1)]
    # a hand-forced pair across the expiry is refused
    t, why = O.simulate(bk, "IC", e - 1, e + 1)
    assert t is None and why == "cross_expiry"


def test_entry_waits_then_skips():
    late = lambda di, m, cp, k: 1 if m >= 17 * 60 + 20 else 0
    bk = book(make_raw(DAYS[:2], lambda di, m: 1000.0, vol_fn=late))
    j, legs = O.try_entry(bk, "IC", 0)
    assert bk.tod[j] == 17 * 60 + 20
    never = lambda di, m, cp, k: 0 if (cp == 1 and k == 970.0) else 1             # the long put wing never prints
    bk2 = book(make_raw(DAYS[:2], lambda di, m: 1000.0, vol_fn=never))
    j2, why = O.try_entry(bk2, "IC", 0)
    assert j2 is None and why == "no_print"
    assert O.try_entry(bk2, "NS", 0)[0] is not None                              # the strangle needs no wing


def test_iv_percentile():
    days = pd.bdate_range("2025-01-01", periods=60).strftime("%Y-%m-%d").tolist()
    ivf = lambda di, m: 10.0 + di                                                # rising IV: today is the highest
    bk = book(make_raw(days, lambda di, m: 1000.0, iv_fn=ivf, t0=17 * 60 + 10, t1=17 * 60 + 20))
    assert np.isnan(bk.iv_pct[:40]).all()
    assert bk.iv_pct[40] == pytest.approx(100.0) and bk.iv_ref[40] == pytest.approx(50.0)


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("seed", [1, 2, 3])
def test_look_ahead_guard(seed):
    rng = np.random.default_rng(seed)
    days = pd.bdate_range("2025-03-03", periods=50).strftime("%Y-%m-%d").tolist()
    walk = 1000.0 + np.cumsum(rng.normal(0, 4, size=(len(days), 61)), axis=None).reshape(len(days), 61)
    ivs = 20 + rng.random(len(days)) * 20
    vol = lambda di, m, cp, k: int(rng.random() > 0.2)
    raw = make_raw(days, lambda di, m: float(walk[di, m - 17 * 60]), vol_fn=vol, iv_fn=lambda di, m: ivs[di])
    full = book(raw)
    cut_day = 45
    cut_ts = pd.Timestamp(days[cut_day]) + pd.Timedelta(hours=17, minutes=30)
    trunc = book(raw[raw.ts < cut_ts])
    pert = raw.copy()
    after = pert.ts >= cut_ts
    pert.loc[after, ["close", "open", "high", "low"]] *= 1.7
    pert.loc[after, "iv"] *= 3
    pert.loc[after, "spot"] += 40
    perturbed = book(pert)
    for other in (trunc, perturbed):
        n = cut_day                                                              # sessions wholly before the cut
        np.testing.assert_array_equal(full.iv_pct[:n + 1], other.iv_pct[:n + 1])
        for s in ("IC", "IF", "NS", "CC"):
            for di in range(n):
                a, b = O.try_entry(full, s, di), O.try_entry(other, s, di)
                assert a[0] == b[0] and a[1] == b[1]
            for di in range(40, n - 1):                                          # trades that exit before the cut
                ta, _ = O.simulate(full, s, di, di + 1)
                tb, _ = O.simulate(other, s, di, di + 1)
                assert (ta is None) == (tb is None)
                if ta is not None:
                    assert ta["net"] == pytest.approx(tb["net"]) and ta["reason"] == tb["reason"]
    assert sum(O.try_entry(full, "IC", di)[0] is not None for di in range(cut_day)) >= 10   # guard not vacuous


def test_cell_ids_count():
    assert len(O.cell_ids()) == 88


# ------------------------------------------------------------------------------------------------ A3.1 valuation
def test_long_wing_convexity_bound_vs_intrinsic():
    # spot falls one strike: the long 1030 call (+3 at entry) leaves the window; convexity gives 4, intrinsic 0
    raw = make_raw(DAYS[:2], lambda di, m: 1000.0 if (di == 0 and m <= 17 * 60 + 15) else 990.0)
    bk = book(raw)
    j = bk.first_at(0, 17 * 60 + 15)
    J = np.array([j + 1])
    v, oow, _, miss = bk.value(bk.seg[j], 0, 1030.0, J, short=False)
    assert oow[0] and miss == 0 and v[0] == pytest.approx(4.0)
    assert v[0] <= tv(0, 1030.0, 990.0)                                          # still a lower bound
    v0, _, _, _ = bk.value(bk.seg[j], 0, 1030.0, J, short=False, long_mode="intrinsic")
    assert v0[0] == 0.0


def test_iron_condor_never_loses_more_than_max_loss():
    # a 9-strike crash: every IC leg leaves the window; A1 loss stays within max loss + spreads + charges
    raw = make_raw(DAYS[:2], lambda di, m: 1000.0 if (di == 0 and m <= 17 * 60 + 15) else 910.0)
    bk = book(raw)
    t, _ = O.simulate(bk, "IC", 0, 1)
    assert t["reason"] == "stop" or t["net"] <= 0
    slack = 4 * 2 * 0.5 * C.OPT_UNITS[BOOK] + t["charges"]                       # generous half-spread allowance
    assert -t["net"] <= t["margin_rs"] + slack
    t0, _ = O.simulate(bk, "IC", 0, 1, valuation="prereg")
    assert t0 is not None
