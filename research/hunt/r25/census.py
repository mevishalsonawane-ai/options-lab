"""R25 Step 1: the census of cheap expiry-day options (PREREG.md Step 1). Descriptive; design days only until the
holdout read (then `census.py holdout` adds the holdout days, from study.py only).

    python3 research/hunt/r25/census.py design      # -> <OUT>/census_design.parquet, results/census.json

A unit = (expiry day, strike, side, slot); slots = bar close times 11:00-11:59, 12:00-12:59, 13:00-13:59, 14:00-15:00.
Start = the first minute in the slot at which the option's traded close is Rs 1-10; ref = that close.
"""
from __future__ import annotations

import json
import math
import sys
from multiprocessing import Pool

import numpy as np
import pandas as pd

import r25cfg as K
import r25data as D
import r25lib as R
from r25data import C, H, N, O, V, minute

SLOTS = {"11": (R.close_row(11, 0), R.close_row(11, 59)), "12": (R.close_row(12, 0), R.close_row(12, 59)),
         "13": (R.close_row(13, 0), R.close_row(13, 59)), "14": (R.close_row(14, 0), R.close_row(15, 0))}
MULTS = (2, 3, 5, 10, 20, 33)
BAND = (1.0, 10.0)
LAST_1505 = minute(15, 4)          # "before 15:05": bars starting <= 15:04


def vix_regime(v: float) -> str:
    if math.isnan(v):
        return "na"
    return "<13" if v < 13 else ("13-17" if v < 17 else ("17-22" if v < 22 else ">=22"))


def price_band(p: float) -> str:
    return "1-3" if p < 3 else ("3-5" if p < 5 else "5-10")


def _itm_floor(day: D.Day, k: float, side: str) -> np.ndarray:
    """Per row: the intrinsic value where the strike is beyond the stored range on the ITM side (a lower bound of its
    price), else NaN."""
    lo, hi = R.stored_range(day)[side]
    s = day.ix[:, 3]
    if side == "C":
        out = np.where(k < lo, np.maximum(s - k, 0), np.nan)
    else:
        out = np.where(k > hi, np.maximum(k - s, 0), np.nan)
    return out


