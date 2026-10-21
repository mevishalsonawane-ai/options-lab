"""R94 idea 5 tests (PREREG section 8): u, g, e; the 09:06 entry, 14:00 exit and the stop; skip rules; sd from the
previous 40 sessions only; the look-ahead guard; the Dukascopy decoder. Hand-made data only (no lake, no network)."""
from __future__ import annotations

import datetime as dt
import struct

import numpy as np
import pandas as pd
import pytest

from research.hunt.r94_mcx_advanced import common as C
from research.hunt.r94_mcx_advanced import fetch_duka as FD
from research.hunt.r94_mcx_advanced import usgap as G


def mcx_day(d, prices: dict, base=100.0):
    """A session 09:00-23:29 at `base`, with chosen minute closes overridden {minute_of_day: price}."""
    ix = pd.date_range(pd.Timestamp(d) + pd.Timedelta(hours=9), pd.Timestamp(d) + pd.Timedelta(hours=23, minutes=29),
                       freq="1min")
    v = np.full(len(ix), base)
    tod = ix.hour * 60 + ix.minute
    for m, p in prices.items():
        v[tod == m] = p
    return pd.Series(v, index=ix)


def us_flat(start, end, price=50.0):
    ix = pd.date_range(start, end, freq="1min")
    return pd.Series(np.full(len(ix), price), index=ix)


# ------------------------------------------------------------------------------------------------ u, g, e
def test_u_g_e_by_hand():
    d0, d1 = pd.Timestamp("2025-09-01"), pd.Timestamp("2025-09-02")
    F = pd.concat([mcx_day(d0, {}, 100.0), mcx_day(d1, {9 * 60 + 5: 101.0, 9 * 60 + 6: 101.0}, 101.0)])
    U = us_flat(d0, d1 + pd.Timedelta(hours=10), 50.0)
    U[U.index >= d1 + pd.Timedelta(hours=8)] = 52.0
    tab = G.day_table(F, U)
    r = tab.loc[d1]
    assert r.skip is None or pd.isna(r.skip)
    assert r.prev == 100.0
    assert r.u == pytest.approx(np.log(52 / 50))
    assert r.g == pytest.approx(np.log(101 / 100))
    assert r.e == pytest.approx(np.log(52 / 50) - np.log(1.01))
    assert tab.loc[d0].skip == "no_prev"


def test_skip_rules():
    d0, d1, d2 = (pd.Timestamp(x) for x in ("2025-09-01", "2025-09-02", "2025-09-03"))
    day1 = mcx_day(d1, {})
    day1 = day1[(day1.index.hour * 60 + day1.index.minute) != 9 * 60 + 5]          # no 09:05 minute
    F = pd.concat([mcx_day(d0, {}), day1, mcx_day(d2, {})])
    U = us_flat(d0, d2 + pd.Timedelta(hours=8, minutes=50))                       # stale by more than 10 min at 09:04
    tab = G.day_table(F, U)
    assert tab.loc[d1].skip == "no_mcx_0905_0906"
    assert tab.loc[d2].skip == "no_us_minute"


def test_price_at_staleness():
    s = pd.Series([1.0, 2.0], index=pd.to_datetime(["2025-01-01 09:00", "2025-01-01 09:03"]))
    assert G.price_at(s, pd.Timestamp("2025-01-01 09:04")) == 2.0
    assert np.isnan(G.price_at(s, pd.Timestamp("2025-01-01 09:14")))
    assert np.isnan(G.price_at(s, pd.Timestamp("2025-01-01 08:59")))


# ------------------------------------------------------------------------------------------------ signals
def test_sd_uses_previous_40_only():
    n = 60
    days = pd.date_range("2025-08-01", periods=n, freq="D")
    rng = np.random.default_rng(1)
    e = rng.normal(0, 0.01, n)
    tab = pd.DataFrame({"e": e, "u": e, "skip": [None] * n}, index=days)
    s = G.signals(tab, "G1")
    assert (s.iloc[:40] == 0).all()                                    # not warm
    for k in range(40, n):
        sd = np.std(e[k - 40:k], ddof=1)
        want = int(np.sign(e[k])) if abs(e[k]) >= sd else 0
        assert s.iloc[k] == want
    # changing today's value does not change today's sd: a huge e today still compares with the past 40
    tab2 = tab.copy()
    tab2.iloc[50, 0] = 1.0
    assert G.signals(tab2, "G1").iloc[50] == 1
    assert (G.signals(tab2, "G1").iloc[:50] == s.iloc[:50]).all()


