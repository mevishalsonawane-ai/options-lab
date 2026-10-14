"""R27 decision rows and point-in-time features (PREREG.md "Features").

    python3 research/hunt/r27/r27feat.py build      # -> <R27_OUT>/rows_NIFTY.parquet (features + outcome labels)

A decision row = (NIFTY expiry day, bar close t in 12:00 .. 14:55 every 5 minutes, an eligible OTM option priced
Rs 1-30). Features read rows <= t of the day, plus EARLIER expiry days only (the volatility seasonality). Labels
(`y_*`) are hindsight and are never read by a feature or a rule's decision.

R25's anatomy features (research/hunt/r25/anatomy.py) are reused with the band widened to Rs 1-30. New families:
  - VOL: realized volatility of the index so far, a forecast of the variance left to 15:30 (recent per-minute variance x
    minutes left x the seasonal ratio seen on earlier expiries at the same minute), the option's fair value at that
    forecast (research/brain/bs.py), market / fair (fv), market / BS value at India VIX (fv_vix), our own IV / forecast.
  - GAMMA: open interest x Black-Scholes gamma per stored strike (dealer-gamma proxy): the pin strike (largest OI x
    gamma), spot's distance from it toward the option in expected moves, the call-minus-put gamma share, and the total
    gamma per contract.
  - MOMENTUM: the first-30-minute return and the return since 14:00 (if t > 14:00), signed toward the option.
"""
from __future__ import annotations

import math
import sys
from multiprocessing import Pool

import numpy as np
import pandas as pd

import r27cfg as K27
import anatomy as A
import r25data as D
import r25lib as R
import r26lib as L26
from r25data import C, N, OI, minute
from research.brain import bs

A.BAND = K27.BAND                                       # anatomy.option_rows reads its module BAND at call time
DECISIONS = list(range(R.close_row(12, 0), R.close_row(14, 55) + 1, 5))
YEAR_MIN = 252 * 375                                    # trading minutes a year
LAST60 = 60


def logret(day) -> np.ndarray:
    c = day.ix[:, 3]
    r = np.full(N, np.nan)
    r[1:] = np.log(c[1:] / c[:-1])
    return r


def var_profile(day) -> tuple[np.ndarray, np.ndarray]:
    """Per decision t: (sum of squared 1-min returns over the last 60 min / count, the same over (t, 15:30]) - the
    second is hindsight and is used only as an EARLIER day's input to the seasonal ratio."""
    r2 = logret(day) ** 2
    past, rem = [], []
    for t in DECISIONS:
        a = r2[max(1, t - LAST60 + 1): t + 1]
        b = r2[t + 1:]
        past.append(np.nanmean(a) if np.isfinite(a).any() else np.nan)
        rem.append(np.nanmean(b) if np.isfinite(b).any() else np.nan)
    return np.array(past), np.array(rem)


def seasonal(prior: list[tuple[np.ndarray, np.ndarray]]) -> np.ndarray:
    """Per decision t, the ratio of mean remaining per-minute variance to mean last-60 per-minute variance over the
    earlier expiry days (1.0 with fewer than 10 earlier days)."""
    if len(prior) < 10:
        return np.ones(len(DECISIONS))
    p = np.array([x[0] for x in prior])
    q = np.array([x[1] for x in prior])
    with np.errstate(divide="ignore", invalid="ignore"):
        r = np.nanmean(q, axis=0) / np.nanmean(p, axis=0)
    return np.where(np.isfinite(r) & (r > 0), r, 1.0)        # a flat stretch (no variance) falls back to 1.0


def mins_left(t: int) -> int:
    return (15 * 60 + 30) - (9 * 60 + 15 + t + 1)


