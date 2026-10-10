"""HEAVY: heavyweight / breadth lead-lag from the 214 F&O stocks' minutes (2024-10 .. 2026-10).

At T minutes after the open (T = 5 or 15):
  basket  : equal-weight log return open->T of the heavyweights (NIFTY: HDFCBANK ICICIBANK RELIANCE INFY BHARTIARTL LT
            ITC TCS AXISBANK KOTAKBANK SBIN; BANKNIFTY: HDFCBANK ICICIBANK SBIN AXISBANK KOTAKBANK) minus the index's
            own open->T return; z = that residual / its sd over the previous 60 days.
  breadth : (share of the 214 stocks above their open at T) - 0.5, minus 0.5*tanh(index z), z-scored the same way (NIFTY).
|z| > k -> buy the index's ATM option: 'follow' in the residual's sign, 'fade' against it. One trade per index a day.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import stocks as SK
import numpy as np, pandas as pd
from obuy import config as C

NB = ["HDFCBANK", "ICICIBANK", "RELIANCE", "INFY", "BHARTIARTL", "LT", "ITC", "TCS", "AXISBANK", "KOTAKBANK", "SBIN"]
BB = ["HDFCBANK", "ICICIBANK", "SBIN", "AXISBANK", "KOTAKBANK"]


def _z(x):
    s = pd.Series(x)
    sd = s.dropna().shift(1).rolling(60, min_periods=30).std().reindex(s.index).values
    return x / sd


def features(T):
    S = SK.load(); A = cm.aligned()
    pos = {d: i for i, d in enumerate(A["days"])}
    J = np.array([pos[d] for d in S["days"]])
    names = S["names"]
    with np.errstate(all="ignore"):
        r = np.log(S["C"][:, :, T - 1] / S["O"][:, :, 0]).astype(float)        # (stocks, days)
    out = {}
    for und, bk in (("NIFTY", NB), ("BANKNIFTY", BB)):
        idx = [names.index(x) for x in bk if x in names]
        bret = np.nanmean(r[idx], axis=0)
        ir = np.log(A["C"][und][J, T - 1] / A["O"][und][J, 0])
        out[(und, "basket")] = _z(bret - ir)
    br = np.nanmean(r > 0, axis=0) - 0.5
    ir = np.log(A["C"]["NIFTY"][J, T - 1] / A["O"]["NIFTY"][J, 0])
    out[("NIFTY", "breadth")] = _z(br - 0.5 * np.tanh(_z(ir)))
    exp = {u: A["exp"][u][J] for u in ("NIFTY", "BANKNIFTY")}
    fwd = {u: np.log(A["C"][u][J, min(T - 1 + 60, 360)] / A["C"][u][J, T - 1]) for u in ("NIFTY", "BANKNIFTY")}
    return S["days"], out, exp, fwd


def sigs(T, k, feat, mode):
    days, F, exp, _ = features(T)
    out = []
    for (und, f), z in F.items():
        if f != feat:
            continue
        for j, d in enumerate(days):
            if np.isfinite(z[j]) and abs(z[j]) > k and not exp[und][j]:
                side = int(np.sign(z[j])) * (1 if mode == "follow" else -1)
                out.append(dict(und=und, day=d, sig_min=C.OPEN_M + T - 1, side=side, book=f"hv_{und}", tag=f"{d}"))
    return pd.DataFrame(out)


def sets():
    out = {}
    for feat in ("basket", "breadth"):
        for T in (5, 15):
            for k in (1.0, 1.5):
                for mode in ("follow", "fade"):
                    out[f"HEAVY_{feat}_T{T}_k{k}_{mode}"] = (sigs(T, k, feat, mode), 5)
    return out


if __name__ == "__main__":   # in-sample index-level check only (< 2025-10-01)
    for T in (5, 15):
        days, F, exp, fwd = features(T)
        ins = np.array([d < cm.HOLD0 for d in days])
        for (und, f), z in F.items():
            fw = fwd[und]
            ok = ins & np.isfinite(z) & np.isfinite(fw)
            big = ok & (np.abs(z) > 1)
            print(T, und, f, ok.sum(), "corr", round(np.corrcoef(z[ok], fw[ok])[0, 1], 3),
                  "|z|>1 n", big.sum(), "follow bps", round(1e4 * np.mean(np.sign(z[big]) * fw[big]), 2))
