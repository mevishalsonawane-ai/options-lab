"""Tests for the R53 MCX book (synthetic data only; no lake needed): data, held-contract pricing, fills and gap fills,
stops, expiry days, missing prints, empty data, the rules' signals and the look-ahead guard."""
from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r53 import common as C
from research.hunt.r53.mcx import data as D
from research.hunt.r53.mcx import rules as R
from research.hunt.r53.mcx import sim as S
from research.hunt.r53.mcx import trend as T


# ------------------------------------------------------------------------------------------------ builders
def minutes(day, a="09:00", b="23:29"):
    return pd.date_range(f"{day} {a}", f"{day} {b}", freq="1min")


def opt_rows(ts, strike, cp, close, volume=10, offset=-1, high=None, low=None, open_=None):
    n = len(ts)
    close = np.broadcast_to(np.asarray(close, float), (n,)).copy()
    return pd.DataFrame({"ts": ts, "cp": cp, "offset": offset, "strike": float(strike),
                         "open": close if open_ is None else open_, "high": close if high is None else high,
                         "low": close if low is None else low, "close": close,
                         "volume": np.broadcast_to(np.asarray(volume), (n,)).copy(), "f1": False})


def market(ts, F, opt=None, sym="NATURALGAS"):
    return D.Market(sym, pd.DatetimeIndex(ts).values, np.asarray(F, float), opt)


def flat_market(day="2025-09-10", F0=300.0, call_k=295.0, put_k=305.0, prem=10.0, extra_opts=None):
    ts = minutes(day)
    F = np.full(len(ts), F0)
    O = pd.concat([opt_rows(ts, call_k, 0, prem, offset=-1), opt_rows(ts, put_k, 1, prem, offset=1),
                   opt_rows(ts, 300.0, 0, prem, offset=0), opt_rows(ts, 300.0, 1, prem, offset=0)]
                  + (extra_opts or []), ignore_index=True)
    return ts, F, O


def idx(m, hhmm, day=None):
    d = pd.Timestamp(day or pd.Timestamp(m.ts[0]).date())
    h, mi = map(int, hhmm.split(":"))
    return m.at_or_after(d, h * 60 + mi)


# ------------------------------------------------------------------------------------------------ data
def test_us_dst_and_session_close():
    assert D.us_dst(date(2025, 8, 5)) and not D.us_dst(date(2025, 12, 1))
    assert D.us_dst(date(2026, 3, 9)) and not D.us_dst(date(2026, 3, 7))
    assert D.session_close(np.datetime64("2025-08-05"), 23 * 60 + 29) == 23 * 60 + 30
    assert D.session_close(np.datetime64("2025-12-01"), 23 * 60 + 54) == 23 * 60 + 55
    # a stray 23:30 bar in summer does not move the close; a short day ends with its data
    assert D.session_close(np.datetime64("2025-08-05"), 23 * 60 + 30) == 23 * 60 + 30
    assert D.session_close(np.datetime64("2025-08-05"), 14 * 60 + 45) == 14 * 60 + 46


def test_market_days_cut_and_empty():
    ts, F, O = flat_market()
    m = market(ts, F, O)
    d = m.days[0]
    assert m.tod[m.day_cut[d]] == 23 * 60 + 19          # 10 minutes before 23:30
    assert m.session_end(d) == 23 * 60 + 30
    assert m.at_or_before(d, 9 * 60 - 1) == -1
    e = market([], [], None)
    assert len(e.days) == 0 and e.legs == {} and len(e.day_ix("2025-09-10")) == 0


def test_build_market_median_spot_and_f1_print_removed():
    ts = minutes("2025-09-10", "10:00", "10:40")
    rows = []
    for t in ts:
        for off, side, k in ((-1, "C", 295.0), (0, "C", 300.0), (0, "P", 300.0), (1, "P", 305.0)):
            rows.append(dict(ts=t, offset=off, side=side, strike=k, spot=300.0 + off * 0.0, open=10.0, high=10.0,
                             low=10.0, close=10.0, volume=5))
    raw = pd.DataFrame(rows)
    raw.loc[(raw.offset == -1), "spot"] = 302.0            # median of 300, 300, 300, 302 = 300
    spike = (raw.ts == ts[20]) & (raw.offset == -1)
    raw.loc[spike, ["open", "high", "low", "close"]] = 40.0  # a one-minute bad print, 4x its neighbours
    m = D.build_market("NATURALGAS", raw)
    assert np.allclose(m.F, 300.0)
    ii, V = m.leg(m.days[0], 0, 295.0)
    assert m.ts[ii].tolist().count(np.datetime64(ts[20])) == 0   # flagged print is no print
    assert len(ii) == len(ts) - 1


