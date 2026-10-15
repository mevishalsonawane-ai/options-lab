"""Tests for fam_ctx.py (R52 'ctx' family): synthetic markets only, no lake needed. 15 Oct 2026.

    cd research/hunt/r52 && python -m pytest -q test_fam_ctx.py

For every signal function: a market that should fire exactly the expected (side, minute), one that should not, and a
look-ahead guard (every bar after a signal's minute, that day and later days, changed -> the signal is unchanged).
Edge cases: empty data, a missing-minute gap, an expiry day.
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1]
for p in (HERE, RES, RES.parent):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import fam_ctx as F  # noqa: E402

W = 375
OPEN = 555


# ------------------------------------------------------------------------------------------------ fake market
class FakeIndex:
    def __init__(self, days, M, exp=(), real=True):
        self.days = list(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        self._M = {k: np.asarray(v, float).reshape(len(self.days), W) for k, v in M.items()}
        self.d = {}
        for i, d in enumerate(self.days):
            ok = ~np.isnan(self._M["c"][i])
            m = np.nonzero(ok)[0] + OPEN
            self.d[d] = dict(m=m, o=self._M["o"][i][ok], h=self._M["h"][i][ok], l=self._M["l"][i][ok],
                             c=self._M["c"][i][ok], lot=75, exp=d in set(exp), real=real)

    def mat(self):
        return self._M

    def daily(self):
        rows = [(d, x["o"][0], x["h"].max(), x["l"].min(), x["c"][-1], x["lot"], x["exp"], x["real"])
                for d, x in self.d.items() if len(x["c"])]
        return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close", "lot", "exp", "real"]).set_index("day")


class FakeChain:
    """Dense [strike, minute] arrays like obuy.data.Chain."""

    def __init__(self, K, c=None, v=None, oi=None, series="WEEK"):
        self.K = np.asarray(K, float)
        nk = len(self.K)
        self.series = series
        self.spot = np.full(W, np.nan)
        z = np.full((nk, W), np.nan)
        for f in ("o", "h", "l", "c", "v", "oi", "iv"):
            setattr(self, f, {"C": z.copy(), "P": z.copy()})
        for r in ("C", "P"):
            if c is not None:
                for f in ("o", "h", "l", "c"):
                    getattr(self, f)[r] = np.asarray(c[r], float).copy()
            if v is not None:
                self.v[r] = np.asarray(v[r], float).copy()
            if oi is not None:
                self.oi[r] = np.asarray(oi[r], float).copy()


class FakeOptions:
    def __init__(self, chains=None):
        self.chains = chains or {}

    def chain(self, d, series="near"):
        return self.chains.get(d)


class FakeVix:
    def __init__(self, days, close):
        self.daily = pd.DataFrame(dict(open=close, high=close, low=close, close=close), index=pd.Index(days))

    def prev_close(self, d):
        s = self.daily.close
        i = s.index.searchsorted(d) - 1
        return float(s.iloc[i]) if i >= 0 else float("nan")


class FakeMk:
    def __init__(self, ixs, opts=None, vix=None):
        self.ixs, self.opts, self._vix = ixs, opts or {}, vix

    def index(self, u):
        return self.ixs[u]

    def options(self, u):
        return self.opts.get(u, FakeOptions())

    def release(self, u=None):
        pass

    @property
    def vix(self):
        return self._vix


def sessions(n, start=date(2025, 1, 6)):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def ohlc_from_close(c, wick=0.0):
    c = np.asarray(c, float)
    flat = c.reshape(-1)
    o = np.r_[flat[0], flat[:-1]].reshape(c.shape)
    return dict(o=o, h=np.maximum(o, c) + wick, l=np.minimum(o, c) - wick, c=c.copy())


def after(M, di, col):
    """Copy of M with every bar after column col of day di (and every later day) changed."""
    out = {k: v.copy() for k, v in M.items()}
    rng = np.random.default_rng(99)
    for k in out:
        out[k][di, col + 1:] += rng.normal(0, 30, W - col - 1)
        out[k][di + 1:] += rng.normal(0, 30, out[k][di + 1:].shape)
    out["h"] = np.maximum.reduce([out["h"], out["o"], out["c"]])
    out["l"] = np.minimum.reduce([out["l"], out["o"], out["c"]])
    return out


def upto(sig, day, sm):
    """Signals at or before (day, sig_min)."""
    if not len(sig):
        return sig
    keep = (sig.day < day) | ((sig.day == day) & (sig.sig_min <= sm))
    return sig[keep][["und", "day", "sig_min", "side"]].reset_index(drop=True)


# ================================================================================================ SPECS
def test_specs_shape():
    import r52rules as R
    keys = [s["key"] for s in F.SPECS]
    assert len(keys) == len(set(keys))
    want = {"R24-2", "R24-3", "R24-4", "R24-5", "R24-6", "R24-7", "R24-8", "H29-3", "OBUY_SEARCH-2", "OBUY_SEARCH-4",
            "SCALP17-2", "SCALP17-3", "R9-4", "H33-1", "H45-1", "H35-1"}
    assert set(keys) == want
    for s in F.SPECS:
        assert len(s["grid"]) <= 23
        assert "option-chart" not in s["source"]
        rules = R.spec_rules(s)
        assert rules[0].vid == "orig" and len(rules) == 1 + len(s["grid"])
        assert all(r.exits.sq_off <= 15 * 60 + 15 for r in rules)
    assert "SCALP17-1" in F.__doc__ and "R16-2" in F.__doc__          # LEFT, with reasons


# ================================================================================================ SCALP17-2 (streak)
def _bars(specs, base=20000.0):
    """75 five-minute bars -> minute o/h/l/c. spec (dir, range): dir +1 green, -1 red, 0 flat; minute closes move
    1 point per minute in dir; the bar's high-low equals range."""
    o, h, l, c = (np.zeros(W) for _ in range(4))
    p = base
    for k, (dr, rg) in enumerate(specs):
        cols = range(5 * k, 5 * k + 5)
        start = p
        for j, col in enumerate(cols):
            o[col] = p
            p = p + dr * 1.0
            c[col] = p
        mid = (start + p) / 2
        for col in cols:
            h[col] = mid + rg / 2
            l[col] = mid - rg / 2
    return o, h, l, c


