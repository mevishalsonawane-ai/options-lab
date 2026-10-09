"""R11 move anatomy - data panels. One npz per index: every minute (375 cols, 09:15..15:29) of every day, all the
features we can read from our history (no order book exists for NSE history; see HUNT_R11_MOVE_ANATOMY.md).

    python3 -I research/hunt/r11/build.py [BANKNIFTY NIFTY]   -> <scratch>/hunt/r11/panel_<U>.npz

Arrays [ndays, 375] float32 (NaN = not known):
  o h l c            index 1-minute OHLC (c forward-filled inside the day)
  optv               nearest-expiry option volume, CE+PE, ATM+-10 (units)  - the VWAP / volume-profile weight (r9/r10 proxy)
  flow               tick-rule signed CE volume minus signed PE volume, ATM+-5 (r9 FLOW; the app's flow.py idea)
  cev2 pev2          CE / PE volume ATM+-2
  cedoi2 pedoi2      CE / PE OI change this minute, ATM+-2 (per strike, OI forward-filled, then summed)
  oi2 oi10           CE+PE open interest ATM+-2 and ATM+-10 (gamma concentration near spot = oi2/oi10, h18)
  ceret peret        log return of the ATM CE / ATM PE premium (strike fixed = ATM of the previous minute)
  iv                 ATM implied vol (mean of CE and PE at the ATM strike, %)
  basis              option-implied forward (K + C - P at ATM) minus index (points; h29's leading forward)
  vix                India VIX minute close
  futv futoi futc    near-month index future volume / OI / close (only 2026-07-29 .. 2026-10-06: h38)
  hwv hwr            heavyweight cash rupee volume (sum) and weight-averaged 1-min log return (Oct 2024 on)
days (int, days since 1970), exp (bool: that index's expiry day)
"""
from __future__ import annotations

import glob
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
from obuy import config as C  # noqa: E402
from obuy import data as D  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "r11")
W = C.W
# approximate index weights (2025-26 factsheets), used only to average the heavyweights' minute returns
HEAVY = {
    "BANKNIFTY": {"HDFCBANK": 0.28, "ICICIBANK": 0.25, "SBIN": 0.09, "KOTAKBANK": 0.09, "AXISBANK": 0.08},
    "NIFTY": {"HDFCBANK": 0.13, "ICICIBANK": 0.09, "RELIANCE": 0.09, "INFY": 0.05, "BHARTIARTL": 0.045, "LT": 0.04,
              "ITC": 0.035, "TCS": 0.03},
}


def ffill_rows(a):
    return pd.DataFrame(a.T).ffill().values.T


def tick_sign(c):
    s = np.zeros_like(c)
    last = np.zeros(c.shape[0])
    prev = np.full(c.shape[0], np.nan)
    for t in range(c.shape[1]):
        x = c[:, t]
        ok = np.isfinite(x)
        d = np.where(ok & np.isfinite(prev), np.sign(x - prev), 0.0)
        last = np.where(d != 0, d, last)
        s[:, t] = np.where(ok, last, 0.0)
        prev = np.where(ok, x, prev)
    return s


def day_minutes(df, col):
    """df with ts (tz-aware or naive IST) -> {date: W-array of col}"""
    ts = df.ts
    if getattr(ts.dt, "tz", None) is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    m = (ts.dt.hour * 60 + ts.dt.minute).values - C.OPEN_M
    ok = (m >= 0) & (m < W)
    day = ts.dt.date.values
    out = {}
    x = df[col].values.astype(np.float64)
    for d in np.unique(day[ok]):
        s = ok & (day == d)
        a = np.full(W, np.nan)
        a[m[s]] = x[s]
        out[d] = a
    return out


def heavy(u, days):
    nd = len(days)
    di = {d: i for i, d in enumerate(days)}
    hwv = np.full((nd, W), np.nan)
    hwr = np.full((nd, W), np.nan)
    acc_r = np.zeros((nd, W)); acc_w = np.zeros((nd, W)); acc_v = np.zeros((nd, W)); seen = np.zeros(nd, bool)
    for sym, w in HEAVY[u].items():
        fs = sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "NSE_EQ", sym, "*.parquet")))
        if not fs:
            print("missing", sym)
            continue
        x = pd.concat([pd.read_parquet(f, columns=["ts", "close", "volume"]) for f in fs])
        x["ts"] = x.ts.dt.tz_localize(None) if x.ts.dt.tz is not None else x.ts
        x = x.drop_duplicates("ts").sort_values("ts")
        cl = day_minutes(x, "close")
        vo = day_minutes(x, "volume")
        for d, a in cl.items():
            i = di.get(d)
            if i is None:
                continue
            a = pd.Series(a).ffill().values
            r = np.r_[np.nan, np.diff(np.log(a))]
            ok = np.isfinite(r)
            acc_r[i, ok] += w * r[ok]; acc_w[i, ok] += w
            v = np.nan_to_num(vo[d]) * np.nan_to_num(a)
            acc_v[i] += v
            seen[i] = True
    hwr[seen] = np.where(acc_w[seen] > 0, acc_r[seen] / np.maximum(acc_w[seen], 1e-12), np.nan)
    hwv[seen] = acc_v[seen]
    return hwv, hwr


