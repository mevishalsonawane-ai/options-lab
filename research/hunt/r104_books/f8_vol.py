"""R104 family F8: volatility and premium structures (PREREG.md; mechanics from research/books/TESTABLE_RULES.md).
Committed before this family's first run.

Engine (R103's conventions): legs from the held-contract store (NIFTY weekly / BANKNIFTY nearest = `WEEK`; the monthly
series `MONTH` for the monthly rules); orders at the minute after the decision fill at that minute's open (or the next
open within 5 minutes) with `liq` slippage (5 bps + 1 / 2 / 4 ticks; stops 2x) and dated charges per order; a leg with
no open within 5 minutes of its order, or with no contract in the store that day, is priced by Black-Scholes at its last
implied volatility (R103 Amendment 2; a missing contract uses the ATM implied volatility, labelled). Basket stops read
minute closes (legs forward-filled) and exit at the next minute's open. Hedges trade the index as a futures proxy (spot
plus half-spread fills, Rs 20 an order and dated futures charges per lot, carry ignored intraday and charged on
multi-day hedges). Cash settlement at expiry: the intrinsic value at the 15:29 spot, STT 0.125% on a bought leg's
intrinsic value.

Readings fixed here where the rules are silent: the HAR forecast is a one-step daily realised-volatility forecast
(1-minute RV plus the overnight return, HAR on 1 / 5 / 22-day means, refit each month on earlier data only) used as the
level to expiry; Bennett 05 is jumpy when ATM IV moved more than the skew (points) since yesterday's 09:20; Wilmott's
hedge band is +-0.5 futures lot; Bjork's book is 5 lots as written, the other rules 1 lot; WHD 01 is run intraday-to-3-
sessions on the WEEK series.
"""
from __future__ import annotations

import math
import pickle
from datetime import date, timedelta
from functools import lru_cache

import numpy as np
import pandas as pd
from scipy.optimize import brentq
from scipy.stats import norm

import r104cfg as K
import r104data as DA
import r104stats as ST
from r104run import IMPL

RATE = 0.065
TICK = 0.05
NAN = float("nan")


def custom(rid, variants, fn):
    IMPL[(rid, "VOL")] = dict(fn=None, custom=fn, variants=variants, insts=(), money=0, _family=__name__)


# ================================================================================================ pricing maths
def bs(S, k, t, v, call):
    if t <= 0 or v <= 0 or S <= 0:
        return max(S - k, 0.0) if call else max(k - S, 0.0)
    d1 = (math.log(S / k) + (RATE + v * v / 2) * t) / (v * math.sqrt(t))
    d2 = d1 - v * math.sqrt(t)
    if call:
        return S * norm.cdf(d1) - k * math.exp(-RATE * t) * norm.cdf(d2)
    return k * math.exp(-RATE * t) * norm.cdf(-d2) - S * norm.cdf(-d1)


def bs_delta(S, k, t, v, call):
    if t <= 0 or v <= 0:
        return (1.0 if S > k else 0.0) if call else (-1.0 if S < k else 0.0)
    d1 = (math.log(S / k) + (RATE + v * v / 2) * t) / (v * math.sqrt(t))
    return norm.cdf(d1) if call else norm.cdf(d1) - 1.0


def bs_vega(S, k, t, v):
    if t <= 0 or v <= 0:
        return 0.0
    d1 = (math.log(S / k) + (RATE + v * v / 2) * t) / (v * math.sqrt(t))
    return S * norm.pdf(d1) * math.sqrt(t)


def implied_vol(p, S, k, t, call):
    intr = max(S - k, 0.0) if call else max(k - S, 0.0)
    if not (p == p and S == S) or t <= 0 or p <= intr + 0.05:
        return NAN
    try:
        return brentq(lambda v: bs(S, k, t, v, call) - p, 0.005, 5.0)
    except ValueError:
        return NAN


def years_left(d, expiry, row):
    return max(((expiry - d).days * 1440 + (375 - row)) / (365.0 * 1440), 1e-6)


# ================================================================================================ chains
class Chain:
    def __init__(self, und, series="WEEK"):
        self.und = und
        self.series = series
        path = (K.STORE if series == "WEEK" else K.OUT / "store_month") / f"day_{und}.pkl"
        import r51cfg  # noqa: F401
        import r51data  # noqa: F401
        with open(path, "rb") as f:
            self.days = pickle.load(f)
        self.dates = sorted(d for d, D in self.days.items() if D.atm is not None)
        self.exp = sorted(d for d, D in self.days.items() if D.expiry)
        self.step = K.STEP[und]

    def expiry_for(self, d):
        import bisect
        i = bisect.bisect_left(self.exp, d)
        return self.exp[i] if i < len(self.exp) else None

    def D(self, d):
        return self.days.get(d)


@lru_cache(None)
def chain(und, series="WEEK"):
    return Chain(und, series)


def last_close(con, r):
    c = con[3, :r + 1]
    ok = np.flatnonzero(np.isfinite(c))
    return (float(c[ok[-1]]), int(ok[-1])) if len(ok) else (NAN, -1)


def vol5(con, r, q):
    v = con[4, max(0, r - 5):r]
    v = v[np.isfinite(v)]
    return float(v.sum()) / q if q else 0.0


def slip(p, buy, vl, mult=1.0):
    t = (1.0 if vl >= 20 else (2.0 if vl > 0 else 4.0)) * mult * TICK
    return round(p * 1.0005 + t, 2) if buy else round(max(p * 0.9995 - t, 0.0), 2)


def opt_charges(price, qty, buy, d):
    from obuy.costs import Costs
    dn = np.array([(d - date(1970, 1, 1)).days])
    return float(Costs("dated").charge(buy, np.array([price]), np.array([qty]), dn, False)[0])


def spot_at(D, r):
    c = D.idx[3, :r + 1]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else NAN


def atm_strike(D, r, step):
    k0 = D.atm[r] if D.atm is not None and np.isfinite(D.atm[r]) else NAN
    if not np.isfinite(k0):
        s = spot_at(D, r)
        k0 = math.floor(s / step + 0.5) * step if s == s else NAN
    return k0


