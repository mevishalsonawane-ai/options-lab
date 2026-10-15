"""R55 tests: the armed trail (T0 = R54's tr25, T2 / T3 / T5 armed after a run-up, no stop before arming), hold, the
end times, expiry settlement (last-30-minute mean, ITM intrinsic, OTM worthless with no sell charges, STT on exercise at
the dated rate, no spread), metrics, and the look-ahead guard on real days (skipped when the lake cache is absent).

    python -m pytest research/hunt/r55/test_r55.py -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r55cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r50lib as L50  # noqa: E402
import r54data as DD  # noqa: E402
import r54lib as LB  # noqa: E402
import r55lib as L  # noqa: E402

N = D.N
K_C = 25100.0
T = LB.bar_close("1230")
S00 = D.minute(15, 0)


@pytest.fixture(autouse=True)
def no_late():
    saved = dict(LB.LATE)
    LB.LATE.clear()
    yield
    LB.LATE.clear()
    LB.LATE.update(saved)


def make_day(path, d=date(2025, 6, 5), und="NIFTY", lot=75, vol=100.0, k=K_C, side="C", spot=25000.0):
    """One option whose 1-minute closes follow `path` (dict row -> price; flat between), open = previous close."""
    ix = np.full((N, 4), spot)
    a = np.full((N, 7), np.nan)
    p = path[min(path)]
    prev = p
    for r in range(N):
        if r in path:
            p = path[r]
        a[r, D.O], a[r, D.H], a[r, D.L], a[r, D.C] = prev, max(prev, p), min(prev, p), p
        a[r, D.V] = vol * lot
        a[r, D.OI] = 1000.0
        prev = p
    day = D.Day(d, und, ix, {(k, side): a}, np.full(N, 14.0), spot, 14.0, lot, None, 100.0)
    day._clean = True
    return day


def run(day, end="1505", exit_="T0", side=1, mult=1.0):
    s = "C" if side > 0 else "P"
    a = day.opts[(K_C, s)]
    return L.simulate(day, K_C, side, T, float(a[T, D.C]), end, exit_, mult)


# ------------------------------------------------------------------------------------------------ rows
def test_rows_and_cells():
    assert L.ENTRY_ROW["1230"] == D.minute(12, 29) and L.ENTRY_ROW["1430"] == D.minute(14, 29)
    assert L.END_ROW["1505"] == R.EXIT_ROW and L.END_ROW["1515"] == D.minute(15, 15) and L.END_ROW["SETTLE"] == N
    assert K.ARM == {"T0": 0.0, "T2": 2.0, "T3": 3.0, "T5": 5.0, "H": None}
    assert len(K.ENTRIES) * len(K.ENDS) * len(K.EXITS) == 30


# ------------------------------------------------------------------------------------------------ T0 = R54 tr25
PATHS = [
    {0: 10.0},
    {0: 10.0, T + 5: 7.4},
    {0: 10.0, T + 10: 30.0, T + 20: 23.0, T + 30: 22.4},
    {0: 10.0, T + 3: 13.0, T + 8: 9.0, T + 40: 50.0, T + 60: 30.0},
]


@pytest.mark.parametrize("path", PATHS)
@pytest.mark.parametrize("end", ["1505", "1515"])
def test_t0_is_r54_tr25_exactly(path, end):
    day = make_day(path)
    a = run(day, end=end, exit_="T0")
    b = LB.simulate(day, K_C, 1, T, float(day.opts[(K_C, "C")][T, D.C]), end, "tr25")
    assert a.exits == b.exits and a.net == b.net and a.peak_mult == b.peak_mult


@pytest.mark.parametrize("end", ["1505", "1515"])
def test_hold_is_r54_hold_exactly(end):
    day = make_day(PATHS[3])
    a = run(day, end=end, exit_="H")
    b = LB.simulate(day, K_C, 1, T, 10.0, end, "hold")
    assert a.exits == b.exits and a.net == b.net


# ------------------------------------------------------------------------------------------------ armed trails
@pytest.mark.parametrize("x", ["T2", "T3", "T5"])
def test_armed_trail_has_no_stop_before_arming(x):
    day = make_day({0: 10.0, T + 5: 3.0})                   # -70%: T0 sells, an armed trail does not
    assert run(day, exit_="T0").exits[-1][3] == "trail"
    tr = run(day, exit_=x)
    assert tr.exits[-1][3] == "end1505" and tr.armed_row is None


def test_t2_arms_at_2x_and_trails_25pct_from_the_best_close_since():
    day = make_day({0: 10.0, T + 10: 21.0, T + 20: 40.0, T + 30: 30.1, T + 40: 29.9})
    tr = run(day, exit_="T2")
    e = tr.entry
    assert 21.0 >= 2 * e                                    # 21 arms it (fill is ~10 + spread)
    assert tr.armed_row == T + 10
    r, q, px, why = tr.exits[-1]
    assert why == "trail" and r == T + 41                   # 29.9 <= 0.75 x 40 = 30; 30.1 is not
    assert px == pytest.approx(R.sell_px(29.9, 100.0), abs=1e-9)
    assert tr.net > 0


def test_t2_needs_a_close_at_2x_not_just_a_high():
    day = make_day({0: 10.0, T + 10: 19.0, T + 30: 5.0})
    a = day.opts[(K_C, "C")]
    a[T + 10, D.H] = 25.0                                   # a high above 2x, but the close is below
    tr = run(day, exit_="T2")
    assert tr.armed_row is None and tr.exits[-1][3] == "end1505"


def test_the_arming_close_never_sells_itself():
    # arms on a close of exactly 2x the fill; the next close is 25% lower and sells
    day = make_day({0: 10.0})
    e = run(day, exit_="H").entry
    day = make_day({0: 10.0, T + 10: 2 * e, T + 11: 1.5 * e - 0.05})
    tr = run(day, exit_="T2")
    assert tr.armed_row == T + 10 and tr.exits[-1][3] == "trail" and tr.exits[-1][0] == T + 12


def test_t3_and_t5_need_their_multiple():
    day = make_day({0: 10.0, T + 10: 25.0, T + 20: 12.0})  # 2.5x then -52%
    assert run(day, exit_="T2").exits[-1][3] == "trail"
    assert run(day, exit_="T3").exits[-1][3] == "end1505"
    day = make_day({0: 10.0, T + 10: 40.0, T + 20: 20.0})  # 4x then -50%
    assert run(day, exit_="T3").exits[-1][3] == "trail"
    assert run(day, exit_="T5").exits[-1][3] == "end1505"
    day = make_day({0: 10.0, T + 10: 60.0, T + 20: 20.0})  # 6x then -67%
    assert run(day, exit_="T5").exits[-1][3] == "trail"


def test_h_never_trails():
    day = make_day({0: 10.0, T + 10: 60.0, T + 20: 1.0})
    assert run(day, exit_="H").exits[-1][3] == "end1505"


def test_proxy_rows_do_not_arm():
    day = make_day({0: 10.0, T + 10: 30.0, T + 30: 15.0})
    a = day.opts[(K_C, "C")]
    a[T + 10:T + 30] = np.nan                               # no trade: the 30 is never printed
    tr = run(day, exit_="T2")
    assert tr.armed_row is None


# ------------------------------------------------------------------------------------------------ settlement
def test_settle_price_is_the_last_30_minute_mean():
    ix = np.full(N, 100.0)
    ix[S00:S00 + 30] = np.arange(30, dtype=float) + 200.0  # 200 ... 229
    assert L.settle_price(ix) == (pytest.approx(214.5), False)
    ix[S00:S00 + 15] = np.nan                               # only 15 bars: fall back to the last close, flagged
    p, flag = L.settle_price(ix)
    assert flag and p == 229.0
    assert math.isnan(L.settle_price(np.full(N, np.nan))[0])


def test_intrinsic():
    assert L.intrinsic(1, 25100.0, 25180.5) == pytest.approx(80.5)
    assert L.intrinsic(1, 25100.0, 25000.0) == 0.0
    assert L.intrinsic(-1, 25100.0, 25000.0) == pytest.approx(100.0)
    assert L.intrinsic(-1, 25100.0, 25100.0) == 0.0         # at the money: worthless
    assert L.intrinsic(1, 25100.0, math.nan) == 0.0


def test_stt_on_exercise_is_dated():
    assert K.stt_exercise(date(2025, 6, 5)) == 0.00125
    assert K.stt_exercise(date(2026, 3, 31)) == 0.00125
    assert K.stt_exercise(date(2026, 4, 1)) == 0.0015


def test_exercise_charges():
    v = 10_000.0
    nse = L.exercise_charges(v, "NIFTY", date(2025, 6, 5))
    txn = v * (0.0003503 + 0.000005)
    sebi = v * 1e-6
    assert nse == pytest.approx(round(20 + v * 0.00125 + txn + sebi + (20 + txn + sebi) * 0.18, 2))
    after = L.exercise_charges(v, "NIFTY", date(2026, 4, 2))
    assert after - nse == pytest.approx(v * (0.0015 - 0.00125), abs=0.011)
    bse = L.exercise_charges(v, "SENSEX", date(2025, 6, 5))
    assert bse == pytest.approx(round(20 + v * 0.00125 + v * 0.000325 + sebi + (20 + v * 0.000325 + sebi) * 0.18, 2))
    assert L.exercise_charges(0.0, "NIFTY", date(2025, 6, 5)) == 0.0


def _settle_day(path, final, d=date(2025, 6, 5), und="NIFTY", side="C"):
    day = make_day(path, d=d, und=und, side=side)
    day.ix[S00:S00 + 30, 3] = final
    return day


def test_otm_expires_worthless_with_no_sell_charges():
    day = _settle_day({0: 10.0, T + 50: 0.5}, 25050.0)       # CE 25,100 with the index at 25,050: worthless
    tr = run(day, end="SETTLE", exit_="H")
    assert tr.settled == "otm" and tr.exits[-1] == (N, 75, 0.0, "settle")
    assert tr.net == pytest.approx(-tr.risked, abs=0.01)    # lost exactly premium + buy charges
    assert tr.charges == pytest.approx(L50.charge(True, tr.entry, 75, "NIFTY", day.day))


def test_itm_pays_intrinsic_with_exercise_charges_and_no_spread():
    day = _settle_day({0: 10.0, T + 50: 30.0}, 25160.0)      # CE 25,100, settles 60 ITM
    tr = run(day, end="SETTLE", exit_="H")
    assert tr.settled == "itm" and tr.exits[-1] == (N, 75, 60.0, "settle")
    buy_ch = L50.charge(True, tr.entry, 75, "NIFTY", day.day)
    ex_ch = L.exercise_charges(60.0 * 75, "NIFTY", day.day)
    assert tr.charges == pytest.approx(round(buy_ch + ex_ch, 2))
    assert tr.net == pytest.approx(60.0 * 75 - tr.entry * 75 - buy_ch - ex_ch, abs=0.02)
    assert ex_ch > 60.0 * 75 * 0.00125                      # STT on exercise is in it


def test_put_settles_itm_below_the_strike():
    day = make_day({0: 10.0}, side="P", k=K_C)
    day.ix[S00:S00 + 30, 3] = 25000.0
    tr = run(day, end="SETTLE", exit_="H", side=-1)
    assert tr.settled == "itm" and tr.exits[-1][2] == pytest.approx(100.0)


def test_settlement_uses_the_dated_stt():
    a = run(_settle_day({0: 10.0}, 25160.0, d=date(2026, 3, 26)), end="SETTLE", exit_="H")
    b = run(_settle_day({0: 10.0}, 25160.0, d=date(2026, 4, 2)), end="SETTLE", exit_="H")
    sb = L50.charge(True, b.entry, 75, "NIFTY", date(2026, 4, 2)) + L.exercise_charges(4500.0, "NIFTY", date(2026, 4, 2))
    assert b.charges == pytest.approx(round(sb, 2)) and b.charges > a.charges


def test_trail_stays_active_until_the_close_under_settle():
    day = _settle_day({0: 10.0, D.minute(15, 28): 7.0}, 25500.0)   # bar closing 15:29 drops 30%: T0 sells at 15:29
    tr = run(day, end="SETTLE", exit_="T0")
    assert tr.exits[-1][3] == "trail" and tr.exits[-1][0] == D.minute(15, 29) and tr.settled is None
    day2 = _settle_day({0: 10.0, D.minute(15, 29): 7.0}, 25500.0)  # the 15:29 bar's close is too late
    tr2 = run(day2, end="SETTLE", exit_="T0")
    assert tr2.exits[-1][3] == "settle" and tr2.settled == "itm"


def test_late_multiplier_hits_a_late_trail_sale_but_not_settlement():
    day = _settle_day({0: 10.0, D.minute(15, 20): 7.0}, 25160.0)
    a = run(day, end="SETTLE", exit_="T0")
    LB.LATE.update({D.minute(15, 20): 2.75})
    b = run(day, end="SETTLE", exit_="T0")
    assert b.exits[-1][2] < a.exits[-1][2]
    h1 = run(day, end="SETTLE", exit_="H")
    LB.LATE.clear()
    h0 = run(day, end="SETTLE", exit_="H")
    assert h1.exits[-1] == h0.exits[-1]                    # settlement pays intrinsic, no spread


def test_metrics_counts_settlement():
    days = [_settle_day({0: 10.0}, 25160.0), _settle_day({0: 10.0}, 25000.0, d=date(2025, 6, 12))]
    trs = [run(d, end="SETTLE", exit_="H") for d in days]
    m = L.metrics(trs, days)
    assert m["itm"] == 1 and m["otm"] == 1 and m["tickets"] == 2 and m["trail_exits"] == 0
    m0 = L.metrics([run(days[0], end="1505", exit_="T0")], days[:1])
    assert m0["itm"] == 0 and m0["otm"] == 0 and m0["armed"] == 1


def test_paired_seed_and_gates():
    p = L.paired(np.array([1.0, 2.0, 3.0]), np.zeros(3))
    assert p["mean"] == 2.0 and p["n"] == 3
    fm = {"net": 100, "tickets": 50, "ex5": 10}
    assert L.gates(fm, 0.01, 0.05, 1.0)["pass"] and not L.gates(fm, 0.06, 0.05, 1.0)["pass"]


# ------------------------------------------------------------------------------------------------ real days
def _real_days():
    if not DD.days_path("NIFTY").exists():
        pytest.skip("lake cache absent")
    return DD.load("NIFTY", "design")[:6] + DD.load("SENSEX", "design")[:6]


def test_no_look_ahead_in_the_exit_decision():
    """Poison every option row after the sell row: the exit (row, price) must not change. Settlement reads the index's
    last 30 minutes only, which is the exchange's payoff, not a decision."""
    LB.load_late()
    seen = 0
    for day in _real_days():
        for sgn in (1, -1):
            pk = LB.pick(day, sgn, T)
            if pk is None:
                continue
            for x in K.EXITS:
                tr = L.simulate(day, pk[0], sgn, T, pk[1], "1505", x)
                if tr is None or tr.exits[-1][3] != "trail":
                    continue
                s = "C" if sgn > 0 else "P"
                a = day.opts[(pk[0], s)]
                keep = a.copy()
                a[tr.exits[-1][0] + 2:] = 999.0
                try:
                    tr2 = L.simulate(day, pk[0], sgn, T, pk[1], "1505", x)
                finally:
                    day.opts[(pk[0], s)] = keep
                assert tr2.exits[-1][:2] == tr.exits[-1][:2], (day.und, day.day, x)
                seen += 1
    assert seen > 0


def test_real_days_settle_prices():
    for day in _real_days():
        p, flag = L.settle_price(day.ix[:, 3])
        last = day.ix[~np.isnan(day.ix[:, 3]), 3][-1]
        assert not flag and abs(p / last - 1) < 0.01