def _day_matrix(day_specs):
    arr = [_bars(s) for s in day_specs]
    return {k: np.stack([a[i] for a in arr]) for i, k in enumerate(("o", "h", "l", "c"))}


def _streak_mk(today_range=11.0, n_green=3):
    flat = [(0, 10.0)] * 75
    today = [(0, 10.0)] * 2 + [(1, today_range)] * n_green + [(0, 10.0)] * (75 - 2 - n_green)
    days = sessions(7)
    M = _day_matrix([flat] * 6 + [today])
    return FakeMk({"BANKNIFTY": FakeIndex(days, M)}), days, M


def test_scalp17_streak_fires_once():
    mk, days, _ = _streak_mk()
    s = F.scalp17_streak(mk)
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.und, r.day, r.sig_min, r.side) == ("BANKNIFTY", days[-1], OPEN + 5 * 4 + 4, -1)


def test_scalp17_streak_no_fire():
    mk, _, _ = _streak_mk(today_range=15.0)          # range ratio 1.5: outside Q4
    assert len(F.scalp17_streak(mk)) == 0
    mk, _, _ = _streak_mk(n_green=2)                 # only two green bars
    assert len(F.scalp17_streak(mk)) == 0


def test_scalp17_streak_lookahead_gap_empty():
    mk, days, M = _streak_mk()
    s0 = F.scalp17_streak(mk)
    M2 = after(M, 6, 24)
    s1 = F.scalp17_streak(FakeMk({"BANKNIFTY": FakeIndex(days, M2)}))
    pd.testing.assert_frame_equal(upto(s0, days[-1], OPEN + 24), upto(s1, days[-1], OPEN + 24))
    # a missing-minute gap inside an untouched bar does not move the signal
    M3 = {k: v.copy() for k, v in M.items()}
    for k in M3:
        M3[k][6, 200:203] = np.nan
    s3 = F.scalp17_streak(FakeMk({"BANKNIFTY": FakeIndex(days, M3)}))
    assert list(s3.sig_min) == [OPEN + 24]
    # empty market
    assert len(F.scalp17_streak(FakeMk({"BANKNIFTY": FakeIndex([], {k: np.zeros((0, W)) for k in "ohlc"})}))) == 0


