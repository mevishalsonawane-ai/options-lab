"""Tests for fam_h42h28 (R52 family: H42 / H28 / H31), on tiny synthetic markets (no lake needed).

Each signal function: a market that fires exactly the expected (side, minute), one that does not, a look-ahead guard
(every bar after a signal's minute - that day and later days - scrambled: the signal must not change), and edge cases
(empty data, a missing-minute gap, an expiry day).
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import fam_h42h28 as F  # noqa: E402
from obuy import config as C  # noqa: E402

W = C.W


# ------------------------------------------------------------------------------------------------ fake market
class FakeIx:
    def __init__(self, days, o, h, l, c, exp=None, real=None):
        self.days = list(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        exp = exp or set()
        self.d = {d: dict(exp=d in exp, real=True if real is None else real.get(d, True), lot=75) for d in self.days}
        self.M = dict(o=np.array(o, float).reshape(len(self.days), W), h=np.array(h, float).reshape(len(self.days), W),
                      l=np.array(l, float).reshape(len(self.days), W), c=np.array(c, float).reshape(len(self.days), W))

    def mat(self):
        return self.M

    def daily(self):
        rows = []
        for i, d in enumerate(self.days):
            rows.append((d, self.M["o"][i, 0], np.nanmax(self.M["h"][i]), np.nanmin(self.M["l"][i]), self.M["c"][i, -1]))
        return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close"]).set_index("day")


class FakeMk:
    def __init__(self, ixs):
        self.ixs = ixs

    def index(self, u):
        return self.ixs[u]


def from_closes(days, closes, exp=None, spread=0.0):
    """Index bars from a (n_days, W) close matrix: open = previous minute's close (the day's first open = its first
    close), high / low = max / min of open and close +- spread."""
    c = np.array(closes, float)
    o = np.empty_like(c)
    o[:, 0] = c[:, 0]
    o[:, 1:] = c[:, :-1]
    h = np.fmax(o, c) + spread
    l = np.fmin(o, c) - spread
    return FakeIx(days, o, h, l, c, exp=exp)


def sessions(start, n):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def flat(n, level=1000.0):
    return np.full((n, W), level)


def scramble_after(ix, day, sig_min, seed=0):
    """A copy of ix with every bar after sig_min on `day` and every bar of later days replaced by noise."""
    rng = np.random.default_rng(seed)
    M = {k: v.copy() for k, v in ix.mat().items()}
    i = ix.days.index(day)
    col = sig_min - C.OPEN_M
    noise = lambda shape: 1000 + rng.normal(0, 30, shape)  # noqa: E731
    for k in ("o", "h", "l", "c"):
        M[k][i, col + 1:] = noise(W - col - 1)
        M[k][i + 1:] = noise(M[k][i + 1:].shape)
    M["h"] = np.fmax(M["h"], np.fmax(M["o"], M["c"]))
    M["l"] = np.fmin(M["l"], np.fmin(M["o"], M["c"]))
    exp = {d for d in ix.days if ix.d[d]["exp"]}
    return FakeIx(ix.days, M["o"], M["h"], M["l"], M["c"], exp=exp)


def assert_lookahead_safe(fn, mk_of, ix, und, sig, **kw):
    """Every signal row survives scrambling all later bars (same day after sig_min, and later days)."""
    for r in sig.itertuples():
        ix2 = scramble_after(ix, r.day, int(r.sig_min), seed=int(r.sig_min))
        s2 = fn(mk_of(ix2), **kw)
        hit = s2[(s2.day == r.day) & (s2.sig_min == r.sig_min) & (s2.side == r.side)]
        assert len(hit) >= 1, (r.day, r.sig_min, r.side)


# ------------------------------------------------------------------------------------------------ H42
def test_h42_q0176_early_high_fires_pe_at_1400_and_not_otherwise():
    days = sessions(date(2025, 2, 3), 3)
    c = flat(3) + np.linspace(0, 5, W)       # day 0 rises all day: its high is late -> no signal
    c[1, 5] = 1010.0                         # day 1: the high so far is made at 09:20 (first 15 min)
    c[1, 6:] = 1000.0 - np.linspace(0, 5, W - 6)
    c[2, 100] = 1010.0                       # day 2: high made at 10:55 -> no signal
    c[2, 101:] = 995.0
    ix = from_closes(days, c)
    mk = FakeMk({"MIDCPNIFTY": ix})
    s = F.h42_signals(mk, qid="Q0176")
    assert list(zip(s.day, s.sig_min, s.side)) == [(days[1], 14 * 60, -1)]
    assert s.book.iloc[0] == "h42_Q0176_MIDCPNIFTY"
    assert_lookahead_safe(F.h42_signals, lambda x: FakeMk({"MIDCPNIFTY": x}), ix, "MIDCPNIFTY", s, qid="Q0176")


def test_h42_q0177_early_low_fires_ce():
    days = sessions(date(2025, 2, 3), 2)
    c = flat(2)
    c[0, 2] = 990.0                          # low at 09:17, then higher all day
    c[0, 3:] = 1001.0
    s = F.h42_signals(FakeMk({"MIDCPNIFTY": from_closes(days, c)}), qid="Q0177")
    assert s[s.day == days[0]][["sig_min", "side"]].values.tolist() == [[840, 1]]


def test_h42_q0378_day_before_expiry_two_legs_two_books():
    days = sessions(date(2025, 3, 24), 5)
    exp = {days[3]}
    ix = from_closes(days, flat(5) + np.linspace(0, 3, W), exp=exp)
    s = F.h42_signals(FakeMk({"MIDCPNIFTY": ix}), qid="Q0378")
    assert set(s.day) == {days[2]}            # only the session right before the expiry day (not the expiry day)
    assert sorted(s.side) == [-1, 1] and set(s.sig_min) == {555}
    assert s.book.nunique() == 2
    assert_lookahead_safe(F.h42_signals, lambda x: FakeMk({"MIDCPNIFTY": x}), ix, "MIDCPNIFTY", s, qid="Q0378")


def test_h42_q0521_weak_noon_pe():
    days = sessions(date(2025, 2, 3), 2)
    c = flat(2)
    c[0, 1:] = 1000.0 - np.linspace(0, 10, W - 1)       # steady fall: -0.52% by 12:30, at the day's low
    c[1, 1:] = 1000.0 - np.linspace(0, 3, W - 1)        # only -0.16%: no signal
    ix = from_closes(days, c)
    s = F.h42_signals(FakeMk({"NIFTY": ix}), qid="Q0521")
    assert list(zip(s.day, s.sig_min, s.side)) == [(days[0], 12 * 60 + 30, -1)]
    assert_lookahead_safe(F.h42_signals, lambda x: FakeMk({"NIFTY": x}), ix, "NIFTY", s, qid="Q0521")


def test_h42_q1213_last3_days_of_finished_months_only():
    days = sessions(date(2025, 1, 20), 25)      # Jan 20 .. Feb 21: January finished, February not
    ix = from_closes(days, flat(len(days)))
    s = F.h42_signals(FakeMk({"NIFTY": ix}), qid="Q1213")
    assert list(s.day) == [date(2025, 1, 29), date(2025, 1, 30), date(2025, 1, 31)]
    assert set(s.side) == {1} and set(s.sig_min) == {555}
    assert_lookahead_safe(F.h42_signals, lambda x: FakeMk({"NIFTY": x}), ix, "NIFTY", s, qid="Q1213")


def test_h42_q0642_bn_5min_spike_fade_uses_previous_days_sd():
    days = sessions(date(2025, 2, 3), 7)
    c = flat(7)
    zig = np.where(np.arange(W) % 10 < 5, 1000.0, 1001.0)    # quiet days: 1-point 5-minute moves
    c[:6] = zig
    c[6] = 1000.0
    c[6, 96:] = 1008.0                                        # day 6: an 8-point jump inside 09:15+95..100
    ix = from_closes(days, c)
    s = F.h42_signals(FakeMk({"BANKNIFTY": ix}), qid="Q0642")
    d6 = s[s.day == days[6]]
    assert d6[["sig_min", "side"]].values.tolist() == [[555 + 100, -1]]
    assert s[s.day.isin(days[:3])].empty                      # < 3 previous days: no sd yet, no signal
    assert_lookahead_safe(F.h42_signals, lambda x: FakeMk({"BANKNIFTY": x}), ix, "BANKNIFTY", d6, qid="Q0642")


def test_h42_q0125_sensex_gap_down_ce():
    days = sessions(date(2025, 2, 3), 3)
    c = flat(3)
    c[1] = 990.0                     # -1.0% gap -> CE at 09:16
    c[2] = 987.0                     # -0.3% gap -> nothing
    ix = from_closes(days, c)
    s = F.h42_signals(FakeMk({"SENSEX": ix}), qid="Q0125")
    assert list(zip(s.day, s.sig_min, s.side)) == [(days[1], 555, 1)]
    assert_lookahead_safe(F.h42_signals, lambda x: FakeMk({"SENSEX": x}), ix, "SENSEX", s, qid="Q0125")


def test_h42_gap_minutes_and_empty_data():
    days = sessions(date(2025, 2, 3), 2)
    c = flat(2)
    c[1, :] = 990.0
    c[1, 0:3] = np.nan                       # the first 3 minutes missing: open = first finite bar
    ix = from_closes(days, c)
    s = F.h42_signals(FakeMk({"SENSEX": ix}), qid="Q0125")
    assert s.empty                           # no bar yet at the 09:15 decision -> no trade
    e = FakeIx([], np.zeros((0, W)), np.zeros((0, W)), np.zeros((0, W)), np.zeros((0, W)))
    for q in ("Q0176", "Q0378", "Q1213", "Q0642"):
        und = F.h42_question(q)["und"]
        out = F.h42_signals(FakeMk({und: e}), qid=q)
        assert out.empty and list(out.columns) == ["und", "day", "sig_min", "side", "book"]


def test_h42_exit_mapping():
    assert F.h42_exit("time", "sq") == dict(sq_off=910)
    assert F.h42_exit("time", "15") == dict(time_stop=15, time_gain=None, sq_off=910)
    assert F.h42_exit("liq", "sq")["stop_pct"] == 0.15
    assert F.h42_exit("T25S20H30", "sq") == dict(tgt_pts=25.0, stop_pts=20.0, time_stop=30, time_gain=None, sq_off=910)


# ------------------------------------------------------------------------------------------------ H28
def test_h28_january_prior_pe_and_weekend_dr_session_dropped():
    days = sessions(date(2025, 1, 27), 8) + [date(2025, 2, 1)]       # + Budget Saturday (a session)
    days = sorted(days + [date(2025, 1, 25)])                        # a Saturday special session: not a session
    ix = from_closes(days, flat(len(days)))
    mk = FakeMk({"NIFTY": ix})
    s = F.h28_signals(mk, legs=(("moy_01", "NIFTY", "0920", "prior", -1),))
    assert list(s.day) == [date(2025, 1, d) for d in (27, 28, 29, 30, 31)]
    assert set(s.sig_min) == {9 * 60 + 19} and set(s.side) == {-1}
    assert_lookahead_safe(F.h28_signals, lambda x: FakeMk({"NIFTY": x}), ix, "NIFTY", s,
                          legs=(("moy_01", "NIFTY", "0920", "prior", -1),))


def test_h28_mom_side_and_no_trade_when_flat():
    days = sessions(date(2025, 1, 6), 3)
    c = flat(3)
    c[0, 10:] = 1005.0                       # up since the open by 10:14 -> CE
    c[1, 10:] = 995.0                        # down -> PE; day 2 flat -> no trade
    ix = from_closes(days, c)
    legs = (("moy_01", "NIFTY", "1015", "mom", 0),)
    s = F.h28_signals(FakeMk({"NIFTY": ix}), legs=legs)
    assert list(zip(s.day, s.sig_min, s.side)) == [(days[0], 614, 1), (days[1], 614, -1)]
    assert_lookahead_safe(F.h28_signals, lambda x: FakeMk({"NIFTY": x}), ix, "NIFTY", s, legs=legs)


def test_h28_expiry_day_and_first3_flags():
    days = sessions(date(2025, 2, 24), 10)                           # Feb 24 .. Mar 7
    ix = from_closes(days, flat(len(days)), exp={days[3]})
    s = F.h28_signals(FakeMk({"BANKNIFTY": ix}), legs=(("exp_day", "BANKNIFTY", "0920", "prior", -1),))
    assert list(s.day) == [days[3]] and s.side.iloc[0] == -1         # trades ON the expiry day
    c = flat(len(days))
    c[:, 200:] = 1003.0                      # up since the open by 13:59 -> CE
    ix2 = from_closes(days, c)
    s2 = F.h28_signals(FakeMk({"BANKNIFTY": ix2}), legs=(("tom_first3", "BANKNIFTY", "1400", "mom", 0),))
    # ranks count the sessions in the data (the lake starts Jan 2024, so only the warm-up month is affected)
    assert list(s2.day) == days[:3] + [date(2025, 3, 3), date(2025, 3, 4), date(2025, 3, 5)]
    assert set(s2.side) == {1}


def test_h28_empty():
    e = FakeIx([], np.zeros((0, W)), np.zeros((0, W)), np.zeros((0, W)), np.zeros((0, W)))
    assert F.h28_signals(FakeMk({"NIFTY": e})).empty


# ------------------------------------------------------------------------------------------------ H31
def _h31_market(n=12, start=date(2025, 3, 3), exp_every=5):
    days = sessions(start, n)
    exp = {d for i, d in enumerate(days) if i % exp_every == exp_every - 1}
    return days, exp


def test_h31_r03_strong_close_ce_next_day_exit():
    days, exp = _h31_market()
    c = flat(len(days))
    c[1, 200:] = 1010.0                      # day 1 closes at its high -> loc 1 -> CE
    c[2, 100:] = 990.0                       # day 2 closes at its low -> PE
    ix = from_closes(days, c, exp=exp)
    mk = FakeMk({"NIFTY": ix})
    s = F.h31_signals(mk, rule="R03", unds=("NIFTY",))
    r1 = s[s.day == days[1]].iloc[0]
    assert (r1.side, r1.sig_min, r1.exit_day, r1.exit_min) == (1, 919, days[2], 615)
    assert s[s.day == days[2]].side.tolist() == [-1]
    assert s[s.day == days[0]].empty         # flat day: loc 0.5 -> no side
    assert_lookahead_safe(F.h31_signals, lambda x: FakeMk({"NIFTY": x}), ix, "NIFTY", s, rule="R03", unds=("NIFTY",))
    s9 = F.h31_signals(mk, rule="R03", xit="X0916", unds=("NIFTY",))
    assert set(s9.exit_min) == {556}
    sp = F.h31_signals(mk, rule="R03", xit="P20", unds=("NIFTY",))
    assert set(sp.exit_min) == {615} and F.h31_exit("P20")["stop_pts"] == 20.0


def test_h31_no_expiry_day_entry_and_never_into_expiry():
    days, exp = _h31_market()
    c = np.tile(np.linspace(1000, 1010, W), (len(days), 1))   # every day closes at its high -> CE
    ix = from_closes(days, c, exp=exp)
    s = F.h31_signals(FakeMk({"NIFTY": ix}), rule="R03", unds=("NIFTY",))
    assert not set(s.day) & exp                                  # never entered on an expiry day
    for r in s.itertuples():
        nxt = [e for e in sorted(exp) if e >= r.day][0]
        assert r.exit_day < nxt                                  # never held into the contract's expiry day
    assert days[3] not in set(s.day)                             # day 3: next session is the expiry -> skipped


def test_h31_filters_r08_and_fomc():
    days = sessions(date(2025, 6, 12), 6)    # Thu 12 .. Thu 19 Jun 2025 (FOMC decision Wed 18 Jun, US date)
    exp = {date(2025, 6, 26)}
    days = days + [date(2025, 6, 26)]
    c = flat(len(days))
    c[:, 300:] = 1004.0                      # each day +0.4% vs its open ...
    for i in range(1, len(days)):
        c[i] = c[i] + 4.0 * i                # ... and vs the previous close: R08 -> CE
    ix = from_closes(days, c, exp=exp)
    mk = FakeMk({"NIFTY": ix})
    s0 = F.h31_signals(mk, rule="R08", unds=("NIFTY",))
    s1 = F.h31_signals(mk, rule="R08", filt="F1", unds=("NIFTY",))
    assert date(2025, 6, 13) in set(s0.day) and date(2025, 6, 13) not in set(s1.day)   # Friday -> Monday: 3 nights
    s2 = F.h31_signals(mk, rule="R08", filt="F2", unds=("NIFTY",))
    assert all((date(2025, 6, 26) - d).days >= 7 for d in s2.exit_day)
    fo = F.h31_signals(mk, rule="R16", unds=("NIFTY",))
    assert list(fo.day) == [date(2025, 6, 18)] and fo.side.tolist() == [1]
    assert_lookahead_safe(F.h31_signals, lambda x: FakeMk({"NIFTY": x}), ix, "NIFTY", s0, rule="R08", unds=("NIFTY",))


def test_h31_gap_minutes_and_empty():
    days, exp = _h31_market()
    c = flat(len(days))
    c[1, 200:] = 1010.0
    c[1, 360:365] = np.nan                   # 15:15-15:19 missing: the last bar within 5 minutes (15:14) decides
    s = F.h31_signals(FakeMk({"NIFTY": from_closes(days, c, exp=exp)}), rule="R03", unds=("NIFTY",))
    assert s[s.day == days[1]].side.tolist() == [1]
    c[1, 354:365] = np.nan                   # nothing within 5 minutes of 15:19 -> no trade
    s = F.h31_signals(FakeMk({"NIFTY": from_closes(days, c, exp=exp)}), rule="R03", unds=("NIFTY",))
    assert s[s.day == days[1]].empty
    e = FakeIx([], np.zeros((0, W)), np.zeros((0, W)), np.zeros((0, W)), np.zeros((0, W)))
    out = F.h31_signals(FakeMk({"NIFTY": e}), unds=("NIFTY",))
    assert out.empty and "exit_day" in out.columns


# ------------------------------------------------------------------------------------------------ SPECS
def test_specs_build_through_the_harness():
    from r52rules import spec_rules
    keys = [s["key"] for s in F.SPECS]
    assert keys == ["H42-3", "H42-4", "H42-6", "H42-7", "H42-8", "H42-9", "H28-1", "H28-2", "H28-3", "H28-4",
                    "H31-4", "H31-5"]
    for s in F.SPECS:
        assert len(s["grid"]) <= 23
        rs = spec_rules(s)
        assert rs[0].vid == "orig" and len(rs) == 1 + len(s["grid"])
        assert s["source"] in ("index", "index (calendar)")
        assert rs[0].rule.money == 1
        if s["key"].startswith("H31"):
            assert all(r.exits.sq_off == F.MD_SQ for r in rs) and rs[0].expiry == "skip"
        else:
            assert rs[0].expiry == "allow"
    h = {s["key"]: s for s in F.SPECS}
    assert h["H42-3"]["orig"]["exits"] == F.h42_exit("T25S20H30", "sq")
    assert h["H42-3"]["grid"][0] == dict(params=dict(qid="Q0177"), exits=F.h42_exit("time", "sq"))
    assert h["H42-4"]["orig"]["exits"] == F.h42_exit("T30S10H15", "sq")
    assert h["H42-7"]["orig"]["exits"] == F.h42_exit("liq", "sq")
    assert h["H42-8"]["orig"]["exits"] == F.h42_exit("time", "15")
    assert h["H28-1"]["orig"]["exits"]["stop_pct"] == 0.15 and h["H28-2"]["orig"]["exits"]["stop_pct"] is None


def test_loading_originals_leaves_no_trace():
    import warnings
    before_path, before_err = list(sys.path), np.geterr()
    before_warn = list(warnings.filters)
    F.h42_tables.cache_clear()
    F.h42_tables()
    assert sys.path == before_path and np.geterr() == before_err and list(warnings.filters) == before_warn
    assert "common" not in sys.modules or getattr(sys.modules["common"], "__file__", "").find("h42") < 0
