"""h39 Stage C1 (PREREG section 3): stock-option features of the heavyweights vs the index, lead-lag 1..15 min,
PRE-HOLDOUT ONLY. Needs the data written by fetch_stockopt.py (scratchpad/hunt/h39/data/<SYM>/<YEAR>.parquet).
Prints the IC table and the pre-registered gate (|t| >= 3 at some k >= 2 AND top-1% move >= 1.0 x break-even).
Stage C2/C3 (P&L) are only run for features that pass this gate.

    python3 -I research/hunt/h39/stage_c_leadlag.py [--data DIR]
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
import proxy_diag as P  # noqa: E402

HOLD = pd.Timestamp("2025-10-01").date()
NW = P.NW


def load_stock(ddir, s, upto=HOLD):
    fs = sorted(f for f in os.listdir(os.path.join(ddir, s)) if f.endswith(".parquet"))
    df = pd.concat([pd.read_parquet(os.path.join(ddir, s, f)) for f in fs], ignore_index=True)
    t = pd.to_datetime(df["ts"].astype("int64") * 60, unit="s", utc=True).dt.tz_convert("Asia/Kolkata")
    df["day"] = t.dt.date
    df["m"] = (t.dt.hour * 60 + t.dt.minute - 555).astype(int)
    df = df[(df.m >= 0) & (df.m < NW) & (df.day < upto)]
    df = df.assign(iv=np.where(df.iv >= 0, df.iv / 20.0, np.nan))      # int16 IV x 20 -> vol points
    days = sorted(df.day.unique())
    di = {d: i for i, d in enumerate(days)}
    r = df.day.map(di).values
    shape = (len(days), NW, 7, 2)                    # day, minute, offset -3..3, side (0 call, 1 put)
    A = {f: np.full(shape, np.nan, np.float32) for f in ("c", "v", "oi", "iv", "strike", "spot")}
    o = df.off.values + 3
    sd = (df.side.values == -1).astype(int)
    for f in A:
        A[f][r, df.m.values, o, sd] = df[f].values
    return days, A


def feats_of(A):
    c, v, oi, iv, k = A["c"], np.nan_to_num(A["v"]), A["oi"], A["iv"], A["strike"]
    spot = np.nanmedian(A["spot"].reshape(*A["spot"].shape[:2], -1), axis=2)
    pv = np.nan_to_num(c) * v
    cs = lambda a: np.cumsum(a, axis=1)  # noqa: E731
    def win(a, n):                       # trailing n-minute sum over [t-n+1, t]
        z = cs(a)
        return z - P.lag(z, n)
    pc, pp = win(pv[..., 0].sum(2), 5), win(pv[..., 1].sum(2), 5)
    vc, vp = win(v[..., 0].sum(2), 5), win(v[..., 1].sum(2), 5)
    oi_f = P.ffill(oi)                               # ffill/lag act on the minute axis for any rank
    d15 = oi_f - P.lag(oi_f, 15)
    toi = np.nansum(oi_f, axis=(2, 3))
    doc, dop = np.nansum(d15[..., 0], 2), np.nansum(d15[..., 1], 2)
    skew = P.ffill(iv[:, :, 3, 0] - iv[:, :, 3, 1])
    synf = np.nanmedian(k[:, :, 2:5, 0] + c[:, :, 2:5, 0] - c[:, :, 2:5, 1], axis=2)
    with np.errstate(invalid="ignore", divide="ignore"):
        basis = P.ffill((synf / spot - 1) * 1e4)
        return {"PFLOW5": (pc - pp) / (pc + pp), "VIMB5": (vc - vp) / (vc + vp), "OIB15": (dop - doc) / toi,
                "IVJ5": skew - P.lag(skew, 5), "SYNF1": basis - P.lag(basis, 1), "SYNF5": basis - P.lag(basis, 5)}


def zscore(x, n=20):
    """Per minute-of-day z vs the previous n days' median / MAD (no look-ahead)."""
    z = np.full_like(x, np.nan)
    for i in range(n, x.shape[0]):
        h = x[i - n:i]
        med = np.nanmedian(h, 0)
        mad = np.nanmedian(np.abs(h - med), 0) * 1.4826
        z[i] = (x[i] - med) / np.where(mad > 0, mad, np.nan)
    return np.clip(z, -5, 5)                          # amendment 2: MAD ~ 0 gave inf z -> overflow in the aggregate


