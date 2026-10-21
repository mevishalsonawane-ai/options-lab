"""R58 tests on synthetic books (no lake needed): look-ahead guard per signal, held-contract pricing, the expiry cut-off,
lot by date, holidays, missing minutes, no data, stray rows, corporate-action days, charges and the exit walk.

    python -m pytest research/hunt/r58/test_r58.py -q
"""
from __future__ import annotations

import copy
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r58cfg as K  # noqa: E402
import r58data as D  # noqa: E402
import r58engine as E  # noqa: E402
import r58rules as RR  # noqa: E402

NM = K.NMIN


def sessions(n: int, start=date(2025, 1, 1), skip=()):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5 and d not in skip:
            out.append(d)
        d += timedelta(days=1)
    return out


def make_book(nd=60, seed=0, skip=(), expiry_at=(40,), price=1000.0, step=10.0, vol=0.004):
    """A stock random walk, NIFTY, daily bars, monthly expiries at the given session indices and ATM +-1 contracts
    priced off the stock (call / put = time value + intrinsic), one row every minute."""
    rng = np.random.default_rng(seed)
    days = sessions(nd, skip=skip)
    ret = rng.normal(0, vol / np.sqrt(NM), size=nd * NM)
    ret[::NM] += rng.normal(0, 0.01, size=nd)                   # overnight gaps
    px = price * np.exp(np.cumsum(ret)).reshape(nd, NM)
    S = np.zeros((nd, NM, 5))
    S[:, :, 3] = px
    S[:, :, 0] = np.concatenate([px[:, :1], px[:, :-1]], axis=1)
    S[:, :, 1] = np.maximum(S[:, :, 0], S[:, :, 3]) * (1 + rng.uniform(0, 5e-4, (nd, NM)))
    S[:, :, 2] = np.minimum(S[:, :, 0], S[:, :, 3]) * (1 - rng.uniform(0, 5e-4, (nd, NM)))
    S[:, :, 4] = rng.integers(1000, 50000, (nd, NM)) * np.where(rng.uniform(size=(nd, NM)) < 0.01, 40, 1)
    N = np.zeros((nd, NM, 4))
    nf = 24000 * np.exp(np.cumsum(rng.normal(0, 0.003 / np.sqrt(NM), nd * NM))).reshape(nd, NM)
    N[:, :, 0], N[:, :, 1], N[:, :, 2], N[:, :, 3] = nf, nf * 1.0002, nf * 0.9998, nf
    Dd = np.stack([S[:, 0, 0], S[:, :, 1].max(1), S[:, :, 2].min(1), S[:, -1, 3]], axis=1)
    exps = [days[i] for i in expiry_at] + [days[-1] + timedelta(days=30)]
    exp_of = np.array([next(j for j, e in enumerate(exps) if e >= d) for d in days])
    exp_di = np.array([days.index(e) if e in days else -1 for e in exps])
    atm = np.round(px / step) * step
    con = {}
    for di in range(nd):
        ei = int(exp_of[di])
        tdays = max((exps[ei] - days[di]).days, 1)
        for k in np.unique(np.concatenate([atm[di] - step, atm[di], atm[di] + step])):
            for sd in ("C", "P"):
                key = (ei, float(k), sd)
                intr = np.maximum(px[di] - k, 0) if sd == "C" else np.maximum(k - px[di], 0)
                p = intr + price * 0.004 * np.sqrt(tdays / 30) + 1.0
                arr = np.full((NM, 6), np.nan)
                arr[:, 3] = p
                arr[:, 0] = np.concatenate([[p[0]], p[:-1]])
                arr[:, 1] = np.maximum(arr[:, 0], p) * 1.01
                arr[:, 2] = np.minimum(arr[:, 0], p) * 0.99
                arr[:, 4] = 500.0 * 30
                arr[:, 5] = 25.0
                if key not in con:
                    con[key] = (di, np.full((0, NM, 6), np.nan, dtype=np.float32))
                d0, a = con[key]
                need = di - d0 + 1
                if a.shape[0] < need:
                    a = np.concatenate([a, np.full((need - a.shape[0], NM, 6), np.nan, dtype=np.float32)])
                a[di - d0] = arr
                con[key] = (d0, a)
    bk = D.Book(sym="TEST", days=days, S=S, N=N, D=Dd, ratio=np.ones(nd), exdate=np.zeros(nd, bool),
                bad=np.zeros(nd, bool), expiries=exps, exp_of=exp_of, exp_di=exp_di, atm=atm.astype(float),
                step=np.full((nd, NM), step), con=con, lots={}, bhav_lots={i: [(days[0], 500)] for i in range(len(exps))})
    bk.spread = {"none": dict(half_used=0.01), "thin": dict(half_used=0.01), "liquid": dict(half_used=0.005)}
    return bk


