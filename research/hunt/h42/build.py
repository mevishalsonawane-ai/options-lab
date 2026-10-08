"""h42 build: per index, one compact cache of
  (a) index minute matrices (o/h/l/c) + day flags,
  (b) per-minute option features (ATM / 1-ITM CE & PE closes, near-ATM OI & volume, max-OI strike),
  (c) the OPTION-BUYER PANEL: for every decision minute s (09:15..15:00 close) and side (CE, PE) the 1-ITM nearest-expiry
      contract bought at the OPEN of s+1, with first-passage minutes of +15/20/25/30 (option HIGH) and -10/15/20 (option LOW)
      within 60 minutes, their fill prices (target: max(level, bar open); stop: min(level, bar open)), time-exit prices
      at +15/+30/+60 minutes and at 15:10, and the app's Liquidity-arm exit (-15% resting stop, out after 20 min unless
      the premium is >= +5%, square-off 15:10).
Decision on the CLOSE of s (features use bars <= s); labels use bars >= s+1. Never past the 15:10 square-off.
python3 -I research/hunt/h42/build.py NIFTY BANKNIFTY ...   (under flock <scratch>/obuy.lock)
Output: <scratch>/hunt/h42/c_<U>.npz
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h42")
NS = 346                 # decisions s = 0..345 (09:15 .. 15:00 close); entry at s+1
SQ = 355                 # 15:10 column: square-off at its open
UPS, DNS = (15, 20, 25, 30), (10, 15, 20)
NEVER = 255
HW = 60


def ffill2(a):
    return pd.DataFrame(a).T.ffill().T.values


def first_hit(mask):
    return np.where(mask.any(1), mask.argmax(1), NEVER).astype(np.uint8)


def build(und):
    mk = D.market()
    ix, op = mk.index(und), mk.options(und)
    M = ix.mat()
    step = C.STEP[und]
    nd = len(ix.days)
    W = C.W
    res = dict(days=np.array([D.dnum(d) for d in ix.days], np.int32),
               exp=np.array([ix.d[d]["exp"] for d in ix.days], np.int8),
               real=np.array([ix.d[d]["real"] for d in ix.days], np.int8),
               has=np.zeros(nd, np.int8))
    for k in ("o", "h", "l", "c"):
        res["i" + k] = M[k].astype(np.float32)
    F = {k: np.full((nd, W), np.nan, np.float32) for k in ("atmC", "atmP", "itmC", "itmP", "oiC", "oiP", "vC", "vP", "maxK")}
    P = {"E": np.full((nd, NS, 2), np.nan, np.float32), "v5": np.full((nd, NS, 2), np.nan, np.float32)}
    for u in UPS:
        P[f"hu{u}"] = np.full((nd, NS, 2), NEVER, np.uint8)
        P[f"fu{u}"] = np.full((nd, NS, 2), np.nan, np.float32)
    for dd in DNS:
        P[f"hd{dd}"] = np.full((nd, NS, 2), NEVER, np.uint8)
        P[f"fd{dd}"] = np.full((nd, NS, 2), np.nan, np.float32)
    for t in ("t15", "t30", "t60", "tsq", "liq"):
        P[t] = np.full((nd, NS, 2), np.nan, np.float32)
    P["liqstop"] = np.zeros((nd, NS, 2), np.uint8)
    s = np.arange(NS)
    e = s + 1
    win = np.arange(SQ)                     # offsets from e
    cols = e[:, None] + win[None, :]
    inday = cols < SQ                       # bars e .. 354 (<= 15:09)
    cols_c = np.minimum(cols, W - 1)
    t0 = time.time()
    cur = None
    for di, d in enumerate(ix.days):
        if d.year != cur:
            if cur is not None:
                mk.release(und)
            cur = d.year
            print(und, d.year, f"{time.time() - t0:.0f}s", flush=True)
        c = M["c"][di]
        if np.isnan(c[:345]).mean() > 0.2:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        res["has"][di] = 1
        cff = pd.Series(c).ffill().bfill().values
        atm = np.rint(cff / step) * step
        K = ch.K
        nk = len(K)
        kidx = lambda strike: np.clip(np.searchsorted(K, strike), 0, nk - 1)  # noqa: E731
        okk = lambda strike, ki: K[ki] == strike  # noqa: E731
        Cc, Pc = ffill2(ch.c["C"]), ffill2(ch.c["P"])
        allm = np.arange(W)
        for name, arr, strike in (("atmC", Cc, atm), ("atmP", Pc, atm), ("itmC", Cc, atm - step), ("itmP", Pc, atm + step)):
            ki = kidx(strike)
            F[name][di] = np.where(okk(strike, ki), arr[ki, allm], np.nan)
        oiC, oiP = ffill2(ch.oi["C"]), ffill2(ch.oi["P"])
        near = np.abs(K[:, None] - atm[None, :]) <= 3 * step
        F["oiC"][di] = np.where(near, np.nan_to_num(oiC), 0).sum(0)
        F["oiP"][di] = np.where(near, np.nan_to_num(oiP), 0).sum(0)
        F["vC"][di] = np.where(near, np.nan_to_num(ch.v["C"]), 0).sum(0)
        F["vP"][di] = np.where(near, np.nan_to_num(ch.v["P"]), 0).sum(0)
        tot = np.nan_to_num(oiC) + np.nan_to_num(oiP)
        F["maxK"][di] = np.where(tot.max(0) > 0, K[tot.argmax(0)], np.nan)
        for si, (r, money_sign) in enumerate((("C", -1), ("P", +1))):
            strike = atm[s] + money_sign * step
            ki = kidx(strike)
            ok = okk(strike, ki)
            O, H, L, Cl = ch.o[r], ch.h[r], ch.l[r], (Cc if r == "C" else Pc)
            E = O[ki, e]
            ok &= np.isfinite(E) & (E > 1.0)
            if not ok.any():
                continue
            rw = np.nonzero(ok)[0]
            kr, er = ki[rw], E[rw].astype(np.float64)
            cc = cols_c[rw]
            m = inday[rw]
            Ow = np.where(m, O[kr[:, None], cc], np.nan)
            Hw = np.where(m, H[kr[:, None], cc], np.nan)
            Lw = np.where(m, L[kr[:, None], cc], np.nan)
            Cw = np.where(m, Cl[kr[:, None], cc], np.nan)
            vv = np.nan_to_num(ch.v[r][kr])
            cs = np.concatenate([np.zeros((len(rw), 1)), np.cumsum(vv, 1)], 1)
            sr = s[rw]
            P["v5"][di, rw, si] = cs[np.arange(len(rw)), sr + 1] - cs[np.arange(len(rw)), np.maximum(sr - 4, 0)]
            P["E"][di, rw, si] = er
            Hh = Hw[:, :HW]
            Ll = Lw[:, :HW]
            Oo = Ow[:, :HW]
            ar = np.arange(len(rw))
            for u in UPS:
                lvl = er + u
                h = first_hit(np.nan_to_num(Hh, nan=-1) >= lvl[:, None])
                P[f"hu{u}"][di, rw, si] = h
                hb = np.minimum(h, HW - 1)
                ob = Oo[ar, hb]
                P[f"fu{u}"][di, rw, si] = np.where(h < NEVER, np.where(np.isfinite(ob) & (ob > lvl), ob, lvl), np.nan)
            for dd in DNS:
                lvl = er - dd
                h = first_hit(np.nan_to_num(Ll, nan=1e9) <= lvl[:, None])
                P[f"hd{dd}"][di, rw, si] = h
                hb = np.minimum(h, HW - 1)
                ob = Oo[ar, hb]
                P[f"fd{dd}"][di, rw, si] = np.where(h < NEVER, np.where(np.isfinite(ob) & (ob < lvl), ob, lvl), np.nan)
            Cf = pd.DataFrame(Cw).ffill(axis=1).values
            Cf = np.where(np.isnan(Cf), er[:, None], Cf)

            def px_at(off):          # exit at the open of e+off (or the last close before it)
                col = np.minimum(e[rw] + off, SQ)
                oo = O[kr, np.minimum(col, W - 1)]
                prev = Cf[ar, np.clip(col - 1 - e[rw], 0, SQ - 1)]
                return np.where(np.isfinite(oo) & (col <= SQ), oo, prev)
            P["t15"][di, rw, si] = px_at(15)
            P["t30"][di, rw, si] = px_at(30)
            P["t60"][di, rw, si] = px_at(60)
            P["tsq"][di, rw, si] = px_at(10 ** 6)
            # Liquidity arm: resting -15% stop (floor tick), at close of e+19 out (next open) unless close >= 1.05 E
            stl = np.floor(er * 0.85 / C.TICK + 1e-9) * C.TICK
            hit = np.nan_to_num(Lw, nan=1e9) <= stl[:, None]
            hs = np.where(hit.any(1), hit.argmax(1), 10 ** 6)
            ts_off = 19
            cl19 = Cf[ar, np.minimum(ts_off, SQ - 1)]
            tstop = (cl19 < er * 1.05) & (e[rw] + ts_off < SQ)
            out_px = np.where(tstop, px_at(20), px_at(10 ** 6))
            out_when = np.where(tstop, ts_off + 1, SQ - e[rw])
            stop_first = hs < out_when
            ob = Ow[ar, np.minimum(hs, SQ - 1)]
            spx = np.where(np.isfinite(ob) & (ob < stl), ob, stl)
            P["liq"][di, rw, si] = np.where(stop_first, spx, out_px)
            P["liqstop"][di, rw, si] = stop_first.astype(np.uint8)
    res.update(F)
    res.update(P)
    os.makedirs(OUT, exist_ok=True)
    np.savez_compressed(os.path.join(OUT, f"c_{und}.npz"), **res)
    print(und, "done", int(res["has"].sum()), "days", f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    for u in sys.argv[1:]:
        build(u)