def vol_state(day, t: int, ratio: float) -> dict:
    r = logret(day)[: t + 1]
    ok = np.isfinite(r)
    out = {}
    out["rv_day"] = math.sqrt(np.mean(r[ok] ** 2) * YEAR_MIN) * 100 if ok.sum() > 10 else math.nan
    a = r[max(1, t - LAST60 + 1):]
    a = a[np.isfinite(a)]
    out["rv60"] = math.sqrt(np.mean(a ** 2) * YEAR_MIN) * 100 if len(a) > 10 else math.nan
    b = r[max(1, t - 14):]
    b = b[np.isfinite(b)]
    out["rv15"] = math.sqrt(np.mean(b ** 2) * YEAR_MIN) * 100 if len(b) > 5 else math.nan
    out["rv_exp"] = out["rv15"] / out["rv60"] if out["rv60"] and out["rv60"] == out["rv60"] and out["rv60"] > 0 else math.nan
    out["seas"] = ratio
    # forecast annualised vol for the time left: recent per-minute variance x the seasonal ratio
    out["rv_fc"] = out["rv60"] * math.sqrt(ratio) if out["rv60"] == out["rv60"] and ratio == ratio and ratio > 0 else math.nan
    return out


def gamma_state(day, t: int, s: float, sig: float) -> dict:
    """Dealer-gamma proxy from the stored chain's OI at t (OI x BS gamma at one vol for every strike)."""
    out = {"pin": math.nan, "gam_cp": math.nan, "gam_tot": math.nan}
    if not (s == s and sig == sig and sig > 0):
        return out
    T = max(mins_left(t), 1) / YEAR_MIN
    ch = L26.chain(day)
    tot = {"C": 0.0, "P": 0.0}
    per_k: dict = {}
    n_oi = 0.0
    for side in ("C", "P"):
        pr = ch.present[side][:, t]
        oi = ch.oi[side][:, t]
        ok = pr & np.isfinite(oi)
        if not ok.any():
            continue
        ks = ch.k[side][ok]
        g = bs.greeks(np.full(len(ks), s), ks, np.full(len(ks), T), np.full(len(ks), sig), side == "C")[1]
        w = oi[ok] * np.nan_to_num(g)
        tot[side] = float(w.sum())
        n_oi += float(oi[ok].sum())
        for k, x in zip(ks, w):
            per_k[k] = per_k.get(k, 0.0) + x
    if not per_k or n_oi <= 0:
        return out
    out["pin"] = float(max(per_k, key=per_k.get))
    allg = tot["C"] + tot["P"]
    out["gam_cp"] = (tot["C"] - tot["P"]) / allg if allg > 0 else math.nan
    out["gam_tot"] = allg / n_oi * s / 100           # gamma per contract, scaled per 1% move
    return out


