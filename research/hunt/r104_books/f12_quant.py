"""R104 family F12: pairs, mean reversion, seasonals and crypto from the quant books (PREREG.md; mechanics from
research/books/TESTABLE_RULES.md). Committed before this family's first run.

Single-leg rules use the common executor. Two-leg rules (pairs) use `pair_trades` below: daily (or hourly) closes,
positions change at the NEXT bar's open, each leg Rs 10,00,000 of notional (the hedge ratio scales leg B), costs per leg
(futures charges and half-spreads; crypto fees), returns from the legs' own prices; index legs are spot proxies (the
carry of a long and a short index future nearly cancels and is ignored, labelled).
Readings fixed here:
- Engle-Granger CADF: the ADF t-stat of the OLS residual (no lag terms) against -3.34 (5%, two variables, MacKinnon).
- OU half-life = -ln 2 / slope of the regression of the spread change on the lagged spread.
- Chan's entry band |z| >= 2 (the rule's text names +2 / -2).
- fmbayes-01: the book's 20% ex-ante volatility target on Rs 10,00,000 of capital, monthly; one month = one trade.
- Ross 03: "price reaches s*" is a target at s*; ADF on the 250-close window at -2.87 (5%, constant).
- Crypto rules always carry their own stop and profit lock (fmchan-08 / 09 as the book's research variant states).
- smith-06: daily bars from 00:00 UTC; funding is charged as 0.03% of notional a day held (labelled estimate).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r104cfg as K
import r104data as DA
import r104ind as I
import r104stats as ST
from f3_patterns import _daily_bars
from r104pat import NAN, Pat, gid
from r104run import IMPL, rule

NOTIONAL = 1_000_000.0


# ================================================================================================ statistics
def ols(y, x):
    x = np.asarray(x, float)
    y = np.asarray(y, float)
    xm, ym = x.mean(), y.mean()
    sxx = ((x - xm) ** 2).sum()
    b = ((x - xm) * (y - ym)).sum() / sxx if sxx > 0 else 0.0
    return ym - b * xm, b


def adf_t(e):
    """t-stat of rho in d(e) = c + rho * e(-1) (no lags)."""
    e = np.asarray(e, float)
    de = np.diff(e)
    lag = e[:-1]
    c, rho = ols(de, lag)
    res = de - (c + rho * lag)
    s2 = (res ** 2).sum() / max(len(de) - 2, 1)
    se = np.sqrt(s2 / max(((lag - lag.mean()) ** 2).sum(), 1e-18))
    return rho / se if se > 0 else 0.0


def half_life(e):
    e = np.asarray(e, float)
    c, rho = ols(np.diff(e), e[:-1] - e[:-1].mean())
    return -np.log(2) / rho if rho < 0 else np.inf


# ================================================================================================ two-leg simulator
def leg_cost(kind, sym, price, notional, d=None):
    if kind == "IDX":
        hs = K.index_half_spread(price) / price
        return notional * hs * 2 + K.index_fut_charges(notional, notional, d or K.D(2020, 1, 1))
    if kind == "MCX":
        C94 = K.mcx_costs()
        hs = C94.fut_half_spread(sym, price) / price
        return notional * hs * 2 + C94.fut_leg_charges(notional, notional) * 1.0
    if kind == "CRY":
        return notional * (2 * K.CRY_HALF) + K.crypto_charges(notional)
    raise ValueError(kind)


def pair_trades(dates, oA, cA, oB, cB, sig, exit_flag, hedge, kind, symA, symB, stop_flag=None, max_hold=None,
                lock=None, cap=None):
    """sig[i] in {+1 (long A / short B), -1, 0}: decided at close i, entered at open i+1. exit_flag[i]: exit at the
    open of i+1. hedge[i]: leg B notional multiplier. lock: (arm, keep) as fractions of gross notional (crypto)."""
    n = len(cA)
    out = []
    pos = None
    for i in range(n - 1):
        if pos is not None:
            side, ei, eA, eB, h = pos
            nb = NOTIONAL * h
            mtm = side * (NOTIONAL * (cA[i] / eA - 1) - nb * (cB[i] / eB - 1))
            pos_peak = max(pos_peak, mtm)
            out_now = bool(exit_flag[i]) or (stop_flag is not None and stop_flag[i])
            if max_hold is not None and i - ei + 1 >= max_hold:
                out_now = True
            if cap is not None and mtm <= -cap * (NOTIONAL + nb):
                out_now = True                  # the loss cap, checked on closes, out at the next open
            if lock is not None:
                arm, keep = lock
                if pos_peak >= arm * (NOTIONAL + nb) and mtm <= keep * pos_peak:
                    out_now = True
            rev = sig[i] != 0 and sig[i] != side
            if out_now or rev:
                xA, xB = oA[i + 1], oB[i + 1]
                g = side * (NOTIONAL * (xA / eA - 1) - nb * (xB / eB - 1))
                cost = leg_cost(kind, symA, eA, NOTIONAL, dates[ei]) + leg_cost(kind, symB, eB, nb, dates[ei])
                out.append(dict(day=dates[ei], exit_day=dates[i + 1], side=side, gross=g, cost=cost, net=g - cost))
                pos = None
        if pos is None and sig[i] != 0:
            pos = (int(sig[i]), i + 1, oA[i + 1], oB[i + 1], float(hedge[i]))
            pos_peak = -np.inf
    return out


def _pair_frame(trades, market):
    t = pd.DataFrame(trades)
    if len(t) == 0:
        return pd.DataFrame(columns=["day", "net", "fold"])
    t["fold"] = [K.fold_of(market, d) for d in t.day]
    return t


def custom(rid, market, variants, fn):
    """Register a rule whose fn(part, **var) returns a positions frame directly (no executor)."""
    IMPL[(rid, market)] = dict(fn=None, custom=fn, variants=variants, insts=(), money=0, _family=__name__)


# ------------------------------------------------------------------------------------------------ loaders
def daily_pair(a_name, b_name, market):
    if market == "DLY":
        A, B = DA.index_daily(a_name), DA.index_daily(b_name)
        kind = "IDX"
    else:
        big = {"GOLD": "GOLD", "SILVER": "SILVER", "CRUDE": "CRUDEOIL", "NATURALGAS": "NATURALGAS"}
        A, B = DA.back_adjusted(DA.fut_daily(big[a_name])), DA.back_adjusted(DA.fut_daily(big[b_name]))
        kind = "MCX"
    j = A[["open", "close"]].join(B[["open", "close"]], lsuffix="A", rsuffix="B", how="inner").dropna()
    j = j[(j.closeA > 0) & (j.closeB > 0)]
    return j, kind


def in_part(market, d, part):
    return K.period_of(market, d) == part


# ================================================================================================ fmaqf-03
def hurst_rs(x):
    x = np.asarray(x, float)
    n = len(x)
    sizes = [int(s) for s in np.unique(np.logspace(np.log10(16), np.log10(n // 2), 10).astype(int))]
    rs = []
    for s in sizes:
        k = n // s
        vals = []
        for i in range(k):
            seg = x[i * s:(i + 1) * s]
            y = np.cumsum(seg - seg.mean())
            r = y.max() - y.min()
            sd = seg.std()
            if sd > 0:
                vals.append(r / sd)
        if vals:
            rs.append(np.mean(vals))
    return float(np.polyfit(np.log(sizes[:len(rs)]), np.log(rs), 1)[0])


def aqf03(market, part, M, pair):
    j, kind = daily_pair(*pair, market)
    X = np.log(j.closeA.values) - np.log(j.closeB.values)
    dates = list(j.index)
    # the Hurst gate on the design period only (fixed once, so the holdout trades whatever design decided)
    des = np.array([in_part(market, d, "design") for d in dates])
    dx = np.diff(X[des])
    h = hurst_rs(dx)
    rng = np.random.default_rng(K.SEED)
    sims = [hurst_rs(rng.normal(size=len(dx))) for _ in range(200)]
    if not h < np.percentile(sims, 5):
        return pd.DataFrame(columns=["day", "net", "fold"]), dict(hurst=h, gate=False)
    sig = np.zeros(len(X), int)
    for i in range(M, len(X)):
        if X[i] < X[i - M:i].min():
            sig[i] = 1
        elif X[i] > X[i - M:i].max():
            sig[i] = -1
    keep = np.array([in_part(market, d, part) for d in dates])
    sig[~keep] = 0
    tr = pair_trades(dates, j.openA.values, j.closeA.values, j.openB.values, j.closeB.values, sig,
                     np.zeros(len(X), bool), np.ones(len(X)), kind, *pair)
    return _pair_frame(tr, market), dict(hurst=h, gate=True)


for _m, _pair in (("DLY", ("BANKNIFTY", "NIFTY")), ("MCXD", ("GOLD", "SILVER"))):
    custom("book-fmaqf-03", _m, {f"M{M}": dict(M=M, pair=_pair) for M in (50, 100, 150)},
           (lambda mk: (lambda part, M, pair: aqf03(mk, part, M, pair)[0]))(_m))


# ================================================================================================ Chan pairs
def chan_pair(market, part, pair, hl_lo, hl_hi, window=252):
    j, kind = daily_pair(*pair, market)
    la, lb = np.log(j.closeA.values), np.log(j.closeB.values)
    dates = list(j.index)
    n = len(la)
    sig = np.zeros(n, int)
    ex = np.zeros(n, bool)
    stopf = np.zeros(n, bool)
    hedge = np.ones(n)
    pos, entry_i, fail, hl_cur = 0, -1, 0, np.inf
    keep = np.array([in_part(market, d, part) for d in dates])
    for i in range(window, n):
        a_, h_ = ols(la[i - window + 1:i + 1], lb[i - window + 1:i + 1])
        spr = la[i - window + 1:i + 1] - h_ * lb[i - window + 1:i + 1]
        t = adf_t(spr)
        hl = half_life(spr)
        z = (spr[-1] - spr.mean()) / spr.std()
        ok = t < -3.34 and hl_lo <= hl <= hl_hi
        fail = 0 if ok else fail + 1
        hedge[i] = abs(h_)                       # leg B notional = h x leg A notional
        if pos != 0:
            if (pos > 0 and z >= 0) or (pos < 0 and z <= 0) or i - entry_i >= 2 * hl_cur or fail >= 5:
                ex[i] = True
                pos = 0
            elif abs(z) >= 3.5:
                stopf[i] = True
                pos = 0
            continue
        if ok and keep[i] and abs(z) >= 2:
            sig[i] = -1 if z > 0 else 1           # spread rich: short A, long B
            pos, entry_i, hl_cur = sig[i], i, hl
    tr = pair_trades(dates, j.openA.values, j.closeA.values, j.openB.values, j.closeB.values, sig, ex, hedge, kind,
                     *pair, stop_flag=stopf, cap=0.02)
    return _pair_frame(tr, market)


custom("book-fmchan-02", "DLY", {"base": dict()}, lambda part: chan_pair("DLY", part, ("BANKNIFTY", "NIFTY"), 3, 30))
custom("book-fmchan-03", "MCXD", {"base": dict()}, lambda part: chan_pair("MCXD", part, ("GOLD", "SILVER"), 5, 40))


# ================================================================================================ fmbayes-01
def bayes01(part):
    syms = {"GOLD": "GOLD", "SILVER": "SILVER", "CRUDE": "CRUDEOIL", "NATURALGAS": "NATURALGAS"}
    minis = {"GOLD": "GOLDM", "SILVER": "SILVERM", "CRUDE": "CRUDEOILM", "NATURALGAS": "NATGASMINI"}
    fr = {k: DA.back_adjusted(DA.fut_daily(v)) for k, v in syms.items()}
    closes = pd.DataFrame({k: f.close for k, f in fr.items()}).dropna()
    rets = np.log(closes).diff()
    months = sorted({(d.year, d.month) for d in closes.index})
    out = []
    C94 = K.mcx_costs()
    for (y, m) in months:
        days = [d for d in closes.index if (d.year, d.month) == (y, m)]
        if not days:
            continue
        d0 = days[0]
        if not in_part("MCXD", d0, part):
            continue
        i0 = closes.index.get_loc(d0)
        if i0 < 192:
            continue
        nxt = [d for d in closes.index[i0:] if (d.year, d.month) != (y, m)]
        d1 = nxt[0] if nxt else closes.index[-1]
        i1 = closes.index.get_loc(d1)
        win = rets.iloc[i0 - 190:i0]
        vol = win.std() * np.sqrt(252)
        z = (closes.iloc[i0 - 1] / closes.iloc[i0 - 190] - 1) / vol
        order = z.sort_values(ascending=False).index
        longs, shorts = list(order[:2]), list(order[2:])
        w = {s: (1 / vol[s]) for s in longs + shorts}
        tot = sum(w.values())
        # scale the book to a 20% ex-ante volatility (diagonal approximation) on Rs 10 lakh
        wv = {s: w[s] / tot for s in w}
        port_vol = np.sqrt(sum((wv[s] * vol[s]) ** 2 for s in wv))
        scale = 0.20 / port_vol if port_vol > 0 else 0
        g = 0.0
        cost = 0.0
        for s in wv:
            sd = 1 if s in longs else -1
            notional = NOTIONAL * wv[s] * scale
            r = closes[s].iloc[i1] / closes[s].iloc[i0] - 1
            g += sd * notional * r
            px = closes[s].iloc[i0]
            cost += notional * 2 * C94.fut_half_spread(minis[s], px) / px + C94.fut_leg_charges(notional, notional)
        out.append(dict(day=d0, exit_day=d1, side=0, gross=g, cost=cost, net=g - cost))
    return _pair_frame(out, "MCXD")


custom("book-fmbayes-01", "MCXD", {"base": dict()}, bayes01)


# ================================================================================================ Chan 04 (NG season)
@rule("book-fmchan-04", "MCXD", {"base": dict()}, insts=("NATURALGAS",))
def chan04(ctx):
    P = Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    days = ctx.s.days
    by_year = {}
    for k, d in enumerate(days):
        by_year.setdefault(d.year, []).append((d, k))
    for y, lst in by_year.items():
        ent = [k for d, k in lst if (d.month, d.day) >= (2, 25)]
        ex = [k for d, k in lst if (d.month, d.day) <= (4, 15)]
        if not ent or not ex:
            continue
        k_in = ent[0]            # the close of 25 Feb (or the next session): entered at the next open
        k_out = ex[-1]
        if k_out <= k_in + 1:
            continue
        a = P.atr[k_in]
        out.append(P.mkt(k_in, 1, b.c[k_in] - 2.5 * a, tmax=k_out + 1))
    return out, None, None


# ================================================================================================ Ross 01 / 03
def _ross01(ctx):
    P = Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    lr = np.r_[np.nan, np.diff(np.log(b.c))]
    a20 = I.atr(b.h, b.l, b.c, 20)
    for k in range(21, P.n - 1):
        if lr[k] < -0.01:
            out.append(P.mkt(k, -1, b.c[k] + a20[k], tmax=-2))   # out at that session's close
    return out, None, None


@rule("book-fmross-01", "MCXD", {"base": dict()}, insts=("CRUDE", "NATURALGAS", "GOLD", "SILVER"))
def ross01(ctx):
    return _ross01(ctx)


def _ross03(ctx):
    P = Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    L = np.log(np.maximum(b.c, 1e-9))
    a20 = I.atr(b.h, b.l, b.c, 20)
    for k in range(251, P.n - 1):
        y, x = L[k - 249:k + 1], L[k - 250:k]
        a, bb = ols(y, x)
        res = y - (a + bb * x)
        s = res.std()
        if not bb < 0.98 or adf_t(L[k - 250:k + 1]) >= -2.87:
            continue
        exp_next = np.exp(a + bb * L[k])
        sstar = np.exp((a + s * s / 2) / (1 - bb))
        if exp_next - b.c[k] > np.exp(L[k] + s) - b.c[k] > 0 and sstar > b.c[k]:
            out.append(P.mkt(k, 1, b.c[k] - 1.5 * a20[k], sstar, tmax=P.after_fill(10)))
        elif b.c[k] - exp_next > b.c[k] - np.exp(L[k] - s) > 0 and sstar < b.c[k]:
            out.append(P.mkt(k, -1, b.c[k] + 1.5 * a20[k], sstar, tmax=P.after_fill(10)))
    return out, None, None


@rule("book-fmross-03", "MCXD", {"base": dict()}, insts=("CRUDE", "NATURALGAS", "GOLD", "SILVER"))
def ross03_m(ctx):
    return _ross03(ctx)


@rule("book-fmross-03", "DLY", {"base": dict()})
def ross03_d(ctx):
    return _ross03(ctx)


# ================================================================================================ crypto
def utc_daily_bars(ctx):
    """Daily bars from 00:00 UTC (05:30 IST) on the IST-day minute series."""
    s = ctx.s
    ist_day = np.array([s.days[i].toordinal() for i in s.ses])
    utc_day = ist_day - (s.tod < 330)
    chg = np.r_[True, utc_day[1:] != utc_day[:-1]]
    i0 = np.flatnonzero(chg)
    i1 = np.r_[i0[1:] - 1, s.n - 1]
    ses = np.arange(len(i0))
    return DA.Bars(1440, s.o[i0], np.maximum.reduceat(s.h, i0), np.minimum.reduceat(s.l, i0), s.c[i1],
                   np.add.reduceat(s.v, i0), i0, i1, ses, s.tod[i1], np.ones(len(i0), bool))


@rule("book-smith-06", "CRY", {"pct": dict(buf="pct", _tmode=1, _multiday=True),
                               "atr": dict(buf="atr", _tmode=1, _multiday=True)})
def smith06(ctx, buf):
    from f5_swing import _smith_trail_arrays, _sst_setups
    b = utc_daily_bars(ctx)
    P = Pat(ctx, 1440, bars=b)
    setups, sm200 = _sst_setups(P, b)
    out = []
    for k, side, ref in setups:
        a = P.atr[k]
        if a != a or k + 1 >= P.n:
            continue
        bf = b.c[k] * 0.005 if buf == "pct" else 0.25 * a
        lvl = b.h[ref] if side > 0 else b.l[ref]
        stop = b.l[ref] - bf if side > 0 else b.h[ref] + bf
        j = min(k + 3, P.n - 1)
        out.append((P.start(k), side, 1, float(lvl), int(b.i1[j]), float(stop), NAN, P.after_fill(20 * 1440), NAN,
                    gid("sst"), 1.0, float(lvl)))
    tr = _smith_trail_arrays(ctx, P) if buf == "pct" else _smith_trail_arrays(ctx, P, buf_atr=0.25, big_atr=2.5)
    return out, tr, None


@rule("book-fmchan-09", "CRY", {"base": dict(_tab=True, _tmode=3)})
def chan09(ctx):
    s = ctx.s
    P = Pat(ctx, 5)
    b, out = P.b, []
    m = I.sma(b.c, 20)
    sd = I.rolling_std(b.c, 20)
    for k in range(21, P.n - 1):
        t = P.dec_tod(k)
        if not (0 <= t < 9 * 60) or sd[k] != sd[k] or not P.same_ses(k, k + 1):
            continue
        e = b.c[k]
        tm = int(s.s0[s.ses[b.i1[k]]] + np.searchsorted(s.tod[s.s0[s.ses[b.i1[k]]]:s.s1[s.ses[b.i1[k]]] + 1], 9 * 60))
        if e > m[k] + 2 * sd[k]:
            out.append(P.mkt(k, -1, e * 1.006, be=0.5, tmax=tm))
        elif e < m[k] - 2 * sd[k]:
            out.append(P.mkt(k, 1, e * 0.994, be=0.5, tmax=tm))
    inside = np.abs(b.c - m) <= sd
    lock = np.full(s.n, 0.5)
    return out, {1: lock, -1: lock}, {1: ctx.to_rows(b, inside, bool), -1: ctx.to_rows(b, inside, bool)}


def chan08(part):
    A = DA.crypto_series("ETHUSDT")
    B = DA.crypto_series("BTCUSDT")
    def hourly(s):
        key = s.ses * 100 + s.tod // 60
        chg = np.r_[True, key[1:] != key[:-1]]
        i0 = np.flatnonzero(chg)
        i1 = np.r_[i0[1:] - 1, s.n - 1]
        days = [s.days[s.ses[i]] for i in i0]
        hrs = s.tod[i0] // 60
        return pd.DataFrame(dict(o=s.o[i0], c=s.c[i1]), index=pd.MultiIndex.from_arrays([days, hrs]))
    j = hourly(A).join(hourly(B), lsuffix="A", rsuffix="B", how="inner").dropna()
    la, lb = np.log(j.cA.values), np.log(j.cB.values)
    days = [x[0] for x in j.index]
    hrs = np.array([x[1] for x in j.index])
    n = len(la)
    sig = np.zeros(n, int)
    ex = np.zeros(n, bool)
    stopf = np.zeros(n, bool)
    hedge = np.ones(n)
    pos, ei, hl_cur = 0, -1, np.inf
    W = 720
    for i in range(W, n - 1):
        win_a, win_b = la[i - W + 1:i + 1], lb[i - W + 1:i + 1]
        a_, h_ = ols(win_a, win_b)
        spr = win_a - h_ * win_b
        z = (spr[-1] - spr.mean()) / spr.std()
        hedge[i] = abs(h_)
        in_win = hrs[i] < 9
        if pos != 0:
            if (pos > 0 and z >= 0) or (pos < 0 and z <= 0) or (i - ei) >= 2 * hl_cur or hrs[i + 1] >= 9 or not in_win:
                ex[i] = True
                pos = 0
            elif abs(z) >= 3.5:
                stopf[i] = True
                pos = 0
            continue
        if not in_win or hrs[i + 1] >= 9 or not in_part("CRY", days[i], part):
            continue
        if abs(z) < 2:
            continue
        t = adf_t(spr)
        hl = half_life(spr)
        if t < -3.34 and 2 <= hl <= 12:
            sig[i] = -1 if z > 0 else 1
            pos, ei, hl_cur = sig[i], i, hl
    tr = pair_trades(days, j.oA.values, j.cA.values, j.oB.values, j.cB.values, sig, ex, hedge, "CRY", "ETH", "BTC",
                     stop_flag=stopf, lock=(0.004, 0.5), cap=0.01)
    return _pair_frame(tr, "CRY")


custom("book-fmchan-08", "CRY", {"base": dict()}, chan08)


# ================================================================================================ fabozzi-03 (IDX pair)
def fabozzi03(part):
    """NIFTY vs BANKNIFTY 5-minute log-spread z: buy the cheap side's call and the rich side's put (1 lot each), exit
    at |z| < 0.5, |z| > 3.5, a combined loss of half the premium, or 15:10."""
    import r104sim as SIM
    sN, sB = DA.idx_series("NIFTY"), DA.idx_series("BANKNIFTY")
    bN, bB = DA.bars(sN, 5), DA.bars(sB, 5)
    fN = pd.DataFrame(dict(c=bN.c, i1=bN.i1, ses=bN.ses, tod=bN.tod1), index=pd.MultiIndex.from_arrays(
        [[sN.days[x] for x in bN.ses], bN.tod1]))
    fB = pd.DataFrame(dict(c=bB.c, i1=bB.i1, ses=bB.ses, tod=bB.tod1), index=pd.MultiIndex.from_arrays(
        [[sB.days[x] for x in bB.ses], bB.tod1]))
    j = fN.join(fB, lsuffix="N", rsuffix="B", how="inner")
    days = np.array([x[0] for x in j.index])
    tods = np.array([x[1] for x in j.index]) + 1
    lN, lB = np.log(j.cN.values), np.log(j.cB.values)
    # beta: rolling 60-session regression of daily log closes (past sessions only)
    dN = DA.index_daily("NIFTY").close
    dB = DA.index_daily("BANKNIFTY").close
    dj = pd.concat([np.log(dN), np.log(dB)], axis=1, keys=["N", "B"]).dropna()
    beta = {}
    dl = list(dj.index)
    for i in range(61, len(dl)):
        w = dj.iloc[i - 60:i]
        beta[dl[i]] = ols(w.B.values, w.N.values)[1]
    spread = np.array([lB[i] - beta.get(days[i], np.nan) * lN[i] for i in range(len(days))])
    W = 20 * 75
    zs = np.full(len(spread), np.nan)
    for i in range(W, len(spread)):
        w = spread[i - W:i]
        if np.isfinite(w).all():
            zs[i] = (spread[i] - w.mean()) / w.std()
    out = []
    SN, SB = DA.store("NIFTY"), DA.store("BANKNIFTY")
    i = W
    while i < len(zs) - 1:
        d, t = days[i], tods[i]
        if not in_part("IDX", d, part) or zs[i] != zs[i] or abs(zs[i]) <= 2 or not (9 * 60 + 30 <= t <= 14 * 60 + 30):
            i += 1
            continue
        DN, DB = SN.get(d), SB.get(d)
        if DN is None or DB is None or DN.expiry or DB.expiry:
            i += 1
            continue
        rich_b = zs[i] > 0
        e_row = t - K.NSE_OPEN
        # exit row: the first later 5-minute close with |z| < 0.5 or > 3.5 (same day), else the square-off
        x = i + 1
        while x < len(zs) and days[x] == d and not (abs(zs[x]) < 0.5 or abs(zs[x]) > 3.5):
            x += 1
        x_row = (tods[x] - K.NSE_OPEN) if (x < len(zs) and days[x] == d) else 10 ** 6
        legs = [("NIFTY", DN, 1 if rich_b else -1, sN, j.i1N.values[i]), ("BANKNIFTY", DB, -1 if rich_b else 1, sB,
                                                                          j.i1B.values[i])]
        res = []
        for und, Dd, side, ss, i1 in legs:
            pr = SIM.price_option(Dd, und, d, side, e_row, x_row, float(ss.c[i1]), 0, K.lot(und, d), Dd.expiry)
            res.append(pr)
        if all(r is not None for r in res):
            g = sum(r["gross"] for r in res)
            c = sum(r["cost"] for r in res)
            # (the book's combined stop at half the premium is not modelled; the z exits close every trade the same day)
            net = g - c
            out.append(dict(day=d, exit_day=d, side=1 if rich_b else -1, gross=g, cost=c, net=net))
        # one position at a time: continue after the exit
        i = max(x, i + 1)
    return _pair_frame(out, "IDX")


custom("book-fmfabozzi-03", "IDX", {"base": dict()}, fabozzi03)
