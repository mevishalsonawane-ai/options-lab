"""R44 tests: indicators against hand-computed values, crossovers, the trade rules, the holdout guard.

    python -m pytest research/hunt/r44 -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r44cfg as K  # noqa: E402
import engine  # noqa: E402
import ind  # noqa: E402
import r44data  # noqa: E402
import sim  # noqa: E402

NAN = np.nan


# ------------------------------------------------------------------------------------------------ indicators
def test_ema_adjust_false_by_hand():
    # a = 2/(3+1) = 0.5: 10, 0.5*12+0.5*10 = 11, 0.5*14+0.5*11 = 12.5, 0.5*10+0.5*12.5 = 11.25
    assert np.allclose(ind.ema(np.array([10.0, 12.0, 14.0, 10.0]), 3), [10, 11, 12.5, 11.25])
    assert len(ind.ema(np.zeros(0), 8)) == 0


def test_rsi_wilder_by_hand():
    # n=2 on closes 1,2,4,3,5: changes +1,+2,-1,+2
    # t=2: ag=(1+2)/2=1.5, al=0 -> 100; t=3: ag=(1.5+0)/2=0.75, al=(0+1)/2=0.5 -> 100-100/(1+1.5)=60
    # t=4: ag=(0.75+2)/2=1.375, al=0.25 -> rs=5.5 -> 100-100/6.5=84.615...
    r = ind.rsi(np.array([1.0, 2.0, 4.0, 3.0, 5.0]), 2)
    assert np.isnan(r[0]) and np.isnan(r[1])
    assert r[2] == pytest.approx(100.0)
    assert r[3] == pytest.approx(60.0)
    assert r[4] == pytest.approx(100 - 100 / 6.5)


def test_rsi_flat_and_short():
    assert np.all(np.isnan(ind.rsi(np.arange(10.0), 14)))           # too short: all NaN
    assert ind.rsi(np.ones(20), 14)[14] == 50.0                      # no gain, no loss


def _adx_ref(h, l, c, n):
    """Straight transcription of Wilder (independent of ind.adx)."""
    T = len(c)
    pdm, mdm, tr = [0.0] * T, [0.0] * T, [0.0] * T
    for t in range(1, T):
        up, dn = h[t] - h[t - 1], l[t - 1] - l[t]
        pdm[t] = up if up > dn and up > 0 else 0.0
        mdm[t] = dn if dn > up and dn > 0 else 0.0
        tr[t] = max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1]))
    sp, sm, st = sum(pdm[1:n + 1]), sum(mdm[1:n + 1]), sum(tr[1:n + 1])
    dx = {}
    for t in range(n, T):
        if t > n:
            sp, sm, st = sp - sp / n + pdm[t], sm - sm / n + mdm[t], st - st / n + tr[t]
        p, m = 100 * sp / st, 100 * sm / st
        dx[t] = 100 * abs(p - m) / (p + m) if p + m else 0.0
    out = [math.nan] * T
    a = sum(dx[t] for t in range(n, 2 * n)) / n
    out[2 * n - 1] = a
    for t in range(2 * n, T):
        a = (a * (n - 1) + dx[t]) / n
        out[t] = a
    return out


def test_adx_by_hand_tiny():
    # n=2. bars (h,l,c): (10,8,9) (11,9,10) (12,10,11) (11,9,9.5) (13,10,12)
    # t=1: +DM 1, -DM 0 (dn=-1), TR max(2,|11-9|,|9-9|)=2; t=2: +DM 1, TR 2 -> seeds sp=2, sm=0, st=4 -> DX(2)=100
    # t=3: up=-1, dn=1 -> -DM 1; TR max(2,0,2)=2 -> sp=2-1+0=1, sm=0+1=1, st=4-2+2=4 -> DI 25/25 -> DX 0
    # ADX(3) = mean(DX2, DX3) = 50
    # t=4: up=2, dn=-1 -> +DM 2; TR max(3, |13-9.5|=3.5, |10-9.5|)=3.5 -> sp=1-0.5+2=2.5, sm=0.5, st=4-2+3.5=5.5
    #      DI+ = 45.45.., DI- = 9.09.. -> DX = 100*36.36/54.54 = 66.67 -> ADX(4) = (50+66.67)/2 = 58.33
    h = np.array([10, 11, 12, 11, 13.0])
    l = np.array([8, 9, 10, 9, 10.0])
    c = np.array([9, 10, 11, 9.5, 12.0])
    a = ind.adx(h, l, c, 2)
    assert np.isnan(a[:3]).all()
    assert a[3] == pytest.approx(50.0)
    assert a[4] == pytest.approx((50 + 200 / 3) / 2)


def test_adx_matches_reference_and_trend():
    rng = np.random.default_rng(1)
    c = 100 + np.cumsum(rng.normal(0, 1, 300))
    h = c + rng.uniform(0.1, 1, 300)
    l = c - rng.uniform(0.1, 1, 300)
    assert np.allclose(ind.adx(h, l, c, 14)[27:], _adx_ref(h, l, c, 14)[27:])
    up = np.arange(60.0)
    assert ind.adx(up + 0.5, up - 0.5, up, 14)[-1] == pytest.approx(100.0)   # a clean trend: ADX 100
    assert np.isnan(ind.adx(up[:20], up[:20], up[:20], 14)).all()            # too short


def test_crossover_edges():
    a = np.array([1.0, 2.0, 2.0, 3.0, 1.0, NAN, 5.0])
    b = np.array([2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0])
    # t=1: 2>2 false; t=3: 3>2 and a[2]<=b[2] (equal counts) -> True; t=6: previous NaN -> False
    assert ind.cross_up(a, b).tolist() == [False, False, False, True, False, False, False]
    assert ind.cross_dn(a, b).tolist() == [False, False, False, False, True, False, False]
    assert ind.cross_up(np.array([1.0]), 0.0).tolist() == [False]                 # t=0 never crosses
    assert ind.cross_dn(np.array([31.0, 30.0, 29.0]), 30.0).tolist() == [False, False, True]   # touch then through
    assert ind.cross_up(np.zeros(0), 1.0).tolist() == []


def test_donchian_prior_bars_only():
    h = np.array([5, 6, 7, 6, 8.0])
    l = np.array([4, 5, 6, 5, 4.0])
    c = np.array([4.5, 5.5, 6.5, 7.5, 3.0])
    lo, sh = ind.donchian(h, l, c, 2)
    # t=2: 6.5 > max(5,6)=6 -> long; t=3: 7.5 > max(6,7) -> long; t=4: 3 < min(6,5) -> short
    assert lo.tolist() == [False, False, True, True, False]
    assert sh.tolist() == [False, False, False, False, True]
    lo, sh = ind.donchian(h[:2], l[:2], c[:2], 20)
    assert not lo.any() and not sh.any()


def test_signals_warmup_and_no_lookahead():
    rng = np.random.default_rng(2)
    c = 100 + np.cumsum(rng.normal(0, 1, 400))
    h, l = c + 0.5, c - 0.5
    for kind, prm in (("ema", (8, 21, 15.0)), ("rsi_fade", (30.0, 70.0)), ("rsi_momo", (55.0, 45.0)),
                      ("donchian", (20,))):
        lo, sh = ind.signals(kind, prm, c, h, l, c)
        assert not lo[:K.WARM_BARS].any() and not sh[:K.WARM_BARS].any()
        lo2, sh2 = ind.signals(kind, prm, c[:300], h[:300], l[:300], c[:300])     # truncating the future
        assert (lo2 == lo[:300]).all() and (sh2 == sh[:300]).all()                 # changes nothing in the past


def test_bars5_aggregation_and_gaps():
    # minutes 09:15..09:24 on one day, 09:20 missing; a 09:35 minute alone; nothing for 09:25-09:34
    m = np.array([555, 556, 557, 558, 559, 561, 562, 575])
    d = np.full(len(m), 1)
    o = np.arange(len(m), dtype=float) + 1
    B = ind.bars5(m, d, o, o + 0.5, o - 0.5, o + 0.25, 555, 75)
    assert B["bar"].tolist() == [0, 1, 4]
    assert B["open"].tolist() == [1, 6, 8] and B["close"].tolist() == [5.25, 7.25, 8.25]
    assert B["high"].tolist() == [5.5, 7.5, 8.5] and B["low"].tolist() == [0.5, 5.5, 7.5]
    E = ind.bars5(np.zeros(0, int), np.zeros(0, int), *(np.zeros(0),) * 4, 555, 75)
    assert len(E["bar"]) == 0


# ------------------------------------------------------------------------------------------------ simulator
def path(nb=75, o=100.0):
    P = np.full((5, nb), NAN)
    P[0], P[1], P[2], P[3], P[4] = o, o + 1, o - 1, o, 1000.0
    return P


def test_entry_at_next_bar_open_and_time_exit_after_12_bars():
    P = path()
    P[0, 10] = 80.0                                   # the entry bar's open (signal bar 9)
    P[3, 21] = 90.0                                   # close of the 12th bar (10 + 11)
    E, X, rE, rX, xb, r = sim.run_one(P, 10, K.grid("NIFTY")["sq"], "ema821_adx15", "NIFTY")
    assert E == 80.0 and r == sim.R_TIME and xb == 21 and X == 90.0
    assert rE > E and rX < X                          # real fills are worse than raw


def test_stop_before_target_in_one_bar_and_gap_fill():
    P = path()
    P[1, 12], P[2, 12] = 200.0, 50.0                  # +100% high and -50% low in one bar
    E, X, *_, xb, r = sim.run_one(P, 10, 72, "ema821_adx15", "NIFTY")
    assert r == sim.R_STOP and xb == 12 and X == 75.0
    P = path()
    P[0, 13], P[2, 13] = 60.0, 55.0                   # gaps through the 75 stop: filled at the open
    E, X, *_, xb, r = sim.run_one(P, 10, 72, "ema821_adx15", "NIFTY")
    assert r == sim.R_STOP and xb == 13 and X == 60.0


def test_target_and_entry_bar_is_checked():
    P = path()
    P[1, 10] = 151.0                                  # the entry bar itself reaches +50%
    E, X, rE, rX, xb, r = sim.run_one(P, 10, 72, "ema821_adx15", "NIFTY")
    assert r == sim.R_TARGET and xb == 10 and X == 150.0 and rX == 150.0


def test_square_off_at_1515_open():
    sq = K.grid("NIFTY")["sq"]
    assert sq == 72                                   # 09:15 + 72*5 min = 15:15
    P = path()
    P[0, 72] = 103.0
    E, X, *_, xb, r = sim.run_one(P, 65, sq, "ema821_adx15", "NIFTY")
    assert r == sim.R_SQ and xb == 72 and X == 103.0
    assert sim.run_one(P, 72, sq, "ema821_adx15", "NIFTY")[5] == sim.R_NOFILL      # no entry at the square-off bar
    assert K.entry_bars("NIFTY") == (1, 71)


def test_missing_bars_and_no_fill():
    P = path()
    P[:, 11:20] = NAN                                 # a gap: nothing triggers inside it
    P[2, 20] = 70.0
    *_, xb, r = sim.run_one(P, 10, 72, "ema821_adx15", "NIFTY")
    assert r == sim.R_STOP and xb == 20
    P = path()
    P[:, 10] = NAN
    assert sim.run_one(P, 10, 72, "ema821_adx15", "NIFTY")[5] == sim.R_NOFILL
    P = path()
    P[:, 21] = NAN                                    # the 12th bar is empty: last known close, counted
    *_, xb, r = sim.run_one(P, 10, 72, "ema821_adx15", "NIFTY")
    assert r == sim.R_MISSING and xb == 21


def test_stepped_lock_ratchets_from_next_bar():
    P = path()
    P[1, 11] = 126.0                                  # peak +26% -> lock at entry (100) from bar 12
    P[2, 11] = 99.0                                   # same bar's low below 100 does NOT exit (lock not yet active)
    P[2, 12] = 99.5
    E, X, *_, xb, r = sim.run_one(P, 10, 72, "ema821_adx15_plock", "NIFTY")
    assert r == sim.R_LOCK and xb == 12 and X == 100.0
    P = path()                                        # no target: +80% peak -> lock +50%
    P[1, 11] = 180.0
    P[:4, 12] = [170.0, 171.0, 160.0, 165.0]          # stays above the +50% lock
    P[:4, 13] = [160.0, 161.0, 140.0, 145.0]          # falls through it: filled at the lock
    E, X, *_, xb, r = sim.run_one(P, 10, 72, "ema821_adx15_plock_nt", "NIFTY")
    assert r == sim.R_LOCK and X == 150.0 and xb == 13
    E, X, *_, r = sim.run_one(P, 10, 72, "ema821_adx15_plock", "NIFTY")             # with the target: +50% first
    assert r == sim.R_TARGET and X == 150.0


def test_costs_boss_and_real():
    res = np.array([[100.0, 110.0, 100.15, 109.9]])
    boss, real = sim.nets("NIFTY", res, np.array([date(2025, 1, 2).toordinal()]))
    assert boss[0] == pytest.approx(10 * 75 - 95)
    assert real[0] < (109.9 - 100.15) * 75 and real[0] > (109.9 - 100.15) * 75 - 120
    boss, real = sim.nets("CRUDEOIL", res, np.array([date(2026, 1, 2).toordinal()]))
    assert boss[0] == pytest.approx(10 * 100 - 200)
    assert sim.mcx_charges(100.0, 110.0, 100) == pytest.approx(
        40 + 0.0005 * 11000 + 0.000418 * 21000 + 21000 * 1e-6 + 0.00003 * 10000
        + 0.18 * (40 + 0.000418 * 21000 + 21000 * 1e-6))


# ------------------------------------------------------------------------------------------------ engine
def fake_data(und="NIFTY", ndays=3, expiry=()):
    nb = K.grid(und)["nbars"]
    days = np.array([date(2025, 1, 6).toordinal() + i for i in range(ndays)], np.int64)
    P = np.stack([path(nb) for _ in range(2 * ndays)]).astype(np.float32)
    pidx = np.full((ndays, nb, 2), -1, np.int32)
    for i in range(ndays):
        pidx[i, :, 0], pidx[i, :, 1] = 2 * i, 2 * i + 1
    bd = np.repeat(days, nb)
    bb = np.tile(np.arange(nb), ndays)
    z = np.zeros(len(bd))
    return r44data.Data(und, bd, bb, z, z, z, z, P, days, pidx, set(expiry))


def test_expiry_skip_and_entry_window():
    D = fake_data(expiry=(date(2025, 1, 7).toordinal(),))
    d0, d1 = date(2025, 1, 6).toordinal(), date(2025, 1, 7).toordinal()
    sig = pd.DataFrame(dict(d=[d0, d0, d0, d1], b=[0, 70, 71, 5], side=0, t=[0, 1, 2, 3]))
    c = engine.plan_trades(D, sig, 0, "full", keep_expiry=False)
    assert c.e.tolist() == [1, 71]                    # bar 71 -> entry 72 = square-off bar: dropped; expiry day out
    c2 = engine.plan_trades(D, sig, 0, "full", keep_expiry=True)
    assert c2.e.tolist() == [1, 71, 6]
    assert engine.plan_trades(D, sig.iloc[:0], 0, "full", False).empty


def test_one_position_and_max_two_a_day():
    D = fake_data()
    d0 = date(2025, 1, 6).toordinal()
    cand = pd.DataFrame(dict(d=[d0] * 5, e=[1, 5, 13, 30, 50], pi=[0] * 5))
    tr = engine.take_trades(D, cand, "ema821_adx15")
    # 1 -> time exit at bar 12; 5 is inside it (ignored); 13 taken; 30, 50: the day's 2 are used
    assert tr.e.tolist() == [1, 13] and tr.xb.tolist() == [12, 24]
    assert engine.take_trades(D, cand.iloc[:0], "ema821_adx15").empty


def test_mcx_grid_and_sessions():
    assert K.grid("CRUDEOIL")["sq"] == (23 * 60 + 15 - 540) // 5
    assert K.entry_bars("CRUDEOIL", "morning") == (1, 71)        # 09:05 .. 14:55
    assert K.entry_bars("CRUDEOIL", "full") == (1, K.grid("CRUDEOIL")["sq"] - 1)


def test_stats_empty_and_drawdown():
    assert engine.stats(pd.DataFrame(columns=["d", "real", "boss", "real_hist", "reason"]), 10)["trades"] == 0
    assert engine.max_dd(np.array([100.0, -50.0, -70.0, 200.0, -10.0])) == 120.0
    assert engine.max_dd(np.zeros(0)) == 0.0


def test_bh():
    # sorted p 0.01, 0.03, 0.04, 0.5 -> p*m/rank 0.04, 0.06, 0.0533, 0.5 -> running min from the top
    assert np.allclose(engine.bh(np.array([0.01, 0.04, 0.03, 0.5])), [0.04, 0.16 / 3, 0.16 / 3, 0.5])
    assert len(engine.bh(np.zeros(0))) == 0


def test_twins_deterministic_and_bounded():
    D = fake_data()
    d0 = date(2025, 1, 6).toordinal()
    tr = engine.add_nets(D, engine.take_trades(D, pd.DataFrame(dict(d=[d0], e=[1], pi=[0])), "ema821_adx15"))
    p1, t1 = engine.twins(D, tr, "ema821_adx15", 0, "full", n_twins=50)
    p2, t2 = engine.twins(D, tr, "ema821_adx15", 0, "full", n_twins=50)
    assert p1 == p2 and np.allclose(t1, t2) and 0 < p1 <= 1 and len(t1) == 50
    assert engine.twins(D, tr.iloc[:0], "ema821_adx15", 0, "full")[0] is None


def test_holdout_guard():
    engine.ALLOW_HOLDOUT = False
    for p in ("holdout", "OOS_boss", "JanAug2026", "ALL"):
        with pytest.raises(PermissionError):
            engine.check_period(p)
    engine.check_period("IS")
    engine.check_period("OOS_design")
    m = engine.in_period(np.array([date(2026, 4, 13).toordinal(), date(2026, 4, 20).toordinal()]), "OOS_design")
    assert m.tolist() == [True, False]                # the embargo is in no design period
    D = fake_data()
    D.bd = np.array([date(2026, 4, 13).toordinal(), date(2026, 4, 27).toordinal()])
    D.bb = D.bo = D.bh = D.bl = D.bc = np.zeros(2)
    assert len(engine._guard_bars(D)[0]) == 1         # bars of the holdout never reach a design computation


def test_expiry_rule():
    tv = pd.Series([2.0, 0.3, 2.5, 2.4, 0.5, 0.4], index=[1, 2, 3, 4, 5, 6])
    assert r44data.expiry_days(tv) == {2}             # 6 has no next session; 5 -> 0.4 is no jump
    assert r44data.expiry_days(pd.Series(dtype=float)) == set()


def test_lot_history():
    assert K.lot_hist("NIFTY", date(2024, 5, 1)) == 25 and K.lot_hist("NIFTY", date(2026, 1, 5)) == 65
    assert K.LOT == {"NIFTY": 75, "BANKNIFTY": 30, "FINNIFTY": 40, "MIDCPNIFTY": 75, "SENSEX": 20, "CRUDEOIL": 100}