def test_detect_expiries_day_boundary_jump():
    ts = np.concatenate([minutes(d, "09:00", "09:30") for d in ("2025-09-22", "2025-09-23", "2025-09-24")])
    day = pd.DatetimeIndex(ts).normalize().values
    s = np.where(day == np.datetime64("2025-09-24"), 300.0, 20.0)
    s[day == np.datetime64("2025-09-23")] = 8.0              # decays into expiry
    s[5] = 500.0                                             # a single bad print is not an expiry
    exp = D.detect_expiries(day, s)
    assert [str(x)[:10] for x in exp] == ["2025-09-23"]
    assert len(D.detect_expiries(day, np.full(len(ts), np.nan))) == 0


def test_px_at_open_then_recent_close_then_nan():
    y = pd.DataFrame({"open": [10.0, 11.0], "close": [10.5, 11.5]},
                     index=pd.to_datetime(["2025-09-10 17:00", "2025-09-10 18:00"]))
    assert D.px_at(y, "2025-09-10 18:00") == 11.0
    assert D.px_at(y, "2025-09-10 18:25") == 11.5
    assert D.px_at(y, "2025-09-10 20:00") == 11.5            # exactly 2 h old
    assert np.isnan(D.px_at(y, "2025-09-10 20:01"))
    assert np.isnan(D.px_at(y, "2025-09-10 16:00"))


def test_eia_releases_dst_exact_and_exceptions():
    r = D.eia_releases(date(2025, 9, 1), date(2025, 12, 31))
    assert r[pd.Timestamp("2025-09-10")] == 20 * 60          # 10:30 EDT = 20:00 IST
    assert r[pd.Timestamp("2025-12-03")] == 21 * 60          # 10:30 EST = 21:00 IST
    assert r[pd.Timestamp("2025-09-04")] == 21 * 60 + 30     # holiday week: Thursday 12:00 EDT
    assert pd.Timestamp("2025-09-03") not in r
    assert r[pd.Timestamp("2025-12-30")] == 3 * 60 + 30      # 29 Dec 17:00 EST = 03:30 IST next day (no MCX trade)


# ------------------------------------------------------------------------------------------------ option simulation
def test_option_entry_first_print_in_wait_else_no_trade():
    ts, F, _ = flat_market()
    j0 = 600
    vol = np.full(len(ts), 10)
    vol[j0 + 1:j0 + 3] = 0                                   # no print at e, e+1; print at e+2
    O = pd.concat([opt_rows(ts, 295.0, 0, 10.0, volume=vol, offset=-1)])
    m = market(ts, F, O)
    t = S.sim_option(m, j0, 1, exit_i=j0 + 30, fam="natgas", mult=1250, wait=2)
    assert t["entry_i"] == j0 + 3 - 1 + 1 and t["fill"] == 10.0
    vol[j0 + 3] = 0
    m2 = market(ts, F, pd.concat([opt_rows(ts, 295.0, 0, 10.0, volume=vol, offset=-1)]))
    assert S.sim_option(m2, j0, 1, exit_i=j0 + 30, fam="natgas", mult=1250, wait=2) is None


def test_option_no_strike_or_no_leg_or_last_minute():
    ts, F, _ = flat_market()
    m = market(ts, F, opt_rows(ts, 295.0, 0, 10.0, offset=-1))
    assert S.sim_option(m, 100, -1, exit_i=200, fam="natgas", mult=1250) is None   # no put offset at all
    assert S.sim_option(m, len(ts) - 1, 1, exit_i=len(ts) - 1, fam="natgas", mult=1250) is None


def test_option_stop_at_level_on_entry_minute_and_gap_fill_later():
    ts, F, _ = flat_market()
    j = 300
    c = np.full(len(ts), 10.0)
    lo = c.copy()
    lo[j + 1] = 8.0                                          # entry minute trades through -15% (8.5)
    m = market(ts, F, opt_rows(ts, 295.0, 0, c, low=lo, offset=-1))
    t = S.sim_option(m, j, 1, exit_i=j + 60, fam="natgas", mult=1250, stop_pct=0.15)
    assert t["why"] == "opt_stop" and t["exit"] == pytest.approx(8.5) and t["exit_i"] == j + 1
    # later minute opens below the stop: filled at that open (the worse price)
    o = c.copy()
    o[j + 5] = 7.0
    lo2 = c.copy()
    lo2[j + 5] = 6.5
    m2 = market(ts, F, opt_rows(ts, 295.0, 0, c, low=lo2, open_=o, offset=-1))
    t2 = S.sim_option(m2, j, 1, exit_i=j + 60, fam="natgas", mult=1250, stop_pct=0.15)
    assert t2["why"] == "opt_stop" and t2["exit"] == 7.0


