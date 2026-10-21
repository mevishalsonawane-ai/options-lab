"""Context of every candle (PREREG section 4): where it formed and what was around it, all known at its close.

Minute-level inputs are read only up to the candle's last minute (tl); day-level inputs only from earlier sessions.
The look-ahead guard (tests/test_lookahead.py) rebuilds every column on data cut at random minutes and checks that
nothing at or before the cut changes.
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np
import pandas as pd

from . import cfg

sys.path.insert(0, str(cfg.ROOT))
from research.brain import profile as BP  # noqa: E402

EMA_N = 50
PROFILE_BIN = {"NIFTY": 5.0, "BANKNIFTY": 10.0, "SENSEX": 10.0}


def ema(x: np.ndarray, n: int) -> np.ndarray:
    a = 2.0 / (n + 1)
    out = np.full(len(x), np.nan)
    v = np.nan
    for i, xi in enumerate(x):
        if not np.isfinite(xi):
            out[i] = v
            continue
        v = xi if not np.isfinite(v) else v + a * (xi - v)
        out[i] = v
    out[: min(n - 1, len(x))] = np.nan                     # warm-up
    return out


def trend_state(c: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """+1 up (close > EMA50 and EMA50 higher than 10 candles ago), -1 down (mirror), 0 otherwise; and the EMA."""
    e = ema(np.asarray(c, float), EMA_N)
    e10 = np.r_[np.full(10, np.nan), e[:-10]]
    with np.errstate(invalid="ignore"):
        st = np.where((c > e) & (e > e10), 1, np.where((c < e) & (e < e10), -1, 0))
    st = np.where(np.isfinite(e10), st, 0)
    return st.astype(int), e


def rolling_pos(h, l, c, n=50) -> np.ndarray:
    """Close position in the high-low range of the last n candles (this one included): 0 bottom, 1 top."""
    hh = pd.Series(h).rolling(n, min_periods=n).max().to_numpy()
    ll = pd.Series(l).rolling(n, min_periods=n).min().to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(hh > ll, (np.asarray(c) - ll) / (hh - ll), np.nan)


def tod_bucket(slot: np.ndarray) -> np.ndarray:
    starts = np.array([s for s, _ in cfg.TOD])
    names = np.array([n for _, n in cfg.TOD])
    return names[np.searchsorted(starts, slot, side="right") - 1]


def vol_regime(atr: np.ndarray, n=100) -> np.ndarray:
    med = pd.Series(atr).shift(1).rolling(n, min_periods=n // 2).median().to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        r = atr / med
    return np.where(~np.isfinite(r), "na", np.where(r > 1.3, "high", np.where(r < 0.8, "low", "normal")))


# ------------------------------------------------------------------------------------------------ minute level
def minute_context(m: pd.DataFrame, und: str) -> pd.DataFrame:
    """m: minutes ts o h l c [act coi poi gex_ratio]. Adds day, vwap (activity-weighted typical price so far; time-weighted
    when no activity is known that day: the index has no volume of its own), dhi / dlo (day high / low BEFORE this
    minute), orh / orl (09:15-09:29 range, NaN before 09:30), pdh pdl pdc, gap, prior-day profile pd_poc pd_vah pd_val."""
    m = m.sort_values("ts").reset_index(drop=True).copy()
    m["day"] = m.ts.dt.normalize()
    tp = (m.h + m.l + m.c) / 3
    act = m["act"].fillna(0).clip(lower=0) if "act" in m else pd.Series(0.0, index=m.index)
    g = m.groupby("day")
    cw = act.groupby(m.day).cumsum()
    cpv = (tp * act).groupby(m.day).cumsum()
    ctw = tp.groupby(m.day).cumsum() / (g.cumcount() + 1)
    m["vwap"] = np.where(cw > 0, cpv / cw.replace(0, np.nan), ctw)
    m["dhi"] = g.h.cummax().groupby(m.day).shift(1)
    m["dlo"] = g.l.cummin().groupby(m.day).shift(1)
    r = (m.ts.dt.hour * 60 + m.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
    orm = m[r < 15].groupby("day").agg(orh=("h", "max"), orl=("l", "min"))
    m = m.join(orm, on="day")
    m.loc[r < 15, ["orh", "orl"]] = np.nan
    dd = g.agg(dh=("h", "max"), dl=("l", "min"), dc=("c", "last"), do=("o", "first"))
    prof = day_profiles(m, und)
    dd = dd.join(prof)
    prev = dd.shift(1).rename(columns=dict(dh="pdh", dl="pdl", dc="pdc", do="_pdo", poc="pd_poc", vah="pd_vah",
                                           val="pd_val"))
    prev["gap"] = dd["do"] / prev["pdc"] - 1
    m = m.join(prev[["pdh", "pdl", "pdc", "gap", "pd_poc", "pd_vah", "pd_val"]], on="day")
    return m


def day_profiles(m: pd.DataFrame, und: str) -> pd.DataFrame:
    """Each session's final activity-weighted profile (POC, VAH, VAL), research.brain.profile's method with option
    activity in place of futures volume (an estimate, labelled). Valid only from the next session."""
    bs = PROFILE_BIN.get(und, 10.0)
    rows = {}
    if "act" not in m:
        return pd.DataFrame(columns=["poc", "vah", "val"])
    for d, x in m.groupby("day"):
        v = x["act"].to_numpy(float)
        if not np.isfinite(v).any() or np.nansum(v) <= 0:
            continue
        b0, H = BP.spread(x.l.to_numpy(), x.h.to_numpy(), x.c.to_numpy(), np.nan_to_num(v), bs)
        if H.shape[1] == 0:
            continue
        tot = H.sum(0)[None, :]
        poc, lo, hi = BP.poc_va(tot)
        if poc[0] < 0:
            continue
        rows[d] = dict(poc=(b0 + poc[0] + 0.5) * bs, val=(b0 + lo[0]) * bs, vah=(b0 + hi[0] + 1) * bs)
    return pd.DataFrame.from_dict(rows, orient="index")


# ------------------------------------------------------------------------------------------------ candle level
def candle_context(k: pd.DataFrame, m: pd.DataFrame, expiries: set) -> pd.DataFrame:
    """k: candles with anatomy (ts, tl, day, slot, o h l c, atr, act); m: minute_context output (with act, coi, poi,
    gex_ratio when known). Returns k with the context columns."""
    k = k.copy()
    c, h, l = k.c.to_numpy(float), k.h.to_numpy(float), k.l.to_numpy(float)
    k["trend"], k["ema50"] = trend_state(c)
    k["pos50"] = rolling_pos(h, l, c, 50)
    with np.errstate(invalid="ignore", divide="ignore"):
        k["ret50_before"] = (c - pd.Series(c).shift(50).to_numpy()) / k.atr.to_numpy()
    k["tod"] = tod_bucket(k.slot.to_numpy())
    k["volreg"] = vol_regime(k.atr.to_numpy())
    # minute values at the candle's last minute
    mm = m.set_index("ts")
    cols = [x for x in ("vwap", "pdh", "pdl", "pdc", "gap", "orh", "orl", "pd_poc", "pd_vah", "pd_val", "coi", "poi",
                        "gex_ratio") if x in mm]
    at = mm.reindex(k.tl)[cols].reset_index(drop=True)
    at.index = k.index
    for x in ("vwap", "pdh", "pdl", "pdc", "gap", "orh", "orl", "pd_poc", "pd_vah", "pd_val", "coi", "poi",
              "gex_ratio"):
        k[x] = at[x].to_numpy(float) if x in at else np.nan        # unknown (no chain that day) = NaN
    # day high / low before the candle (from candles of the same day)
    g = k.groupby("day")
    k["dhi_before"] = g.h.cummax().groupby(k.day).shift(1)
    k["dlo_before"] = g.l.cummin().groupby(k.day).shift(1)
    with np.errstate(invalid="ignore", divide="ignore"):
        k["vwap_dist"] = (c - k.vwap.to_numpy()) / k.atr.to_numpy()
        k["pcr"] = k.poi / k.coi
    # activity vs the same slot over the previous 20 sessions (median, >= 10 sessions)
    if "act" in k and np.isfinite(k["act"].to_numpy(float)).any():
        pv = k.pivot_table(index="day", columns="slot", values="act", aggfunc="first")
        usual = pv.shift(1).rolling(20, min_periods=10).median()
        u = usual.stack().rename("act_usual")
        k = k.join(u, on=["day", "slot"])
        with np.errstate(invalid="ignore", divide="ignore"):
            k["act_ratio"] = k.act / k.act_usual
    else:
        k["act_usual"] = np.nan
        k["act_ratio"] = np.nan
    # OI change over the candle (ATM-1..+1 sums; the strike set rolls with ATM, so this is noisy: labelled)
    for x in ("coi", "poi"):
        k["d" + x] = k[x] - k.groupby("day")[x].shift(1)
    k["expiry"] = k.day.dt.date.isin(expiries)
    return k


def orderflow_proxies(k: pd.DataFrame, m: pd.DataFrame) -> pd.DataFrame:
    """Order-flow proxies from the candle's own minutes (no tick data in this study): delta_share = sum(sign(minute
    close - open) x minute activity) / sum(activity) (a bar-rule delta, NOT true aggressor delta); up_wick_act /
    lo_wick_act = share of the candle's activity in minutes that traded inside its upper / lower wick."""
    k = k.copy()
    if "act" not in m or k.empty:
        k["delta_share"] = np.nan
        k["up_wick_act"] = np.nan
        k["lo_wick_act"] = np.nan
        return k
    mm = m[["ts", "o", "h", "l", "c", "act"]].copy()
    mm["day"] = mm.ts.dt.normalize()
    mm["r"] = (mm.ts.dt.hour * 60 + mm.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
    # each minute belongs to the candle of its day with the largest slot <= its row, and only up to that candle's tl
    key = pd.DataFrame(dict(day=k.day.to_numpy(), r=k.slot.to_numpy(), i=np.arange(len(k))))
    mm = pd.merge_asof(mm.sort_values("r"), key.sort_values("r"), on="r", by="day", direction="backward")
    mm = mm[mm.i.notna()].copy()
    mm["i"] = mm.i.astype(int)
    kk = k.reset_index(drop=True)
    mm = mm[mm.ts.to_numpy() <= kk.tl.to_numpy()[mm.i.to_numpy()]]
    top = np.maximum(kk.o, kk.c).to_numpy()[mm.i.to_numpy()]
    bot = np.minimum(kk.o, kk.c).to_numpy()[mm.i.to_numpy()]
    a = mm.act.fillna(0).clip(lower=0).to_numpy()
    sg = np.sign(mm.c.to_numpy() - mm.o.to_numpy())
    agg = pd.DataFrame(dict(i=mm.i.to_numpy(), a=a, d=sg * a, uw=a * (mm.h.to_numpy() > top),
                            lw=a * (mm.l.to_numpy() < bot))).groupby("i").sum()
    with np.errstate(invalid="ignore", divide="ignore"):
        den = agg.a.where(agg.a > 0)
        res = pd.DataFrame(dict(delta_share=agg.d / den, up_wick_act=agg.uw / den, lo_wick_act=agg.lw / den))
    res = res.reindex(range(len(kk)))
    for x in res:
        k[x] = res[x].to_numpy()
    return k


# ------------------------------------------------------------------------------------------------ context conditions
def conditions(k: pd.DataFrame) -> dict[str, np.ndarray]:
    """The fixed context conditions (PREREG section 4) crossed with every candle family."""
    c, h, l, o = (k[x].to_numpy(float) for x in "chlo")
    slot = k.slot.to_numpy()
    pc = k.groupby("day").c.shift(1).to_numpy()
    with np.errstate(invalid="ignore"):
        ar = k.act_ratio.to_numpy(float)
        cond = {
            "uptrend": k.trend.to_numpy() == 1,
            "downtrend": k.trend.to_numpy() == -1,
            "flat_trend": k.trend.to_numpy() == 0,
            "top_of_50": k.pos50.to_numpy() >= 0.8,
            "bottom_of_50": k.pos50.to_numpy() <= 0.2,
            "new_day_high": h > k.dhi_before.to_numpy(),
            "new_day_low": l < k.dlo_before.to_numpy(),
            "sweep_pdh": (h > k.pdh.to_numpy()) & (c < k.pdh.to_numpy()),
            "sweep_pdl": (l < k.pdl.to_numpy()) & (c > k.pdl.to_numpy()),
            "break_pdh": (c > k.pdh.to_numpy()) & (pc <= k.pdh.to_numpy()),
            "break_pdl": (c < k.pdl.to_numpy()) & (pc >= k.pdl.to_numpy()),
            "above_or": (slot >= 15) & (c > k.orh.to_numpy()),
            "below_or": (slot >= 15) & (c < k.orl.to_numpy()),
            "far_above_vwap": k.vwap_dist.to_numpy() >= 1.5,
            "far_below_vwap": k.vwap_dist.to_numpy() <= -1.5,
            "first_hour": slot < 60,
            "last_hour": slot >= cfg.row(14, 30),
            "gap_up_day": k.gap.to_numpy() >= 0.003,
            "gap_down_day": k.gap.to_numpy() <= -0.003,
            "high_activity": ar >= 1.5,
            "low_activity": ar <= 0.67,
            "reject_pd_vah": (h > k.pd_vah.to_numpy()) & (c < k.pd_vah.to_numpy()),
            "reject_pd_val": (l < k.pd_val.to_numpy()) & (c > k.pd_val.to_numpy()),
            "bull_oi_shift": (k.dpoi.to_numpy() > 0) & (k.dcoi.to_numpy() < 0),
            "bear_oi_shift": (k.dcoi.to_numpy() > 0) & (k.dpoi.to_numpy() < 0),
            "short_gamma": k.gex_ratio.to_numpy(float) < 0,
            "long_gamma": k.gex_ratio.to_numpy(float) > 0,
            "expiry_day": k.expiry.to_numpy(bool),
            "high_vol": k.volreg.to_numpy() == "high",
            "low_vol": k.volreg.to_numpy() == "low",
        }
    del o
    return {x: np.asarray(v, bool) for x, v in cond.items()}


def footprints(k: pd.DataFrame) -> dict[str, np.ndarray]:
    """'What the big players are doing' signatures (PREREG section 5), each a candle family of its own."""
    s, ar = k["size"].to_numpy(float), k.act_ratio.to_numpy(float)
    col, ds = k.col.to_numpy(), k.delta_share.to_numpy(float)
    c, h, l = (k[x].to_numpy(float) for x in "chl")
    with np.errstate(invalid="ignore"):
        f = {
            "fp_absorption": (ar >= 2.0) & (s <= 0.7),
            "fp_climax": (ar >= 2.5) & (s >= 1.5),
            "fp_stop_run_up": (h > k.dhi_before.to_numpy()) & (c < k.dhi_before.to_numpy()),
            "fp_stop_run_down": (l < k.dlo_before.to_numpy()) & (c > k.dlo_before.to_numpy()),
            "fp_heavy_lower_wick": (k.lo_pct.to_numpy() >= 0.4) & (k.lo_wick_act.to_numpy(float) >= 0.5)
                                   & (s >= 0.8),
            "fp_heavy_upper_wick": (k.up_pct.to_numpy() >= 0.4) & (k.up_wick_act.to_numpy(float) >= 0.5)
                                   & (s >= 0.8),
            "fp_delta_divergence_up": (col < 0) & (ds > 0.2),
            "fp_delta_divergence_down": (col > 0) & (ds < -0.2),
        }
    return {x: np.asarray(v, bool) for x, v in f.items()}


CONDITIONS = ("uptrend", "downtrend", "flat_trend", "top_of_50", "bottom_of_50", "new_day_high", "new_day_low",
              "sweep_pdh", "sweep_pdl", "break_pdh", "break_pdl", "above_or", "below_or", "far_above_vwap",
              "far_below_vwap", "first_hour", "last_hour", "gap_up_day", "gap_down_day", "high_activity",
              "low_activity", "reject_pd_vah", "reject_pd_val", "bull_oi_shift", "bear_oi_shift", "short_gamma",
              "long_gamma", "expiry_day", "high_vol", "low_vol")
FOOTPRINTS = ("fp_absorption", "fp_climax", "fp_stop_run_up", "fp_stop_run_down", "fp_heavy_lower_wick",
              "fp_heavy_upper_wick", "fp_delta_divergence_up", "fp_delta_divergence_down")


def expiry_set(und: str, frm: date, to: date) -> set:
    from .model import expiry_dates            # research/brain/calendar + the SENSEX Thursday fallback (A1)
    return {d for d in expiry_dates(und, frm, to) if frm <= d <= to}
