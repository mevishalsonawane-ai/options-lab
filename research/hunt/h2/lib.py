"""h2: intraday LONG-ONLY cash (MIS) backtests on the 214 F&O stocks' Dhan minute candles (2024-10-07 .. 2026-10-05).

Arrays (stock, day, minute) from build_cache.py; row 0 = NIFTY index. Minute k = the bar 09:15+k (k = 0..374).
A signal decided on the CLOSE of bar k fills at the OPEN of bar k+1. Square-off decision 15:15 -> fill open of bar 360.

Fills / costs
  gross: fills at the bar prices exactly, no charges.
  net:   + half-spread slippage by liquidity tier (20-day median traded value, prior days only):
           >= Rs 1000 cr: 2 bps, 300-1000 cr: 3 bps, 100-300 cr: 5 bps, < 100 cr: 8 bps a side (never below half a
           Rs 0.05 tick); a resting stop that triggers pays 2x; a size-dependent impact term is added at final sizing.
         + intraday equity (MIS) charges, per order: brokerage min(Rs 20, 0.03%), STT 0.025% on the SELL value,
           NSE transaction 0.00297%, SEBI Rs 10/crore, stamp 0.003% on the BUY value, GST 18% on (brokerage + exchange +
           SEBI). (The app's Costs('app') model is for options; this is the same structure with cash-intraday rates.)
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
CACHE = os.path.join(SCR, "hunt", "h2", "cache")
HOLD = np.datetime64("2025-10-01")
SQ = 360  # 15:15 bar


class Mkt:
    def __init__(self):
        L = lambda k: np.load(os.path.join(CACHE, f"{k}.npy"), mmap_mode="r")  # noqa: E731
        self.o, self.h, self.l, self.c, self.v = (np.asarray(L(k)) for k in "ohlcv")
        self.days = np.load(os.path.join(CACHE, "days.npy"))
        self.names = open(os.path.join(CACHE, "names.txt")).read().split()
        S, D, _ = self.c.shape
        self.S, self.D = S, D
        # forward-filled close (for exits on missing bars)
        c = self.c.reshape(S * D, 375)
        idx = np.where(np.isfinite(c), np.arange(375)[None, :], 0)
        np.maximum.accumulate(idx, axis=1, out=idx)
        self.cf = np.take_along_axis(c, idx, axis=1).reshape(S, D, 375)
        last = self.cf[:, :, -1]
        self.pc = np.full((S, D), np.nan, np.float32)
        self.pc[:, 1:] = last[:, :-1]                        # previous close
        self.op = self.o[:, :, 0].copy()
        bad = ~np.isfinite(self.op)
        self.op[bad] = self.cf[:, :, 5][bad]
        self.gap = self.op / self.pc - 1
        val = np.nansum(self.c * self.v, axis=2)             # Rs traded per day
        val[:, :] = np.where(val > 0, val, np.nan)
        self.val = val
        adv = pd.DataFrame(val.T).rolling(20, min_periods=5).median().shift(1).to_numpy().T
        self.adv = adv                                       # prior 20-day median traded value
        bps = np.where(adv >= 1e10, 2, np.where(adv >= 3e9, 3, np.where(adv >= 1e9, 5, 8))).astype(np.float32)
        bps[~np.isfinite(adv)] = 8
        self.slip_bps = bps
        # vwap (cumulative)
        tp = (self.h + self.l + self.c) / 3
        pv = np.nan_to_num(tp * self.v)
        self.cumv = np.cumsum(self.v, axis=2, dtype=np.float32)
        self.vwap = np.cumsum(pv, axis=2, dtype=np.float64).astype(np.float32) / np.maximum(self.cumv, 1)
        self.valid = np.isfinite(self.op) & np.isfinite(self.pc) & np.isfinite(adv) & (np.abs(self.gap) < 0.2)
        self.valid[0] = False                                # NIFTY is not tradable here
        self.ism = self.days < HOLD

    def relvol(self, k):
        """volume of bars 0..k vs the mean of the same window over the previous 10 days."""
        w = self.cumv[:, :, k]
        base = pd.DataFrame(w.T).rolling(10, min_periods=5).mean().shift(1).to_numpy().T
        return w / np.where(base > 0, base, np.nan)

    def ret_open(self, k):
        return self.cf[:, :, k] / self.op - 1


def costs_rs(buy_val, sell_val):
    brk = np.minimum(20.0, 0.0003 * buy_val) + np.minimum(20.0, 0.0003 * sell_val)
    exch = 0.0000297 * (buy_val + sell_val)
    sebi = 1e-7 * (buy_val + sell_val)
    return brk + exch + sebi + 0.00025 * sell_val + 0.00003 * buy_val + 0.18 * (brk + exch + sebi)


def simulate(m: Mkt, s, d, te, stop=None, tgt=None, tx=None, trail=None, chunk=20000):
    """Long trades: buy at open of bar te. Exits (first wins; stop before target in a bar): resting stop (fill at stop or
    the open if gapped below), resting target (fill at target or a better open), time exit: open of bar tx (default
    SQ). trail: fraction below the running max high (from bars before the current one) - raises the stop.
    Returns DataFrame with entry, exit, reason (0 time, 1 stop, 2 target), exit bar."""
    n = len(s)
    s, d, te = (np.asarray(a, dtype=np.int64) for a in (s, d, te))
    stop = np.full(n, np.nan) if stop is None else np.asarray(stop, dtype=np.float64)
    tgt = np.full(n, np.nan) if tgt is None else np.asarray(tgt, dtype=np.float64)
    tx = np.full(n, SQ) if tx is None else np.minimum(np.asarray(tx, dtype=np.int64), SQ)
    ent = np.empty(n)
    ex = np.empty(n)
    why = np.zeros(n, np.int8)
    xb = np.empty(n, np.int64)
    cols = np.arange(375)[None, :]
    for a in range(0, n, chunk):
        b = min(n, a + chunk)
        ss, dd, tt, xx = s[a:b], d[a:b], te[a:b], tx[a:b]
        O, H, Lo, CF = m.o[ss, dd], m.h[ss, dd], m.l[ss, dd], m.cf[ss, dd]
        e = O[np.arange(b - a), tt]
        e = np.where(np.isfinite(e), e, CF[np.arange(b - a), np.maximum(tt - 1, 0)])
        ent[a:b] = e
        live = (cols >= tt[:, None]) & (cols < xx[:, None])
        st = np.broadcast_to(stop[a:b, None], H.shape).copy()
        if trail is not None:
            hm = np.where(live, np.nan_to_num(H, nan=-np.inf), -np.inf)
            hm = np.maximum.accumulate(hm, axis=1)
            prev = np.concatenate([np.full((b - a, 1), -np.inf), hm[:, :-1]], axis=1)
            prev = np.maximum(prev, e[:, None])
            tr = prev * (1 - (trail[a:b, None] if np.ndim(trail) else trail))
            st = np.fmax(st, tr)
        hit_s = live & (Lo <= st)
        hit_t = live & (H >= tgt[a:b, None])
        fs = np.where(hit_s.any(1), hit_s.argmax(1), 999)
        ft = np.where(hit_t.any(1), hit_t.argmax(1), 999)
        r = np.arange(b - a)
        out = CF[r, np.maximum(xx - 1, 0)]
        ox = O[r, np.minimum(xx, 374)]
        out = np.where(np.isfinite(ox), ox, out)
        reason = np.zeros(b - a, np.int8)
        bar = xx.copy()
        isS = (fs <= ft) & (fs < 999)
        isT = (ft < fs)
        fsb = np.minimum(fs, 374)
        ftb = np.minimum(ft, 374)
        so = O[r, fsb]
        sv = st[r, fsb]
        sfill = np.where(np.isfinite(so) & (so < sv), so, sv)
        to = O[r, ftb]
        tfill = np.where(np.isfinite(to) & (to > tgt[a:b]), to, tgt[a:b])
        out = np.where(isS, sfill, np.where(isT, tfill, out))
        reason[isS] = 1
        reason[isT] = 2
        bar = np.where(isS, fs, np.where(isT, ft, bar))
        ex[a:b] = out
        why[a:b] = reason
        xb[a:b] = bar
    return pd.DataFrame(dict(s=s, d=d, te=te, ent=ent, ex=ex, why=why, xb=xb))


def pnl(m: Mkt, tr: pd.DataFrame, notional=500_000.0, impact=False):
    """Adds gross / net Rs P&L for a fixed notional per trade."""
    qty = np.floor(notional / tr.ent.to_numpy())
    bps = m.slip_bps[tr.s.to_numpy(), tr.d.to_numpy()].astype(np.float64)
    if impact:
        # participation of the order in the stock's average 5-minute traded value that day-so-far / prior day
        adv = m.adv[tr.s.to_numpy(), tr.d.to_numpy()]
        part = notional / (adv / 75.0)                        # vs an average 5-minute slice of the day's value
        bps = bps + 10.0 * np.sqrt(np.clip(part, 0, None))
    half_tick = 0.025 / tr.ent.to_numpy() * 1e4
    bps = np.maximum(bps, half_tick)
    bi = tr.ent.to_numpy() * (1 + bps / 1e4)
    sm = np.where(tr.why.to_numpy() == 1, 2.0, 1.0)
    so = tr.ex.to_numpy() * (1 - sm * bps / 1e4)
    g = qty * (tr.ex.to_numpy() - tr.ent.to_numpy())
    n = qty * (so - bi) - costs_rs(qty * bi, qty * so)
    tr = tr.copy()
    tr["qty"] = qty
    tr["gross"] = g
    tr["net"] = n
    tr["notional"] = qty * tr.ent.to_numpy()
    return tr
