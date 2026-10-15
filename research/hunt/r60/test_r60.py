"""R60 tests on synthetic data (no lake): cleaning, ATR14, the translated Solo signal, held-contract pricing, the
"1-ITM else ATM" rule, exits and sales, MCX sessions / holidays / expiry, missing data, bad evening prints, the
look-ahead guard on every feature, the period guard, twins, BH and the gates.

    python -m pytest research/hunt/r60/test_r60.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r60cfg as C  # noqa: E402
import r60mcx as X  # noqa: E402
from research.hunt.r53.mcx import data as D53  # noqa: E402

DAY = pd.Timestamp("2026-01-14")          # US winter time: MCX closes 23:55


# ------------------------------------------------------------------------------------------------ synthetic market
def minutes(day, start="09:00", end="23:30"):
    return pd.date_range(f"{day.date()} {start}", f"{day.date()} {end}", freq="1min")


def make_raw(days, fut_fn, strikes_step=100.0, cp_price=None, vol_fn=None, extra=None):
    """Lake-like rows: offsets -3..+3, calls and puts, spot = the future; option price = intrinsic + 50 by default."""
    rows = []
    for day in days:
        ts = minutes(day)
        for i, t in enumerate(ts):
            f = fut_fn(day, i, t)
            atm = round(f / strikes_step) * strikes_step
            for off in range(-3, 4):
                k = atm + off * strikes_step
                for side in ("C", "P"):
                    px = cp_price(day, i, t, side, k, f) if cp_price else max(f - k, 0) + 50 if side == "C" else max(k - f, 0) + 50
                    v = vol_fn(day, i, t, side, off) if vol_fn else 1
                    rows.append((t, off, side, k, f, px, px + 1, px - 1, px, v, 100 + off, 20.0))
    df = pd.DataFrame(rows, columns=["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume",
                                     "oi", "iv"])
    if extra is not None:
        df = pd.concat([df, extra], ignore_index=True)
    return df


def flat_days(n, start="2026-01-01"):
    return [d for d in pd.bdate_range(start, periods=n)]


@pytest.fixture(scope="module")
def mkt():
    """20 weekday sessions; on the last one the future rises from 1000 to 1060 by 11:59 and keeps rising."""
    days = flat_days(20)
    last = days[-1]

    def fut(day, i, t):
        if day != last:
            return 1000.0 + 5 * np.sin(i / 30.0)            # ATR from a +-5 wiggle
        return 1000.0 + min(i, 179) / 179 * 60 + (1.0 * (i - 179) if i > 179 else 0)
    raw = make_raw(days, fut)
    m, counts = X.build_market("CRUDEOIL", raw)
    return m, days, counts


# ------------------------------------------------------------------------------------------------ cleaning
def test_foreign_mask():
    m = X.foreign_mask(np.array([100.0, 104.0, 96.0, np.nan]), np.array([100.0, 100.0, 100.0, 100.0]))
    assert m.tolist() == [False, True, True, True]


def test_spike_mask_reverting_and_sustained():
    day = np.array(["d"] * 6)
    side = np.array(["C"] * 6)
    k = np.full(6, 100.0)
    r = np.arange(6)
    assert X.spike_mask(day, side, k, r, [10, 10, 30, 10, 10, 10]).tolist() == [False, False, True, False, False, False]
    assert not X.spike_mask(day, side, k, r, [10, 10, 30, 30, 30, 30]).any()     # a genuine jump that stays
    assert not X.spike_mask(day, side, k, r, [30, 10, 10, 10, 10, 30]).any()     # first and last are never spikes


def test_ffill_day_never_crosses_days():
    a = np.array([1.0, np.nan, np.nan, 5.0])
    d = np.array([1, 1, 2, 2])
    out = X.ffill_day(a, d)
    assert out[1] == 1.0 and np.isnan(out[2]) and out[3] == 5.0


def test_build_market_drops_foreign_rows_and_bad_evening_print():
    days = flat_days(2)
    stray = pd.DataFrame([(pd.Timestamp(f"{days[0].date()} 12:00"), 0, "C", 1000.0, 2000.0, 9, 9, 9, 9, 1, 1, 20.0)],
                         columns=["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume",
                                  "oi", "iv"])

    def price(day, i, t, side, k, f):                      # a one-minute 5x print at 21:00 on day 0 (bad evening print)
        base = max(f - k, 0) + 50 if side == "C" else max(k - f, 0) + 50
        return base * 5 if (day == days[0] and t.hour == 21 and t.minute == 0 and side == "C" and k == 1000.0) else base
    raw = make_raw(days, lambda d, i, t: 1000.0, cp_price=price, extra=stray)
    m, counts = X.build_market("CRUDEOIL", raw)
    assert counts["foreign"] == 1
    assert counts["f1"] >= 1 or counts["spikes"] >= 1
    ii, V = m.leg(days[0], 0, 1000.0)
    bad = pd.Timestamp(f"{days[0].date()} 21:00")
    assert not any(pd.Timestamp(m.ts[i]) == bad for i in ii)  # the bad print is no price
    assert np.allclose(m.F, 1000.0)                           # the stray row never moved the proxy


# ------------------------------------------------------------------------------------------------ ATR14
def test_atr14_roll_day_uses_high_low_only():
    H = np.full(16, 110.0)
    L = np.full(16, 100.0)
    Cl = np.full(16, 105.0)
    seg = np.zeros(16, int)
    H[10], L[10], Cl[10] = 210.0, 200.0, 205.0        # a new contract 100 higher: no 100-point true range
    seg[10:] = 1
    a = X.atr14(H, L, Cl, seg)
    assert np.isnan(a[:14]).all()
    tr11 = max(110, 205) - min(100, 205)               # day 11 back to the old level: a real move inside the new seg
    assert a[14] == pytest.approx((13 * 10 + tr11) / 14)


def test_market_atr_and_expiry_fields(mkt):
    m, days, _ = mkt
    d = X.dkey(days[-1])
    assert np.isfinite(m.atr[d]) and m.atr[d] > 0
    assert np.isnan(m.atr[X.dkey(days[3])])            # warm-up: fewer than 14 sessions


def test_sessions_to_expiry_and_expiry_eve():
    """Regression (amendment 1): the expiry days must match the session keys, so dte and the eve flag are set."""
    days = flat_days(6)
    m, _ = X.build_market("CRUDEOIL", make_raw(days, lambda d, i, t: 1000.0))
    m.exp_days = np.array([np.datetime64(days[3], "ns")], dtype="datetime64[ns]")
    X._days(m)
    got = [m.dte[X.dkey(d)] for d in days]
    assert got[:4] == [3.0, 2.0, 1.0, 0.0] and np.isnan(got[4]) and np.isnan(got[5])
    assert m.eve_of_expiry[X.dkey(days[2])] and not m.eve_of_expiry[X.dkey(days[1])]
    assert X.exit_cap(m, X.dkey(days[2])) <= 23 * 60


# ------------------------------------------------------------------------------------------------ the Solo signal
def solo_day(move=60.0, n=900, first=0, gap_at=None):
    tod = np.arange(9 * 60 + first, 9 * 60 + first + n)
    F = 1000.0 + np.clip(np.arange(n) - 0, 0, 179) / 179 * move
    if gap_at is not None:
        keep = tod != gap_at
        tod, F = tod[keep], F[keep]
    return tod, F


def test_solo_signal_up_down_and_strength():
    tod, F = solo_day(60)
    s = X.solo_signal(tod, F, atr=100.0, D=12 * 60, min_minutes=100)
    assert s and s["side"] == 1 and s["strength"] == pytest.approx(0.6, abs=0.01) and tod[s["k"]] == 11 * 60 + 59
    tod, F = solo_day(-60)
    assert X.solo_signal(tod, F, atr=100.0, D=12 * 60, min_minutes=100)["side"] == -1


def test_solo_signal_refusals():
    tod, F = solo_day(30)
    assert X.solo_signal(tod, F, 100.0, 12 * 60, 100) is None                 # 0.3 ATR: too small
    tod, F = solo_day(60)
    assert X.solo_signal(tod, F, np.nan, 12 * 60, 100) is None                # no ATR
    tod, F = solo_day(60, first=10)
    assert X.solo_signal(tod, F, 100.0, 12 * 60, 100) is None                 # first minute after 09:05
    tod, F = solo_day(60, gap_at=11 * 60 + 59)
    assert X.solo_signal(tod, F, 100.0, 12 * 60, 100) is None                 # no 11:59 minute
    tod, F = solo_day(60)
    F = F.copy()
    F[(tod >= 10 * 60) & (tod < 11 * 60)] = 1100.0                           # the close is mid-range
    assert X.solo_signal(tod, F, 100.0, 12 * 60, 100) is None
    tod = np.arange(17 * 60, 23 * 60)                                         # holiday: evening session only
    assert X.solo_signal(tod, np.full(len(tod), 1000.0), 100.0, 12 * 60, 100) is None


def test_solo_signal_look_ahead_guard():
    tod, F = solo_day(60)
    a = X.solo_signal(tod, F, 100.0, 12 * 60, 100)
    F2 = F.copy()
    F2[tod >= 12 * 60] = 1.0                                                   # change everything from 12:00 on
    assert X.solo_signal(tod, F2, 100.0, 12 * 60, 100) == a


def test_range_break():
    tod = np.arange(17 * 60, 22 * 60 + 1)
    F = np.full(len(tod), 100.0)
    F[(tod >= 17 * 60) & (tod < 19 * 60)] = np.tile([99.0, 101.0], 60)
    F[tod == 19 * 60 + 30] = 102.0
    k, side, hi, lo = X.range_break(tod, F, 17 * 60, 19 * 60, 22 * 60, 60)
    assert tod[k] == 19 * 60 + 30 and side == 1 and (hi, lo) == (101.0, 99.0)
    assert X.range_break(tod[tod >= 18 * 60], F[tod >= 18 * 60], 17 * 60, 19 * 60, 22 * 60, 61) is None


# ------------------------------------------------------------------------------------------------ held contract and exits
def j_at(m, day, hh, mm):
    return m.at_or_before(X.dkey(day), hh * 60 + mm)


def test_held_contract_and_itm_strike(mkt):
    m, days, _ = mkt
    j = j_at(m, days[-1], 11, 59)
    t = X.sim(m, j, 1, "CRUDEOIL", exit_tod=14 * 60 + 30)
    k_itm = m.strike(0, -1, j)
    assert t["strike"] == k_itm and t["offset"] == -1
    # the held contract is priced to its exit even though the ATM strike moved on (the rolling series rolled)
    assert m.strike(0, 0, t["i_out"]) != m.strike(0, 0, j)
    ii, V = m.leg(m.day[j], 0, k_itm)
    q = np.searchsorted(ii, t["i_out"])
    assert t["rX"] == V[q, 3] and t["why"] == "time" and X.pydate(m.day[t["i_out"]]) == days[-1].date()
    assert m.tod[t["i_out"]] == 14 * 60 + 30


def test_atm_fallback_when_itm_has_no_print():
    days = flat_days(2)
    raw = make_raw(days, lambda d, i, t: 1000.0, vol_fn=lambda d, i, t, side, off: 0 if off == -1 else 1)
    m, _ = X.build_market("CRUDEOIL", raw)
    j = j_at(m, days[1], 11, 59)
    t = X.sim(m, j, 1, "CRUDEOIL", exit_tod=14 * 60 + 30)
    assert t["offset"] == 0
    raw = make_raw(days, lambda d, i, t: 1000.0, vol_fn=lambda d, i, t, side, off: 0)
    m, _ = X.build_market("CRUDEOIL", raw)
    assert X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30) is None   # nothing printed


def test_fill_window_is_four_minutes():
    days = flat_days(2)

    def vol(d, i, t, side, off):                      # nothing prints 12:00-12:03; prints from 12:04
        return 0 if (t.hour == 12 and t.minute < 4) else 1
    raw = make_raw(days, lambda d, i, t: 1000.0, vol_fn=vol)
    m, _ = X.build_market("CRUDEOIL", raw)
    assert X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30) is None

    def vol2(d, i, t, side, off):
        return 0 if (t.hour == 12 and t.minute < 3) else 1
    m, _ = X.build_market("CRUDEOIL", make_raw(days, lambda d, i, t: 1000.0, vol_fn=vol2))
    t = X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30)
    assert t is not None and m.tod[t["i_in"]] == 12 * 60 + 3


def test_future_stop_sells_next_minute_open():
    days = flat_days(2)

    def fut(d, i, t):
        return 1000.0 - (30.0 if (d == days[1] and t >= pd.Timestamp(f"{d.date()} 12:30")) else 0.0)
    m, _ = X.build_market("CRUDEOIL", make_raw(days, fut))
    j = j_at(m, days[1], 11, 59)
    t = X.sim(m, j, 1, "CRUDEOIL", exit_tod=14 * 60 + 30, entF=1000.0, stop_d=20.0)
    assert t["why"] == "fut_stop" and m.tod[t["i_out"]] == 12 * 60 + 31
    ii, V = m.leg(m.day[j], 0, t["strike"])
    assert t["rX"] == V[np.searchsorted(ii, t["i_out"]), 0]                   # the next minute's open


def test_breakeven_lock():
    days = flat_days(2)

    def fut(d, i, t):
        if d != days[1]:
            return 1000.0
        mins = t.hour * 60 + t.minute
        if 12 * 60 + 10 <= mins < 12 * 60 + 40:
            return 1050.0                         # +50 >= 0.45 ATR (45): the lock is earned
        if mins >= 12 * 60 + 40:
            return 999.0                          # back to the entry: out at breakeven
        return 1000.0
    m, _ = X.build_market("CRUDEOIL", make_raw(days, fut))
    t = X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30, entF=1000.0, stop_d=30.0, earn_d=45.0)
    assert t["why"] == "fut_lock" and m.tod[t["i_out"]] == 12 * 60 + 41
    t = X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30, entF=1000.0, stop_d=30.0, earn_d=60.0)
    assert t["why"] == "time"                     # never earned: no lock


def test_option_stop_gap_fills_at_open_and_step_lock():
    days = flat_days(2)

    def price(d, i, t, side, k, f):
        mins = t.hour * 60 + t.minute
        if d == days[1] and side == "C" and mins >= 12 * 60 + 20:
            return 10.0                            # gaps from ~150 to 10 (below a -30% stop)
        return max(f - k, 0) + 50 if side == "C" else max(k - f, 0) + 50
    m, _ = X.build_market("CRUDEOIL", make_raw(days, lambda d, i, t: 1000.0, cp_price=price))
    t = X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30, stop_pct=0.30)
    assert t["why"] == "opt_stop" and t["rX"] == pytest.approx(10.0)         # the open (worse than the level)

    def price2(d, i, t, side, k, f):
        mins = t.hour * 60 + t.minute
        base = max(f - k, 0) + 50 if side == "C" else max(k - f, 0) + 50
        if d == days[1] and side == "C":
            if 12 * 60 + 10 <= mins < 12 * 60 + 20:
                return base * 1.5                  # +50%: the +10% lock is earned
            if mins >= 12 * 60 + 20:
                return base * 1.0                  # back to the fill: out at +10%
        return base
    m, _ = X.build_market("CRUDEOIL", make_raw(days, lambda d, i, t: 1000.0, cp_price=price2))
    t = X.sim(m, j_at(m, days[1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30, stop_pct=0.30, steps=((0.40, 0.10),))
    assert t["why"] == "lock" and t["rX"] == pytest.approx(150.0)              # a gap through the lock: the open


def test_expiry_eve_cap_and_session_end():
    days = flat_days(2)
    m, _ = X.build_market("CRUDEOIL", make_raw(days, lambda d, i, t: 1000.0))
    d = X.dkey(days[1])
    assert m.session_end(d) == 23 * 60 + 31                                   # the data stop at 23:30
    m.eve_of_expiry[d] = True
    assert X.exit_cap(m, d) == min(23 * 60, 23 * 60 + 31 - 15)
    t = X.sim(m, j_at(m, days[1], 18, 0), 1, "CRUDEOIL", exit_tod=23 * 60 + 15)
    assert m.tod[t["i_out"]] <= 23 * 60
    m.eve_of_expiry[d] = False


def test_us_summer_and_winter_session_close():
    assert D53.session_close(pd.Timestamp("2026-07-01"), 23 * 60 + 59) == 23 * 60 + 30
    assert D53.session_close(pd.Timestamp("2026-01-14"), 23 * 60 + 59) == 23 * 60 + 55
    assert X.us_open_tod(pd.Timestamp("2026-07-01")) == 19 * 60 and X.us_open_tod(pd.Timestamp("2026-01-14")) == 20 * 60


def test_expiry_days_are_never_traded(mkt):
    m, days, _ = mkt
    d = X.dkey(days[-1])
    lo, hi = date(2026, 1, 1), date(2026, 1, 31)
    assert d in X.days_in(m, lo, hi)
    m.is_exp[m.day_first[d]] = True
    try:
        assert d not in X.days_in(m, lo, hi)
    finally:
        m.is_exp[m.day_first[d]] = False


def test_costs_and_thin_spread(mkt):
    m, days, _ = mkt
    t = X.sim(m, j_at(m, days[-1], 11, 59), 1, "CRUDEOIL", exit_tod=14 * 60 + 30)
    buy, sell = t["rE"] * 100, t["rX"] * 100
    from research.hunt.r53 import common as C53
    ch = C53.mcx_opt_charges(buy, sell)
    hs = C53.opt_half_spread("crude", 12 * 60) * buy + C53.opt_half_spread("crude", 14 * 60 + 30) * sell
    assert t["net"] == pytest.approx(sell - buy - ch - hs)
    assert t["net2"] == pytest.approx(sell - buy - 2 * ch - 2 * hs)
    assert X.half_spread("crude", 12 * 60, True) == 2 * X.half_spread("crude", 12 * 60, False)
    assert X.half_spread("crude", 18 * 60, False) == X.half_spread("crude", 12 * 60, False) / 2


def test_sale_fallbacks():
    days = flat_days(2)

    def vol(d, i, t, side, off):                  # no print 13:00-13:20
        return 0 if (t.hour == 13 and t.minute <= 20) else 1
    m, _ = X.build_market("CRUDEOIL", make_raw(days, lambda d, i, t: 1000.0, vol_fn=vol))
    j = j_at(m, days[1], 11, 59)
    k = m.strike(0, -1, j)
    ii, V = m.leg(m.day[j], 0, k)
    px, t, stale = X.sale(m, ii, V, j_at(m, days[1], 13, 15), use_open=True)
    assert m.tod[t] == 13 * 60 + 21 and not stale                              # the next print within 10 minutes
    px, t, stale = X.sale(m, ii, V, j_at(m, days[1], 13, 5), use_open=False)
    assert stale and px == V[np.searchsorted(ii, j_at(m, days[1], 13, 5)) - 1, 3]   # last printed close, stale


# ------------------------------------------------------------------------------------------------ features
def test_features_look_ahead_guard_every_feature():
    tod = np.arange(9 * 60, 20 * 60)
    rng = np.random.default_rng(1)
    F = 1000 + np.cumsum(rng.normal(0, 1, len(tod)))
    k = int(np.flatnonzero(tod == 14 * 60)[0])
    a = X.features_at(tod, F, k, 20.0, 1, pd.Timestamp("2026-01-14"))
    F2 = F.copy()
    F2[k + 1:] = rng.normal(0, 1000, len(F2) - k - 1)
    b = X.features_at(tod, F2, k, 20.0, 1, pd.Timestamp("2026-01-14"))
    assert set(a) == set(X.FEATS) - {"dte", "iv_atm", "pcr_oi"}
    for n in a:
        assert (np.isnan(a[n]) and np.isnan(b[n])) or a[n] == b[n], n
    c = X.features_at(tod, F, k, 20.0, -1, pd.Timestamp("2026-01-14"))
    for n in X.SIGNED:
        assert c[n] == pytest.approx(-a[n])


def test_context_is_point_in_time(mkt):
    m, days, _ = mkt
    assert m.iv_atm.dtype == np.float32 and np.nanmax(m.iv_atm) == pytest.approx(20.0)
    assert np.isfinite(m.pcr).any()


# ------------------------------------------------------------------------------------------------ guard, twins, stats
def test_period_guard():
    with pytest.raises(PermissionError):
        C.check_range(date(2025, 8, 1), date(2025, 9, 1), mcx=True)             # before the MCX minutes
    with pytest.raises(PermissionError):
        C.check_range(date(2024, 11, 1), date(2025, 1, 1))                    # before 20 Nov 2024
    with pytest.raises(PermissionError):
        C.check_range(date(2026, 3, 1), date(2026, 4, 2))                     # the final test, unopened
    C.check_range(date(2025, 8, 5), date(2026, 3, 31), mcx=True)
    assert C.period_of(date(2026, 4, 1)) == "final" and C.period_of(date(2024, 11, 19)) is None
    assert C.period_of(date(2025, 8, 4), mcx=True) is None


def test_twin_uses_same_day_and_redraws(mkt, monkeypatch):
    m, days, _ = mkt
    monkeypatch.setitem(X._M, "CRUDEOIL", m)
    t = X.cand_solo(m, "CRUDEOIL", X.dkey(days[-1]), "SN")
    assert t is not None and t["setup"] == "SN" and t["side"] == 1
    rng = np.random.default_rng(0)
    nets = [X.twin_one(m, "CRUDEOIL", t, rng) for _ in range(20)]
    assert all(np.isfinite(nets)) and len(set(np.round(nets, 6))) > 1
    tot = X.twin_totals([t], 10, 60)
    assert tot.shape == (10,)


def test_candidates_mo_needs_dte_window(mkt, monkeypatch):
    m, days, _ = mkt
    d = X.dkey(days[-1])
    old = m.dte.get(d)
    m.dte[d] = 5.0
    assert X.cand_mo(m, "SILVERM", d) is None
    m.dte[d] = 15.0
    assert X.cand_mo(m, "CRUDEOIL", d) is None                                  # SILVERM only
    m.dte[d] = old


def test_no_data_market():
    m = D53.Market("GOLD", np.array([], dtype="datetime64[ns]"), np.array([]), None)
    assert len(m.days) == 0 and m.leg(DAY, 0, 1000.0) is None


def test_study_gates_bh_stats():
    pytest.importorskip("numba")
    pytest.importorskip("sklearn")
    import r60study as T
    q = T.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    good = dict(net=1000, trades=50, ex_top5=10)
    assert T.gate(good, 0.01, 0.05, 10) == "PASS"
    assert "fewer than 40" in T.gate(dict(good, trades=39), 0.01, 0.05, 10)
    assert "top-5" in T.gate(dict(good, ex_top5=0), 0.01, 0.05, 10)
    assert "design" in T.gate(good, 0.01, 0.05, -1)
    assert "q > 0.10" in T.gate(good, 0.01, 0.2, 10)
    assert T.stats([])["trades"] == 0
    tr = [dict(day=date(2026, 4, 1), net=100.0, net2=50.0, und="GOLD", setup="SN", rE=10.0, qty=100.0),
          dict(day=date(2026, 4, 2), net=-40.0, net2=-60.0, und="GOLD", setup="SN", rE=12.0, qty=100.0)]
    s = T.stats(tr, sessions=10)
    assert s["net"] == 60 and s["net_2x"] == -10 and s["max_dd"] == 40 and s["trades_per_day"] == 0.2
    assert s["capital_median"] == 1100
    assert T.p_value(5.0, np.array([1.0, 6.0, 7.0])) == pytest.approx(0.75)


def test_pooled_rules_strongest_first_ties_az():
    pytest.importorskip("numba")
    pytest.importorskip("sklearn")
    import r60study as T
    d = date(2026, 4, 1)
    c = [dict(setup="SN", day=d, und="SILVER", strength=1.0, entry_ts=1, net=1),
         dict(setup="SN", day=d, und="GOLD", strength=1.0, entry_ts=1, net=2),
         dict(setup="SN", day=d, und="CRUDEOIL", strength=0.9, entry_ts=1, net=3)]
    out = T.mcx_rules(c, d, d)
    assert [t["und"] for t in out["X13"]] == ["GOLD"] and [t["und"] for t in out["X5"]] == ["SILVER"]
    assert out["X14"] == []