# ================================================================================================ SCALP17-3 (r15 + dfav)
def _r15_mk(low=9975.0, fall=True):
    days = sessions(7)
    c = np.full((7, W), 10000.0)
    if fall:
        for k in range(16):
            c[6, 150 + k] = 10000.0 - k
        c[6, 166:] = 9985.0
    M = dict(o=c.copy(), h=c.copy(), l=c.copy(), c=c.copy())
    M["l"][6, 50] = low
    return FakeMk({"NIFTY": FakeIndex(days, M)}), days, M


def test_scalp17_r15dfav_fires():
    mk, days, _ = _r15_mk()
    s = F.scalp17_r15dfav(mk)
    assert len(s) > 0 and set(s.day) == {days[-1]} and set(s.side) == {-1}
    assert s.sig_min.min() == OPEN + 159                     # first minute with a >= 8.36 bps fall over 15 minutes
    assert s.sig_min.max() == OPEN + 171                     # until the 15-minute fall decays below 8.36 bps


def test_scalp17_r15dfav_no_fire():
    mk, _, _ = _r15_mk(low=9900.0)                           # too far above the day low (dfav Q5)
    assert len(F.scalp17_r15dfav(mk)) == 0
    mk, _, _ = _r15_mk(fall=False)
    assert len(F.scalp17_r15dfav(mk)) == 0


def test_scalp17_r15dfav_lookahead():
    mk, days, M = _r15_mk()
    s0 = F.scalp17_r15dfav(mk)
    s1 = F.scalp17_r15dfav(FakeMk({"NIFTY": FakeIndex(days, after(M, 6, 159))}))
    pd.testing.assert_frame_equal(upto(s0, days[-1], OPEN + 159), upto(s1, days[-1], OPEN + 159))


def test_scalp17_skips_days_with_many_missing_minutes():
    mk, days, M = _r15_mk()
    for k in M:
        M[k][6, 1:120] = np.nan                              # > 20% of 09:16-15:00 missing
    assert len(F.scalp17_r15dfav(FakeMk({"NIFTY": FakeIndex(days, M)}))) == 0


# ================================================================================================ OBUY_SEARCH
def _trend_mk(exp_last=True, ndays=4):
    days = sessions(ndays)
    c = np.zeros((ndays, W))
    p = 20000.0
    for i in range(ndays):
        for j in range(W):
            p *= 1.0001
            c[i, j] = p
    M = ohlc_from_close(c, wick=0.5)
    exp = {days[-1]} if exp_last else set()
    return FakeMk({"FINNIFTY": FakeIndex(days, M, exp=exp)}), days, M


def test_obs_rule2_fires_on_expiry_afternoon():
    mk, days, _ = _trend_mk()
    s = F.obs_rule(mk, und="FINNIFTY", side=1, atoms=("vwap_far_with", "ema5_with", "expiry_day"), window="1200-1430")
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.day, r.sig_min, r.side) == (days[-1], 12 * 60 - 1, 1)


def test_obs_rule2_no_fire_without_expiry_or_for_puts():
    mk, _, _ = _trend_mk(exp_last=False)
    assert len(F.obs_rule(mk, und="FINNIFTY", side=1, atoms=("vwap_far_with", "ema5_with", "expiry_day"))) == 0
    mk, _, _ = _trend_mk()
    assert len(F.obs_rule(mk, und="FINNIFTY", side=-1, atoms=("vwap_far_with", "ema5_with", "expiry_day"))) == 0


