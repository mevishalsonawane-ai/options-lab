"""Tests for fam_liq.py (R52 Liquidity family): synthetic markets only, no lake needed.

    cd research/hunt/r52 && python -m pytest -q test_fam_liq.py
"""
from __future__ import annotations

import importlib.util
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

import fam_liq as F  # noqa: E402

W = 375
D0 = date(2025, 1, 6)


# ------------------------------------------------------------------------------------------------ fake market
class FakeIndex:
    def __init__(self, days, M, exp=None):
        self.days = list(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        self._M = {k: np.asarray(v, float).reshape(len(self.days), W) for k, v in M.items()}
        exp = exp or set()
        self.d = {}
        for i, d in enumerate(self.days):
            ok = ~np.isnan(self._M["c"][i])
            m = np.nonzero(ok)[0] + F.OPEN_M
            self.d[d] = dict(m=m, o=self._M["o"][i][ok], h=self._M["h"][i][ok], l=self._M["l"][i][ok],
                             c=self._M["c"][i][ok], lot=75, exp=d in exp, real=True)

    def mat(self):
        return self._M

    def daily(self):
        rows = [(d, x["o"][0], x["h"].max(), x["l"].min(), x["c"][-1], x["lot"], x["exp"], x["real"])
                for d, x in self.d.items() if len(x["c"])]
        return pd.DataFrame(rows, columns=["day", "open", "high", "low", "close", "lot", "exp", "real"]).set_index("day")


class FakeMk:
    def __init__(self, ixs):
        self.ixs = ixs

    def index(self, u):
        return self.ixs[u]


def sessions(n, start=D0):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def from_closes(c):
    """Continuous candles: open = previous close, no wicks (high/low = body). c: (ndays, 375)."""
    c = np.asarray(c, float)
    flat = c.reshape(-1)
    o = np.r_[flat[0], flat[:-1]].reshape(c.shape)
    return dict(o=o, h=np.maximum(o, c), l=np.minimum(o, c), c=c.copy())


def walk(ndays, seed, start=20000.0, vol=4.0):
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, ndays * W))
    M = from_closes(c.reshape(ndays, W))
    wick = np.abs(rng.normal(0, vol / 2, (2, ndays, W)))
    M["h"] = M["h"] + wick[0]
    M["l"] = M["l"] - wick[1]
    return M


def mk_of(M, und="BANKNIFTY", days=None, exp=None):
    n = M["c"].shape[0]
    days = days or sessions(n)
    return FakeMk({und: FakeIndex(days, M, exp)})


def scenario(jump=106.0, extra_days=0, steep=0.01):
    """Day 1: a slow fall (no levels). Day 2: a spike high 105 at minute 50 (a swing high, lookback 20), a second
    touch of its wick at minute 60 (a pool: 2 contacts, 10 bars apart, confirmed at 70), then a close at `jump` at
    minute 90 (a break of both when > 105), then a fall of `steep` a minute. Later days keep falling slowly."""
    nd = 2 + extra_days
    c = np.zeros((nd, W))
    c[0] = 101.5 - 0.002 * np.arange(W)
    c[1] = c[0, -1] - 0.002 * (1 + np.arange(W))
    c[1, 90:] = jump - steep * np.arange(W - 90)
    for k in range(2, nd):
        c[k] = c[k - 1, -1] - 0.002 * (1 + np.arange(W))
    M = from_closes(c)
    M["h"][1, 50] = 105.0
    M["h"][1, 60] = M["o"][1, 50] + 0.25
    return M


def same(a, b):
    return (a == b) or (isinstance(a, float) and isinstance(b, float) and np.isnan(a) and np.isnan(b))


def key_cols(df, cols=("und", "day", "sig_min", "side", "idx_stop", "idx_target")):
    return [tuple(r) for r in df[list(c for c in cols if c in df.columns)].itertuples(index=False)]


def row_in(row, rows):
    return any(all(same(x, y) for x, y in zip(row, r)) for r in rows)


def perturb_after(M, r, col, seed):
    """New market arrays: every bar after column col of day r, and every later day, replaced by another walk."""
    rng = np.random.default_rng(seed)
    X = {k: v.copy() for k, v in M.items()}
    nd = X["c"].shape[0]
    idx = [(rr, cc) for rr in range(r, nd) for cc in range(W) if rr > r or cc > col]
    if not idx:
        return X
    last = X["c"][r, col]
    steps = np.cumsum(rng.normal(0, 6.0, len(idx)))
    for k, (rr, cc) in enumerate(idx):
        cl = last + steps[k]
        op = last + (steps[k - 1] if k else 0.0)
        X["o"][rr, cc], X["c"][rr, cc] = op, cl
        X["h"][rr, cc] = max(op, cl) + abs(rng.normal(0, 2))
        X["l"][rr, cc] = min(op, cl) - abs(rng.normal(0, 2))
    return X


