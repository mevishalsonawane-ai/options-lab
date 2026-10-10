"""R2 library: MCX daily/minute loaders, US series alignment, costs, stats."""
from __future__ import annotations
import datetime as dt
from pathlib import Path
import numpy as np, pandas as pd

S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad")
H = S / "hunt"
R2 = H / "r2"; D = R2 / "data"; OUT = R2 / "out"; OUT.mkdir(parents=True, exist_ok=True)
HOLD_START = pd.Timestamp("2025-10-01"); HOLD_END = pd.Timestamp("2026-10-06")

COMS = ["CRUDE", "NATGAS", "GOLD", "SILVER"]
MCX_SYM = {"CRUDE": "CRUDEOIL", "NATGAS": "NATURALGAS", "GOLD": "GOLDM", "SILVER": "SILVERM"}
US_SYM = {"CRUDE": "CL_F", "NATGAS": "NG_F", "GOLD": "GC_F", "SILVER": "SI_F"}
# mini futures for Rs P&L: units per price-unit of the MCX quote
MINI = {"CRUDE": ("CRUDEOILM", 10), "NATGAS": ("NATGASMINI", 250), "GOLD": ("GOLDTEN", 1), "SILVER": ("SILVERMIC", 1)}
OPT_MULT = {"CRUDE": 100, "NATGAS": 1250, "GOLD": 10, "SILVER": 5}
OPT_SPREAD = {"CRUDE": (0.0030, 0.0060), "NATGAS": (0.0030, 0.0060), "GOLD": (0.0040, 0.0080), "SILVER": (0.0040, 0.0080)}
FUT_SPREAD = 0.0003


def fut_charges(buy_rs, sell_rs):
    brok = np.minimum(20, 0.0003 * buy_rs) + np.minimum(20, 0.0003 * sell_rs)
    ctt = 0.0001 * sell_rs; txn = 0.000021 * (buy_rs + sell_rs); sebi = 1e-6 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs; gst = 0.18 * (brok + txn + sebi)
    return brok + ctt + txn + sebi + stamp + gst


def opt_charges(buy_rs, sell_rs):
    brok = 40.0; ctt = 0.0005 * sell_rs; txn = 0.000418 * (buy_rs + sell_rs); sebi = 1e-6 * (buy_rs + sell_rs)
    stamp = 0.00003 * buy_rs; gst = 0.18 * (brok + txn + sebi)
    return brok + ctt + txn + sebi + stamp + gst


def fut_pnl(com, side, p_in, p_out):
    """1 mini lot, side +1/-1, MCX prices. Net Rs after Zerodha charges and 0.03% full spread."""
    u = MINI[com][1]
    gross = side * (p_out - p_in) * u
    notional_in, notional_out = p_in * u, p_out * u
    cost = fut_charges(notional_in, notional_out) + FUT_SPREAD * 0.5 * (notional_in + notional_out)
    return gross, gross - cost


def load_mcx_daily(com):
    df = pd.read_parquet(H / "m1" / "data" / f"daily_{MCX_SYM[com]}.parquet").sort_index()
    df = df[(df.close > 0) & (df.high >= df.low) & (df.open > 0)].copy()
    df.index = pd.to_datetime(df.index)
    oi = df.open_interest
    roll = ((oi / oi.shift()) > 1.8) | (df.volume < 0.05 * df.volume.rolling(20, min_periods=5).median().shift())
    df["roll"] = roll
    df["gap"] = np.log(df.open / df.close.shift()); df.loc[roll, "gap"] = np.nan
    df["oc"] = np.log(df.close / df.open)
    df["ret"] = df.gap.fillna(0) + df.oc
    df["rng"] = (df.high - df.low) / df.open
    return df


def yahoo(iv):
    return pd.read_parquet(D / f"yahoo_{iv}.parquet")


def us_daily(sym):
    y = yahoo("d"); y = y[y.sym == sym].copy()
    y["date"] = y.ts.dt.normalize() if sym != "INR_X" else y.ts.dt.normalize()
    y = y.drop_duplicates("date", keep="last").set_index("date").sort_index()
    return y


def prior_us(sym, mcx_days, col="close", kind="ret"):
    """For each MCX day D: value computed from US bars dated strictly before D (all closed before 09:00 IST of D)."""
    y = us_daily(sym)[col]
    if kind == "ret":
        v = np.log(y / y.shift())
    else:
        v = y.diff()
    v = v.dropna()
    idx = np.searchsorted(v.index.values, np.asarray(mcx_days, dtype="datetime64[ns]"), side="left") - 1
    out = np.where(idx >= 0, v.values[np.clip(idx, 0, None)], np.nan)
    # stale guard: last US bar must be within 5 days
    age = np.asarray(mcx_days, dtype="datetime64[ns]") - v.index.values[np.clip(idx, 0, None)]
    out[age > np.timedelta64(5, "D")] = np.nan
    return pd.Series(out, index=mcx_days)


def stats(side, ret, pnl_net, dates, n_perm=4000, seed=7):
    """side +-1 array, ret = log return of underlying (per trade), pnl_net Rs. Random-side p (one-sided)."""
    side = np.asarray(side, float); ret = np.asarray(ret, float); m = np.isfinite(ret) & (side != 0)
    side, ret, pn, dts = side[m], ret[m], np.asarray(pnl_net, float)[m], pd.DatetimeIndex(np.asarray(dates)[m])
    n = len(ret)
    if n < 5:
        return dict(n=n)
    sr = side * ret
    rng = np.random.default_rng(seed)
    rs = rng.choice([-1.0, 1.0], size=(n_perm, n))
    null = (rs * np.abs(ret)).mean(1) if False else (rs * ret).mean(1)
    p = (np.sum(null >= sr.mean()) + 1) / (n_perm + 1)
    yrs = pd.Series(pn, index=dts).groupby(dts.year).sum()
    mos = pd.Series(pn, index=dts).groupby(dts.to_period("M")).sum()
    eq = np.cumsum(pn); dd = float(np.max(np.maximum.accumulate(np.r_[0, eq])[1:] - eq)) if n else 0
    return dict(n=n, hit=float(np.mean(sr > 0)), mean_bp=1e4 * sr.mean(), t=sr.mean() / (sr.std(ddof=1) / np.sqrt(n)),
                p_rand=float(p), net_tr=float(pn.mean()), net_tot=float(pn.sum()), maxdd=dd,
                yrs_pos=float((yrs > 0).mean()), mos_pos=float((mos > 0).mean()), n_years=len(yrs))


def bh(p):
    p = np.asarray(p, float); n = np.sum(np.isfinite(p)); q = np.full_like(p, np.nan)
    ok = np.where(np.isfinite(p))[0]; o = ok[np.argsort(p[ok])]
    ranked = p[o] * n / np.arange(1, n + 1)
    q[o] = np.minimum.accumulate(ranked[::-1])[::-1]
    return np.minimum(q, 1)


def us_dst(day) -> bool:
    """US Eastern daylight time on this date (2nd Sun Mar - 1st Sun Nov)."""
    d = pd.Timestamp(day)
    y = d.year
    mar = pd.Timestamp(y, 3, 1); s2 = mar + pd.Timedelta(days=(6 - mar.weekday()) % 7 + 7)
    nov = pd.Timestamp(y, 11, 1); s1 = nov + pd.Timedelta(days=(6 - nov.weekday()) % 7)
    return s2 <= d.normalize() < s1


def et_to_utc(day, hh, mm):
    off = 4 if us_dst(day) else 5
    return pd.Timestamp(day).normalize() + pd.Timedelta(hours=hh + off, minutes=mm)