def test_obs_rule2_lookahead_and_gap():
    mk, days, M = _trend_mk()
    a = ("vwap_far_with", "ema5_with", "expiry_day")
    s0 = F.obs_rule(mk, und="FINNIFTY", side=1, atoms=a)
    M2 = after(M, len(days) - 1, 12 * 60 - 1 - OPEN)
    s1 = F.obs_rule(FakeMk({"FINNIFTY": FakeIndex(days, M2, exp={days[-1]})}), und="FINNIFTY", side=1, atoms=a)
    pd.testing.assert_frame_equal(upto(s0, days[-1], 719), upto(s1, days[-1], 719))
    M3 = {k: v.copy() for k, v in M.items()}
    for k in M3:
        M3[k][-1, 100:104] = np.nan                           # missing minutes are filled with the previous close
    s3 = F.obs_rule(FakeMk({"FINNIFTY": FakeIndex(days, M3, exp={days[-1]})}), und="FINNIFTY", side=1, atoms=a)
    assert list(s3.sig_min) == [719]
    with pytest.raises(ValueError):
        F.obs_rule(mk, atoms=("nr7",))


def _obs4_mk(pcr=1.5, vix=20.0):
    days = sessions(4)
    c = np.zeros((4, W))
    for i in range(4):
        for k in range(75):
            c[i, 5 * k:5 * k + 5] = 22000.0 + (5.0 if k % 2 else 0.0)        # alternating 5-min bars: RSI ~ 50
    M = ohlc_from_close(c, wick=1.0)
    K = np.arange(21500.0, 22501.0, 50.0)
    oi = {"C": np.full((len(K), W), 1000.0), "P": np.full((len(K), W), 1000.0 * pcr)}
    ch = FakeChain(K, oi=oi)
    vx = FakeVix(days, [vix] * 4)
    mk = FakeMk({"NIFTY": FakeIndex(days, M)}, opts={"NIFTY": FakeOptions({days[-1]: ch})}, vix=vx)
    return mk, days, M


def test_obs_rule4_fires_and_not():
    a = ("rsi_mid", "vix_high", "pcr_with")
    mk, days, _ = _obs4_mk()
    s = F.obs_rule(mk, und="NIFTY", side=1, atoms=a, window="0920-1015")
    assert len(s) == 1 and (s.iloc[0].day, s.iloc[0].sig_min, s.iloc[0].side) == (days[-1], 559, 1)
    mk, _, _ = _obs4_mk(pcr=1.0)
    assert len(F.obs_rule(mk, und="NIFTY", side=1, atoms=a, window="0920-1015")) == 0
    mk, _, _ = _obs4_mk(vix=15.0)
    assert len(F.obs_rule(mk, und="NIFTY", side=1, atoms=a, window="0920-1015")) == 0


def test_obs_pcr_needs_three_strikes():
    K = np.array([100.0, 150.0])
    oiC = np.full((2, W), 10.0)
    assert not F._obs_pcr_with(K, oiC, oiC * 2, 120.0, 10, 50, 1)


# ================================================================================================ R9-4 absorption
def _r9_mk(event=True, ndays=13):
    days = sessions(ndays)
    c = np.full((ndays, W), 20000.0)
    if event:
        c[-1, 69:100] = np.linspace(20000.0, 20040.0, 31)
        c[-1, 100:] = 20040.0
    M = dict(o=c.copy(), h=c + 1.0, l=c - 1.0, c=c.copy())       # normal 5-min range 2 points
    if event:
        M["h"][-1, 100:105] = c[-1, 100:105] + 0.2              # a narrow event bar (range 0.4 <= 0.5 x 2)
        M["l"][-1, 100:105] = c[-1, 100:105] - 0.2
    K = np.arange(19500.0, 20501.0, 50.0)
    chains = {}
    for i, d in enumerate(days):
        v = np.full((len(K), W), 100.0)
        if event and i == ndays - 1:
            v[:, 100:105] = 500.0                              # 5x the usual volume
        chains[d] = FakeChain(K, v={"C": v, "P": v})
    mk = FakeMk({"NIFTY": FakeIndex(days, M)}, opts={"NIFTY": FakeOptions(chains)})
    return mk, days, M


def test_r9_absorption_fires():
    F._OPTV.clear()
    mk, days, _ = _r9_mk()
    s = F.r9_absorption(mk, horizon=60, unds=("NIFTY",))
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.day, r.sig_min, r.side) == (days[-1], OPEN + 104, -1)       # reversal of an up move: buy the PE
    assert r.exit_at == OPEN + 104 + 61


