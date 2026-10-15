"""R59 tests: switch / roll detection, the literal arithmetic, the held-contract signal (a strike switch is not a drop),
the stock signal, lot sizing by date and expiry, the expiry cut-off over holidays, short sessions, missing minutes and
stale bars, foreign rows and spikes, fills and the S25 stop, no overlap, twins and BH, and the look-ahead guard (signals
on data cut at a day or a minute are identical up to the cut; the design loader and the final lock refuse).

    python -m pytest research/hunt/r59/test_r59.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r59cfg as K  # noqa: E402
import r59data as D  # noqa: E402
import r59lit as LT  # noqa: E402
import r59sim as S  # noqa: E402

N = K.N


# ------------------------------------------------------------------------------------------------ synthetic lake
def weekdays(a: date, n: int, holidays=()):
    out, x = [], a
    while len(out) < n:
        if x.weekday() < 5 and x not in holidays:
            out.append(x)
        x += timedelta(days=1)
    return out


def make(days, spot_fn, prem_fn, step=10.0, minutes=range(N), eq_minutes=None, offsets=(-1, 0, 1)):
    """Rolling rows: at each minute, strikes ATM-1..ATM+1 around spot_fn(di, m) (grid `step`), both sides, priced by
    prem_fn(strike, side, di, m) (None = no trade that minute). Equity close = spot."""
    rows, eq = [], []
    for di, d in enumerate(days):
        for m in minutes:
            s = spot_fn(di, m)
            ts = pd.Timestamp(d) + pd.Timedelta(minutes=9 * 60 + 15 + m)
            if eq_minutes is None or m in eq_minutes:
                eq.append(dict(sym="TST", ts=ts, close=s))
            atm = round(s / step) * step
            for off in offsets:
                k = atm + off * step
                for side in ("C", "P"):
                    p = prem_fn(k, side, di, m)
                    if p is None:
                        continue
                    rows.append(dict(ts=ts, offset=off, side=side, open=p, high=p, low=p, close=p, volume=1000,
                                     strike=k, spot=s))
    opt = pd.DataFrame(rows)
    opt["d"], opt["m"] = D._dm_fast(opt.ts)
    e = pd.DataFrame(eq)
    e["d"], e["m"] = D._dm_fast(e.ts)
    return opt, e


def bhav_for(exps, lot=100, sym="TST"):
    return pd.DataFrame([dict(sym=sym, expiry=e, bdate=e - timedelta(days=40), lot=lot) for e in exps])


@pytest.fixture(autouse=True)
def tst_symbol(monkeypatch):
    monkeypatch.setattr(K, "STOCKS", K.STOCKS + ("TST",))


def book(days, spot_fn, prem_fn, exps=None, **kw):
    opt, eq = make(days, spot_fn, prem_fn, **kw)
    exps = exps or [days[-1] + timedelta(days=30)]
    return D.build_book("TST", opt, eq, bhav_for(exps), days[0], days[-1])


def flat_spot(di, m):
    return 1000.0


def bs_like(k, side, di, m, spot=1000.0):
    return 20.0 + max(0.0, (spot - k) if side == "C" else (k - spot))


# ------------------------------------------------------------------------------------------------ literal (steps 1-2)
def test_classify_same_switch_roll():
    b = pd.DataFrame(dict(expiry=[1, 1, 1, 2], strike=[100.0, 100.0, 110.0, 110.0]))
    c = LT.classify(b, np.array([1, 1, 2, 3]), np.array([0, 2, 3, 4]))
    assert list(c) == ["same", "switch", "roll", "none"]


def test_literal_arithmetic_and_switch_drop():
    # rolling ATM call: 100 -> 70 is a strike switch (the series moved to a higher, cheaper strike), then back
    ts = pd.date_range("2025-01-06 09:15", periods=6, freq="15min")
    b = pd.DataFrame(dict(ts=ts, close=[100.0, 100.0, 70.0, 72.0, 80.0, 90.0],
                          strike=[1000.0, 1000.0, 1010.0, 1010.0, 1010.0, 1000.0],
                          d=[ts[0].date().toordinal()] * 6, expiry=[9] * 6))
    b = pd.concat([b, b.iloc[[-1]].assign(ts=ts[-1] + pd.Timedelta(minutes=15))], ignore_index=True)
    T = LT.their_trades(b)
    assert len(T) == 1 and T.i.iat[0] == 2
    assert T.pnl.iat[0] == pytest.approx(10000 / 70 * (90 - 70) - 95)
    assert T.drop_cat.iat[0] == "switch" and T.rec_cat.iat[0] == "switch"


def test_literal_min_premium():
    ts = pd.date_range("2025-01-06 09:15", periods=7, freq="15min")
    b = pd.DataFrame(dict(ts=ts, close=[6.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0], strike=[1.0] * 7, d=[1] * 7, expiry=[9] * 7))
    assert len(LT.their_trades(b)) == 0          # dropped 33% but closes below 5


def test_bars15_rolls_after_expiry():
    days = weekdays(date(2025, 1, 27), 4)        # Mon 27 .. Thu 30 Jan 2025
    exps = np.array([date(2025, 1, 28).toordinal(), date(2025, 2, 27).toordinal()])
    opt, _ = make(days, flat_spot, bs_like, minutes=range(0, 30))
    b = LT.bars15(opt, "C", exps)
    e = b.groupby("d").expiry.first()
    assert e.iloc[0] == exps[0] and e.iloc[1] == exps[0] and e.iloc[2] == exps[1]


# ------------------------------------------------------------------------------------------------ held contract
def test_held_contract_drop_is_a_signal_and_switch_drop_is_not():
    days = weekdays(date(2025, 1, 6), 3)
    # spot steps from 1000 to 1006 at minute 45 (bar 3): the ATM strike switches 1000 -> 1010.
    spot = lambda di, m: 1000.0 if m < 45 else 1006.0  # noqa: E731

    def prem(k, side, di, m):
        if side == "P":
            return 30.0
        if k == 1010.0:
            return 10.0                       # the new ATM call is far cheaper (a 50%+ "drop" on the rolling series)
        if k == 1000.0:
            return 20.0                       # the held 1000 call does not move
        return 15.0

    bk = book(days, spot, prem)
    assert S.signals_A(bk, 0) == []           # measured on the held contract: no drop
    opt, _ = make(days, spot, prem)
    b = LT.bars15(opt[opt.d == days[0].toordinal()], "C", np.array([days[-1].toordinal() + 30]))
    T = LT.their_trades(b)
    assert len(T) >= 1 and (T.drop_cat == "switch").all()   # the rolling series "finds" it

    def prem2(k, side, di, m):
        if side == "P":
            return 30.0
        if di == 1 and 45 <= m:
            return 14.0                       # day 1: every call really falls 30% from bar 2 to bar 3
        return 20.0

    bk2 = book(days, flat_spot, prem2)
    sig = S.signals_A(bk2, 0)
    assert [(di, b) for di, b, _ in sig] == [(1, 3)]


def test_signal_needs_same_session_previous_bar_and_fresh_close():
    days = weekdays(date(2025, 1, 6), 3)

    def prem(k, side, di, m):
        if side == "P":
            return 30.0
        if di == 1 and m < 15:
            return 14.0                       # bar 0 of day 1 vs day 0's last bar: overnight, never a signal (t >= bar 2)
        if di == 2 and m >= 45:
            # bar 3 of day 2 drops 30% but its last trade is at minute 50 (10 minutes before the bar's end): stale
            return 14.0 if m <= 50 else None
        return 20.0

    bk = book(days, flat_spot, prem)
    assert S.signals_A(bk, 0) == []


def test_stock_signal_against_the_option():
    days = weekdays(date(2025, 1, 6), 2)
    spot = lambda di, m: 1000.0 if (di == 0 or m < 45 or m >= 60) else 988.0  # noqa: E731
    bk = book(days, spot, bs_like)
    ce = S.signals_B(bk, 0)
    pe = S.signals_B(bk, 1)
    assert [(di, b) for di, b, _ in ce] == [(1, 3)]       # fell 1.2% in bar 3 -> buy the call
    assert [(di, b) for di, b, _ in pe] == [(1, 4)]       # rose back 1.2% in bar 4 -> buy the put


# ------------------------------------------------------------------------------------------------ lots, expiry, sessions
def test_lot_sizing_by_date_and_expiry():
    e1, e2 = date(2025, 1, 30), date(2025, 2, 27)
    bh = pd.DataFrame([dict(sym="X", expiry=e1, bdate=date(2025, 1, 1), lot=550),
                       dict(sym="X", expiry=e1, bdate=date(2025, 1, 15), lot=1100),     # e.g. a bonus mid-life
                       dict(sym="X", expiry=e2, bdate=date(2025, 1, 15), lot=1100)])
    L = D.Lots.of(bh, "X")
    assert L.lot(e1.toordinal(), date(2025, 1, 10).toordinal()) == 550
    assert L.lot(e1.toordinal(), date(2025, 1, 15).toordinal()) == 1100
    assert L.lot(e2.toordinal(), date(2025, 1, 2).toordinal()) == 1100     # none before: the earliest after
    assert L.lot(date(2025, 3, 27).toordinal(), date(2025, 3, 1).toordinal()) == 0


def test_expiry_cutoff_counts_trading_days_over_a_holiday():
    hol = {date(2025, 2, 26)}                                   # a Wednesday holiday before Thursday's expiry
    days = weekdays(date(2025, 2, 20), 6, hol)                  # Thu 20, Fri 21, Mon 24, Tue 25, Thu 27, Fri 28
    exps = np.array([date(2025, 2, 27).toordinal(), date(2025, 3, 27).toordinal()])
    cal = np.array([d.toordinal() for d in days])
    sk = D.cutoff_sessions(cal, exps, cal)
    got = {d: bool(s) for d, s in zip(days, sk)}
    assert got[date(2025, 2, 27)] and got[date(2025, 2, 25)] and got[date(2025, 2, 24)]
    assert not got[date(2025, 2, 21)] and not got[date(2025, 2, 28)]


def test_short_special_session_is_not_a_session():
    days = weekdays(date(2025, 1, 6), 3)
    opt, eq = make(days, flat_spot, bs_like)
    short = eq.d == days[1].toordinal()
    eq = eq[~short | (eq.m < 60)]                               # a one-hour session (Muhurat-like)
    bk = D.build_book("TST", opt, eq, bhav_for([days[-1] + timedelta(days=30)]), days[0], days[-1])
    assert list(bk.days) == [days[0].toordinal(), days[2].toordinal()]
    assert bk.info["short_or_missing_sessions"] == 1


def test_corporate_action_window_is_skipped():
    days = weekdays(date(2025, 1, 6), 14)
    spot = lambda di, m: 1000.0 if di < 7 else 500.0  # noqa: E731   (a 50% fall overnight: a demerger)
    bk = book(days, spot, lambda k, side, di, m: 20.0)
    assert bk.info["corporate_actions"] == [days[7].isoformat()]
    assert bk.skip[2:13].all() and not bk.skip[0] and not bk.skip[1] and not bk.skip[13]


def test_foreign_rows_and_spikes_are_dropped():
    days = weekdays(date(2025, 1, 6), 1)
    opt, eq = make(days, flat_spot, lambda k, side, di, m: 20.0)
    i = opt.index[(opt.m == 10) & (opt.offset == 0) & (opt.side == "C")][0]
    opt.loc[i, "spot"] = 500.0                                  # a row of another underlying
    j = opt.index[(opt.m == 20) & (opt.strike == 1000.0) & (opt.side == "P")]
    opt.loc[j, ["open", "high", "low", "close"]] = 90.0         # a one-minute spike that reverts
    bk = D.build_book("TST", opt, eq, bhav_for([days[0] + timedelta(days=30)]), days[0], days[0])
    assert bk.info["foreign_rows"] == 1
    assert bk.info["spike_rows"] == len(j)
    assert (bk.c <= 20.0 + 1e-9).all()


# ------------------------------------------------------------------------------------------------ trading
def test_entry_waits_for_a_row_and_missing_minutes():
    days = weekdays(date(2025, 1, 6), 1)

    def prem(k, side, di, m):
        if 45 <= m <= 51:
            return None                                         # no trade in the 5 minutes after bar 2
        return 20.0

    bk = book(days, flat_spot, prem)
    assert S.skeleton(bk, 0, 2, 0) is None                      # bar 2 ends at minute 44: rows 45..49 missing
    s = S.skeleton(bk, 0, 3, 0)
    assert s is not None and bk.g[s.r0] == 60                   # bar 3 ends at 59: the entry row is minute 60
    assert s.X == 120 and s.rx >= 0


def test_exit_fallback_when_no_row():
    days = weekdays(date(2025, 1, 6), 1)
    bk = book(days, flat_spot, lambda k, side, di, m: None if 118 <= m <= 130 else 20.0)
    s = S.skeleton(bk, 0, 3, 0)
    assert s.rx == -1 and s.xpx == 20.0                         # last close before the exit minute
    p = S.price(bk, s, "NS", 0.0)
    assert p["reason"] == "fallback" and p["xfill"] == pytest.approx(round(20 * 0.9995 - 4 * 0.05, 2))


def test_exit_capped_at_1525():
    days = weekdays(date(2025, 1, 6), 1)
    bk = book(days, flat_spot, bs_like)
    s = S.skeleton(bk, 0, 22, 0)                                # bar 22 ends 14:59, entry 15:00
    assert s.X == K.EXIT_CAP
    assert S.skeleton(bk, 0, 24, 0) is None                     # the last bar has no minute after it


def test_fills_spread_and_roll():
    assert S.buy_fill(100.0, 25, 0.0) == pytest.approx(100.10)     # 5 bps + 1 tick
    assert S.buy_fill(100.0, 5, 0.0) == pytest.approx(100.15)      # 2 ticks
    assert S.buy_fill(100.0, 0, 0.0) == pytest.approx(100.25)      # 4 ticks
    assert S.buy_fill(100.0, 25, 0.004) == pytest.approx(100.45)   # Roll half-spread 0.4% > 1 tick
    assert S.sell_fill(100.0, 25, 0.004, 2.0) == pytest.approx(99.15)  # 2x costs double the spread part
    assert S.sell_fill(100.0, 25, 0.0, stop=True) == pytest.approx(99.80)


def test_s25_stop_gap_and_level():
    days = weekdays(date(2025, 1, 6), 1)

    def gap(k, side, di, m):
        return 20.0 if m < 70 else 10.0                          # gaps through the stop at minute 70

    bk = book(days, flat_spot, gap)
    s = S.skeleton(bk, 0, 3, 0)
    p = S.price(bk, s, "S25", 0.0)
    assert p["reason"] == "stop_gap" and p["xfill"] < 10.0
    opt, eq = make(days, flat_spot, lambda k, side, di, m: 20.0)
    r = opt.index[(opt.m == 70)]
    opt.loc[r, "low"] = 12.0                                     # an intrabar low through the stop, open at 20
    bk2 = D.build_book("TST", opt, eq, bhav_for([days[0] + timedelta(days=30)]), days[0], days[0])
    p2 = S.price(bk2, S.skeleton(bk2, 0, 3, 0), "S25", 0.0)
    L = 0.75 * S.buy_fill(20.0, S.skeleton(bk2, 0, 3, 0).l5_in, 0.0)
    assert p2["reason"] == "stop" and p2["xfill"] <= L


def test_one_lot_charges_and_no_overlap():
    days = weekdays(date(2025, 1, 6), 1)
    bk = book(days, flat_spot, lambda k, side, di, m: 20.0)
    sig = [(0, 2, S.atm_at_bar(bk, 0, 2, 0)), (0, 4, S.atm_at_bar(bk, 0, 4, 0)), (0, 7, S.atm_at_bar(bk, 0, 7, 0))]
    tr = S.trades_for(bk, sig, "NS", 0.0)
    assert [t["bar"] for t in tr] == [2, 7]                      # bar 4's signal falls inside the first trade
    assert all(t["lot"] == 100 for t in tr) and all(t["charges"] > 40 for t in tr)
    assert tr[0]["capital"] == pytest.approx(tr[0]["fill"] * 100, abs=1)


def test_twins_and_bh():
    rng = np.random.default_rng(1)
    pools = {("X", "CE"): {0: np.array([1.0, 1.0]), 1: np.array([-1.0])}}
    tw = S.twin_totals([dict(sym="X", side="CE", di=0), dict(sym="X", side="CE", di=1)], pools, rng, 50)
    assert (tw == 0.0).all()
    assert S.pvalue(0.0, tw) == 1.0 and S.pvalue(0.5, tw) == pytest.approx(1 / 51)
    assert S.bh([0.01, 0.04, None, 0.03]) == [0.03, 0.04, None, 0.04]


# ------------------------------------------------------------------------------------------------ look-ahead guard
def random_world(seed):
    rng = np.random.default_rng(seed)
    days = weekdays(date(2025, 1, 6), 6)
    walk = 1000 * np.exp(np.cumsum(rng.normal(0, 0.002, (len(days), N)), axis=1))
    noise = rng.uniform(0.5, 1.5, (len(days), N))

    def spot(di, m):
        return float(walk[di, m])

    def prem(k, side, di, m):
        s = walk[di, m]
        return float(round((10 + max(0.0, (s - k) if side == "C" else (k - s))) * noise[di, m], 2))

    return days, spot, prem


def test_look_ahead_prefix_days_and_minutes(monkeypatch):
    # Which dates are sessions (>= 300 minutes; special sessions) is known ahead from the exchange calendar, so the
    # minute cut below keeps the cut day a session.
    monkeypatch.setattr(K, "MIN_SESSION_MIN", 150)
    days, spot, prem = random_world(3)
    opt, eq = make(days, spot, prem, step=5.0)
    bh = bhav_for([days[-1] + timedelta(days=30)])
    full = D.build_book("TST", opt, eq, bh, days[0], days[-1])
    cut = D.build_book("TST", opt[opt.d <= days[3].toordinal()], eq[eq.d <= days[3].toordinal()], bh, days[0], days[3])
    for fn in (S.signals_A, S.signals_B):
        for sd in (0, 1):
            a = [x[:2] for x in fn(full, sd) if x[0] <= 3]
            b = [x[:2] for x in fn(cut, sd)]
            assert a == b
    # cut inside the last day at minute 200: every signal of a bar that closed by then is unchanged
    keep_o = (opt.d < days[3].toordinal()) | ((opt.d == days[3].toordinal()) & (opt.m < 200))
    keep_e = (eq.d < days[3].toordinal()) | ((eq.d == days[3].toordinal()) & (eq.m < 200))
    part = D.build_book("TST", opt[keep_o & (opt.d <= days[3].toordinal())], eq[keep_e & (eq.d <= days[3].toordinal())],
                        bh, days[0], days[3])
    for fn in (S.signals_A, S.signals_B):
        for sd in (0, 1):
            a = [x[:2] for x in fn(cut, sd) if x[0] < 3 or x[1] * 15 + 14 < 200]
            b = [x[:2] for x in fn(part, sd) if x[0] < 3 or x[1] * 15 + 14 < 200]
            assert a == b


def test_signals_ignore_later_prices():
    days, spot, prem = random_world(5)
    opt, eq = make(days, spot, prem, step=5.0)
    bh = bhav_for([days[-1] + timedelta(days=30)])
    bk = D.build_book("TST", opt, eq, bh, days[0], days[-1])
    sig = S.signals_A(bk, 0) + S.signals_B(bk, 1)
    opt2, eq2 = opt.copy(), eq.copy()
    late = (opt2.d == days[-1].toordinal()) & (opt2.m >= 300)
    opt2.loc[late, ["open", "high", "low", "close"]] *= 3.0     # change only the future
    bk2 = D.build_book("TST", opt2, eq2, bh, days[0], days[-1])
    sig2 = S.signals_A(bk2, 0) + S.signals_B(bk2, 1)
    keep = lambda s: [x[:2] for x in s if not (x[0] == len(days) - 1 and x[1] * 15 + 14 >= 300)]  # noqa: E731
    assert keep(sig) == keep(sig2)


def test_design_guard_and_final_lock(tmp_path, monkeypatch):
    with pytest.raises(ValueError):
        D.assert_design_days([date(2026, 4, 1)])
    D.assert_design_days([date(2026, 3, 31)])
    with pytest.raises(RuntimeError):
        D.phase_end("final")
    monkeypatch.setattr(K, "SPREAD_JSON", tmp_path / "spread.json")
    monkeypatch.setattr(K, "FINAL_LOCK", tmp_path / "final_read.json")
    with pytest.raises(RuntimeError):
        D.final_key()                                           # no frozen spread yet
    (tmp_path / "spread.json").write_text("{}")
    k = D.final_key()
    assert D.phase_end("final", k) > K.DESIGN_END
    (tmp_path / "final_read.json").write_text("{}")
    with pytest.raises(RuntimeError):
        D.final_key()                                           # read once


def test_roll_spread_recovers_bounce():
    days = weekdays(date(2025, 1, 6), 2)
    # bid 99.5 / ask 100.5 bounce around a constant 100: relative spread about 1%
    rng = np.random.default_rng(0)
    side_draw = rng.integers(0, 2, (len(days), N))
    bk = book(days, flat_spot, lambda k, side, di, m: 100.0 + (0.5 if side_draw[di, m] else -0.5))
    rs, n = S.roll_spread(bk, 0)
    assert n >= 2 and rs == pytest.approx(0.01, rel=0.15)
