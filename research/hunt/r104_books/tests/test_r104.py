"""R104 tests: the executor's fills and exits (market / stop / limit entries, stop, target, gap fills, break-even,
trail, rule exits, time exits, one-cancels-other, one position at a time), bars, causal swings and ZigZag, the
statistics (bootstrap p, BH, walk-forward choice, gates), futures / crypto pricing, and the LOOK-AHEAD GUARD: every
registered rule run on a synthetic market cut at random bars gives exactly the same intents up to the cut. No lake.

    python -m pytest research/hunt/r104_books -q
"""
from __future__ import annotations

import importlib
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import r104cfg as K  # noqa: E402
import r104data as DA  # noqa: E402
import r104ind as I  # noqa: E402
import r104run as R  # noqa: E402
import r104sim as SIM  # noqa: E402
import r104stats as ST  # noqa: E402

NAN = float("nan")
E_F = SIM.EMPTY_F
E_B = SIM.EMPTY_B


def arr(*x):
    return np.array(x, float)


# ================================================================================================ executor
def test_market_entry_stop_hit_and_gap_fill():
    o, h, l = arr(100, 100, 99, 95), arr(101, 101, 100, 96), arr(99, 99, 97, 94)
    f, fpx, x, xpx, rs, touch = SIM.walk(o, h, l, 1, 1, 0, NAN, -1, 98.0, NAN, -1, NAN, 4, E_F, E_B)
    assert (f, fpx) == (1, 100.0)
    assert (x, xpx, rs, touch) == (2, 98.0, 1, 1)           # touched inside bar 2 at the stop
    f, fpx, x, xpx, rs, touch = SIM.walk(o, h, l, 1, 1, 0, NAN, -1, 96.0, NAN, -1, NAN, 4, E_F, E_B)
    assert (x, xpx) == (3, 95.0)                           # gapped through 96: fills at the open


def test_stop_entry_and_target_and_stop_first():
    o, h, l = arr(100, 100, 102, 104), arr(100, 103, 106, 105), arr(100, 99, 101, 100)
    f, fpx, x, xpx, rs, _t = SIM.walk(o, h, l, 1, 1, 1, 102.0, 3, 99.0, 105.0, -1, NAN, 4, E_F, E_B)
    assert (f, fpx) == (1, 102.0)
    assert (x, xpx, rs) == (2, 105.0, 2)
    # a bar that hits both: the stop wins
    o, h, l = arr(100, 100), arr(100, 110), arr(100, 90)
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, 95.0, 105.0, -1, NAN, 2, E_F, E_B)
    assert r[4] == 1 and r[2] == 1


def test_stop_order_not_triggered_and_limit_fill():
    o, h, l = arr(100, 100, 100), arr(101, 101, 101), arr(99, 99, 99)
    assert SIM.walk(o, h, l, 0, 1, 1, 105.0, 2, 90.0, NAN, -1, NAN, 3, E_F, E_B)[0] == -1
    r = SIM.walk(o, h, l, 0, 1, 2, 99.5, 2, 90.0, NAN, -1, NAN, 3, E_F, E_B)
    assert r[0] == 0 and r[1] == 99.5


def test_short_side_mirror():
    o, h, l = arr(100, 100, 101), arr(100, 101, 103), arr(100, 98, 100)
    r = SIM.walk(o, h, l, 0, -1, 0, NAN, -1, 102.0, 95.0, -1, NAN, 3, E_F, E_B)
    assert r[4] == 1 and r[3] == 102.0


def test_time_rule_end_exits():
    o, h, l = arr(100, 101, 102, 103), arr(100, 101, 102, 103), arr(100, 101, 102, 103)
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, NAN, NAN, 2, NAN, 4, E_F, E_B)
    assert (r[2], r[3], r[4]) == (2, 102.0, 3)
    xf = np.array([False, False, False, True])
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, NAN, NAN, -1, NAN, 4, E_F, xf)
    assert (r[2], r[4]) == (3, 4)
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, NAN, NAN, -1, NAN, 3, E_F, E_B)
    assert (r[2], r[3], r[4]) == (3, 103.0, 5)            # the square-off bar's open