def test_option_target_and_stop_first_in_one_minute():
    ts, F, _ = flat_market()
    j = 300
    c = np.full(len(ts), 10.0)
    hi, lo = c.copy(), c.copy()
    hi[j + 4], lo[j + 4] = 16.0, 7.0
    m = market(ts, F, opt_rows(ts, 295.0, 0, c, high=hi, low=lo, offset=-1))
    t = S.sim_option(m, j, 1, exit_i=j + 60, fam="natgas", mult=1250, stop_pct=0.25, tgt_pct=0.5)
    assert t["why"] == "opt_stop" and t["exit"] == 7.5
    hi2 = c.copy()
    o2 = c.copy()
    hi2[j + 4], o2[j + 4] = 17.0, 16.0
    m2 = market(ts, F, opt_rows(ts, 295.0, 0, c, high=hi2, open_=o2, offset=-1))
    t2 = S.sim_option(m2, j, 1, exit_i=j + 60, fam="natgas", mult=1250, stop_pct=0.25, tgt_pct=0.5)
    assert t2["why"] == "opt_target" and t2["exit"] == 16.0   # gapped over the target: its open


def test_option_future_stop_prices_next_print_or_stale():
    ts, F0, _ = flat_market()
    j = 300
    F = F0.copy()
    F[j + 10:] = 290.0                                       # the future closes below the stop at j + 10
    c = np.full(len(ts), 10.0)
    c[j + 11:] = 6.0
    vol = np.full(len(ts), 10)
    vol[j + 10] = 0                                          # no print at the stop minute
    m = market(ts, F, opt_rows(ts, 295.0, 0, c, volume=vol, offset=-1))
    t = S.sim_option(m, j, 1, exit_i=j + 60, fam="natgas", mult=1250, stopF=295.0)
    assert t["why"] == "fut_stop" and t["exit"] == 6.0 and t["exit_i"] == j + 11 and not t["stale"]
    vol2 = np.full(len(ts), 10)
    vol2[j + 10:] = 0                                        # nothing prints after it: stale last close
    m2 = market(ts, F, opt_rows(ts, 295.0, 0, c, volume=vol2, offset=-1))
    t2 = S.sim_option(m2, j, 1, exit_i=j + 60, fam="natgas", mult=1250, stopF=295.0)
    assert t2["stale"] and t2["exit"] == 10.0


def test_option_held_contract_ignores_strike_switch_of_rolling_series():
    ts, F, _ = flat_market()
    j = 300
    a = opt_rows(ts[:j + 20], 295.0, 0, 10.0, offset=-1)
    b = opt_rows(ts[j + 20:], 300.0, 0, 50.0, offset=-1)    # the series moves to another strike
    held_later = opt_rows(ts[j + 20:], 295.0, 0, 12.0, offset=-2)
    m = market(ts, F, pd.concat([a, b, held_later], ignore_index=True))
    t = S.sim_option(m, j, 1, exit_i=j + 40, fam="natgas", mult=1250)
    assert t["strike"] == 295.0 and t["exit"] == 12.0


def test_option_costs_formula_and_time_exit():
    ts, F, _ = flat_market()
    j = 18 * 60 - 9 * 60                                     # 18:00: evening spread
    m = market(ts, F, opt_rows(ts, 295.0, 0, 10.0, offset=-1))
    t = S.sim_option(m, j, 1, exit_i=j + 30, fam="natgas", mult=1250)
    buy = sell = 10.0 * 1250
    want = C.mcx_opt_charges(buy, sell) + C.opt_half_spread("natgas", 18 * 60) * (buy + sell)
    assert t["why"] == "time" and t["gross"] == 0 and t["cost"] == pytest.approx(want)
    assert t["exit_i"] == j + 30
    t2 = S.sim_option(m, j, 1, exit_i=j + 30, fam="natgas", mult=1250, hold_from_fill=10)
    assert t2["exit_i"] == j + 1 + 10