def test_signals_skip_rows_excluded_from_sd():
    n = 50
    days = pd.date_range("2025-08-01", periods=n, freq="D")
    e = np.r_[np.full(41, 0.001) * np.where(np.arange(41) % 2, 1, -1), np.full(9, 0.5)]
    skip = [None] * n
    skip[41] = "no_us_minute"
    tab = pd.DataFrame({"e": e, "u": e, "skip": skip}, index=days)
    s = G.signals(tab, "G1")
    assert s.iloc[41] == 0                                              # a skipped day never trades
    assert s.iloc[42] == 1


# ------------------------------------------------------------------------------------------------ one trade
def test_entry_0906_exit_1400_and_costs():
    d = pd.Timestamp("2025-10-01")
    F = mcx_day(d, {9 * 60 + 6: 100.0}, 100.0)
    F[(F.index.hour * 60 + F.index.minute) > 9 * 60 + 6] = 103.0
    t = G.simulate(F, +1, atr=20.0, mini="NATGASMINI", units=250.0)
    assert t["entry_ts"] == d + pd.Timedelta(hours=9, minutes=6)
    assert t["exit_ts"] == d + pd.Timedelta(hours=14)
    assert t["why"] == "time"
    hs = C.fut_half_spread("NATGASMINI", 100.0)
    assert hs == pytest.approx(0.05)
    fin, fout = 100.0 + 0.05, 103.0 - 0.05
    want = (fout - fin) * 250 - C.fut_leg_charges(fin * 250, fout * 250)
    assert t["net"] == pytest.approx(want)
    assert t["gross"] == pytest.approx(3.0 * 250)


def test_stop_hits_on_close_and_fills_there():
    d = pd.Timestamp("2025-10-01")
    F = mcx_day(d, {9 * 60 + 6: 100.0, 10 * 60: 94.0, 10 * 60 + 1: 99.0}, 100.0)      # stop at 100 - 0.5 x 10 = 95
    t = G.simulate(F, +1, atr=10.0, mini="GOLDM", units=10.0)
    assert t["why"] == "stop" and t["exit_px"] == 94.0
    assert t["exit_ts"] == d + pd.Timedelta(hours=10)
    s = G.simulate(F, -1, atr=10.0, mini="GOLDM", units=10.0)                         # short: stop at 105, never hit
    assert s["why"] == "time"


def test_stop_ignores_minutes_after_1400():
    d = pd.Timestamp("2025-10-01")
    F = mcx_day(d, {9 * 60 + 6: 100.0, 15 * 60: 50.0}, 100.0)
    t = G.simulate(F, +1, atr=10.0, mini="GOLDM", units=10.0)
    assert t["why"] == "time" and t["exit_px"] == 100.0


def test_no_trade_without_0906_or_atr():
    d = pd.Timestamp("2025-10-01")
    F = mcx_day(d, {})
    assert G.simulate(F[(F.index.hour * 60 + F.index.minute) != 9 * 60 + 6], 1, 10.0, "GOLDM", 10.0) is None
    assert G.simulate(F, 1, float("nan"), "GOLDM", 10.0) is None


def test_atr_before_uses_earlier_rows_and_roll_rule():
    n = 20
    d = pd.DataFrame({"date": pd.date_range("2025-01-01", periods=n), "high": np.full(n, 110.0),
                      "low": np.full(n, 100.0), "close": np.full(n, 105.0), "oi": np.full(n, 100.0)})
    a = G.atr_before(d)
    assert np.isnan(a.iloc[13]) and a.iloc[14] == pytest.approx(10.0)
    d.loc[16, ["high", "low", "close"]] = [210.0, 200.0, 205.0]          # a jump on a roll row: TR = high - low
    d.loc[16, "oi"] = 1000.0
    a2 = G.atr_before(d)
    assert a2.iloc[16] == pytest.approx(10.0)                            # today's row is not used for today
    assert a2.iloc[17] == pytest.approx(10.0)                            # the roll row's TR is 10, not 105