def opt_moves(u, days, feats, be_prem):
    """Top-1% |feature| minutes (pre-holdout, 09:20-15:00): the 1-ITM nearest index option on the signal side, bought at
    the next minute's OPEN, marked at the close k minutes later; premium points minus round-trip cost (be_prem).
    Baseline: the same option mechanics on every minute in the window, side = sign of the feature (all |values|)."""
    from obuy import data as D
    op = D.market().options(u)
    step = C.STEP[u]
    cols = np.arange(P.A0, 345)
    res = {f: {k: [] for k in (5, 10, 15)} for f in feats}
    base = {k: [] for k in (5, 10, 15)}
    thr = {f: np.nanpercentile(np.abs(x[:, cols][x[:, cols] != 0]), 99) for f, x in feats.items()}
    for i, d in enumerate(days):
        if d.weekday() >= 5:
            continue
        ch = op.chain(d, "near")
        if ch is None:
            continue
        for f, x in feats.items():
            xs = x[i, cols]
            hit = np.where(np.abs(xs) >= thr[f])[0]
            allm = np.arange(0, len(cols), 15) if f == "PFLOW5" else []     # baseline sampled every 15 min
            for j, is_base in [(h, False) for h in hit] + [(a, True) for a in allm]:
                t = cols[j]
                sg = np.sign(xs[j])
                if sg == 0 or not np.isfinite(ch.spot[t]):
                    continue
                k0 = (np.floor if sg > 0 else np.ceil)(ch.spot[t] / step) * step
                ki = ch.kpos(int(k0))
                if ki < 0:
                    continue
                r = "C" if sg > 0 else "P"
                e = ch.o[r][ki, t + 1]
                for k in (5, 10, 15):
                    xk = ch.c[r][ki, min(t + k, NW - 1)]
                    if np.isfinite(e) and np.isfinite(xk):
                        (base[k] if is_base else res[f][k]).append(xk - e - be_prem)
    rows = [dict(feat=f, n=len(res[f][5]), **{f"net{k}": np.mean(res[f][k]) if res[f][k] else np.nan for k in (5, 10, 15)})
            for f in feats]
    rows.append(dict(feat="baseline(all minutes)", n=len(base[5]), **{f"net{k}": np.mean(base[k]) for k in (5, 10, 15)}))
    return pd.DataFrame(rows)


def build(u, W, data, upto=HOLD):
    """Weight-aggregated, per-minute-of-day z-scored features over the heavyweights: (days, {name: [day, minute]})."""
    acc, wsum, days0 = {}, 0.0, None
    for s, w in W.items():
        if not os.path.isdir(os.path.join(data, s)):
            print(f"{u}: no data for {s}, skipped")
            continue
        days, A = load_stock(data, s, upto)
        if days0 is None:
            days0 = days
        F = feats_of(A)
        di = {d: i for i, d in enumerate(days)}
        ix = [di.get(d, -1) for d in days0]
        for f, x in F.items():
            xz = zscore(x)
            al = np.where(np.array(ix)[:, None] >= 0, xz[np.maximum(ix, 0)], np.nan)
            acc[f] = acc.get(f, 0) + w * np.nan_to_num(al)
        wsum += w
    if not acc:
        return None, None
    return days0, {f: v / wsum for f, v in acc.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(C.SCRATCH, "hunt", "h39", "data"))
    ap.add_argument("--und", action="append")
    a = ap.parse_args()
    for u, W in (("BANKNIFTY", P.W_BN), ("NIFTY", P.W_NF)):
        if a.und and u not in a.und:
            continue
        days0, feats = build(u, W, a.data)
        if feats is None:
            continue
        idx = pd.concat([pd.read_parquet(os.path.join(P.R, "candles", "minute", "IDX_I", u, f"{y}.parquet"))
                         for y in sorted({d.year for d in days0})])
        ix = P.ffill(P.grid(idx, days0, "close"))
        be, prem, lot = P.breakeven(u, [d for d in days0 if d.year >= 2023][-250:])
        t = P.ic_table(feats, ix)
        m = P.tail_moves(feats, ix, be)
        print(f"=== {u} pre-holdout {days0[0]}..{days0[-1]}  break-even {be:.1f} index pts ===")
        print(t.round(3).to_string(index=False))
        print(m.round(2).to_string(index=False))
        om = opt_moves(u, days0, feats, be * P.DELTA)
        print(f"1-ITM nearest {u} option, premium pts NET of {be * P.DELTA:.2f} round-trip cost (next-minute open -> close t+k):")
        print(om.round(2).to_string(index=False))
        tk = t[[c for c in t.columns if c.startswith("t") and c[1:].isdigit() and int(c[1:]) >= 2]].abs().max(1)
        gate = (tk.values >= 3) & (m["best/BE"].values >= 1.0)
        print("GATE pass:", [f for f, g in zip(t.feat, gate) if g] or "none -> Stage C stops, verdict NO")


if __name__ == "__main__":
    main()