# ------------------------------------------------------------------------------------------------ futures simulation
def test_fut_next_bar_close_fill_and_gap_through_stop():
    ts = minutes("2025-09-10")
    F = np.full(len(ts), 300.0)
    F[101] = 301.0
    F[110] = 290.0                                           # gaps through a 298 stop: filled at 290 (worse)
    m = market(ts, F)
    t = S.sim_fut(m, 101, 1, exit_i=200, fam="natgas", units=250, tick=0.1, stopF=298.0)
    assert t["fill"] == 301.0 and t["exit"] == 290.0 and t["why"] == "fut_stop"
    assert t["gross"] == pytest.approx(-11 * 250)
    t2 = S.sim_fut(m, 101, -1, exit_i=200, fam="natgas", units=250, tick=0.1, stop_pct=0.03)
    assert t2["why"] == "time" and t2["exit_i"] == 200
    t3 = S.sim_fut(m, 101, 1, exit_i=200, fam="natgas", units=250, tick=0.1, stop_pct=0.03)
    assert t3["why"] == "pct_stop" and t3["exit"] == 290.0
    assert S.sim_fut(m, len(ts), 1, exit_i=len(ts), fam="natgas", units=1, tick=0.1) is None


def test_fut_costs():
    ts = minutes("2025-09-10")
    m = market(ts, np.full(len(ts), 6000.0))
    t = S.sim_fut(m, 10, -1, exit_i=20, fam="crude", units=10, tick=1.0)
    want = C.mcx_fut_charges(60000, 60000) + 2 * 1.0 * 10
    assert t["cost"] == pytest.approx(want)


# ------------------------------------------------------------------------------------------------ rules
class Ctx(R.Ctx):
    pass


def eve_market(break_at="19:30", side=1, expiry=False):
    day = "2025-09-10"
    ts = minutes(day)
    tod = pd.DatetimeIndex(ts).hour * 60 + pd.DatetimeIndex(ts).minute
    F = np.where((tod >= 17 * 60) & (tod < 19 * 60), 300 + np.sin(np.arange(len(ts))) * 2, 300.0)
    h, mi = map(int, break_at.split(":"))
    F = np.where(tod >= h * 60 + mi, 300 + side * 4, F)
    O = pd.concat([opt_rows(ts, 295.0, 0, 10.0, offset=-1), opt_rows(ts, 305.0, 1, 10.0, offset=1)])
    m = market(ts, F, O)
    if expiry:
        m.exp_days = np.array([m.days[0]])
        m.is_exp = np.isin(m.day, m.exp_days)
    return m


def test_range_break_first_close_beyond_and_needs_60_minutes():
    m = eve_market()
    j, side, hi, lo = R.range_break(m, m.days[0], 17 * 60, 19 * 60, 22 * 60)
    assert m.tod[j] == 19 * 60 + 30 and side == 1 and hi == pytest.approx(302, abs=0.01)
    thin = market(m.ts[(m.tod < 17 * 60 + 30) | (m.tod >= 19 * 60)], m.F[(m.tod < 17 * 60 + 30) | (m.tod >= 19 * 60)])
    assert R.range_break(thin, thin.days[0], 17 * 60, 19 * 60, 22 * 60) is None
    nob = eve_market(break_at="23:00")
    assert R.range_break(nob, nob.days[0], 17 * 60, 19 * 60, 22 * 60) is None


def test_ma1_signal_exits_and_expiry_skip():
    m = eve_market()
    ctx = R.Ctx(markets={"NATURALGAS": m})
    r = R.MA1()
    s = r.signals(ctx, r.variants["orig"], m.days)
    assert len(s) == 1 and s[0]["side"] == 1
    assert s[0]["stopF"] == pytest.approx(300, abs=0.1) and m.tod[s[0]["exit_i"]] == 19 * 60 + 31 + 120
    tr = r.run(ctx, r.variants["orig"], m.days)
    assert len(tr) == 1 and tr[0]["strike"] == 295.0
    late = eve_market(break_at="21:59")
    ctx2 = R.Ctx(markets={"NATURALGAS": late})
    s2 = r.signals(ctx2, r.variants["orig"], late.days)
    assert late.tod[s2[0]["exit_i"]] == 23 * 60 + 15          # flat by 23:15
    mx = eve_market(expiry=True)
    assert R.MA1().signals(R.Ctx(markets={"NATURALGAS": mx}), r.variants["orig"], mx.days) == []
    # the futures version (MA1b) trades expiry days
    gm = eve_market(expiry=True)
    gm.sym = "GOLDM"
    assert len(R.MA1b().signals(R.Ctx(markets={"GOLDM": gm}), R.MA1b.variants["orig"], gm.days)) == 1


def test_twins_one_way_keep_side_and_two_way_mix():
    m = eve_market()
    ctx = R.Ctx(markets={"NATURALGAS": m})
    r = R.MA1()
    P = r.variants["orig"]
    tr = r.run(ctx, P, m.days)
    tw = r.twins(ctx, P, m.days, len(tr), tr, 20, np.random.default_rng(0))
    assert tw.shape == (20,) and np.isfinite(tw).all()
    assert r.twins(ctx, P, m.days, 0, tr, 5, np.random.default_rng(0)).tolist() == [0.0] * 5


