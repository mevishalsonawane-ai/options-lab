"""R192 pure pieces (no data files): calendars, the ideas' signals, Black-76, the one walker that prices a held option
(real minutes or synthetic prices), statistics, pooled twins, BH and the gate. Every function is tested in
test_r192.py. Plain-English notes, 17 Nov 2026.

Index day arrays are (375, 4) O H L C minutes (row r = 09:15 + r minutes), NaN where there is no minute.
A decision at row t reads only rows < t; daily histories passed to a signal end with the previous session.
"""
from __future__ import annotations

import bisect
import math
from collections import defaultdict
from datetime import date, timedelta

import numpy as np
from scipy.special import ndtr

import r192cfg as K
from research.obuy.costs import Costs, adverse_bps

O, H, L, C = 0, 1, 2, 3
DATED = Costs("dated")


# ================================================================================================ day helpers
def blank_after(a: np.ndarray, t: int) -> np.ndarray:
    b = np.array(a, dtype=float, copy=True)
    b[t:] = np.nan
    return b


def last_close(a: np.ndarray, t: int, back: int = 10) -> float:
    """The last finite close in rows t-back .. t-1: the price known when the row-t order goes."""
    seg = a[max(0, t - back):t, C]
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[-1]]) if ok.size else np.nan


def day_open(a: np.ndarray, t: int = 5) -> float:
    """The first finite open in rows 0 .. min(4, t-1) (the 09:15 open, or the first minute in the first 5)."""
    seg = a[:min(5, t), O]
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[0]]) if ok.size else np.nan


def day_hilo(a: np.ndarray, t: int) -> tuple:
    h, lo = a[:t, H], a[:t, L]
    if not np.isfinite(h).any() or not np.isfinite(lo).any():
        return np.nan, np.nan
    return float(np.nanmax(h)), float(np.nanmin(lo))


def close_loc(px: float, hi: float, lo: float) -> float:
    if not (np.isfinite(px) and np.isfinite(hi) and np.isfinite(lo)) or hi <= lo:
        return np.nan
    return (px - lo) / (hi - lo)


# ================================================================================================ calendars
def holidays_between(sessions: list) -> set:
    """Weekdays with no session between the first and the last session (exchange holidays, or missing data days)."""
    have = set(sessions)
    out, d = set(), sessions[0]
    while d <= sessions[-1]:
        if d.weekday() < 5 and d not in have:
            out.add(d)
        d += timedelta(days=1)
    return out


def next_weekday(d: date) -> date:
    n = d + timedelta(days=1)
    while n.weekday() >= 5:
        n += timedelta(days=1)
    return n


def is_last_of_month(sessions: list, i: int) -> bool:
    """The session is the last of its calendar month (needs the next session, which the exchange calendar gives)."""
    if i + 1 >= len(sessions):
        return False
    a, b = sessions[i], sessions[i + 1]
    return (a.year, a.month) != (b.year, b.month)


def is_pre_holiday(sessions: list, j: int, hol: set) -> bool:
    """Session j's next weekday is a holiday (inside the known calendar)."""
    if j + 1 >= len(sessions):
        return False
    return next_weekday(sessions[j]) in hol


def syn_expiries(sessions: list) -> list:
    """The synthetic expiry sessions: the monthly expiry (last Thursday of the month) before the weekly era, and every
    Thursday from K.WEEKLY_FROM; a Thursday that is not a session moves to the latest session before it."""
    have = sorted(sessions)
    hs = set(have)
    out = set()
    y, m = have[0].year, have[0].month
    last = have[-1]
    while (y, m) <= (last.year, last.month):
        nd = date(y + (m == 12), m % 12 + 1, 1)
        d = nd - timedelta(days=1)
        while d.weekday() != 3:
            d -= timedelta(days=1)
        out.add(d)
        y, m = nd.year, nd.month
    d = K.WEEKLY_FROM
    while d <= last:
        out.add(d)
        d += timedelta(days=7)
    res = set()
    for t in out:
        e = t
        while e not in hs and e >= have[0]:
            e -= timedelta(days=1)
        if e >= have[0]:
            res.add(e)
    return sorted(res)