def clear(bk):
    for a in ("_b5", "_slot", "_dc5", "_mp", "_vwap"):
        if hasattr(bk, a):
            delattr(bk, a)


def scramble_after(bk, di, s, seed=99):
    """A copy whose stock / NIFTY minutes after (di, s), and daily bars from di on, are random."""
    b2 = copy.deepcopy(bk)
    clear(b2)
    rng = np.random.default_rng(seed)
    for arr in (b2.S, b2.N):
        noise = rng.uniform(0.9, 1.1, size=arr.shape)
        arr[di, s + 1:] *= noise[di, s + 1:]
        arr[di + 1:] *= noise[di + 1:]
    b2.D[di:] *= rng.uniform(0.9, 1.1, size=b2.D[di:].shape)
    return b2


def same(a, b) -> bool:
    """Spec equality with NaN == NaN."""
    import dataclasses
    for x, y in zip(dataclasses.astuple(a), dataclasses.astuple(b)):
        if isinstance(x, float) and isinstance(y, float) and np.isnan(x) and np.isnan(y):
            continue
        if x != y:
            return False
    return True


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("rule", RR.rules(), ids=lambda r: r.id)
def test_no_look_ahead(rule):
    """Every signal is unchanged when everything after its decision minute is replaced (PREREG 7)."""
    bk = make_book(nd=60, seed=3, vol=0.02)
    days = list(range(30, 58))
    seen = 0
    for di in days:
        a = RR.specs(bk, rule, [di])
        cut = a[0].s if a else max(rule.twin_rows())
        b2 = scramble_after(bk, di, cut)
        b = RR.specs(b2, rule, [di])
        if a:
            seen += 1
            assert b and same(b[0], a[0]), (rule.id, di)
        else:
            assert not [x for x in b if x.s <= cut], (rule.id, di)
    assert seen or rule.kind in ("rs_d", "mom", "gap", "rs_i", "h37", "vwap", "dc20")


def test_signals_fire_on_synthetic_data():
    bk = make_book(nd=60, seed=3, vol=0.02)
    fired = {r.id for r in RR.rules() if RR.specs(bk, r, list(range(30, 58)))}
    assert {"orb15-rs", "dc5-rt-atm", "vol-k25-60"} <= fired


# ------------------------------------------------------------------------------------------------ held contract
def test_held_contract_pricing_ignores_atm_switch():
    bk = make_book(nd=10, seed=1, expiry_at=(8,))
    di, s = 2, 30
    k = bk.atm[di, s]
    ei = int(bk.exp_of[di])
    bk.atm[di, s + 1:] = k + 10 * bk.step[di, s]                  # the rolling ATM moves far away
    other = (ei, float(k + 10 * bk.step[di, s]), "C")
    bk.con[other] = (di, np.full((1, NM, 6), 999.0, dtype=np.float32))  # a contract with absurd prices
    sp = E.Spec(sym="TEST", di=di, s=s, side=1, tmin=60, pstop=0.0)
    t = E.simulate(bk, sp, E.Mode(band=False), want_cash=False)
    d0, arr = bk.con[(ei, float(k), "C")]
    assert t["strike"] == k
    assert t["raw_entry"] == pytest.approx(float(arr[di - d0, s + 1, D.O]))
    assert t["raw_exit"] == pytest.approx(float(arr[di - d0, s + 1 + 60, D.O]))
    assert t["raw_exit"] < 500


