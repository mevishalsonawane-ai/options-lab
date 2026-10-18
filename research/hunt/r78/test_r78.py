"""R78 tests: the Daily High/Low Reversal engine on synthetic XAUUSD minutes, plus the look-ahead guards:
the previous day's high / low are known only after that day closes, and every entry uses only closed 1-minute bars."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r78 import r78engine as E
from research.hunt.r78 import r78study as S

MON, TUE = "2026-01-05", "2026-01-06"


def flat_days(days, price):
    idx = pd.DatetimeIndex([])
    for d in days:
        idx = idx.append(pd.date_range(d, periods=1440, freq="min"))
    return pd.DataFrame({"open": price, "high": price, "low": price, "close": price}, index=idx, dtype=float)


def put(df, ts, o, h, l, c):
    df.loc[pd.Timestamp(ts), ["open", "high", "low", "close"]] = [o, h, l, c]


def long_scenario(t0="07:30"):
    """Mon range 1990-2010. Tue: flat 2000, a 2005 high 20 min before the break, a close below 1990 at t0, a green bar
    at t0+1, a close above its high at t0+2, entry at t0+3's open, drift at 1991, target 2005 hit at t0+30."""
    df = flat_days([MON, TUE], 2000.0)
    T = pd.Timestamp(f"{TUE} {t0}")
    at = lambda k: T + pd.Timedelta(minutes=k)
    put(df, f"{MON} 10:00", 2000, 2010, 2000, 2000)
    put(df, f"{MON} 11:00", 2000, 2000, 1990, 2000)
    put(df, at(-20), 2000, 2005, 2000, 2000)
    put(df, at(0), 2000, 2000, 1987, 1988)          # break: close < 1990
    put(df, at(1), 1988, 1989.5, 1986, 1989)        # green
    put(df, at(2), 1989, 1991, 1988.8, 1990.5)      # closes above 1989.5
    put(df, at(3), 1990.5, 1991, 1990, 1990.8)      # entry at this open
    df.loc[at(4):at(29), ["open", "high", "low", "close"]] = 1991.0
    put(df, at(30), 1991, 2005.5, 1991, 2004)
    df.loc[at(31):, ["open", "high", "low", "close"]] = 2004.0
    return df


def mirror(df, k=4000.0):
    return pd.DataFrame({"open": k - df.open, "high": k - df.low, "low": k - df.high, "close": k - df.close},
                        index=df.index)


V1 = E.VARIANTS[0]


def run(df, v=V1, side=1):
    return E.run_variant(E.Bars(df), v, side)


# ------------------------------------------------------------------------------------------------------------- basics
def test_prev_weekday():
    d = lambda s: int(np.datetime64(s, "D").astype(np.int64))
    assert E.prev_weekday(d("2026-01-05")) == d("2026-01-02")   # Monday -> Friday
    assert E.prev_weekday(d("2026-01-06")) == d("2026-01-05")
    assert E.prev_weekday(d("2026-01-09")) == d("2026-01-08")


def test_long_trade_exact():
    tr = run(long_scenario())
    assert len(tr) == 1
    t = tr.iloc[0]
    assert t.entry_time == pd.Timestamp(f"{TUE} 07:33")
    assert t.entry_px == pytest.approx(1990.8)                # ask = bid open + 0.30
    assert t.stop_px == pytest.approx(1985.9)                 # swing low 1986 - 0.10
    assert t.target_px == pytest.approx(2005.0)               # the 07:10 high, within 60 bars of the break
    assert t.reason == "target"
    assert t.net == pytest.approx((2005 - 1990.8 - 0.07) * 100)
    assert t.r == pytest.approx(4.9)


def test_short_is_the_mirror():
    lt = run(long_scenario()).iloc[0]
    st = run(mirror(long_scenario()), side=-1)
    assert len(st) == 1 and st.iloc[0].side == "short"
    s = st.iloc[0]
    assert s.net == pytest.approx(lt.net)
    assert s.r == pytest.approx(lt.r)
    assert s.entry_px == pytest.approx(4000 - 1990.5)        # sells at the bid
    assert s.exit_px == pytest.approx(4000 - 2005 + 0.30)     # buys back at the ask
    assert run(long_scenario(), side=-1).empty               # no break above Monday's high


def test_no_previous_day_no_trade():
    df = long_scenario()
    df = df[df.index >= pd.Timestamp(TUE)]                   # Monday missing
    assert run(df).empty
    df2 = long_scenario()
    df2 = df2[~((df2.index >= pd.Timestamp(MON)) & (df2.index < pd.Timestamp(f"{MON} 15:00")))]   # Monday < 600 min
    assert run(df2).empty


