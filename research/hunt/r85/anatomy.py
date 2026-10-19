"""R85 Part 1: candle anatomy on DESIGN data only (PREREG section 3), 19 Oct 2026.

    python3 -m research.hunt.r85.anatomy            # all instruments, 1m / 5m / 15m / 1h + day; writes results/part1_*

A "cell" = instrument x timeframe x condition (a shape or a context) x colour (green / red) x session bucket (ALL or one
of six). Outcome: the next h candles' signed move s_h = colour x (C[t+h] - C[t]) / ATR[t] (positive = the candle's
colour continued). Baseline = every coloured candle of the same instrument, timeframe and bucket ("random time").
Effect = mean(s_h - baseline_s_h[bucket]); standard error clustered by day (forward windows overlap); z test,
two-sided p. Cells with n < 30 are listed but not tested. Primary family: h = 3, BH q < 0.10 over every tested cell.
"""
from __future__ import annotations

import json
import os
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

from research.hunt.r85 import candles as K

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
HALVES = ((date(2025, 8, 5), date(2025, 11, 30)), (date(2025, 12, 1), date(2026, 3, 31)))
FINAL_START = date(2026, 4, 1)
DAY_DESIGN = (date(2010, 1, 1), date(2022, 12, 31))
DAY_CONFIRM = (date(2023, 1, 1), date(2026, 3, 31))
INSTRUMENTS = ("GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "NATURALGAS")
TFS = (1, 5, 15, 60)
MIN_N = 30
PRIMARY_H = 3
Q_GATE = 0.10

SHAPES = ("s_marubozu", "s_bigbody", "s_small", "s_hammer", "s_star", "s_bull_engulf", "s_bear_engulf", "s_inside",
          "s_outside", "s_three", "s_flip3")
SHAPES_1M = ("s_bigbody", "s_small", "s_three", "s_flip3")


def contexts(cd: pd.DataFrame) -> dict:
    """Context conditions (PREREG section 3), each a boolean mask known at the candle close."""
    col = cd.colour.values
    with np.errstate(invalid="ignore"):
        return {
            "all": np.ones(len(cd), bool),
            "trend_with": cd.trend.values == col,
            "trend_against": cd.trend.values == -col,
            "above_mean_1atr": cd.smean_dist.values >= 1.0,
            "below_mean_1atr": cd.smean_dist.values <= -1.0,
            "near_day_high": cd.near_hi.values.astype(bool),
            "near_day_low": cd.near_lo.values.astype(bool),
            "broke_prev_high": cd.broke_pdh.values.astype(bool),
            "broke_prev_low": cd.broke_pdl.values.astype(bool),
            "vol_high": cd.vol_reg.values > 1.3,
            "vol_low": cd.vol_reg.values < 0.8,
            "activity_high": cd.act_ratio.values >= 2.0,
            "activity_low": cd.act_ratio.values < 0.5,
            "big_range": cd.rng_atr.values >= 1.5,
            "close_top20": cd.cl_loc.values >= 0.8,
            "close_bottom20": cd.cl_loc.values <= 0.2,
            "event_day": cd.event_day.values.astype(bool),
            "expiry_week": cd.exp_week.values.astype(bool),
            "dte_11_20": (cd.dte.values >= 11) & (cd.dte.values <= 20),
            "monday": cd.dow.values == 0,
            "friday": cd.dow.values == 4,
            "bigbody_activity_high": (cd.body_atr.values >= 1.0) & (cd.act_ratio.values >= 2.0),
        }


def clustered_mean_se(x: np.ndarray, groups: np.ndarray) -> tuple[float, float]:
    """Mean of x and its standard error clustered by `groups` (CR1 small-sample factor). se = NaN if < 2 groups."""
    x = np.asarray(x, float)
    n = len(x)
    if n == 0:
        return float("nan"), float("nan")
    mu = float(x.mean())
    s = pd.Series(x - mu).groupby(np.asarray(groups)).sum().values
    G = len(s)
    if G < 2:
        return mu, float("nan")
    var = (s ** 2).sum() / n ** 2 * G / (G - 1)
    return mu, float(np.sqrt(var))


def bh(p: np.ndarray) -> np.ndarray:
    """Benjamini-Hochberg q-values (NaN p stays NaN)."""
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    pv = p[ok]
    m = len(pv)
    if m == 0:
        return q
    o = np.argsort(pv)
    r = pv[o] * m / np.arange(1, m + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(r, 1.0)
    q[ok] = out
    return q


def cond_name_tested(cname: str) -> bool:
    """The colour-matched test of the plain colour cell ("all") is zero by construction: not tested."""
    return cname != "all"


def in_range(days, a: date, b: date) -> np.ndarray:
    d = np.asarray(days, dtype=object)
    return np.array([a <= x <= b for x in d], dtype=bool)


def cell_table(cd: pd.DataFrame, inst: str, tf: str, conds: dict, buckets, horizons=K.HORIZONS) -> list[dict]:
    """Every cell's statistics for one instrument x timeframe on the given (design) candles."""
    out = []
    coloured = cd.colour.values != 0
    day = cd.day.values
    half2 = in_range(day, *HALVES[1])
    for h in horizons:
        s = cd[f"fwd_s{h}"].values
        ok = coloured & np.isfinite(s)
        base_s = {b: float(np.nanmean(s[ok & (cd.bucket.values == b)])) if (ok & (cd.bucket.values == b)).any()
                  else np.nan for b in set(cd.bucket.values)}
        base_c = {b: float(np.mean(s[ok & (cd.bucket.values == b)] > 0)) if (ok & (cd.bucket.values == b)).any()
                  else np.nan for b in set(cd.bucket.values)}
        bs = np.array([base_s.get(b, np.nan) for b in cd.bucket.values])
        # colour-matched baseline (NOTES.md deviation D1): every candle of the SAME colour, instrument, timeframe and
        # bucket, so a market drifting one way does not make one colour look like it "continues"
        cv = cd.colour.values
        bkt = cd.bucket.values
        base_cm = {}
        for b in set(bkt):
            for cc in (1, -1):
                w = ok & (bkt == b) & (cv == cc)
                base_cm[(b, cc)] = float(np.mean(s[w])) if w.any() else np.nan
        bcm = np.array([base_cm.get((b, cc), np.nan) for b, cc in zip(bkt, cv)])
        bc = np.array([base_c.get(b, np.nan) for b in cd.bucket.values])
        rng = cd[f"fwd_rng{h}"].values
        for cname, mask in conds.items():
            for colour in (1, -1):
                for b in buckets:
                    sel = ok & mask & (cd.colour.values == colour)
                    if b != "ALL":
                        sel &= cd.bucket.values == b
                    sel &= np.isfinite(bs)
                    n = int(sel.sum())
                    row = dict(inst=inst, tf=tf, cond=cname, colour="green" if colour > 0 else "red", bucket=b, h=h,
                               n=n, days=int(len(set(day[sel]))) if n else 0)
                    if n == 0:
                        out.append(row)
                        continue
                    x = s[sel] - bs[sel]
                    eff, se = clustered_mean_se(x, day[sel])
                    cont = float(np.mean(s[sel] > 0))
                    row.update(mean_s=float(np.mean(s[sel])), effect=eff, se=se, cont=cont,
                               base_cont=float(np.mean(bc[sel])), fwd_rng=float(np.nanmean(rng[sel])),
                               up_rate=float(np.mean(colour * s[sel] > 0)))
                    if n >= MIN_N and np.isfinite(se) and se > 0:
                        z = eff / se
                        row.update(z=float(z), p=float(2 * stats.norm.sf(abs(z))))
                    xc = s[sel] - bcm[sel]
                    effc, sec = clustered_mean_se(xc, day[sel])
                    row.update(effect_cm=effc, base_cm=float(np.nanmean(bcm[sel])))
                    if n >= MIN_N and np.isfinite(sec) and sec > 0 and cond_name_tested(cname):
                        row.update(p_cm=float(2 * stats.norm.sf(abs(effc / sec))))
                    for k, hm in (("h1", ~half2), ("h2", half2)):
                        ss = sel & hm
                        row[f"effect_{k}"] = float(np.mean(s[ss] - bs[ss])) if ss.any() else np.nan
                        row[f"n_{k}"] = int(ss.sum())
                    out.append(row)
    return out


# ------------------------------------------------------------------------------------------------ data
def lake_ready():
    os.environ.setdefault("IRAALGO_LAKE", "/dev/shm/lake")
    os.environ.setdefault("IRAALGO_CACHE", "/dev/shm/r85cache")


def instrument_candles(inst: str, tf: int, upto: date | None = None) -> pd.DataFrame:
    """Featured candles of one instrument and timeframe from the lake (all dates up to `upto`)."""
    lake_ready()
    from research.hunt.r53.mcx import data as D
    m = D.load_market(inst)
    mn = K.session_minutes(K.minutes_from_market(m))
    if upto is not None:
        mn = mn[mn.day <= upto].reset_index(drop=True)
    ev = K.event_days(inst, mn.day.min(), mn.day.max())
    cd = K.build_candles(mn, tf)
    return K.add_features(cd, mn, inst, m.exp_days, ev, tf)


def daily_for(inst: str) -> pd.DataFrame:
    lake_ready()
    from research.hunt.r53.mcx import data as D
    return K.daily_candles(D.load_daily(inst))


def describe(cd: pd.DataFrame, inst: str, tf: str) -> list[dict]:
    """Plain descriptive rows per bucket: green / red / doji share, median body, range (points and Rs per mini lot),
    median range / ATR, median wicks."""
    out = []
    u = K.MINI_UNITS[inst]
    for b in ["ALL"] + sorted(set(cd.bucket)):
        x = cd if b == "ALL" else cd[cd.bucket == b]
        if not len(x):
            continue
        rng = (x.h - x.l).values
        out.append(dict(inst=inst, tf=tf, bucket=b, n=len(x), green=float((x.colour == 1).mean()),
                        red=float((x.colour == -1).mean()), doji=float((x.colour == 0).mean()),
                        med_body=float(np.median(np.abs(x.c - x.o))), med_range=float(np.median(rng)),
                        med_range_rs=float(np.median(rng) * u), med_rng_atr=float(np.nanmedian(x.rng_atr)),
                        med_uw=float(np.median(x.uw_pct)), med_lw=float(np.median(x.lw_pct))))
    return out


def one_instrument(inst: str, tfs=TFS, with_daily=True) -> tuple[list, list]:
    """All cells and descriptive rows of one instrument (design only)."""
    rows, desc = [], []
    buckets = ("ALL",) + K.BUCKETS
    for tf in tfs:
        cd = instrument_candles(inst, tf, upto=DESIGN[1])
        cd = cd[in_range(cd.day.values, *DESIGN)].reset_index(drop=True)
        tfn = f"{tf}m"
        desc += describe(cd, inst, tfn)
        conds = contexts(cd)
        shapes = SHAPES_1M if tf == 1 else SHAPES
        for s in shapes:
            conds[s] = cd[s].values.astype(bool)
        if tf == 1:
            for k in ("close_top20", "close_bottom20"):    # close-only 1m candles have no wicks
                conds.pop(k)
        rows += cell_table(cd, inst, tfn, conds, buckets)
        print(inst, tfn, len(cd), flush=True)
    if with_daily:
        dd = daily_for(inst)
        for part, (a, b) in (("design", DAY_DESIGN), ("confirm", DAY_CONFIRM)):
            x = dd[in_range(dd.day.values, a, b)].reset_index(drop=True)
            conds = contexts(x)
            for s in SHAPES:
                conds[s] = x[s].values.astype(bool)
            for k in ("above_mean_1atr", "below_mean_1atr", "activity_high", "activity_low", "event_day",
                      "expiry_week", "dte_11_20", "bigbody_activity_high"):
                conds.pop(k)
            rows += cell_table(x, inst, f"day_{part}", conds, ("ALL",))
            desc += describe(x.assign(bucket="DAY"), inst, f"day_{part}")
        print(inst, "day", len(dd), flush=True)
    return rows, desc


def run(instruments=INSTRUMENTS, procs=4):
    RESULTS.mkdir(parents=True, exist_ok=True)
    lake_ready()
    from multiprocessing import Pool
    with Pool(procs) as pool:
        parts = pool.map(one_instrument, instruments)
    rows = [r for p in parts for r in p[0]]
    desc = [r for p in parts for r in p[1]]
    T = pd.DataFrame(rows)
    # BH families: intraday design per horizon; day design per horizon (confirm rows are not tested)
    T["q"] = np.nan
    T["q_cm"] = np.nan
    if "p_cm" not in T:
        T["p_cm"] = np.nan
    intr = ~T.tf.str.startswith("day")
    for h in K.HORIZONS:
        for fam in (intr, T.tf == "day_design"):
            sel = fam & (T.h == h) & T.p.notna()
            T.loc[sel, "q"] = bh(T.loc[sel, "p"].values)
            sel = fam & (T.h == h) & T.p_cm.notna()
            T.loc[sel, "q_cm"] = bh(T.loc[sel, "p_cm"].values)
    T.to_csv(RESULTS / "part1_cells.csv.gz", index=False, float_format="%.5g")
    pd.DataFrame(desc).to_csv(RESULTS / "part1_describe.csv", index=False, float_format="%.5g")
    prim = T[intr & (T.h == PRIMARY_H)]
    summ = {"tested_primary": int(prim.p.notna().sum()), "q_lt_0.10": int((prim.q < Q_GATE).sum()),
            "q_lt_0.10_both_halves": int(((prim.q < Q_GATE) & (np.sign(prim.effect_h1) == np.sign(prim.effect_h2))).sum()),
            "q_lt_0.10_and_colour_matched_q_lt_0.10": int(((prim.q < Q_GATE) & (prim.q_cm < Q_GATE)).sum()),
            "colour_matched_q_lt_0.10": int((prim.q_cm < Q_GATE).sum()),
            "day_tested": int(T[(T.tf == "day_design") & (T.h == PRIMARY_H)].p.notna().sum()),
            "day_q_lt_0.10": int((T[(T.tf == "day_design") & (T.h == PRIMARY_H)].q < Q_GATE).sum())}
    (RESULTS / "part1_summary.json").write_text(json.dumps(summ, indent=1))
    print(summ)
    return T


if __name__ == "__main__":
    run()