def two_day_market(gap=0.01, sym="NATURALGAS", roll=False):
    ts = np.concatenate([minutes("2025-09-09").values, minutes("2025-09-10").values])
    F = np.where(pd.DatetimeIndex(ts).day == 9, 300.0, 300.0 * np.exp(gap))
    O = pd.concat([opt_rows(pd.DatetimeIndex(ts), 295.0, 0, 10.0, offset=-1),
                   opt_rows(pd.DatetimeIndex(ts), 305.0, 1, 10.0, offset=1)])
    m = market(ts, F, O, sym=sym)
    if roll:
        m.seg = (m.day == np.datetime64("2025-09-10")).astype(np.int32)
    return m


def test_ma3_gap_follow_next_minute_fill_and_roll_skip():
    m = two_day_market(gap=0.01)
    ctx = R.Ctx(markets={"NATURALGAS": m})
    r = R.MA3Fut()
    tr = r.run(ctx, r.variants["orig"], m.days)
    assert len(tr) == 1 and tr[0]["side"] == 1
    assert pd.Timestamp(tr[0]["entry_ts"]).strftime("%H:%M") == "09:06"
    assert pd.Timestamp(tr[0]["exit_ts"]).strftime("%H:%M") == "14:00"
    xe = r.run(ctx, r.variants["xeod"], m.days)
    assert pd.Timestamp(xe[0]["exit_ts"]).strftime("%H:%M") == "23:19"
    mr = two_day_market(gap=0.01, roll=True)
    assert R.MA3Fut().run(R.Ctx(markets={"NATURALGAS": mr}), r.variants["orig"], mr.days) == []
    mo = two_day_market(gap=-0.01)
    t = R.MA3Opt().run(R.Ctx(markets={"NATURALGAS": mo}), R.MA3Opt.variants["orig"], mo.days)
    assert len(t) == 1 and t[0]["side"] == -1 and t[0]["strike"] == 305.0


def yahoo_pair(us_prev, us_now, fx_prev=83.0, fx_now=83.0, prev_hour="2025-09-09 18:00"):
    idx = pd.to_datetime([prev_hour, "2025-09-10 03:00"])
    return (pd.DataFrame({"open": [us_prev, us_now], "close": [us_prev, us_now]}, index=idx),
            pd.DataFrame({"open": [fx_prev, fx_now], "close": [fx_prev, fx_now]}, index=idx))


def test_ma2_follows_us_and_fx_with_disaster_stop():
    m = two_day_market(gap=0.0, sym="SILVERM")
    us, fx = yahoo_pair(30.0, 30.3)                          # 23:30 IST = 18:00 UTC on a summer day
    ctx = R.Ctx(markets={"SILVERM": m}, yahoo={"SI_F": us, "INR_X": fx})
    r = R.MA2()
    tr = r.run(ctx, r.variants["orig"], m.days)
    assert len(tr) == 1 and tr[0]["side"] == 1
    assert pd.Timestamp(tr[0]["entry_ts"]).strftime("%H:%M") == "09:05"
    us2, fx2 = yahoo_pair(30.0, 29.0)
    ctx2 = R.Ctx(markets={"SILVERM": m}, yahoo={"SI_F": us2, "INR_X": fx2})
    assert r.run(ctx2, r.variants["orig"], m.days)[0]["side"] == -1
    ctx3 = R.Ctx(markets={"SILVERM": m}, yahoo={"SI_F": us2.iloc[1:], "INR_X": fx2})
    assert r.run(ctx3, r.variants["orig"], m.days) == []    # a missing price: no trade


def test_ma4_fades_residual_beyond_threshold():
    m = two_day_market(gap=0.004, sym="CRUDEOIL")           # gap +40 bp, US +10 bp -> residual +30 bp > 12 bp
    us, fx = yahoo_pair(60.0, 60.0 * np.exp(0.001))
    ctx = R.Ctx(markets={"CRUDEOIL": m}, yahoo={"CL_F": us, "INR_X": fx})
    r = R.MA4Crude()
    tr = r.run(ctx, r.variants["orig"], m.days)
    assert len(tr) == 1 and tr[0]["side"] == -1
    us2, fx2 = yahoo_pair(60.0, 60.0 * np.exp(0.0035))       # residual 5 bp: no trade
    assert r.run(R.Ctx(markets={"CRUDEOIL": m}, yahoo={"CL_F": us2, "INR_X": fx2}), r.variants["orig"], m.days) == []


