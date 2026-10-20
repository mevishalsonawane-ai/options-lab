"""R88 candles, targets and factors (PREREG sections 2 and 5).

Every factor of the candle that starts at minute t0 is computed from rows strictly before t0 (the last completed
minute is t0 - 1); tests/test_lookahead.py checks this by deleting and perturbing every row from t0 on.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import cfg

CONT = ["F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F10", "F11", "F12", "F13", "F14", "F15", "F16",
        "F17", "F21", "F22a", "F22b"]
CAT = ["F09", "F18", "F19", "F20"]
NAMES = {
    "F01": "previous candle's move", "F02": "previous candle's size", "F03": "realised volatility (12 candles)",
    "F04": "VIX / ATM IV level (z)", "F05": "VIX / ATM IV change, 30 min", "F06": "today's gap",
    "F07": "position in the day's range", "F08": "distance from the day's average price",
    "F09": "vs the opening range", "F10": "put/call OI ratio", "F11": "put/call OI ratio change, 30 min",
    "F12": "call OI change, 30 min", "F13": "put OI change, 30 min", "F14": "IV skew (puts minus calls)",
    "F15": "implied vs realised move", "F16": "option volume surge", "F17": "call vs put volume",
    "F18": "days to expiry", "F19": "weekday", "F20": "event day (budget, RBI)", "F21": "leader index's last candle",
    "F22a": "yesterday's move", "F22b": "yesterday's range"}


def open_min(sym: str) -> int:
    return cfg.MCX_OPEN if cfg.is_mcx(sym) else cfg.NSE_OPEN


def build(mins: pd.DataFrame, tf: int, omin: int) -> pd.DataFrame:
    """Candles of tf minutes anchored at omin: ts (start), day, slot, o, h, l, c, n (minutes). A candle needs at least
    half of its minutes."""
    m = mins.assign(day=mins.ts.dt.normalize(),
                    slot=(mins.ts.dt.hour * 60 + mins.ts.dt.minute - omin) // tf)
    m = m[m.slot >= 0]
    g = m.groupby(["day", "slot"], sort=True)
    k = g.agg(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last"), n=("c", "size")).reset_index()
    k = k[k.n >= max(1, tf // 2)].reset_index(drop=True)
    k["ts"] = k.day + pd.to_timedelta(omin + k.slot * tf, unit="min")
    return k


def _slot_baseline(k: pd.DataFrame, col: str, days: int = cfg.BASE_DAYS, need: int = cfg.BASE_MIN) -> pd.Series:
    """Median of `col` at the same slot over the previous `days` sessions (>= need of them), aligned with k."""
    p = k.pivot(index="day", columns="slot", values=col)
    b = p.shift(1).rolling(days, min_periods=need).median()
    s = b.stack().rename("b").reset_index()
    return k[["day", "slot"]].merge(s, on=["day", "slot"], how="left").b.to_numpy()


def targets(k: pd.DataFrame) -> pd.DataFrame:
    k = k.copy()
    k["R"] = k.h - k.l
    k["r"] = k.c / k.c.shift(1) - 1
    k["B"] = _slot_baseline(k, "R")
    with np.errstate(divide="ignore", invalid="ignore"):
        k["S"] = np.log(k.R.where(k.R > 0) / k.B.where(k.B > 0))
        k["D"] = (k.c - k.o) / k.B.where(k.B > 0)
    k["up"] = np.where(k.c > k.o, 1.0, np.where(k.c < k.o, 0.0, np.nan))
    return k


def _asof(k_ts: pd.Series, src: pd.DataFrame, cols, lag_min: int = 1) -> pd.DataFrame:
    """Values of src (ts + cols) at the last minute <= k_ts - lag_min, same session only (NaN otherwise)."""
    q = pd.DataFrame({"q": k_ts - pd.Timedelta(minutes=lag_min), "i": np.arange(len(k_ts))}).sort_values("q")
    s = src[["ts", *cols]].dropna(subset=list(cols), how="all").sort_values("ts").rename(columns={"ts": "src_ts"})
    m = pd.merge_asof(q, s, left_on="q", right_on="src_ts", direction="backward")
    bad = m.src_ts.isna() | (m.src_ts.dt.normalize() != m.q.dt.normalize())
    m.loc[bad, list(cols)] = np.nan
    return m.sort_values("i")[list(cols)].reset_index(drop=True)


def _daily(mins: pd.DataFrame) -> pd.DataFrame:
    d = mins.assign(day=mins.ts.dt.normalize()).groupby("day").agg(o=("o", "first"), h=("h", "max"), l=("l", "min"),
                                                                   c=("c", "last"))
    d["prev_c"] = d.c.shift(1)
    d["ret"] = d.c / d.prev_c - 1
    d["rng"] = (d.h - d.l) / d.prev_c
    d["rng_rel"] = d.rng / d.rng.shift(1).rolling(cfg.BASE_DAYS, min_periods=cfg.BASE_MIN).median()
    return d


def dte(days, expiries: set) -> pd.Series:
    """Trading sessions from each day to the next expiry on or after it (0 = expiry day); NaN if none known."""
    days = sorted(pd.Series(days).dt.date.unique())
    exp = sorted(e for e in expiries)
    out, j = {}, 0
    pos = {d: i for i, d in enumerate(days)}
    for d in days:
        while j < len(exp) and exp[j] < d:
            j += 1
        if j < len(exp) and exp[j] in pos:
            out[d] = pos[exp[j]] - pos[d]
        else:
            out[d] = np.nan
    return pd.Series(out)


def dte_cat(x: float):
    if np.isnan(x):
        return np.nan
    return "0" if x == 0 else "1" if x == 1 else "2" if x == 2 else "3-5" if x <= 5 else "6+"


def factors(sym: str, tf: int, mins: pd.DataFrame, chain: pd.DataFrame, vix: pd.DataFrame | None, expiries: set,
            leader: pd.DataFrame | None = None, events: set | None = None) -> pd.DataFrame:
    """Candles of tf minutes with targets (S, D, up, R, B, r) and factors F01-F22b known before each candle."""
    om = open_min(sym)
    k = targets(build(mins, tf, om))
    t0 = k.ts
    # F01-F03: the candles before
    k["F01"] = k.D.shift(1)
    k["F02"] = k.S.shift(1)
    k["rv"] = k.r.rolling(cfg.RV_N, min_periods=cfg.RV_N // 2).std().shift(1)
    k["rv_b"] = _slot_baseline(k, "rv")
    k["F03"] = k.rv / k.rv_b
    # minute state at t0 - 1
    mm = mins.sort_values("ts").assign(day=lambda x: x.ts.dt.normalize())
    mm["dh"] = mm.groupby("day").h.cummax()
    mm["dl"] = mm.groupby("day").l.cummin()
    mm["twap"] = mm.groupby("day").c.transform(lambda x: x.expanding().mean())
    st = _asof(t0, mm, ["c", "dh", "dl", "twap"])
    with np.errstate(divide="ignore", invalid="ignore"):
        k["F07"] = ((st.c - st.dl) / (st.dh - st.dl).where(st.dh > st.dl)).to_numpy()
        k["F08"] = ((st.c - st.twap) / k.B.where(k.B > 0).to_numpy()).to_numpy()
    # F09: opening range (first OR_MIN minutes)
    orr = mm[(mm.ts.dt.hour * 60 + mm.ts.dt.minute) < om + cfg.OR_MIN].groupby("day").agg(orh=("h", "max"),
                                                                                       orl=("l", "min"))
    o2 = k[["day"]].join(orr, on="day")
    after = (t0.dt.hour * 60 + t0.dt.minute) >= om + cfg.OR_MIN
    k["F09"] = np.where(~after | st.c.isna().to_numpy() | o2.orh.isna().to_numpy(), None,
                        np.where(st.c.to_numpy() > o2.orh.to_numpy(), "above",
                                 np.where(st.c.to_numpy() < o2.orl.to_numpy(), "below", "inside")))
    # F06, F22: days
    dly = _daily(mins)
    # the day's open is part of its first candle, so the gap is known (strictly before) from the second candle on
    k["F06"] = k.day.map(dly.o / dly.prev_c - 1).where(k.day.eq(k.day.shift(1)))
    k["F22a"] = k.day.map(dly.ret.shift(1))
    k["F22b"] = k.day.map(dly.rng_rel.shift(1))
    # F04, F05: VIX (indices) or ATM IV (MCX)
    vol_src = (vix[["ts", "c"]].rename(columns={"c": "v"}) if vix is not None
               else chain[["ts", "atm_iv"]].rename(columns={"atm_iv": "v"}))
    v_now = _asof(t0, vol_src, ["v"]).v
    v_30 = _asof(t0, vol_src, ["v"], lag_min=31).v
    vd = vol_src.dropna().assign(day=lambda x: x.ts.dt.normalize()).groupby("day").v.last()
    mu = vd.shift(1).rolling(cfg.VIX_DAYS, min_periods=20).mean()
    sd = vd.shift(1).rolling(cfg.VIX_DAYS, min_periods=20).std()
    k["F04"] = ((v_now.to_numpy() - k.day.map(mu).to_numpy()) / k.day.map(sd).to_numpy())
    k["F05"] = (v_now - v_30).to_numpy()
    # F10-F15: option chain at t0 - 1 and 30 minutes earlier
    cols = ["pcr", "oi_c", "oi_p", "skew", "straddle", "spot"]
    c_now = _asof(t0, chain, cols)
    c_30 = _asof(t0, chain, cols, lag_min=31)
    k["F10"] = c_now.pcr.to_numpy()
    k["F11"] = (c_now.pcr - c_30.pcr).to_numpy()
    with np.errstate(divide="ignore", invalid="ignore"):
        k["F12"] = (c_now.oi_c / c_30.oi_c.where(c_30.oi_c > 0) - 1).to_numpy()
        k["F13"] = (c_now.oi_p / c_30.oi_p.where(c_30.oi_p > 0) - 1).to_numpy()
        k["F15"] = ((c_now.straddle / c_now.spot) / k.rv.where(k.rv > 0).to_numpy()).to_numpy()
    k["F14"] = c_now["skew"].to_numpy()
    # F16, F17: option volume during the previous candle (same session)
    ch = chain.assign(day=chain.ts.dt.normalize(),
                      slot=(chain.ts.dt.hour * 60 + chain.ts.dt.minute - om) // tf)
    cv = ch.groupby(["day", "slot"]).agg(vol=("vol", "sum"), vol_c=("vol_c", "sum"), vol_p=("vol_p", "sum")).reset_index()
    kv = k[["day", "slot"]].merge(cv, on=["day", "slot"], how="left")
    k["vol"] = kv.vol.to_numpy()
    k["vol_b"] = _slot_baseline(k.assign(vol=k.vol), "vol")
    same = k.day.eq(k.day.shift(1))
    with np.errstate(divide="ignore", invalid="ignore"):
        k["F16"] = (k.vol / k.vol_b.where(k.vol_b > 0)).shift(1).where(same)
        k["F17"] = np.log((kv.vol_c.to_numpy() + 1) / (kv.vol_p.to_numpy() + 1))
    k["F17"] = pd.Series(k.F17).shift(1).where(same).to_numpy()
    # F18-F20: calendar
    d = dte(k.day, expiries)
    k["dte"] = k.day.dt.date.map(d)
    k["F18"] = k.dte.map(dte_cat)
    k["F19"] = k.day.dt.day_name().str[:3]
    k["F20"] = None if cfg.is_mcx(sym) else np.where(k.day.dt.date.isin(events or set()), "event", "normal")
    # F21: the leader's previous candle return (bps)
    if leader is not None:
        lr = leader[["ts", "r"]].assign(lr=lambda x: x.r.shift(1) * 1e4)[["ts", "lr"]]
        k["F21"] = k[["ts"]].merge(lr, on="ts", how="left").lr.to_numpy()
    else:
        k["F21"] = np.nan
    k["sym"], k["tf"] = sym, tf
    return k


CONC = ["C_div", "C_dpcr", "C_doic", "C_doip", "C_volimb", "C_dvix", "C_lead"]
CONC_NAMES = {"C_div": "ATM IV change", "C_dpcr": "put/call OI ratio change", "C_doic": "call OI change %",
              "C_doip": "put OI change %", "C_volimb": "call vs put option volume", "C_dvix": "VIX change",
              "C_lead": "leader index's same candle"}


def concurrent(k: pd.DataFrame, chain: pd.DataFrame, vix: pd.DataFrame | None, leader: pd.DataFrame | None,
               tf: int) -> pd.DataFrame:
    """Part B: what changes DURING each candle (from its last minute before it to its own last minute). These are
    not known before the candle and are never used as predictors."""
    t0, t1 = k.ts, k.ts + pd.Timedelta(minutes=tf)
    cols = ["atm_iv", "pcr", "oi_c", "oi_p"]
    a, b = _asof(t0, chain, cols), _asof(t1, chain, cols)
    same = (t1 - pd.Timedelta(minutes=1)).dt.normalize().to_numpy() == t0.dt.normalize().to_numpy()
    out = pd.DataFrame(index=k.index)
    with np.errstate(divide="ignore", invalid="ignore"):
        out["C_div"] = (b.atm_iv - a.atm_iv).to_numpy()
        out["C_dpcr"] = (b.pcr - a.pcr).to_numpy()
        out["C_doic"] = (b.oi_c / a.oi_c.where(a.oi_c > 0) - 1).to_numpy()
        out["C_doip"] = (b.oi_p / a.oi_p.where(a.oi_p > 0) - 1).to_numpy()
    om = open_min(str(k.sym.iloc[0])) if len(k) else cfg.NSE_OPEN
    ch = chain.assign(day=chain.ts.dt.normalize(), slot=(chain.ts.dt.hour * 60 + chain.ts.dt.minute - om) // tf)
    cv = ch.groupby(["day", "slot"]).agg(vol_c=("vol_c", "sum"), vol_p=("vol_p", "sum")).reset_index()
    kv = k[["day", "slot"]].merge(cv, on=["day", "slot"], how="left")
    out["C_volimb"] = np.log((kv.vol_c.to_numpy() + 1) / (kv.vol_p.to_numpy() + 1))
    if vix is not None:
        va, vb = _asof(t0, vix[["ts", "c"]], ["c"]).c, _asof(t1, vix[["ts", "c"]], ["c"]).c
        out["C_dvix"] = (vb - va).to_numpy()
    else:
        out["C_dvix"] = np.nan
    out["C_lead"] = (k[["ts"]].merge(leader[["ts", "r"]], on="ts", how="left").r.to_numpy() * 1e4
                     if leader is not None else np.nan)
    out.loc[~same, CONC] = np.nan
    return out