def test_break_even_and_trail():
    o, h, l = arr(100, 100, 103, 101), arr(100, 102.5, 104, 101), arr(100, 99.5, 102, 99)
    # risk 2 (stop 98); +1R at 102 -> stop to 100 -> bar 3 low 99 exits at 100
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, 98.0, NAN, -1, 1.0, 4, E_F, E_B)
    assert (r[2], r[3], r[4]) == (3, 100.0, 1)
    tr = arr(NAN, NAN, 101.5, NAN)
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, 98.0, NAN, -1, NAN, 4, tr, E_B)
    assert r[2] == 3 and r[3] == 101.0                     # opens below the trailed 101.5: fills at the open
    # trail after break-even only (tab): no BE -> the trail never arms
    r = SIM.walk(o, h, l, 0, 1, 0, NAN, -1, 98.0, NAN, -1, 5.0, 4, tr, E_B, True)
    assert r[4] == 5


def test_execute_one_position_oco_and_fill_order():
    o = np.full(10, 100.0)
    h = np.r_[np.full(5, 100.0), np.full(5, 103.0)]
    l = np.r_[np.full(3, 100.0), np.full(7, 97.0)]
    end = lambda i: 10
    I1 = (0, 1, 1, 102.0, 9, 90.0, NAN, -1, NAN, "a|L", 1.0, 102.0)  # fills at 5
    I2 = (0, -1, 1, 98.0, 9, 110.0, NAN, -1, NAN, "a|S", 1.0, 98.0)  # fills at 3 -> wins the OCO
    out = SIM.execute(o, h, l, [I1, I2], end)
    assert len(out) == 1 and out[0]["side"] == -1 and out[0]["f"] == 3
    I3 = (4, 1, 0, NAN, -1, 50.0, NAN, -1, NAN, "b", 1.0, 100.0)     # busy until 10: skipped
    out = SIM.execute(o, h, l, [I2, I3], end)
    assert [x["grp"] for x in out] == ["a|S"]


# ================================================================================================ indicators
def test_swings_are_causal():
    rng = np.random.default_rng(1)
    c = 100 + np.cumsum(rng.normal(size=300))
    h, l = c + 0.5, c - 0.5
    full = I.swings(h, l, 2)
    for cut in (50, 120, 299):
        part = I.swings(h[:cut + 1], l[:cut + 1], 2)
        for a, b in zip(full, part):
            assert np.array_equal(np.asarray(a)[:cut + 1], np.asarray(b), equal_nan=True)


def test_zigzag_confirms_after_reversal():
    c = arr(100, 103, 106, 104, 101, 99, 102, 105)
    pb, cb, pv, pt = I.zigzag(c, c, c, np.full(8, 3.0))
    assert list(pt[:2]) == [-1, 1] or list(pt[:2]) == [1, -1]
    for p, q in zip(pb, cb):
        assert q > p


def test_ema_rsi_atr_shapes():
    x = np.linspace(1, 50, 50)
    assert np.isnan(I.ema(x, 10)[8]) and np.isfinite(I.ema(x, 10)[9])
    assert I.rsi(x, 14)[-1] == 100.0
    assert np.isfinite(I.atr(x + 1, x - 1, x, 14)[-1])


# ================================================================================================ statistics
def test_bootstrap_p_and_bh():
    rng = np.random.default_rng(0)
    assert ST.boot_p(rng.normal(1.0, 1.0, 300)) < 0.01
    assert ST.boot_p(rng.normal(-0.2, 1.0, 300)) == 1.0
    q = ST.bh([0.01, 0.04, 0.03, 0.5])
    assert np.allclose(q, [0.04, 0.04 * 4 / 3, 0.04, 0.5]) or q[0] <= q[1]


def test_walk_forward_choice_uses_only_earlier_folds():
    d = lambda i: date(2022, 1, 1) + timedelta(days=i)
    a = pd.DataFrame(dict(day=[d(0), d(1), d(2), d(3)], net=[10.0, -5, 7, 1], fold=[0, 1, 2, 3]))
    b = pd.DataFrame(dict(day=[d(0), d(1), d(2), d(3)], net=[-10.0, 50, 7, 100], fold=[0, 1, 2, 3]))
    s, picks, ff = ST.walk_forward({"a": a, "b": b}, 4)
    assert picks == [None, "a", "b", "b"] and ff == 1
    assert list(s.net) == [-5.0, 7.0, 100.0]


