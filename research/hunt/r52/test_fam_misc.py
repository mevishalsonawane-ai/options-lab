"""Tests for fam_misc.py (R52 family 'misc'): synthetic markets, no lake needed.

    cd research/hunt/r52 && python -m pytest -q test_fam_misc.py

Each signal function: a designed market that fires exactly the expected (side, minute), one that does not, and the
look-ahead guard (every bar after a signal's minute, that day and every later day, replaced by a different path: the
signal's entry fields must not change). Edge cases: no data, a missing-minute gap, an expiry day.
"""
from __future__ import annotations

import sys
import warnings
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fam_misc as F  # noqa: E402

warnings.filterwarnings("ignore", category=RuntimeWarning)
W, OPEN = 375, 555
ENTRY_COLS = ["und", "day", "sig_min", "side", "idx_stop", "idx_target", "strike", "ref_spot", "book", "tag"]


# ------------------------------------------------------------------------------------------------ fake market
class FakeIndex:
    def __init__(self, days, M, exp=None, lot=30):
        self.days = list(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        self._M = {k: np.asarray(v, dtype=float) for k, v in M.items()}
        exp = exp or set()
        self.d = {d: dict(exp=d in exp, real=True, lot=lot) for d in self.days}

    def mat(self):
        return self._M

    def lot(self, d, mode="data"):
        return self.d[d]["lot"]


class FakeChain:
    def __init__(self, K, c, oi):
        self.K, self.c, self.oi = np.asarray(K), c, oi


class FakeOptions:
    def __init__(self, chains):
        self.chains = chains

    def chain(self, d, series="near"):
        return self.chains.get(d)


class FakeVix:
    def __init__(self, days, closes):
        self.daily = pd.DataFrame(dict(open=closes, high=closes, low=closes, close=closes), index=list(days))

    def prev_close(self, d):
        s = self.daily.close
        i = s.index.searchsorted(d) - 1
        return float(s.iloc[i]) if i >= 0 else float("nan")


class FakeMk:
    def __init__(self, ixs, chains=None, vix=None):
        self.ixs = ixs
        self.chains = chains or {}
        self.vix = vix

    def index(self, u):
        return self.ixs.get(u) or FakeIndex([], {k: np.zeros((0, W)) for k in "ohlc"})

    def options(self, u):
        return FakeOptions(self.chains.get(u, {}))

    def release(self, u=None):
        pass


def days_from(n, start=date(2025, 1, 6)):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def from_close(c, wick=0.0):
    """o/h/l/c dict from a (D, 375) close matrix: open = previous minute's close, wicks of `wick` points."""
    c = np.asarray(c, dtype=float)
    o = np.concatenate([c[:, :1], c[:, :-1]], axis=1)
    return dict(o=o, h=np.maximum(o, c) + wick, l=np.minimum(o, c) - wick, c=c)


def walk(D, p0=50000.0, sig=12.0, seed=1, drift=None):
    rng = np.random.default_rng(seed)
    steps = rng.normal(0, sig, (D, W))
    if drift is not None:
        steps += drift[:, None]
    return p0 + np.cumsum(steps.reshape(-1)).reshape(D, W)


def make_chains(days, c, step=100.0):
    """A chain per day: strikes ATM +- 10 around the day's first close; CE / PE prices and OI are functions of the
    SAME minute's index (and its past), so a chain never leaks a later minute."""
    out = {}
    for i, d in enumerate(days):
        atm = np.round(c[i, 0] / step) * step
        K = atm + step * np.arange(-10, 11)
        spot = c[i][None, :]
        ce = np.maximum(spot - K[:, None], 0) + 50.0
        pe = np.maximum(K[:, None] - spot, 0) + 50.0
        drift = np.cumsum(np.r_[0.0, np.diff(c[i])])[None, :]
        oi_c = 1e5 + 0 * K[:, None] - 50 * drift
        oi_p = 1e5 + 0 * K[:, None] + 50 * drift
        out[d] = FakeChain(K, dict(C=ce, P=pe), dict(C=oi_c, P=oi_p))
    return out


def market(und, days, M, chains=None, vix=None, exp=None, lot=30):
    vix = vix or FakeVix(days, np.full(len(days), 12.0))
    return FakeMk({und: FakeIndex(days, M, exp=exp, lot=lot)}, {und: chains or {}}, vix)


def perturb(M, di, col, seed=99):
    """Every bar after (di, col) replaced: the rest of day di and all later days follow a different random walk."""
    rng = np.random.default_rng(seed)
    c = np.array(M["c"], dtype=float)
    D = c.shape[0]
    flat = c.reshape(-1)
    k = di * W + col + 1
    if k < flat.size:
        start = flat[k - 1] if np.isfinite(flat[k - 1]) else 50000.0
        flat[k:] = start + np.cumsum(rng.normal(0, 25.0, flat.size - k))
    out = from_close(flat.reshape(D, W), wick=3.0)
    for key in "ohl":                       # keep the original bars up to and including the signal minute
        a = np.array(M[key], dtype=float).reshape(-1)
        b = out[key].reshape(-1)
        b[:k] = a[:k]
        out[key] = b.reshape(D, W)
    out["c"] = flat.reshape(D, W)
    return out


def rows_of(df):
    if not len(df):
        return []
    cols = [c for c in ENTRY_COLS if c in df.columns]
    out = []
    for r in df[cols].itertuples(index=False):
        out.append(tuple(round(v, 6) if isinstance(v, float) and np.isfinite(v) else
                         ("nan" if isinstance(v, float) else v) for v in r))
    return out


def lookahead_check(fn, params, und, days, M, chains_fn=None, vix=None, n_check=6, seed=5):
    """For up to n_check signals: perturb every later bar and require the signal (entry fields) to survive unchanged."""
    mk = market(und, days, M, chains=chains_fn(days, M["c"]) if chains_fn else None, vix=vix)
    base = fn(mk, **params)
    assert len(base), f"{fn.__name__} {params}: the random market gave no signal to guard"
    rng = np.random.default_rng(seed)
    pick = rng.choice(len(base), min(n_check, len(base)), replace=False)
    base_rows = rows_of(base)
    di_of = {d: i for i, d in enumerate(days)}
    for j in pick:
        r = base.iloc[j]
        di, col = di_of[r.day], int(r.sig_min) - OPEN
        M2 = perturb(M, di, col, seed=int(j) + 11)
        mk2 = market(und, days, M2, chains=chains_fn(days, M2["c"]) if chains_fn else None, vix=vix)
        new = rows_of(fn(mk2, **params))
        assert base_rows[j] in new, f"{fn.__name__} {params}: signal {base_rows[j]} changed after perturbing later bars"


# ------------------------------------------------------------------------------------------------ look-ahead guard
D_RAND = 26
DAYS = days_from(D_RAND)
BN = from_close(walk(D_RAND, seed=3), wick=4.0)

GUARD = [
    (F.r20c_break, dict(unds=("BANKNIFTY",))),
    (F.r20c_break, dict(types=("PIV", "PDHL"), tf=15, n=2, last_entry=(10, 30), liq_exit=True, unds=("BANKNIFTY",))),
    (F.r20c_break, dict(tf=15, vix_max=13.0, liq_exit=True, unds=("BANKNIFTY",))),
    (F.h18_break, dict(levels="R60", unds=("BANKNIFTY",))),
    (F.h18_break, dict(levels="ALL", unds=("BANKNIFTY",))),
    (F.h43_bakeoff, dict(strat="bb", tf=1)),
    (F.h43_bakeoff, dict(strat="pe", tf=5)),
    (F.h43_enhanced, dict(und="BANKNIFTY", fam="bb", n=20, k=2.0, tf=3, filt="oi")),
    (F.h43_enhanced, dict(und="BANKNIFTY", fam="ema", f=5, s=21, thr=15, tf=5, filt="wide")),
    (F.h43_enhanced, dict(und="BANKNIFTY", fam="pe", start=13 * 60 + 30, band=None, tf=5, filt="none")),
    (F.h46_vwap_reject, dict(und="BANKNIFTY", tf=5, rej="R1", xm="CLOSE")),
    (F.h46_vwap_reject, dict(und="BANKNIFTY", tf=5, rej="R1", xm="TOUCH")),
    (F.trend_hh, dict(n=2, chop=False, exit_rule="trend")),
    (F.trend_hh, dict(n=2, chop=True, exit_rule="fixed")),
    (F.chandelier, dict(tf=15, mode=1)),
    (F.chandelier, dict(tf=5, mode="flip")),
    (F.tas, dict(tf=15, lock=(1.0, 3.0), unds=("BANKNIFTY",))),
    (F.tas, dict(tf=5, lock=None, confirm=3, unds=("BANKNIFTY",))),
    (F.st_ema, dict(tf=5)),
    (F.matrix_premium, dict(dead=True)),
    (F.matrix_v22, dict(tf=5, mode="lock")),
    (F.matrix_v22, dict(tf=5, mode="ribbon")),
]


@pytest.mark.parametrize("fn,params", GUARD, ids=[f"{f.__name__}-{i}" for i, (f, _) in enumerate(GUARD)])
def test_no_lookahead(fn, params):
    vix = FakeVix(DAYS, np.full(D_RAND, 12.0))
    lookahead_check(fn, params, "BANKNIFTY", DAYS, BN, chains_fn=make_chains, vix=vix)


def test_no_lookahead_reversal():
    mk_days, M, chains = reversal_market()
    base = F.reversal(market("BANKNIFTY", mk_days, M, chains=chains), drop=0.003)
    assert len(base) == 1
    r = base.iloc[0]
    M2 = perturb(M, mk_days.index(r.day), int(r.sig_min) - OPEN)
    ch2 = reversal_chains(mk_days, M2["c"])
    new = F.reversal(market("BANKNIFTY", mk_days, M2, chains=ch2), drop=0.003)
    assert rows_of(base)[0] in rows_of(new)


# ------------------------------------------------------------------------------------------------ empty / gap / expiry
ALL_FNS = [F.r20c_break, F.h18_break, F.h43_bakeoff, F.h43_enhanced, F.h46_vwap_reject, F.trend_hh, F.reversal,
           F.chandelier, F.tas, F.st_ema, F.matrix_premium, F.matrix_v22]


@pytest.mark.parametrize("fn", ALL_FNS, ids=[f.__name__ for f in ALL_FNS])
def test_empty_market(fn):
    mk = FakeMk({}, {}, FakeVix([], np.zeros(0)))
    out = fn(mk)
    assert len(out) == 0
    assert {"und", "day", "sig_min", "side"} <= set(out.columns)


def test_specs_are_well_formed():
    from r52rules import spec_rules
    keys = [s["key"] for s in F.SPECS]
    assert len(keys) == len(set(keys))
    want = {"R20C-1", "R20C-1N", "R20C-3", "R20C-4", "H18-1", "H43-2", "H43-3", "H43-5", "H43-6", "H43-7", "H46-2",
            "TREND_FILTER-1", "REVERSAL_SETUP-1", "CH-1", "TAS_OPTIONS-1", "ST-1", "MP-1", "MV-1"}
    assert set(keys) == want
    for s in F.SPECS:
        assert len(s["grid"]) <= 23
        assert s["source"] in ("index", "index + VIX", "index + option-chain context (OI)")
        rs = spec_rules(s)
        assert rs[0].vid == "orig" and rs[0].expiry == "skip"
        assert all(r.exits.sq_off <= 15 * 60 + 15 for r in rs)
        assert len({(r.signal_fn.__name__, repr(sorted(r.params.items())), r.rule, r.exits, r.expiry, repr(r.pos),
                     r.window, r.unds) for r in rs}) == len(rs), f"{s['key']}: duplicate settings in the grid"


def test_boss_pine_settings_kept():
    sp = {s["key"]: s for s in F.SPECS}
    assert sp["ST-1"]["orig"]["params"] == dict(tf=5) and sp["ST-1"]["orig"]["exits"] == dict(sq_off=15 * 60 + 15)
    assert sp["MP-1"]["orig"]["params"] == dict(dead=True)
    assert F.matrix_premium.__defaults__[1:3] == (2.7, 2.5)
    assert sp["CH-1"]["orig"]["params"] == dict(tf=60, mode=1)
    assert sp["R20C-1"]["orig"]["params"]["n"] == 2 and sp["R20C-1"]["orig"]["params"]["tf"] == 5


# ------------------------------------------------------------------------------------------------ R20C
def r20c_market(today_jump_col=None, today_drop_col=None, n=18):
    days = days_from(n)
    c = np.full((n, W), 100.0)
    h, l = c.copy(), c.copy()
    h[:, 10] = 101.0                       # every day: high 101, low 99, close 100 -> R1 101, S1 99
    l[:, 20] = 99.0
    if today_jump_col is not None:
        c[-1, today_jump_col:] = 102.0
        h[-1, today_jump_col:] = 102.0
        if today_drop_col is not None:
            c[-1, today_drop_col:] = 100.0
        h[-1] = np.maximum(h[-1], c[-1])
        l[-1] = np.minimum(l[-1], c[-1])
    o = np.concatenate([c[:, :1], c[:, :-1]], axis=1)
    return days, dict(o=o, h=np.maximum(h, o), l=np.minimum(l, o), c=c)


def test_r20c_fires_on_two_closes_above_r1():
    days, M = r20c_market(today_jump_col=60)
    out = F.r20c_break(market("NIFTY", days, M), unds=("NIFTY",))
    assert len(out) == 1
    r = out.iloc[0]
    assert (r.side, r.sig_min, r.day) == (1, OPEN + 14 * 5 - 1, days[-1])      # candles 12 + 13 close above, row 69
    assert r.tag == "r1"


def test_r20c_n1_fires_one_candle_earlier_and_flat_day_does_not():
    days, M = r20c_market(today_jump_col=60)
    out = F.r20c_break(market("NIFTY", days, M), n=1, unds=("NIFTY",))
    assert list(out.sig_min) == [OPEN + 13 * 5 - 1]
    days, M = r20c_market()
    assert len(F.r20c_break(market("NIFTY", days, M), unds=("NIFTY",))) == 0


def test_r20c_last_entry_and_vix_filter():
    days, M = r20c_market(today_jump_col=60)
    assert len(F.r20c_break(market("NIFTY", days, M), last_entry=(10, 0), unds=("NIFTY",))) == 0   # row 69 > 10:00
    hi = FakeVix(days, np.full(len(days), 14.0))
    lo = FakeVix(days, np.full(len(days), 12.0))
    assert len(F.r20c_break(market("NIFTY", days, M, vix=hi), vix_max=13.0, unds=("NIFTY",))) == 0
    assert len(F.r20c_break(market("NIFTY", days, M, vix=lo), vix_max=13.0, unds=("NIFTY",))) == 1


def test_r20c_liquidity_failed_break_exit():
    days, M = r20c_market(today_jump_col=30, today_drop_col=100)
    out = F.r20c_break(market("NIFTY", days, M), tf=15, n=2, liq_exit=True, unds=("NIFTY",))
    r = out.iloc[0]
    assert r.sig_min == OPEN + 4 * 15 - 1                     # 15-min candles 2 and 3 (from 09:45) close above R1
    assert r.exit_at == OPEN + 105                            # the 15-min candle ending at row 104 closes below


def test_r20c_gap_and_expiry_day():
    days, M = r20c_market(today_jump_col=60)
    M2 = {k: v.copy() for k, v in M.items()}
    for k in M2:
        M2[k][-1, 100:105] = np.nan                           # a 5-minute hole after the signal
    out = F.r20c_break(market("NIFTY", days, M2, exp={days[-1]}), unds=("NIFTY",))
    assert list(out.sig_min) == [OPEN + 69]                   # expiry days are skipped by the engine, not here


# ------------------------------------------------------------------------------------------------ H18
def h18_market(jump=True):
    days = days_from(3)
    c = np.full((3, W), 100.0)
    if jump:
        c[2, 100:] = 100.2
    return days, from_close(c)


def test_h18_break_of_rolling_60min_high():
    days, M = h18_market()
    out = F.h18_break(market("NIFTY", days, M), levels="R60", unds=("NIFTY",))
    assert len(out) == 1
    r = out.iloc[0]
    assert (r.side, r.sig_min, r.day, r.tag) == (1, OPEN + 100, days[2], "R60H")
    assert r.idx_stop == pytest.approx(100.0 - 15.0)
    allv = F.h18_break(market("NIFTY", days, M), levels="ALL", unds=("NIFTY",))
    assert set(allv.tag) >= {"R60H", "PDH", "ORH"}


def test_h18_flat_market_no_break_and_gap():
    days, M = h18_market(jump=False)
    assert len(F.h18_break(market("NIFTY", days, M), unds=("NIFTY",))) == 0
    days, M = h18_market()
    for k in M:
        M[k][2, 200:210] = np.nan
    assert list(F.h18_break(market("NIFTY", days, M), unds=("NIFTY",)).sig_min) == [OPEN + 100]


# ------------------------------------------------------------------------------------------------ H43
def bb_market(jump_col=60):
    days = days_from(2)
    c = np.full((2, W), 100.0)
    c[:, 1::2] = 100.01                                       # tiny noise so the band has a width
    c[1, jump_col:] = 101.0
    return days, from_close(c)


def test_h43_bakeoff_bb_fires_at_the_jump_minute():
    days, M = bb_market()
    out = F.h43_bakeoff(market("BANKNIFTY", days, M), strat="bb", tf=1)
    first = out[out.day == days[1]].iloc[0]
    assert (first.side, first.sig_min) == (1, OPEN + 60)
    days, M = bb_market(jump_col=W)                           # no jump
    flat = F.h43_bakeoff(market("BANKNIFTY", days, M), strat="bb", tf=1)
    assert not len(flat[flat.day == days[1]])


def test_h43_bakeoff_pe_only_after_14_and_puts_only():
    out = F.h43_bakeoff(market("BANKNIFTY", DAYS, BN), strat="pe", tf=5)
    assert len(out) and (out.side == -1).all() and (out.sig_min >= 14 * 60).all() and (out.sig_min <= 913).all()


def test_h43_enhanced_bb_and_filters():
    days, M = bb_market()
    mk = market("BANKNIFTY", days, M, chains=make_chains(days, M["c"]))
    base = F.h43_enhanced(mk, und="BANKNIFTY", fam="bb", n=20, k=2.5, tf=1)
    first = base[base.day == days[1]].iloc[0]
    assert (first.side, first.sig_min) == (1, OPEN + 60)
    am = F.h43_enhanced(mk, und="BANKNIFTY", fam="bb", n=20, k=2.5, tf=1, filt="am")
    pm = F.h43_enhanced(mk, und="BANKNIFTY", fam="bb", n=20, k=2.5, tf=1, filt="pm")
    assert (OPEN + 60) in set(am.sig_min) and (OPEN + 60) not in set(pm.sig_min)
    oi = F.h43_enhanced(mk, und="BANKNIFTY", fam="bb", n=20, k=2.5, tf=1, filt="oi")   # PE OI rises with the index
    assert (OPEN + 60) in set(oi[oi.side == 1].sig_min)


def test_h43_enhanced_oi_filter_rejects_disagreeing_build_up():
    days, M = bb_market()
    ch = make_chains(days, M["c"])
    for x in ch.values():                                     # CE OI now builds with the rise: disagrees with a CE
        x.oi["C"], x.oi["P"] = x.oi["P"], x.oi["C"]
    mk = market("BANKNIFTY", days, M, chains=ch)
    oi = F.h43_enhanced(mk, und="BANKNIFTY", fam="bb", n=20, k=2.5, tf=1, filt="oi")
    assert (OPEN + 60) not in set(oi[oi.side == 1].sig_min)


def test_h43_enhanced_pe_family_every_bar_from_start():
    days, M = bb_market(jump_col=W)
    out = F.h43_enhanced(market("BANKNIFTY", days, M), und="BANKNIFTY", fam="pe", start=13 * 60 + 30, band=None, tf=5)
    want = [OPEN + k * 5 + 4 for k in range(75) if 13 * 60 + 30 <= OPEN + k * 5 + 4 <= 15 * 60 + 13]
    assert list(out[out.day == days[1]].sig_min) == want and (out.side == -1).all()


def test_h43_enhanced_ema_cross_up():
    days = days_from(3)
    c = np.full((3, W), 100.0)
    c[2, :60] = 100.0 - 0.02 * np.arange(60)
    c[2, 60:] = c[2, 59] + 0.2 * np.arange(1, W - 59)
    M = from_close(c)
    out = F.h43_enhanced(market("BANKNIFTY", days, M), und="BANKNIFTY", fam="ema", f=5, s=21, thr=0, tf=5)
    up = out[(out.day == days[2]) & (out.side == 1)]
    assert len(up) == 1 and 60 <= up.iloc[0].sig_min - OPEN <= 100


def test_h43_enhanced_vix_high_filter():
    days = days_from(70)
    c = walk(70, seed=8)
    M = from_close(c, wick=3.0)
    # previous close vs the trailing (<= 250-day) median: days 60-68 see 10 (low), the last day sees 20 (high); the
    # first 60 sessions have no filter value (partD: j >= 60). A close equal to the median counts as 'high' (partD).
    v_hi = FakeVix(days, np.r_[np.full(59, 15.0), np.full(9, 10.0), 20.0, 20.0])
    mk = market("BANKNIFTY", days, M, vix=v_hi)
    out = F.h43_enhanced(mk, und="BANKNIFTY", fam="pe", start=13 * 60, band=None, tf=5, filt="vix_high")
    assert set(out.day) == {days[-1]}
    low = F.h43_enhanced(mk, und="BANKNIFTY", fam="pe", start=13 * 60, band=None, tf=5, filt="vix_low")
    assert set(low.day) == set(days[60:69])


# ------------------------------------------------------------------------------------------------ H46
def h46_market(reject=True):
    days = days_from(3)
    c = np.full((3, W), 110.0)
    o = c.copy()
    h, l = c.copy(), c.copy()
    c[2] = 100.0
    o[2], h[2], l[2] = 100.0, 100.0, 100.0
    if reject:                                                # candle 4 (cols 60-74): open 100.5, wick to 100, close 100.6
        o[2, 60:75], c[2, 60:75], h[2, 60:75] = 100.5, 100.6, 100.6
        l[2, 60] = 100.0
        l[2, 61:75] = 100.5
    return days, dict(o=o, h=h, l=l, c=c)


def test_h46_reject_off_twap_fires_and_close_exit():
    days, M = h46_market()
    out = F.h46_vwap_reject(market("MIDCPNIFTY", days, M), und="MIDCPNIFTY", tf=15)
    out = out[out.day == days[2]]
    assert len(out) == 1
    r = out.iloc[0]
    assert (r.side, r.sig_min) == (1, OPEN + 74)
    assert r.exit_at == OPEN + 90                             # the next candle closes below the (higher) EMA9


def test_h46_no_reject_no_signal():
    days, M = h46_market(reject=False)
    out = F.h46_vwap_reject(market("MIDCPNIFTY", days, M), und="MIDCPNIFTY", tf=15)
    assert not len(out[out.day == days[2]])


# ------------------------------------------------------------------------------------------------ TREND_FILTER
def trend_market(rise=True):
    days = days_from(3)
    c = np.full((3, W), 100.0)
    if rise:
        c[2] = 100.0 + 0.1 * np.arange(1, W + 1)
    return days, from_close(c)


def test_trend_filter_fires_first_hh_bar_from_0930():
    days, M = trend_market()
    out = F.trend_hh(market("BANKNIFTY", days, M), n=3, chop=True)
    r = out.iloc[0]
    assert (r.side, r.sig_min, r.day) == (1, OPEN + 15 + 4, days[2])
    assert r.ref_spot == pytest.approx(M["c"][2, 9])
    assert (out.sig_min <= OPEN + 310 + 4).all()


def test_trend_filter_flat_day_and_trend_exit():
    days, M = trend_market(rise=False)
    assert len(F.trend_hh(market("BANKNIFTY", days, M))) == 0
    days, M = trend_market()
    out = F.trend_hh(market("BANKNIFTY", days, M), exit_rule="trend")
    assert out.iloc[0].exit_at == OPEN + 350 + 5               # never closes under the EMA: out after the 15:05 bar


# ------------------------------------------------------------------------------------------------ REVERSAL_SETUP
def reversal_chains(days, c):
    out = {}
    for i, d in enumerate(days):
        K = np.arange(95.0, 106.0)
        spot = c[i][None, :]
        ce = np.maximum(spot - K[:, None], 0) + 400.0 + 10 * (K[:, None] < 99)   # ITM calls dearer
        pe = np.maximum(K[:, None] - spot, 0) + 400.0
        out[d] = FakeChain(K, dict(C=ce, P=pe), dict(C=np.ones_like(ce), P=np.ones_like(pe)))
    return out


def reversal_market():
    days = days_from(2)
    c = np.full((2, W), 100.0)
    c[1, 20:] = 99.0
    M = from_close(c)
    M["l"][1, 22] = 98.9
    M["o"][1, 40] = 98.95
    M["l"][1, 40] = 98.95
    return days, M, reversal_chains(days, c)


def test_reversal_fires_on_stalled_fall_green_candle():
    days, M, ch = reversal_market()
    out = F.reversal(market("BANKNIFTY", days, M, chains=ch), drop=0.003)
    assert len(out) == 1
    r = out.iloc[0]
    assert (r.side, r.sig_min, r.day, r.strike) == (1, OPEN + 44, days[1], 99.0)


def test_reversal_capital_and_no_setup():
    days, M, ch = reversal_market()
    mk = market("BANKNIFTY", days, M, chains=ch)
    tight = F.reversal(mk, drop=0.003, capital=(("BANKNIFTY", 400.0 * 30 + 1),))
    assert list(tight.strike) == [99.0]                        # 99 CE costs 400 x 30
    none = F.reversal(mk, drop=0.003, capital=(("BANKNIFTY", 1000.0),))
    assert len(none) == 0                                     # nothing fits Rs 1,000
    assert len(F.reversal(mk, drop=0.02)) == 0                # a 1% fall is not a 2% move
    c = np.full((2, W), 100.0)
    assert len(F.reversal(market("BANKNIFTY", days, from_close(c), chains=ch))) == 0


# ------------------------------------------------------------------------------------------------ CH-1
def chandelier_market():
    days = days_from(4)
    c = np.full((4, W), 100.0)
    c[3, 60:180] = 99.0
    c[3, 180:] = 101.0
    return days, from_close(c)


def test_chandelier_flips_and_opposite_exit():
    days, M = chandelier_market()
    out = F.chandelier(market("BANKNIFTY", days, M), tf=60, mode=1)
    out = out[out.day == days[3]].reset_index(drop=True)
    assert list(zip(out.side, out.sig_min)) == [(-1, OPEN + 119), (1, OPEN + 239)]
    assert out.exit_at.iloc[0] == OPEN + 240                  # the BUY flip closes the PE at its next bar's open
    assert out.idx_stop.iloc[0] > 99.0 and out.idx_target.iloc[0] < 99.0
    flip = F.chandelier(market("BANKNIFTY", days, M), tf=60, mode="flip")
    assert flip.idx_stop.isna().all()


def test_chandelier_flat_no_signal():
    days = days_from(4)
    assert len(F.chandelier(market("BANKNIFTY", days, from_close(np.full((4, W), 100.0))), tf=60)) == 0


# ------------------------------------------------------------------------------------------------ TAS
def test_tas_signals_follow_the_tracker_turn():
    GT = F._mod("gold_tas", "gold_tas.py")
    D = 30
    days = days_from(D)
    drift = np.r_[np.full(15, -1.0), np.full(15, 1.0)]
    M = from_close(walk(D, p0=24000, sig=3.0, seed=4, drift=drift), wick=1.0)
    mk = market("NIFTY", days, M)
    out = F.tas(mk, tf=30, lock=(1.0, 3.0))
    assert len(out) and set(out.side) <= {1, -1}
    b = F._bars(F.day_dicts(mk.index("NIFTY")), 30).rename(columns=dict(o="open", h="high", l="low", c="close"))
    trk, d, pct = GT.indicators(b, 19)
    key = {(days[int(b.di.iat[i])], int(OPEN + b.s.iat[i + 1] - 1)): i for i in range(len(b) - 1)
           if b.di.iat[i + 1] == b.di.iat[i]}
    for r in out.itertuples():
        i = key[(r.day, r.sig_min)]
        assert d[i] == r.side and r.side * pct[i] >= 50
        assert any(d[k] == r.side and d[k - 1] != r.side for k in range(max(1, i - 10), i + 1))
    assert (out.sig_min <= OPEN + 315 - 1).all()


def test_tas_flat_market_no_signal():
    days = days_from(10)
    assert len(F.tas(market("NIFTY", days, from_close(np.full((10, W), 24000.0))), tf=30)) == 0


# ------------------------------------------------------------------------------------------------ ST-1
def test_st_ema_flip_below_ema50():
    ST = F._mod("st_ema_pine", "st_ema_pine.py")
    days = days_from(4)
    c = np.full((4, W), 100.0)
    c[:, 1::2] = 100.2
    c[3, 100:] = 98.0
    M = from_close(c)
    out = F.st_ema(market("BANKNIFTY", days, M), tf=5)
    today = out[out.day == days[3]]
    assert len(today) == 1
    r = today.iloc[0]
    assert r.side == -1 and r.sig_min == OPEN + 104            # the 5-min bar holding minute 100 closes below
    b = F._bars(F.day_dicts(market("BANKNIFTY", days, M).index("BANKNIFTY")), 5)
    dirn = ST.supertrend(b)
    i = int(np.nonzero((b.di.values == 3) & (b.s.values == 100))[0][0])
    assert dirn[i] == 1 and dirn[i - 1] == -1                  # the script's bearish flip (Pine: +1 = down)
    flat = F.st_ema(market("BANKNIFTY", days, from_close(np.full((4, W), 100.0))), tf=5)
    assert len(flat) == 0


def test_st_ema_reverse_sets_exit():
    out = F.st_ema(market("BANKNIFTY", DAYS, BN), tf=5)
    assert len(out)
    for (u, d), g in out.groupby(["und", "day"]):
        g = g.sort_values("sig_min")
        assert (np.diff(g.side.values) != 0).all()            # entries alternate within a day
        x = g.exit_at.values[:-1]
        assert np.allclose(x[np.isfinite(x)], (g.sig_min.values[1:] + 1)[np.isfinite(x)])
    assert (out.exit_at.dropna() < 15 * 60 + 15).all()


# ------------------------------------------------------------------------------------------------ MP-1
def mp_market(col):
    days = days_from(3)
    c = np.full((3, W), 100.0)
    c[2, col:] = 101.0
    return days, from_close(c)


def test_matrix_premium_cross_and_levels():
    days, M = mp_market(60)
    mk = market("BANKNIFTY", days, M)
    out = F.matrix_premium(mk)
    r = out[out.day == days[2]].iloc[0]
    assert (r.side, r.sig_min) == (1, OPEN + 64)
    b = F._bars(F.day_dicts(mk.index("BANKNIFTY")), 5)
    i = int(np.nonzero((b.di.values == 2) & (b.s.values == 60))[0][0])
    risk = 2.7 * b.atr.iat[i]
    assert r.idx_stop == pytest.approx(101.0 - risk) and r.idx_target == pytest.approx(101.0 + 2.5 * risk)


def test_matrix_premium_dead_zone():
    days, M = mp_market(11 * 60 + 30 - OPEN)
    assert len(F.matrix_premium(market("BANKNIFTY", days, M), dead=True)) == 0
    assert len(F.matrix_premium(market("BANKNIFTY", days, M), dead=False)) == 1
    days, M = mp_market(W)
    assert len(F.matrix_premium(market("BANKNIFTY", days, M))) == 0


# ------------------------------------------------------------------------------------------------ MV-1
def mv_market(breakout=True):
    days = days_from(3)
    c = np.full((3, W), 100.0)
    M = from_close(c)
    M["h"][2, 25:30] = 101.0                                   # 5-min bar 5: a swing high at 101, close 100
    if breakout:
        M["c"][2, 60:] = 102.0                                 # bar 12 closes above it
        M["o"][2, 61:] = 102.0
        M["h"][2, 60:] = 102.0
    return days, M


def test_matrix_v22_bos_entry_two_halves():
    days, M = mv_market()
    out = F.matrix_v22(market("BANKNIFTY", days, M), tf=5)
    out = out[out.day == days[2]]
    assert len(out) == 2
    assert set(out.book) == {"mv_BANKNIFTY_A", "mv_BANKNIFTY_B"}
    assert (out.side == 1).all() and (out.sig_min == OPEN + 64).all()
    assert out.exit_at.isna().all()                            # nothing hit: both halves at 15:10


def test_matrix_v22_no_break_and_walk_is_flat_between_trades():
    days, M = mv_market(breakout=False)
    assert len(F.matrix_v22(market("BANKNIFTY", days, M), tf=5)) == 0
    out = F.matrix_v22(market("BANKNIFTY", DAYS, BN), tf=5)
    assert len(out) % 2 == 0
    for d, g in out.groupby("day"):
        a = g[g.book.str.endswith("_A")].sort_values("sig_min")
        bb = g[g.book.str.endswith("_B")].sort_values("sig_min")
        ex = bb.exit_at.fillna(15 * 60 + 10).values
        assert (a.sig_min.values[1:] + 1 >= ex[:-1]).all()     # the next entry fills at/after the previous exit