@pytest.mark.parametrize("t0,n", [("06:50", 0), ("07:00", 1), ("18:57", 1), ("18:58", 0), ("19:10", 0)])
def test_session_window(t0, n):
    # break bar must start 07:00-18:59 UTC and the entry bar by 19:00 UTC
    assert len(run(long_scenario(t0))) == n


def test_needs_a_close_through_and_a_green_within_k():
    df = long_scenario()
    put(df, f"{TUE} 07:30", 2000, 2000, 1987, 1990.0)          # close == level: not "completely below"
    assert run(df).empty
    df = long_scenario()
    put(df, f"{TUE} 07:31", 1988, 1988, 1986, 1987)            # not green; green comes at 07:32
    put(df, f"{TUE} 07:32", 1987, 1988.5, 1986.5, 1988.2)
    put(df, f"{TUE} 07:33", 1988.2, 1990, 1988, 1989.0)        # closes above 1988.5
    put(df, f"{TUE} 07:34", 1989.0, 1989.5, 1988.9, 1989.2)
    assert len(run(df)) == 1                                   # K 3
    assert run(df, E.VARIANTS[1]).empty                        # K 1: the green must be the very next bar


def test_failed_confirmation_tries_next_green():
    df = long_scenario()
    put(df, f"{TUE} 07:32", 1989, 1989.4, 1988.8, 1989.3)     # green but does not close above 1989.5
    put(df, f"{TUE} 07:33", 1989.3, 1990.5, 1989.2, 1990.4)   # closes above 1989.4 -> confirms green 07:32
    put(df, f"{TUE} 07:34", 1990.4, 1990.6, 1990.3, 1990.5)
    tr = run(df)
    assert len(tr) == 1
    assert tr.iloc[0].green_time == pd.Timestamp(f"{TUE} 07:32")
    assert tr.iloc[0].entry_time == pd.Timestamp(f"{TUE} 07:34")


def test_stop_first_on_same_bar_and_slippage():
    df = long_scenario()
    put(df, f"{TUE} 08:00", 1991, 2005.5, 1985, 1990)          # touches both
    t = run(df).iloc[0]
    assert t.reason == "stop"
    assert t.exit_px == pytest.approx(1985.9 - 0.05)
    assert t.net == pytest.approx((1985.85 - 1990.8 - 0.07) * 100)


def test_stop_gap_fills_at_open():
    df = long_scenario()
    put(df, f"{TUE} 08:00", 1980, 1981, 1979, 1980)
    t = run(df).iloc[0]
    assert t.exit_px == pytest.approx(1980 - 0.05)


def test_time_exit_at_2040_and_never_overnight():
    df = long_scenario()
    for m in pd.date_range(f"{TUE} 08:00", f"{TUE} 23:59", freq="min"):
        put(df, m, 1995, 1995, 1995, 1995)
    t = run(df).iloc[0]
    assert t.reason == "time 20:40"
    assert t.exit_time == pd.Timestamp(f"{TUE} 20:40")
    assert t.net == pytest.approx((1995 - 1990.8 - 0.07) * 100)


def test_no_room_and_small_risk_skipped():
    df = long_scenario()
    df.loc[pd.Timestamp(f"{TUE} 06:30"):pd.Timestamp(f"{TUE} 07:29"), ["open", "high", "low", "close"]] = 1990.5
    put(df, f"{TUE} 07:30", 1990.5, 1990.6, 1987, 1988)        # swing high 1990.6 < entry fill 1990.8: no room
    assert run(df).empty
    df = long_scenario()
    put(df, f"{TUE} 07:30", 1990.2, 1990.2, 1989.95, 1989.96)  # break with a shallow low
    put(df, f"{TUE} 07:31", 1989.96, 1989.99, 1989.95, 1989.98)
    put(df, f"{TUE} 07:32", 1989.98, 1990.0, 1989.97, 1989.995)
    put(df, f"{TUE} 07:33", 1989.995, 1990.0, 1989.99, 1990.0)  # fill 1990.295, stop 1989.85: R 0.445 < 0.5
    assert run(df).empty


def test_r_target_and_lock():
    t = run(long_scenario(), E.VARIANTS[4]).iloc[0]
    assert t.target_px == pytest.approx(1990.8 + 1.5 * 4.9)
    assert t.reason == "target"
    df = long_scenario()
    put(df, f"{TUE} 08:00", 1991, 1996.0, 1991, 1995.5)         # +5.2 = 1.06 R: lock to entry + 0.10 from 08:01
    put(df, f"{TUE} 08:01", 1995, 1995, 1990, 1990)
    df.loc[pd.Timestamp(f"{TUE} 08:02"):, ["open", "high", "low", "close"]] = 1990.0
    t7 = run(df, E.VARIANTS[6]).iloc[0]
    assert t7.reason == "stop" and t7.exit_time == pd.Timestamp(f"{TUE} 08:01")
    assert t7.exit_px == pytest.approx(1990.9 - 0.05)
    t1 = run(df).iloc[0]                                         # no lock: held to 20:40
    assert t1.reason == "time 20:40" and t1.exit_px == pytest.approx(1990.0)


