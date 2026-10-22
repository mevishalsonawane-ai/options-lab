"""R102 part A: descriptive statistics of NIFTY and BANKNIFTY (PREREG.md). "Descriptive, not a trading rule."

    python research/hunt/r102_deep/r102desc.py design      # results/desc_design.json
    python research/hunt/r102_deep/r102desc.py all         # after the holdout read: results/desc_all.json

Every table carries its sample size (n = days, bars, or contract-minutes). Numbers are rounded for the JSON only.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r102data as D  # noqa: E402

WINDOWS = {  # minute index [a, b): 09:15 -> 0
    "09:15-09:30": (0, 15), "09:30-10:30": (15, 75), "10:30-13:00": (75, 225), "13:00-14:30": (225, 315),
    "14:30-15:30": (315, 375),
}
CHECKPOINTS = {"09:30": 15, "10:30": 75, "13:00": 225, "14:30": 315}
HORIZONS_MIN = {"1m": 1, "5m": 5, "15m": 15, "1h": 60}


def r(x, nd=4):
    if x is None:
        return None
    if isinstance(x, (float, np.floating)):
        return None if not np.isfinite(x) else round(float(x), nd)
    if isinstance(x, (int, np.integer)):
        return int(x)
    return x


def stats(x: pd.Series, nd=4) -> dict:
    x = pd.Series(x).dropna()
    if len(x) == 0:
        return {"n": 0}
    return {"n": int(len(x)), "mean": r(x.mean(), nd), "median": r(x.median(), nd), "sd": r(x.std(), nd),
            "p1": r(x.quantile(0.01), nd), "p5": r(x.quantile(0.05), nd), "p25": r(x.quantile(0.25), nd),
            "p75": r(x.quantile(0.75), nd), "p95": r(x.quantile(0.95), nd), "p99": r(x.quantile(0.99), nd),
            "min": r(x.min(), nd), "max": r(x.max(), nd),
            "skew": r(x.skew(), 3), "kurt": r(x.kurt(), 3)}


def daily_frame(sym: str, part: str) -> pd.DataFrame:
    d = D.daily(sym).copy()
    d["day"] = d.index.date
    d = D.cut(d, part)
    d["ret"] = d.close.pct_change() * 100
    d["gap"] = (d.open / d.close.shift(1) - 1) * 100
    d["oc"] = (d.close / d.open - 1) * 100
    d["range"] = (d.high - d.low) / d.close.shift(1) * 100
    d["year"] = d.index.year
    d["wd"] = [D.weekday_name(x) for x in d.day]
    return d


# ---- A1 daily returns, correlation, beta -----------------------------------------------------------------------------

def a1_daily(part: str) -> dict:
    out = {}
    frames = {s: daily_frame(s, part) for s in D.UNDS + D.OTHERS}
    for s in D.UNDS:
        d = frames[s]
        by_year = {}
        for y, g in d.groupby("year"):
            st = stats(g.ret, 3)
            st["ann_vol_pct"] = r(g.ret.std() * np.sqrt(252), 2)
            st["up_share"] = r((g.ret > 0).mean(), 3)
            st["best_day"] = [str(g.ret.idxmax().date()), r(g.ret.max(), 2)] if g.ret.notna().any() else None
            st["worst_day"] = [str(g.ret.idxmin().date()), r(g.ret.min(), 2)] if g.ret.notna().any() else None
            by_year[int(y)] = st
        allp = stats(d.ret, 3)
        allp["ann_vol_pct"] = r(d.ret.std() * np.sqrt(252), 2)
        allp["up_share"] = r((d.ret > 0).mean(), 3)
        allp["first_day"], allp["last_day"] = str(d.day.min()), str(d.day.max())
        allp["abs_move_ge_1pct_share"] = r((d.ret.abs() >= 1).mean(), 3)
        allp["abs_move_ge_2pct_share"] = r((d.ret.abs() >= 2).mean(), 3)
        out[s] = {"all": allp, "by_year": by_year, "oc_all": stats(d.oc, 3), "range_all": stats(d["range"], 3)}
    # correlation and beta of daily returns, by year and all, BANKNIFTY vs the rest and NIFTY vs the rest
    corr = {}
    for a in D.UNDS:
        for b in D.UNDS + D.OTHERS:
            if a == b:
                continue
            j = frames[a][["ret"]].join(frames[b][["ret"]], lsuffix="_a", rsuffix="_b", how="inner").dropna()
            if len(j) < 30:
                continue
            rows = {"all": {"n": int(len(j)), "corr": r(j.ret_a.corr(j.ret_b), 3),
                            "beta": r(np.cov(j.ret_a, j.ret_b)[0, 1] / j.ret_b.var(), 3)}}
            for y, g in j.groupby(j.index.year):
                if len(g) >= 30:
                    rows[int(y)] = {"n": int(len(g)), "corr": r(g.ret_a.corr(g.ret_b), 3),
                                    "beta": r(np.cov(g.ret_a, g.ret_b)[0, 1] / g.ret_b.var(), 3)}
            corr[f"{a}_vs_{b}"] = rows
    out["daily_corr_beta"] = corr
    # intraday (5-minute) correlation BANKNIFTY vs NIFTY, from the minute lake
    try:
        ma = five_min(D.cut(D.minutes("BANKNIFTY"), part)); mb = five_min(D.cut(D.minutes("NIFTY"), part))
        j = ma.join(mb, lsuffix="_a", rsuffix="_b", how="inner").dropna()
        out["intraday_5m_corr_BANKNIFTY_vs_NIFTY"] = {"n_bars": int(len(j)), "corr": r(j.ret_a.corr(j.ret_b), 3),
                                                      "beta": r(np.cov(j.ret_a, j.ret_b)[0, 1] / j.ret_b.var(), 3),
                                                      "first_day": str(j.index.get_level_values(0).min()),
                                                      "last_day": str(j.index.get_level_values(0).max())}
    except Exception as e:  # pragma: no cover - only when a lake file is missing
        out["intraday_5m_corr_BANKNIFTY_vs_NIFTY"] = {"error": str(e)}
    return out


def five_min(m: pd.DataFrame) -> pd.DataFrame:
    x = m[m.m % 5 == 4][["day", "m", "close"]].copy()     # the close of every 5th minute (09:19, 09:24, ...)
    x["ret"] = x.groupby("day").close.pct_change() * 100
    return x.set_index(["day", "m"])[["ret"]]


# ---- A2 realised vs implied ------------------------------------------------------------------------------------------

def a2_vol(part: str) -> dict:
    out = {}
    vix = daily_frame("INDIAVIX", part)[["close"]].rename(columns={"close": "vix"})
    for s in D.UNDS:
        d = daily_frame(s, part).join(vix, how="left")
        d["park"] = np.sqrt((np.log(d.high / d.low) ** 2) / (4 * np.log(2))) * 100  # Parkinson daily sigma, %
        d["rv21"] = d.ret.rolling(21).std() * np.sqrt(252)
        d["fwd_rv21"] = d.ret[::-1].rolling(21).std()[::-1] * np.sqrt(252)   # the next 21 days' realised vol
        rows = {}
        for y, g in d.groupby("year"):
            g = g.dropna(subset=["ret"])
            rows[int(y)] = {"n": int(len(g)), "rv_cc_ann": r(g.ret.std() * np.sqrt(252), 2),
                            "rv_park_ann": r(g.park.mean() * np.sqrt(252), 2),
                            "vix_mean": r(g.vix.mean(), 2), "vix_min": r(g.vix.min(), 2), "vix_max": r(g.vix.max(), 2),
                            "vix_over_fwd_rv": r((g.vix / g.fwd_rv21).median(), 3) if g.fwd_rv21.notna().sum() > 20 else None}
        # VIX regime terciles (fixed cuts from the whole design period: descriptive only)
        reg = {}
        q1, q2 = d.vix.quantile([1 / 3, 2 / 3])
        d["regime"] = np.where(d.vix <= q1, "low", np.where(d.vix <= q2, "mid", "high"))
        for k, g in d.dropna(subset=["ret", "vix"]).groupby("regime"):
            reg[k] = {"n": int(len(g)), "vix_range": [r(g.vix.min(), 1), r(g.vix.max(), 1)],
                      "rv_cc_ann": r(g.ret.std() * np.sqrt(252), 2), "median_range_pct": r(g["range"].median(), 3),
                      "median_abs_ret_pct": r(g.ret.abs().median(), 3), "fwd_rv21_median": r(g.fwd_rv21.median(), 2)}
        j = d.dropna(subset=["vix", "fwd_rv21"])
        out[s] = {"by_year": rows, "vix_regime": reg, "vix_cuts": [r(q1, 2), r(q2, 2)],
                  "vix_vs_fwd_rv21_all": {"n": int(len(j)), "median_ratio": r((j.vix / j.fwd_rv21).median(), 3),
                                          "share_vix_above_fwd_rv": r((j.vix > j.fwd_rv21).mean(), 3),
                                          "corr": r(j.vix.corr(j.fwd_rv21), 3)}}
    return out


# ---- A3 intraday range timing ---------------------------------------------------------------------------------------

def a3_intraday(sym: str, part: str) -> dict:
    m = D.full_days(D.cut(D.minutes(sym), part))
    a, days = D.session_grid(m)
    n = len(days)
    if n == 0:
        return {"n_days": 0}
    hi, lo, cl, op = a[:, :, 1], a[:, :, 2], a[:, :, 3], a[:, :, 0]
    day_hi, day_lo = np.nanmax(hi, axis=1), np.nanmin(lo, axis=1)
    first_open = np.array([next((v for v in row if np.isfinite(v)), np.nan) for row in op])
    last_close = np.array([next((v for v in row[::-1] if np.isfinite(v)), np.nan) for row in cl])
    day_range = (day_hi - day_lo) / first_open * 100
    exp = D.expiries(sym)
    exp_days = set(exp[exp.is_expiry].day)
    is_exp = np.array([d in exp_days for d in days])
    wd = np.array([D.weekday_name(d) for d in days])
    years = np.array([d.year for d in days])
    cum_hi = np.fmax.accumulate(np.where(np.isfinite(hi), hi, -np.inf), axis=1)
    cum_lo = np.fmin.accumulate(np.where(np.isfinite(lo), lo, np.inf), axis=1)
    out = {"n_days": int(n), "first_day": str(days[0]), "last_day": str(days[-1]),
           "day_range_pct": stats(pd.Series(day_range), 3)}
    # share of the day's range already made by each checkpoint (median over days)
    made = {}
    for k, idx in CHECKPOINTS.items():
        share = (cum_hi[:, idx - 1] - cum_lo[:, idx - 1]) / (day_hi - day_lo)
        made[k] = {"median_share": r(np.nanmedian(share), 3), "mean_share": r(np.nanmean(share), 3),
                   "share_days_range_done": r(np.nanmean(share >= 0.999), 3)}
    out["range_made_by"] = made
    # range inside each window as % of open, and where the day's high / low fall
    hi_min = np.nanargmax(np.where(np.isfinite(hi), hi, -np.inf), axis=1)
    lo_min = np.nanargmin(np.where(np.isfinite(lo), lo, np.inf), axis=1)
    win = {}
    for k, (s0, s1) in WINDOWS.items():
        wr = (np.nanmax(hi[:, s0:s1], axis=1) - np.nanmin(lo[:, s0:s1], axis=1)) / first_open * 100
        win[k] = {"median_range_pct": r(np.nanmedian(wr), 3), "mean_range_pct": r(np.nanmean(wr), 3),
                  "share_of_day_range_median": r(np.nanmedian(wr / day_range), 3),
                  "day_high_here_share": r(np.mean((hi_min >= s0) & (hi_min < s1)), 3),
                  "day_low_here_share": r(np.mean((lo_min >= s0) & (lo_min < s1)), 3),
                  "range_per_minute_bp": r(np.nanmedian(wr) / (s1 - s0) * 100, 3)}
    out["windows"] = win
    # the 09:15 and 09:20 bars, the first 5 and 15 minutes
    r1 = (hi[:, 0] - lo[:, 0]) / first_open * 100
    out["first_minute_range_pct"] = stats(pd.Series(r1), 3)
    out["first_5min_range_pct"] = stats(pd.Series((np.nanmax(hi[:, :5], axis=1) - np.nanmin(lo[:, :5], axis=1)) / first_open * 100), 3)
    # by expiry / non-expiry and by weekday: median day range and the window shares
    def grp(mask):
        if mask.sum() == 0:
            return {"n": 0}
        g = {"n": int(mask.sum()), "median_day_range_pct": r(np.nanmedian(day_range[mask]), 3),
             "median_abs_oc_pct": r(np.nanmedian(np.abs(last_close[mask] / first_open[mask] - 1) * 100), 3)}
        for k, (s0, s1) in WINDOWS.items():
            wr = (np.nanmax(hi[mask, s0:s1], axis=1) - np.nanmin(lo[mask, s0:s1], axis=1)) / first_open[mask] * 100
            g[k] = r(np.nanmedian(wr), 3)
        return g
    out["expiry_days"], out["non_expiry_days"] = grp(is_exp), grp(~is_exp)
    out["by_weekday"] = {w: grp(wd == w) for w in ("Mon", "Tue", "Wed", "Thu", "Fri")}
    out["by_year"] = {int(y): grp(years == y) for y in sorted(set(years))}
    # high-before-low: trend-day shape; "open is the day's high or low" (first 5 minutes)
    out["open_in_first5_is_day_extreme_share"] = r(np.mean((hi_min < 5) | (lo_min < 5)), 3)
    out["high_before_low_share"] = r(np.mean(hi_min < lo_min), 3)
    # close location in the day's range
    clv = (last_close - day_lo) / (day_hi - day_lo)
    out["close_location_in_range"] = stats(pd.Series(clv), 3)
    out["close_in_top_or_bottom_10pct_share"] = r(np.nanmean((clv >= 0.9) | (clv <= 0.1)), 3)
    return out


# ---- A4 gaps ---------------------------------------------------------------------------------------------------------

def a4_gaps(sym: str, part: str) -> dict:
    d = daily_frame(sym, part).dropna(subset=["gap"]).copy()
    pc = d.close.shift(1)
    d["pc"] = pc
    d = d.dropna(subset=["pc"])
    out = {"n_days": int(len(d)), "first_day": str(d.day.min()), "last_day": str(d.day.max()),
           "gap_pct": stats(d.gap, 3),
           "abs_gap_ge": {str(t): {"share": r((d.gap.abs() >= t).mean(), 3), "n": int((d.gap.abs() >= t).sum())}
                          for t in (0.1, 0.25, 0.5, 1.0, 2.0)}}
    up, dn = d[d.gap > 0], d[d.gap < 0]
    filled_up = up.low <= up.pc          # a gap up fills when the day trades back to the previous close
    filled_dn = dn.high >= dn.pc
    out["fill_by_close_any_size"] = {"gap_up": {"n": int(len(up)), "share": r(filled_up.mean(), 3)},
                                     "gap_down": {"n": int(len(dn)), "share": r(filled_dn.mean(), 3)}}
    rows = {}
    bins = [(0.0, 0.25), (0.25, 0.5), (0.5, 1.0), (1.0, 2.0), (2.0, 99.0)]
    for lo_, hi_ in bins:
        k = f"{lo_}-{hi_}%"
        u = up[(up.gap >= lo_) & (up.gap < hi_)]
        n_ = dn[(-dn.gap >= lo_) & (-dn.gap < hi_)]
        rows[k] = {"gap_up": {"n": int(len(u)), "fill_share": r((u.low <= u.pc).mean(), 3),
                              "mean_oc_pct": r(u.oc.mean(), 3), "median_oc_pct": r(u.oc.median(), 3),
                              "close_below_prev_close_share": r((u.close < u.pc).mean(), 3)},
                   "gap_down": {"n": int(len(n_)), "fill_share": r((n_.high >= n_.pc).mean(), 3),
                                "mean_oc_pct": r(n_.oc.mean(), 3), "median_oc_pct": r(n_.oc.median(), 3),
                                "close_above_prev_close_share": r((n_.close > n_.pc).mean(), 3)}}
    out["by_size"] = rows
    # minute of the fill (from the minute lake, where it exists)
    try:
        m = D.cut(D.minutes(sym), part)
        g = m.groupby("day")
        first_fill = {}
        pcs = d.set_index("day").pc
        gaps = d.set_index("day").gap
        for day, grp in g:
            if day not in pcs.index:
                continue
            pcv, gv = pcs[day], gaps[day]
            if abs(gv) < 0.25:
                continue
            hit = grp[(grp.low <= pcv)] if gv > 0 else grp[(grp.high >= pcv)]
            first_fill[day] = int(hit.m.iloc[0]) if len(hit) else -1
        ff = pd.Series(first_fill)
        filled = ff[ff >= 0]
        out["fill_minute_abs_gap_ge_0.25"] = {"n_gaps": int(len(ff)), "filled_share": r((ff >= 0).mean(), 3),
                                              "median_minutes_after_open": r(filled.median(), 1) if len(filled) else None,
                                              "filled_by_09:30_share": r((ff.between(0, 14)).mean(), 3),
                                              "filled_by_10:30_share": r((ff.between(0, 74)).mean(), 3),
                                              "filled_by_13:00_share": r((ff.between(0, 224)).mean(), 3)}
    except Exception as e:  # pragma: no cover
        out["fill_minute_abs_gap_ge_0.25"] = {"error": str(e)}
    return out


# ---- A5 autocorrelation (descriptive) ---------------------------------------------------------------------------------

def bar_returns(m: pd.DataFrame, h: int) -> pd.Series:
    """Non-overlapping h-minute returns inside each session (bars [0,h), [h,2h) ...; the last partial bar dropped)."""
    x = m[["day", "m", "close"]].copy()
    x["bar"] = x.m // h
    x = x[x.bar < D.N_MIN // h]
    last = x.groupby(["day", "bar"]).close.last()
    rets = last.groupby(level=0).pct_change() * 100
    return rets.dropna()


def a5_autocorr(sym: str, part: str) -> dict:
    out = {}
    m = D.cut(D.minutes(sym), part)
    for k, h in HORIZONS_MIN.items():
        rr = bar_returns(m, h)
        # lag-1 autocorrelation within the session only (the pair must be in the same day)
        prev = rr.groupby(level=0).shift(1)
        j = pd.DataFrame({"r": rr, "p": prev}).dropna()
        out[k] = {"n_pairs": int(len(j)), "lag1_autocorr": r(j.r.corr(j.p), 4),
                  "follow_share": r((np.sign(j.r) == np.sign(j.p)).mean(), 4),
                  "bar_sd_pct": r(rr.std(), 4)}
    d = daily_frame(sym, part)
    rr = d.ret.dropna()
    out["1d"] = {"n_pairs": int(len(rr) - 1), "lag1_autocorr": r(rr.autocorr(1), 4),
                 "follow_share": r((np.sign(rr) == np.sign(rr.shift(1))).iloc[1:].mean(), 4), "bar_sd_pct": r(rr.std(), 4),
                 "first_day": str(d.day.min())}
    w = d.close.resample("W-FRI").last().pct_change().dropna() * 100
    out["1w"] = {"n_pairs": int(len(w) - 1), "lag1_autocorr": r(w.autocorr(1), 4),
                 "follow_share": r((np.sign(w) == np.sign(w.shift(1))).iloc[1:].mean(), 4), "bar_sd_pct": r(w.std(), 4)}
    # intraday: does the first 30 minutes' direction hold to the close?
    a, days = D.session_grid(D.full_days(m))
    if len(days):
        cl = a[:, :, 3]
        op0 = np.array([next((v for v in row if np.isfinite(v)), np.nan) for row in a[:, :, 0]])
        c30 = cl[:, 29]; c60 = cl[:, 59]; cend = np.array([next((v for v in row[::-1] if np.isfinite(v)), np.nan) for row in cl])
        for lab, c in (("first_30min", c30), ("first_hour", c60)):
            ok = np.isfinite(c) & np.isfinite(op0) & np.isfinite(cend) & (c != op0)
            same = np.sign(cend[ok] - c[ok]) == np.sign(c[ok] - op0[ok])
            out[f"{lab}_direction_continues_to_close"] = {"n_days": int(ok.sum()), "share": r(same.mean(), 3),
                                                          "mean_rest_of_day_pct_signed": r(np.mean((cend[ok] / c[ok] - 1) * 100 * np.sign(c[ok] - op0[ok])), 4)}
    return out


# ---- A6 calendar: weekday, expiry, events ---------------------------------------------------------------------------

def a6_calendar(sym: str, part: str) -> dict:
    d = daily_frame(sym, part).dropna(subset=["ret"]).copy()
    exp = D.expiries(sym)
    exp_days = set(exp[exp.is_expiry].day)
    mexp = set(exp[exp.is_monthly_expiry].day)
    d["is_exp"] = d.day.isin(exp_days)
    d["is_mexp"] = d.day.isin(mexp)
    since = d[d.day >= exp.day.min()] if len(exp) else d.iloc[0:0]

    def g(x: pd.DataFrame) -> dict:
        return {"n": int(len(x)), "mean_ret_pct": r(x.ret.mean(), 4), "sd_ret_pct": r(x.ret.std(), 3),
                "up_share": r((x.ret > 0).mean(), 3), "median_range_pct": r(x["range"].median(), 3),
                "median_abs_ret_pct": r(x.ret.abs().median(), 3), "mean_oc_pct": r(x.oc.mean(), 4),
                "t_mean_ret": r(x.ret.mean() / (x.ret.std() / np.sqrt(max(len(x), 1))), 2) if len(x) > 2 else None}
    out = {"first_day": str(d.day.min()), "last_day": str(d.day.max()),
           "by_weekday": {w: g(d[d.wd == w]) for w in ("Mon", "Tue", "Wed", "Thu", "Fri")},
           "by_weekday_2021on": {w: g(d[(d.wd == w) & (d.day >= date(2021, 1, 1))]) for w in ("Mon", "Tue", "Wed", "Thu", "Fri")},
           "expiry_vs_not": {"expiry": g(since[since.is_exp]), "monthly_expiry": g(since[since.is_mexp]),
                             "weekly_only_expiry": g(since[since.is_exp & ~since.is_mexp]), "other": g(since[~since.is_exp]),
                             "day_before_expiry": g(since[since.index.isin(since.index[since.is_exp.shift(-1, fill_value=False)])]),
                             "day_after_expiry": g(since[since.index.isin(since.index[since.is_exp.shift(1, fill_value=False)])]),
                             "first_day": str(since.day.min()) if len(since) else None}}
    ev = {}
    for cat in ("RBI", "FOMC", "USCPI", "BUDGET", "ELECTION"):
        ss = D.event_sessions([cat])
        x = d[d.day.isin(ss)]
        base = d[(d.day >= min(ss)) & (d.day <= max(ss))] if ss else d.iloc[0:0]
        ev[cat] = {"event_sessions": g(x), "all_days_same_span": g(base)}
    # results season (approximate: the 15th of the month after a quarter end to the 15th of the next month)
    def in_results(x: date) -> bool:
        return (x.month in (1, 4, 7, 10) and x.day >= 15) or (x.month in (2, 5, 8, 11) and x.day <= 15)
    d["results"] = d.day.map(in_results)
    ev["results_season_approx"] = {"in_season": g(d[d.results]), "out_of_season": g(d[~d.results])}
    out["events"] = ev
    return out


# ---- A7 option chain from the bhavcopy (end of day) and A8 straddle / theta / premium paths -------------------------

def a7_chain(und: str, part: str) -> dict:
    b = D.bhav(und)
    b = b[b.date.dt.date <= (D.DESIGN_END if part == "design" else D.HOLDOUT_END)]
    exp = D.expiries(und).set_index("day")
    d = D.daily(und)
    rows = []
    for day, x in b.groupby(b.date.dt.date):
        if day not in exp.index:
            continue
        near = exp.loc[day, "expiry"]
        c = x[(x.expiry == near) & (x.oi_qty > 0)]
        if len(c) < 10 or day not in d.index.date:
            continue
        spot = x.und_px.iloc[0] if np.isfinite(x.und_px.iloc[0]) and x.und_px.iloc[0] > 0 else d.loc[pd.Timestamp(day), "close"]
        ce, pe = c[c.kind == "CE"], c[c.kind == "PE"]
        if len(ce) < 5 or len(pe) < 5:
            continue
        pcr = pe.oi_qty.sum() / max(ce.oi_qty.sum(), 1)
        strikes = np.array(sorted(set(c.strike)))
        ce_oi = ce.groupby("strike").oi_qty.sum().reindex(strikes, fill_value=0).values
        pe_oi = pe.groupby("strike").oi_qty.sum().reindex(strikes, fill_value=0).values
        # max pain: the strike where the total intrinsic value owed to option holders is least
        pain = [np.sum(ce_oi * np.clip(k - strikes, 0, None)) + np.sum(pe_oi * np.clip(strikes - k, 0, None)) for k in strikes]
        max_pain = strikes[int(np.argmin(pain))]
        call_wall = strikes[int(np.argmax(ce_oi))]
        put_wall = strikes[int(np.argmax(pe_oi))]
        rows.append({"day": day, "spot": spot, "pcr": pcr, "max_pain": max_pain, "call_wall": call_wall, "put_wall": put_wall,
                     "dte": exp.loc[day, "days_to_expiry"], "is_exp": bool(exp.loc[day, "is_expiry"]),
                     "n_strikes": len(strikes)})
    t = pd.DataFrame(rows)
    if t.empty:
        return {"n_days": 0}
    t["year"] = [x.year for x in t.day]
    dd = d.copy(); dd["day"] = dd.index.date
    t = t.merge(dd[["day", "close"]], on="day", how="left")
    t["next_close"] = t.close.shift(-1)
    t["mp_dist_pct"] = (t.max_pain / t.spot - 1) * 100
    t["cw_dist_pct"] = (t.call_wall / t.spot - 1) * 100
    t["pw_dist_pct"] = (t.put_wall / t.spot - 1) * 100
    t["next_ret"] = (t.next_close / t.close - 1) * 100
    out = {"n_days": int(len(t)), "first_day": str(t.day.min()), "last_day": str(t.day.max()),
           "pcr_oi_nearest_expiry": stats(t.pcr, 3),
           "pcr_by_year": {int(y): stats(g.pcr, 3) for y, g in t.groupby("year")},
           "max_pain_minus_spot_pct": stats(t.mp_dist_pct, 3),
           "call_wall_minus_spot_pct": stats(t.cw_dist_pct, 3), "put_wall_minus_spot_pct": stats(t.pw_dist_pct, 3),
           "spot_between_walls_share": r(((t.spot <= t.call_wall) & (t.spot >= t.put_wall)).mean(), 3)}
    # on expiry day: how far did the close land from the previous day's max pain? (descriptive)
    e = t[t.is_exp].copy()
    prev = t.shift(1)
    e_prev_mp = prev.loc[e.index, "max_pain"]
    dist = (e.close / e_prev_mp - 1).abs() * 100
    out["expiry_close_vs_prev_day_max_pain_abs_pct"] = stats(dist, 3)
    # the close within one strike step of max pain on expiry day
    step = D.STEP[und]
    out["expiry_close_within_1_step_of_prev_max_pain_share"] = r(((e.close - e_prev_mp).abs() <= step).mean(), 3)
    out["expiry_n"] = int(len(e))
    # PCR extremes vs the next day's return (descriptive: with the sample size, not a rule)
    lo_, hi_ = t.pcr.quantile([0.1, 0.9])
    out["pcr_decile_next_day_ret"] = {"pcr_le_p10": {"cut": r(lo_, 3), **stats(t[t.pcr <= lo_].next_ret, 3)},
                                      "pcr_ge_p90": {"cut": r(hi_, 3), **stats(t[t.pcr >= hi_].next_ret, 3)},
                                      "middle": stats(t[(t.pcr > lo_) & (t.pcr < hi_)].next_ret, 3)}
    return out


def _book(und: str, years, offsets, minutes_=None, part: str = "design") -> pd.DataFrame:
    parts = [D.options(und, y, offsets=offsets, minutes_=minutes_) for y in years]
    o = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    if o.empty:
        return o
    return D.cut(o, part)


def a8_straddle(und: str, part: str) -> dict:
    years = D.option_years(und)
    marks = [0, 5, 15, 30, 75, 165, 225, 255, 315, 345, 360, 374]   # 09:15 09:20 09:30 09:45 10:30 12:00 13:00 13:30 14:30 15:00 15:15 15:29
    o = _book(und, years, offsets=[-5, 0, 5], minutes_=marks, part=part)
    if o.empty:
        return {"n_days": 0}
    exp = D.expiries(und).set_index("day")
    o["is_exp"] = o.day.map(lambda d: bool(exp.is_expiry.get(d, False)))
    o["dte"] = o.day.map(lambda d: int(exp.days_to_expiry.get(d, -1)))
    atm = o[o.offset == 0]
    st = atm.pivot_table(index=["day", "m"], columns="side", values="close", aggfunc="first")
    st["spot"] = atm.groupby(["day", "m"]).spot.first()
    st["straddle"] = st.C + st.P
    st["straddle_pct"] = st.straddle / st.spot * 100
    st = st.reset_index()
    st["is_exp"] = st.day.map(lambda d: bool(exp.is_expiry.get(d, False)))
    st["dte"] = st.day.map(lambda d: int(exp.days_to_expiry.get(d, -1)))
    st["year"] = [d.year for d in st.day]
    out = {"n_days": int(st.day.nunique()), "first_day": str(st.day.min()), "last_day": str(st.day.max())}
    # 09:20 straddle as % of spot: by year and by expiry / days to expiry
    s920 = st[st.m == 5]
    out["straddle_0920_pct_of_spot"] = {"all": stats(s920.straddle_pct, 3),
                                        "by_year": {int(y): stats(g.straddle_pct, 3) for y, g in s920.groupby("year")},
                                        "by_dte": {int(k): stats(g.straddle_pct, 3) for k, g in s920.groupby("dte") if 0 <= k <= 7}}
    # does the day exceed what the straddle prices? close vs 09:20 spot, and the day's range from 09:20
    mins = D.full_days(D.cut(D.minutes(und), part))
    grid, days = D.session_grid(mins)
    dmap = {d: i for i, d in enumerate(days)}
    rows = []
    for _, x in s920.iterrows():
        i = dmap.get(x.day)
        if i is None or not np.isfinite(x.straddle):
            continue
        cl = grid[i, :, 3]
        last = next((v for v in cl[::-1] if np.isfinite(v)), np.nan)
        hi_, lo_ = np.nanmax(grid[i, 5:, 1]), np.nanmin(grid[i, 5:, 2])
        rows.append({"day": x.day, "is_exp": x.is_exp, "year": x.year, "straddle": x.straddle, "spot": x.spot,
                     "move": abs(last - x.spot), "range_from_0920": max(hi_ - x.spot, x.spot - lo_)})
    t = pd.DataFrame(rows)
    if len(t):
        def ex(g):
            return {"n": int(len(g)), "close_move_exceeds_straddle_share": r((g.move > g.straddle).mean(), 3),
                    "max_excursion_exceeds_straddle_share": r((g.range_from_0920 > g.straddle).mean(), 3),
                    "median_move_over_straddle": r((g.move / g.straddle).median(), 3),
                    "mean_move_over_straddle": r((g.move / g.straddle).mean(), 3)}
        out["day_vs_0920_straddle"] = {"all": ex(t), "expiry": ex(t[t.is_exp]), "non_expiry": ex(t[~t.is_exp]),
                                       "by_year": {int(y): ex(g) for y, g in t.groupby("year")}}
    # theta by hour: the rolling ATM straddle price at each mark as a share of its 09:20 value (median over days)
    def path(g: pd.DataFrame) -> dict:
        p = g.pivot_table(index="day", columns="m", values="straddle", aggfunc="first")
        if 5 not in p.columns:
            return {"n": 0}
        base = p[5]
        res = {"n_days": int(base.notna().sum())}
        for mm in marks:
            if mm in p.columns:
                ratio = (p[mm] / base).replace([np.inf, -np.inf], np.nan).dropna()
                res[f"m{mm}"] = {"label": f"{(mm + D.OPEN_MIN) // 60:02d}:{(mm + D.OPEN_MIN) % 60:02d}", "median_share_of_0920": r(ratio.median(), 3), "n": int(len(ratio))}
        return res
    out["atm_straddle_path"] = {"expiry": path(st[st.is_exp]), "dte_1": path(st[st.dte == 1]), "dte_2_to_4": path(st[st.dte.between(2, 4)]),
                                "non_expiry": path(st[~st.is_exp])}
    # skew: IV of ATM-5 put minus ATM+5 call, and ATM IV, at 09:20 / 12:00 / 15:15
    iv = o[o.iv > 0]
    sk = {}
    for mm, lab in ((5, "09:20"), (165, "12:00"), (360, "15:15")):
        x = iv[iv.m == mm]
        p5 = x[(x.offset == -5) & (x.side == "P")].set_index("day").iv
        c5 = x[(x.offset == 5) & (x.side == "C")].set_index("day").iv
        a0 = x[(x.offset == 0)].groupby("day").iv.mean()
        j = pd.DataFrame({"put_iv": p5, "call_iv": c5, "atm_iv": a0}).dropna()
        j["skw"] = j.put_iv - j.call_iv
        j["is_exp"] = [bool(exp.is_expiry.get(d, False)) for d in j.index]
        sk[lab] = {"n_days": int(len(j)), "atm_iv": stats(j.atm_iv, 2), "put5_minus_call5_iv_pts": stats(j.skw, 2),
                   "skew_positive_share": r((j.skw > 0).mean(), 3),
                   "non_expiry_atm_iv_median": r(j[~j.is_exp].atm_iv.median(), 2), "expiry_atm_iv_median": r(j[j.is_exp].atm_iv.median(), 2)}
    out["skew"] = sk
    # ATM IV (nearest expiry) vs India VIX (30-day) at 09:20: a crude term structure
    vix = D.daily("INDIAVIX").close
    x = iv[(iv.m == 5) & (iv.offset == 0)].groupby("day").iv.mean()
    j = pd.DataFrame({"atm": x, "vix": [vix.get(pd.Timestamp(d), np.nan) for d in x.index]}).dropna()
    j["is_exp"] = [bool(exp.is_expiry.get(d, False)) for d in j.index]
    j["dte"] = [int(exp.days_to_expiry.get(d, -1)) for d in j.index]
    out["atm_iv_minus_vix"] = {"all": stats(j.atm - j.vix, 2), "non_expiry": stats(j[~j.is_exp].atm - j[~j.is_exp].vix, 2),
                               "expiry": stats(j[j.is_exp].atm - j[j.is_exp].vix, 2),
                               "by_dte": {int(k): stats(g.atm - g.vix, 2) for k, g in j.groupby("dte") if 0 <= k <= 7}}
    return out


def a8b_premium_paths(und: str, part: str) -> dict:
    """ATM call / put premiums in the first 15 minutes and the last hour (rolling ATM: the strike follows spot)."""
    years = D.option_years(und)
    o = _book(und, years, offsets=[0], minutes_=list(range(0, 16)) + list(range(315, 375)), part=part)
    if o.empty:
        return {"n_days": 0}
    exp = D.expiries(und).set_index("day")
    o["is_exp"] = o.day.map(lambda d: bool(exp.is_expiry.get(d, False)))
    out = {"n_days": int(o.day.nunique())}
    st = o.pivot_table(index=["day", "m"], columns="side", values="close", aggfunc="first")
    st["straddle"] = st.C + st.P
    st = st.reset_index()
    st["is_exp"] = st.day.map(lambda d: bool(exp.is_expiry.get(d, False)))
    def seg(g, m0, m1, lab):
        p = g.pivot_table(index="day", columns="m", values="straddle", aggfunc="first")
        if m0 not in p.columns or m1 not in p.columns:
            return {"n": 0}
        ch = (p[m1] / p[m0] - 1).replace([np.inf, -np.inf], np.nan).dropna() * 100
        return {"label": lab, "n_days": int(len(ch)), "median_change_pct": r(ch.median(), 2), "mean_change_pct": r(ch.mean(), 2),
                "p25": r(ch.quantile(0.25), 2), "p75": r(ch.quantile(0.75), 2), "share_down": r((ch < 0).mean(), 3)}
    for lab, g in (("non_expiry", st[~st.is_exp]), ("expiry", st[st.is_exp])):
        out[lab] = {"straddle_0915_to_0930": seg(g, 0, 15, "09:15->09:30"), "straddle_0915_to_0920": seg(g, 0, 5, "09:15->09:20"),
                    "straddle_1430_to_1529": seg(g, 315, 374, "14:30->15:29"), "straddle_1500_to_1529": seg(g, 345, 374, "15:00->15:29")}
    # the single ATM option's minute-to-minute volatility in the first 15 minutes vs the last hour (median |1-min change|)
    o = o.sort_values(["day", "side", "m"])
    o["chg"] = o.groupby(["day", "side"]).close.pct_change().abs() * 100
    first = o[(o.m.between(1, 15))]; last = o[o.m.between(316, 374)]
    out["median_abs_1min_change_pct"] = {"first_15": {"n": int(first.chg.notna().sum()), "median": r(first.chg.median(), 3)},
                                         "last_hour": {"n": int(last.chg.notna().sum()), "median": r(last.chg.median(), 3)},
                                         "last_hour_expiry": {"n": int(last[last.is_exp].chg.notna().sum()), "median": r(last[last.is_exp].chg.median(), 3)}}
    return out


# ---- A9 participants (levels) ---------------------------------------------------------------------------------------

def participant_signals() -> pd.DataFrame:
    p = D.participants()
    s = pd.DataFrame(index=p.index)
    for g in ("FII", "Client", "Pro", "DII"):
        tot = (p[f"{g}_fut_idx_long"] + p[f"{g}_fut_idx_short"]).replace(0, np.nan)
        s[f"{g}_fut_net_share"] = (p[f"{g}_fut_idx_long"] - p[f"{g}_fut_idx_short"]) / tot
        s[f"{g}_fut_net_contracts"] = p[f"{g}_fut_idx_long"] - p[f"{g}_fut_idx_short"]
        otot = (p[f"{g}_idx_call_long"] + p[f"{g}_idx_put_short"] + p[f"{g}_idx_call_short"] + p[f"{g}_idx_put_long"]).replace(0, np.nan)
        s[f"{g}_opt_net_share"] = (p[f"{g}_idx_call_long"] + p[f"{g}_idx_put_short"] - p[f"{g}_idx_call_short"] - p[f"{g}_idx_put_long"]) / otot
    s["FII_share_of_idx_fut_oi"] = (p["FII_fut_idx_long"] + p["FII_fut_idx_short"]) / (2 * p["TOTAL_fut_idx_long"]).replace(0, np.nan)
    s["Client_share_of_idx_fut_oi"] = (p["Client_fut_idx_long"] + p["Client_fut_idx_short"]) / (2 * p["TOTAL_fut_idx_long"]).replace(0, np.nan)
    s["Pro_share_of_idx_fut_oi"] = (p["Pro_fut_idx_long"] + p["Pro_fut_idx_short"]) / (2 * p["TOTAL_fut_idx_long"]).replace(0, np.nan)
    s["Client_share_of_idx_opt_long"] = (p["Client_idx_call_long"] + p["Client_idx_put_long"]) / (p["TOTAL_idx_call_long"] + p["TOTAL_idx_put_long"]).replace(0, np.nan)
    s["Pro_share_of_idx_opt_short"] = (p["Pro_idx_call_short"] + p["Pro_idx_put_short"]) / (p["TOTAL_idx_call_short"] + p["TOTAL_idx_put_short"]).replace(0, np.nan)
    s["FII_share_of_idx_opt_short"] = (p["FII_idx_call_short"] + p["FII_idx_put_short"]) / (p["TOTAL_idx_call_short"] + p["TOTAL_idx_put_short"]).replace(0, np.nan)
    return s


def a9_participants(part: str) -> dict:
    s = participant_signals()
    s["day"] = s.index.date
    s = D.cut(s, part)
    out = {"n_days": int(len(s)), "first_day": str(s.day.min()), "last_day": str(s.day.max())}
    for c in [c for c in s.columns if c != "day"]:
        out[c] = {"all": stats(s[c], 3), "by_year": {int(y): {"n": int(len(g)), "mean": r(g[c].mean(), 3), "min": r(g[c].min(), 3), "max": r(g[c].max(), 3)}
                                                     for y, g in s.groupby(s.index.year)}}
    f = D.fpi(); f["day"] = f.index.date; f = D.cut(f, part)
    out["fpi_net_cr"] = {"all": stats(f.eq_exch_net_cr, 1), "positive_share": r((f.eq_exch_net_cr > 0).mean(), 3),
                         "by_year": {int(y): {"n": int(len(g)), "sum_cr": r(g.eq_exch_net_cr.sum(), 0), "positive_share": r((g.eq_exch_net_cr > 0).mean(), 3)}
                                     for y, g in f.groupby(f.index.year)}}
    return out


# ---- run -------------------------------------------------------------------------------------------------------------

SECTIONS = {
    "a1_daily": lambda part: a1_daily(part),
    "a2_vol": lambda part: a2_vol(part),
    "a3_intraday": lambda part: {s: a3_intraday(s, part) for s in D.UNDS},
    "a4_gaps": lambda part: {s: a4_gaps(s, part) for s in D.UNDS},
    "a5_autocorr": lambda part: {s: a5_autocorr(s, part) for s in D.UNDS},
    "a6_calendar": lambda part: {s: a6_calendar(s, part) for s in D.UNDS},
    "a7_chain": lambda part: {s: a7_chain(s, part) for s in D.UNDS},
    "a8_straddle": lambda part: {s: a8_straddle(s, part) for s in D.UNDS},
    "a8b_premium_paths": lambda part: {s: a8b_premium_paths(s, part) for s in D.UNDS},
    "a9_participants": lambda part: a9_participants(part),
}


def run(part: str, cache: Path | None = None) -> dict:
    """Every section; with `cache`, a section already computed (cache/<section>.json) is read back, not recomputed."""
    res = {"part": part, "design_end": str(D.DESIGN_END), "holdout": [str(D.HOLDOUT_START), str(D.HOLDOUT_END)],
           "note": "descriptive, not a trading rule"}
    for name, fn in SECTIONS.items():
        f = cache / f"{part}_{name}.json" if cache else None
        if f is not None and f.is_file():
            res[name] = json.loads(f.read_text())
            continue
        res[name] = fn(part)
        if f is not None:
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(json.dumps(res[name], indent=1, default=str))
        print(f"{part}: {name} done", flush=True)
    return res


def main(argv) -> int:
    part = argv[1] if len(argv) > 1 else "design"
    D.OUT.mkdir(parents=True, exist_ok=True)
    res = run(part, cache=Path(os.environ.get("R102_CACHE", "/tmp/r102_cache")))
    (D.OUT / f"desc_{part}.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(f"wrote desc_{part}.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
