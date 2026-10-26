"""R109 engine (PREREG.md sections 1, 2, 4, 7): pure functions on one day's arrays, so tests can drive them with
hand-made bars.

Arrays: an index day `idx` (4, 375) O H L C on the 09:15..15:29 minute grid; an option contract `con` (5, 375) O H L C V;
NaN where nothing printed. Row r = 09:15 + r minutes; a bar's close is known at the start of row r + 1.

mom60 on real prints
    ret60(c)                          trailing 60-minute return (%) at every row
    mom60_day(...)                    the day's signal walk (Mom60.Watcher.process) with a pricing callback
    long_trade(...)                   one bought option: next-minute fill, 30% premium stop on closes, 60-minute hold
strangle on real prints
    strangle_week(...)                Monday 09:31 sell CE ATM+200 / PE ATM-200, buy back Thursday 15:29 or settle
IraThinker's own BS tape (OptionsMath / Mom60 / Strangle ported line for line)
    ira_bs, mom60_bs, strangle_bs
statistics
    twin_p, boot_p, bh, period_stats
"""
from __future__ import annotations

import bisect
import math
from datetime import date, timedelta

import numpy as np

import r109cfg as K

from obuy.costs import Costs  # noqa: E402

COSTS = Costs("dated")
NAN = float("nan")


# ------------------------------------------------------------------------------------------------ prices and costs
def r2(x: float) -> float:
    return round(x + 0.0, 2)


def vol5_lots(con: np.ndarray, r: int, lot_: int) -> float:
    """Contracts traded in the 5 minutes before minute r, in lots (R103)."""
    v = con[4, max(0, r - 5): r]
    v = v[np.isfinite(v)]
    return float(v.sum()) / lot_ if lot_ else 0.0


def ticks(v_lots: float) -> float:
    return 1.0 if v_lots >= K.THIN_LOTS else (2.0 if v_lots > 0 else 4.0)


def slip(p: float, buy: bool, v_lots: float, stop: bool = False, mult: float = 1.0) -> float:
    """'liq' fills: 5 bps (10 for a stop) plus 1-4 ticks (twice for a stop) against us."""
    bps = K.BPS_STOP if stop else K.BPS
    t = ticks(v_lots) * (2.0 if stop else 1.0) * mult * K.TICK
    return r2(p * (1 + bps / 1e4) + t) if buy else r2(max(p * (1 - bps / 1e4) - t, 0.0))


def charges(price: float, qty: int, buy: bool, d: date, bse: bool) -> float:
    if not (price > 0):
        return 0.0
    dn = np.array([(d - date(1970, 1, 1)).days])
    return float(COSTS.charge(buy, np.array([price]), np.array([qty]), dn, bse)[0])


def first_open(con: np.ndarray, r: int, look: int) -> tuple[float, int]:
    """(open, row) of the first print with an open in rows r..r+look; (NaN, -1) if none."""
    for k in range(max(r, 0), min(K.NROWS, r + look + 1)):
        o = con[0, k]
        if np.isfinite(o) and o > 0:
            return float(o), k
    return NAN, -1


def last_close_row(con: np.ndarray, r: int, back: int | None = None) -> int:
    """Row of the contract's last close at or before r (within `back` rows if given); -1 if none."""
    lo = 0 if back is None else max(0, r - back)
    c = con[3, lo: r + 1]
    ok = np.flatnonzero(np.isfinite(c) & (c > 0))
    return int(ok[-1]) + lo if len(ok) else -1


# ------------------------------------------------------------------------------------------------ Black-Scholes (ours, R103)
def _ncdf(x: float) -> float:
    return 0.5 * math.erfc(-x / math.sqrt(2.0))


def bs(spot: float, k: float, t: float, vol: float, call: bool) -> float:
    if t <= 0 or vol <= 0:
        return max(spot - k, 0.0) if call else max(k - spot, 0.0)
    d1 = (math.log(spot / k) + (K.RATE + vol * vol / 2) * t) / (vol * math.sqrt(t))
    d2 = d1 - vol * math.sqrt(t)
    if call:
        return spot * _ncdf(d1) - k * math.exp(-K.RATE * t) * _ncdf(d2)
    return k * math.exp(-K.RATE * t) * _ncdf(-d2) - spot * _ncdf(-d1)