def leg_price(ch, D, d, side, k, r, fallback_iv=NAN):
    """(raw price, stale flag) of a leg for an order at minute r: the open at r..r+5, else Black-Scholes at the leg's
    last implied volatility (or fallback_iv if the contract has no print that day)."""
    con = D.con.get((side, float(k)))
    if con is not None:
        for m in range(r, min(375, r + 6)):
            o = con[0, m]
            if np.isfinite(o) and o > 0:
                return float(o), False, con
    exp = ch.expiry_for(d)
    S = spot_at(D, r)
    t = years_left(d, exp, r)
    iv = NAN
    if con is not None:
        p, m = last_close(con, r)
        if m >= 0:
            iv = implied_vol(p, spot_at(D, m), k, years_left(d, exp, m), side == "C")
    if iv != iv:
        iv = fallback_iv
    if iv != iv:
        return NAN, True, con
    return bs(S, k, t, iv, side == "C"), True, con


def atm_iv(ch, D, d, r):
    k0 = atm_strike(D, r, ch.step)
    exp = ch.expiry_for(d)
    if k0 != k0 or exp is None:
        return NAN, k0
    S = spot_at(D, r)
    t = years_left(d, exp, r)
    ivs = []
    for side in ("C", "P"):
        con = D.con.get((side, float(k0)))
        if con is None:
            continue
        p, m = last_close(con, r)
        if m >= 0 and r - m <= 5:
            ivs.append(implied_vol(p, S, k0, t, side == "C"))
    ivs = [x for x in ivs if x == x]
    return (float(np.mean(ivs)) if ivs else NAN), k0


def leg_iv(ch, D, d, side, k, r):
    con = D.con.get((side, float(k)))
    exp = ch.expiry_for(d)
    if con is None or exp is None:
        return NAN
    p, m = last_close(con, r)
    if m < 0 or r - m > 5:
        return NAN
    return implied_vol(p, spot_at(D, m), k, years_left(d, exp, m), side == "C")


# ================================================================================================ the trade walker
def run_struct(ch, d0, legs, er, exit_day, exit_row, q, stop=None, hedge=None, settle=False, iv_fb=NAN):
    """Walk a structure from minute er of d0 to exit_row of exit_day (inclusive days), minute closes.
    legs: [(side, strike, sign, lots)]; stop(pnl_rs, minute info) -> True to exit at the next minute's open.
    hedge: dict(band_lots, every, vol: None (own IV) or a float). settle: hold to expiry and settle at intrinsic.
    Returns dict(gross, cost, net, why) or None."""
    D0 = ch.D(d0)
    if D0 is None:
        return None
    if ch.expiry_for(d0) is None or exit_day > ch.expiry_for(d0):
        return None                         # never hold a contract past its own expiry (the store's series rolls)
    entry, cost = [], 0.0
    stale_n = 0
    for side, k, sg, lots in legs:
        p, stale, con = leg_price(ch, D0, d0, side, k, er, iv_fb)
        if p != p or p <= 0:
            return None
        stale_n += stale
        vl = vol5(con, er, q) if con is not None else 0.0
        f = slip(p, sg > 0, vl)
        entry.append(f)
        cost += opt_charges(f, q * lots, sg > 0, d0)
    days = [d for d in ch.dates if d0 <= d <= exit_day]
    hedge_units = 0.0
    hedge_px = 0.0
    hedge_pnl = 0.0
    last_hedge_min = -999
    last_hedge_spot = NAN
    why = "time"
    xd, xr = exit_day, exit_row
    prev_marks = {}
    exp = ch.expiry_for(d0)
    done = False
    for di, d in enumerate(days):
        D = ch.D(d)
        r0 = er if d == d0 else 0
        r1 = exit_row if d == exit_day else (K.SQ_ROW_EXPIRY if D.expiry else 374)
        if d != d0 and hedge_units:
            # overnight carry on the hedge
            hedge_pnl -= abs(hedge_units) * spot_at(D, 0) * K.CARRY / 365.0
        for r in range(r0, r1):
            S = spot_at(D, r)
            if S != S:
                continue
            mv = 0.0
            ok = True
            for (side, k, sg, lots), f in zip(legs, entry):
                con = D.con.get((side, float(k)))
                c = con[3, r] if con is not None else NAN
                if c == c:
                    prev_marks[(side, k)] = c
                c = prev_marks.get((side, k), NAN)
                if c != c:
                    ok = False
                    break
                mv += sg * (c - f) * q * lots
            if not ok:
                continue
            pnl = mv + hedge_pnl + hedge_units * (S - hedge_px if hedge_units else 0.0)
            if stop is not None and stop(pnl, dict(d=d, r=r, S=S)):
                why, xd, xr = "stop", d, r + 1
                done = True
                break
            if hedge is not None and (r - last_hedge_min >= hedge.get("every", 15) or hedge.get("band_move")):
                net_delta = 0.0
                t = years_left(d, exp, r)
                for side, k, sg, lots in legs:
                    if hedge.get("vol_fn") is not None:
                        v = hedge["vol_fn"](S)
                    else:
                        v = hedge.get("vol") or leg_iv(ch, D, d, side, k, r)
                    if v != v:
                        v = iv_fb
                    if v == v:
                        net_delta += sg * lots * q * bs_delta(S, k, t, v, side == "C")
                tot = net_delta + hedge_units
                lot = q
                move_ok = True
                if hedge.get("band_move"):
                    move_ok = last_hedge_spot != last_hedge_spot or abs(S / last_hedge_spot - 1) >= hedge["band_move"]
                if move_ok and abs(tot) > hedge.get("band_lots", 0.5) * lot:
                    want = -round(net_delta / lot) * lot
                    trade = want - hedge_units
                    if trade:
                        px = S + (K.index_half_spread(S) if trade > 0 else -K.index_half_spread(S))
                        # realise the old hedge at px, open the new size at px
                        hedge_pnl += hedge_units * (px - hedge_px) if hedge_units else 0.0
                        cost += fut_order_cost(abs(trade) * px, trade < 0, d)
                        hedge_units, hedge_px = want, px
                        last_hedge_spot = S
                last_hedge_min = r
        if done:
            break
    D = ch.D(xd)
    gross = 0.0
    if settle and not done:
        # cash settlement at the expiry's 15:29 spot
        S = spot_at(D, 374)
        for (side, k, sg, lots), f in zip(legs, entry):
            intr = max(S - k, 0.0) if side == "C" else max(k - S, 0.0)
            gross += sg * (intr - f) * q * lots
            if sg > 0 and intr > 0:
                cost += 0.00125 * intr * q * lots
        why = "expiry"
        Sx = S
    else:
        xr = min(xr, 374)
        for (side, k, sg, lots), f in zip(legs, entry):
            p, stale, con = leg_price(ch, D, xd, side, k, xr, iv_fb)
            if p != p:
                p = prev_marks.get((side, k), f)
            stale_n += stale
            vl = vol5(con, xr, q) if con is not None else 0.0
            x = slip(p, sg < 0, vl, 2.0 if why == "stop" else 1.0)
            gross += sg * (x - f) * q * lots
            cost += opt_charges(x, q * lots, sg < 0, xd)
        Sx = spot_at(D, xr)
    if hedge_units:
        px = Sx - (K.index_half_spread(Sx) if hedge_units > 0 else -K.index_half_spread(Sx))
        hedge_pnl += hedge_units * (px - hedge_px)
        cost += fut_order_cost(abs(hedge_units) * px, hedge_units > 0, xd)
    gross += hedge_pnl
    return dict(gross=gross, cost=cost, net=gross - cost, why=why, stale=stale_n, exit_day=xd)