def monthly_set(expiries: list) -> set:
    """The monthly expiries: the last expiry of each calendar month."""
    last = {}
    for e in sorted(expiries):
        last[(e.year, e.month)] = e
    return set(last.values())


def expiry_for(d: date, expiries: list, monthly: set | None = None,
               monthly_only_before: date = date(2019, 2, 11)) -> date | None:
    """The nearest expiry on or after d. With `monthly`, days before the weekly launch only see monthly expiries."""
    i = bisect.bisect_left(expiries, d)
    while i < len(expiries):
        e = expiries[i]
        if monthly is None or d >= monthly_only_before or e in monthly:
            return e
        i += 1
    return None


# ================================================================================================ signals
def sig_k4_base(move: float, loc: float) -> int:
    if not (np.isfinite(move) and np.isfinite(loc)):
        return 0
    if move > 0 and loc >= K.LOC_HI:
        return 1
    if move < 0 and loc <= K.LOC_LO:
        return -1
    return 0


def sma_prev(closes_before, n: int = K.SMA_DAYS) -> float:
    c = np.asarray(closes_before, float)
    c = c[np.isfinite(c)]
    return float(c[-n:].mean()) if len(c) >= n else np.nan


def vix_pct_high(vix_before, pctl: float = K.K4_F3_PCTL, n: int = K.K4_F3_DAYS) -> bool:
    """The previous VIX close is above the pctl percentile of the n closes ending with it. Unknown = not high."""
    v = np.asarray(vix_before, float)
    v = v[np.isfinite(v)]
    if len(v) < n:
        return False
    w = v[-n:]
    return bool(w[-1] > np.percentile(w, pctl))


def k4_allows(f: str, side: int, ctx: dict) -> bool:
    """K4's filter menu. ctx: move, loc, px, sma200, vix_high, gap_night (next session is not the next day)."""
    if side == 0:
        return False
    if f == "F0":
        return True
    if f == "F1":
        return not ctx["gap_night"]
    if f == "F2":
        s = ctx["sma200"]
        return bool(np.isfinite(s) and ((side > 0 and ctx["px"] > s) or (side < 0 and ctx["px"] < s)))
    if f == "F3":
        return not ctx["vix_high"]
    if f == "F4":
        return bool(np.isfinite(ctx["move"]) and abs(ctx["move"]) < K.BIG_MOVE)
    if f == "F5":
        lc = ctx["loc"]
        return bool(np.isfinite(lc) and ((side > 0 and lc >= K.K4_F5[0]) or (side < 0 and lc <= K.K4_F5[1])))
    raise ValueError(f)


def gap_of(a: np.ndarray, prev_close: float, t: int) -> float:
    o = day_open(a, t)
    if not (np.isfinite(o) and np.isfinite(prev_close) and prev_close > 0):
        return np.nan
    return o / prev_close - 1.0


def sig_k5(a: np.ndarray, prev_close: float) -> int:
    """Gap fade at 09:20: 0.3% <= |gap| <= 1.0% and the index is still on the gap side of the previous close."""
    b = blank_after(a, K.R0920)
    g = gap_of(b, prev_close, K.R0920)
    if not np.isfinite(g) or not (K.GAP_FADE[0] <= abs(g) <= K.GAP_FADE[1]):
        return 0
    px = last_close(b, K.R0920)
    if not np.isfinite(px):
        return 0
    if g > 0 and px > prev_close:
        return -1
    if g < 0 and px < prev_close:
        return 1
    return 0


def sig_k6(a: np.ndarray, prev_close: float) -> int:
    """Gap follow at 09:30: |gap| >= 0.5% and the index is beyond the day's open in the gap's direction."""
    b = blank_after(a, K.R0930)
    g = gap_of(b, prev_close, K.R0930)
    if not np.isfinite(g) or abs(g) < K.GAP_GO:
        return 0
    px, o = last_close(b, K.R0930), day_open(b, K.R0930)
    if not (np.isfinite(px) and np.isfinite(o)):
        return 0
    if g > 0 and px > o:
        return 1
    if g < 0 and px < o:
        return -1
    return 0