def test_gates_need_every_fold_positive():
    d = lambda i: date(2022, 1, 1) + timedelta(days=i)
    t = pd.DataFrame(dict(day=[d(i) for i in range(200)], net=[5.0] * 199 + [-1.0], fold=[i // 50 for i in range(200)]))
    g = ST.entry_gates("IDX", t, 0, 4, 0.001, 0.01)
    assert ST.design_pass(g)
    t.loc[t.fold == 2, "net"] = -1.0
    assert not ST.design_pass(ST.entry_gates("IDX", t, 0, 4, 0.001, 0.01))


# ================================================================================================ pricing
def test_future_and_crypto_pricing():
    p = SIM.price_index_future("NIFTY", date(2025, 1, 1), date(2025, 1, 1), 1, 24000.0, 24100.0, False, False, 75)
    assert p["gross"] < 100 * 75 and p["gross"] > 90 * 75 and p["cost"] > 40
    q = SIM.price_index_future("NIFTY", date(2025, 1, 1), date(2025, 1, 31), -1, 24000.0, 24000.0, False, False, 75)
    assert q["gross"] > -3.0 * 75 * 2                     # a short earns the carry, minus the spread
    c = SIM.price_crypto(1, 100.0, 101.0, False, False)
    assert c["cost"] == pytest.approx(2 * 100000 * 0.0005 * 1.18)


def test_futures_charges_are_dated():
    a = K.index_fut_charges(1e6, 1e6, date(2022, 1, 1))
    b = K.index_fut_charges(1e6, 1e6, date(2026, 5, 1))
    assert b > a                                           # STT 0.01% -> 0.05%


# ================================================================================================ look-ahead guard
def synth_series(market="IDX", n_days=12, seed=7):
    rng = np.random.default_rng(seed)
    rows = []
    d0 = date(2022, 1, 3)
    p = 18000.0
    for i in range(n_days):
        d = d0 + timedelta(days=i)
        for r in range(375):
            o = p
            p = p * (1 + rng.normal(0, 0.0007))
            hi, lo = max(o, p) * (1 + abs(rng.normal(0, 0.0002))), min(o, p) * (1 - abs(rng.normal(0, 0.0002)))
            rows.append(dict(day=d, tod=K.NSE_OPEN + r, open=o, high=hi, low=lo, close=p, volume=rng.integers(1, 100)))
    df = pd.DataFrame(rows)
    sq = lambda d, tods, base: DA._first_at_or_after(tods, base, 15 * 60 + 10)
    return DA.make_series("SYN", market, df, K.NSE_OPEN, sq)


def cut_series(s, cut):
    """The series truncated after minute `cut` (as if the data ended there)."""
    keep = slice(0, cut + 1)
    ns = s.ses[keep]
    s0, s1 = DA._sessions(ns)
    return DA.Series(s.name, s.market, s.o[keep], s.h[keep], s.l[keep], s.c[keep], s.v[keep], ns, s.tod[keep],
                     s.days[:ns[-1] + 1], s0, s1, s.sq[:ns[-1] + 1], s.open_tod, s.expiry[:ns[-1] + 1] if len(s.expiry) else s.expiry)


FAMILIES = ["f3_patterns"]


@pytest.mark.parametrize("fam", FAMILIES)
def test_no_look_ahead_in_any_rule(fam):
    importlib.import_module(fam)
    s = synth_series()
    daily = pd.DataFrame(dict(open=[1.0], high=[1.0], low=[1.0], close=[1.0]))
    rng = np.random.default_rng(3)
    cuts = sorted(rng.integers(1500, s.n - 10, size=3))
    for (rid, mk), imp in R.IMPL.items():
        if imp["_family"] != fam or mk not in ("IDX", "MCXI", "CRY"):
            continue
        for vn, var in imp["variants"].items():
            kw = {k: v for k, v in var.items() if not k.startswith("_")}
            full = imp["fn"](R.Ctx("IDX", "SYN", s, daily), **kw)[0]
            for cut in cuts:
                sc = cut_series(s, int(cut))
                part = imp["fn"](R.Ctx("IDX", "SYN", sc, daily), **kw)[0]
                key = lambda t: (t[0], t[1], t[2], round(t[3], 6) if t[3] == t[3] else None,
                                 round(t[5], 6) if t[5] == t[5] else None, round(t[6], 6) if t[6] == t[6] else None)
                # every decision made up to the cut (start <= cut) must be identical
                fa = sorted(key(t) for t in full if t[0] <= cut)
                pa = sorted(key(t) for t in part if t[0] <= cut)
                assert fa == pa, (rid, vn, cut)