def rows_at(day, t: int, ratio: float) -> list[dict]:
    st = A.day_state(day, t)
    base = A.option_rows(day, t, st)
    if not base:
        return []
    s = st["spot"]
    vs = vol_state(day, t, ratio)
    vix = st["vix"] / 100 if st["vix"] == st["vix"] else math.nan
    gs = gamma_state(day, t, s, vix if vix == vix else (vs["rv_fc"] / 100 if vs["rv_fc"] == vs["rv_fc"] else math.nan))
    T = max(mins_left(t), 1) / YEAR_MIN
    first30 = (day.ix[minute(9, 44), 3] / day.ix[0, 0] - 1) * 100 if day.ix[0, 0] == day.ix[0, 0] else math.nan
    r14 = R.close_row(14, 0)
    since14 = (s / day.ix[r14, 3] - 1) * 100 if t > r14 and day.ix[r14, 3] == day.ix[r14, 3] else 0.0
    out = []
    for f in base:
        sgn = 1 if f["side"] == "C" else -1
        k, p = f["strike"], f["price"]
        f["band"] = "1-10" if p <= 10 + R.EPS else ("10-15" if p < 15 - R.EPS else "15-30")
        f.update({x: vs[x] for x in ("rv_day", "rv60", "rv15", "rv_exp", "seas", "rv_fc")})
        sig = vs["rv_fc"] / 100 if vs["rv_fc"] == vs["rv_fc"] else math.nan
        fair = float(bs.price(s, k, T, sig, sgn > 0)) if sig == sig and sig > 0 else math.nan
        f["fair"] = fair
        f["fv"] = p / fair if fair == fair and fair > 0.01 else (math.inf if fair == fair else math.nan)
        fvx = float(bs.price(s, k, T, vix, sgn > 0)) if vix == vix and vix > 0 else math.nan
        f["fv_vix"] = p / fvx if fvx == fvx and fvx > 0.01 else (math.inf if fvx == fvx else math.nan)
        iv = float(bs.implied_vol(np.array([p]), np.array([s]), np.array([k]), np.array([T]), np.array([sgn > 0]))[0])
        f["iv_bs"] = iv * 100 if iv == iv else math.nan
        f["iv_rv"] = f["iv_bs"] / vs["rv_fc"] if f["iv_bs"] == f["iv_bs"] and vs["rv_fc"] == vs["rv_fc"] and vs["rv_fc"] > 0 else math.nan
        em = st["em"]
        f["pin_dist"] = sgn * (s - gs["pin"]) / em if gs["pin"] == gs["pin"] and em == em and em > 0 else math.nan
        f["gam_cp"] = sgn * gs["gam_cp"] if gs["gam_cp"] == gs["gam_cp"] else math.nan
        f["gam_tot"] = gs["gam_tot"]
        f["first30"] = sgn * first30 if first30 == first30 else math.nan
        f["since14"] = sgn * since14
        f["pcr"] = st["pcr"]
        # return from the previous close to now, toward the option (Baltussen et al. 2021 "rest of day" momentum)
        f["rod"] = sgn * (s / day.prev_close - 1) * 100 if day.prev_close == day.prev_close and day.prev_close > 0 else math.nan
        f["_spot"] = s
        out.append(f)
    return out


def labels(day, f: dict) -> dict:
    """Hindsight: the trade at Rs 2,000 (R26's engine) held to 15:05 (E1) and with Boss's 2x + step lock (E3)."""
    sgn = 1 if f["side"] == "C" else -1
    o = {}
    for ex in ("E1", "E3"):
        tr = L26.simulate(day, f["strike"], sgn, f["t"], f["price"], ex, K27.BUDGET)
        o[f"y_net_{ex}"] = tr.net if tr else math.nan
        o[f"y_p1000_{ex}"] = tr.per_1000 if tr else math.nan
        if ex == "E1":
            o["y_peak"] = tr.peak_mult if tr else math.nan
            o["y_risked"] = tr.risked if tr else math.nan
    return o


def day_rows(day, ratios: np.ndarray, with_labels: bool = True) -> list[dict]:
    out = []
    for j, t in enumerate(DECISIONS):
        for f in rows_at(day, t, float(ratios[j])):
            if with_labels:
                f.update(labels(day, f))
            out.append(f)
    return out


def _job(args):
    day, ratios = args
    D.clean(day)
    return day_rows(day, ratios)


def days() -> list:
    return sorted(D.load(K27.UND, "all"), key=lambda d: d.day)


def all_ratios(ds: list) -> list[np.ndarray]:
    """The seasonal ratio for each day from EARLIER days only."""
    prof = [var_profile(d) for d in ds]
    return [seasonal(prof[:i]) for i in range(len(ds))]


def build(procs: int = 4) -> pd.DataFrame:
    ds = days()
    rat = all_ratios(ds)
    rows = []
    with Pool(procs) as pool:
        for r in pool.imap(_job, list(zip(ds, rat)), chunksize=2):
            rows += r
    df = pd.DataFrame(rows)
    K27.OUT.mkdir(parents=True, exist_ok=True)
    df.to_parquet(K27.OUT / "rows_NIFTY.parquet")
    return df


if __name__ == "__main__":
    if sys.argv[1:2] == ["build"]:
        df = build()
        print(len(df), "rows", df.day.nunique(), "days")
