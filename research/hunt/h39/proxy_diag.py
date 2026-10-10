"""h39 Stage A (PREREG section 1): heavyweight STOCK FUTURES (basis, OI build-up, flow) vs the index, lead-lag 0..15 min.
Descriptive only. The only stock-derivative minute history available is the live nearest futures contract
(2026-07-29 .. 2026-10-06), which lies inside the locked holdout: nothing is chosen or traded from this.

    python3 -I research/hunt/h39/proxy_diag.py
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import costs as CO  # noqa: E402
from obuy import data as D  # noqa: E402

R = os.path.join(C.SCRATCH, "dhan", "repo", "dhan-data")
OUT = os.path.join(C.SCRATCH, "hunt", "h39")
FUT = "2026-10-27"
W_BN = {"HDFCBANK": 27.0, "ICICIBANK": 24.5, "SBIN": 9.0, "KOTAKBANK": 8.5, "AXISBANK": 8.5}
W_NF = {"HDFCBANK": 13.0, "ICICIBANK": 9.0, "RELIANCE": 8.5, "INFY": 5.0, "BHARTIARTL": 4.5, "LT": 4.0, "ITC": 3.5,
        "TCS": 3.0, "SBIN": 3.0, "AXISBANK": 3.0}
HALF_SPREAD = 0.0016           # h24 real half-spread, BN and NIFTY 1-ITM/ATM
DELTA = 0.6
NW = 375
A0, A1 = 5, 360                # 09:20 .. 15:15 signal minutes
pd.set_option("display.width", 250)


def grid(df, days, col):
    ts = pd.to_datetime(df["ts"])
    d = ts.dt.date.values
    m = (ts.dt.hour * 60 + ts.dt.minute).values - (9 * 60 + 15)
    ok = (m >= 0) & (m < NW)
    di = {x: i for i, x in enumerate(days)}
    out = np.full((len(days), NW), np.nan)
    rows = np.array([di.get(x, -1) for x in d[ok]])
    k = rows >= 0
    out[rows[k], m[ok][k]] = df[col].values[ok][k]
    return out


def ffill(a):
    a = a.copy()
    for j in range(1, a.shape[1]):
        cur = a[:, j]
        nan = np.isnan(cur)
        cur[nan] = a[:, j - 1][nan]
    return a


def lag(a, k):
    out = np.full_like(a, np.nan)
    out[:, k:] = a[:, :-k] if k else a
    return out


def load_days():
    f = pd.read_parquet(os.path.join(R, "futures", "NSE_FNO", "HDFCBANK", f"{FUT}_minute.parquet"))
    return sorted(set(pd.to_datetime(f["ts"]).dt.date))


def stock_feats(s, days):
    f = pd.read_parquet(os.path.join(R, "futures", "NSE_FNO", s, f"{FUT}_minute.parquet"))
    q = pd.read_parquet(os.path.join(R, "candles", "minute", "NSE_EQ", s, "2026.parquet"))
    fc, fo, fv = ffill(grid(f, days, "close")), grid(f, days, "open"), grid(f, days, "volume")
    oi = grid(f, days, "open_interest")
    oi[oi <= 0] = np.nan
    oi = ffill(oi)
    sc = ffill(grid(q, days, "close"))
    with np.errstate(invalid="ignore", divide="ignore"):
        basis = (fc / sc - 1) * 1e4
        out = {
            "FRET1": (fc / lag(fc, 1) - 1) * 1e4,
            "SRET1": (sc / lag(sc, 1) - 1) * 1e4,
            "dBASIS1": basis - lag(basis, 1),
            "dBASIS5": basis - lag(basis, 5),
            "BU5": np.sign(fc - lag(fc, 5)) * np.maximum(oi / lag(oi, 5) - 1, 0) * 1e4,
        }
        sv = np.nan_to_num(fv) * np.sign(np.nan_to_num(fc - fo))
        csv = np.nancumsum(sv, 1)
        cv = np.nancumsum(np.nan_to_num(fv), 1)
        out["FVI5"] = (csv - lag(csv, 5)) / (cv - lag(cv, 5))
    return out


def agg(weights, days):
    acc, wsum = {}, 0.0
    for s, w in weights.items():
        F = stock_feats(s, days)
        for k, v in F.items():
            acc[k] = acc.get(k, 0) + w * np.nan_to_num(v)
        wsum += w
    return {k: v / wsum for k, v in acc.items()}


def ic_table(feats, idx):
    rows = []
    cols = np.arange(A0, A1)
    for name, x in feats.items():
        row = {"feat": name}
        for k in [0, 1, 2, 3, 5, 10, 15]:
            with np.errstate(invalid="ignore", divide="ignore"):
                if k == 0:
                    y = idx[:, cols] / idx[:, cols - 1] - 1
                else:
                    y = idx[:, np.minimum(cols + k, NW - 1)] / idx[:, cols] - 1
            a, b = x[:, cols].ravel(), y.ravel()
            ok = np.isfinite(a) & np.isfinite(b) & (a != 0)
            r = np.corrcoef(a[ok], b[ok])[0, 1]
            n_eff = ok.sum() / max(k, 1)
            row[f"k{k}"] = r
            row[f"t{k}"] = r * np.sqrt(n_eff)
        rows.append(row)
    return pd.DataFrame(rows)


def tail_moves(feats, idx, be_pts):
    rows = []
    cols = np.arange(A0, A1 - 15)
    for name, x in feats.items():
        a = x[:, cols]
        thr = np.nanpercentile(np.abs(a[a != 0]), 99)
        sel = np.abs(a) >= thr
        row = {"feat": name, "n": int(sel.sum())}
        for k in (1, 5, 10, 15):
            mv = np.sign(a) * (idx[:, cols + k] - idx[:, cols])
            row[f"pts{k}"] = np.nanmean(mv[sel])
        row["breakeven_pts"] = be_pts
        row["best/BE"] = max(row["pts5"], row["pts10"], row["pts15"]) / be_pts
        rows.append(row)
    return pd.DataFrame(rows)


def breakeven(u, days):
    """Median round-trip cost of a 1-ITM nearest option, in INDEX points (/delta), on the window's days at 10:00."""
    mk = D.market()
    op = mk.options(u)
    step = C.STEP[u]
    co = CO.Costs()
    pts = []
    for d in days:
        ch = op.chain(d, "near")
        if ch is None:
            continue
        sp = ch.spot[45]
        if not np.isfinite(sp):
            continue
        k = int(np.floor(sp / step) * step)        # 1-ITM call
        i = ch.kpos(k)
        if i < 0:
            continue
        p = ch.c["C"][i, 45]
        if not np.isfinite(p):
            continue
        lot = D.lot_schedule(u, d)
        chg = (co.charge(True, np.array([p]), np.array([lot]))[0] + co.charge(False, np.array([p]), np.array([lot]))[0]) / lot
        pts.append((2 * HALF_SPREAD * p + 0.001 * p + chg, p, lot))
    a = np.array(pts)
    return np.median(a[:, 0]) / DELTA, np.median(a[:, 1]), np.median(a[:, 2])


