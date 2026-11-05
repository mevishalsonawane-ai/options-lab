"""R146 features (PREREG 4) and targets (PREREG 3). Pure functions, no I/O.

Minute features of a night read ONLY the entry day's rows up to 15:19 (the 15:19 bar's close) and earlier sessions:
`m_features` blanks every later row of the entry day itself before reading it, so a future value cannot leak in even
by mistake (tests/test_r146.py checks this by scrambling the future). Targets are computed separately (`m_targets`).
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

from . import cfg as K

C_, O_, H_, L_ = K.C, K.O, K.H, K.L
MIN_BARS = 30

M_COLS = ["m_move", "m_loc", "m_l30", "m_l60", "m_oc", "m_gap0", "m_brd", "m_vix", "m_dvix", "m_bu", "m_buz", "m_pcr",
          "m_dpcr", "m_ivx", "m_dte", "m_exp", "m_post", "m_wkend", "m_us_ev", "m_in_ev", "m_n225", "m_hsi", "m_ks11",
          "m_spx_prev", "m_dow0", "m_dow1", "m_dow2", "m_dow3", "m_dow4"]
D_COLS = ["d_ret", "d_loc", "d_gap0", "d_oc", "d_ret5", "d_rng", "d_vix", "d_dvix", "d_n225", "d_hsi", "d_ks11",
          "d_spx_prev", "d_fx", "d_wkend", "d_dow0", "d_dow1", "d_dow2", "d_dow3", "d_dow4"]


# ================================================================================================ small helpers
def last_close(a: np.ndarray, upto: int = K.N - 1) -> float:
    """The last finite close at or before row `upto` (NaN if none)."""
    c = a[C_, : upto + 1]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if ok.size else np.nan


def first_open(a: np.ndarray, lo: int = 0, hi: int = 4) -> float:
    o = a[O_, lo: hi + 1]
    ok = np.flatnonzero(np.isfinite(o))
    return float(o[ok[0]]) if ok.size else np.nan


def valid_day(a: np.ndarray) -> bool:
    return int(np.isfinite(a[C_]).sum()) >= MIN_BARS


def truncate(a: np.ndarray, upto: int = K.R1519) -> np.ndarray:
    """A copy with every row after `upto` blanked (the look-ahead wall)."""
    b = np.array(a, dtype=np.float64, copy=True)
    b[..., upto + 1:] = np.nan
    return b


def atm(x: float, step: float) -> float:
    return float(np.floor(x / step + 0.5) * step)


def build_up(opt: dict, x: float, step: float) -> float:
    """NightRules.buildUp over ATM +-2 at 15:19 (first bar = 09:15 row 0). NaN when the OI total is not positive."""
    k0 = atm(x, step)
    num, den = 0.0, 0.0
    for i in range(-2, 3):
        k = k0 + i * step
        c, p = opt.get(("C", k)), opt.get(("P", k))
        if c is None or p is None:
            continue
        v = [c[3, 0], c[3, K.R1519], c[5, 0], c[5, K.R1519], p[3, 0], p[3, K.R1519], p[5, 0], p[5, K.R1519]]
        if all(np.isfinite(v)):
            num += np.sign(v[1] - v[0]) * abs(v[3] - v[2]) - np.sign(v[5] - v[4]) * abs(v[7] - v[6])
        if np.isfinite(v[3]) and np.isfinite(v[7]):
            den += v[3] + v[7]
    return num / den if den > 0 else np.nan


def zscore(value: float, history: list) -> float:
    """NightRules.z: against the last 60 finite values; NaN under 20 or no spread."""
    h = [v for v in history if np.isfinite(v)][-K.Z_WINDOW:]
    if len(h) < K.Z_MIN or not np.isfinite(value):
        return np.nan
    sd = float(np.std(h, ddof=1))
    return (value - float(np.mean(h))) / sd if sd > 0 else np.nan


def _last_oi(a: np.ndarray, upto: int) -> float:
    v = a[5, : upto + 1]
    ok = np.flatnonzero(np.isfinite(v))
    return float(v[ok[-1]]) if ok.size else np.nan


def _first_oi(a: np.ndarray) -> float:
    v = a[5]
    ok = np.flatnonzero(np.isfinite(v))
    return float(v[ok[0]]) if ok.size else np.nan


def pcr(opt: dict, x: float, step: float, upto: int = K.R1519, first: bool = False) -> float:
    """Put OI / call OI over ATM +-5 (ATM from x): at `upto` (last OI so far), or at each contract's first bar."""
    k0 = atm(x, step)
    cs, ps = 0.0, 0.0
    for i in range(-5, 6):
        k = k0 + i * step
        c, p = opt.get(("C", k)), opt.get(("P", k))
        if c is None or p is None:
            continue
        a, b = (_first_oi(c), _first_oi(p)) if first else (_last_oi(c, upto), _last_oi(p, upto))
        if np.isfinite(a) and np.isfinite(b):
            cs += a
            ps += b
    return ps / cs if cs > 0 else np.nan


