"""Tests for fam_r1.py (R52 family R1), on tiny synthetic markets (no lake needed). 15 Oct 2026.

Each adapter: one market that must fire exactly the expected (side, minute), one that must not, and a look-ahead guard
(every bar AFTER the signal minute, same day and later days, changed -> the signal row is unchanged). Edge cases: no
data, a missing-minute gap, a day with too much missing data, expiry days.
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

import r52cfg  # noqa: F401  (sys.path: research/, repo root)
import fam_r1 as F
import r52rules as RR

W = 375
OPEN = 555


# ------------------------------------------------------------------------------------------------ fake market
class FakeIx:
    def __init__(self, closes, exp, start):
        self.days = []
        d = start
        for _ in range(len(closes)):
            while d.weekday() >= 5:
                d += timedelta(days=1)
            self.days.append(d)
            d += timedelta(days=1)
        C = np.array(closes, float).reshape(len(closes), W) if len(closes) else np.zeros((0, W))
        O = np.full_like(C, np.nan)
        for i in range(len(C)):
            row = C[i]
            prev = pd.Series(row).ffill().shift(1).to_numpy(copy=True)
            prev[0] = row[0]
            O[i] = np.where(np.isfinite(row), np.where(np.isfinite(prev), prev, row), np.nan)
        H = np.fmax(O, C) + 0.5
        Lw = np.fmin(O, C) - 0.5
        self.M = dict(o=O, h=H, l=Lw, c=C)
        self.d = {}
        for i, dd in enumerate(self.days):
            self.d[dd] = dict(exp=bool(exp[i]), real=True)

    def mat(self):
        return self.M

    def daily(self):
        rows = []
        for i, d in enumerate(self.days):
            c = self.M["c"][i]
            ok = np.isfinite(c)
            if not ok.any():
                rows.append((d, np.nan, np.nan, np.nan, np.nan))
                continue
            rows.append((d, self.M["o"][i][ok][0], np.nanmax(self.M["h"][i]), np.nanmin(self.M["l"][i]), c[ok][-1]))
        return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close"]).set_index("day")


class FakeVix:
    def __init__(self, days, closes):
        self.daily = pd.DataFrame(dict(close=closes), index=pd.Index(days, name="day"))


class FakeMk:
    def __init__(self, closes, exp=None, vix=None, und="NIFTY", start=date(2025, 1, 6)):
        exp = exp if exp is not None else [False] * len(closes)
        self.ix = {und: FakeIx(closes, exp, start)}
        days = self.ix[und].days
        self.vix = FakeVix(days, vix if vix is not None else [15.0] * len(days))

    def index(self, u):
        return self.ix[u]


def flat(level=20000.0):
    return np.full(W, level)


def wiggle(level=20000.0, amp=50.0):
    """A day ranging level-amp .. level+amp, closing at level."""
    c = flat(level)
    c[10], c[20] = level + amp, level - amp
    return c


def perturb(closes, di, s, seed=1):
    """Change every bar after column s of day di and every bar of the later days."""
    rng = np.random.default_rng(seed)
    out = [np.array(c, float) for c in closes]
    out[di][s + 1:] = out[di][s + 1:] * (1 + 0.02 * rng.standard_normal(W - s - 1))
    for k in range(di + 1, len(out)):
        out[k] = out[k] * (1 + 0.02 * rng.standard_normal(W))
    return out


KEYS = ["und", "day", "sig_min", "side", "idx_stop", "idx_target"]


def same_row(a, b):
    ra = a.iloc[0][KEYS]
    rb = b[(b.day == a.iloc[0].day) & (b.und == a.iloc[0].und)]
    assert len(rb) == 1
    rb = rb.iloc[0][KEYS]
    for k in KEYS:
        x, y = ra[k], rb[k]
        if isinstance(x, float) and np.isnan(x):
            assert isinstance(y, float) and np.isnan(y), k
        else:
            assert x == y, k


def run(fn, closes, **kw):
    mkw = {k: kw.pop(k) for k in ("exp", "vix") if k in kw}
    return fn(FakeMk(closes, **mkw), unds=("NIFTY",), **kw)


# ------------------------------------------------------------------------------------------------ R1-2 N2 WVB
def wvb_days(fire=True):
    d1 = flat()
    if fire:
        d1[100:] = 20100.0         # range 101 x 0.75 = 75.75 above the 20000 open
    return [wiggle(), d1]


def test_wvb_fires_and_not():
    df = run(F.r1_wvb, wvb_days(), k=0.75, exit="native")
    assert len(df) == 1 and df.sig_min[0] == OPEN + 100 and df.side[0] == 1
    assert df.idx_stop[0] == 20000.0 and np.isnan(df.idx_target[0])
    eod = run(F.r1_wvb, wvb_days(), k=0.75, exit="EOD")
    assert len(eod) == 1 and np.isnan(eod.idx_stop[0]) and np.isnan(eod.exit_at[0])
    assert len(run(F.r1_wvb, wvb_days(False), k=0.75, exit="EOD")) == 0


def test_wvb_lookahead_and_gap():
    c = wvb_days()
    a = run(F.r1_wvb, c, k=0.75, exit="native")
    same_row(a, run(F.r1_wvb, perturb(c, 1, 100), k=0.75, exit="native"))
    g = [x.copy() for x in c]
    g[1][40:50] = np.nan            # a 10-minute gap before the signal
    b = run(F.r1_wvb, g, k=0.75, exit="native")
    assert len(b) == 1 and b.sig_min[0] == OPEN + 100


# ------------------------------------------------------------------------------------------------ R1-1 N1 noise
def noise_days(fire=True):
    days = []
    for i in range(15):
        c = 20000.0 * (1 + 0.001 * np.sin(np.arange(W) / 7.0 + i))
        days.append(c)
    d = flat()
    if fire:
        d[50:] = 20300.0            # +1.5% from 10:05: first check above the band = 10:30 (column 74)
    days.append(d)
    return days


def test_noise_fires_and_not():
    df = run(F.r1_noise, noise_days(), m=1.0, exit="EOD")
    assert len(df) == 1 and df.side[0] == 1 and df.sig_min[0] == OPEN + 74
    assert df.day[0] == FakeMk(noise_days()).ix["NIFTY"].days[15]
    assert len(run(F.r1_noise, noise_days(False), m=1.0, exit="EOD")) == 0


def test_noise_trail_and_lookahead():
    c = noise_days()
    c[15][200:] = 20000.0           # falls back inside max(band, TWAP) at 12:35
    t = run(F.r1_noise, c, m=1.0, exit="trail1")
    assert len(t) == 1 and t.exit_at[0] == OPEN + 201
    a = run(F.r1_noise, c, m=1.0, exit="EOD")
    same_row(a, run(F.r1_noise, perturb(c, 15, 74), m=1.0, exit="EOD"))


def test_noise_needs_14_days():
    assert len(run(F.r1_noise, noise_days()[-5:], m=1.0, exit="EOD")) == 0


# ------------------------------------------------------------------------------------------------ R1-4 N4 ROD
def rod_days(up=True):
    d1 = flat()
    d1[300:] = 20100.0 if up else 20000.0
    return [flat(), d1]


def test_rod_expiry_only():
    df = run(F.r1_rod, rod_days(), exp=[False, True])
    assert len(df) == 1 and df.side[0] == 1 and df.sig_min[0] == 14 * 60 + 39
    assert len(run(F.r1_rod, rod_days(), exp=[False, False])) == 0          # not an expiry day
    assert len(run(F.r1_rod, rod_days(False), exp=[False, True])) == 0      # no move
    e = run(F.r1_rod, rod_days(), exp=[False, False], T="1430", base="op", thr=0.0, expiry_only=False)
    assert len(e) == 1 and e.sig_min[0] == 14 * 60 + 29


def test_rod_lookahead():
    c = rod_days()
    a = run(F.r1_rod, c, exp=[False, True])
    same_row(a, run(F.r1_rod, perturb(c, 1, 324), exp=[False, True]))


# ------------------------------------------------------------------------------------------------ R1-6 N6 FVG
def fvg_days(fire=True):
    d = flat()
    if fire:
        d[35:40] = 20030.0
        d[40:60] = 20060.0
        d[60:] = 20020.0            # trades back into the gap at 10:15
    return [flat(), d]


def test_fvg_fires_and_not():
    df = run(F.r1_fvg, fvg_days(), T0="0930", g=0.0005, exit="native")
    assert len(df) == 1 and df.side[0] == 1 and df.sig_min[0] == OPEN + 60
    assert df.idx_stop[0] == pytest.approx(19999.5) and df.idx_target[0] == pytest.approx(20020 + 2 * 20.5)
    eod = run(F.r1_fvg, fvg_days(), T0="0930", g=0.0005, exit="EOD")
    assert eod.idx_stop[0] == pytest.approx(19999.5) and np.isnan(eod.idx_target[0])
    assert len(run(F.r1_fvg, fvg_days(False), T0="0930", g=0.0005, exit="EOD")) == 0
    late = run(F.r1_fvg, fvg_days(), T0="1000", g=0.0005, exit="EOD")      # the gap's 3rd bar closes 09:59: counts
    assert len(late) == 1 and late.sig_min[0] == OPEN + 60


def test_fvg_lookahead():
    c = fvg_days()
    a = run(F.r1_fvg, c, T0="0930", g=0.0005, exit="native")
    same_row(a, run(F.r1_fvg, perturb(c, 1, 60), T0="0930", g=0.0005, exit="native"))


# ------------------------------------------------------------------------------------------------ R1-7 N7 turtle
def turtle_days(fire=True):
    d = flat()
    if fire:
        d[3:8] = 19990.0            # below yesterday's low 19999.5, back above by the 09:29 5-min close
    return [flat(), d]


def test_turtle_fires_and_not():
    df = run(F.r1_turtle, turtle_days(), B=5, exit="native")
    assert len(df) == 1 and df.side[0] == 1 and df.sig_min[0] == OPEN + 14
    assert df.idx_stop[0] == pytest.approx(19989.5) and df.idx_target[0] == pytest.approx(20000 + 2 * 10.5)
    assert len(run(F.r1_turtle, turtle_days(False), B=5, exit="native")) == 0


def test_turtle_lookahead():
    c = turtle_days()
    a = run(F.r1_turtle, c, B=5, exit="native")
    same_row(a, run(F.r1_turtle, perturb(c, 1, 14), B=5, exit="native"))


# ------------------------------------------------------------------------------------------------ R1-8 N8 IBS
def ibs_days(low_close=True):
    d0 = wiggle()
    d0[-1] = 19955.0 if low_close else 20000.0
    return [d0, flat()]


def test_ibs_fires_and_not():
    df = run(F.r1_ibs, ibs_days(), lo=0.1, exit="60m")
    assert len(df) == 1 and df.side[0] == 1 and df.sig_min[0] == OPEN and df.exit_at[0] == OPEN + 61
    liq = run(F.r1_ibs, ibs_days(), lo=0.1, exit="LIQ")
    assert len(liq) == 1 and np.isnan(liq.exit_at[0])
    assert len(run(F.r1_ibs, ibs_days(False), lo=0.1, exit="60m")) == 0


def test_ibs_lookahead():
    c = ibs_days()
    a = run(F.r1_ibs, c, lo=0.1, exit="60m")
    same_row(a, run(F.r1_ibs, perturb(c, 1, 0), lo=0.1, exit="60m"))


# ------------------------------------------------------------------------------------------------ R1-9 N9 VIX spike
def test_vixspk_fires_and_not():
    c = [flat(), flat(), flat()]
    df = run(F.r1_vixspk, c, vix=[10.0, 12.0, 11.0])
    assert len(df) == 1 and df.side[0] == 1 and df.sig_min[0] == OPEN
    assert df.day[0] == FakeMk(c).ix["NIFTY"].days[2]
    assert len(run(F.r1_vixspk, c, vix=[10.0, 10.5, 11.0])) == 0
    t = run(F.r1_vixspk, c, vix=[10.0, 12.0, 11.0], trig="chg10", T="0945", exit="LIQ")
    assert len(t) == 1 and t.sig_min[0] == 9 * 60 + 44


def test_vixspk_lookahead():
    c = [flat(), flat(), flat(), flat()]
    a = run(F.r1_vixspk, c, vix=[10.0, 12.0, 11.0, 11.0])
    # later VIX and later bars changed: the 3rd day's signal stays
    b = run(F.r1_vixspk, perturb(c, 2, 0), vix=[10.0, 12.0, 30.0, 5.0])
    same_row(a, b)


# ------------------------------------------------------------------------------------------------ R1-14 N14
def lunch_days(up=True):
    d = flat()
    d[100:] = 20200.0 if up else 20050.0
    return [flat(), d]


def test_lunchrev_fires_and_not():
    df = run(F.r1_lunchrev, lunch_days(), thr=0.006, exit="LIQ")
    assert len(df) == 1 and df.side[0] == -1 and df.sig_min[0] == 11 * 60 + 59 and np.isnan(df.exit_at[0])
    t = run(F.r1_lunchrev, lunch_days(), thr=0.006, exit="1330")
    assert t.exit_at[0] == 13 * 60 + 30
    assert len(run(F.r1_lunchrev, lunch_days(False), thr=0.006, exit="LIQ")) == 0
    assert len(run(F.r1_lunchrev, lunch_days(False), thr=0.003, exit="LIQ")) == 0      # +0.25%


def test_lunchrev_lookahead_and_expiry():
    c = lunch_days()
    a = run(F.r1_lunchrev, c, thr=0.006, exit="LIQ")
    same_row(a, run(F.r1_lunchrev, perturb(c, 1, 164), thr=0.006, exit="LIQ"))
    # R1 traded expiry days too: the flag does not change the signal
    e = run(F.r1_lunchrev, c, thr=0.006, exit="LIQ", exp=[False, True])
    same_row(a, e)


# ------------------------------------------------------------------------------------------------ edge cases
ALL = [(F.r1_noise, {}), (F.r1_wvb, {}), (F.r1_rod, {}), (F.r1_fvg, {}), (F.r1_turtle, {}), (F.r1_ibs, {}),
       (F.r1_vixspk, {}), (F.r1_lunchrev, {})]


@pytest.mark.parametrize("fn,kw", ALL)
def test_empty_market(fn, kw):
    df = fn(FakeMk([]), unds=("NIFTY",), **kw)
    assert len(df) == 0 and list(df.columns) == F.COLS


def test_broken_day_skipped():
    c = wvb_days()
    c[1][120:] = np.nan             # > 20% of the session missing: R1's data filter drops the day
    c[1][100:120] = 20100.0
    assert len(run(F.r1_wvb, c, k=0.75, exit="EOD")) == 0


def test_lunchrev_two_indices_books():
    mk = FakeMk(lunch_days())
    mk.ix["BANKNIFTY"] = mk.ix["NIFTY"]
    df = F.r1_lunchrev(mk, thr=0.006, exit="LIQ", unds=("NIFTY", "BANKNIFTY"))
    assert sorted(df.book) == ["r1n14_BANKNIFTY", "r1n14_NIFTY"]


def test_per_index_params():
    mk = FakeMk(wvb_days())
    mk.ix["BANKNIFTY"] = mk.ix["NIFTY"]
    df = F.r1_wvb(mk, k={"NIFTY": 0.75, "BANKNIFTY": 0.25}, exit={"NIFTY": "native", "BANKNIFTY": "EOD"},
                  unds=("NIFTY", "BANKNIFTY"))
    n, b = df[df.und == "NIFTY"].iloc[0], df[df.und == "BANKNIFTY"].iloc[0]
    assert n.idx_stop == 20000.0 and np.isnan(b.idx_stop)


# ------------------------------------------------------------------------------------------------ specs
def test_specs_build():
    keys = [s["key"] for s in F.SPECS]
    assert len(keys) == len(set(keys))
    for s in F.SPECS:
        vs = RR.spec_rules(s)
        assert vs[0].vid == "orig" and 1 <= len(vs) <= 24
        assert vs[0].rule.money == 1 and vs[0].rule.series == "near"
        assert s["source"] in ("index", "India VIX + index")
        ids = {(repr(sorted(v.params.items())), v.exits.label(), v.expiry, v.window) for v in vs}
        assert len(ids) == len(vs)              # no duplicate setting
        for v in vs:
            assert v.window[0] <= v.window[1]
            liq = isinstance(v.params.get("exit"), str) and v.params["exit"] == "LIQ"
            assert (v.exits.stop_pct == 0.15 and v.exits.time_stop == 20) == liq
    by = {s["key"]: s for s in F.SPECS}
    assert by["R1-4"]["orig"]["expiry"] == "only"
    assert by["R1-2@BANKNIFTY"]["orig"]["exits"] == F.LIQ and by["R1-2@NIFTY"]["orig"]["exits"] == {}