def test_ma5_catch_up_signal_and_one_at_a_time():
    ts = minutes("2025-09-10", "09:00", "12:00")
    F = np.full(len(ts), 100000.0)
    x = pd.Series(2000.0, index=ts)
    x.iloc[60:] = 2000.0 * np.exp(0.003)                     # XAU jumps 30 bp at 10:00, GOLDM does not follow
    O = pd.concat([opt_rows(ts, 99000.0, 0, 3000.0, offset=-1), opt_rows(ts, 101000.0, 1, 3000.0, offset=1)])
    m = market(ts, F, O, sym="GOLDM")
    ctx = R.Ctx(markets={"GOLDM": m}, xau=x)
    r = R.MA5()
    s = r.signals(ctx, r.variants["orig"], m.days)
    assert [m.tod[g["j"]] for g in s] == [600, 601, 602, 603, 604] and all(g["side"] == 1 for g in s)
    tr = r.run(ctx, r.variants["orig"], m.days)
    assert len(tr) == 1 and tr[0]["exit_i"] - tr[0]["entry_i"] == 15


def test_ma5u_has_no_daily_cap_and_ma5_keeps_one():
    assert R.MA5.max_per_day == 1 and R.MA5().max_per_day == 1
    assert R.MA5u.max_per_day >= 10 ** 9 and R.MA5u.rid == "MA5u" and R.MA5u not in R.MINUTE_RULES
    ts = minutes("2025-09-10", "09:00", "12:00")
    F = np.full(len(ts), 100000.0)
    x = pd.Series(2000.0, index=ts)
    x.iloc[60:] = 2000.0 * np.exp(0.003)                     # a lead at 10:00 ...
    x.iloc[100:] = 2000.0 * np.exp(0.006)                    # ... and another at 10:40
    O = pd.concat([opt_rows(ts, 99000.0, 0, 3000.0, offset=-1), opt_rows(ts, 101000.0, 1, 3000.0, offset=1)])
    m = market(ts, F, O, sym="GOLDM")
    P = R.MA5.variants["orig"]
    capped = R.MA5().run(R.Ctx(markets={"GOLDM": m}, xau=x), P, m.days)
    free = R.MA5u().run(R.Ctx(markets={"GOLDM": m}, xau=x), P, m.days)
    assert len(capped) == 1 and len(free) == 2 and free[1]["entry_i"] >= free[0]["exit_i"]


def test_ema_adx_bars_and_signals():
    ts = minutes("2025-09-10", "09:00", "14:59")
    t = np.arange(len(ts))
    F = 100000 + np.where(t < 120, -t * 5.0, -600 + (t - 120) * 8.0)
    m = market(ts, F, None, sym="GOLDM")
    B = R.bars_from_closes(m, 5)
    assert B.start.iloc[0] == 540 and B.sig_min.iloc[0] == 544 and B.c.iloc[0] == F[4]
    sg = R.ema_adx_signals(B)
    assert (sg.side == 1).any()


def test_gann_swing_long_after_top_break():
    h = np.array([10, 11, 12, 11, 10, 9, 10, 11, 12, 13], float)
    B = dict(h=h, l=h - 1, c=h - 0.5)
    sig = R.gann_swing_signals(B)
    assert sig and sig[0][1] == 1 and sig[0][2] == pytest.approx(8.0)
    assert R.gann_swing_signals(dict(h=np.array([]), l=np.array([]), c=np.array([]))) == []


def test_ma7_nat_exit_and_one_way():
    ts = np.concatenate([minutes(d).values for d in ("2025-09-08", "2025-09-09", "2025-09-10")])
    rng = np.random.default_rng(4)                          # a path with long and short swing breaks
    F = 100000 + np.cumsum(rng.normal(0, 40, len(ts)))
    m = market(ts, F, None, sym="SILVERM")
    ctx = R.Ctx(markets={"SILVERM": m})
    r = R.MA7()
    tr = r.run(ctx, r.variants["orig"], m.days)
    assert len(tr) > 0 and all(t["side"] == 1 for t in tr)
    both = R.MA7().run(R.Ctx(markets={"SILVERM": m}), r.variants["both"], m.days)
    assert {t["side"] for t in both} == {1, -1}
    for t in tr + both:
        assert t["entry_i"] % R.W_COLS - (pd.Timestamp(t["sig_ts"]).hour * 60 + pd.Timestamp(t["sig_ts"]).minute - 540) == 1
        assert t["why"] in ("fut_stop", "time")
        assert pd.Timestamp(t["exit_ts"]).hour * 60 + pd.Timestamp(t["exit_ts"]).minute <= 23 * 60 + 15