def check_lookahead(fn, params, M, und, n_rows=6, seed=1, cols=("und", "day", "sig_min", "side", "idx_stop", "idx_target"),
                    days=None):
    base = fn(mk_of(M, und, days), **params)
    assert len(base) > 0, "the look-ahead test needs signals"
    days = days or sessions(M["c"].shape[0])
    rng = np.random.default_rng(seed)
    pick = rng.choice(len(base), min(n_rows, len(base)), replace=False)
    for j in pick:
        row = base.iloc[int(j)]
        r = days.index(row.day)
        col = int(row.sig_min) - F.OPEN_M
        X = perturb_after(M, r, col, seed + int(j))
        new = fn(mk_of(X, und, days), **params)
        assert row_in(tuple(row[list(c for c in cols if c in base.columns)]), key_cols(new, cols)), row.to_dict()


# ------------------------------------------------------------------------------------------------ BREAK engine
def test_break_fires_exactly_once_up_and_down():
    M = scenario()
    for st, xa in ((False, np.nan), (True, F.OPEN_M + 192)):
        s = F.break_signals(mk_of(M), books=((1, 20, 10),), source="swing", stop=st)
        assert len(s) == 1
        r = s.iloc[0]
        assert (r.day, int(r.sig_min), int(r.side)) == (sessions(2)[1], F.OPEN_M + 90, 1)
        assert np.isnan(r.idx_target) and same(float(r.exit_at), float(xa))
        # fb: the 1-min close back below 105 at minute 191 -> out at the next minute's open
    # mirror image: a break DOWN
    Md = {k: 200.0 - v for k, v in M.items()}
    Md["h"], Md["l"] = 200.0 - M["l"], 200.0 - M["h"]
    s = F.break_signals(mk_of(Md), books=((1, 20, 10),), source="swing", stop=False)
    assert len(s) == 1 and int(s.side.iloc[0]) == -1 and int(s.sig_min.iloc[0]) == F.OPEN_M + 90


def test_break_both_and_pool_fire_on_the_pool_on_swing():
    M = scenario()
    for src in ("pool", "both", "either"):
        s = F.break_signals(mk_of(M), books=((1, 20, 10),), source=src, stop=True)
        assert len(s) == 1 and int(s.sig_min.iloc[0]) == F.OPEN_M + 90 and int(s.side.iloc[0]) == 1, src


def test_break_does_not_fire():
    s = F.break_signals(mk_of(scenario(jump=104.9)), books=((1, 20, 10),), source="either")
    assert s.empty
    c = np.tile(100 - 0.002 * np.arange(W), (3, 1)) - 0.75 * np.arange(3)[:, None]
    assert F.break_signals(mk_of(from_closes(c)), books=((1, 20, 10),), source="either").empty
    # outside the entry window (fills after 14:30)
    assert F.break_signals(mk_of(scenario()), books=((1, 20, 10),), source="swing", win=(100, 315)).empty


def test_break_gap_and_empty_and_expiry():
    M = scenario()
    for k in M:
        M[k][1, 100:110] = np.nan
        M[k][1, 200] = np.nan
    s = F.break_signals(mk_of(M), books=((1, 20, 10),), source="swing")
    assert len(s) == 1 and int(s.sig_min.iloc[0]) == F.OPEN_M + 90
    empty = FakeMk({"BANKNIFTY": FakeIndex([], {k: np.zeros((0, W)) for k in "ohlc"})})
    for fn, kw in ((F.break_signals, {}), (F.wait5_signals, {}), (F.carry_signals, {})):
        out = fn(empty, **kw)
        assert out.empty and {"und", "day", "sig_min", "side"} <= set(out.columns)
    assert F.arm_signals(FakeMk({"BANKNIFTY": FakeIndex([], {k: np.zeros((0, W)) for k in "ohlc"})})).empty
    days = sessions(2)
    ex = F.break_signals(mk_of(scenario(), days=days, exp={days[1]}), books=((1, 20, 10),), source="swing",
                         skip="expday")
    assert ex.empty
    # DTE <= 1: the next expiry is the day after -> skipped; two days after -> kept
    d3 = sessions(4)
    M3 = scenario(extra_days=2)
    assert F.break_signals(mk_of(M3, days=d3, exp={d3[2]}), books=((1, 20, 10),), source="swing", skip="dte1").empty
    assert len(F.break_signals(mk_of(M3, days=d3, exp={d3[3]}), books=((1, 20, 10),), source="swing",
                               skip="dte1")) == 1