def test_r9_absorption_no_fire_and_too_little_history():
    F._OPTV.clear()
    mk, _, _ = _r9_mk(event=False)
    assert len(F.r9_absorption(mk, unds=("NIFTY",))) == 0
    F._OPTV.clear()
    mk, _, _ = _r9_mk(ndays=8)                                # < 10 earlier days for the medians
    assert len(F.r9_absorption(mk, unds=("NIFTY",))) == 0


def test_r9_absorption_lookahead():
    F._OPTV.clear()
    mk, days, M = _r9_mk()
    s0 = F.r9_absorption(mk, unds=("NIFTY",))
    mk2 = FakeMk({"NIFTY": FakeIndex(days, after(M, len(days) - 1, 104))}, opts=mk.opts)
    F._OPTV.clear()
    s1 = F.r9_absorption(mk2, unds=("NIFTY",))
    pd.testing.assert_frame_equal(upto(s0, days[-1], 659), upto(s1, days[-1], 659))


# ================================================================================================ H33-1 price shock
def _h33_mk(jump=50.0, vol_burst=True, ndays=7):
    days = sessions(ndays)
    rng = np.random.default_rng(3)
    c = 20000.0 + np.cumsum(rng.choice([-1.0, 1.0], (ndays, W)), axis=1)
    if jump:
        c[-1, 100:] += jump
    M = ohlc_from_close(c, wick=0.5)
    K = np.arange(19000.0, 21001.0, 50.0)
    chains = {}
    for i, d in enumerate(days):
        v = np.full((len(K), W), 100.0)
        if vol_burst and i == ndays - 1:
            v[:, 96:101] = 1000.0
        px = np.full((len(K), W), 100.0)
        chains[d] = FakeChain(K, c={"C": px, "P": px}, v={"C": v, "P": v}, oi={"C": px * 10, "P": px * 10})
    return FakeMk({"FINNIFTY": FakeIndex(days, M)}, opts={"FINNIFTY": FakeOptions(chains)}), days, M


def test_h33_shock_fires_follow_and_fade():
    F._H33.clear()
    mk, days, _ = _h33_mk()
    s = F.h33_shock(mk, und="FINNIFTY", W=1, Z=5, VB=1, N=3)
    assert len(s) == 1
    assert (s.iloc[0].day, s.iloc[0].sig_min, s.iloc[0].side) == (days[-1], OPEN + 100 + 3, 1)
    f = F.h33_shock(mk, und="FINNIFTY", W=1, Z=5, VB=1, N=3, orient="fade")
    assert list(f.side) == [-1]


def test_h33_shock_no_fire():
    F._H33.clear()
    mk, _, _ = _h33_mk(jump=0.0)
    assert len(F.h33_shock(mk, und="FINNIFTY", VB=0)) == 0
    F._H33.clear()
    mk, _, _ = _h33_mk(vol_burst=False)                       # the shock without an option-volume burst
    assert len(F.h33_shock(mk, und="FINNIFTY", VB=1)) == 0
    F._H33.clear()
    assert len(F.h33_shock(mk, und="FINNIFTY", VB=0)) == 1    # ... still a shock without the volume filter


def test_h33_shock_lookahead():
    F._H33.clear()
    mk, days, M = _h33_mk()
    s0 = F.h33_shock(mk, und="FINNIFTY", VB=1)
    F._H33.clear()
    mk2 = FakeMk({"FINNIFTY": FakeIndex(days, after(M, len(days) - 1, 100))}, opts=mk.opts)
    s1 = F.h33_shock(mk2, und="FINNIFTY", VB=1)
    # the shock minute (100) decides; its signal (N minutes later) must not change
    pd.testing.assert_frame_equal(upto(s0, days[-1], OPEN + 103), upto(s1, days[-1], OPEN + 103))


