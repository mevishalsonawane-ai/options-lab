"""Tests for fam_swingvol.py (R52 swing / long-vol family), on tiny synthetic markets - no lake needed.

Each signal function: one market that must fire exactly the expected (side, minute), one that must not, a look-ahead
guard (scrambling every bar after a signal's minute, that day and later, must not change that signal) and edge cases
(empty data, a missing minute, expiry days). Run: python -m pytest -q test_fam_swingvol.py
"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fam_swingvol as F  # noqa: E402

W = 375
OPEN = 9 * 60 + 15


def col(hhmm):
    return (hhmm // 100) * 60 + hhmm % 100 - OPEN


# ------------------------------------------------------------------------------------------------ fake market
class FakeChain:
    def __init__(self, K, c, oi=None, series="WEEK"):
        self.K = np.asarray(K)
        self.c = c
        self.oi = oi if oi is not None else {r: np.full((len(K), W), np.nan) for r in "CP"}
        self.series = series

    def kpos(self, k):
        i = int(np.searchsorted(self.K, k))
        return i if i < len(self.K) and self.K[i] == k else -1


class FakeOpts:
    def __init__(self, chains):
        self.chains = chains

    def chain(self, d, series="near"):
        return self.chains.get(d)


class FakeIx:
    def __init__(self, days, M, exp=(), real=None):
        self.days = list(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        self.d = {d: dict(exp=d in set(exp), real=True if real is None else bool(real[i]), lot=30)
                  for i, d in enumerate(self.days)}
        self._M = M

    def mat(self):
        return self._M

    def daily(self):
        rows = []
        for i, d in enumerate(self.days):
            o, h, l, c = (self._M[k][i] for k in "ohlc")
            ok = ~np.isnan(c)
            rows.append((d, o[~np.isnan(o)][0] if ok.any() else np.nan, np.nanmax(h) if ok.any() else np.nan,
                         np.nanmin(l) if ok.any() else np.nan, c[ok][-1] if ok.any() else np.nan, 30,
                         self.d[d]["exp"], self.d[d]["real"]))
        return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close", "lot", "exp", "real"]).set_index("day")


class FakeMk:
    def __init__(self, ixs, opts=None):
        self.ixs, self.opts = ixs, opts or {}

    def index(self, u):
        return self.ixs[u]

    def options(self, u):
        return self.opts.get(u, FakeOpts({}))


def bdays(n, start="2025-01-01"):
    return list(pd.bdate_range(start, periods=n).date)


def flat_mat(levels):
    """(n, W) o/h/l/c, every bar of day i at levels[i]."""
    n = len(levels)
    a = np.repeat(np.asarray(levels, float)[:, None], W, axis=1)
    return {k: a.copy() for k in "ohlc"}


def set_bar(M, i, c, v):
    for k in "ohlc":
        M[k][i, c] = v


def keyrows(df):
    if not len(df):
        return set()
    return {(r.und, r.day, int(r.sig_min), int(r.side)) for r in df.itertuples()}


def scramble(M, i, c, seed=0):
    """Copy of M with every bar after column c of day i, and every bar of later days, scrambled."""
    rng = np.random.default_rng(seed)
    out = {k: v.copy() for k, v in M.items()}
    for k in "ohlc":
        f = rng.uniform(0.7, 1.3, out[k].shape)
        out[k][i, c + 1:] *= f[i, c + 1:]
        out[k][i + 1:] *= f[i + 1:]
    return out


def scramble_chain(ch, c, seed=1):
    rng = np.random.default_rng(seed)
    cc = {r: ch.c[r].copy() for r in "CP"}
    oi = {r: ch.oi[r].copy() for r in "CP"}
    for r in "CP":
        cc[r][:, c + 1:] *= rng.uniform(0.5, 1.5, cc[r][:, c + 1:].shape)
        oi[r][:, c + 1:] *= rng.uniform(0.5, 1.5, oi[r][:, c + 1:].shape)
    return FakeChain(ch.K, cc, oi, ch.series)


def lookahead_ok(fn, build, params, sig, chain_unds=()):
    """build(M_override=None, chains_override=None) -> mk. Scramble after sig and compare."""
    und, d, sm, side = sig
    mk0 = build()
    base = keyrows(fn(mk0, **params))
    assert sig in base
    ix = mk0.index(und)
    i = ix.pos[d]
    M2 = scramble(ix.mat(), i, sm - OPEN)
    ch2 = None
    if und in chain_unds:
        chains = dict(mk0.options(und).chains)
        for dd, ch in chains.items():
            if dd > d:
                chains[dd] = scramble_chain(ch, -1)
            elif dd == d:
                chains[dd] = scramble_chain(ch, sm - OPEN)
        ch2 = chains
    mk1 = build(M2, ch2)
    after = keyrows(fn(mk1, **params))
    assert sig in after
    before0 = {r for r in base if r[1] < d}
    before1 = {r for r in after if r[1] < d}
    assert before0 == before1


# ------------------------------------------------------------------------------------------------ SWING
def swing_market(n=12, drop_day=7, exp_days=None, gap=False):
    days = bdays(n)
    M = flat_mat([50000.0] * n)
    c = col(1514)
    set_bar(M, drop_day, c, 49500.0)          # the 15:14 close dips below the close 5 days ago, then recovers
    if gap:
        for k in "ohlc":
            M[k][drop_day, c] = np.nan
        set_bar(M, drop_day, c - 1, 49500.0)
    exp = exp_days if exp_days is not None else [days[-1]]
    return days, M, exp


def test_swing_rev5_fires_once_and_not_on_flat():
    days, M, exp = swing_market()
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, exp)})
    s = F.swing_signals(mk, rule="rev5", hold=1)
    assert keyrows(s) == {("BANKNIFTY", days[7], 15 * 60 + 14, 1)}
    r = s.iloc[0]
    assert r.exit_day == days[8] and r.exit_min == 15 * 60 + 15
    flat = FakeMk({"BANKNIFTY": FakeIx(days, flat_mat([50000.0] * 12), exp)})
    assert len(F.swing_signals(flat, rule="rev5", hold=1)) == 0


def test_swing_hold_cut_before_expiry_and_expiry_days_skipped():
    days, M, _ = swing_market()
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[10]])})
    s = F.swing_signals(mk, rule="rev5", hold=5)
    assert s.iloc[0].exit_day == days[9]                       # session before the contract's expiry
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[7], days[11]])})
    assert len(F.swing_signals(mk, rule="rev5", hold=1)) == 0  # signal on the expiry day: next contract not in lake
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[8], days[11]])})
    assert len(F.swing_signals(mk, rule="rev5", hold=1)) == 0  # session before expiry: a 15-minute trade originally


def test_swing_gap_and_empty():
    days, M, exp = swing_market(gap=True)
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, exp)})
    assert keyrows(F.swing_signals(mk, rule="rev5", hold=1)) == {("BANKNIFTY", days[7], 15 * 60 + 14, 1)}
    empty = FakeMk({"BANKNIFTY": FakeIx([], {k: np.zeros((0, W)) for k in "ohlc"})})
    s = F.swing_signals(empty)
    assert len(s) == 0 and list(s.columns) == F.MDCOLS


def test_swing_lookahead():
    days, M, exp = swing_market()

    def build(M2=None, ch=None):
        return FakeMk({"BANKNIFTY": FakeIx(days, M if M2 is None else M2, exp)})
    lookahead_ok(F.swing_signals, build, dict(rule="rev5", hold=1), ("BANKNIFTY", days[7], 15 * 60 + 14, 1))


def test_swing_gapdown_rule():
    days = bdays(10)
    M = flat_mat([50000.0] * 10)
    M["o"][6, 0] = 49700.0                       # opens 0.6% below yesterday's close
    for k in "hlc":
        M[k][6, 0] = 49700.0
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[-1]])})
    s = F.swing_signals(mk, rule="gapdown", hold=1)
    assert keyrows(s) == {("BANKNIFTY", days[6], 15 * 60 + 14, 1)}


# ------------------------------------------------------------------------------------------------ SWING_DEEP
def bigdown_market(n=60, drop=45, exp=None):
    rng = np.random.default_rng(3)
    lv = 20000 * np.cumprod(1 + rng.uniform(0.0005, 0.0025, n))
    lv[drop:] *= 0.96                            # a -4% day on `drop`
    days = bdays(n)
    return days, flat_mat(lv), (exp if exp is not None else [days[-1]])


def test_sdeep_bigdown_fires_next_morning():
    days, M, exp = bigdown_market()
    mk = FakeMk({"NIFTY": FakeIx(days, M, exp)})
    s = F.sdeep_signals(mk, rule="bigdown", ex="10d", unds=("NIFTY",))
    assert keyrows(s) == {("NIFTY", days[46], 9 * 60 + 19, 1)}
    assert s.iloc[0].exit_day == days[56] and s.iloc[0].exit_min == 9 * 60 + 20
    days2, M2, exp2 = bigdown_market(drop=10**6)
    assert len(F.sdeep_signals(FakeMk({"NIFTY": FakeIx(days2, M2, exp2)}), rule="bigdown", unds=("NIFTY",))) == 0


def test_sdeep_cap_at_monthly_expiry_and_skip_entry_on_expiry():
    days, M, _ = bigdown_market(n=70)              # days[50] is the March monthly expiry, days[-1] is in April
    mk = FakeMk({"NIFTY": FakeIx(days, M, [days[50], days[-1]])})
    r = F.sdeep_signals(mk, rule="bigdown", ex="10d", unds=("NIFTY",)).iloc[0]
    assert r.exit_day == days[50] and r.exit_min == F.EXP_SQ
    mk = FakeMk({"NIFTY": FakeIx(days, M, [days[46], days[-1]])})     # entry day = the monthly expiry day
    assert len(F.sdeep_signals(mk, rule="bigdown", ex="10d", unds=("NIFTY",))) == 0


def test_sdeep_lookahead_and_empty():
    days, M, exp = bigdown_market()

    def build(M2=None, ch=None):
        return FakeMk({"NIFTY": FakeIx(days, M if M2 is None else M2, exp)})
    lookahead_ok(F.sdeep_signals, build, dict(rule="bigdown", ex="chand", unds=("NIFTY",)),
                 ("NIFTY", days[46], 9 * 60 + 19, 1))
    empty = FakeMk({"NIFTY": FakeIx([], {k: np.zeros((0, W)) for k in "ohlc"})})
    assert len(F.sdeep_signals(empty, unds=("NIFTY",))) == 0


# ------------------------------------------------------------------------------------------------ SWING_DEEP-6 (NR7)
def nr7_market(n=40, cross=True, exp=None):
    days = bdays(n)
    lv = np.full(n, 20000.0)
    M = flat_mat(lv)
    for i in range(n):                           # wide days, each a little wider than the last (no NR7 / inside) ...
        M["h"][i] += 200 + 3 * i
        M["l"][i] -= 200 + 3 * i
    M["h"][30] = 20050.0                         # ... then a narrow inside day (NR7)
    M["l"][30] = 19950.0
    if cross:
        M["h"][31] = 20000.0
        M["l"][31] = 19990.0
        M["h"][31, col(1000):] = 20100.0         # trades through 20050 at 10:00
        M["c"][31, col(1000):] = 20090.0
    else:
        M["h"][31] = 20010.0
        M["l"][31] = 19990.0
    return days, M, (exp if exp is not None else [days[-1]])


def test_nr7_stop_entry_side_and_minute():
    days, M, exp = nr7_market()
    mk = FakeMk({"NIFTY": FakeIx(days, M, exp)})
    s = F.nr7_signals(mk, ex="5d", unds=("NIFTY",))
    assert ("NIFTY", days[31], 10 * 60, 1) in keyrows(s)
    r = s[s.day == days[31]].iloc[0]
    assert r.exit_day == days[36] and r.exit_min == 9 * 60 + 20
    days, M, exp = nr7_market(cross=False)
    s = F.nr7_signals(FakeMk({"NIFTY": FakeIx(days, M, exp)}), ex="5d", unds=("NIFTY",))
    assert days[31] not in set(s.day)


def test_nr7_lookahead_and_expiry_cap():
    days, M, exp = nr7_market()

    def build(M2=None, ch=None):
        return FakeMk({"NIFTY": FakeIx(days, M if M2 is None else M2, exp)})
    lookahead_ok(F.nr7_signals, build, dict(ex="atr10", unds=("NIFTY",)), ("NIFTY", days[31], 10 * 60, 1))
    days, M, _ = nr7_market()
    mk = FakeMk({"NIFTY": FakeIx(days, M, [days[33]])})          # the month's (only) expiry, inside the hold
    r = F.nr7_signals(mk, ex="20d", unds=("NIFTY",))
    r = r[r.day == days[31]].iloc[0]
    assert r.exit_day == days[33] and r.exit_min == F.EXP_SQ


def test_nr7_atr_stop_exit_minute():
    days, M, exp = nr7_market()
    M["l"][33, col(1100)] = 15000.0              # a crash two days later: the 2 x ATR stop hits at 11:00
    mk = FakeMk({"NIFTY": FakeIx(days, M, exp)})
    r = F.nr7_signals(mk, ex="atr10", unds=("NIFTY",))
    r = r[r.day == days[31]].iloc[0]
    assert r.exit_day == days[33] and r.exit_min == 11 * 60 + 1


# ------------------------------------------------------------------------------------------------ LV-2
def lv2_market(n=14, prem=24.0, exp=None):
    days = bdays(n)
    M = flat_mat([50000.0] * n)
    for i in range(n):
        M["c"][i, F.LV2_CUT] = 50010.0           # |15:10 close - 09:20 open| = 10 every day
    K = np.arange(49000, 51001, 100)
    chains = {}
    for d in days:
        cc = {r: np.full((len(K), W), prem) for r in "CP"}
        chains[d] = FakeChain(K, cc)
    return days, M, chains, (exp if exp is not None else [days[-1]])


def test_lv2_fires_in_band_only():
    days, M, ch, exp = lv2_market()
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, exp)}, {"BANKNIFTY": FakeOpts(ch)})
    s = F.lv2_signals(mk)                        # cost = 24 + 24 + 1 = 49 -> 4.9 in (4.44, 6.25]
    assert keyrows(s) == {("BANKNIFTY", d, 9 * 60 + 19, 0) for d in days[10:13]}   # last day = expiry, skipped
    days, M, ch, exp = lv2_market(prem=100.0)
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, exp)}, {"BANKNIFTY": FakeOpts(ch)})
    assert len(F.lv2_signals(mk)) == 0


def test_lv2_missing_option_minute_and_lookahead():
    days, M, ch, exp = lv2_market()
    for r in "CP":
        ch[days[11]].c[r][:, 4] = np.nan         # no option bar at 09:19: the 09:18 close is used
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, exp)}, {"BANKNIFTY": FakeOpts(ch)})
    assert ("BANKNIFTY", days[11], 9 * 60 + 19, 0) in keyrows(F.lv2_signals(mk))

    def build(M2=None, ch2=None):
        return FakeMk({"BANKNIFTY": FakeIx(days, M if M2 is None else M2, exp)},
                      {"BANKNIFTY": FakeOpts(ch if ch2 is None else ch2)})
    lookahead_ok(F.lv2_signals, build, {}, ("BANKNIFTY", days[10], 9 * 60 + 19, 0), chain_unds=("BANKNIFTY",))


# ------------------------------------------------------------------------------------------------ LV-4 / LV-5 / BS-1
def test_lv4_overnight_skips_expiry_nights():
    days = bdays(6)
    M = flat_mat([50000.0] * 6)
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[3]])})
    s = F.lv4_signals(mk)
    # day 2 -> 3 runs into the expiry day, day 3 is the expiry day, day 4 / 5 have no later expiry in the data
    assert keyrows(s) == {("BANKNIFTY", days[i], 15 * 60 + 14, 0) for i in (0, 1)}
    assert list(s.exit_day) == [days[1], days[2]] and set(s.exit_min) == {9 * 60 + 21}
    empty = FakeMk({"BANKNIFTY": FakeIx([], {k: np.zeros((0, W)) for k in "ohlc"})})
    assert len(F.lv4_signals(empty)) == 0


def test_lv4_lookahead():
    days = bdays(6)
    M = flat_mat([50000.0] * 6)

    def build(M2=None, ch=None):
        return FakeMk({"BANKNIFTY": FakeIx(days, M if M2 is None else M2, [days[5]])})
    lookahead_ok(F.lv4_signals, build, {}, ("BANKNIFTY", days[2], 15 * 60 + 14, 0))


def test_lv5_event_rows():
    days = bdays(10)
    M = flat_mat([50000.0] * 10)
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[9]])})
    ev = [(days[5], "RBI"), (date(2030, 1, 1), "Budget")]
    s = F.lv5_signals(mk, back=1, events=ev)
    assert keyrows(s) == {("BANKNIFTY", days[4], 15 * 60 + 14, 0)}
    assert s.iloc[0].exit_day == days[5] and s.iloc[0].exit_min == 15 * 60 + 11
    s2 = F.lv5_signals(mk, back=2, xmin=10 * 60 + 31, events=ev)
    assert keyrows(s2) == {("BANKNIFTY", days[3], 15 * 60 + 14, 0)} and s2.iloc[0].exit_min == 10 * 60 + 31
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[5], days[9]])})
    assert len(F.lv5_signals(mk, back=1, events=ev)) == 0     # the event day is an expiry day: other contract
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[4], days[9]])})
    assert len(F.lv5_signals(mk, back=1, events=ev)) == 0     # bought on an expiry day: next contract not in lake
    assert len(F.lv5_events()) > 20


def test_bs_both_sides_each_day():
    days = bdays(3)
    M = flat_mat([50000.0] * 3)
    M["c"][1, :col(920)] = np.nan                # day 1: no index bar before 09:20 -> no ATM, no trade
    mk = FakeMk({"BANKNIFTY": FakeIx(days, M, [days[-1]])})
    s = F.bs_signals(mk)
    assert keyrows(s) == {("BANKNIFTY", d, 9 * 60 + 19, sd) for d in (days[0], days[2]) for sd in (1, -1)}
    assert set(s.book) == {"bs_CE_BANKNIFTY", "bs_PE_BANKNIFTY"}
    s = F.bs_signals(mk, m0=10 * 60)
    assert set(s.sig_min) == {10 * 60 - 1}


# ------------------------------------------------------------------------------------------------ R13
def r13_market(touch=True):
    """26 weeks alternating up / down closes (MR ~ 50), then 8 falling weeks (MR touches <= 28.57), then a green week,
    then 10 flat-up weeks."""
    days = bdays(5 * 45, start="2024-01-01")
    lv = np.empty(len(days))
    x = 20000.0
    wk = []
    for w in range(45):
        if w < 26:
            step = 30.0 if w % 2 == 0 else -25.0
        elif w < 34 and touch:
            step = -40.0
        elif w < 34:
            step = 30.0 if w % 2 == 0 else -25.0
        else:
            step = 20.0
        for k in range(5):
            x += step / 5
            lv[5 * w + k] = x
        wk.append(w)
    M = flat_mat(lv)
    for i in range(len(days)):                   # each day's bars drift by its step: open < close on up days
        if i:
            M["o"][i] = lv[i - 1]
    return days, M


def test_r13_wait_reading_fires_after_first_green_week():
    days, M = r13_market()
    mk = FakeMk({"NIFTY": FakeIx(days, M, [days[-1]])})
    s = F.r13_signals(mk)
    assert len(s) == 1
    r = s.iloc[0]
    assert r.day == days[5 * 35] and r.sig_min == OPEN and r.side == 1     # Monday after the green week 34
    ep = M["o"][5 * 35][0]
    assert r.idx_target == pytest.approx(ep + 1000) and r.idx_stop == pytest.approx(ep - 500)
    days, M = r13_market(touch=False)
    assert len(F.r13_signals(FakeMk({"NIFTY": FakeIx(days, M, [days[-1]])}))) == 0


def test_r13_lookahead_and_cap():
    days, M = r13_market()

    def build(M2=None, ch=None):
        return FakeMk({"NIFTY": FakeIx(days, M if M2 is None else M2, [days[-1]])})
    lookahead_ok(F.r13_signals, build, {}, ("NIFTY", days[5 * 35], OPEN, 1))
    mk = FakeMk({"NIFTY": FakeIx(days, M, [days[5 * 35 + 7], days[-1]])})
    r = F.r13_signals(mk).iloc[0]
    assert r.exit_day == days[5 * 35 + 7] and r.exit_min == F.EXP_SQ       # monthly expiry inside the hold


# ------------------------------------------------------------------------------------------------ H5
def h5_market(agree=True, n=20):
    days = bdays(n)
    M = flat_mat([20000.0] * n)
    for i in range(n):                           # ATR14 = 200
        M["h"][i] += 100
        M["l"][i] -= 100
    t = col(1130)
    d = n - 1
    M["c"][d, :t + 1] = np.linspace(20000, 20150, t + 1)       # up 150 by 11:30
    M["c"][d, t + 1:] = 20150.0
    M["c"][d, t + 31:] = 20020.0                 # falls 130 >= 0.6 x 200 at 12:01 -> out 12:02
    K = np.arange(19500, 20501, 50)
    oi = {r: np.full((len(K), W), 1000.0) for r in "CP"}
    add = "P" if agree else "C"
    oi[add][:, 60:] = 3000.0                     # puts (or calls) added after 10:15
    flat_oi = {r: np.full((len(K), W), 1000.0) for r in "CP"}           # earlier days: no OI added (imbalance 0)
    ch = {dd: FakeChain(K, {r: np.full((len(K), W), 100.0) for r in "CP"},
                        {r: (oi if dd == days[d] else flat_oi)[r].copy() for r in "CP"}) for dd in days}
    return days, M, ch


def test_h5_fires_with_oi_agreement_and_trail_exit():
    days, M, ch = h5_market()
    mk = FakeMk({"NIFTY": FakeIx(days, M, [])}, {"NIFTY": FakeOpts(ch)})
    s = F.h5_signals(mk, unds=("NIFTY",))
    assert keyrows(s) == {("NIFTY", days[-1], 11 * 60 + 30, 1)}
    assert s.iloc[0].exit_at == 12 * 60 + 2
    days, M, ch = h5_market(agree=False)
    mk = FakeMk({"NIFTY": FakeIx(days, M, [])}, {"NIFTY": FakeOpts(ch)})
    assert len(F.h5_signals(mk, unds=("NIFTY",))) == 0


def test_h5_time_stop_unreal_days_and_missing_minutes():
    days, M, ch = h5_market()
    M["c"][-1, col(1130) + 1:] = 20150.0          # never extends 0.15 x ATR: out after 60 minutes
    mk = FakeMk({"NIFTY": FakeIx(days, M, [])}, {"NIFTY": FakeOpts(ch)})
    assert F.h5_signals(mk, unds=("NIFTY",)).iloc[0].exit_at == 11 * 60 + 30 + 61
    assert np.isnan(F.h5_signals(mk, TS=0, unds=("NIFTY",)).iloc[0].exit_at)   # no time stop: 15:15
    real = [True] * (len(days) - 1) + [False]
    mk = FakeMk({"NIFTY": FakeIx(days, M, [], real)}, {"NIFTY": FakeOpts(ch)})
    assert len(F.h5_signals(mk, unds=("NIFTY",))) == 0
    days, M, ch = h5_market()
    for k in "ohlc":
        M[k][-1, 200:240] = np.nan               # 40 missing minutes: day dropped (build: at most 30)
    mk = FakeMk({"NIFTY": FakeIx(days, M, [])}, {"NIFTY": FakeOpts(ch)})
    assert len(F.h5_signals(mk, unds=("NIFTY",))) == 0


def test_h5_lookahead():
    days, M, ch = h5_market()

    def build(M2=None, ch2=None):
        return FakeMk({"NIFTY": FakeIx(days, M if M2 is None else M2, [])}, {"NIFTY": FakeOpts(ch if ch2 is None else ch2)})
    lookahead_ok(F.h5_signals, build, dict(unds=("NIFTY",)), ("NIFTY", days[-1], 11 * 60 + 30, 1), chain_unds=("NIFTY",))


# ------------------------------------------------------------------------------------------------ SPECS
def test_specs_shape():
    keys = [s["key"] for s in F.SPECS]
    assert keys == ["SWING-3", "SWING-4", "SWING-5", "SWING_DEEP-3", "SWING_DEEP-6", "SWING_DEEP-8", "LV-2", "LV-4",
                    "LV-5", "BS-1", "R13-1", "H5-1"]
    for s in F.SPECS:
        assert len(s["grid"]) <= 23
        assert s["source"] and s["why"]
        o = s["orig"]
        assert callable(o["fn"]) and o["exits"].get("sq_off", 0) <= F.MD_SQ
        multi = o["fn"] in (F.swing_signals, F.sdeep_signals, F.nr7_signals, F.lv4_signals, F.lv5_signals,
                            F.r13_signals)
        for g in [{}] + s["grid"]:
            ex = g.get("exits", o["exits"])
            assert (ex["sq_off"] == F.MD_SQ) == multi                  # multi-day holds must end at the path's end
            assert ex["sq_off"] <= 15 * 60 + 15 or multi