def _lb_module():
    spec = importlib.util.spec_from_file_location("r52_test_liquidity_break", RES / "liquidity_break.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _replay(sig, M, days):
    """One position at a time with the signals' own index exits (what liquidity_break.simulate does without a
    premium stop): -> [(day, entry minute offset, side)]."""
    out, busy = [], {}
    for r in sig.sort_values(["day", "sig_min"]).itertuples(index=False):
        di = days.index(r.day)
        m = int(r.sig_min) - F.OPEN_M + 1
        if busy.get(r.day, -1) > m - 1:
            continue
        xs = [355]
        if np.isfinite(r.exit_at):
            xs.append(int(r.exit_at) - F.OPEN_M - 1)
        if np.isfinite(r.idx_target):
            ext = M["h"][di] if r.side > 0 else M["l"][di]
            hit = np.nonzero((ext[m:] >= r.idx_target) if r.side > 0 else (ext[m:] <= r.idx_target))[0]
            if len(hit):
                xs.append(m + int(hit[0]))
        busy[r.day] = min(xs)
        out.append((r.day, m, int(r.side)))
    return out


@pytest.mark.parametrize("tf,src,stop", [(5, "both", True), (5, "either", False), (15, "pool", True),
                                          (15, "swing", False), (3, "both", False)])
def test_break_matches_liquidity_break_simulate(tf, src, stop):
    """Parity with research/liquidity_break.py simulate (index-points mode, no chain, no premium stop) on a
    continuous random walk (open = previous close, so the old 'open of the entry minute' = our 'breaking close')."""
    lb = _lb_module()
    nd = 25
    rng = np.random.default_rng(tf * 7 + len(src))
    c = 20000 + np.cumsum(rng.normal(0, 5, nd * W)).reshape(nd, W)
    M = from_closes(c)
    M["h"] += np.abs(rng.normal(0, 3, (nd, W)))
    M["l"] -= np.abs(rng.normal(0, 3, (nd, W)))
    days = sessions(nd)
    old_days = [dict(day=d, I=dict(open=M["o"][i], high=M["h"][i], low=M["l"][i], close=M["c"][i]), chain={},
                     exp=False) for i, d in enumerate(days)]
    b = lb.bars(old_days, tf)
    zones = lb.swing_zones(b, 20, "full") + lb.pool_zones(b, 2, 5, 10)
    tr = lb.simulate(old_days, b, zones, src, stop)
    old = [(t.day, int(t.m0), int(t.sign)) for t in tr.itertuples(index=False)]
    sig = F.break_signals(mk_of(M, days=days), books=((tf, 20, 10),), source=src, stop=stop)
    new = _replay(sig, M, days)
    assert len(old) > 10
    assert new == old


@pytest.mark.parametrize("params", [
    dict(books=((5, 20, 10),), source="both", stop=True),
    dict(books=((15, 20, 10), (5, 10, 10)), source="either", stop=False),
    dict(books=((10, 20, 10),), source="pool", vol_fh=0.5, trend="vwap", skip="dte1"),
    dict(books=((5, 20, 10),), source="either", trend="ema5x50", vol_rng=0.3, be=10.0),
    dict(books=((5, 20, 10),), source="swing", trend="ema20x3"),
    dict(books=((5, 10, 15),), source="either", trend="ema20x1"),
    dict(books=((5, 20, 10),), source="pool", trend="open"),
])
def test_break_no_lookahead(params):
    M = walk(30, 3)
    days = sessions(30)
    exp = {d for d in days if d.weekday() == 3}
    base_mk = FakeMk({"BANKNIFTY": FakeIndex(days, M, exp)})

    def fn(mk, **kw):
        mk.ixs["BANKNIFTY"] = FakeIndex(days, mk.ixs["BANKNIFTY"].mat(), exp)
        return F.break_signals(mk, **kw)
    assert len(F.break_signals(base_mk, **params)) > 0
    check_lookahead(fn, params, M, "BANKNIFTY", days=days)


def test_exit_at_is_a_stopping_time():
    """The structural exit (failed break / new level / index breakeven) depends only on bars up to its decision."""
    M = walk(20, 5)
    days = sessions(20)
    params = dict(books=((5, 20, 10),), source="either", stop=True, be=15.0)
    base = F.break_signals(mk_of(M, days=days), **params)
    rows = base[np.isfinite(base.exit_at)]
    assert len(rows) > 3
    for j, row in enumerate(rows.head(5).itertuples(index=False)):
        r = days.index(row.day)
        X = perturb_after(M, r, int(row.exit_at) - F.OPEN_M - 1, 100 + j)
        new = F.break_signals(mk_of(X, days=days), **params)
        hit = new[(new.day == row.day) & (new.sig_min == row.sig_min) & (new.side == row.side)]
        assert len(hit) == 1 and int(hit.exit_at.iloc[0]) == int(row.exit_at)


# ------------------------------------------------------------------------------------------------ ARM engine
def test_arm_fires_on_pool_on_swing_and_not_on_expiry_or_short_break():
    days = sessions(2)
    M = scenario(steep=0.01)
    s = F.arm_signals(mk_of(M, "NIFTY", days), books=(("NIFTY", 1),))
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.day, int(r.sig_min), int(r.side), int(r.gate)) == (days[1], F.OPEN_M + 90, 1, F.OPEN_M + 91)
    assert r.idx_stop == pytest.approx(105.0 - 15.0) and np.isnan(r.idx_target)
    assert int(r.exit_at) == F.OPEN_M + 192          # 1-min close back below 105 at minute 191
    # LIQ-19 modes drop the index stop and pick the exits
    n3 = F.arm_signals(mk_of(M, "NIFTY", days), books=(("NIFTY", 1),), mode="N3").iloc[0]
    n2 = F.arm_signals(mk_of(M, "NIFTY", days), books=(("NIFTY", 1),), mode="N2").iloc[0]
    assert np.isnan(n3.idx_stop) and int(n3.exit_at) == F.OPEN_M + 192 and np.isnan(n2.exit_at)
    # stop multiple
    assert F.arm_signals(mk_of(M, "NIFTY", days), books=(("NIFTY", 1),), stop_mult=2.0).idx_stop.iloc[0] == pytest.approx(75.0)
    # entry window: bar end 10:46 is outside a 10:30 cut-off
    assert F.arm_signals(mk_of(M, "NIFTY", days), books=(("NIFTY", 1),), win_to=10 * 60 + 30).empty
    assert F.arm_signals(mk_of(M, "NIFTY", days, exp={days[1]}), books=(("NIFTY", 1),)).empty
    assert F.arm_signals(mk_of(scenario(jump=104.9), "NIFTY", days), books=(("NIFTY", 1),)).empty


