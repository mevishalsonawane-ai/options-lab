"""h38 feature panels (see PREREG.md). Pure features, no P&L.

    OBUY_CACHE=<scratch>/hunt/h38/cache flock <scratch>/obuy.lock python3 -I research/hunt/h38/build.py syn [UND ...]
    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/build.py futmin
    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/build.py daily

syn    : per underlying, per day, per minute (375 cols): spot (index close, ffilled) and the SYNTHETIC futures premium
         from put-call parity on the nearest option series: F_t = median over ATM+-2 strikes K of (K + C_K - P_K) using
         only strikes whose CE and PE both printed in the last 3 minutes; basis_bps = (F_t / spot_t - 1) x 1e4.
         -> <scratch>/hunt/h38/data/syn_<U>.npz (days int, X float32 [ndays, 2, 375])
futmin : Dhan 1-minute index futures with OI (only the contracts live in Oct 2026 exist; the 2026-10 contract is the
         one continuous series 2026-07-29 .. 2026-10-06, i.e. inside the locked holdout) -> data/futmin_<U>.parquet
daily  : NSE bhavcopy (fetch_bhav.py) -> data/daily_fut.parquet: per index per day: near-month futures OHLC/settle,
         contracts and OI summed over all expiries, near-month OI, index close, nearest-expiry option OI by side.
"""
from __future__ import annotations

import glob
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market, dnum  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h38", "data")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
FUTSEG = {"NIFTY": "NSE_FNO", "BANKNIFTY": "NSE_FNO", "FINNIFTY": "NSE_FNO", "MIDCPNIFTY": "NSE_FNO",
          "SENSEX": "BSE_FNO", "BANKEX": "BSE_FNO"}
FUTEXP = {"NSE_FNO": "2026-10-27", "BSE_FNO": "2026-10-29"}


def ffill(a):
    return pd.DataFrame(np.asarray(a, dtype=np.float64).T).ffill().values.T


def recent(A, k=3):
    """True where the row printed within the last k minutes (inclusive)."""
    ok = ~np.isnan(A)
    cs = np.cumsum(ok, axis=1)
    lag = np.concatenate([np.zeros((A.shape[0], k)), cs[:, :-k]], axis=1)
    return (cs - lag) > 0


def day_syn(ch, spot, step):
    K = ch.K.astype(np.float64)
    cC, cP = ffill(ch.c["C"]), ffill(ch.c["P"])
    fresh = recent(ch.c["C"]) & recent(ch.c["P"])
    atm = np.round(spot / step) * step
    F = K[:, None] + cC - cP
    near = np.abs(K[:, None] - atm[None, :]) <= 2 * step + 1e-9
    F = np.where(near & fresh, F, np.nan)
    with np.errstate(all="ignore"):
        f = np.nanmedian(F, axis=0)
        b = (f / spot - 1.0) * 1e4
    return np.stack([spot, b]).astype(np.float32)


def build_syn(u):
    mk = market()
    ix = mk.index(u)
    M = ix.mat()
    op = mk.options(u)
    step = C.STEP[u]
    days, X = [], []
    t0 = time.time()
    for i, d in enumerate(ix.days):
        if d.year < 2020:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        sp = M["c"][i].copy()
        if np.isnan(sp).all():
            sp = ch.spot.copy()
        sp = ffill(sp[None, :])[0].copy()
        if np.isnan(sp).all():
            continue
        first = np.flatnonzero(~np.isnan(sp))[0]
        sp[:first] = sp[first]
        X.append(day_syn(ch, sp, step))
        days.append(dnum(d))
        if len(days) % 300 == 0:
            print(u, d, len(days), f"{time.time() - t0:.0f}s", flush=True)
    os.makedirs(OUT, exist_ok=True)
    np.savez_compressed(os.path.join(OUT, f"syn_{u}.npz"), days=np.array(days), X=np.stack(X))
    mk.release()
    print(u, "syn done", len(days), f"{time.time() - t0:.0f}s", flush=True)