def implied_vol(p: float, spot: float, k: float, t: float, call: bool) -> float | None:
    """Bisection on [0.5%, 500%]; None when the price is at or below intrinsic (+ 1 tick) or inputs are missing."""
    intr = max(spot - k, 0.0) if call else max(k - spot, 0.0)
    if not (np.isfinite(p) and np.isfinite(spot)) or t <= 0 or p <= intr + K.TICK:
        return None
    lo, hi = 0.005, 5.0
    if bs(spot, k, t, hi, call) < p or bs(spot, k, t, lo, call) > p:
        return None                                   # no vol in [0.5%, 500%] fits (R103's brentq would fail too)
    for _ in range(80):
        mid = (lo + hi) / 2
        if bs(spot, k, t, mid, call) < p:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def years_left(d: date, expiry: date, r: int) -> float:
    """Calendar time from the start of minute r of day d to 15:30 (row 375) on the expiry day, in years."""
    return ((expiry - d).days * 1440 + (K.NROWS - r)) / (365.0 * 1440)


def reprice_stale(p_last: float, s_last: float, t_last: float, s_now: float, t_now: float, k: float, call: bool):
    """A stale leg's model price now: BS at the implied vol of its last print (R103 Amendment 2); None if no vol."""
    v = implied_vol(p_last, s_last, k, t_last, call)
    if v is None:
        return None
    return max(bs(s_now, k, t_now, v, call), K.TICK)


def idx_close_at(idx: np.ndarray, r: int) -> float:
    c = idx[3, : min(r, K.NROWS - 1) + 1]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else NAN


# ------------------------------------------------------------------------------------------------ mom60 signal
def ret60(c: np.ndarray) -> np.ndarray:
    """% return of close[i] vs close[i - 60] (the bar that started exactly 60 minutes earlier, same session); NaN when
    either is missing (Mom60.ret60At). Uses only rows <= i."""
    c = np.asarray(c, dtype=np.float64)
    out = np.full(c.shape, np.nan)
    a, b = c[60:], c[:-60]
    ok = np.isfinite(a) & np.isfinite(b) & (a > 0) & (b > 0)
    out[60:][ok] = (a[ok] / b[ok] - 1) * 100
    return out


def mom60_day(c: np.ndarray, thr: float, trade, first: int = K.FIRST_SIG_ROW, last: int = K.LAST_SIG_ROW) -> list:
    """The day's walk (Mom60.Watcher.process on one day). `trade(i, call)` prices a position fired on signal bar i and
    returns (filled, resolve_row) or (False, None); resolve_row is the bar whose close decided the exit. Returns the
    fired (i, call, result) list. Rules: closed bars in order; while a position is open, bars up to and including its
    resolve bar are not judged for entry; entries (signal rows) at least 60 apart; signal bar in [first, last]."""
    r = ret60(c)
    out = []
    last_entry = None
    busy_until = -1
    for i in range(first, min(last, len(c) - 1) + 1):
        if i <= busy_until:
            continue
        if last_entry is not None and i - last_entry < K.SPACING:
            continue
        x = r[i]
        if not np.isfinite(x) or abs(x) < thr:
            continue
        call = x > 0
        filled, res_row, info = trade(i, call)
        if not filled:
            continue                       # no print to buy: skipped, the spacing clock is not reset
        last_entry = i
        busy_until = res_row
        out.append((i, call, info))
    return out


