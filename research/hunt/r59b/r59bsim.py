"""R59b step 3: their Bollinger band on HELD contracts (A, option chart, NON-DEFAULT) and on the STOCK (B, default),
priced with R59's engine (r59sim: skeleton, fills, spread, charges, twins, BH).

  held_bars()   every contract's own 15-minute closes (last close in each bar where it has a row, any offset) and its
                Bollinger(20, 2) lower band over ITS OWN bars only (NaN until it has 20)
  stock_bars()  the stock's own 15-minute closes and bands
  signals_A()   ATM contract at bar t whose own close <= its own lower band and >= 5 (fresh last trade)
  signals_B()   stock close <= lower band -> ATM CE; >= upper band -> ATM PE
  price()       R59's fills and S25 stop, plus S25L (breakeven lock once a row's high reaches +20%), and the gross
                before costs (raw prices, no spread, bps or charges)
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

import r59bcfg as K
import r59cfg as K59
import r59sim as S

N = K59.N
NB = K59.NBARS
BAR = K59.BAR


# ------------------------------------------------------------------------------------------------ bars and bands
@dataclass
class HeldBars:
    start: np.ndarray       # per contract: slice [start, end) into the arrays below
    end: np.ndarray
    bar: np.ndarray         # global bar id (di * 25 + b)
    close: np.ndarray       # last close in the bar
    lastg: np.ndarray       # global minute of that last close
    lower: np.ndarray       # band over the contract's own 20 most recent bars up to and including this one
    upper: np.ndarray


def _band(c: np.ndarray):
    s = pd.Series(c)
    mid = s.rolling(K.BB_N).mean()
    sd = s.rolling(K.BB_N).std(ddof=K.BB_DDOF)
    return (mid - K.BB_K * sd).to_numpy(float), (mid + K.BB_K * sd).to_numpy(float)


def held_bars(bk) -> HeldBars:
    bar = bk.g // BAR
    ncon = len(bk.cstart)
    st, en = np.zeros(ncon, np.int64), np.zeros(ncon, np.int64)
    B, C, G, LO, UP = [], [], [], [], []
    pos = 0
    for cid in range(ncon):
        s, e = int(bk.cstart[cid]), int(bk.cend[cid])
        bb = bar[s:e]
        last = np.r_[bb[1:] != bb[:-1], True]            # last row of each bar (rows sorted by g)
        idx = np.flatnonzero(last) + s
        lo, up = _band(bk.c[idx])
        B.append(bar[idx]), C.append(bk.c[idx]), G.append(bk.g[idx]), LO.append(lo), UP.append(up)
        st[cid], en[cid] = pos, pos + len(idx)
        pos += len(idx)
    cat = (lambda x, t: np.concatenate(x).astype(t) if x else np.zeros(0, t))
    return HeldBars(st, en, cat(B, np.int64), cat(C, float), cat(G, np.int64), cat(LO, float), cat(UP, float))


def held_band_at(H: HeldBars, cid: int, gbar: int):
    """(close, lastg, lower) of contract cid at global bar gbar, or None if it has no row in that bar."""
    s, e = H.start[cid], H.end[cid]
    k = s + int(np.searchsorted(H.bar[s:e], gbar, side="left"))
    if k >= e or H.bar[k] != gbar:
        return None
    return float(H.close[k]), int(H.lastg[k]), float(H.lower[k])


@dataclass
class StockBars:
    close: np.ndarray       # [nd, 25] last stock close in the bar (NaN none)
    lastm: np.ndarray       # [nd, 25] its minute in the session (-1 none)
    lower: np.ndarray       # [nd, 25] band over the stock's 20 most recent bars with a close (NaN until 20)
    upper: np.ndarray


def stock_bars(bk) -> StockBars:
    nd = len(bk.days)
    close = np.full((nd, NB), np.nan)
    lastm = np.full((nd, NB), -1, np.int64)
    for b in range(NB):
        blk = bk.eqc[:, b * BAR:(b + 1) * BAR]
        ok = np.isfinite(blk)
        has = ok.any(axis=1)
        j = BAR - 1 - np.argmax(ok[:, ::-1], axis=1)
        close[has, b] = blk[np.flatnonzero(has), j[has]]
        lastm[has, b] = b * BAR + j[has]
    flat = close.ravel()
    have = np.flatnonzero(np.isfinite(flat))
    lo, up = _band(flat[have])
    lower = np.full(nd * NB, np.nan)
    upper = np.full(nd * NB, np.nan)
    lower[have], upper[have] = lo, up
    return StockBars(close, lastm, lower.reshape(nd, NB), upper.reshape(nd, NB))


# ------------------------------------------------------------------------------------------------ signals
def signals_A(bk, sd, H: HeldBars, days_ok=None):
    """(di, b, cid): the ATM contract of side sd at bar b closes at or under the lower band of ITS OWN last 20 bars
    and >= 5; its last trade in bar b is in the bar's last 5 minutes. Option-chart signal: NON-DEFAULT."""
    out = []
    for di in range(len(bk.days)):
        if bk.skip[di] or (days_ok is not None and not days_ok[di]):
            continue
        for b in range(1, NB - 1):
            cid = S.atm_at_bar(bk, di, b, sd)
            if cid < 0:
                continue
            x = held_band_at(H, cid, di * NB + b)
            if x is None:
                continue
            c, lg, lo = x
            if lg < di * N + b * BAR + BAR - K59.FRESH_OPT:
                continue
            if np.isfinite(lo) and c >= K.MIN_PREM and c <= lo:
                out.append((di, b, cid))
    return out