def sig_k7(vix_before, closes_before) -> int:
    """VIX close(d-1) >= 1.25 x the mean of the 10 closes before it, on a down day for NIFTY (d-1 < d-2)."""
    v = np.asarray(vix_before, float)
    c = np.asarray(closes_before, float)
    if len(v) < K.VIX_BACK + 1 or len(c) < 2:
        return 0
    w = v[-(K.VIX_BACK + 1):]
    if not np.isfinite(w).all() or not np.isfinite(c[-2:]).all():
        return 0
    return 1 if (w[-1] >= K.VIX_SPIKE * w[:-1].mean() and c[-1] < c[-2]) else 0


def sig_k8(px: float, closes_before) -> int:
    c = np.asarray(closes_before, float)
    c = c[np.isfinite(c)]
    if len(c) < K.HI_DAYS or not np.isfinite(px):
        return 0
    w = c[-K.HI_DAYS:]
    return 1 if px > w.max() else (-1 if px < w.min() else 0)


def rv_series(closes: np.ndarray, n: int = K.RV_DAYS) -> np.ndarray:
    """rv[j] = std of the n daily log returns ending with close j (NaN until n returns exist)."""
    c = np.asarray(closes, float)
    r = np.diff(np.log(c))
    out = np.full(len(c), np.nan)
    for j in range(n, len(c)):
        w = r[j - n:j]
        if np.isfinite(w).all():
            out[j] = float(w.std(ddof=1))
    return out


def sig_k9(px: float, closes_before) -> int:
    c = np.asarray(closes_before, float)
    if len(c) < K.RV_MED_DAYS + K.RV_DAYS + 1 or not np.isfinite(px):
        return 0
    rv = rv_series(c[-(K.RV_MED_DAYS + K.RV_DAYS + 1):])
    hist = rv[-K.RV_MED_DAYS:]
    if not np.isfinite(hist).all():
        return 0
    if rv[-1] > np.median(hist):
        return 0
    s = sma_prev(c)
    if not np.isfinite(s):
        return 0
    return 1 if px > s else (-1 if px < s else 0)


def sig_big(move: float, reverse: bool) -> int:
    if not np.isfinite(move) or abs(move) < K.BIG_MOVE:
        return 0
    s = 1 if move > 0 else -1
    return -s if reverse else s


# ================================================================================================ holds
SPECS = {   # timing -> (entry row, exit session offset, exit row, stop fraction, has target)
    "ON": (K.R1520, 1, K.R0916, None, False),
    "CC1": (K.R1520, 1, K.R1520, None, False),
    "CC2": (K.R1520, 2, K.R1520, None, False),
    "D2": (K.R0920, 1, K.R1520, None, False),
    "G5": (K.R0920, 0, K.R1520, K.STOP_FRAC, True),
    "G6": (K.R0930, 0, K.R1520, K.STOP_FRAC, False),
}


def hold_window(timing: str, exp_flags: list) -> tuple | None:
    """(entry row, exit offset, exit row) after the expiry cut, or None (no trade). exp_flags[k]: the held series
    expires on session k of the hold (k = 0 is the entry session)."""
    er, off, xr = SPECS[timing][:3]
    if len(exp_flags) < off + 1:
        return None
    for k in range(off + 1):
        if exp_flags[k]:
            if k == 0 and er >= K.R1505:
                return None
            if k < off or xr > K.R1505:
                return er, k, K.R1505
            break
    return er, off, xr


def strike_1itm(atm: float, side: int) -> float:
    return float(atm - K.STEP) if side > 0 else float(atm + K.STEP)


def atm_of(px: float) -> float:
    return float(round(px / K.STEP) * K.STEP)