def straddle(opt: dict, x: float, step: float) -> float:
    """(ATM call + ATM put close at 15:19, last close within 5 minutes) / x."""
    k = atm(x, step)
    c, p = opt.get(("C", k)), opt.get(("P", k))
    if c is None or p is None:
        return np.nan
    cc, pc = c[3, K.R1519 - 5: K.R1519 + 1], p[3, K.R1519 - 5: K.R1519 + 1]
    cc, pc = cc[np.isfinite(cc)], pc[np.isfinite(pc)]
    if not cc.size or not pc.size:
        return np.nan
    return float(cc[-1] + pc[-1]) / x


def day_ret(df: pd.DataFrame | None, d: date) -> float:
    """Close-to-close log return of a daily series on date d (0 when that market did not trade on d)."""
    if df is None or d not in df.index:
        return 0.0
    i = df.index.get_loc(d)
    if i == 0:
        return 0.0
    return float(np.log(df["close"].iloc[i] / df["close"].iloc[i - 1]))


def prev_ret(df: pd.DataFrame | None, d: date) -> float:
    """The return of the last session dated strictly before d (NaN if none)."""
    if df is None:
        return np.nan
    i = int(np.searchsorted(np.array(df.index, dtype=object), d)) - 1   # index sorted ascending
    if i < 1:
        return np.nan
    return float(np.log(df["close"].iloc[i] / df["close"].iloc[i - 1]))


def dow(d: date, prefix: str) -> dict:
    return {f"{prefix}_dow{i}": float(d.weekday() == i) for i in range(5)}


# ================================================================================================ minute (M)
def m_features(und: str, d: date, nd: date, a_today: np.ndarray, prev_close_: float, opt_today: dict,
               others: dict, vix_today: np.ndarray | None, vix_prev: float, bu_hist: list, sessions: list,
               expiries: set, us_ev: set, in_ev: set, asia: dict, spx: pd.DataFrame | None) -> dict:
    """Every M feature of the night d -> nd (PREREG 4). `others`: {und: (today (4,375), previous close)} of the
    breadth indices (this index included or not; it is added). Reads nothing after 15:19 of d."""
    step = K.STEP[und]
    a = truncate(a_today)
    opt = {k: truncate(v) for k, v in opt_today.items()}
    x = a[C_, K.R1519]
    if not np.isfinite(x):
        x = last_close(a, K.R1519) if np.isfinite(a[C_, K.R1519 - 3: K.R1519 + 1]).any() else np.nan
    hi, lo = np.nanmax(a[H_, : K.R1519 + 1]), np.nanmin(a[L_, : K.R1519 + 1])
    o0 = first_open(a)
    f = {"x": x}
    f["m_move"] = x / prev_close_ - 1
    f["m_loc"] = (x - lo) / (hi - lo) if hi > lo else 0.5
    c30, c60 = last_close(a, K.R1449), last_close(a, K.R1419)
    f["m_l30"] = x / c30 - 1
    f["m_l60"] = x / c60 - 1
    f["m_oc"] = x / o0 - 1
    f["m_gap0"] = o0 / prev_close_ - 1
    signs = [np.sign(f["m_move"])]
    for u, (b, pc) in others.items():
        if u == und or b is None:
            continue
        y = last_close(truncate(b), K.R1519)
        if np.isfinite(y) and np.isfinite(pc):
            signs.append(np.sign(y / pc - 1))
    f["m_brd"] = float(np.mean(signs))
    if vix_today is not None:
        v = last_close(truncate(vix_today), K.R1519)
        f["m_vix"] = v
        f["m_dvix"] = float(np.log(v / vix_prev)) if np.isfinite(vix_prev) and vix_prev > 0 else np.nan
    else:
        f["m_vix"] = f["m_dvix"] = np.nan
    bu = build_up(opt, x, step) if np.isfinite(x) else np.nan
    f["m_bu"] = bu
    f["m_buz"] = zscore(bu, bu_hist)
    p1 = pcr(opt, x, step) if np.isfinite(x) else np.nan
    p0 = pcr(opt, x, step, first=True) if np.isfinite(x) else np.nan
    f["m_pcr"] = p1
    f["m_dpcr"] = p1 - p0
    f["m_ivx"] = straddle(opt, x, step) if np.isfinite(x) else np.nan
    nxt_exp = min((e for e in expiries if e >= d), default=None)
    f["m_dte"] = float(sum(1 for s in sessions if d < s <= nxt_exp)) if nxt_exp else np.nan
    f["m_exp"] = float(d in expiries)
    prev_s = max((s for s in sessions if s < d), default=None)
    f["m_post"] = float(prev_s in expiries) if prev_s else 0.0
    f["m_wkend"] = float((nd - d).days > 1)
    f["m_us_ev"] = float(nd in us_ev)
    f["m_in_ev"] = float(nd in in_ev)
    for k in ("n225", "hsi", "ks11"):
        f[f"m_{k}"] = day_ret(asia.get(k.upper()), d)
    f["m_spx_prev"] = prev_ret(spx, d)
    f.update(dow(d, "m"))
    return f