def test_twins_choose_a_side_per_trade():
    trades = [{"net": 10.0}, {"net": -5.0}]
    tw = G.twins(trades, [-12.0, 4.0], n=2000)
    assert set(np.unique(tw)) <= {5.0, 14.0, -17.0, -8.0}
    assert len(np.unique(tw)) == 4


# ------------------------------------------------------------------------------------------------ look-ahead guard
def synth(n_days=70, seed=3):
    rng = np.random.default_rng(seed)
    days = pd.bdate_range("2025-08-04", periods=n_days)
    parts, lvl = [], 100.0
    for d in days:
        ix = pd.date_range(d + pd.Timedelta(hours=9), d + pd.Timedelta(hours=23, minutes=29), freq="1min")
        x = lvl * np.exp(np.cumsum(rng.normal(0, 0.0005, len(ix))))
        lvl = x[-1] * np.exp(rng.normal(0, 0.004))
        parts.append(pd.Series(x, index=ix))
    F = pd.concat(parts)
    U = us_flat(days[0], days[-1] + pd.Timedelta(hours=23, minutes=59), 1.0)
    U = pd.Series(np.exp(np.cumsum(rng.normal(0, 0.0004, len(U)))), index=U.index)
    return F, U, days


@pytest.mark.parametrize("seed", [3, 7, 11])
@pytest.mark.parametrize("rule", G.RULES)
def test_look_ahead_guard(seed, rule):
    F, U, days = synth(seed=seed)
    full = G.signals(G.day_table(F, U), rule)
    assert (full != 0).sum() >= 3                                         # not vacuous
    rng = np.random.default_rng(seed)
    for cut_day in rng.choice(days[45:-2], 4, replace=False):
        cut = pd.Timestamp(cut_day) + pd.Timedelta(hours=9, minutes=int(rng.integers(10, 800)))
        Ft, Ut = F[F.index < cut], U[U.index < cut]
        trunc = G.signals(G.day_table(Ft, Ut), rule)
        before = full.index[full.index < pd.Timestamp(cut_day)]
        assert (trunc.reindex(before) == full.reindex(before)).all()
        Fp, Up = F.copy(), U.copy()
        Fp[Fp.index >= cut] *= np.exp(rng.normal(0, 0.05, (Fp.index >= cut).sum()))
        Up[Up.index >= cut] *= 1.3
        pert = G.signals(G.day_table(Fp, Up), rule)
        assert (pert.reindex(before) == full.reindex(before)).all()
        # trades before the cut day are identical too
        for d in before[-5:]:
            if full[d] != 0:
                a = G.simulate(F[F.index.normalize() == d], int(full[d]), 1.0, "GOLDM", 10.0)
                b = G.simulate(Fp[Fp.index.normalize() == d], int(pert[d]), 1.0, "GOLDM", 10.0)
                assert a["net"] == b["net"]


# ------------------------------------------------------------------------------------------------ decoder
def test_decode_records():
    day = dt.date(2026, 9, 15)
    raw = struct.pack(">IIIIIf", 60, 4297428, 4296725, 4295705, 4298405, 0.5)
    raw += struct.pack(">IIIIIf", 120, 1, 1, 1, 1, 0.0)                 # zero volume: dropped
    raw += b"\x00" * 7                                                   # a torn tail is ignored
    rows = FD.decode(raw, day, 1000.0)
    assert len(rows) == 1
    t, o, h, l, c, v = rows[0]
    assert t == int(dt.datetime(2026, 9, 15, 0, 1, tzinfo=dt.timezone.utc).timestamp())
    assert (o, h, l, c) == (4297.428, 4298.405, 4295.705, 4296.725)
    assert h >= max(o, c) and l <= min(o, c)


def test_gas_point():
    assert FD.POINT["GASCMDUSD"] == 10000.0 and FD.POINT["XAUUSD"] == 1000.0