# ================================================================================================ H35-1 levels
def _h35_mk(cross=True):
    days = sessions(7)
    c = np.full((7, W), 100.0)
    M = dict(o=c.copy(), h=c + 0.05, l=c - 0.05, c=c.copy())
    for i in range(6):
        M["h"][i, 10] = 101.0                                  # previous days: H 101, L 99, C 100 -> R1 101
        M["l"][i, 20] = 99.0
    if cross:
        for col in range(55, 60):                              # 5-min bar 11 closes 101.5 (through R1 = 101)
            M["c"][6, col] = M["o"][6, col] = 101.5
        M["c"][6, 60:] = M["o"][6, 60:] = 101.6                # bar 12 holds beyond it
        M["h"][6, 55:] = 101.7
        M["l"][6, 55:] = 101.4
    vx = FakeVix(days, [14.0] * 7)
    return FakeMk({"NIFTY": FakeIndex(days, M)}, vix=vx), days, M


def test_h35_pivot_break_fires():
    F._H35.clear()
    mk, days, _ = _h35_mk()
    s = F.h35_levels(mk, fam="pivD", mode="brk", unds=("NIFTY",))
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.day, r.sig_min, r.side) == (days[-1], OPEN + 12 * 5 + 4, 1)
    assert r.idx_stop == pytest.approx(101.0 - 15.0)


def test_h35_no_fire_flat():
    F._H35.clear()
    mk, _, _ = _h35_mk(cross=False)
    assert len(F.h35_levels(mk, fam="pivD", mode="brk", unds=("NIFTY",))) == 0


def test_h35_lookahead():
    F._H35.clear()
    mk, days, M = _h35_mk()
    s0 = F.h35_levels(mk, fam="pivD", mode="brk", unds=("NIFTY",))
    F._H35.clear()
    s1 = F.h35_levels(FakeMk({"NIFTY": FakeIndex(days, after(M, 6, 64))}, vix=mk.vix), fam="pivD", mode="brk",
                      unds=("NIFTY",))
    pd.testing.assert_frame_equal(upto(s0, days[-1], 619), upto(s1, days[-1], 619))


# ================================================================================================ H45-1 Liquidity entries
def _walk_mk(ndays=14, seed=7, flat=False):
    days = sessions(ndays)
    rng = np.random.default_rng(seed)
    if flat:
        c = np.full((ndays, W), 30000.0)
    else:
        c = 30000.0 + np.cumsum(rng.normal(0, 6.0, ndays * W)).reshape(ndays, W)
    M = ohlc_from_close(c, wick=0.0 if flat else 2.0)
    return FakeMk({"BANKNIFTY": FakeIndex(days, M)}), days, M


def test_h45_liq_entries_fire_and_flat_does_not():
    mk, days, _ = _walk_mk()
    s = F.h45_liq(mk, unds=("BANKNIFTY",))
    assert len(s) > 0
    assert "strike" not in s.columns and set(s.side) <= {1, -1}
    assert s.sig_min.between(9 * 60 + 19, 14 * 60).all()
    mk, _, _ = _walk_mk(flat=True)
    assert len(F.h45_liq(mk, unds=("BANKNIFTY",))) == 0