def _h11_module():
    spec = importlib.util.spec_from_file_location("r52_test_h11_sig", RES / "hunt" / "h11" / "sig.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_arm_matches_h11_raw_signals():
    """With the 09:20-14:00 window, room 1 and the a-priori index stop, arm_signals = hunt/h11/sig.py raw_signals."""
    sig = _h11_module()
    nd = 14
    M = walk(nd, 11, start=23000.0, vol=3.0)
    days = sessions(nd)
    exp = {days[4], days[9]}
    mk = mk_of(M, "NIFTY", days, exp)
    raw = sig.raw_signals(mk, unds=("NIFTY",), tfs=(5, 15))
    raw = raw[raw.dist >= 15.0]
    ours = F.arm_signals(mk_of(M, "NIFTY", days, exp), books=(("NIFTY", 5), ("NIFTY", 15)))
    assert len(raw) > 5
    a = raw.assign(idx_stop=raw.level - raw.side * 15.0)
    cols = ["day", "sig_min", "gate", "side", "book", "ref_spot", "idx_stop", "idx_target", "exit_at"]
    a = a[cols].sort_values(["book", "day", "sig_min"]).reset_index(drop=True)
    b = ours[cols].sort_values(["book", "day", "sig_min"]).reset_index(drop=True)
    pd.testing.assert_frame_equal(a, b, check_dtype=False)
    assert not set(ours.day) & exp


def test_arm_no_lookahead():
    M = walk(14, 21, start=23000.0, vol=3.0)
    check_lookahead(F.arm_signals, dict(books=(("NIFTY", 5), ("NIFTY", 15))), M, "NIFTY")


def test_arm_gap_minutes():
    days = sessions(2)
    M = scenario()
    for k in M:
        M[k][1, 120:130] = np.nan
    s = F.arm_signals(mk_of(M, "NIFTY", days), books=(("NIFTY", 1),))
    assert len(s) == 1 and int(s.sig_min.iloc[0]) == F.OPEN_M + 90


# ------------------------------------------------------------------------------------------------ LIQ-5 wait 5
def test_wait5_fires_and_not():
    days = sessions(2)
    M = scenario()
    s = F.wait5_signals(mk_of(M, days=days), tf=1, kind="found", source="swing", how="net")
    # the swing high (minute 50) is found at minute 70; candles 71..75 fall -> buy the PE at minute 76
    assert len(s) == 1 and (s.day.iloc[0], int(s.sig_min.iloc[0]), int(s.side.iloc[0])) == (days[1], F.OPEN_M + 75, -1)
    assert len(F.wait5_signals(mk_of(M, days=days), tf=1, kind="found", source="swing", how="colour")) == 1
    c = np.tile(100 - 0.002 * np.arange(W), (2, 1)) - 0.75 * np.arange(2)[:, None]
    assert F.wait5_signals(mk_of(from_closes(c)), tf=1, kind="found", source="either", how="net").empty


@pytest.mark.parametrize("params", [dict(tf=5, kind="found", source="both", how="colour"),
                                    dict(tf=5, kind="break", source="pool", how="held"),
                                    dict(tf=3, kind="found", source="either", how="hhhl")])
def test_wait5_no_lookahead(params):
    check_lookahead(F.wait5_signals, params, walk(25, 8), "BANKNIFTY")


# ------------------------------------------------------------------------------------------------ LIQ-3 carry
def test_carry_holds_overnight_and_squares_off():
    days = sessions(3)
    M = scenario(extra_days=1, steep=0.0005)
    s = F.carry_signals(mk_of(M, days=days), tf=1, source="swing", stop=False, carry=True)
    assert len(s) == 1
    r = s.iloc[0]
    assert (r.day, int(r.sig_min), int(r.side)) == (days[1], F.OPEN_M + 90, 1)
    assert (r.exit_day, int(r.exit_min)) == (days[2], 15 * 60 + 15)     # no later session: out at 15:15
    s = F.carry_signals(mk_of(M, days=days), tf=1, source="swing", stop=False, carry=False)
    assert (s.exit_day.iloc[0], int(s.exit_min.iloc[0])) == (days[1], 15 * 60 + 10)
    # never carried over the contract's expiry day
    s = F.carry_signals(mk_of(M, days=days, exp={days[1]}), tf=1, source="swing", stop=False, carry=True)
    assert (s.exit_day.iloc[0], int(s.exit_min.iloc[0])) == (days[1], 15 * 60 + 15)
    # failed break: the 1-min close back below 105 -> out at the next minute
    s = F.carry_signals(mk_of(scenario(extra_days=1), days=days), tf=1, source="swing", stop=True, carry=True)
    assert (s.exit_day.iloc[0], int(s.exit_min.iloc[0])) == (days[1], F.OPEN_M + 192)
    assert F.carry_signals(mk_of(scenario(jump=104.9, extra_days=1), days=days), tf=1, source="either").empty


def test_carry_no_lookahead_and_one_at_a_time():
    M = walk(30, 13)
    days = sessions(30)
    params = dict(tf=15, source="either", stop=False, carry=True)
    s = F.carry_signals(mk_of(M, days=days), **params)
    assert len(s) > 3 and s.book.is_unique
    t0 = [(d.toordinal(), m) for d, m in zip(s.day, s.sig_min)]
    t1 = [(d.toordinal(), m) for d, m in zip(s.exit_day, s.exit_min)]
    assert all(b > a for a, b in zip(t0, t1))
    assert all(t0[k + 1] >= t1[k] for k in range(len(s) - 1))             # the next entry after the last exit
    check_lookahead(F.carry_signals, params, M, "BANKNIFTY", cols=("und", "day", "sig_min", "side"), days=days)


# ------------------------------------------------------------------------------------------------ SPECS
def test_specs_build():
    import r52rules
    keys = [s["key"] for s in F.SPECS]
    assert keys == ["H9-1", "H11-1", "H11-2", "LIQ-1", "LIQ-2", "LIQ-3", "LIQ-4", "LIQ-5", "LIQ-6", "LIQ-9", "LIQ-12",
                    "LIQ-19", "LIQ-22"]
    for s in F.SPECS:
        assert len(s["grid"]) <= 23 and s["source"] == "index"
        rules = r52rules.spec_rules(s)
        assert rules[0].vid == "orig" and len(rules) == len(s["grid"]) + 1
        assert len({repr((r.params, r.rule, r.exits, r.pos, r.window, r.unds)) for r in rules}) == len(rules), s["key"]
        for r in rules:
            assert r.exits.sq_off <= 15 * 60 + 15 or r.exits.sq_off == F.MD_SQ
            assert r.signal_fn in (F.arm_signals, F.break_signals, F.wait5_signals, F.carry_signals)