def signals_B(bk, sd, SB: StockBars, days_ok=None):
    """(di, b, cid): the stock's 15-minute close at or under its lower band (CE) / at or over its upper band (PE); the
    close must be in the bar's last 3 minutes; the ATM contract of that side at bar b. Signal on the stock (default)."""
    out = []
    for di in range(len(bk.days)):
        if bk.skip[di] or (days_ok is not None and not days_ok[di]):
            continue
        for b in range(1, NB - 1):
            c = SB.close[di, b]
            if not np.isfinite(c) or SB.lastm[di, b] < b * BAR + BAR - K59.FRESH_EQ:
                continue
            lo, up = SB.lower[di, b], SB.upper[di, b]
            if (sd == 0 and np.isfinite(lo) and c <= lo) or (sd == 1 and np.isfinite(up) and c >= up):
                cid = S.atm_at_bar(bk, di, b, sd)
                if cid >= 0:
                    out.append((di, b, cid))
    return out


# ------------------------------------------------------------------------------------------------ pricing
def price(bk, s: "S.Skel", variant: str, roll_h: float, k: float = 1.0) -> dict:
    """R59's price() for NS / S25, plus S25L, plus gross0 (raw prices x lot: no spread, bps or charges)."""
    fill = S.buy_fill(s.eopen, s.l5_in, roll_h, k)
    G0 = s.di * N
    reason, exit_g, raw_x = "time", G0 + s.X, s.xpx
    if s.rx < 0:
        reason = "fallback"
        xfill = S.sell_fill(s.xpx, 0.0, roll_h, k)
    else:
        xfill = S.sell_fill(s.xpx, s.l5_out, roll_h, k)
    if variant in ("S25", "S25L"):
        L = (1 - K59.STOP) * fill
        locked = False
        a, z = S.row_range(bk, s.cid, bk.g[s.r0], G0 + s.X - 1)
        for r in range(a, z):
            tag = "lock" if locked else "stop"
            if r > s.r0:
                op = S.open_for_sell(bk, s.cid, r)
                if op <= L:
                    xfill = S.sell_fill(op, S.lots5_before(bk, s.cid, r, s.lot), roll_h, k, stop=True)
                    reason, exit_g, raw_x = tag + "_gap", int(bk.g[r]), op
                    break
            if bk.l[r] <= L:
                xfill = S.sell_fill(L, S.lots5_before(bk, s.cid, r, s.lot), roll_h, k, stop=True)
                reason, exit_g, raw_x = tag, int(bk.g[r]), L
                break
            if variant == "S25L" and not locked and bk.h[r] >= (1 + K.LOCK_AT) * fill:
                locked, L = True, fill                    # breakeven from the next row on
    d = int(bk.days[s.di])
    qty = s.lot
    ch = S.charges(True, fill, qty, d, k) + S.charges(False, xfill, qty, d, k)
    gross = (xfill - fill) * qty
    return dict(fill=fill, xfill=xfill, gross=gross, charges=ch, net=gross - ch, reason=reason, exit_g=exit_g,
                entry_g=int(bk.g[s.r0]), capital=fill * qty, lot=qty, gross0=(raw_x - s.eopen) * qty)


def trades_for(bk, sigs, variant, roll_h):
    """Priced, non-overlapping trades (one position per stock and side), 1x and 2x costs and gross before costs."""
    sk, pr = [], []
    for di, b, cid in sigs:
        s = S.skeleton(bk, di, b, int(bk.cside[cid]), cid)
        if s is None:
            continue
        sk.append(s)
        pr.append(price(bk, s, variant, roll_h, 1.0))
    keep = S.no_overlap(sk, pr)
    out = []
    for s, p, kp in zip(sk, pr, keep):
        if not kp:
            continue
        p2 = price(bk, s, variant, roll_h, 2.0)
        out.append(dict(sym=bk.sym, side="CE" if s.sd == 0 else "PE", date=date.fromordinal(int(bk.days[s.di])).isoformat(),
                        di=s.di, bar=s.b, strike=float(bk.cstrike[s.cid]),
                        expiry=date.fromordinal(int(bk.cexp[s.cid])).isoformat(), lot=p["lot"], fill=p["fill"],
                        xfill=p["xfill"], net=round(p["net"], 2), net2=round(p2["net"], 2),
                        gross0=round(p["gross0"], 2), cost=round(p["gross0"] - p["net"], 2),
                        charges=round(p["charges"], 2), capital=round(p["capital"], 0), reason=p["reason"]))
    return out


def metrics(tr, twins=None, sessions=None):
    out = S.metrics(tr, twins)
    if not tr:
        return out
    g = np.array([t["gross0"] for t in tr])
    c = np.array([t["cost"] for t in tr])
    out["gross_per_trade"] = round(float(g.mean()), 0)
    out["gross_t"] = round(float(g.mean() / (g.std(ddof=1) / np.sqrt(len(g)))), 2) if len(g) > 1 and g.std(ddof=1) > 0 else None
    out["cost_median"] = round(float(np.median(c)), 0)
    out["locks"] = int(sum(t["reason"].startswith("lock") for t in tr))
    out["stops"] = int(sum(t["reason"].startswith("stop") for t in tr))
    if sessions:
        out["trades_per_session"] = round(len(tr) / sessions, 2)
    return out