def test_1itm_strike():
    bk = make_book(nd=5, seed=1, expiry_at=(4,))
    k = bk.atm[1, 20]
    assert E.strike_for(bk, 1, 20, 1, 1) == k - 10
    assert E.strike_for(bk, 1, 20, -1, 1) == k + 10


# ------------------------------------------------------------------------------------------------ expiry cut-off
def test_expiry_cutoff():
    bk = make_book(nd=30, seed=2, expiry_at=(20,))
    m = E.Mode()
    T = 20
    for di in (T - 2, T - 1, T):
        assert E.last_hold(bk, di, 1, m) == -1                   # no entries on T-2 .. T
    assert E.last_hold(bk, T - 3, 1, m) == T - 3
    assert E.last_hold(bk, T - 6, 5, m) == T - 3                 # a 5-session hold is cut at T-3
    assert E.last_hold(bk, T + 1, 3, m) == T + 3                 # next month's contract (expiry after the data)
    assert E.last_hold(bk, bk.nd - 1, 3, m) == bk.nd - 1
    nc = E.Mode(cutoff=False)
    assert E.last_hold(bk, T, 1, nc) == T
    assert E.last_hold(bk, T - 2, 5, nc) == T


def test_cutoff_trade_exits_by_1515_on_t_minus_3():
    bk = make_book(nd=30, seed=2, expiry_at=(20,))
    sp = E.Spec(sym="TEST", di=15, s=4, side=1, hold=5)
    t = E.simulate(bk, sp, E.Mode(band=False), want_cash=False)
    assert t["exit_day"] == bk.days[17]
    assert t["exit_f"] == 2 * NM + K.SQ and t["why"] == "sq"
    assert E.simulate(bk, E.Spec(sym="TEST", di=18, s=4, side=1), E.Mode()) is None


def test_design_book_ends_on_its_last_session():
    bk = make_book(nd=30, seed=2, expiry_at=(28,))
    m = E.Mode(end=12)
    assert E.last_hold(bk, 11, 5, m) == 12


# ------------------------------------------------------------------------------------------------ lots
def test_lot_by_date():
    tab = [(date(2025, 1, 6), 400), (date(2025, 1, 20), 2000)]
    assert D.bhav_lot(tab, date(2025, 1, 1)) == 400                # before the first bhav date: the earliest
    assert D.bhav_lot(tab, date(2025, 1, 10)) == 400
    assert D.bhav_lot(tab, date(2025, 1, 20)) == 2000
    assert D.bhav_lot(tab, date(2025, 3, 1)) == 2000
    assert D.bhav_lot([], date(2025, 3, 1)) == 0


def test_lot_from_volume():
    v400 = np.array([400 * k for k in range(1, 80)], float)        # not all multiples of 2000
    assert D.lot_from_volume(v400, [400, 2000]) == 400
    v2000 = np.array([2000 * k for k in range(1, 80)], float)
    assert D.lot_from_volume(v2000, [400, 2000]) == 2000
    assert D.lot_from_volume(v2000[:10], [400, 2000]) == 0         # too few minutes


def test_book_lot_prefers_volume_table():
    bk = make_book(nd=5, seed=1, expiry_at=(4,))
    ei, k = int(bk.exp_of[1]), float(bk.atm[1, 10])
    assert bk.lot(ei, k, "C", 1) == 500
    bk.lots[(ei, k, "C", 1)] = 250
    assert bk.lot(ei, k, "C", 1) == 250


# ------------------------------------------------------------------------------------------------ holidays, missing data
def test_holiday_hold_counts_sessions():
    skip = {date(2025, 1, 13)}                                     # a Monday holiday
    bk = make_book(nd=20, seed=4, skip=skip, expiry_at=(18,))
    assert date(2025, 1, 13) not in bk.days
    di = bk.days.index(date(2025, 1, 10))                          # Friday
    t = E.simulate(bk, E.Spec(sym="TEST", di=di, s=4, side=1, hold=2), E.Mode(band=False), want_cash=False)
    assert t["exit_day"] == date(2025, 1, 14) and t["held_sessions"] == 2