def futures(u, days):
    f = os.path.join(C.SCRATCH, "hunt", "h38", "data", f"futmin_{u}.parquet")
    nd = len(days)
    A = {k: np.full((nd, W), np.nan) for k in ("futv", "futoi", "futc")}
    if not os.path.exists(f):
        return A
    x = pd.read_parquet(f)
    x["day"] = pd.to_datetime(x.day).dt.date
    di = {d: i for i, d in enumerate(days)}
    for d, g in x.groupby("day"):
        i = di.get(d)
        if i is None:
            continue
        g = g[(g.col >= 0) & (g.col < W)]
        A["futv"][i, g.col.values] = g.volume.values
        A["futoi"][i, g.col.values] = g.open_interest.values
        A["futc"][i, g.col.values] = g.close.values
    return A


def build(u):
    t0 = time.time()
    mk = D.market()
    ix = mk.index(u)
    M = ix.mat()
    days = list(ix.days)
    nd = len(days)
    step = C.STEP[u]
    real = np.array([bool(ix.d[d]["real"]) for d in days])
    exp = np.array([bool(ix.d[d]["exp"]) for d in days])
    c = ffill_rows(M["c"].astype(np.float64))
    names = ["optv", "flow", "cev2", "pev2", "cedoi2", "pedoi2", "oi2", "oi10", "ceret", "peret", "iv", "basis"]
    A = {k: np.full((nd, W), np.nan) for k in names}
    op = mk.options(u)
    for i, d in enumerate(days):
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        sp = pd.Series(c[i]).ffill().bfill().values
        if not np.isfinite(sp).any():
            continue
        K = ch.K.astype(float)
        atm = np.round(sp / step) * step
        dist = np.abs(K[:, None] - atm[None, :]) / step
        in2, in5, in10 = dist <= 2.5, dist <= 5.5, dist <= 10.5
        vC, vP = np.nan_to_num(ch.v["C"]), np.nan_to_num(ch.v["P"])
        A["optv"][i] = ((vC + vP) * in10).sum(0)
        sC, sP = tick_sign(ch.c["C"]), tick_sign(ch.c["P"])
        A["flow"][i] = ((sC * vC - sP * vP) * in5).sum(0)
        A["cev2"][i] = (vC * in2).sum(0)
        A["pev2"][i] = (vP * in2).sum(0)
        oC, oP = ffill_rows(ch.oi["C"]), ffill_rows(ch.oi["P"])
        dC = np.nan_to_num(np.diff(oC, axis=1, prepend=np.nan))
        dP = np.nan_to_num(np.diff(oP, axis=1, prepend=np.nan))
        A["cedoi2"][i] = (dC * in2).sum(0)
        A["pedoi2"][i] = (dP * in2).sum(0)
        A["oi2"][i] = ((np.nan_to_num(oC) + np.nan_to_num(oP)) * in2).sum(0)
        A["oi10"][i] = ((np.nan_to_num(oC) + np.nan_to_num(oP)) * in10).sum(0)
        cC, cP = ffill_rows(ch.c["C"]), ffill_rows(ch.c["P"])
        iC, iP = ffill_rows(ch.iv["C"]), ffill_rows(ch.iv["P"])
        kidx = np.clip(np.searchsorted(K, atm), 0, len(K) - 1)
        okk = K[kidx] == atm
        cols = np.arange(W)
        prevk = np.r_[kidx[0], kidx[:-1]]
        with np.errstate(divide="ignore", invalid="ignore"):
            A["ceret"][i, 1:] = np.log(cC[prevk[1:], cols[1:]] / cC[prevk[1:], cols[:-1]])
            A["peret"][i, 1:] = np.log(cP[prevk[1:], cols[1:]] / cP[prevk[1:], cols[:-1]])
        iv = 0.5 * (iC[kidx, cols] + iP[kidx, cols])
        A["iv"][i] = np.where(okk, iv, np.nan)
        syn = K[kidx] + cC[kidx, cols] - cP[kidx, cols]
        A["basis"][i] = np.where(okk, syn - sp, np.nan)
        if i % 200 == 0:
            print(u, d, i, f"{time.time() - t0:.0f}s", flush=True)
    vm = mk.vix.minutes()
    vix = np.full((nd, W), np.nan)
    for i, d in enumerate(days):
        if d in vm:
            vix[i] = vm[d]
    F = futures(u, days)
    hwv, hwr = heavy(u, days)
    out = dict(days=np.array([D.dnum(d) for d in days]), exp=exp, real=real,
               o=M["o"], h=M["h"], l=M["l"], c=c, vix=vix, hwv=hwv, hwr=hwr, **A, **F)
    out = {k: (v.astype(np.float32) if isinstance(v, np.ndarray) and v.dtype == np.float64 else v) for k, v in out.items()}
    np.savez_compressed(os.path.join(OUT, f"panel_{u}.npz"), **out)
    mk.release()
    print(u, "done", nd, days[0], days[-1], f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for u in (sys.argv[1:] or ["BANKNIFTY", "NIFTY"]):
        build(u)
