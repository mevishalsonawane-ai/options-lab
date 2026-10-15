"""R47 tests: fills and charges against research/obuy, fixed-contract pricing (stops, gaps, overnight holds, targets,
locks, the ATM+-9 edge, expiry square-off, conservative values out of the lake's rows), debit spreads and straddles,
budget sizing, expiry recovery, filters, the look-ahead guard of every signal family, and the holdout lock.

    python -m pytest research/hunt/r47 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r47cfg as K  # noqa: E402
import r47data as D  # noqa: E402
import r47price as P  # noqa: E402
import r47run as R  # noqa: E402
import r47sig as S  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402

N = K.N
NAN = np.nan


# ------------------------------------------------------------------------------------------------ synthetic data
def make_store(contracts, nd=3, und="NIFTY", series="WEEK"):
    """contracts: list of dicts(strike, side, exp (session index or None), rows=[(g, o, h, l, c, v, off)])."""
    g, o, h, l, c, v, off = [], [], [], [], [], [], []
    cs, ce, ck, cd, cx = [], [], [], [], []
    offcid = np.full((nd, N, 2, 21), -1, dtype=np.int32)
    for i, k in enumerate(contracts):
        cs.append(len(g))
        for (gg, oo, hh, ll, cc, vv, ff) in sorted(k["rows"]):
            g.append(gg); o.append(oo); h.append(hh); l.append(ll); c.append(cc); v.append(vv); off.append(ff)
            offcid[gg // N, gg % N, 0 if k["side"] > 0 else 1, ff + 10] = i
        ce.append(len(g))
        ck.append(k["strike"]); cd.append(k["side"]); cx.append(D.BIG if k.get("exp") is None else k["exp"])
    f32 = lambda x: np.array(x, dtype=np.float32)  # noqa: E731
    return D.Store(und, series, np.array(g, dtype=np.int64), f32(o), f32(h), f32(l), f32(c), f32(v),
                   np.array(off, dtype=np.int8), np.array(cs, dtype=np.int64), np.array(ce, dtype=np.int64),
                   np.array(ck, dtype=np.float64), np.array(cd, dtype=np.int8), np.array(cx, dtype=np.int64), offcid,
                   np.zeros(0, dtype=np.int64), np.full((nd, N), NAN), np.full((nd, N), NAN),
                   np.full((nd, N), NAN, dtype=np.float32))


def rows(day, m0, prices, off=0, vol=1e6):
    """Flat rows (o=h=l=c=p) from minute m0 on session `day`; a tuple p = (o, h, l, c)."""
    out = []
    for j, p in enumerate(prices):
        o_, h_, l_, c_ = p if isinstance(p, tuple) else (p, p, p, p)
        out.append((day * N + m0 + j, o_, h_, l_, c_, vol, off))
    return out


def sim(st, g_dec, cid0, exit_g, stop=0.0, tgt=0.0, arm=0.0, keep=0.0, cid1=-1, sg1=0, pos=False, lot=50,
        last_g=None, spot=None):
    nd = st.offcid.shape[0]
    spot = np.full(nd * N, 20000.0) if spot is None else spot
    last_g = nd * N - 1 if last_g is None else last_g
    return P.sim_position(np.int64(g_dec), np.int64(cid0), np.int64(cid1), np.int8(1), np.int8(sg1), np.int64(exit_g),
                          stop, tgt, arm, keep, np.int64(lot), pos, np.int64(last_g), st.g, st.o, st.h, st.l, st.c,
                          st.v, st.off, st.cstart, st.cend, st.cstrike, st.cside, st.cexp, st.offcid, spot, spot,
                          K.EDGE, K.FILL_WAIT, K.EXPIRY_SQ)


# ------------------------------------------------------------------------------------------------ fills and charges
@pytest.mark.parametrize("p", [0.05, 1.0, 3.35, 99.95, 101.27, 555.55, 1234.5])
@pytest.mark.parametrize("lots5", [0.0, 5.0, 25.0])
def test_fills_match_obuy_liq(p, lots5):
    f = Fills("liq")
    assert P.buy_fill(p, lots5) == pytest.approx(float(f.buy(np.array([p]), np.array([lots5]))[0]), abs=1e-9)
    assert P.sell_fill(p, lots5, False) == pytest.approx(float(f.sell(np.array([p]), np.array([lots5]))[0]), abs=1e-9)
    assert P.sell_fill(p, lots5, True) == pytest.approx(float(f.sell(np.array([p]), np.array([lots5]), stop=True)[0]), abs=1e-9)


def test_charges_per_order_match_obuy_dated():
    c = Costs("dated")
    dn = np.array([date(2024, 5, 2).toordinal() - date(1970, 1, 1).toordinal()] * 2)
    got = P.charges(np.array([True, False]), np.array([100.0, 120.0]), np.array([75, 75]), dn, False)
    assert got[0] == pytest.approx(c.charge(True, np.array([100.0]), np.array([75]), dn[:1])[0])
    assert got[1] == pytest.approx(c.charge(False, np.array([120.0]), np.array([75]), dn[:1])[0])


def test_size_lots_budget_cap_and_volume():
    assert P.size_lots(np.array([100.0]), 50, np.array([1000.0]))[0] == 4          # 20000 / 5000
    assert P.size_lots(np.array([1.0]), 50, np.array([1e6]))[0] == K.LOT_CAP        # 400 lots -> cap 20
    assert P.size_lots(np.array([1.0]), 50, np.array([30.0]))[0] == 3              # 10% of 30 lots
    assert P.size_lots(np.array([2000.0]), 50, np.array([1e6]))[0] == 1            # one lot over budget -> 1


def test_settle_long_option_by_hand():
    d = np.array([date(2024, 5, 2).toordinal()])
    net, lots, outlay, ch = P.settle(d, np.array([50]), np.array([1]), np.array([0]), np.array([100.0]),
                                     np.array([0.0]), np.array([110.0]), np.array([0.0]), np.array([1000.0]),
                                     np.array([0.0]), np.array([False]), False)
    assert lots[0] == 4 and outlay[0] == pytest.approx(20000.0)
    c = Costs("dated")
    dn = d - date(1970, 1, 1).toordinal()
    exp = 10 * 200 - c.charge(True, np.array([100.0]), np.array([200]), dn)[0] - c.charge(False, np.array([110.0]), np.array([200]), dn)[0]
    assert net[0] == pytest.approx(exp)


def test_settle_debit_spread_short_leg_pays_buyback():
    d = np.array([date(2025, 1, 6).toordinal()])
    net, lots, outlay, ch = P.settle(d, np.array([75]), np.array([1]), np.array([-1]), np.array([100.0]),
                                     np.array([40.0]), np.array([130.0]), np.array([55.0]), np.array([1000.0]),
                                     np.array([1000.0]), np.array([True]), False)
    q = lots[0] * 75
    assert lots[0] == 4                                     # 60 x 75 = 4500 a lot
    assert net[0] + ch[0] == pytest.approx(((130 - 100) - (55 - 40)) * q)
    assert ch[0] > 4 * 20 * 1.18 - 1e-6                     # four orders, brokerage on each


# ------------------------------------------------------------------------------------------------ single leg
def test_entry_fills_next_minute_open_and_time_exit():
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, [100] * 100))])
    r = sim(st, 9, 0, 60)
    assert r[0] == P.OK and r[1] == 10                                   # fill at minute 10
    assert r[3] == P.buy_fill(100, 1e6 / 50 * 5 / 5)                     # lots in 5 minutes before
    assert r[2] == 60 and r[9] == P.R_TIME and r[5] == P.sell_fill(100, 100.0, False)


def test_no_row_within_three_minutes_means_no_trade():
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, [100] * 5) + rows(0, 20, [100] * 5))])
    assert sim(st, 9, 0, 60)[0] == P.NO_FILL


def test_stop_fills_at_level_or_worse_open():
    p = [100, 100, 100, (95, 96, 89, 90), 90]           # entry at minute 1 open 100; stop 10% = 90
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, p))])
    r = sim(st, 0, 0, 60, stop=0.10)
    assert r[9] == P.R_STOP and r[2] == 3
    assert r[5] == P.sell_fill(P.buy_fill(100, 1e6 / 50) * 0.9, 1e6 * 3 / 50, True)
    p = [100, 100, (80, 82, 79, 81), 81]                # opens through the stop: filled at the open (worse)
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, p))])
    r = sim(st, 0, 0, 60, stop=0.10)
    assert r[9] == P.R_STOP and r[5] == P.sell_fill(80, 1e6 * 2 / 50, True)


def test_stop_before_target_in_the_same_minute():
    p = [100, 100, (100, 200, 50, 100), 100]
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, p))])
    assert sim(st, 0, 0, 60, stop=0.2, tgt=0.5)[9] == P.R_STOP


def test_target_and_lock():
    p = [100, 100, (100, 160, 99, 150), 150]
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, p))])
    r = sim(st, 0, 0, 60, tgt=0.5)
    e = P.buy_fill(100, 1e6 / 50)
    assert r[9] == P.R_TGT and r[5] == P.sell_fill(e * 1.5, 1e6 * 2 / 50, False)
    # lock: armed by minute 2's high (+30%), filled at +10% when minute 3 falls back
    p = [100, 100, (100, 135, 100, 132), (132, 133, 131, 132), (132, 132, 105, 106), 106]
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, p))])
    r = sim(st, 0, 0, 60, stop=0.3, arm=0.3, keep=0.1)
    assert r[9] == P.R_LOCK and r[2] == 4


def test_stray_open_print_never_helps_the_fill():
    # amendment 1: a 09:15-style stray open of 500 on a contract near 60 is sold at the median close, not at 500
    p = rows(0, 0, [60] * 5) + [(5, 500.0, 500.0, 59.0, 60.0, 1e6, 0)] + rows(0, 6, [60] * 5)
    st = make_store([dict(strike=20000, side=1, rows=p)])
    r = sim(st, 0, 0, 5)
    assert r[9] == P.R_TIME and r[5] == P.sell_fill(60.0, 1e6 * 4 / 50, False)
    # bought on a stray LOW open: paid the median, not the print
    p = rows(0, 0, [60] * 5) + [(5, 5.0, 61.0, 5.0, 60.0, 1e6, 0)] + rows(0, 6, [60] * 5)
    st = make_store([dict(strike=20000, side=1, rows=p)])
    assert sim(st, 4, 0, 9)[3] == P.buy_fill(60.0, 1e6 * 4 / 50)
    # a genuine move (the closes follow the open) is filled at the open
    p = rows(0, 0, [60] * 5) + rows(0, 5, [90] * 5)
    st = make_store([dict(strike=20000, side=1, rows=p)])
    assert sim(st, 0, 0, 5)[5] == P.sell_fill(90.0, 1e6 * 4 / 50, False)


def test_stray_high_cannot_hit_a_target():
    p = rows(0, 0, [100] * 3) + [(3, 100.0, 400.0, 99.0, 100.0, 1e6, 0)] + rows(0, 4, [100] * 5)
    st = make_store([dict(strike=20000, side=1, rows=p)])
    assert sim(st, 0, 0, 8, tgt=1.0)[9] == P.R_TIME


def test_lock_armed_by_a_minute_is_not_filled_by_its_own_low():
    p = [100, 100, (100, 135, 104, 120), 120, 120]
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, p))])
    r = sim(st, 0, 0, 4, stop=0.5, arm=0.3, keep=0.1)
    assert r[9] == P.R_TIME


def test_overnight_gap_stop_fills_at_next_session_open():
    p0 = rows(0, 360, [100] * 15)                       # 15:15 .. 15:29
    p1 = rows(1, 0, [(50, 55, 45, 52)] + [52] * 10)
    st = make_store([dict(strike=20000, side=1, rows=p0 + p1)])
    r = sim(st, 364, 0, 1 * N + K.SQ, stop=0.3, pos=True)
    # the 5 minutes before the open are the previous session's last 5 rows (the g grid runs on across sessions)
    assert r[9] == P.R_STOP and r[2] == N and r[5] == P.sell_fill(50, 5 * 1e6 / 50, True)


def test_overnight_hold_time_exit_next_morning():
    p0 = rows(0, 360, [100] * 15)
    p1 = rows(1, 0, [120] * 10)
    st = make_store([dict(strike=20000, side=1, rows=p0 + p1)])
    r = sim(st, 364, 0, N + K.MORNING_EXIT, pos=True)
    assert r[9] == P.R_TIME and r[2] == N + 1 and r[5] == P.sell_fill(120, 1e6 / 50, False)


def test_contract_missing_at_next_open_is_valued_at_intrinsic():
    p0 = rows(0, 360, [100] * 15)
    p1 = rows(1, 30, [5] * 10)                          # back only at 09:45 (was beyond +-10 at the open)
    st = make_store([dict(strike=20000, side=1, rows=p0 + p1)])
    spot = np.full(3 * N, 20000.0)
    spot[N:] = 20300.0                                  # 300 in the money at the next open
    r = sim(st, 364, 0, 2 * N + 100, pos=True, spot=spot)
    assert r[9] == P.R_GAPOUT and r[10] and r[5] == P.sell_fill(300.0, 0.0, True)


def test_edge_exit_at_atm_plus_minus_nine():
    p = rows(0, 0, [100] * 5) + [(5, 150, 150, 150, 150, 1e6, 9)] + rows(0, 6, [160] * 5)
    st = make_store([dict(strike=20000, side=1, rows=p)])
    r = sim(st, 0, 0, 60)
    assert r[9] == P.R_EDGE and r[2] == 6 and r[5] == P.sell_fill(160, 1e6 * 5 / 50, False)


def test_expiry_square_off_and_no_positional_entry_on_expiry_day():
    st = make_store([dict(strike=20000, side=1, exp=0, rows=rows(0, 0, [100] * N))])
    r = sim(st, 100, 0, K.SQ)
    assert r[9] == P.R_EXPIRY and r[2] == K.EXPIRY_SQ
    assert sim(st, 364, 0, N + 1, pos=True)[0] == P.EXP_DAY


def test_trade_past_the_phase_end_is_dropped():
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 360, [100] * 15))], nd=1)
    assert sim(st, 364, 0, N + 1, pos=True, last_g=N - 1)[0] == P.PAST_END


def test_sequential_batch_skips_overlaps():
    st = make_store([dict(strike=20000, side=1, rows=rows(0, 0, [100] * 200))])
    out = P.sim_batch(np.array([0, 10, 70], dtype=np.int64), np.zeros(3, dtype=np.int64), np.full(3, -1, dtype=np.int64),
                      np.ones(3, dtype=np.int8), np.zeros(3, dtype=np.int8), np.array([60, 70, 130], dtype=np.int64),
                      0.0, 0.0, 0.0, 0.0, np.full(3, 50, dtype=np.int64), np.zeros(3, dtype=np.bool_), np.int64(3 * N),
                      st.g, st.o, st.h, st.l, st.c, st.v, st.off, st.cstart, st.cend, st.cstrike, st.cside, st.cexp,
                      st.offcid, np.full(3 * N, 2e4), np.full(3 * N, 2e4), K.EDGE, K.FILL_WAIT, K.EXPIRY_SQ, True)
    assert list(out[0]) == [P.OK, -1, P.OK]


# ------------------------------------------------------------------------------------------------ two legs
def test_debit_spread_stop_on_net_value_exits_next_minute():
    a = rows(0, 0, [100, 100, 100, 70, 69, 69])        # long leg
    b = rows(0, 0, [40, 40, 40, 30, 29, 29], off=3)    # short leg
    st = make_store([dict(strike=20000, side=1, rows=a), dict(strike=20150, side=1, rows=b)])
    r = sim(st, 0, 0, 60, stop=0.3, cid1=1, sg1=-1)
    # entry value ~ 100.15 - 39.9 = 60; minute 3 close: 70 - 30 = 40 < 42 -> exit at minute 4 opens
    assert r[9] == P.R_STOP and r[2] == 4
    assert r[5] == P.sell_fill(69, 1e6 * 4 / 50, True) and r[6] == P.buy_fill(29, 1e6 * 4 / 50)


def test_straddle_time_exit_both_legs():
    a = rows(0, 0, [100] * 70)
    b = rows(0, 0, [90] * 70)
    st = make_store([dict(strike=20000, side=1, rows=a), dict(strike=20000, side=-1, rows=b)])
    r = sim(st, 0, 0, 60, cid1=1, sg1=1)
    assert r[9] == P.R_TIME and r[5] == P.sell_fill(100, 1e6 * 5 / 50, False) and r[6] == P.sell_fill(90, 1e6 * 5 / 50, False)


def test_short_leg_out_of_rows_valued_at_an_upper_bound():
    a = rows(0, 360, [300] * 15) + rows(1, 0, [400] * 10)
    b = rows(0, 360, [100] * 15, off=2)                # short leg missing the next morning
    cov = rows(1, 0, [380] * 10, off=-10)              # a covered strike 20200 call at the next open
    st = make_store([dict(strike=20000, side=1, rows=a), dict(strike=20100, side=1, rows=b),
                     dict(strike=20200, side=1, rows=cov)])
    r = sim(st, 364, 0, 2 * N, cid1=1, sg1=-1, pos=True)
    # C(20100) <= min(C(20000) = 400, C(20200) + 100 = 480): the tightest bound, bought back with the thin-market fill
    assert r[9] == P.R_GAPOUT and r[10] and r[6] == P.buy_fill(400.0, 0.0)


# ------------------------------------------------------------------------------------------------ expiries
def test_expiries_from_time_value_and_gap_refill():
    tv = np.array([3.0, 2.0, 0.3, 3.0, 2.0, 1.0, 0.8, 3.0, 2.5, 0.2, 3.0, 1.0])
    days = np.array([0, 1, 2, 5, 6, 7, 8, 9, 12, 13, 14, 15]) + 738000
    got = D.expiries(tv, days=days)
    assert 2 in got and 9 in got and 11 not in got
    tv2 = np.array([0.3, 3, 2.8, 2.6, 2.4, 2.2, 2.0, 1.8, 1.6, 1.0, 1.9, 1.7, 1.5, 0.3, 3])
    got2 = D.expiries(tv2, weekly_gap=10, days=np.arange(15) + 738000)      # a 13-day gap: one weekly expiry missed
    assert 9 in got2 and 0 in got2 and 13 in got2


def test_rule_expiries_move_to_previous_session_and_merge_keeps_data():
    days = np.array([date(2021, 11, x).toordinal() for x in (1, 2, 3, 5, 8, 9, 10, 11, 12)])   # 4 Nov: holiday
    got = D.rule_expiries("NIFTY", "WEEK", days)
    assert [date.fromordinal(int(days[i])) for i in got] == [date(2021, 11, 3), date(2021, 11, 11)]
    merged = D.merge_expiries([2], got, days)
    assert merged == [2, 7]                                  # 3 Nov from the data, 11 Nov added by the rule


# ------------------------------------------------------------------------------------------------ signals: look-ahead
def make_und(nd=70, seed=0):
    rng = np.random.default_rng(seed)
    base = date(2021, 8, 27)                            # FIRST_DAY is set to 1 Sep 2021 below: 5 warm-up sessions
    days = np.array([base.toordinal() + i for i in range(nd)], dtype=np.int64)
    path = 20000 * np.exp(np.cumsum(rng.normal(0, 4e-4, nd * N))).reshape(nd, N)
    O = path * (1 + rng.normal(0, 1e-4, path.shape))
    C = path
    H = np.maximum(O, C) * (1 + np.abs(rng.normal(0, 2e-4, path.shape)))
    L = np.minimum(O, C) * (1 - np.abs(rng.normal(0, 2e-4, path.shape)))
    vix = 15 + np.cumsum(rng.normal(0, 0.05, nd * N)).reshape(nd, N)
    st = make_store([], nd=nd)
    st.oi_c = rng.uniform(1e6, 2e6, (nd, N))
    st.oi_p = rng.uniform(1e6, 2e6, (nd, N))
    st.iv_atm = rng.uniform(10, 20, (nd, N)).astype(np.float32)
    st.exp_di = np.arange(3, nd, 5, dtype=np.int64)
    u = D.Und("NIFTY", "design", days, O, H, L, C, D.daily_close(days, C), vix, D.daily_close(days, vix),
              {"WEEK": st}, nd - 1)
    return u


def _cut(u, di, m, seed):
    """A copy of u whose index / VIX / chain values after (di, m) are replaced by noise."""
    import copy
    v = copy.deepcopy(u)
    rng = np.random.default_rng(seed)
    for arr in (v.O, v.H, v.L, v.C, v.vixC):
        flat = arr.reshape(-1)
        flat[di * N + m + 1:] = flat[di * N + m + 1:] * rng.uniform(0.9, 1.1, len(flat) - (di * N + m + 1))
    st = v.stores["WEEK"]
    for arr in (st.oi_c, st.oi_p, st.iv_atm):
        flat = arr.reshape(-1)
        flat[di * N + m + 1:] = flat[di * N + m + 1:] * rng.uniform(0.5, 1.5, len(flat) - (di * N + m + 1)).astype(flat.dtype)
    v.dclose = D.daily_close(v.days, v.C)
    v.vix_dclose = D.daily_close(v.days, v.vixC)
    return v


LOOKAHEAD_CASES = [
    ("U1_orb", dict(or_min=15)), ("U2_gap", dict(th=0.0, how="follow")), ("U3_lateday", dict(th=0.0, how="fade")),
    ("U4_donchian", dict(n=10)), ("U5_daily_mr", dict(kind="streak3")), ("U6_ema", dict(f=5, s=20, vwap=True)),
    ("U7_bigbar", dict(k=1.2, how="follow")), ("U8_pdhl", dict(how="break")), ("U9_vix", dict(th=-1.0)),
    ("U10_trendday", dict(th=0.0, at=(13, 0))), ("C1_pcr", dict(at=(10, 15), how="follow")),
    ("C2_ivcheap", dict(pct=0.5, when="day")), ("C3_oishift", dict(how="fade")),
    ("E3_expiry_hero", dict(at=(13, 30), th=0.0)),
]


@pytest.mark.parametrize("fam,p", LOOKAHEAD_CASES)
def test_signals_read_nothing_after_the_decision_minute(fam, p, monkeypatch):
    monkeypatch.setattr(K, "FIRST_DAY", date(2021, 9, 1))
    monkeypatch.setattr(S, "event_days", lambda u: set())
    u = make_und()
    full = S.SIGNALS[fam](u, **p)
    assert len(full) > 0
    rng = np.random.default_rng(1)
    for t, di in enumerate(rng.integers(62, 69, 4)):
        for m in (10, 64, 200, 363, 364):
            cut = S.SIGNALS[fam](_cut(u, int(di), m, t), **p)
            keep_f = (full.di < di) | ((full.di == di) & (full.m <= m))
            keep_c = (cut.di < di) | ((cut.di == di) & (cut.m <= m))
            assert np.array_equal(full.di[keep_f], cut.di[keep_c]), (fam, di, m)
            assert np.array_equal(full.m[keep_f], cut.m[keep_c]) and np.array_equal(full.d[keep_f], cut.d[keep_c])


def test_option_chart_signal_reads_only_its_contract_up_to_the_decision():
    nd = 70
    u = make_und(nd)
    rs = []
    for di in range(nd):
        prices = [100 + (j % 40) * (1 if di % 2 else -0.5) for j in range(N)]
        rs += rows(di, 0, prices)
    st = make_store([dict(strike=20000, side=1, rows=rs)], nd=nd)
    u.stores["WEEK"] = st
    full = S.o2_prem_mom(u, 0.2)
    assert len(full) > 0 and set(full.cid) == {0}
    i = 0
    di, m = int(full.di[i]), int(full.m[i])
    # prices after the decision minute changed: the same signal
    st2 = make_store([dict(strike=20000, side=1, rows=[r if r[0] <= di * N + m else (r[0], 1, 1, 1, 1, r[5], 0) for r in rs])], nd=nd)
    u.stores["WEEK"] = st2
    cut = S.o2_prem_mom(u, 0.2)
    assert (int(cut.di[i]), int(cut.m[i])) == (di, m)


def test_prev_rank_uses_only_earlier_sessions():
    x = np.arange(100, dtype=float)
    r = S.prev_rank(x, look=60, min_n=40)
    assert np.isnan(r[39]) and r[40] == 1.0 and r[99] == 1.0
    y = x.copy()
    y[80:] = -1
    assert S.prev_rank(y)[79] == r[79]


def test_filters_and_day_features(monkeypatch):
    monkeypatch.setattr(K, "FIRST_DAY", date(2021, 9, 1))
    monkeypatch.setattr(S, "event_days", lambda u: {10})
    u = make_und()
    f = S.day_features(u)
    di = np.arange(5, 15)
    assert S.filter_mask("event", f, di).tolist() == [i in (9, 10, 11) for i in di]
    assert np.isnan(f["vix_prev"][0]) and f["vix_prev"][5] == u.vix_dclose[4]
    assert f["dte"][3] == 0 and f["dte"][2] == 1 and f["dte"][4] == 4
    assert all(S.filter_mask(n, f, di).dtype == bool for n in K.FILTERS)


# ------------------------------------------------------------------------------------------------ runner
def test_legs_pick_strikes_at_the_decision_minute_by_side_and_offset():
    nd = 2
    cs = []
    for k, (side, off) in enumerate([(1, 0), (1, 3), (-1, 0), (-1, -2), (1, 2)]):
        cs.append(dict(strike=20000 + 50 * off, side=side, rows=rows(0, 100, [100] * 5, off=off)))
    st = make_store(cs, nd=nd)
    u = make_und(nd)
    u.stores = {"WEEK": st}
    ev = S.Events(np.array([0, 0, 0]), np.array([102, 102, 102]), np.array([1, -1, 0], dtype=np.int8), np.full(3, -1))
    c0, c1, s0, s1 = R.legs_for(u, ev, "sp_atm_otm3")
    assert (c0[0], c1[0], s1[0]) == (0, 1, -1) and c0[1] == -1          # no put OTM3 leg -> skipped
    c0, c1, s0, s1 = R.legs_for(u, ev, "strangle2")
    assert (c0[2], c1[2], s1[2]) == (4, 3, 1)
    c0, *_ = R.legs_for(u, ev, "atm")
    assert list(c0[:2]) == [0, 2]


def test_exit_minutes():
    ev = S.Events(np.array([2, 2]), np.array([100, 364]), np.array([1, 1], dtype=np.int8), np.full(2, -1))
    g, pos = R.exit_minutes(ev, (0, 0, 60, None))
    assert list(g) == [2 * N + 161, 2 * N + K.SQ] and not pos.any()
    g, pos = R.exit_minutes(ev, (0, 0, "am", None))
    assert list(g) == [3 * N + 1] * 2 and pos.all()
    g, pos = R.exit_minutes(ev, (0, 0, 3, None))
    assert list(g) == [5 * N + K.SQ] * 2
    g, pos = R.exit_minutes(ev, (0, 0, "sq1", None))
    assert list(g) == [2 * N + K.SQ, 3 * N + K.SQ] and list(pos) == [False, True]


def test_block_of():
    b = R.block_of([date(2021, 8, 2).toordinal(), date(2023, 6, 1).toordinal(), date(2025, 10, 31).toordinal(),
                    date(2025, 11, 3).toordinal()])
    assert list(b) == [0, 1, 3, -1]


# ------------------------------------------------------------------------------------------------ holdout lock
def test_full_phase_needs_the_holdout_key():
    with pytest.raises(RuntimeError):
        D.phase_end("full")
    with pytest.raises(RuntimeError):
        D.phase_end("full", key=object())
    assert D.phase_end("design") == K.DESIGN_END


def test_holdout_key_refuses_without_candidates_or_after_a_read(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "CANDIDATES", tmp_path / "candidates.json")
    monkeypatch.setattr(K, "HOLDOUT_JSON", tmp_path / "holdout.json")
    with pytest.raises(RuntimeError, match="no frozen candidates"):
        D.holdout_key(check_git=False)
    (tmp_path / "candidates.json").write_text("{}")
    assert D.holdout_key(check_git=False) is not None
    (tmp_path / "holdout.json").write_text("{}")
    with pytest.raises(RuntimeError, match="already read"):
        D.holdout_key(check_git=False)


def test_design_guard_refuses_holdout_dates():
    D.assert_design_days([K.DESIGN_END])
    with pytest.raises(ValueError):
        D.assert_design_days([K.DESIGN_END, K.HOLDOUT_START])


def test_split_is_ordered():
    assert K.BLOCKS[-1][2] == K.DESIGN_END < K.EMBARGO_END < K.HOLDOUT_START
    assert (K.HOLDOUT_START - K.DESIGN_END).days > 7                      # a 5-session hold cannot reach the holdout


# ------------------------------------------------------------------------------------------------ selection + BH
def test_tstat_and_walk_forward_use_only_earlier_blocks():
    import pandas as pd
    import r47select as SEL
    assert SEL.tstat(4, 8.0, 20.0) == pytest.approx(2 / np.sqrt((20 - 16) / 3 / 4))
    base = dict(params="{}", instr="atm", exit=0, filt="all", exit_spec="[]", n=0, net=0.0, top5=0.0, fb=0, outlay=1.0)
    # variant a: great in B1, bad after; variant b: modest in B1-B2, good after
    a = dict(base, instr="a", n0=50, s0=5000.0, q0=5000.0 * 120, n1=50, s1=-3000.0, q1=1e6, n2=50, s2=-3000.0, q2=1e6, n3=50, s3=-1.0, q3=1e6)
    b = dict(base, instr="b", n0=50, s0=500.0, q0=1e6, n1=50, s1=4000.0, q1=1e6, n2=50, s2=4000.0, q2=1e6, n3=50, s3=4000.0, q3=1e6)
    wf = SEL.walk_forward(pd.DataFrame([a, b]))
    assert [p["instr"] for p in wf["picks"]] == ["a", "b", "b"]        # B2 is picked from B1 alone
    assert wf["oos_n"] == 150 and wf["oos_net"] == pytest.approx(-3000 + 8000) and wf["green"] == 2


def test_candidate_rule_needs_blocks_and_top5():
    import pandas as pd
    import r47select as SEL
    base = dict(params="{}", exit=0, filt="all", exit_spec="[]", fb=0, outlay=1.0)
    lucky = dict(base, instr="lucky", n=200, net=1000.0, top5=5000.0, **{f"n{k}": 50 for k in range(4)},
                 **{f"s{k}": 250.0 for k in range(4)}, **{f"q{k}": 1e6 for k in range(4)})
    one_year = dict(base, instr="one", n=200, net=1000.0, top5=10.0, **{f"n{k}": 50 for k in range(4)},
                    s0=4000.0, s1=-1000.0, s2=-1000.0, s3=-1000.0, **{f"q{k}": 1e6 for k in range(4)})
    assert SEL.candidate(pd.DataFrame([lucky, one_year])) is None
    fine = dict(one_year, instr="fine", s0=400.0, s1=-100.0, s2=400.0, s3=300.0)
    assert SEL.candidate(pd.DataFrame([lucky, one_year, fine]))["instr"] == "fine"


def test_bh_q_values():
    import r47holdout as H
    q = H.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.16 / 3, 0.16 / 3, 0.5])         # 0.03 x 4/2 = 0.06 > 0.04 x 4/3


def test_foreign_rows_are_dropped():
    # amendment 2: rows whose own spot is another underlying's level (FINNIFTY file, MIDCPNIFTY-level spot)
    C = np.full((2, N), 25000.0)
    C[1, 5] = np.nan                                     # a missing index minute: the session's last close is used
    spot = np.array([25100.0, 12500.0, 25000.0 * 1.031, 24990.0, np.nan])
    got = D.foreign_rows(spot, np.array([0, 0, 0, 1, 0]), np.array([3, 4, 5, 5, 6]), C)
    assert got.tolist() == [False, True, True, False, True]


def test_sessions_never_start_where_a_previous_session_would_wrap():
    u = make_und(10)
    u.days = u.days + 0                                   # the index starts after FIRST_DAY (BANKNIFTY: 4 Aug 2021)
    assert S.sessions_from(u, date(2000, 1, 1)).start == 3