def test_missing_entry_minutes_no_trade():
    bk = make_book(nd=5, seed=1, expiry_at=(4,))
    di, s = 1, 50
    k = bk.atm[di, s]
    d0, arr = bk.con[(int(bk.exp_of[di]), float(k), "C")]
    arr[di - d0, s + 1:s + 4] = np.nan
    assert E.simulate(bk, E.Spec(sym="TEST", di=di, s=s, side=1), E.Mode()) is None


def test_missing_exit_minutes_next_print_then_stale_then_model():
    bk = make_book(nd=5, seed=1, expiry_at=(4,))
    di, s = 1, 50
    k = float(bk.atm[di, s])
    d0, arr = bk.con[(int(bk.exp_of[di]), k, "C")]
    x = s + 1 + 30
    arr[di - d0, x] = np.nan                                      # the exit minute has no print: the next one is used
    t = E.simulate(bk, E.Spec(sym="TEST", di=di, s=s, side=1, tmin=30), E.Mode(band=False), want_cash=False)
    assert t["src"] == "print" and t["raw_exit"] == pytest.approx(float(arr[di - d0, x + 1, D.O]))
    arr[di - d0, x:] = np.nan                                     # nothing after: the last print before (stale)
    t = E.simulate(bk, E.Spec(sym="TEST", di=di, s=s, side=1, tmin=30), E.Mode(band=False), want_cash=False)
    assert t["src"] == "stale" and t["raw_exit"] == pytest.approx(float(arr[di - d0, x - 1, D.C]))
    arr[di - d0 + 1] = np.nan                                     # no print on the exit session at all: model
    t = E.simulate(bk, E.Spec(sym="TEST", di=di, s=s, side=1, hold=2), E.Mode(band=False), want_cash=False)
    assert t["src"] == "model" and t["raw_exit"] > 0


def test_no_data():
    bk = make_book(nd=5, seed=1, expiry_at=(4,))
    bk.con = {}
    assert E.simulate(bk, E.Spec(sym="TEST", di=1, s=20, side=1), E.Mode()) is None
    bk = make_book(nd=40, seed=1, expiry_at=(38,))
    bk.S[35] = np.nan
    clear(bk)
    for r in RR.rules():
        assert not [x for x in RR.specs(bk, r, [35])], r.id
    bk.atm[30] = np.nan
    assert E.simulate(bk, E.Spec(sym="TEST", di=30, s=20, side=1), E.Mode()) is None


# ------------------------------------------------------------------------------------------------ hygiene
def test_stray_rows():
    spot = np.array([100.0, 102.9, 103.5, 50.0, 100.0])
    px = np.array([100.0, 100.0, 100.0, 100.0, np.nan])
    m = D.stray_mask(spot, px, np.ones(5))
    assert m.tolist() == [False, False, True, True, True]
    # a 1:5 split: the adjusted history is 1/5 of the option's spot, the day's ratio brings it back
    assert not D.stray_mask(np.array([2000.0]), np.array([400.0]), np.array([5.0]))[0]


def test_exdate_and_bad_day():
    r = np.array([5.0, 5.0, 5.0, 1.0, 1.0, 1.0, 0.47, 1.0, 1.0])
    ex, bad = D.ratio_flags(r)
    assert np.flatnonzero(ex).tolist() == [3]
    assert np.flatnonzero(bad).tolist() == [6]


def test_no_hold_across_exdate_or_bad_day():
    bk = make_book(nd=30, seed=2, expiry_at=(28,))
    bk.exdate[10] = True
    assert E.last_hold(bk, 8, 5, E.Mode()) == 9
    bk.bad[15] = True
    assert E.last_hold(bk, 15, 1, E.Mode()) == -1
    assert E.last_hold(bk, 13, 5, E.Mode()) == 14