def fut_order_cost(value, sell, d):
    """One index-futures order: Rs 20, exchange + SEBI (+ GST), stamp on a buy, STT on a sell, dated."""
    exch = K._dated(d, K._FUT_EXCH) * value
    sebi = 1e-6 * value
    return 20.0 + exch + sebi + 0.18 * (20.0 + exch + sebi) + (K._dated(d, K._FUT_STT) * value if sell else 0.00002 * value)


# ================================================================================================ forecasts
@lru_cache(None)
def daily_rv(und):
    """Annualised daily realised vol from 1-minute log returns plus the overnight return (index_1m)."""
    s = DA.idx_series_long(und, 2017 if und == "NIFTY" else 2021)
    lr = np.diff(np.log(s.c))
    same = s.ses[1:] == s.ses[:-1]
    rv = {}
    for i in range(len(s.days)):
        a, z = s.s0[i], s.s1[i]
        x = lr[a:z][same[a:z]]
        on = np.log(s.o[a] / s.c[a - 1]) if a > 0 else 0.0
        rv[s.days[i]] = math.sqrt((np.sum(x * x) + on * on) * 252)
    return pd.Series(rv).sort_index()


@lru_cache(None)
def har_forecast(und):
    """{date: forecast of the NEXT session's annualised RV}, HAR refit monthly on earlier data only (expanding)."""
    rv = daily_rv(und)
    x = pd.DataFrame(dict(d=rv, w=rv.rolling(5).mean(), m=rv.rolling(22).mean()))
    y = rv.shift(-1)
    out = {}
    dates = list(rv.index)
    coef = None
    last_month = None
    for i, dt in enumerate(dates):
        if i < 250:
            continue
        mo = (dt.year, dt.month)
        if mo != last_month:
            tr = x.iloc[22:i - 1].dropna()
            yy = y.loc[tr.index].dropna()
            tr = tr.loc[yy.index]
            A = np.c_[np.ones(len(tr)), tr.values]
            coef = np.linalg.lstsq(A, yy.values, rcond=None)[0]
            last_month = mo
        row = x.iloc[i - 1]                       # yesterday's values: known at today's 09:20
        if row.isna().any():
            continue
        out[dt] = float(coef @ np.r_[1.0, row.values])
    return out


@lru_cache(None)
def rv20(und):
    c = DA.index_daily(und).close
    r = np.log(c).diff()
    return (r.rolling(20).std() * math.sqrt(252)).shift(1).to_dict()      # known before today's open


@lru_cache(None)
def ewma_vol(und, lam=0.94):
    c = DA.index_daily(und).close
    r = np.log(c).diff().fillna(0).values
    v = np.zeros(len(r))
    v[0] = r[1:30].var()
    for i in range(1, len(r)):
        v[i] = lam * v[i - 1] + (1 - lam) * r[i - 1] ** 2
    return dict(zip(c.index, np.sqrt(v * 252)))


@lru_cache(None)
def prev_day_ret(und):
    c = DA.index_daily(und).close
    return (c / c.shift(1) - 1).shift(1).to_dict()       # yesterday's close-to-close, known today


@lru_cache(None)
def vix_minutes():
    """{day: (375,) India VIX minute closes (NaN-filled forward)}."""
    s = DA.idx_series_long("INDIAVIX", 2021)
    out = {}
    for i, d in enumerate(s.days):
        a, z = s.s0[i], s.s1[i] + 1
        arr = np.full(375, np.nan)
        rr = s.tod[a:z] - K.NSE_OPEN
        ok = (rr >= 0) & (rr < 375)
        arr[rr[ok]] = s.c[a:z][ok]
        out[d] = pd.Series(arr).ffill().values
    return out


@lru_cache(None)
def vix_prev_close():
    c = DA.index_daily("INDIAVIX").close
    return c.shift(1).to_dict()


def event_days(kinds=("budget", "rbi")):
    e = DA.events()
    return set(e[e.kind.isin(kinds)].date)


def in_part(d, part):
    return K.period_of("VOL", d) == part


def frame(rows):
    t = pd.DataFrame(rows)
    if len(t) == 0:
        return pd.DataFrame(columns=["day", "net", "fold"])
    t = t[np.isfinite(t.net.astype(float))]        # a trade with no price at all (no spot print) is dropped
    t["fold"] = [K.fold_of("VOL", d) for d in t.day]
    return t