def build_futmin():
    os.makedirs(OUT, exist_ok=True)
    for u in UNDS + ["BANKEX"]:
        seg = FUTSEG[u]
        fs = sorted(glob.glob(os.path.join(C.DATA, "futures", seg, u, "*_minute.parquet")))
        parts = []
        for f in fs:
            x = pd.read_parquet(f)
            x["expiry"] = os.path.basename(f)[:10]
            parts.append(x)
        if not parts:
            continue
        x = pd.concat(parts)
        t = pd.to_datetime(x.ts, unit="s", utc=True).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        x["day"] = t.dt.normalize()
        x["col"] = (t.dt.hour * 60 + t.dt.minute - C.OPEN_M).astype(np.int16)
        x = x[(x.col >= 0) & (x.col < C.W)].drop(columns=["ts"]).sort_values(["expiry", "day", "col"])
        x.to_parquet(os.path.join(OUT, f"futmin_{u}.parquet"), compression="zstd", index=False)
        g = x.groupby("expiry").agg(rows=("col", "size"), days=("day", "nunique"), first=("day", "min"), last=("day", "max"))
        g["bars_per_day"] = (g.rows / g.days).round(0)
        print(u, "\n", g.to_string(), flush=True)


def build_daily():
    B = os.path.join(OUT, "bhav")
    fut = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(os.path.join(B, "fut_*.parquet")))])
    opt = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(os.path.join(B, "opt_*.parquet")))])
    fut = fut[fut.expiry >= fut.date]
    rows = []
    for (d, s), g in fut.groupby(["date", "sym"]):
        g = g.sort_values("expiry")
        near = g.iloc[0]
        # near contract on its own expiry day still counts; roll to next when near has < 3 sessions left is NOT done:
        # OI is summed over all expiries so roll-over does not create fake OI changes.
        rows.append(dict(date=d, sym=s, near_exp=near.expiry, f_open=near.open, f_high=near.high, f_low=near.low,
                         f_close=near.close, f_settle=near.settle, f_contracts=g.contracts.sum(),
                         f_oi=g.oi.sum(), f_oi_near=near.oi, f_und=near.und_px,
                         f_close_next=g.iloc[1].close if len(g) > 1 else np.nan))
    D = pd.DataFrame(rows)
    opt = opt[opt.expiry >= opt.date]
    ne = opt.groupby(["date", "sym"]).expiry.min().rename("o_exp")
    o = opt.merge(ne, on=["date", "sym"])
    o = o[o.expiry == o.o_exp].pivot_table(index=["date", "sym"], columns="side", values=["oi", "chg_oi"], aggfunc="sum")
    o.columns = [f"o_{a}_{b}" for a, b in o.columns]
    oa = opt.pivot_table(index=["date", "sym"], columns="side", values="oi", aggfunc="sum")
    oa.columns = [f"o_oi_all_{c}" for c in oa.columns]
    D = D.join(o, on=["date", "sym"]).join(oa, on=["date", "sym"])
    mk = market()
    cl = []
    for u in D.sym.unique():
        dl = mk.index(u).daily()
        cl.append(pd.DataFrame({"date": pd.to_datetime(dl.index), "sym": u, "spot_close": dl.close.values,
                                "real": dl.real.values}))
    D = D.merge(pd.concat(cl), on=["date", "sym"], how="left").sort_values(["sym", "date"])
    D.to_parquet(os.path.join(OUT, "daily_fut.parquet"), compression="zstd", index=False)
    print(D.groupby("sym").agg(days=("date", "size"), first=("date", "min"), last=("date", "max"),
                               spot_ok=("spot_close", lambda x: x.notna().mean())).to_string())


if __name__ == "__main__":
    if sys.argv[1] == "syn":
        for u in (sys.argv[2:] or UNDS):
            build_syn(u)
    elif sys.argv[1] == "futmin":
        build_futmin()
    else:
        build_daily()