def long_trade(con: np.ndarray, idx: np.ndarray, e: int, lot_: int, d: date, expiry: date | None, bse: bool,
               k: float, call: bool, hold: int = K.HOLD, stop_mult: float = K.STOP_MULT, slip_mult: float = 1.0):
    """One bought option (mom60 rules). Order for minute e (the minute after the signal bar). Returns None when there
    is no open in e..e+2, else a dict with the fill row f, the resolve row (decision bar), the exit row, prices, reason
    ('STOP' / 'TIME'), stale flag and net rupees."""
    E_, f = first_open(con, e, K.FILL_LOOK_ENTRY)
    if f < 0:
        return None
    real_e = slip(E_, True, vol5_lots(con, f, lot_), mult=slip_mult)
    stop_lvl = E_ * stop_mult
    t_end = f + hold                                       # the time exit sells at this minute's open
    reason, dec = "TIME", t_end - 1
    for m in range(f, min(t_end, K.NROWS)):
        cm = con[3, m]
        if np.isfinite(cm) and cm <= stop_lvl:
            reason, dec = "STOP", m
            break
    x_row = dec + 1
    stale = False
    if x_row <= K.NROWS - 1:
        X, xr = first_open(con, x_row, K.FILL_LOOK_EXIT)
    else:                                                  # due after 15:29: the 15:29 close
        lc = last_close_row(con, K.NROWS - 1, K.FILL_LOOK_EXIT)
        X, xr = (float(con[3, lc]), lc) if lc >= 0 else (NAN, -1)
        x_row = K.NROWS - 1
    if xr >= 0:
        real_x = slip(X, False, vol5_lots(con, xr, lot_), stop=(reason == "STOP"), mult=slip_mult)
    else:
        stale = True
        xr = min(x_row, K.NROWS - 1)
        X = NAN
        lc = last_close_row(con, xr)
        if lc >= 0 and expiry is not None:
            X = reprice_stale(float(con[3, lc]), idx_close_at(idx, lc), years_left(d, expiry, lc),
                              idx_close_at(idx, xr), years_left(d, expiry, xr), k, call)
            X = NAN if X is None else X
        if not np.isfinite(X):                             # cannot model it: the last close (counted)
            X = float(con[3, lc]) if lc >= 0 else E_
        real_x = slip(X, False, 0.0, stop=(reason == "STOP"), mult=slip_mult)
    net = (real_x - real_e) * lot_ - charges(real_e, lot_, True, d, bse) - charges(real_x, lot_, False, d, bse)
    return dict(f=f, dec=dec, xr=xr, raw_e=E_, raw_x=X, real_e=real_e, real_x=real_x, reason=reason, stale=stale,
                net=float(net), gross_pct=(X / E_ - 1) * 100, prem_rs=E_ * lot_)


def atm_at(D, und: str, r: int) -> float:
    """The chain's ATM label at minute r (offset 0), else the index close rounded to the step."""
    if D.atm is not None and 0 <= r < K.NROWS and np.isfinite(D.atm[r]):
        return float(D.atm[r])
    s = idx_close_at(D.idx, r)
    st = K.STEP[und]
    return float(math.floor(s / st + 0.5) * st) if np.isfinite(s) else NAN


def mom60_trade_at(D, und: str, d: date, expiry, sig: int, call: bool, slip_mult: float = 1.0):
    """Price a mom60 position fired on signal bar `sig` (strike off the chain's ATM at that minute)."""
    k = atm_at(D, und, sig)
    if not np.isfinite(k):
        return None
    con = D.con.get(("C" if call else "P", float(k)))
    if con is None:
        return None
    t = long_trade(con, D.idx, sig + 1, K.lot(und, d), d, expiry, und in K.BSE, k, call, slip_mult=slip_mult)
    if t is not None:
        t.update(k=k, sig=sig, call=call)
    return t