def test_h45_liq_lookahead():
    mk, days, M = _walk_mk()
    s0 = F.h45_liq(mk, unds=("BANKNIFTY",))
    r = s0.iloc[len(s0) // 2]
    di = days.index(r.day)
    s1 = F.h45_liq(FakeMk({"BANKNIFTY": FakeIndex(days, after(M, di, int(r.sig_min) - OPEN))}), unds=("BANKNIFTY",))
    pd.testing.assert_frame_equal(upto(s0, r.day, r.sig_min), upto(s1, r.day, r.sig_min))


def test_h45_liq_expiry_day_skipped():
    mk, days, M = _walk_mk()
    s = F.h45_liq(mk, unds=("BANKNIFTY",))
    d = s.day.iloc[-1]
    mk2 = FakeMk({"BANKNIFTY": FakeIndex(days, M, exp={d})})
    assert d not in set(F.h45_liq(mk2, unds=("BANKNIFTY",)).day)


# ================================================================================================ H29-3 cheap IV
def _h29_mk(cheap=True, trend=1.0):
    days = sessions(70, start=date(2024, 8, 1)) + sessions(30, start=date(2025, 1, 6))
    n = len(days)
    target = 90                                               # a 2025 session
    rng = np.random.default_rng(11)
    c = np.zeros((n, W))
    p = 20000.0
    for i in range(n):
        for j in range(W):
            p += rng.normal(0, 4.0)
            if i == target:
                p += 0.3 * trend
            c[i, j] = p
    M = ohlc_from_close(c, wick=1.0)
    exp = {d for i, d in enumerate(days) if i % 5 == 4 and i != target}
    K = np.arange(19000.0, 21501.0, 50.0)
    chains = {}
    for i, d in enumerate(days):
        tv = 120.0 + 10.0 * np.sin(i)
        if cheap and i == target:
            tv = 40.0
        S = pd.Series(c[i]).ffill().values
        Cp = np.maximum(S[None, :] - K[:, None], 0) + tv
        Pp = np.maximum(K[:, None] - S[None, :], 0) + tv
        chains[d] = FakeChain(K, c={"C": Cp, "P": Pp})
    mk = FakeMk({"NIFTY": FakeIndex(days, M, exp=exp)}, opts={"NIFTY": FakeOptions(chains)})
    return mk, days, M, target


def test_h29_cheap_fires_on_the_cheap_day():
    F._H29.clear()
    mk, days, _, t = _h29_mk()
    s = F.h29_cheap(mk, q=0.1, dirn="day", unds=("NIFTY",))
    hit = s[s.day == days[t]]
    assert list(hit.sig_min) == [599, 689, 779]
    assert set(hit.side) == {1}


def test_h29_cheap_no_fire_when_not_cheap():
    F._H29.clear()
    mk, days, _, t = _h29_mk(cheap=False)
    s = F.h29_cheap(mk, q=0.1, dirn="day", unds=("NIFTY",))
    assert days[t] not in set(s.day)


def test_h29_cheap_lookahead_and_expiry():
    F._H29.clear()
    mk, days, M, t = _h29_mk()
    s0 = F.h29_cheap(mk, q=0.1, dirn="mom30", unds=("NIFTY",))
    F._H29.clear()
    expd = {d for d, x in mk.ixs["NIFTY"].d.items() if x["exp"]}
    mk2 = FakeMk({"NIFTY": FakeIndex(days, after(M, t, 599 - OPEN), exp=expd)}, opts=mk.opts)
    s1 = F.h29_cheap(mk2, q=0.1, dirn="mom30", unds=("NIFTY",))
    assert len(upto(s0, days[t], 599)) > 0
    pd.testing.assert_frame_equal(upto(s0, days[t], 599), upto(s1, days[t], 599))
    assert not (set(s0.day) & expd)                           # expiry days skipped


def test_h29_har_uses_only_earlier_days():
    days = sessions(80, start=date(2024, 8, 1)) + sessions(40, start=date(2025, 1, 6))
    rv = pd.DataFrame(dict(day=days, rv=np.linspace(1e-5, 3e-5, len(days))))
    H0 = F._h29_har({"NIFTY": rv})
    rv2 = rv.copy()
    rv2.loc[rv2.day >= date(2025, 2, 1), "rv"] = 1e-3        # a change in Feb 2025 ...
    H1 = F._h29_har({"NIFTY": rv2})
    m = H0.day < date(2025, 2, 1)
    np.testing.assert_allclose(H0.har[m].values, H1.har[m].values)   # ... does not move earlier forecasts


# ================================================================================================ R24 patterns
class R24Mk:
    """A market whose R24 feature rows are given (the brain features at each cube minute)."""

    def __init__(self, feats):
        self.feats = feats

    def r24_features(self, u):
        return self.feats.get(u, pd.DataFrame(columns=["ts"] + F.R24_FEATS))


def _feat_day(d, **vals):
    ts = pd.date_range(pd.Timestamp(d) + pd.Timedelta(hours=9, minutes=15), periods=W, freq="min")
    f = pd.DataFrame(dict(ts=ts, minute_of_day=np.arange(W, dtype=float)))
    for c in F.R24_FEATS:
        if c != "minute_of_day":
            f[c] = np.nan
    for k, v in vals.items():
        f[k] = v
    return f


def test_r24_p5_fires_ce_on_gap_down_dte1():
    d = date(2025, 3, 4)
    mk = R24Mk({"NIFTY": _feat_day(d, gap_pct=-0.5, dte_days=1.0)})
    s = F.r24_pattern(mk, pattern="p5", unds=("NIFTY",))
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.und, r.day, r.sig_min, r.side) == ("NIFTY", d, 559, 1)     # decision minute 09:19 -> entry 09:20
    assert np.isnan(r.exit_at)