def main():
    days = load_days()
    print(f"window {days[0]} .. {days[-1]}: {len(days)} sessions (ALL inside the locked holdout: descriptive only)")
    res = []
    for u, W in (("BANKNIFTY", W_BN), ("NIFTY", W_NF)):
        ix = ffill(grid(pd.read_parquet(os.path.join(R, "candles", "minute", "IDX_I", u, "2026.parquet")), days, "close"))
        fi = pd.read_parquet(os.path.join(R, "futures", "NSE_FNO", u, f"{FUT}_minute.parquet"))
        ifc = ffill(grid(fi, days, "close"))
        feats = agg(W, days)
        with np.errstate(invalid="ignore", divide="ignore"):
            feats["IDXFUT_RET1"] = (ifc / lag(ifc, 1) - 1) * 1e4
            ib = (ifc / ix - 1) * 1e4
            feats["IDX_dBASIS1"] = ib - lag(ib, 1)
        be, prem, lot = breakeven(u, days)
        print(f"\n=== {u}: 1-ITM nearest premium median Rs {prem:.0f}, lot {lot:.0f}; round trip "
              f"(2x0.16% spread + app 5+5 bps + app charges) = {be * DELTA:.2f} premium pts = {be:.1f} index pts at delta 0.6")
        t = ic_table(feats, ix)
        print("IC of feature at minute t vs index return t-1->t (k0) and t->t+k:")
        print(t.round(3).to_string(index=False))
        m = tail_moves(feats, ix, be)
        print("top-1% |feature| minutes: mean index move in the signal direction from close t (index pts):")
        print(m.round(2).to_string(index=False))
        t.insert(0, "und", u)
        m.insert(0, "und", u)
        res.append((t, m))
    pd.concat([r[0] for r in res]).to_csv(os.path.join(OUT, "proxy_ic.csv"), index=False)
    pd.concat([r[1] for r in res]).to_csv(os.path.join(OUT, "proxy_tail.csv"), index=False)


if __name__ == "__main__":
    main()