def test_ma9_only_on_eia_days():
    r = R.MA9()
    d = pd.Timestamp("2025-09-10")                            # a Wednesday: 20:00 IST release
    assert r.windows(None, d) == (19 * 60 + 45, 20 * 60, 20 * 60 + 30)
    assert r.windows(None, pd.Timestamp("2025-09-11")) is None


# ------------------------------------------------------------------------------------------------ look-ahead guard
def truncated(m, j):
    keep = np.arange(len(m.ts)) <= j
    t = D.Market(m.sym, m.ts[keep], m.F[keep], None)
    t.legs, t.k_at = m.legs, {k: v[keep] for k, v in m.k_at.items()}
    t.exp_days, t.seg, t.is_exp = m.exp_days, m.seg[keep], m.is_exp[keep]
    return t


def test_lookahead_range_break_unchanged_when_data_after_signal_removed():
    m = eve_market()
    j, side, hi, lo = R.range_break(m, m.days[0], 17 * 60, 19 * 60, 22 * 60)
    t = truncated(m, j + 1)                                   # keep the entry minute only
    assert R.range_break(t, t.days[0], 17 * 60, 19 * 60, 22 * 60) == (j, side, hi, lo)


def test_lookahead_ema_adx_and_gann_prefix_stable():
    rng = np.random.default_rng(3)
    ts = np.concatenate([minutes(d, "09:00", "14:59").values for d in ("2025-09-09", "2025-09-10")])
    F = 100000 + np.cumsum(rng.normal(0, 20, len(ts)))
    m = market(ts, F, None, sym="GOLDM")
    full = R.ema_adx_signals(R.bars_from_closes(m, 5))
    assert len(full) > 0
    for _, row in full.iterrows():
        cut = truncated(m, int(row.last_i))
        part = R.ema_adx_signals(R.bars_from_closes(cut, 5))
        assert int(part.iloc[-1].last_i) == int(row.last_i) and int(part.iloc[-1].side) == int(row.side)
    h = 100 + np.cumsum(rng.normal(0, 1, 300))
    B = dict(h=h + 0.5, l=h - 0.5, c=h)
    sg = R.gann_swing_signals(B)
    for t, side, stop in sg:
        sub = {k: v[:t + 1] for k, v in B.items()}
        assert R.gann_swing_signals(sub)[-1] == (t, side, stop)


def test_lookahead_gap_and_catch_up_use_only_past():
    m = two_day_market(gap=0.01)
    j = m.at_or_after(m.days[1], 9 * 60 + 5)
    assert R.gap_at(truncated(m, j), m.days[1], j) == pytest.approx(R.gap_at(m, m.days[1], j))
    ts = minutes("2025-09-10", "09:00", "11:00")
    rng = np.random.default_rng(5)
    F = 100000 * np.exp(np.cumsum(rng.normal(0, 3e-4, len(ts))))
    x = pd.Series(2000 * np.exp(np.cumsum(rng.normal(0, 6e-4, len(ts)))), index=ts)
    m2 = market(ts, F, None, sym="GOLDM")
    cu = R.MA5().catch_up(R.Ctx(markets={"GOLDM": m2}, xau=x))
    for j in (30, 60, 90):
        cut = truncated(m2, j)
        cu2 = R.MA5().catch_up(R.Ctx(markets={"GOLDM": cut}, xau=x[x.index <= ts[j]]))
        assert cu2[j] == pytest.approx(cu[j])


# ------------------------------------------------------------------------------------------------ MA10 trend
def daily(closes, start="2024-01-01", oi=None):
    idx = pd.bdate_range(start, periods=len(closes))
    c = np.asarray(closes, float)
    return pd.DataFrame({"open": c, "high": c, "low": c, "close": c, "volume": 100.0,
                         "oi": oi if oi is not None else 1000.0}, index=idx)


def test_month_signals_252_rows():
    df = daily(np.linspace(100, 200, 300))
    sg = T.month_signals(df)
    assert (sg[sg.index < df.index[252]] == 0).all() and sg.iloc[-1] == 1
    df2 = daily(np.linspace(200, 100, 300))
    assert T.month_signals(df2).iloc[-1] == -1