def test_lock_steps():
    df = long_scenario()
    put(df, f"{TUE} 08:00", 1991, 1990.8 + 2.0 * 4.9, 1991, 1995)   # 2 R reached -> stop at entry + 1 R
    for m in pd.date_range(f"{TUE} 08:01", f"{TUE} 23:59", freq="min"):
        put(df, m, 1999, 1999, 1994, 1994)
    t7 = run(df, E.VARIANTS[6]).iloc[0]
    assert t7.reason == "stop"
    assert t7.exit_px == pytest.approx(1990.8 + 4.9 - 0.05)


def test_stop_order_entry_v8():
    t = run(long_scenario(), E.VARIANTS[7]).iloc[0]
    assert t.entry_time == pd.Timestamp(f"{TUE} 07:32")
    assert t.entry_px == pytest.approx(1989.5 + 0.30 + 0.05)  # max(open 1989, green high 1989.5) + spread + slip
    assert t.reason == "target"


# ------------------------------------------------------------------------------------------------ look-ahead guards
def random_minutes(seed, days=("2026-01-05", "2026-01-06", "2026-01-07", "2026-01-08")):
    rng = np.random.default_rng(seed)
    idx = pd.DatetimeIndex([])
    for d in days:
        idx = idx.append(pd.date_range(d, periods=1440, freq="min"))
    c = 2000 + np.cumsum(rng.normal(0, 0.6, len(idx)))
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) + rng.exponential(0.3, len(idx))
    l = np.minimum(o, c) - rng.exponential(0.3, len(idx))
    return pd.DataFrame({"open": o, "high": h, "low": l, "close": c}, index=idx)


@pytest.mark.parametrize("seed", range(4))
def test_daily_levels_known_only_after_the_day_closes(seed):
    df = random_minutes(seed)
    B = E.Bars(df)
    pdh, pdl = E.daily_levels(B)
    for d in ("2026-01-06", "2026-01-07", "2026-01-08"):
        first = int(np.searchsorted(B.t, np.datetime64(d)))
        cut = E.Bars(df.iloc[: first + 1])                     # data up to the day's first minute only
        h2, l2 = E.daily_levels(cut)
        assert h2[-1] == pdh[first] and l2[-1] == pdl[first]
        prev = df[(df.index >= pd.Timestamp(d) - pd.Timedelta(days=1)) & (df.index < pd.Timestamp(d))]
        assert pdh[first] == prev.high.max() and pdl[first] == prev.low.min()
        # changing today's or later prices never moves today's levels
        df2 = df.copy()
        df2.loc[df2.index >= pd.Timestamp(d), ["high", "low"]] += [500.0, -500.0]
        h3, l3 = E.daily_levels(E.Bars(df2))
        assert h3[first] == pdh[first] and l3[first] == pdl[first]


@pytest.mark.parametrize("seed", range(6))
@pytest.mark.parametrize("vi", [0, 1, 2, 3, 7])
@pytest.mark.parametrize("side", [1, -1])
def test_entries_use_only_closed_bars(seed, vi, side):
    """Every trade is found again, with the same entry bar and price, when the data stops at its entry bar (for the
    next-open entry the entry bar is cut to its open; for the stop order, to what the order needs: the bar's open and
    its reaching the green high)."""
    v = E.VARIANTS[vi]
    df = random_minutes(seed)
    tr = run(df, v, side)
    for _, t in tr.iterrows():
        e = t.entry_time
        cut = df[df.index <= e].copy()
        if v.entry == "close":
            o = cut.iloc[-1].open
            cut.iloc[-1] = [o, o, o, o]
        tr2 = run(cut, v, side)
        hit = tr2[tr2.entry_time == e]
        assert len(hit) == 1
        assert hit.iloc[0].entry_px == pytest.approx(t.entry_px)
        assert hit.iloc[0].stop_px == pytest.approx(t.stop_px)
        assert hit.iloc[0].target_px == pytest.approx(t.target_px)
        # and no bar after the entry changes the setup
        df3 = df.copy()
        df3.loc[df3.index > e, ["open", "high", "low", "close"]] = 1000.0
        same = run(df3, v, side)
        assert e in set(same.entry_time)


def test_random_data_gives_trades_and_one_per_side_per_day():
    tot = 0
    for seed in range(6):
        for side in (1, -1):
            tr = run(random_minutes(seed), V1, side)
            tot += len(tr)
            if tr.empty:
                continue
            assert tr.day.is_unique
            assert (tr.exit_time.dt.date == tr.entry_time.dt.date).all()
            assert ((tr.entry_time.dt.hour * 60 + tr.entry_time.dt.minute) <= E.LAST_ENTRY).all()
            assert ((tr.break_time.dt.hour * 60 + tr.break_time.dt.minute).between(E.W0, E.W1)).all()
    assert tot > 0


