"""R16 row tables (features + targets + baselines) from data.py's outputs. No model, no evaluation here.

    python3 -I research/hunt/r16/build.py <scratch>

Every feature at decision minute t uses minutes 0..t of the session, earlier sessions, and the VIX close of the
previous session only. Targets use minutes after t.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

N = 375
STEP = {"BANKNIFTY": 100.0, "NIFTY": 50.0}


def vix_prev(scr, days):
    v = pd.read_csv(os.path.join(scr, "daily_VIX.csv"), parse_dates=["day"])
    v = v.sort_values("day")
    vd = v.day.dt.date.values
    i = np.searchsorted(vd, np.array(days), side="left") - 1
    return np.where(i >= 0, v.close.values[np.maximum(i, 0)], np.nan)


def lg(x):
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.log(x)


def session_table(scr, sym):
    z = np.load(os.path.join(scr, f"{sym}.npz"))
    days = [pd.Timestamp(str(d)).date() for d in z["days"]]
    ix, oc, oi, ks = z["ix"], z["oc"], z["oi"], z["strikes"]
    exp = [pd.Timestamp(str(d)).date() for d in z["expiry"]]
    O, H, L, C = ix[..., 0], ix[..., 1], ix[..., 2], ix[..., 3]
    D = len(days)
    dO, dH, dL, dC = O[:, 0], H.max(1), L.min(1), C[:, -1]
    prevC = np.r_[np.nan, dC[:-1]]
    lr_day = lg((dH - dL) / dC)
    s = pd.Series(lr_day)
    m5 = s.shift(1).rolling(5).mean().values
    m20 = s.shift(1).rolling(20).mean().values
    R20 = pd.Series((dH - dL) / dC).shift(1).rolling(20).mean().values
    pret = pd.Series(lg(dC / prevC)).shift(1).values          # previous day's return (known before open)
    plr = s.shift(1).values
    vix = vix_prev(scr, days)
    dte = np.array([(e - d).days for d, e in zip(days, exp)])
    wd = np.array([d.weekday() for d in days])
    ce = np.nansum(oi[..., 0], axis=2)
    pe = np.nansum(oi[..., 1], axis=2)
    ce[ce == 0] = np.nan
    pe[pe == 0] = np.nan
    ce = pd.DataFrame(ce).T.ffill().bfill().T.values
    pe = pd.DataFrame(pe).T.ffill().bfill().T.values
    return dict(days=days, O=O, H=H, L=L, C=C, oc=oc, ks=ks, dO=dO, dH=dH, dL=dL, dC=dC, prevC=prevC, m5=m5, m20=m20,
                R20=R20, pret=pret, plr=plr, vix=vix, dte=dte, wd=wd, ce=ce, pe=pe, D=D)


def feats_at(T, i, t):
    O, H, L, C = T["O"][i], T["H"][i], T["L"][i], T["C"][i]
    c = C[t]
    f = dict(day=T["days"][i], t=t)
    for k in (1, 5, 15, 30, 60):
        f[f"r{k}"] = lg(c / C[t - k]) if t - k >= 0 else np.nan
        f[f"lr{k}"] = lg((H[max(0, t - k + 1):t + 1].max() - L[max(0, t - k + 1):t + 1].min()) / c) if k > 1 else np.nan
    f.pop("lr1")
    hso, lso = H[:t + 1].max(), L[:t + 1].min()
    f["lrso"] = lg((hso - lso) / c)
    f["ropen"] = lg(c / O[0])
    f["gap"] = lg(O[0] / T["prevC"][i])
    twap = ((H[:t + 1] + L[:t + 1] + C[:t + 1]) / 3).mean()
    f["dvwap"] = (c - twap) / max(hso - lso, 1e-9)
    f["pos"] = (c - lso) / max(hso - lso, 1e-9)
    f["er_open"] = abs(c - O[0]) / max(hso - lso, 1e-9)
    f["lvix"] = lg(T["vix"][i])
    for k in ("pret", "plr", "m5", "m20"):
        f[k] = T[k][i]
    f["lrso_rel"] = f["lrso"] - T["m20"][i]
    f["wd"], f["dte"], f["isexp"] = T["wd"][i], T["dte"][i], float(T["dte"][i] == 0)
    ce, pe = T["ce"][i], T["pe"][i]
    f["oice_open"], f["oipe_open"] = lg(ce[t] / ce[0]), lg(pe[t] / pe[0])
    f["oice15"], f["oipe15"] = lg(ce[t] / ce[max(0, t - 15)]), lg(pe[t] / pe[max(0, t - 15)])
    f["oice30"], f["oipe30"] = lg(ce[t] / ce[max(0, t - 30)]), lg(pe[t] / pe[max(0, t - 30)])
    f["dpcr_open"] = lg(pe[t] / ce[t]) - lg(pe[0] / ce[0])
    return f


def fwd_range(T, i, t, h):
    H, L, C = T["H"][i], T["L"][i], T["C"][i]
    e = min(N, t + 1 + h)
    return lg((H[t + 1:e].max() - L[t + 1:e].min()) / C[t])


def add_btod(df, col, out):
    """mean of `col` at the same t over the previous 20 sessions."""
    p = df.pivot_table(index="day", columns="t", values=col)
    b = p.shift(1).rolling(20, min_periods=20).mean()
    m = b.stack().rename(out).reset_index()
    return df.merge(m, on=["day", "t"], how="left")


def atm_quotes(T, i, t, h, sym):
    ks, c = T["ks"][i], T["C"][i][t]
    if np.all(np.isnan(ks)):
        return [np.nan] * 5
    j = int(np.nanargmin(np.abs(ks - c)))
    if abs(ks[j] - c) > STEP[sym]:
        return [np.nan] * 5
    oc = T["oc"][i]
    return [ks[j], oc[t, j, 0], oc[t + h, j, 0], oc[t, j, 1], oc[t + h, j, 1]]


def intraday(scr, sym):
    T = session_table(scr, sym)
    D = T["D"]
    out = {}
    # a1/a2 and b share decision rows every 15 min
    rows = []
    for i in range(1, D):
        for t in range(15, 346, 15):
            f = feats_at(T, i, t)
            C = T["C"][i]
            for h in (15, 30, 60):
                if t + h <= 360:
                    r = C[t + h] - C[t]
                    f[f"y{h}"] = np.nan if r == 0 else float(r > 0)
                    f[f"ret{h}"] = lg(C[t + h] / C[t])
                    k, ci, co, pi, po = atm_quotes(T, i, t, h, sym)
                    f[f"k{h}"], f[f"ce_in{h}"], f[f"ce_out{h}"], f[f"pe_in{h}"], f[f"pe_out{h}"] = k, ci, co, pi, po
                else:
                    f[f"y{h}"] = np.nan
            for h in (30, 60):
                f[f"v{h}"] = fwd_range(T, i, t, h) if t + h <= 360 else np.nan
            rows.append(f)
    A = pd.DataFrame(rows)
    for h in (30, 60):
        A = add_btod(A, f"v{h}", f"btod{h}")
    A["blast30"], A["blast60"] = A.lr30, A.lr60
    A.to_parquet(os.path.join(scr, f"rows_{sym}_intra.parquet"))
    out["intra"] = len(A)
    # a3b (rest-of-day range from 09:30), c (regime), e (analogs): one row per session
    R = []
    for i in range(1, D):
        H, L, C, O = T["H"][i], T["L"][i], T["C"][i], T["O"][i]
        f = feats_at(T, i, 15)
        f["v_rest"] = lg((H[16:].max() - L[16:].min()) / C[15])
        for t in (30, 45):
            g = feats_at(T, i, t)
            er = abs(C[-1] - C[t]) / max(H[t + 1:].max() - L[t + 1:].min(), 1e-9)
            f[f"er_rest{t}"] = er
            f[f"trend{t}"] = float(er >= 0.5)
            for k in ("er_open", "lrso", "lrso_rel", "ropen", "oice_open", "oipe_open", "dpcr_open", "r15", "r30", "dvwap", "pos"):
                f[f"{k}_{t}"] = g[k]
        # analogs at 10:15 (t = 60)
        R20 = T["R20"][i]
        o = O[0]
        for j, m in enumerate(range(4, 60, 5)):
            f[f"path{j}"] = (C[m] - o) / o / R20
        f["gap_n"] = (o - T["prevC"][i]) / T["prevC"][i] / R20
        f["e_ret"] = (C[-1] - C[60]) / C[60] / R20
        f["e_rng"] = (H[61:].max() - L[61:].min()) / C[60] / R20
        R.append(f)
    S = pd.DataFrame(R)
    S["btod_rest"] = S.v_rest.shift(1).rolling(20, min_periods=20).mean()
    for t in (30, 45):
        S[f"prev_trend{t}"] = S[f"trend{t}"].shift(1)
        S[f"prev_er{t}"] = S[f"er_rest{t}"].shift(1)
    # VIX percentile over the trailing 250 sessions of the daily VIX file (previous close)
    v = pd.read_csv(os.path.join(scr, "daily_VIX.csv"), parse_dates=["day"]).sort_values("day")
    v["pct"] = v.close.rolling(250, min_periods=100).apply(lambda x: (x[:-1] < x[-1]).mean(), raw=True)
    vd = v.day.dt.date.values
    ii = np.searchsorted(vd, S.day.values, side="left") - 1
    S["vix_pct"] = v.pct.values[np.maximum(ii, 0)]
    S.to_parquet(os.path.join(scr, f"rows_{sym}_day.parquet"))
    out["day"] = len(S)
    print(sym, out, flush=True)


def daily(scr, sym):
    y = pd.read_csv(os.path.join(scr, f"daily_{sym}.csv"), parse_dates=["day"]).sort_values("day")
    y["rng"] = (y.high - y.low) / y.close.shift(1)
    y = y[(y.rng > 0) & (y.rng < 0.15)].copy()          # Yahoo bad ticks: drop H<=L and >15% rows (3 rows; a listed deviation)
    y["lr"] = np.log(y.rng)
    v = pd.read_csv(os.path.join(scr, "daily_VIX.csv"), parse_dates=["day"])[["day", "close"]].rename(columns={"close": "vix"})
    y = y.merge(v, on="day", how="left")
    y["vix"] = y.vix.ffill()
    d = pd.DataFrame({"day": y.day.dt.date.shift(-1), "feat_day": y.day.dt.date})   # forecast for the NEXT session
    d["y"] = y.lr.shift(-1).values
    d["lr1"] = y.lr.values
    d["lr5"] = y.lr.rolling(5).mean().values
    d["lr22"] = y.lr.rolling(22).mean().values
    d["b20"] = y.lr.rolling(20).mean().values
    d["lvix"] = np.log(y.vix.values)
    d["absret"] = np.abs(np.log(y.close / y.close.shift(1))).values
    nd = pd.to_datetime(d.day)
    d["wd"] = nd.dt.weekday.values
    d["mexp_week"] = (nd.dt.day >= 22).astype(float).values
    d = d.dropna(subset=["day"]).reset_index(drop=True)
    d.to_parquet(os.path.join(scr, f"rows_{sym}_daily.parquet"))
    print(sym, "daily rows", len(d), d.day.min(), d.day.max())


def main():
    scr = sys.argv[1]
    for sym in ("BANKNIFTY", "NIFTY"):
        daily(scr, sym)
        intraday(scr, sym)


if __name__ == "__main__":
    main()