# ------------------------------------------------------------------------------------------------ strangle
def strangle_week(days: dict, monday: date, expiry: date | None, lot_: int, entry_row: int | None = None,
                  slip_mult: float = 1.0):
    """IraThinker's Monday strangle on real prints. days: {date: Day} of the MONTH series. entry_row: the sell
    order's minute (default 09:31, the minute after the 09:30 decision bar; the twin passes a random one, the spot then
    being the close of the minute before). Returns None when the week cannot be entered (holiday, missing bar or leg)."""
    if monday.weekday() != 0 or monday not in days or expiry is None:
        return None
    D = days[monday]
    e = K.STR_DECIDE + 1 if entry_row is None else entry_row
    spot = D.idx[3, e - 1]
    if not np.isfinite(spot) or spot <= 0:
        return None
    atm = math.floor(spot / K.STR_STRIKE_STEP + 0.5) * K.STR_STRIKE_STEP
    kc, kp = atm + K.STR_OFFSET, atm - K.STR_OFFSET
    thu = monday + timedelta(days=3)
    exp_week = expiry <= thu
    target = expiry if exp_week else thu
    sess = sorted(x for x in days if monday <= x <= target)
    exit_day = sess[-1]
    legs = []
    for side, k in (("C", kc), ("P", kp)):
        con = D.con.get((side, float(k)))
        if con is None:
            return None
        p, f = first_open(con, e, K.FILL_LOOK_ENTRY)
        if f < 0:
            return None
        legs.append(dict(side=side, k=k, raw=p, f=f, real=slip(p, False, vol5_lots(con, f, lot_), mult=slip_mult)))
    bse = False
    ch = sum(charges(L["real"], lot_, False, monday, bse) for L in legs)
    stale = 0
    settle = exp_week and exit_day == expiry
    X = days[exit_day]
    s_exit = idx_close_at(X.idx, K.NROWS - 1)
    for L in legs:
        call = L["side"] == "C"
        if settle:
            L["xraw"] = max(s_exit - L["k"], 0.0) if call else max(L["k"] - s_exit, 0.0)
            L["xreal"] = L["xraw"]
            continue
        con = X.con.get((L["side"], float(L["k"])))
        px, xr = (NAN, -1)
        if con is not None:
            o = con[0, K.STR_EXIT_ROW]
            if np.isfinite(o) and o > 0:
                px, xr = float(o), K.STR_EXIT_ROW
            else:
                lc = last_close_row(con, K.NROWS - 1, K.FILL_LOOK_EXIT)
                if lc >= 0:
                    px, xr = float(con[3, lc]), lc
        if xr >= 0:
            L["xraw"] = px
            L["xreal"] = slip(px, True, vol5_lots(con, xr, lot_), mult=slip_mult)
        else:
            stale += 1
            L["xraw"] = _stale_leg(days, sess, L, s_exit, exit_day, expiry, call)
            L["xreal"] = slip(L["xraw"], True, 0.0, mult=slip_mult)
        ch += charges(L["xreal"], lot_, True, exit_day, bse)
    credit_raw = sum(L["raw"] for L in legs)
    cost_raw = sum(L["xraw"] for L in legs)
    net = sum(L["real"] - L["xreal"] for L in legs) * lot_ - ch
    return dict(day=monday, exit_day=exit_day, exp_week=bool(exp_week), settle=bool(settle), spot=float(spot), atm=atm,
                credit=credit_raw, cost=cost_raw, net=float(net), charges=ch, stale=stale, lot=lot_,
                gross_pct=(credit_raw - cost_raw) / credit_raw * 100, net_pct=net / (credit_raw * lot_) * 100,
                margin=K.MARGIN_FRAC * spot * lot_, expiry=expiry, s_exit=s_exit)


def _stale_leg(days, sess, L, s_exit, exit_day, expiry, call) -> float:
    """A strangle leg with no print near the exit: BS at the implied vol of its last print in the hold."""
    for dd in reversed(sess):
        con = days[dd].con.get((L["side"], float(L["k"])))
        if con is None:
            continue
        lc = last_close_row(con, K.NROWS - 1)
        if lc < 0:
            continue
        p = reprice_stale(float(con[3, lc]), idx_close_at(days[dd].idx, lc), years_left(dd, expiry, lc), s_exit,
                          years_left(exit_day, expiry, K.STR_EXIT_ROW), L["k"], call)
        if p is not None:
            return p
        return float(con[3, lc])
    intr = max(s_exit - L["k"], 0.0) if call else max(L["k"] - s_exit, 0.0)
    return max(intr, K.TICK)


# ------------------------------------------------------------------------------------------------ IraThinker's BS tape
IRA_R = 0.065


def ira_ncdf(x: float) -> float:
    """OptionsMath.normCdf (Abramowitz-Stegun 26.2.17)."""
    t = 1 / (1 + 0.2316419 * abs(x))
    poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
    pdf = math.exp(-x * x / 2) / math.sqrt(2 * math.pi)
    cdf = 1 - pdf * poly
    return cdf if x >= 0 else 1 - cdf


def ira_bs(spot: float, k: float, days: float, iv_pct: float, call: bool) -> float:
    """OptionsMath.bsPriceDays: 0.35-day floor, 6.5% rate."""
    if spot <= 0 or k <= 0:
        return NAN
    t = max(days, 0.35) / 365.0
    sig = max(iv_pct, 1) / 100.0
    d1 = (math.log(spot / k) + (IRA_R + sig * sig / 2) * t) / (sig * math.sqrt(t))
    d2 = d1 - sig * math.sqrt(t)
    disc = math.exp(-IRA_R * t)
    return spot * ira_ncdf(d1) - k * disc * ira_ncdf(d2) if call else k * disc * ira_ncdf(-d2) - spot * ira_ncdf(-d1)


