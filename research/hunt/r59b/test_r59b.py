"""R59b tests: the band on the HELD contract only (a strike switch inside the window is not a band break; fewer than
20 own bars: no signal; the window may reach the previous session of the same contract), the stock band (lower -> CE,
upper -> PE), switch / roll / window-mixed detection on the literal series and its arithmetic, the profit lock, gross
before costs, lot sizing, the expiry cut-off over a holiday, missing minutes and stale bars, and the look-ahead guard
(signals on data cut at a day or a minute are identical up to the cut; later prices change nothing; the design guard
and the R59b final lock refuse).

    python -m pytest research/hunt/r59b/test_r59b.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r59bcfg as K  # noqa: E402
import r59blit as BL  # noqa: E402
import r59brun as R  # noqa: E402
import r59bsim as BS  # noqa: E402
import r59data as D  # noqa: E402
import r59lit as LT  # noqa: E402
import r59sim as S  # noqa: E402
from test_r59 import bhav_for, book, flat_spot, make, random_world, weekdays  # noqa: E402

N = 375


def wiggle(base):
    """A premium that alternates +-0.5 bar by bar around `base` (so the band has a width)."""
    return lambda m: base + (0.5 if (m // 15) % 2 else -0.5)


def sigs(lst):
    return [(di, b) for di, b, _ in lst]


# ------------------------------------------------------------------------------------------------ band on the held contract
def test_strike_switch_in_the_window_is_not_a_band_break():
    days = weekdays(date(2025, 1, 6), 3)
    spot = lambda di, m: 1000.0 if (di == 0 or m < 45) else 1006.0  # noqa: E731   ATM call 1000 -> 1010 at bar 3, day 1

    def prem(k, side, di, m):
        if side == "P":
            return wiggle(30.0)(m)
        return wiggle({990.0: 30.0, 1000.0: 20.0, 1010.0: 10.0, 1020.0: 5.0}[k])(m)

    bk = book(days, spot, prem)
    assert sigs(BS.signals_A(bk, 0, BS.held_bars(bk))) == []        # the held 1010 call never left its band
    opt, _ = make(days, spot, prem)
    b = LT.bars15(opt[opt.d <= days[1].toordinal()], "C", np.array([days[-1].toordinal() + 30]))
    T = BL.their_trades(b)
    assert len(T) >= 1 and T.window_mixed.all() and not T.one_contract.any()   # the rolling series "finds" it
    assert (T.drop_cat == "switch").any()


def test_real_band_break_on_the_held_contract_and_window_reaches_previous_session():
    days = weekdays(date(2025, 1, 6), 3)

    def prem(k, side, di, m):
        if side == "P":
            return wiggle(30.0)(m)
        if di == 1 and 45 <= m < 60:
            return 14.0                                                # bar 3 of day 1: the call really falls
        return wiggle(20.0)(m)

    bk = book(days, flat_spot, prem)
    H = BS.held_bars(bk)
    # day 0 bar 19 is the contract's 20th bar: the first bar with a band; a day-1 bar-3 break uses day-0 bars too
    assert sigs(BS.signals_A(bk, 0, H)) == [(1, 3)]
    cid = S.atm_at_bar(bk, 1, 3, 0)
    c, lg, lo = BS.held_band_at(H, cid, 1 * 25 + 3)
    own = list(H.close[H.start[cid]:H.end[cid]][25 + 3 - 19:25 + 3 + 1])
    assert c == 14.0 and lo == pytest.approx(np.mean(own) - 2 * np.std(own, ddof=1))


def test_fewer_than_20_own_bars_no_signal():
    days = weekdays(date(2025, 1, 6), 2)
    # only offset-0 rows: the 1010 call exists only from bar 10 of day 1 (when it becomes ATM)
    spot = lambda di, m: 1000.0 if (di == 0 or m < 150) else 1006.0  # noqa: E731

    def prem(k, side, di, m):
        if k == 1010.0 and side == "C" and 210 <= m < 225:
            return 3.0 + 4.0                                           # its 5th own bar falls hard (7 >= Rs 5)
        return wiggle(10.0 if k == 1010.0 else 20.0)(m)

    bk = book(days, spot, prem, offsets=(0,))
    H = BS.held_bars(bk)
    cid = S.atm_at_bar(bk, 1, 14, 0)
    assert bk.cstrike[cid] == 1010.0
    assert H.end[cid] - H.start[cid] == 15                              # bars 10..24 of day 1
    assert np.isnan(H.lower[H.start[cid]:H.end[cid]]).all()
    assert (1, 14) not in sigs(BS.signals_A(bk, 0, H))


def test_min_premium_and_stale_bar():
    days = weekdays(date(2025, 1, 6), 3)

    def prem(k, side, di, m):
        if side == "C" and di == 1 and 45 <= m < 60:
            return 4.0                                                 # breaks the band but closes under Rs 5
        if side == "C" and di == 2 and 45 <= m < 60:
            return 12.0 if m <= 50 else None                           # breaks the band, last trade 9 minutes early
        return wiggle(20.0)(m)

    bk = book(days, flat_spot, prem)
    assert sigs(BS.signals_A(bk, 0, BS.held_bars(bk))) == []


def test_missing_minutes_bars_absent_from_history():
    days = weekdays(date(2025, 1, 6), 2)
    # the contract has no rows in bars 5..9 of day 0: those bars are simply not in its history
    bk = book(days, flat_spot, lambda k, side, di, m: None if (di == 0 and 75 <= m < 150) else wiggle(20.0)(m))
    H = BS.held_bars(bk)
    cid = S.atm_at_bar(bk, 0, 2, 0)
    bars = H.bar[H.start[cid]:H.end[cid]]
    assert len(bars) == 45 and not set(range(5, 10)) & set(bars.tolist())


# ------------------------------------------------------------------------------------------------ the stock band
def test_stock_band_lower_buys_call_upper_buys_put():
    days = weekdays(date(2025, 1, 6), 2)

    def spot(di, m):
        base = 1000.0 + (0.5 if (m // 15) % 2 else -0.5)
        if di == 1 and 45 <= m < 60:
            return 990.0                                               # breaks the lower band in bar 3
        if di == 1 and 120 <= m < 135:
            return 1012.0                                              # breaks the upper band in bar 8
        return base

    bk = book(days, spot, lambda k, side, di, m: 20.0, step=50.0)
    SB = BS.stock_bars(bk)
    assert sigs(BS.signals_B(bk, 0, SB)) == [(1, 3)]
    assert (1, 8) in sigs(BS.signals_B(bk, 1, SB)) and (1, 3) not in sigs(BS.signals_B(bk, 1, SB))


def test_stock_band_needs_fresh_close():
    days = weekdays(date(2025, 1, 6), 2)
    spot = lambda di, m: 990.0 if (di == 1 and 45 <= m < 60) else 1000.0 + (0.5 if (m // 15) % 2 else -0.5)  # noqa
    eqm = set(range(N)) - set(range(54, 60))                           # no stock minute in bar 3's last 6 minutes
    bk = book(days, spot, lambda k, side, di, m: 20.0, step=50.0, eq_minutes=eqm)
    assert (1, 3) not in sigs(BS.signals_B(bk, 0, BS.stock_bars(bk)))


# ------------------------------------------------------------------------------------------------ literal
def test_literal_band_arithmetic_and_categories():
    ts = pd.date_range("2025-01-06 09:15", periods=26, freq="15min")
    close = [20.0 + (0.5 if i % 2 else -0.5) for i in range(20)] + [14.0, 15.0, 16.0, 17.0, 18.0, 19.0]
    b = pd.DataFrame(dict(ts=ts, close=close, strike=[1000.0] * 26, d=[ts[0].date().toordinal()] * 26, expiry=[9] * 26))
    T = BL.their_trades(b)
    lo = pd.Series(close).rolling(20).mean() - 2 * pd.Series(close).rolling(20).std()
    assert T.i.iat[0] == 20 and close[20] <= lo.iat[20]
    assert T.pnl.iat[0] == pytest.approx(10000 / 14.0 * (18.0 - 14.0) - 95)
    assert T.one_contract.iat[0] and not T.window_mixed.iat[0]
    b2 = b.assign(strike=[1000.0] * 5 + [1010.0] + [1000.0] * 20)      # one switched bar inside the window
    T2 = BL.their_trades(b2)
    assert T2.window_mixed.iat[0] and not T2.one_contract.iat[0] and T2.drop_cat.iat[0] == "same"
    b3 = b.assign(expiry=[9] * 22 + [10] * 4)                          # the exit row is the next expiry
    assert BL.their_trades(b3).rec_cat.iat[0] == "roll"


def test_run_start_and_drawdown():
    b = pd.DataFrame(dict(expiry=[1, 1, 1, 1, 2, 2], strike=[10.0, 10.0, 11.0, 10.0, 10.0, 10.0]))
    assert list(BL.run_start(b)) == [0, 0, 2, 3, 4, 4]
    assert BL.max_drawdown(np.array([100.0, -50.0, -80.0, 200.0, -10.0])) == -130.0
    assert BL.max_drawdown(np.array([-5.0, 10.0])) == -5.0


# ------------------------------------------------------------------------------------------------ pricing
def test_price_matches_r59_for_ns_and_s25():
    days, spot, prem = random_world(7)
    opt, eq = make(days, spot, prem, step=5.0)
    bk = D.build_book("TST", opt, eq, bhav_for([days[-1] + timedelta(days=30)]), days[0], days[-1])
    n = 0
    for b in range(1, 23):
        s = S.skeleton(bk, 1, b, 0)
        if s is None:
            continue
        for v in ("NS", "S25"):
            a, z = S.price(bk, s, v, 0.003), BS.price(bk, s, v, 0.003)
            assert a["net"] == pytest.approx(z["net"]) and a["reason"] == z["reason"]
        n += 1
    assert n > 10


def test_profit_lock_breakeven_and_gross_before_costs():
    days = weekdays(date(2025, 1, 6), 1)

    def prem(k, side, di, m):
        if 70 <= m < 80:
            return 26.0                                                # +30%: the lock arms
        if 80 <= m < 100:
            return 19.0                                                # falls back under the fill ...
        if m >= 100:
            return 16.0                                                # ... and on (NS rides it to the time exit)
        return 20.0

    bk = book(days, flat_spot, prem)
    s = S.skeleton(bk, 0, 3, 0)                                        # entry row minute 60 at 20
    ns, s25, lk = (BS.price(bk, s, v, 0.0) for v in ("NS", "S25", "S25L"))
    assert ns["reason"] == "time" and s25["reason"] == "time"
    assert lk["reason"] == "lock_gap" and lk["exit_g"] == 80           # 19 opens under the breakeven stop
    assert lk["net"] > ns["net"]
    assert ns["gross0"] == pytest.approx((16.0 - 20.0) * 100) and lk["gross0"] == pytest.approx((19.0 - 20.0) * 100)
    assert ns["gross0"] > ns["net"]                                    # costs only take away
    # the lock does not arm below +20%, and S25L == S25 then
    bk2 = book(days, flat_spot, lambda k, side, di, m: 23.0 if 70 <= m < 80 else (19.0 if m >= 80 else 20.0))
    s2 = S.skeleton(bk2, 0, 3, 0)
    assert BS.price(bk2, s2, "S25L", 0.0)["net"] == pytest.approx(BS.price(bk2, s2, "S25", 0.0)["net"])


def test_trades_one_lot_no_overlap_and_metrics():
    days = weekdays(date(2025, 1, 6), 1)
    bk = book(days, flat_spot, lambda k, side, di, m: 20.0)
    sig = [(0, 2, S.atm_at_bar(bk, 0, 2, 0)), (0, 4, S.atm_at_bar(bk, 0, 4, 0)), (0, 7, S.atm_at_bar(bk, 0, 7, 0))]
    tr = BS.trades_for(bk, sig, "S25L", 0.0)
    assert [t["bar"] for t in tr] == [2, 7] and all(t["lot"] == 100 for t in tr)
    assert all(t["gross0"] == 0.0 and t["cost"] > 0 for t in tr)
    m = BS.metrics(tr, None, 4)
    assert m["trades"] == 2 and m["gross_per_trade"] == 0 and m["trades_per_session"] == 0.5


def test_lot_by_expiry_and_expiry_cutoff_over_holiday():
    hol = {date(2025, 2, 26)}
    days = weekdays(date(2025, 2, 3), 19, hol)                         # 3 Feb .. 28 Feb, Wed 26 a holiday
    e1 = date(2025, 2, 27)
    bh = pd.DataFrame([dict(sym="TST", expiry=e1, bdate=date(2025, 1, 1), lot=550),
                       dict(sym="TST", expiry=e1, bdate=date(2025, 2, 10), lot=1100),
                       dict(sym="TST", expiry=date(2025, 3, 27), bdate=date(2025, 2, 1), lot=1100)])

    def prem(k, side, di, m):
        return 14.0 if 45 <= m < 60 else wiggle(20.0)(m)                # a band break every session at bar 3

    opt, eq = make(days, flat_spot, prem)
    bk = D.build_book("TST", opt, eq, bh, days[0], days[-1])
    got = [days[di] for di, _ in sigs(BS.signals_A(bk, 0, BS.held_bars(bk)))]
    assert date(2025, 2, 27) not in got and date(2025, 2, 25) not in got and date(2025, 2, 24) not in got
    assert date(2025, 2, 21) in got and date(2025, 2, 28) not in got    # 28 Feb: the March contract has < 20 bars
    tr = BS.trades_for(bk, BS.signals_A(bk, 0, BS.held_bars(bk)), "NS", 0.0)
    lots = {t["date"]: t["lot"] for t in tr}
    assert lots["2025-02-07"] == 550 and lots["2025-02-21"] == 1100


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_look_ahead_prefix_days_and_minutes(monkeypatch):
    monkeypatch.setattr(D.K, "MIN_SESSION_MIN", 150)
    days, spot, prem = random_world(3)
    opt, eq = make(days, spot, prem, step=5.0)
    bh = bhav_for([days[-1] + timedelta(days=30)])

    def all_sigs(bk):
        H, SB = BS.held_bars(bk), BS.stock_bars(bk)
        return [sigs(BS.signals_A(bk, sd, H)) for sd in (0, 1)] + [sigs(BS.signals_B(bk, sd, SB)) for sd in (0, 1)]

    full = all_sigs(D.build_book("TST", opt, eq, bh, days[0], days[-1]))
    d3 = days[3].toordinal()
    cut = all_sigs(D.build_book("TST", opt[opt.d <= d3], eq[eq.d <= d3], bh, days[0], days[3]))
    assert sum(len(x) for x in full) > 0
    for a, b in zip(full, cut):
        assert [x for x in a if x[0] <= 3] == b
    keep_o = (opt.d < d3) | ((opt.d == d3) & (opt.m < 200))
    keep_e = (eq.d < d3) | ((eq.d == d3) & (eq.m < 200))
    part = all_sigs(D.build_book("TST", opt[keep_o], eq[keep_e], bh, days[0], days[3]))
    early = lambda s: [x for x in s if x[0] < 3 or x[1] * 15 + 14 < 200]  # noqa: E731
    for a, b in zip(cut, part):
        assert early(a) == early(b)


def test_signals_ignore_later_prices():
    days, spot, prem = random_world(5)
    opt, eq = make(days, spot, prem, step=5.0)
    bh = bhav_for([days[-1] + timedelta(days=30)])

    def all_sigs(o, e):
        bk = D.build_book("TST", o, e, bh, days[0], days[-1])
        H, SB = BS.held_bars(bk), BS.stock_bars(bk)
        return BS.signals_A(bk, 0, H) + BS.signals_A(bk, 1, H) + BS.signals_B(bk, 0, SB) + BS.signals_B(bk, 1, SB)

    opt2, eq2 = opt.copy(), eq.copy()
    late_o = (opt2.d == days[-1].toordinal()) & (opt2.m >= 300)
    late_e = (eq2.d == days[-1].toordinal()) & (eq2.m >= 300)
    opt2.loc[late_o, ["open", "high", "low", "close"]] *= 3.0
    eq2.loc[late_e, "close"] *= 1.05
    keep = lambda s: [x[:2] for x in s if not (x[0] == len(days) - 1 and x[1] * 15 + 14 >= 300)]  # noqa: E731
    assert keep(all_sigs(opt, eq)) == keep(all_sigs(opt2, eq2))


def test_design_guard_and_final_lock(tmp_path, monkeypatch):
    with pytest.raises(ValueError):
        D.assert_design_days([date(2026, 4, 1)])
    with pytest.raises(RuntimeError):
        R.phase_end("final")
    assert R.phase_end("design") == date(2026, 3, 31)
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    monkeypatch.setattr(K, "FINAL_LOCK", tmp_path / "final_read.json")
    with pytest.raises(RuntimeError):
        R.final_key()                                                  # no frozen design results yet
    (tmp_path / "design.json").write_text("{}")
    k = R.final_key()
    assert R.phase_end("final", k) > K.DESIGN_END
    (tmp_path / "final_read.json").write_text("{}")
    with pytest.raises(RuntimeError):
        R.final_key()                                                  # read once
    assert R.period(date(2024, 11, 19)) == "pre" and R.period(date(2026, 3, 31)) == "design"
    assert R.period(date(2026, 4, 1)) == "final"


def test_tuning_verdict_rule():
    des = {"A-NS-ALL": dict(trades=100, gross_per_trade=50, gross_t=2.5)}
    fin = {"A-NS-ALL": dict(trades=50, gross_per_trade=200, gross_t=2.1, cost_median=150)}
    v = R.tuning_verdict(des, fin)
    assert v["A-NS-ALL"]["worth_trying"] and not v["B-NS-ALL"]["worth_trying"]
    fin["A-NS-ALL"]["cost_median"] = 250                               # gross does not cover the costs
    assert not R.tuning_verdict(des, fin)["A-NS-ALL"]["worth_trying"]