def daily_ratio(und, ch, fc, r=5):
    """{day: ATM IV at minute r / forecast} for every chain day (both NaN-safe)."""
    out = {}
    for d in ch.dates:
        D = ch.D(d)
        iv, _ = atm_iv(ch, D, d, r)
        f = fc.get(d, NAN) if isinstance(fc, dict) else NAN
        if iv == iv and f == f and f > 0:
            out[d] = iv / f
    return out


def trailing_pct(series: dict, d, q, n=252):
    keys = [k for k in series if k < d][-n:]
    if len(keys) < 60:
        return NAN
    return float(np.percentile([series[k] for k in keys], q))


# ================================================================================================ tests
UNDS = ("NIFTY", "BANKNIFTY")


def _skip_intraday(d, und):
    return d in event_days() or prev_day_ret(und).get(d, 0.0) < -0.03


def bennett01(part):
    rows = []
    for und in UNDS:
        ch = chain(und)
        ratio = daily_ratio(und, ch, har_forecast(und))
        step = ch.step
        for d in ch.dates:
            if not in_part(d, part) or d not in ratio or _skip_intraday(d, und):
                continue
            p70 = trailing_pct(ratio, d, 70)
            if not (ratio[d] >= p70):
                continue
            D = ch.D(d)
            k0 = atm_strike(D, 5, step)
            legs = [("C", k0, -1, 1), ("P", k0, -1, 1), ("C", k0 + 4 * step, 1, 1), ("P", k0 - 4 * step, 1, 1)]
            q = K.lot(und, d)
            iv, _ = atm_iv(ch, D, d, 5)
            credit0 = sum(-sg * (spot_leg(D, side, k, 5)) for side, k, sg, _l in legs) * q
            maxloss = 4 * step * q - credit0
            lim = min(credit0, maxloss) if credit0 > 0 else None
            stop = (lambda pnl, info, lim=lim: lim is not None and pnl <= -lim)
            sq = K.SQ_ROW_EXPIRY if D.expiry else K.SQ_ROW
            r = run_struct(ch, d, legs, 6, d, sq, q, stop=stop, iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def spot_leg(D, side, k, r):
    con = D.con.get((side, float(k)))
    if con is None:
        return NAN
    return last_close(con, r)[0]


def bennett04(part, band_move):
    rows = []
    for und in UNDS:
        ch = chain(und)
        fc = har_forecast(und)
        ratio = daily_ratio(und, ch, fc)
        for d in ch.dates:
            if not in_part(d, part) or d not in ratio:
                continue
            p30 = trailing_pct(ratio, d, 30)
            if not (ratio[d] <= p30):
                continue
            D = ch.D(d)
            k0 = atm_strike(D, 5, ch.step)
            q = K.lot(und, d)
            legs = [("C", k0, 1, 1), ("P", k0, 1, 1)]
            prem = sum(spot_leg(D, s_, k0, 5) for s_ in ("C", "P")) * q
            stop = (lambda pnl, info, prem=prem: pnl <= -0.4 * prem)
            sq = K.SQ_ROW_EXPIRY if D.expiry else K.SQ_ROW
            hedge = dict(band_lots=0.5, every=1, vol=fc.get(d), band_move=band_move)
            r = run_struct(ch, d, legs, 6, d, sq, q, stop=stop, hedge=hedge)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def bennett05(part):
    rows = []
    for und in UNDS:
        ch = chain(und)
        skew_hist = {}
        prev_iv = None
        for d in ch.dates:
            D = ch.D(d)
            k0 = atm_strike(D, 5, ch.step)
            if k0 != k0:
                continue
            ivp = leg_iv(ch, D, d, "P", k0 - 5 * ch.step, 5)
            ivc = leg_iv(ch, D, d, "C", k0 + 5 * ch.step, 5)
            iv0, _ = atm_iv(ch, D, d, 5)
            if not (ivp == ivp and ivc == ivc and iv0 == iv0):
                prev_iv = iv0 if iv0 == iv0 else prev_iv
                continue
            skew = (ivp - ivc) * 100
            past = [skew_hist[k] for k in sorted(skew_hist)[-60:]]
            skew_hist[d] = skew
            jumpy = prev_iv is not None and abs(iv0 - prev_iv) * 100 > skew
            prev_iv = iv0
            if not in_part(d, part) or len(past) < 30 or skew <= np.median(past) or jumpy:
                continue
            if d in event_days() or prev_day_ret(und).get(d, 0.0) < -0.02:
                continue
            vm = vix_minutes().get(d)
            pc = vix_prev_close().get(d, np.nan)
            if vm is None or pc != pc or not np.isfinite(vm[5]) or vm[5] / pc - 1 >= 0.10:
                continue                                   # VIX up 10% on the day by 09:20 (or unknown): skip
            q = K.lot(und, d)
            legs = [("P", k0 - 5 * ch.step, -1, 1), ("C", k0 + 5 * ch.step, 1, 1)]
            S0 = spot_at(D, 5)
            lim = 0.015 * S0 * q
            stop = (lambda pnl, info, lim=lim, vm=vm, pc=pc: pnl <= -lim or (np.isfinite(vm[info["r"]]) and
                                                                         vm[info["r"]] / pc - 1 >= 0.15))
            sq = K.SQ_ROW_EXPIRY if D.expiry else K.SQ_ROW
            r = run_struct(ch, d, legs, 6, d, sq, q, stop=stop, hedge=dict(band_lots=0.5, every=15), iv_fb=iv0)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def javaheri03(part, with_stop):
    rows = []
    for und in UNDS:
        ch = chain(und)
        ratio = daily_ratio(und, ch, har_forecast(und))
        for d in ch.dates:
            if not in_part(d, part) or d not in ratio:
                continue
            if not (ratio[d] >= trailing_pct(ratio, d, 70)):
                continue
            D = ch.D(d)
            k0 = atm_strike(D, 5, ch.step)
            sc, sp = spot_leg(D, "C", k0, 5), spot_leg(D, "P", k0, 5)
            if not (sc == sc and sp == sp):
                continue
            w = max(1, int(round((sc + sp) / ch.step)))
            if 2 * w > 10:
                continue
            st = ch.step
            legs = [("C", k0, -1, 1), ("P", k0, -1, 1), ("C", k0 + w * st, 1, 1), ("P", k0 - w * st, 1, 1),
                    ("C", k0 + 2 * w * st, -1, 1), ("P", k0 - 2 * w * st, -1, 1)]
            q = K.lot(und, d)
            credit = sum(-sg * spot_leg(D, s_, k, 5) for s_, k, sg, _ in legs) * q
            stop = (lambda pnl, info, c=credit: pnl <= -c) if with_stop and credit > 0 else None
            iv, _ = atm_iv(ch, D, d, 5)
            sq = K.SQ_ROW_EXPIRY if D.expiry else K.SQ_ROW
            r = run_struct(ch, d, legs, 6, d, sq, q, stop=stop, iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def short_straddle_hedged(part, und_list, lots, cond, stop_fn, hedge, skip_expiry=True, smile=False):
    rows = []
    for und in und_list:
        ch = chain(und)
        for d in ch.dates:
            if not in_part(d, part):
                continue
            D = ch.D(d)
            if (skip_expiry and D.expiry) or d in event_days():
                continue
            iv, k0 = atm_iv(ch, D, d, 5)
            if iv != iv or not cond(und, d, iv, D):
                continue
            q = K.lot(und, d)
            legs = [("C", k0, -1, lots), ("P", k0, -1, lots)]
            credit = sum(spot_leg(D, s_, k0, 5) for s_ in ("C", "P")) * q * lots
            stop = (lambda pnl, info, c=credit: stop_fn(pnl, c))
            sq = K.SQ_ROW_EXPIRY if D.expiry else K.SQ_ROW
            r = run_struct(ch, d, legs, 6, d, sq, q, stop=stop, hedge=hedge, iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def bjork01(part):
    return short_straddle_hedged(part, ("NIFTY",), 5, lambda u, d, iv, D: True,
                                 lambda pnl, c: pnl <= -min(13000.0, c), dict(band_lots=1.0, every=15))


def bjork02(part):
    rows = []
    und = "BANKNIFTY"
    ch = chain(und)
    fc = har_forecast(und)
    gap = DA.index_daily(und)
    gp = (gap.open / gap.close.shift(1) - 1).to_dict()
    for d in ch.dates:
        if not in_part(d, part):
            continue
        D = ch.D(d)
        iv, k0 = atm_iv(ch, D, d, 5)
        f = fc.get(d, NAN)
        if iv != iv or f != f or not iv < f or abs(gp.get(d, 0.0)) > 0.02:
            continue
        q = K.lot(und, d)
        legs = [("C", k0, 1, 5), ("P", k0, 1, 5)]
        prem = sum(spot_leg(D, s_, k0, 5) for s_ in ("C", "P")) * q * 5
        stop = (lambda pnl, info, p=prem: pnl <= -0.4 * p)
        sq = K.SQ_ROW_EXPIRY if D.expiry else K.SQ_ROW
        r = run_struct(ch, d, legs, 6, d, sq, q, stop=stop, hedge=dict(band_lots=1.0, every=15), iv_fb=iv)
        if r:
            rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def musiela03(part):
    hist = {}

    def cond(und, d, iv, D):
        rv = rv20(und).get(d, NAN)
        if rv != rv or rv <= 0:
            return False
        R = iv / rv
        h = hist.setdefault(und, {})
        past = [h[k] for k in sorted(h)[-250:]]
        h[d] = R
        mv = abs(spot_at(D, 5) / D.idx[0, 0] - 1) if np.isfinite(D.idx[0, 0]) else 1.0
        return len(past) >= 60 and R >= np.percentile(past, 80) and mv <= 0.005
    return short_straddle_hedged(part, UNDS, 1, cond, lambda pnl, c: pnl <= -0.5 * c or pnl <= -13000.0, None)


def musiela02(part, book):
    """The 5-lot short straddle with hedge A (BS delta at each leg's own IV) or B (+ vega x the smile slope)."""
    # the smile slope s (ATM IV change per index point), regressed on the previous 20 sessions' 09:20-15:10 minutes
    if book == "A":
        return short_straddle_hedged(part, ("NIFTY",), 5, lambda u, d, iv, D: True, lambda pnl, c: pnl <= -13000.0,
                                     dict(band_lots=1.0, every=15))
    ch = chain("NIFTY")
    slopes = smile_slopes(ch)
    rows = []
    for d in ch.dates:
        if not in_part(d, part):
            continue
        D = ch.D(d)
        if D.expiry or d in event_days():
            continue
        iv, k0 = atm_iv(ch, D, d, 5)
        s_ = slopes.get(d, NAN)
        if iv != iv or s_ != s_:
            continue
        q = K.lot("NIFTY", d)
        legs = [("C", k0, -1, 5), ("P", k0, -1, 5)]
        # B: delta + vega x s, i.e. an effective delta from a vol that moves with spot; modelled by hedging with the
        # BS delta evaluated at iv + s x (S - S0)
        S0 = spot_at(D, 5)
        r = run_struct_smile(ch, d, legs, 6, K.SQ_ROW, q, iv, s_, S0)
        if r:
            rows.append(dict(day=d, und="NIFTY", **r))
    return frame(rows)


@lru_cache(None)
def smile_slopes(ch):
    out = {}
    hist = []
    for d in ch.dates:
        D = ch.D(d)
        if len(hist) >= 20:
            xs = np.concatenate([h[0] for h in hist[-20:]])
            ys = np.concatenate([h[1] for h in hist[-20:]])
            if len(xs) > 50 and np.std(xs) > 0:
                out[d] = float(np.polyfit(xs, ys, 1)[0])
        xs, ys = [], []
        exp = ch.expiry_for(d)
        k0 = atm_strike(D, 5, ch.step)
        if exp is None or k0 != k0:
            continue
        for r in range(5, K.SQ_ROW, 15):
            S = spot_at(D, r)
            kk = atm_strike(D, r, ch.step)
            iv, _ = atm_iv(ch, D, d, r)
            if iv == iv and S == S:
                xs.append(S - spot_at(D, 5))
                ys.append(iv)
        if len(xs) > 5:
            hist.append((np.array(xs), np.array(ys)))
    return out


def run_struct_smile(ch, d, legs, er, sq, q, iv0, s_, S0):
    """Book B: hedge with the BS delta at the smile-implied vol iv0 + s x (S - S0) (delta + vega x s, Musiela)."""
    hedge = dict(band_lots=1.0, every=15, vol_fn=lambda S: max(iv0 + s_ * (S - S0), 0.01))
    return run_struct(ch, d, legs, er, d, sq, q, stop=lambda pnl, info: pnl <= -13000.0, hedge=hedge, iv_fb=iv0)


def binomial03(part):
    """Sell 1 ATM straddle when ATM IV >= 20-day RV + 2 points; hedge on 5-minute checks when |delta| > 0.2 lot (whole
    lots); stop at a loss of 1.5x the credit; hold to the expiry square-off (15:05). One position at a time."""
    rows = []
    for und in UNDS:
        ch = chain(und)
        busy_until = None
        for d in ch.dates:
            if not in_part(d, part) or (busy_until and d <= busy_until):
                continue
            D = ch.D(d)
            if d in event_days():
                continue
            iv, k0 = atm_iv(ch, D, d, 5)
            rv = rv20(und).get(d, NAN)
            if iv != iv or rv != rv or iv < rv + 0.02:
                continue
            exp = ch.expiry_for(d)
            if exp is None or ch.D(exp) is None:
                continue
            q = K.lot(und, d)
            legs = [("C", k0, -1, 1), ("P", k0, -1, 1)]
            credit = sum(spot_leg(D, s_, k0, 5) for s_ in ("C", "P")) * q
            r = run_struct(ch, d, legs, 6, exp, K.SQ_ROW_EXPIRY, q, stop=lambda pnl, info, c=credit: pnl <= -1.5 * c,
                           hedge=dict(band_lots=0.2, every=5), iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
                busy_until = r["exit_day"]
    return frame(rows)


def ross02(part):
    rows = []
    for und in UNDS:
        ch = chain(und)
        c = DA.index_daily(und).close
        lr = np.log(c).diff()
        state = pd.cut(lr, [-np.inf, -0.01, 0, 0.01, np.inf], labels=False)
        nxt = lr.shift(-1)
        dates = list(c.index)
        pos = {d: i for i, d in enumerate(dates)}
        for d in ch.dates:
            if not in_part(d, part) or d not in pos:
                continue
            i = pos[d]
            if i < 502:
                continue
            st = state.iloc[i - 1]
            win = pd.DataFrame(dict(s=state.iloc[i - 501:i - 1].values, n=nxt.iloc[i - 501:i - 1].values)).dropna()
            same = win[win.s == st].n
            if len(same) < 20:
                continue
            f = float(same.std() * math.sqrt(252))
            D = ch.D(d)
            exp = ch.expiry_for(d)
            if exp is None or len([x for x in ch.dates if d <= x <= exp]) <= 2:
                continue                                  # the last 2 sessions before expiry
            iv, k0 = atm_iv(ch, D, d, 2)
            if iv != iv or f <= iv + 0.02:
                continue
            q = K.lot(und, d)
            legs = [("C", k0, 1, 1), ("P", k0, 1, 1)]
            prem = sum(spot_leg(D, s_, k0, 2) for s_ in ("C", "P")) * q
            r = run_struct(ch, d, legs, 1, d, K.SQ_ROW, q, stop=lambda pnl, info, p=prem: pnl <= -0.3 * p, iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def whd02(part):
    """Buy the ATM straddle at 15:15 the session before an RBI policy / Budget day, sell at 15:15 on the event day, in
    the nearest expiry at least 2 sessions away (the weekly series, else the monthly series)."""
    rows = []
    ev = sorted(event_days())
    for und in UNDS:
        chw, chm = chain(und), chain(und, "MONTH")
        past_ranges = []
        for e in ev:
            prev = [d for d in chw.dates if d < e]
            if not prev or chw.D(e) is None:
                continue
            d = prev[-1]
            De = chw.D(e)
            rr = (np.nanmax(De.idx[1]) - np.nanmin(De.idx[2]))
            ch = None
            for c_ in (chw, chm):
                x = c_.expiry_for(d)
                if x is not None and c_.D(d) is not None and len([y for y in c_.dates if d < y <= x]) >= 2:
                    ch = c_
                    break
            if ch is None or not in_part(d, part):
                past_ranges.append(rr)
                continue
            D = ch.D(d)
            k0 = atm_strike(D, 360, ch.step)
            q = K.lot(und, d)
            cost_pts = spot_leg(D, "C", k0, 360) + spot_leg(D, "P", k0, 360)
            if past_ranges and cost_pts > np.median(past_ranges):
                past_ranges.append(rr)
                continue
            legs = [("C", k0, 1, 1), ("P", k0, 1, 1)]
            r = run_struct(ch, d, legs, 360, e, 360, q)
            if r:
                rows.append(dict(day=d, und=und, **r))
            past_ranges.append(rr)
    return frame(rows)


def multi_day_entry_days(ch):
    """The first session after each expiry of the chain."""
    out = []
    for e in ch.exp:
        nxt = [d for d in ch.dates if d > e]
        if nxt:
            out.append((nxt[0], ch.expiry_for(nxt[0])))
    return out


def bennett02(part, with_stop):
    """Long 1 lot future (spot proxy) + short the nearest weekly call nearest 25 delta, the call settled at expiry;
    one cycle per expiry. Stop variant: the future at -3% from entry closes the cycle."""
    rows = []
    und = "NIFTY"
    ch = chain(und)
    for d, exp in multi_day_entry_days(ch):
        if exp is None or not in_part(d, part) or ch.D(exp) is None:
            continue
        D = ch.D(d)
        S = spot_at(D, 5)
        t = years_left(d, exp, 5)
        best, bk = None, None
        for j in range(0, 11):
            k = atm_strike(D, 5, ch.step) + j * ch.step
            v = leg_iv(ch, D, d, "C", k, 5)
            if v != v:
                continue
            dl = bs_delta(S, k, t, v, True)
            if best is None or abs(dl - 0.25) < best:
                best, bk = abs(dl - 0.25), k
        if bk is None:
            continue
        q = K.lot(und, d)
        legs = [("C", bk, -1, 1)]
        stop = (lambda pnl, info, S0=S: info["S"] <= S0 * 0.97) if with_stop else None
        r = run_struct(ch, d, legs, 6, exp, 374, q, stop=stop, settle=True)
        if not r:
            continue
        # the future leg: from the 09:21 spot to the call's exit (stop) or the expiry close
        De = ch.D(r["exit_day"])
        Sx = spot_at(De, 374) if r["why"] == "expiry" else S * 0.97
        fut = price_fut(und, d, r["exit_day"], S, Sx, q, r["why"] == "stop")
        rows.append(dict(day=d, und=und, gross=r["gross"] + fut["gross"], cost=r["cost"] + fut["cost"],
                         net=r["net"] + fut["net"], why=r["why"], exit_day=r["exit_day"]))
    return frame(rows)


def price_fut(und, d0, d1, e, x, q, stop):
    import r104sim as SIM
    return SIM.price_index_future(und, d0, d1, 1, e, x, stop, False, q)


def bennett03(part, iv_filter):
    rows = []
    for und in UNDS:
        ch = chain(und)
        fc = har_forecast(und)
        for d, exp in multi_day_entry_days(ch):
            if exp is None or not in_part(d, part) or ch.D(exp) is None:
                continue
            D = ch.D(d)
            S = spot_at(D, 5)
            t = years_left(d, exp, 5)
            best, bk, bv = None, None, None
            for j in range(0, 11):
                k = atm_strike(D, 5, ch.step) - j * ch.step
                v = leg_iv(ch, D, d, "P", k, 5)
                if v != v:
                    continue
                dl = bs_delta(S, k, t, v, False)
                if best is None or abs(dl + 0.25) < best:
                    best, bk, bv = abs(dl + 0.25), k, v
            if bk is None:
                continue
            if iv_filter and not (bv > fc.get(d, np.inf)):
                continue
            q = K.lot(und, d)
            legs = [("P", bk, -1, 1), ("P", bk - 3 * ch.step, 1, 1)]
            credit = (spot_leg(D, "P", bk, 5) - spot_leg(D, "P", bk - 3 * ch.step, 5)) * q
            if not credit > 0:
                continue
            # the spread's value reaching 2x the credit = a loss of 1x the credit
            r = run_struct(ch, d, legs, 6, exp, K.SQ_ROW_EXPIRY, q, stop=lambda pnl, info, c=credit: pnl <= -c, iv_fb=bv)
            if r:
                rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def bennett09(part):
    rows = []
    for und in UNDS:
        ch = chain(und)
        c = DA.index_daily(und).close
        fall3 = (c / c.shift(3) - 1).shift(1).to_dict()
        busy = None
        for d in ch.dates:
            if not in_part(d, part) or (busy and d <= busy) or not (fall3.get(d, 0) <= -0.04):
                continue
            D = ch.D(d)
            exp = ch.expiry_for(d)
            if exp is None or ch.D(exp) is None:
                continue
            S = spot_at(D, 5)
            prev = c[c.index < d]
            fall = prev.iloc[-4] - prev.iloc[-1]
            k0 = atm_strike(D, 5, ch.step)
            ks = round((S + fall / 2) / ch.step) * ch.step
            legs = [("C", k0, 1, 1), ("C", ks, -1, 2), ("C", ks + 2 * ch.step, 1, 1)]
            q = K.lot(und, d)
            iv, _ = atm_iv(ch, D, d, 5)
            r = run_struct(ch, d, legs, 6, exp, K.SQ_ROW_EXPIRY, q, stop=lambda pnl, info, k=ks: info["S"] >= k, iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
                busy = r["exit_day"]
    return frame(rows)


def wilmott01(part, variant):
    rows = []
    und = "NIFTY"
    ch = chain(und, "MONTH")
    ew = ewma_vol(und)
    for d, exp in multi_day_entry_days(ch):
        if exp is None or not in_part(d, part) or ch.D(exp) is None:
            continue
        D = ch.D(d)
        iv, k0 = atm_iv(ch, D, d, 365)
        f = ew.get(d, NAN)
        if iv != iv or f != f or iv / f < 1.15:
            continue
        if any(e for e in event_days() if d < e <= exp):
            continue
        q = K.lot(und, d)
        if variant == "C":
            S = spot_at(D, 365)
            t = years_left(d, exp, 365)
            worst = sum(bs(S * (1 + g), k0, t, iv + 0.05, s_ == "C") - bs(S, k0, t, iv, s_ == "C")
                        for s_ in ("C", "P") for g in (0.03,)) * q
            worst = max(worst, sum(bs(S * 0.97, k0, t, iv + 0.05, s_ == "C") - bs(S, k0, t, iv, s_ == "C")
                                   for s_ in ("C", "P")) * q)
            if worst > 40000:
                continue
        legs = [("C", k0, -1, 1), ("P", k0, -1, 1)]
        stop = (lambda pnl, info: pnl <= -13000.0) if variant == "B" else None
        r = run_struct(ch, d, legs, 366, exp, K.SQ_ROW_EXPIRY, q, stop=stop, hedge=dict(band_lots=0.5, every=5), iv_fb=iv)
        if r:
            rows.append(dict(day=d, und=und, **r))
    return frame(rows)


def ross04(part):
    """AR(1) horizon variance vs the GBM variance in ATM IV: ratio < 0.6 and the ADF test passes -> sell an ATM iron
    butterfly (wings 2 strikes) at the next open with 10-30 sessions to expiry; stop at a loss of 1x the credit; out one
    session before expiry. NIFTY monthly series, BANKNIFTY nearest (monthly after Nov 2024)."""
    from f12_quant import adf_t, ols
    rows = []
    for und, series in (("NIFTY", "MONTH"), ("BANKNIFTY", "MONTH")):
        ch = chain(und, series)
        c = DA.index_daily(und).close
        L = np.log(c.values)
        pos = {d: i for i, d in enumerate(c.index)}
        busy = None
        for d in ch.dates:
            if not in_part(d, part) or d not in pos or (busy and d <= busy):
                continue
            i = pos[d]
            if i < 252:
                continue
            exp = ch.expiry_for(d)
            if exp is None:
                continue
            left = [x for x in ch.dates if d < x <= exp]
            if not (10 <= len(left) <= 30):
                continue
            y, x = L[i - 250:i], L[i - 251:i - 1]
            a, b = ols(y, x)
            if not b < 1 or adf_t(L[i - 251:i]) >= -2.87:
                continue
            res = y - (a + b * x)
            s2 = res.var()
            h = len(left)
            ar_var = s2 * (1 - b ** (2 * h)) / (1 - b * b)
            D = ch.D(d)
            iv, k0 = atm_iv(ch, D, d, 2)
            if iv != iv:
                continue
            gbm = iv * iv * h / 252.0
            if not ar_var / gbm < 0.6:
                continue
            st = ch.step
            legs = [("C", k0, -1, 1), ("P", k0, -1, 1), ("C", k0 + 2 * st, 1, 1), ("P", k0 - 2 * st, 1, 1)]
            q = K.lot(und, d)
            credit = sum(-sg * spot_leg(D, s_, k, 2) for s_, k, sg, _ in legs) * q
            if not credit > 0:
                continue
            out_day = left[-2] if len(left) >= 2 else exp
            r = run_struct(ch, d, legs, 1, out_day, K.SQ_ROW, q, stop=lambda pnl, info, c=credit: pnl <= -c, iv_fb=iv)
            if r:
                rows.append(dict(day=d, und=und, **r))
                busy = r["exit_day"]
    return frame(rows)


def whd01(part):
    """IV-residual pair on the nearest expiry, ATM +-5, 5-minute checks 09:30-14:45; residual = IV minus the median IV by
    moneyness over the previous 20 sessions at the same time; sell the richest, buy the cheapest when the gap > 3 points,
    delta-hedged in whole lots; out at a gap < 1 point, a Rs 3,000 loss, 15:05 on expiry day or 3 sessions."""
    rows = []
    for und in UNDS:
        ch = chain(und)
        hist = {}                  # (time slot, moneyness step, side) -> list of IVs (one per session)
        busy = None
        for di, d in enumerate(ch.dates):
            D = ch.D(d)
            exp = ch.expiry_for(d)
            today = {}
            for r in range(15, 330, 5):
                S = spot_at(D, r)
                k0 = atm_strike(D, r, ch.step)
                if S != S or k0 != k0 or exp is None:
                    continue
                res = {}
                for j in range(-5, 6):
                    for side in ("C", "P"):
                        v = leg_iv(ch, D, d, side, k0 + j * ch.step, r)
                        if v == v:
                            today[(r, j, side)] = v
                            past = hist.get((r, j, side), [])[-20:]
                            if len(past) >= 10:
                                res[(side, k0 + j * ch.step)] = (v - np.median(past)) * 100
                if (busy and d <= busy) or not in_part(d, part) or len(res) < 4:
                    continue
                hi = max(res, key=res.get)
                lo = min(res, key=res.get)
                if res[hi] - res[lo] <= 3:
                    continue
                q = K.lot(und, d)
                legs = [(hi[0], hi[1], -1, 1), (lo[0], lo[1], 1, 1)]
                later = [x for x in ch.dates if x >= d][:3]
                xd = later[-1]
                if exp and exp < xd:
                    xd = exp
                xr = K.SQ_ROW_EXPIRY if ch.D(xd).expiry else K.SQ_ROW
                rr = run_struct(ch, d, legs, r + 1, xd, xr, q, stop=lambda pnl, info: pnl <= -3000.0,
                                hedge=dict(band_lots=0.5, every=5), iv_fb=NAN)
                if rr:
                    rows.append(dict(day=d, und=und, **rr))
                    busy = rr["exit_day"]
                break
            for kk, v in today.items():
                hist.setdefault(kk, []).append(v)
    return frame(rows)


custom("book-bennett-01", {"A": dict()}, bennett01)
custom("book-bennett-02", {"nostop": dict(with_stop=False), "stop3": dict(with_stop=True)}, bennett02)
custom("book-bennett-03", {"plain": dict(iv_filter=False), "ivf": dict(iv_filter=True)}, bennett03)
custom("book-bennett-04", {"A": dict(band_move=0.0), "B": dict(band_move=0.005)}, bennett04)
custom("book-bennett-05", {"base": dict()}, bennett05)
custom("book-bennett-09", {"base": dict()}, bennett09)
custom("book-fmwilmott-01", {"A": dict(variant="A"), "B": dict(variant="B"), "C": dict(variant="C")}, wilmott01)
custom("book-fmjavaheri-03", {"nostop": dict(with_stop=False), "stop": dict(with_stop=True)}, javaheri03)
custom("book-fmbjork-01", {"base": dict()}, bjork01)
custom("book-fmbjork-02", {"base": dict()}, bjork02)
custom("book-fmbinomial-03", {"base": dict()}, binomial03)
custom("book-fmwhd-01", {"base": dict()}, whd01)
custom("book-fmwhd-02", {"base": dict()}, whd02)
custom("book-fmmusiela-02", {"B": dict(book="B")}, musiela02)
custom("book-fmmusiela-02~host", {"A": dict(book="A")}, musiela02)
custom("book-fmmusiela-03", {"base": dict()}, musiela03)
custom("book-fmross-02", {"base": dict()}, ross02)
custom("book-fmross-04", {"base": dict()}, ross04)