def _last_wd(y: int, m: int, wd: int) -> date:
    d = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
    while d.weekday() != wd:
        d -= timedelta(days=1)
    return d


def ira_monthly_expiry(today: date, bse: bool) -> date:
    """OptionsMath.monthlyExpiry (last Tuesday) / monthlyExpiryBse (last Thursday)."""
    wd = 3 if bse else 1
    e = _last_wd(today.year, today.month, wd)
    if today > e:
        n = date(today.year + (today.month == 12), today.month % 12 + 1, 1)
        e = _last_wd(n.year, n.month, wd)
    return e


def ira_days(d: date, expiry: date, minute_row: int) -> float:
    """Mom60.daysToExpiryFrac at the start of minute `minute_row` of day d (row 375 = 15:30)."""
    return max(0.0, ((expiry - d).days * 1440 + (K.NROWS - minute_row)) / 1440.0)


def mom60_bs(c: np.ndarray, sig: int, call: bool, step: float, d: date, iv: float, bse: bool):
    """IraThinker's paper-log grade of a signal fired on bar `sig`: BS entry at the bar's close, then each later bar's
    close repriced (frozen IV), stop at 70% checked first, time exit when the bar's close time reaches entry + 60
    minutes; a day that ends first force-closes on its last usable bar. Returns (pnl %, reason) or None."""
    if not (np.isfinite(iv) and iv > 0):
        return None
    s0 = c[sig]
    k = math.floor(s0 / step + 0.5) * step
    exp = ira_monthly_expiry(d, bse)
    e0 = ira_bs(s0, k, ira_days(d, exp, sig + 1), iv, call)
    if not (e0 > 0):
        return None
    stop = e0 * K.STOP_MULT
    last = None
    for j in range(sig + 1, len(c)):
        if not np.isfinite(c[j]) or c[j] <= 0:
            continue
        p = ira_bs(c[j], k, ira_days(d, exp, j + 1), iv, call)
        last = p
        if p <= stop:
            return (p / e0 - 1) * 100, "STOP"
        if j + 1 >= sig + 1 + K.HOLD:
            return (p / e0 - 1) * 100, "TIME"
    if last is None:
        return None
    return (last / e0 - 1) * 100, "CLOSE"


def strangle_bs(days: dict, monday: date, iv: float):
    """Strangle.buildEntry + grade on IraThinker's BS tape: BS at 09:31 with frozen IV, last-Tuesday monthly expiry,
    BS buy-back at the exit day's 15:30 (Thursday) or intrinsic at the expiry close; net = gross - 1.0 pp."""
    if monday not in days or not (np.isfinite(iv) and iv > 0):
        return None
    D = days[monday]
    spot = D.idx[3, K.STR_DECIDE]
    if not np.isfinite(spot):
        return None
    atm = math.floor(spot / 100 + 0.5) * 100
    kc, kp = atm + 200, atm - 200
    exp = ira_monthly_expiry(monday, False)
    ce = ira_bs(spot, kc, ira_days(monday, exp, K.STR_DECIDE + 1), iv, True)
    pe = ira_bs(spot, kp, ira_days(monday, exp, K.STR_DECIDE + 1), iv, False)
    credit = ce + pe
    if not (credit > 0):
        return None
    thu = monday + timedelta(days=3)
    exp_week = exp <= thu
    ex = exp if exp_week else thu
    sess = [x for x in sorted(days) if monday <= x <= ex]
    s = NAN
    for dd in reversed(sess):
        s = idx_close_at(days[dd].idx, K.NROWS - 1)
        if np.isfinite(s):
            break
    if not np.isfinite(s):
        return None
    if exp_week:
        cost = max(0.0, s - kc) + max(0.0, kp - s)
    else:
        dleft = (exp - ex).days
        cost = ira_bs(s, kc, dleft, iv, True) + ira_bs(s, kp, dleft, iv, False)
    g = (credit - cost) / credit * 100
    return dict(gross_pct=g, net_pct=g - 1.0, credit=credit, exp_week=exp_week)