# ------------------------------------------------------------------------------------------------ costs and the walk
def test_option_charges_match_obuy_dated():
    from obuy.costs import Costs
    c = Costs("dated")
    for d in (date(2025, 6, 2), date(2026, 5, 4)):
        dn = (d - date(1970, 1, 1)).days
        for buy in (True, False):
            for px, q in ((12.35, 700), (250.5, 50), (3.1, 5500)):
                ref = float(c.charge(buy, np.array([px]), np.array([float(q)]), np.array([dn]))[0])
                assert E.opt_charge(buy, px, q, d) == pytest.approx(ref, abs=0.011)


def test_cash_charges():
    mis = E.cash_charge(False, 1000.0, 500, delivery=False)
    # brokerage 20, STT 125, exch 14.85, SEBI 0.5, GST 0.18 * 35.35
    assert mis == pytest.approx(20 + 125 + 14.85 + 0.5 + 0.18 * (20 + 14.85 + 0.5), abs=0.01)
    dl = E.cash_charge(True, 1000.0, 500, delivery=True)
    assert dl == pytest.approx(500 + 14.85 + 0.5 + 75 + 0.18 * 15.35, abs=0.01)


def test_walk_premium_stop_fills_at_worse_of_level_and_open():
    n = 10
    po = np.full(n, 100.0)
    pl, ph = po.copy(), po.copy()
    pl[4], po[4] = 60.0, 65.0                                     # gap below the 70 stop
    sc = np.full(n, 1000.0)
    f, why, lvl = E.walk(po, pl, ph, sc, sc, sc, 1, n - 1, 1, 70.0, 0.0, np.nan, np.nan, False, -1, 0.0, 1e9, False)
    assert (f, why, lvl) == (4, E.PSTOP, 65.0)


def test_walk_band_exit_and_gap():
    n = 2 * NM
    po = np.full(n, 10.0)
    sc = np.full(n, 1000.0)
    so = sc.copy()
    sc[100] = 1013.0                                              # >= 1.25 steps of 10 above 1000
    f, why, _ = E.walk(po, po, po, sc, so, sc, 1, n - 1, 1, 0.0, 0.0, np.nan, np.nan, False, -1, 987.5, 1012.5, True)
    assert (f, why) == (101, E.BAND)
    sc[100] = 1000.0
    so[NM] = 1020.0                                               # gap at the next open
    f, why, _ = E.walk(po, po, po, sc, so, sc, 1, n - 1, 1, 0.0, 0.0, np.nan, np.nan, False, -1, 987.5, 1012.5, True)
    assert (f, why) == (NM, E.BAND)


def test_walk_underlying_stop_short_side():
    n = 50
    po = np.full(n, 10.0)
    sc = np.full(n, 1000.0)
    sc[20] = 1006.0
    f, why, _ = E.walk(po, po, po, sc, sc, sc, 1, n - 1, -1, 0.0, 0.0, 1005.0, np.nan, False, -1, 0.0, 1e9, False)
    assert (f, why) == (21, E.USTOP)


def test_twin_spec_keeps_stop_distance():
    bk = make_book(nd=10, seed=1, expiry_at=(8,))
    ref = bk.S[2, 30, 3]
    sp = E.Spec(sym="TEST", di=2, s=30, side=1, ustop=ref * 0.99)
    tw = E.twin_spec(bk, {}, sp, 5, 100)
    assert tw.ustop == pytest.approx(bk.S[5, 100, 3] * 0.99)
    assert (tw.di, tw.s, tw.side) == (5, 100, 1)


def test_final_is_locked(tmp_path, monkeypatch):
    import r58study as S
    monkeypatch.setattr(K, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(PermissionError):
        S.run_rule(RR.rules()[0], "final")


def test_universe_and_rule_count():
    assert len(K.UNIVERSE) == 10 and len(RR.rules()) == 38
    assert {r.id for r in RR.rules() if r.itm} == {"dc5-rt-itm1", "h37-60-itm1"}
    assert "LT" not in RR.stocks_for(next(r for r in RR.rules() if r.itm))