# ================================================================================================ fills, charges
def ticks(v_lots: float) -> float:
    return 1.0 if v_lots >= 20.0 else (2.0 if v_lots > 0 else 4.0)


def buy_fill(p: float, v_lots: float, mult: float = 1.0) -> float:
    return round(float(adverse_bps(np.array([p]), int(round(5 * mult)), True)[0]) + mult * ticks(v_lots) * K.TICK, 2)


def sell_fill(p: float, v_lots: float, mult: float = 1.0) -> float:
    return max(round(float(adverse_bps(np.array([p]), int(round(5 * mult)), False)[0]) - mult * ticks(v_lots) * K.TICK, 2), 0.0)


def _dn(d: date) -> int:
    return (d - date(1970, 1, 1)).days


def charges(ef: float, xf: float, q: int, d_in: date, d_out: date) -> float:
    return float(DATED.charge(True, np.array([ef]), np.array([q]), np.array([_dn(d_in)]))[0]
                 + DATED.charge(False, np.array([xf]), np.array([q]), np.array([_dn(d_out)]))[0])


def lot(d: date) -> int:
    out = 0
    for s, n in K.QLOTS:
        if d >= date.fromisoformat(s):
            out = n
    return out


# ================================================================================================ the walker
def walk(o: np.ndarray, c: np.ndarray, v: np.ndarray, ixc: np.ndarray, entry_pos: int, exit_pos: int, qty: int,
         d_of_pos, stop_frac=None, target=None, side: int = 1) -> dict | None:
    """Price one held option along a path of minutes (sessions concatenated, 375 rows each).
    o, c, v: the option's open, close, volume (units); ixc: the index close (for the target); positions are
    k * 375 + row. Entry: the open at entry_pos or the first print up to 2 minutes later (same session), else None.
    Each minute from the entry to exit_pos - 1: the stop (option close <= stop_frac x entry fill) or the target (index
    close at or beyond the level, in the side's favour) triggers a sale at the next minute's open. Sale: the open at the
    exit minute or the next print up to 10 minutes later in that session, else the last print before it.
    d_of_pos(pos) -> the session date (charges)."""
    n = len(o)
    sess_end = lambda p: (p // K.N + 1) * K.N  # noqa: E731
    eref = next((p for p in range(entry_pos, min(entry_pos + K.FILL_SLACK, sess_end(entry_pos), n))
                 if np.isfinite(o[p]) and o[p] > 0), None)
    if eref is None or eref >= exit_pos:
        return None
    ve = float(np.nansum(v[max(eref - 5, (eref // K.N) * K.N):eref])) / qty
    ef = buy_fill(float(o[eref]), ve)
    xr, why = exit_pos, "time"
    for p in range(eref, exit_pos):
        if stop_frac is not None and np.isfinite(c[p]) and c[p] <= stop_frac * ef:
            xr, why = p + 1, "stop"
            break
        if target is not None and np.isfinite(ixc[p]) and ((side > 0 and ixc[p] >= target) or (side < 0 and ixc[p] <= target)):
            xr, why = p + 1, "target"
            break
    xref = None
    if xr < n:
        xref = next((p for p in range(xr, min(xr + K.EXIT_SLACK, sess_end(xr), n)) if np.isfinite(o[p]) and o[p] > 0), None)
    if xref is not None:
        x, how, xp = float(o[xref]), "open", xref
    else:
        seg = np.flatnonzero(np.isfinite(c[eref:min(xr, n)]))
        if not seg.size:
            return None
        xp = eref + int(seg[-1])
        x, how = float(c[xp]), "last_print"
    vx = float(np.nansum(v[max(xp - 5, (xp // K.N) * K.N):xp])) / qty
    xf = sell_fill(x, vx)
    d_in, d_out = d_of_pos(eref), d_of_pos(xp)
    net = (xf - ef) * qty - charges(ef, xf, qty, d_in, d_out)
    ef2, xf2 = buy_fill(float(o[eref]), ve, 2.0), sell_fill(x, vx, 2.0)
    stress = (xf2 - ef2) * qty - charges(ef2, xf2, qty, d_in, d_out)
    return dict(net=float(net), stress=float(stress), e_raw=float(o[eref]), ef=float(ef), x_raw=float(x), qty=int(qty),
                exit_how=how, why=why, prem=float(ef * qty), epos=int(eref), xpos=int(xp))


# ================================================================================================ Black-76
def b76(S, Kx, T, sig, cp: int, r: float = K.RATE):
    """Black-76 on the forward F = S e^(rT), discounted. cp: +1 call, -1 put. Vectorised over S / T."""
    S, T = np.asarray(S, float), np.maximum(np.asarray(T, float), 1.0 / (365 * 1440))
    F = S * np.exp(r * T)
    sq = sig * np.sqrt(T)
    with np.errstate(all="ignore"):
        d1 = (np.log(F / Kx) + 0.5 * sq * sq) / sq
    d2 = d1 - sq
    df = np.exp(-r * T)
    if cp > 0:
        return df * (F * ndtr(d1) - Kx * ndtr(d2))
    return df * (Kx * ndtr(-d2) - F * ndtr(-d1))


def t_years(d: date, rows, expiry: date) -> np.ndarray:
    """Calendar time from session d at the start of minute row(s) `rows` to the expiry's 15:30, in years (>= 1 min)."""
    now = _dn(d) * 1440 + K.OPEN_MIN + np.asarray(rows, float)
    end = _dn(expiry) * 1440 + 15 * 60 + 30
    return np.maximum(end - now, 1.0) / (365.0 * 1440.0)


YEAR_TRADING_MIN, YEAR_OTHER_MIN = 248 * 375.0, 525600.0 - 248 * 375.0


def t_trading(d: date, rows, expiry: date, n_between: int, w: float) -> np.ndarray:
    """Amendment 1: trading-time clock. (trading minutes left + w x other minutes left) / (93,000 + w x 432,600) years.
    n_between = sessions strictly between d and the expiry session (the expiry session counts as a full 375 minutes)."""
    r = np.asarray(rows, float)
    if d >= expiry:
        tr = np.maximum(K.N - r, 0.0)
    else:
        tr = np.maximum(K.N - r, 0.0) + K.N * (max(n_between, 0) + 1)
    total = np.maximum(_dn(expiry) * 1440 + 15 * 60 + 30 - (_dn(d) * 1440 + K.OPEN_MIN + r), 0.0)
    other = np.maximum(total - tr, 0.0)
    return np.maximum(tr + w * other, 1.0) / (YEAR_TRADING_MIN + w * YEAR_OTHER_MIN)


def clock_T(d: date, rows, expiry: date, clock) -> np.ndarray:
    """clock: None = the calendar clock; (n_between, w) = the trading-time clock."""
    if clock is None:
        return t_years(d, rows, expiry)
    return t_trading(d, rows, expiry, clock[0], clock[1])


def syn_price(S, Kx, d: date, rows, expiry: date, sig: float, cp: int, minute_end: bool = False, clock=None) -> np.ndarray:
    """The synthetic premium at a minute's open (or its close with minute_end), rounded to a tick, at least a tick."""
    r = np.atleast_1d(np.asarray(rows, float)) + (1.0 if minute_end else 0.0)
    p = b76(np.atleast_1d(np.asarray(S, float)), Kx, clock_T(d, r, expiry, clock), sig, cp)
    return np.where(np.isfinite(p), np.maximum(np.round(p / K.TICK) * K.TICK, K.TICK), np.nan)


# ================================================================================================ statistics
def max_drawdown(daily) -> float:
    eq, peak, dd = 0.0, 0.0, 0.0
    for x in daily:
        eq += x
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    return dd


def stats(trades: list, key: str = "net") -> dict:
    tr = [t for t in trades if t.get(key) is not None and np.isfinite(t[key])]
    n = len(tr)
    if not n:
        return dict(trades=0, win=0.0, net=0.0, avg_win=0.0, avg_loss=0.0, net_wo_top5=0.0, worst_day=0.0,
                    worst_month=0.0, max_dd=0.0, per_trade=0.0, months=0, months_up=0)
    nets = np.array([t[key] for t in tr], float)
    days, months = defaultdict(float), defaultdict(float)
    for t in tr:
        days[t["day"]] += t[key]
        months[f"{t['day'].year}-{t['day'].month:02d}"] += t[key]
    dser = [days[d] for d in sorted(days)]
    wins, losses = nets[nets > 0], nets[nets <= 0]
    return dict(trades=n, win=float(len(wins) / n * 100), net=float(nets.sum()), per_trade=float(nets.mean()),
                avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
                net_wo_top5=float(nets.sum() - np.sort(nets)[::-1][:5].sum()), worst_day=float(min(dser)),
                worst_month=float(min(months.values())), max_dd=float(max_drawdown(dser)), months=len(months),
                months_up=int(sum(1 for v in months.values() if v > 0)))


def norm_tail(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def pooled_twins(pools: dict, counts: dict, rng, n_twins: int = K.TWINS) -> np.ndarray:
    """Twin totals summed over eras. pools[era] = (call nets, put nets) of the era's eligible days; counts[era] = the
    rule's trade count there. Each twin draws that many days without replacement per era, each with a random side."""
    tot = np.zeros(n_twins)
    for e, n in counts.items():
        pc, pp = pools.get(e, (np.zeros(0), np.zeros(0)))
        m = len(pc)
        if n <= 0 or m == 0:
            continue
        k = min(n, m)
        for i in range(n_twins):
            idx = rng.choice(m, k, replace=False)
            side = rng.random(k) < 0.5
            tot[i] += float(np.where(side, pc[idx], pp[idx]).sum())
    return tot


def twin_p(rule_net: float, twins) -> tuple:
    tw = np.asarray(twins, float)
    if not len(tw):
        return 1.0, 1.0
    k = int((tw >= rule_net - 1e-9).sum())
    pe = (1 + k) / (len(tw) + 1)
    sd = float(tw.std(ddof=1)) if len(tw) > 1 else 0.0
    pn = (1.0 if rule_net <= float(tw.mean()) else 0.0) if sd <= 0 else norm_tail((rule_net - float(tw.mean())) / sd)
    return pe, pn


def bh_total(p, m_total: int = K.M_TOTAL) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def gate(eras: dict, need: tuple, p_emp: float | None, q: float) -> list:
    """PREREG 7. eras: era -> stats (None = no data). need: the eras this rule must pass. Returns the failed items."""
    fails = []
    for e in need:
        s = eras.get(e)
        if s is None or s["trades"] == 0:
            fails.append(f"era {e}: no trades")
            continue
        if s["trades"] < K.MIN_TRADES:
            fails.append(f"era {e}: {s['trades']} trades < {K.MIN_TRADES}")
        if not s["net"] > 0:
            fails.append(f"era {e}: net {s['net']:,.0f} <= 0")
        if e in K.REAL_ERAS and not s["net_wo_top5"] > 0:
            fails.append(f"era {e}: net without the top 5 trades {s['net_wo_top5']:,.0f} <= 0")
    if p_emp is None:
        fails.append("pooled twin p: untestable")
    elif not p_emp < K.GATE_P:
        fails.append(f"pooled twin p {p_emp:.3f} >= {K.GATE_P}")
    if not q <= K.GATE_Q:
        fails.append(f"BH q {q:.3g} > {K.GATE_Q}")
    return fails


def account_breach(s: dict) -> list:
    out = []
    if s and s.get("max_dd", 0) > K.ACC_DD:
        out.append(f"max drawdown Rs {s['max_dd']:,.0f} > Rs 40k")
    if s and s.get("worst_month", 0) < K.MONTH_CAP:
        out.append(f"worst month Rs {s['worst_month']:,.0f} < -Rs 13k")
    return out