# ------------------------------------------------------------------------------------------------ statistics
def twin_p(real: np.ndarray, pool: np.ndarray, draws: int, rng) -> float:
    """(1 + #{draws whose mean twin net >= the real mean net}) / (draws + 1); pool (n, POOL) of twin nets per trade
    (NaN where a twin could not be priced: that trade's other twins are drawn)."""
    real = np.asarray(real, float)
    n = len(real)
    if n == 0:
        return 1.0
    pool = np.asarray(pool, float)
    target = real.mean()
    # per-trade valid twins
    valid = [np.flatnonzero(np.isfinite(pool[i])) for i in range(n)]
    cnt = np.array([len(v) for v in valid])
    keep = cnt > 0
    if not keep.all():                                        # a trade with no twin keeps its own net (neutral)
        pool = pool.copy()
        for i in np.flatnonzero(~keep):
            pool[i, 0] = real[i]
            valid[i] = np.array([0])
        cnt = np.array([len(v) for v in valid])
    W = max(cnt)
    idx = np.zeros((n, W), dtype=np.int64)
    for i, v in enumerate(valid):
        idx[i, : len(v)] = v
    hits = 0
    done = 0
    chunk = max(1, min(draws, 4_000_000 // max(n, 1)))
    while done < draws:
        m = min(chunk, draws - done)
        u = (rng.random((m, n)) * cnt).astype(np.int64)
        cols = idx[np.arange(n), u]
        vals = pool[np.arange(n), cols]
        hits += int((vals.mean(axis=1) >= target - 1e-9).sum())
        done += m
    return (1 + hits) / (draws + 1)


def boot_p(nets: np.ndarray, rng, boot: int = K.BOOT) -> float:
    """One-sided bootstrap p of mean <= 0 (R103)."""
    nets = np.asarray(nets, float)
    if len(nets) < 2:
        return 1.0
    means = nets[rng.integers(0, len(nets), size=(boot, len(nets)))].mean(axis=1)
    return float((means <= 0).mean())


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the earlier tests as p = 1 (R108)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total or n, n)
    if n == 0:
        return p
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def max_dd(daily: np.ndarray) -> float:
    """Deepest fall of the cumulative net from its running peak (>= 0), starting from 0."""
    if len(daily) == 0:
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def period_stats(rows: list) -> dict:
    """rows: dicts with day (date), net, and optionally gross_pct, stale, reason. Stats with n."""
    n = len(rows)
    if n == 0:
        return dict(n=0, win=None, net=0.0, per_trade=None, worst_day=None, worst_week=None, worst_month=None,
                    max_dd=0.0, months_le_13k=0, gross_pct=None, stale=0, days=0)
    nets = np.array([r["net"] for r in rows])
    by_day, by_week, by_month = {}, {}, {}
    for r in rows:
        d = r["day"]
        by_day[d] = by_day.get(d, 0.0) + r["net"]
        wk = d - timedelta(days=d.weekday())
        by_week[wk] = by_week.get(wk, 0.0) + r["net"]
        by_month[(d.year, d.month)] = by_month.get((d.year, d.month), 0.0) + r["net"]
    dd = max_dd(np.array([by_day[k] for k in sorted(by_day)]))
    g = [r["gross_pct"] for r in rows if "gross_pct" in r and np.isfinite(r["gross_pct"])]
    wins = nets[nets > 0].sum()
    losses = -nets[nets < 0].sum()
    return dict(n=n, win=float((nets > 0).mean()), net=float(nets.sum()), per_trade=float(nets.mean()),
                worst_day=float(min(by_day.values())), worst_week=float(min(by_week.values())),
                worst_month=float(min(by_month.values())), max_dd=dd,
                months_le_13k=int(sum(v <= K.ACCOUNT_MONTH for v in by_month.values())),
                gross_pct=float(np.mean(g)) if g else None, pf=float(wins / losses) if losses > 0 else None,
                stale=int(sum(bool(r.get("stale")) for r in rows)), days=len(by_day),
                stops=int(sum(r.get("reason") == "STOP" for r in rows)))


def in_range(d: date, lo: date, hi: date) -> bool:
    return lo <= d <= hi


def nearest_on_or_after(sorted_dates: list, d: date):
    i = bisect.bisect_left(sorted_dates, d)
    return sorted_dates[i] if i < len(sorted_dates) else None