def m_targets(a_today: np.ndarray, a_next: np.ndarray, x: float) -> dict:
    """g = ln(next 09:15 open / x); g29 from the 15:29 close (descriptive)."""
    o = first_open(a_next, 0, 4)
    c29 = last_close(a_today)
    return {"g": float(np.log(o / x)) if np.isfinite(o) and np.isfinite(x) else np.nan,
            "g29": float(np.log(o / c29)) if np.isfinite(o) and np.isfinite(c29) else np.nan}


# ================================================================================================ daily (D)
def clean_daily(df: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """PREREG 1 data check: drop inconsistent bars, then years with > 5% opens equal to the previous close."""
    df = df.dropna(subset=["open", "high", "low", "close"])
    bad = (df.high < df[["open", "close"]].max(axis=1) - 1e-9) | (df.low > df[["open", "close"]].min(axis=1) + 1e-9)
    bad |= (df[["open", "high", "low", "close"]] <= 0).any(axis=1)
    df = df[~bad]
    stale = (df.open - df.close.shift(1)).abs() < 1e-9
    yrs = pd.Series([d.year for d in df.index], index=df.index)
    share = stale.groupby(yrs).mean()
    drop_years = sorted(int(y) for y, s in share.items() if s > K.STALE_OPEN_MAX)
    keep = ~yrs.isin(drop_years)
    return df[keep], {"inconsistent_bars": int(bad.sum()), "stale_open_years": drop_years,
                      "stale_share_by_year": {int(k): round(float(v), 4) for k, v in share.items()}}


def d_frame(ix: pd.DataFrame, vix: pd.DataFrame | None, asia: dict, spx: pd.DataFrame | None,
            fx: pd.DataFrame | None, end: date = K.D_END) -> pd.DataFrame:
    """One row per index session t with D features (the day's own bar: the 15:30 caveat), the target g =
    ln(O_{t+1} / C_t), and ov_spx (the S&P session dated t: arrives overnight; never a predictor)."""
    ix = ix[ix.index <= end]
    dates = list(ix.index)
    o, h, l_, c = (ix[k].to_numpy(float) for k in ("open", "high", "low", "close"))
    rows = []
    for i in range(5, len(dates) - 1):
        t, nt = dates[i], dates[i + 1]
        r = {"d": t, "nd": nt}
        r["d_ret"] = np.log(c[i] / c[i - 1])
        r["d_loc"] = (c[i] - l_[i]) / (h[i] - l_[i]) if h[i] > l_[i] else 0.5
        r["d_gap0"] = np.log(o[i] / c[i - 1])
        r["d_oc"] = np.log(c[i] / o[i])
        r["d_ret5"] = np.log(c[i] / c[i - 5])
        r["d_rng"] = np.log(h[i] / l_[i])
        if vix is not None and t in vix.index:
            j = vix.index.get_loc(t)
            r["d_vix"] = float(vix.close.iloc[j])
            r["d_dvix"] = float(np.log(vix.close.iloc[j] / vix.close.iloc[j - 1])) if j > 0 else np.nan
        else:
            r["d_vix"] = r["d_dvix"] = np.nan
        for k in ("n225", "hsi", "ks11"):
            r[f"d_{k}"] = day_ret(asia.get(k.upper()), t)
        r["d_spx_prev"] = prev_ret(spx, t)
        r["d_fx"] = prev_ret(fx, t)
        r["d_wkend"] = float((nt - t).days > 1)
        r.update(dow(t, "d"))
        r["g"] = np.log(o[i + 1] / c[i])
        r["ov_spx"] = day_ret(spx, t)
        rows.append(r)
    return pd.DataFrame(rows)