def day_units(day: D.Day) -> tuple[list[dict], dict]:
    rows = []
    lo_hi = R.stored_range(day)
    for (k, side), a in day.opts.items():
        sgn = 1 if side == "C" else -1
        trd = (np.nan_to_num(a[:, V]) > 0) & ~np.isnan(a[:, C])
        inb = trd & (a[:, C] >= BAND[0] - R.EPS) & (a[:, C] <= BAND[1] + R.EPS)
        hi_path = np.fmax(np.where(trd | ~np.isnan(a[:, H]), a[:, H], np.nan), _itm_floor(day, k, side))
        for slot, (r0, r1) in SLOTS.items():
            idx = np.nonzero(inb[r0:r1 + 1])[0]
            if not len(idx):
                continue
            r = r0 + int(idx[0])
            ref = float(a[r, C])
            seg = hi_path[r + 1: LAST_1505 + 1]
            peak = float(np.nanmax(seg)) if np.isfinite(seg).any() else ref
            seg2 = hi_path[r + 1:]
            peak_close = float(np.nanmax(seg2)) if np.isfinite(seg2).any() else ref
            peak = max(peak, ref)
            peak_close = max(peak_close, peak)
            pk_row = r + 1 + int(np.nanargmax(seg)) if np.isfinite(seg).any() else r
            o1505 = a[R.EXIT_ROW, O]
            if math.isnan(o1505):
                p_, _ = R.ltp(a, R.EXIT_ROW - 1)
                o1505 = p_
            cl = a[r + 1: LAST_1505 + 1, C]
            half_min = int(np.nansum(cl >= 0.5 * peak)) if peak >= 2 * ref else 0
            s = R.spot(day, r)
            am = R.atm(day, s) if not math.isnan(s) else math.nan
            dist_k = (k - am) / K.STEP[day.und] * sgn if not math.isnan(am) else math.nan
            tr = R.simulate(day, k, sgn, r, ref, None, "hold")
            u = dict(und=day.und, day=day.day, year=day.day.year, wd=day.day.weekday(), strike=k, side=side, slot=slot,
                     row=r, ref=ref, band=price_band(ref), vix=float(day.vix[r]) if day.vix is not None else math.nan,
                     dist_k=dist_k, dist_pct=(k - s) / s * 100 * sgn if not math.isnan(s) else math.nan,
                     peak=peak, peak_close=peak_close, peak_row=pk_row, m1505=(o1505 / ref if o1505 == o1505 else math.nan),
                     half_min=half_min, giveback=(1 - o1505 / peak) if peak > 0 and o1505 == o1505 else math.nan,
                     net1=tr.net if tr else math.nan, p1000=tr.per_1000 if tr else math.nan,
                     proxy=bool(tr.proxy) if tr else False)
            pkc = np.nanmax(cl) if np.isfinite(cl).any() else ref
            u["peak_cl"] = float(max(pkc, ref))
            for m in MULTS:
                u[f"x{m}cl"] = u["peak_cl"] >= m * ref - R.EPS
                u[f"x{m}"] = peak >= m * ref - R.EPS
                u[f"x{m}c"] = peak_close >= m * ref - R.EPS
            rows.append(u)
    # coverage: minutes 11:00-15:00 where the outermost stored strike on a side was priced above Rs 10 / above Rs 1
    w = slice(R.close_row(11, 0), R.close_row(15, 0) + 1)
    cov = dict(und=day.und, day=day.day)
    for j, s in enumerate(("C", "P")):
        e = day.edge[w, j]
        ok = ~np.isnan(e)
        cov[f"{s}_min"] = int(ok.sum())
        cov[f"{s}_gt10"] = int((e[ok] > BAND[1]).sum())
        cov[f"{s}_gt1"] = int((e[ok] > BAND[0]).sum())
    # hidden-start jackpots (a lower bound): a strike first stored after 11:00 at the outer edge, priced > Rs 10, that
    # then ran to >= 5x that first price before 15:05 - its cheaper phase was outside the stored range
    hid = 0
    for (k, side), a in day.opts.items():
        ok = np.nonzero(~np.isnan(a[:, C]))[0]
        if not len(ok) or ok[0] < R.close_row(11, 0) or ok[0] > R.close_row(15, 0):
            continue
        r = int(ok[0])
        lo, hi = lo_hi[side]
        at_edge = (side == "C" and k >= hi[r]) or (side == "P" and k <= lo[r])
        p0 = a[r, C]
        if at_edge and p0 > BAND[1]:
            seg = a[r + 1: LAST_1505 + 1, H]
            if np.isfinite(seg).any() and np.nanmax(seg) >= 5 * p0:
                hid += 1
    cov["hidden_5x"] = hid
    return rows, cov


_CACHE: dict = {}


def _one(args):
    und, i, part = args
    if (und, part) not in _CACHE:
        _CACHE.clear()
        _CACHE[(und, part)] = D.load(und, part)
    return day_units(_CACHE[(und, part)][i])


def run(part: str = "design", procs: int = 4) -> pd.DataFrame:
    jobs = []
    for u in K.UNDS:
        n = len(D.load(u, part))
        jobs += [(u, i, part) for i in range(n)]
    units, covs = [], []
    with Pool(procs) as p:
        for rows, cov in p.imap(_one, jobs, chunksize=8):
            units += rows
            covs.append(cov)
    df = pd.DataFrame(units)
    cv = pd.DataFrame(covs)
    K.OUT.mkdir(parents=True, exist_ok=True)
    df.to_parquet(K.OUT / f"census_{part}.parquet")
    cv.to_parquet(K.OUT / f"coverage_{part}.parquet")
    return df