def test_r24_p5_no_fire():
    d = date(2025, 3, 4)
    assert len(F.r24_pattern(R24Mk({"NIFTY": _feat_day(d, gap_pct=-0.5, dte_days=3.0)}), "p5", unds=("NIFTY",))) == 0
    assert len(F.r24_pattern(R24Mk({"NIFTY": _feat_day(d, gap_pct=0.0, dte_days=1.0)}), "p5", unds=("NIFTY",))) == 0
    assert len(F.r24_pattern(R24Mk({}), "p5", unds=("NIFTY",))) == 0                           # empty data


def test_r24_p4_expiry_day_squares_off_1505():
    d = date(2025, 3, 27)
    f = _feat_day(d, lvl_near_count=2.0, dte_days=0.0)
    f.loc[f.minute_of_day < 40, "lvl_near_count"] = 0.0                  # levels near only from 09:55
    s = F.r24_pattern(R24Mk({"BANKNIFTY": f}), "p4", unds=("BANKNIFTY",))
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.sig_min, r.side, r.exit_at) == (OPEN + 40, 1, 15 * 60 + 5)


def test_r24_p3_iv_cut_and_lookahead():
    d = date(2025, 5, 6)
    f = _feat_day(d, gap_pct=0.5, atm_iv=12.0)
    f.loc[f.minute_of_day >= 100, "atm_iv"] = 20.0                       # IV turns high at 10:55 -> PE (gap up)
    s0 = F.r24_pattern(R24Mk({"NIFTY": f}), "p3", unds=("NIFTY",))
    assert list(zip(s0.sig_min, s0.side)) == [(OPEN + 100, -1)]
    g = f.copy()
    late = g.minute_of_day > 100
    g.loc[late, "atm_iv"] = 5.0
    g.loc[late, "gap_pct"] = -3.0
    s1 = F.r24_pattern(R24Mk({"NIFTY": g}), "p3", unds=("NIFTY",))
    pd.testing.assert_frame_equal(s0[["und", "day", "sig_min", "side"]], s1[["und", "day", "sig_min", "side"]])
    # a missing-minute gap (rows absent) before the pattern starts does not matter
    h = f[(f.minute_of_day < 60) | (f.minute_of_day > 70)]
    assert list(F.r24_pattern(R24Mk({"NIFTY": h}), "p3", unds=("NIFTY",)).sig_min) == [OPEN + 100]


def test_r24_one_side_only_minutes_skipped():
    d = date(2025, 5, 6)
    # p6: other-side OI build-up high + rv high; both sides' other-side OI high at once -> that minute is skipped
    f = _feat_day(d, rv_30m=5.0, oi_build_ce_pct=500.0, oi_build_pe_pct=500.0)
    assert len(F.r24_pattern(R24Mk({"NIFTY": f}), "p6", unds=("NIFTY",))) == 0
    f.loc[f.minute_of_day >= 30, "oi_build_ce_pct"] = 0.0                # only the call side builds afterwards
    s = F.r24_pattern(R24Mk({"NIFTY": f}), "p6", unds=("NIFTY",))
    assert list(zip(s.sig_min, s.side)) == [(OPEN + 30, 1)]              # call OI low, put OI high -> buy CE


def test_r24_frozen_patterns_match_the_writeup():
    pats, fam, cuts = F.r24_design()
    assert pats["p3"] == (("iv", 2), ("gap", 0))
    assert pats["p8"] == (("behind", 2), ("ivrank", 2))
    assert all(fam[p] == "liq" for p in ("p2", "p3", "p4", "p5", "p6", "p7", "p8"))
    assert cuts[("gap", "NIFTY")][0] < 0 < cuts[("gap", "NIFTY")][1]
