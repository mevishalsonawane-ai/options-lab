"""h5 cache builder: per (underlying, day) index minutes, near-series option open/close per strike, OI-derived and
straddle features, VIX minutes; plus F&O-stock breadth per day/minute (2024-26).  Run: python3 -I build.py"""
import os, sys, pickle
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import glob
import numpy as np, pandas as pd
from obuy import data as D, config as C

OUT = os.path.join(C.SCRATCH, "hunt", "h5")
DEC = [10 * 60, 10 * 60 + 30, 11 * 60, 11 * 60 + 30]          # decision minutes (bar start); use that bar's close


def build_und(u):
    mk = D.market()
    ix, op = mk.index(u), mk.options(u)
    vixm = mk.vix.minutes()
    step = C.STEP[u]
    days, rec = [], {}
    for d in ix.days:
        x = ix.d[d]
        if not x["real"]:
            continue
        ch = op.chain(d, "near")
        if ch is None:
            continue
        col = x["m"] - C.OPEN_M
        ok = (col >= 0) & (col < C.W)
        I = {k: np.full(C.W, np.nan) for k in "ohlc"}
        for k in "ohlc":
            I[k][col[ok]] = x[k][ok]
        if np.isnan(I["c"]).sum() > 30:
            continue
        for k in "ohlc":
            I[k] = pd.Series(I[k]).ffill().bfill().values
        K = ch.K
        r = dict(K=K.astype(np.int32), exp=bool(x["exp"]), series=ch.series,
                 Co=ch.o["C"].astype(np.float32), Cc=ch.c["C"].astype(np.float32),
                 Po=ch.o["P"].astype(np.float32), Pc=ch.c["P"].astype(np.float32),
                 io=I["o"], ih=I["h"], il=I["l"], ic=I["c"])
        # OI imbalance and straddle at each decision minute
        feats = {}
        oiC, oiP = ch.oi["C"], ch.oi["P"]
        def first_valid(a):
            out = np.full(a.shape[0], np.nan)
            for i in range(a.shape[0]):
                v = a[i][~np.isnan(a[i])]
                if len(v):
                    out[i] = v[0]
            return out
        oC0, oP0 = first_valid(oiC[:, :10]), first_valid(oiP[:, :10])
        def stv(t):
            s = I["c"][t]
            atm = int(round(s / step)) * step
            i = np.searchsorted(K, atm)
            if i >= len(K) or K[i] != atm:
                return np.nan
            cc = pd.Series(ch.c["C"][i, :t + 1]).ffill().values[-1]
            pp = pd.Series(ch.c["P"][i, :t + 1]).ffill().values[-1]
            return cc + pp
        st5 = stv(5)
        for T in DEC:
            t = T - C.OPEN_M
            s = I["c"][t]
            atm = int(round(s / step)) * step
            sel = (K >= atm - 5 * step) & (K <= atm + 5 * step)
            def last(a):
                out = np.full(a.shape[0], np.nan)
                for i in range(a.shape[0]):
                    v = a[i, :t + 1][~np.isnan(a[i, :t + 1])]
                    if len(v):
                        out[i] = v[-1]
                return out
            dC = np.nansum((last(oiC) - oC0)[sel]); dP = np.nansum((last(oiP) - oP0)[sel])
            den = abs(dC) + abs(dP)
            feats[T] = dict(oi_imb=(dP - dC) / den if den > 0 else 0.0, st_ratio=stv(t) / st5 if st5 and st5 > 0 else np.nan)
        r["feats"] = feats
        v = vixm.get(d)
        r["vix"] = None if v is None else pd.Series(v).ffill().bfill().values.astype(np.float32)
        rec[d] = r
        days.append(d)
    lots = {d: ix.lot(d, "today") for d in days}
    with open(os.path.join(OUT, f"day_{u}.pkl"), "wb") as f:
        pickle.dump(dict(days=days, rec=rec, daily=ix.daily(), vixd=mk.vix.daily, lot_today=ix.lot(days[-1], "today")), f, protocol=5)
    mk.release(u)
    print(u, len(days), flush=True)


def build_breadth():
    fs = sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "NSE_EQ", "*")))
    ups_o, ups_p, cnt = {}, {}, {}
    for s in fs:
        parts = [pd.read_parquet(f, columns=["ts", "open", "close"]) for f in sorted(glob.glob(os.path.join(s, "*.parquet")))]
        if not parts:
            continue
        x = pd.concat(parts)
        x["ts"] = x.ts.dt.tz_localize(None)
        x["day"] = x.ts.dt.date
        x["m"] = x.ts.dt.hour * 60 + x.ts.dt.minute
        x = x[(x.m >= C.OPEN_M) & (x.m <= C.LAST_M)].drop_duplicates(["day", "m"]).sort_values(["day", "m"])
        dop = x.groupby("day").open.first()
        dcl = x.groupby("day").close.last()
        pc = dcl.shift(1)
        for T in DEC:
            y = x[x.m <= T].groupby("day").close.last()
            for dd, val in y.items():
                k = (dd, T)
                cnt[k] = cnt.get(k, 0) + 1
                ups_o[k] = ups_o.get(k, 0) + (val > dop[dd])
                if not np.isnan(pc.get(dd, np.nan)):
                    ups_p[k] = ups_p.get(k, 0) + (val > pc[dd])
    br = pd.DataFrame([dict(day=k[0], T=k[1], n=n, up_open=ups_o[k] / n, up_pc=ups_p.get(k, np.nan) / n) for k, n in cnt.items()])
    br.to_pickle(os.path.join(OUT, "breadth.pkl"))
    print("breadth", len(br), flush=True)


if __name__ == "__main__":
    for a in sys.argv[1:]:
        build_breadth() if a == "breadth" else build_und(a)