# ------------------------------------------------------------------------------------------------- twins and stats
def test_twins_on_flat_prices_lose_exactly_the_costs():
    df = flat_days(["2026-01-05", "2026-01-06", "2026-01-07"], 2000.0)
    B = E.Bars(df)
    trades = pd.DataFrame(dict(entry_mod=[480, 600], r=[3.0, 3.0], tgt_dist=[5.0, 5.0]))
    mask = np.ones(B.n, bool)
    for side in (1, -1):
        books = E.twin_nets(B, trades, V1, side, mask, n_twins=20, seed=1)
        assert np.allclose(books, -2 * (0.30 + 0.07) * 100)


def test_twins_deterministic_and_respect_mask():
    df = random_minutes(3)
    B = E.Bars(df)
    tr = run(df, V1, 1)
    if tr.empty:
        tr = pd.DataFrame(dict(entry_mod=[500], r=[2.0], tgt_dist=[3.0]))
    m = S.bar_mask(B, "2026-01-07", "2026-01-08")
    a = E.twin_nets(B, tr, V1, 1, m, n_twins=50, seed=5)
    b = E.twin_nets(B, tr, V1, 1, m, n_twins=50, seed=5)
    assert np.array_equal(a, b)
    empty = E.twin_nets(B, tr.iloc[:0], V1, 1, m, n_twins=10, seed=5)
    assert np.all(empty == 0)


def test_bh_and_stats():
    q = E.bh(np.array([0.01, 0.04, 0.03, 0.5]))
    assert q == pytest.approx([0.04, 0.04 * 4 / 3, 0.04 * 4 / 3, 0.5])
    tr = pd.DataFrame(dict(day=pd.to_datetime(["2026-01-05", "2026-01-05", "2026-01-06", "2026-01-07"]),
                           net=[100.0, -50.0, -200.0, 300.0], reason=["target", "stop", "stop", "target"]))
    st = E.stats(tr, 1.0)
    assert st["n"] == 4 and st["win"] == 0.5
    assert st["pf"] == pytest.approx(400 / 250)
    assert st["worst_day"] == -200.0
    assert st["max_dd"] == -200.0          # equity 50 -> -150 (peak 50)
    assert E.stats(tr.iloc[:0], 1.0)["n"] == 0


def test_final_refuses_a_second_read(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RES", tmp_path)
    (tmp_path / "FINAL_READ").write_text("read")
    with pytest.raises(SystemExit):
        S.final("unused.csv.gz")


def test_study_end_to_end_on_synthetic(tmp_path, monkeypatch):
    days = [d.strftime("%Y-%m-%d") for d in pd.bdate_range("2026-01-05", "2026-01-16")]
    df = random_minutes(11, days)
    p = tmp_path / "x.csv.gz"
    out = df.reset_index().rename(columns={"index": "timestamp"})
    out["timestamp"] = (out.timestamp.astype("int64") // 10 ** 6)
    out.to_csv(p, index=False, compression="gzip")
    monkeypatch.setattr(S, "RES", tmp_path / "res")
    monkeypatch.setattr(S, "DESIGN", ("2026-01-05", "2026-01-12"))
    monkeypatch.setattr(S, "FINAL", ("2026-01-13", "2026-01-16"))
    monkeypatch.setattr(S, "WF_QUARTERS", [("2026-01-07", "2026-01-09"), ("2026-01-10", "2026-01-12")])
    monkeypatch.setattr(S, "N_TWINS", 20)
    d = S.design(str(p))
    assert len(d["lines"]) == 24
    for ln in d["lines"].values():
        for ex in ln["examples"]:
            assert pd.Timestamp(ex["entry_time"]) < pd.Timestamp("2026-01-13")
    f = S.final(str(p))
    assert len(f["lines"]) == 24
    with pytest.raises(SystemExit):
        S.final(str(p))


def test_missing_weekdays_lists_only_absent_weekdays():
    import datetime as dt
    from research.hunt.r78.fetch_fill import missing_weekdays
    have = pd.Series([int(pd.Timestamp(d).value // 10 ** 6) for d in ("2026-01-05 10:00", "2026-01-07 00:00",
                                                                        "2026-01-07 23:59")])
    got = missing_weekdays(have, dt.date(2026, 1, 3), dt.date(2026, 1, 12))
    assert got == [dt.date(2026, 1, 6), dt.date(2026, 1, 8), dt.date(2026, 1, 9), dt.date(2026, 1, 12)]
