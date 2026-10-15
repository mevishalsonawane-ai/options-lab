"""Tests for fam_r4 (R4 Gann / Fibonacci / magic / harmonics and R5-1 Pine adapters). Synthetic markets only, no lake.

    cd research/hunt/r52 && python -m pytest -q test_fam_r4.py
"""
from __future__ import annotations

import math
import warnings
from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

import r52cfg  # noqa: F401  (paths: research/, research/hunt/r52)
import fam_r4 as F

W = 375
OPEN = 9 * 60 + 15


# ------------------------------------------------------------------------------------------------ fake market
class FakeIx:
    def __init__(self, days, M, exp=None):
        self.days = list(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        self.M = M
        exp = exp or set()
        self.d = {d: dict(exp=d in exp, real=True, lot=75) for d in self.days}

    def mat(self):
        return self.M

    def daily(self):
        rows = []
        for i, d in enumerate(self.days):
            o, h, l, c = (self.M[k][i] for k in "ohlc")
            ok = ~np.isnan(c)
            rows.append((d, o[ok][0], np.nanmax(h), np.nanmin(l), c[ok][-1]))
        return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close"]).set_index("day")


class FakeMk:
    def __init__(self, ixs):
        self.ixs = ixs

    def index(self, u):
        return self.ixs[u]


def weekdays(start, n):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def from_closes(C, wick=0.0):
    """o = previous minute's close (first minute: own close), h / l = max / min(o, c) +- wick."""
    C = np.asarray(C, float)
    O = np.empty_like(C)
    O[:, 0] = C[:, 0]
    O[:, 1:] = C[:, :-1]
    with np.errstate(invalid="ignore"):
        H = np.fmax(O, C) + wick
        L = np.fmin(O, C) - wick
    return dict(o=O, h=H, l=L, c=C)


def mk1(days, M, u="NIFTY", exp=None):
    return FakeMk({u: FakeIx(days, M, exp)})


def random_market(n_days=45, seed=1, start=10000.0, vol=0.0007):
    rng = np.random.default_rng(seed)
    r = rng.standard_normal((n_days, W)) * vol
    r[:, 0] += rng.standard_normal(n_days) * 0.004            # overnight gaps
    C = start * np.exp(np.cumsum(r.ravel())).reshape(n_days, W)
    M = from_closes(C)
    M["h"] = M["h"] + np.abs(rng.standard_normal((n_days, W))) * start * 0.0002
    M["l"] = M["l"] - np.abs(rng.standard_normal((n_days, W))) * start * 0.0002
    return M


# ------------------------------------------------------------------------------------------------ F1 Fibonacci pivots
def f1_market(touch=True, exp_day=False):
    days = weekdays(date(2025, 2, 3), 2)
    C = np.full((2, W), 100.0)
    C[0, 50:100] = 110.0
    C[0, 150:200] = 90.0                     # prev day H 110, L 90, C 100 -> P 100, R1 107.64, R2 112.36
    M = from_closes(C)
    if touch:
        C1 = np.full(W, 100.0)
        C1[50:54] = 104.0
        C1[54] = 106.0                       # 5-min bar 10 (cols 50-54): high 108 > R1, close 106 < R1
        C1[55:] = 105.0
        M1 = from_closes(C1[None])
        M1["h"][0, 52] = 108.0
        for k in "ohlc":
            M[k][1] = M1[k][0]
    return mk1(days, M, exp={days[1]} if exp_day else None), days


def test_f1_bounce_fires_once_with_levels():
    mk, days = f1_market()
    df = F.r4_rule(mk, "F1_FIBPIV|bnc|NAT", unds=("NIFTY",))
    assert len(df) == 1
    r = df.iloc[0]
    assert (r.day, r.sig_min, r.side) == (days[1], OPEN + 54, -1)
    piv, rg = 100.0, 20.0
    assert r.idx_stop == pytest.approx(piv + 0.618 * rg) and r.idx_target == pytest.approx(piv)
    opt = F.r4_rule(mk, "F1_FIBPIV|bnc|OPT", unds=("NIFTY",))
    assert len(opt) == 1 and np.isnan(opt.idx_stop.iloc[0]) and np.isnan(opt.exit_at.iloc[0])
    t30 = F.r4_rule(mk, "F1_FIBPIV|bnc|T30", unds=("NIFTY",))
    assert t30.exit_at.iloc[0] == OPEN + 54 + 31
    assert len(F.r4_rule(mk, "F1_FIBPIV|brk|OPT", unds=("NIFTY",))) == 0


def test_f1_no_touch_no_signal():
    mk, _ = f1_market(touch=False)
    assert len(F.r4_rule(mk, "F1_FIBPIV|bnc|OPT", unds=("NIFTY",))) == 0


def test_expiry_day_still_signals():
    """R4 traded expiry days ('as r1'): the rule keeps them, the spec uses expiry='allow'."""
    mk, days = f1_market(exp_day=True)
    df = F.r4_rule(mk, "F1_FIBPIV|bnc|OPT", unds=("NIFTY",))
    assert list(df.day) == [days[1]]
    spec = {s["key"]: s for s in F.SPECS}["R4-1"]
    assert spec["orig"]["expiry"] == "allow"


def test_per_index_var_and_missing_index_variant():
    mk, _ = f1_market()
    df = F.r4_rule(mk, (("NIFTY", "F1_FIBPIV|bnc|OPT"),), unds=("NIFTY",))
    assert len(df) == 1 and df.book.iloc[0] == "r4_NIFTY"
    assert len(F.r4_rule(mk, (("BANKNIFTY", "F1_FIBPIV|bnc|OPT"),), unds=("NIFTY",))) == 0


# ------------------------------------------------------------------------------------------------ M1 square of 9
def test_sq9_calculator_buy_above():
    days = weekdays(date(2025, 2, 3), 1)
    C = np.full((1, W), 10010.0)
    C[0, 100:] = 10030.0                     # ref 09:25 = 10010 -> buy_above (100.125)^2 = 10025.02, sell 10000
    mk = mk1(days, from_closes(C))
    df = F.r4_rule(mk, "M1_SQ9I|r0925|NATk3", unds=("NIFTY",))
    assert len(df) == 1
    r = df.iloc[0]
    assert (r.sig_min, r.side) == (OPEN + 100, 1)
    assert r.idx_stop == pytest.approx(10000.0)
    assert r.idx_target == pytest.approx((98 + 0.125 * 20) ** 2 * 0.9995)


def test_sq9_calculator_quiet_day():
    days = weekdays(date(2025, 2, 3), 1)
    mk = mk1(days, from_closes(np.full((1, W), 10010.0)))
    assert len(F.r4_rule(mk, "M1_SQ9I|r0925|NATk3", unds=("NIFTY",))) == 0


# ------------------------------------------------------------------------------------------------ G7 seasonal
def seas_market(days, falling=True):
    n = len(days)
    lv = np.linspace(11000, 10000, n) if falling else np.linspace(10000, 11000, n)
    return mk1(days, from_closes(np.repeat(lv[:, None], W, axis=1)))


def test_seasonal_date_fades_5day_move():
    days = weekdays(date(2025, 3, 10), 10)          # ends Fri 21 Mar 2025 (a Gann date), index 9
    df = F.r4_rule(seas_market(days), "G7_SEAS|fade5d|OPT", unds=("NIFTY",))
    assert list(zip(df.day, df.sig_min, df.side)) == [(date(2025, 3, 21), OPEN, 1)]
    up = F.r4_rule(seas_market(days, falling=False), "G7_SEAS|fade5d|OPT", unds=("NIFTY",))
    assert list(up.side) == [-1]


def test_seasonal_no_date_no_signal():
    days = weekdays(date(2025, 4, 1), 10)
    assert len(F.r4_rule(seas_market(days), "G7_SEAS|fade5d|OPT", unds=("NIFTY",))) == 0


# ------------------------------------------------------------------------------------------------ G5 Gann angle
def g5_market(with_prev=True):
    days = weekdays(date(2025, 2, 3), 2)
    C = np.full((2, W), 100.5)
    C[0, 10] = 175.5                          # prev day range 75 (100.5 .. 175.5) -> 2x1 unit 2 points a 5-min bar
    M = from_closes(C)
    M["h"][1] = 100.5
    M["l"][1] = 100.5
    M["l"][1, 0] = 100.0                      # opening-range low at bar 0
    M["h"][1, 27] = 101.0                     # opening-range high at bar 5
    if not with_prev:
        M = {k: v[1:] for k, v in M.items()}
        days = days[1:]
    return mk1(days, M), days


def test_gann_2x1_close_above_falling_line():
    mk, days = g5_market()
    df = F.r4_rule(mk, "G5_ANG|2x1|NAT", unds=("NIFTY",))
    assert list(zip(df.day, df.sig_min, df.side)) == [(days[1], OPEN + 34, 1)]
    assert np.isnan(df.exit_at.iloc[0])       # never closes back below the line: 15:10 square-off


def test_gann_angle_needs_previous_day():
    mk, _ = g5_market(with_prev=False)
    assert len(F.r4_rule(mk, "G5_ANG|2x1|NAT", unds=("NIFTY",))) == 0


# ------------------------------------------------------------------------------------------------ edge cases
def test_empty_market():
    mk = mk1([], {k: np.zeros((0, W)) for k in "ohlc"})
    for s in F.SPECS:
        o = s["orig"]
        p = dict(o["params"], unds=("NIFTY",))
        df = o["fn"](mk, **p)
        assert len(df) == 0 and {"und", "day", "sig_min", "side"} <= set(df.columns)


def test_gap_day_runs_and_never_signals_inside_window_gap():
    M = random_market(30, seed=4)
    for k in "ohlc":
        M[k][10, 100:160] = np.nan            # a 60-minute hole (16% of the day): the day stays tradable
    days = weekdays(date(2025, 1, 1), 30)
    mk = mk1(days, M)
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        for s in F.SPECS:
            p = dict(s["orig"]["params"], unds=("NIFTY",))
            if "var" in p and not isinstance(p["var"], str):
                p["var"] = dict(p["var"]).get("NIFTY") or next(iter(dict(p["var"]).values()))
            df = s["orig"]["fn"](mk, **p)
            assert df.sig_min.between(OPEN, OPEN + W - 1).all()


# ------------------------------------------------------------------------------------------------ parity with R4's own request object
@pytest.mark.parametrize("bname,tf", [("prevday", 0), ("magic", 0), ("gann_intraday", 0), ("gann_swing", 15),
                                      ("fib_swings", 3), ("fib_swings", 15), ("harmonics", 15)])
def test_recorder_matches_original_request_object(bname, tf):
    """The recorder sees exactly the (variant, day, minute, side) requests R4's own lib4.SReq receives."""
    days = weekdays(date(2025, 1, 1), 40)
    mk = mk1(days, random_market(40, seed=7))
    P = F.panel(mk, "NIFTY")
    mine = F._requests(mk, "NIFTY", P, bname, tf)
    R = F.L4.SReq(P)
    okd = P["ok"].copy()
    if bname in ("prevday", "magic", "gann_intraday"):
        for di in np.nonzero(P["ok"])[0]:
            getattr(F.S4, bname)(R, P, di, F.S4.bars5_day(P, di))
    else:
        B = F._bars(P, tf)
        if bname == "gann_swing":
            F.S4.gann_swing(R, P, B, tf, okd)
        else:
            getattr(F.S4, bname)(R, P, B, tf, okd, F.S4.piv(B, 2))
    orig = R.frame()
    assert len(orig) == len(mine)
    assert sorted(zip(orig["var"], orig.di, orig.s, orig.side)) == sorted((v, d, s, sd) for v, d, s, sd, _ in mine)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _all_settings():
    """(fn, params) of every orig and grid setting, on NIFTY only (per-index vars resolved to NIFTY's or the first)."""
    import r52rules as RR
    out, seen = [], set()
    for s in F.SPECS:
        for r in RR.spec_rules(s):
            p = dict(r.params, unds=("NIFTY",))
            if "var" in p and not isinstance(p["var"], str):
                p["var"] = dict(p["var"]).get("NIFTY") or next(iter(dict(p["var"]).values()))
            k = (r.signal_fn.__name__, repr(sorted(p.items())))
            if k not in seen:
                seen.add(k)
                out.append((s["key"], r.signal_fn, p))
    return out


SETTINGS = _all_settings()
KEYCOLS = ["und", "day", "sig_min", "side", "idx_stop", "idx_target"]


def _norm(df):
    return df[KEYCOLS].round(6).fillna(-1.0).sort_values(KEYCOLS).reset_index(drop=True)


def test_look_ahead_guard_every_setting():
    days = weekdays(date(2025, 1, 1), 50)
    base = random_market(50, seed=11)
    rng = np.random.default_rng(3)
    fired = 0
    for key, fn, p in SETTINGS:
        df = fn(mk1(days, base), **p)
        if not len(df):
            continue
        fired += 1
        picks = df.iloc[rng.choice(len(df), min(3, len(df)), replace=False)]
        for r in picks.itertuples():
            di, col = days.index(r.day), r.sig_min - OPEN
            M = {k: v.copy() for k, v in base.items()}
            shock = np.zeros((len(days), W))
            shock[di, col + 1:] = 1.0
            shock[di + 1:, :] = 1.0
            noise = np.cumsum(rng.standard_normal((len(days), W)), axis=1) * 15.0 * shock
            for k in "ohlc":
                M[k] = M[k] + noise
            df2 = fn(mk1(days, M), **p)
            before = lambda x: x[(x.day < r.day) | ((x.day == r.day) & (x.sig_min <= r.sig_min))]  # noqa: E731
            a, b = _norm(before(df)), _norm(before(df2))
            assert a.equals(b), f"{key} {p}: signals up to {r.day} {r.sig_min} changed after a future-only change"
    assert fired >= len(SETTINGS) // 2, f"only {fired}/{len(SETTINGS)} settings fired on the random market"


# ------------------------------------------------------------------------------------------------ specs
def test_specs_shape():
    import r52rules as RR
    keys = [s["key"] for s in F.SPECS]
    assert keys == ["R4-1", "R4-2", "R4-3", "R4-4", "R4-6", "R4-8", "R4-10", "R4-13", "R4-14", "R4-16", "R4-18",
                    "R5-1"]
    for s in F.SPECS:
        assert s["source"] == "index"
        assert len(s["grid"]) <= 23
        rs = RR.spec_rules(s)
        assert rs[0].vid == "orig" and rs[0].rule.money == 1 and rs[0].expiry == "allow"
        assert rs[0].pos == dict(one_at_a_time=True, max_per_day=3)
        for r in rs:
            v = r.params.get("var")
            if v is not None:                 # one exit kind per setting: OPT <-> premium exits
                vs = [v] if isinstance(v, str) else [x for _, x in v]
                kinds = {x.endswith("|OPT") for x in vs}
                assert len(kinds) == 1
                assert (r.exits.stop_pct == 0.25) == kinds.pop()
            assert r.exits.sq_off == 15 * 60 + 10
            assert set(r.unds) == set(r.params["unds"])


# ------------------------------------------------------------------------------------------------ R5-1 Pine adapter
def test_r5_adapter_maps_script_entries(monkeypatch):
    """Entries in 09:20-14:45 on ok days only; long -> CE, short -> PE; NAT = the script's stop / TP1 at entry, plus
    its same-day reversal close (exit at the next minute's open); OPT carries no index levels."""
    days = weekdays(date(2025, 2, 3), 2)
    mk = mk1(days, from_closes(np.full((2, W), 10000.0)))
    T = pd.DataFrame([
        dict(ecol=59, edpos=1, side=1, sl=9950.0, tp=10090.0, why="REV", xdpos=1, xcol=119),   # 10:14 CE, reversed 11:14
        dict(ecol=344, edpos=1, side=-1, sl=10050.0, tp=9910.0, why="SL", xdpos=1, xcol=359),  # 15:00: after 14:45, dropped
        dict(ecol=29, edpos=0, side=-1, sl=10050.0, tp=9910.0, why="REV", xdpos=1, xcol=14),   # 09:44 PE, reversed next day
        dict(ecol=4, edpos=0, side=1, sl=9950.0, tp=10090.0, why="TP", xdpos=0, xcol=9),       # 09:19: before window
    ])
    monkeypatch.setattr(F.P5, "simulate", lambda B, piv, ms, cd, cfg: (T, {}))
    df = F.r5_pine(mk, cfg="PRAG", tf=15, unds=("NIFTY",))
    assert list(zip(df.day, df.sig_min, df.side)) == [(days[1], OPEN + 59, 1), (days[0], OPEN + 29, -1)]
    assert (df.idx_stop.iloc[0], df.idx_target.iloc[0], df.exit_at.iloc[0]) == (9950.0, 10090.0, OPEN + 120)
    assert np.isnan(df.exit_at.iloc[1])
    opt = F.r5_pine(mk, cfg="PRAG", tf=15, exit="OPT", unds=("NIFTY",))
    assert len(opt) == 2 and opt.idx_stop.isna().all() and opt.exit_at.isna().all()


def test_r5_flat_market_no_signal():
    days = weekdays(date(2025, 2, 3), 20)
    assert len(F.r5_pine(mk1(days, from_closes(np.full((20, W), 10000.0))), cfg="PRAG", unds=("NIFTY",))) == 0


def test_r5_look_ahead_guard_loose_script_settings():
    """The default script almost never fires on a short synthetic market, so the guard runs the same code with loose
    pivots (2/2, no swing filter, no cooldown) on 15m and 60m."""
    days = weekdays(date(2025, 1, 1), 50)
    base = random_market(50, seed=2, vol=0.0012)    # seed with PRAG signals on 15m and 60m
    rng = np.random.default_rng(5)
    fired = 0
    for cfg in ("AS_WRITTEN", "PRAG", "CONFL"):
        for tf in (15, 60):
            p = dict(cfg=cfg, tf=tf, piv=2, ms=0.0, cd=0, exit="NAT", unds=("NIFTY",))
            df = F.r5_pine(mk1(days, base), **p)
            fired += len(df) > 0 and cfg == "PRAG"
            for r in (df.iloc[rng.choice(len(df), min(3, len(df)), replace=False)].itertuples() if len(df) else []):
                di, col = days.index(r.day), r.sig_min - OPEN
                shock = np.zeros((len(days), W))
                shock[di, col + 1:] = 1.0
                shock[di + 1:, :] = 1.0
                noise = np.cumsum(rng.standard_normal((len(days), W)), axis=1) * 15.0 * shock
                M = {k: v + noise for k, v in base.items()}
                df2 = F.r5_pine(mk1(days, M), **p)
                cut = lambda x: x[(x.day < r.day) | ((x.day == r.day) & (x.sig_min <= r.sig_min))]  # noqa: E731
                assert _norm(cut(df)).equals(_norm(cut(df2))), f"R5 {cfg} tf{tf}: changed by future bars"
    assert fired >= 1