def summarise(df: pd.DataFrame, cv: pd.DataFrame, part: str) -> dict:
    out = {"part": part, "units": int(len(df)), "days": int(df.groupby(["und", "day"]).ngroups)}
    out["days_by_und_year"] = {f"{u}|{y}": int(n) for (u, y), n in df.groupby(["und", "year"]).day.nunique().items()}

    def rate(g):
        r = {"n": int(len(g)), "days": int(g.day.nunique())}
        for m in MULTS:
            r[f"x{m}"] = round(float(g[f"x{m}"].mean()) * 100, 2)
            r[f"x{m}c"] = round(float(g[f"x{m}c"].mean()) * 100, 2)
            r[f"x{m}cl"] = round(float(g[f"x{m}cl"].mean()) * 100, 2)
        r["mean_net_lot"] = round(float(g.net1.mean()), 1)
        r["mean_per1000"] = round(float(g.p1000.mean()), 1)
        r["win_rate"] = round(float((g.net1 > 0).mean()) * 100, 1)
        return r

    out["all"] = rate(df)
    for col in ("und", "year", "wd", "slot", "band", "side"):
        out[f"by_{col}"] = {str(k): rate(g) for k, g in df.groupby(col)}
    df = df.assign(vreg=df.vix.map(vix_regime), dk=df.dist_k.clip(upper=12).round(),
                   dp=pd.cut(df.dist_pct, [-1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 99]).astype(str))
    out["by_vix"] = {str(k): rate(g) for k, g in df.groupby("vreg")}
    out["by_dist_strikes"] = {str(int(k)): rate(g) for k, g in df.groupby("dk")}
    out["by_dist_pct"] = {str(k): rate(g) for k, g in df.groupby("dp")}
    # per day: any unit reached m x
    dd = df.groupby(["und", "day"])[[f"x{m}{t}" for t in ("", "c", "cl") for m in MULTS]].any()
    out["days_any"] = {c: [int(dd[c].sum()), int(len(dd))] for c in dd.columns}
    out["days_any_by_und"] = {u: {c: int(g[c].sum()) for c in g.columns} | {"n": int(len(g))}
                              for u, g in dd.groupby(level=0)}
    # anatomy of the 10x units
    j = df[df.x10]
    out["x10_units"] = {
        "n": int(len(j)), "days": int(j.groupby(["und", "day"]).ngroups),
        "half_min_median": float(j.half_min.median()), "half_min_q25_q75": [float(j.half_min.quantile(.25)), float(j.half_min.quantile(.75))],
        "giveback_median_pct": round(float(j.giveback.median()) * 100, 1),
        "share_ge2x_at_1505": round(float((j.m1505 >= 2).mean()) * 100, 1),
        "share_ge1x_at_1505": round(float((j.m1505 >= 1).mean()) * 100, 1),
        "peak_time_minutes_after_start_median": float((j.peak_row - j.row).median()),
        "mean_net_lot_hold": round(float(j.net1.mean()), 1)}
    out["proxy_trades"] = int(df.proxy.sum())
    # coverage
    tot = {s: [int(cv[f"{s}_gt10"].sum()), int(cv[f"{s}_gt1"].sum()), int(cv[f"{s}_min"].sum())] for s in ("C", "P")}
    out["coverage"] = {"minutes_band_wholly_unseen_C": tot["C"][0], "minutes_band_partly_unseen_C": tot["C"][1],
                       "minutes_C": tot["C"][2], "minutes_band_wholly_unseen_P": tot["P"][0],
                       "minutes_band_partly_unseen_P": tot["P"][1], "minutes_P": tot["P"][2],
                       "hidden_start_5x_strikes": int(cv.hidden_5x.sum()),
                       "days_with_hidden_start_5x": int((cv.hidden_5x > 0).sum()), "days": int(len(cv)),
                       "by_und": {u: {"C_gt10": int(g.C_gt10.sum()), "C_gt1": int(g.C_gt1.sum()), "C_min": int(g.C_min.sum()),
                                      "P_gt10": int(g.P_gt10.sum()), "P_gt1": int(g.P_gt1.sum()), "P_min": int(g.P_min.sum()),
                                      "hidden_5x": int(g.hidden_5x.sum()), "days": int(len(g))}
                                  for u, g in cv.groupby("und")}}
    # the biggest jackpot days (best multiple reached by any unit before 15:05)
    df = df.assign(best=df.peak / df.ref)
    top = df.sort_values("best", ascending=False).drop_duplicates(["und", "day"]).head(10)
    out["top10_days"] = [dict(und=r.und, day=str(r.day), strike=r.strike, side=r.side, slot=r.slot, ref=r.ref,
                              peak=r.peak, mult=round(r.best, 1), start=R_time(r.row), peak_at=R_time(r.peak_row),
                              m1505=round(r.m1505, 2) if r.m1505 == r.m1505 else None)
                         for r in top.itertuples()]
    return out


def R_time(row: int) -> str:
    m = 9 * 60 + 15 + int(row) + 1
    return f"{m // 60:02d}:{m % 60:02d}"


if __name__ == "__main__":
    part = sys.argv[1] if len(sys.argv) > 1 else "design"
    if part != "design":
        raise SystemExit("census of the holdout runs only from study.py holdout")
    df = run(part)
    cv = pd.read_parquet(K.OUT / f"coverage_{part}.parquet")
    s = summarise(df, cv, part)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "census.json").write_text(json.dumps(s, indent=1, default=str))
    print(json.dumps({k: s[k] for k in ("units", "days", "all", "days_any", "x10_units", "coverage")}, indent=1, default=str))