def test_leg_months_roll_gap_not_booked_and_roll_cost_charged():
    idx = pd.bdate_range("2025-01-01", "2025-02-28")
    c = np.full(len(idx), 100.0)
    oi = np.full(len(idx), 1000.0)
    k = list(idx).index(pd.Timestamp("2025-01-20"))
    c[k:] = 110.0                                             # the new contract is 10 higher (contango)
    oi[k:] = 5000.0
    df = pd.DataFrame({"open": c, "high": c, "low": c, "close": c, "volume": 100.0, "oi": oi}, index=idx)
    sides = {pd.Period("2025-01"): 1, pd.Period("2025-02"): 1}
    recs = T.leg_months(df, sides, 10.0, 1.0, "crude", 1.0, "CRUDEOILM", list(sides))
    jan = recs[0]
    assert jan["gross"] == 0.0                                # the roll jump is not profit
    roll = T.side_cost(110.0, 10, 1.0, "crude", buy=False) + T.side_cost(110.0, 10, 1.0, "crude", buy=True)
    entry = T.side_cost(100.0, 10, 1.0, "crude", buy=True)
    assert jan["cost"] == pytest.approx(entry + roll)
    assert recs[1]["cost"] == 0 and recs[1]["gross"] == 0     # carried, no costs


def test_leg_months_flip_closes_at_next_open():
    idx = pd.bdate_range("2025-01-01", "2025-02-28")
    c = np.linspace(100, 120, len(idx))
    df = pd.DataFrame({"open": c, "high": c, "low": c, "close": c, "volume": 100.0, "oi": 1000.0}, index=idx)
    df.loc[df.index >= "2025-02-01", "open"] = df.close + 1
    sides = {pd.Period("2025-01"): 1, pd.Period("2025-02"): -1}
    recs = T.leg_months(df, sides, 1.0, 1.0, "silver", 1.0, "SILVERMIC", list(sides))
    feb = recs[1]
    jan_close = df.close[df.index < "2025-02-01"].iloc[-1]
    feb_open = df.open[df.index >= "2025-02-01"].iloc[0]
    assert feb["prev_side"] == 1 and feb["side"] == -1
    expect = (feb_open - jan_close) - (df.close[df.index >= "2025-02-01"].iloc[-1] - feb_open)
    assert feb["gross"] == pytest.approx(expect)


def test_trend_twins_and_months():
    assert T.months_of("design")[0] == pd.Period("2024-12") and T.months_of("design")[-1] == pd.Period("2026-03")
    assert T.months_of("final")[0] == pd.Period("2026-04")


# ------------------------------------------------------------------------------------------------ runner end to end
def test_runner_design_then_one_final_read(tmp_path, monkeypatch):
    from research.hunt.r53.mcx import run as U
    days = ["2025-09-10", "2025-09-11", "2026-05-12", "2026-05-13"]
    ms = [eve_market() for _ in days]
    ts = np.concatenate([pd.DatetimeIndex(m.ts) + (pd.Timestamp(d) - pd.Timestamp("2025-09-10")) for m, d in zip(ms, days)])
    F = np.concatenate([m.F for m in ms])
    O = pd.concat([pd.concat([opt_rows(pd.DatetimeIndex(ts), 295.0, 0, 10.0, offset=-1),
                              opt_rows(pd.DatetimeIndex(ts), 305.0, 1, 10.0, offset=1)])])
    mk = market(ts, F, O)
    monkeypatch.setattr(D, "load_market", lambda sym, rebuild=False: mk)
    monkeypatch.setattr(R, "MINUTE_RULES", [R.MA1])
    legs = {s: daily(100 + np.cumsum(np.random.default_rng(i).normal(0, 1, 760)), start="2023-12-01")
            for i, s in enumerate(T.LEGS)}
    monkeypatch.setattr(T, "load_legs", lambda lake=None: legs)
    monkeypatch.setattr(C, "RESULTS", tmp_path)
    monkeypatch.setattr(U, "TRADES_DIR", tmp_path / "trades")
    des = U.design(n_twins=5, workers=1)
    assert set(des["rules"]) == {"MA1", "MA10"}
    v = des["rules"]["MA1"]["variants"]["orig"]
    assert v["trades"] == 2 and v["trading_days"] == 2 and v["p"] is not None
    assert des["rules"]["MA10"]["variants"]["orig"]["trades"] > 0
    fin = U.final(n_twins=5, workers=1)
    assert fin["rules"]["MA1"]["variants"]["orig"]["trades"] == 2
    assert all(pd.Timestamp(x["entry_ts"]) >= pd.Timestamp("2026-04-01")
               for x in pd.read_csv(tmp_path / "trades" / "mcx_MA10_orig_final.csv.gz").to_dict("records"))
    with pytest.raises(C.FinalReadError):
        U.final(n_twins=5, workers=1)
