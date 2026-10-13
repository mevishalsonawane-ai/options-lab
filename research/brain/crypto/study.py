"""C1: what is predictable in crypto? The pre-registered study (PREREG.md here), DESIGN data only.

    python -m research.brain.crypto.study --symbols BTCUSDT ETHUSDT [--out research/brain/crypto/results/c1_design.json]

Reads the crypto feature store and labels (build them first: README.md), keeps design rows only (holdout.design: the
label window must end before 1 Apr 2026), and runs the 40 tests per symbol of PREREG.md with one Benjamini-Hochberg
family. The results file holds numbers only (no prices or rows), so it can be committed.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

from .. import split
from . import config, costs, holdout, labels
from .features import store

OUT = Path(__file__).resolve().parent / "results" / "c1_design.json"
WARMUP_DAYS = 32
N_SPLITS, MIN_TRAIN = 24, 6          # 30 equal blocks of days, the first 6 train only
P_LONG, P_SHORT = 0.55, 0.45
B = 2000
SEED = 20261013

HOURLY = ["range_60m_pct", "range_240m_pct", "rv_1440m_pct", "dvol", "is_weekend", "hour_utc", "ret_60m", "ret_240m",
          "ret_1440m", "funding_z_30d", "basis_z_7d", "oi_chg_60m_pct", "perp_taker_imb_60m", "vwap24_z",
          "funding_bps", "day_high_dist_pct", "day_low_dist_pct", "oi_chg_24h_pct", "dow"]


# --- models (fixed by PREREG.md) ---------------------------------------------------------------------------------------
def _prep(Xtr: np.ndarray, Xte: np.ndarray):
    keep = np.isfinite(Xtr).any(axis=0)
    Xtr, Xte = Xtr[:, keep], Xte[:, keep]
    med = np.nanmedian(Xtr, axis=0)
    Xtr = np.where(np.isfinite(Xtr), Xtr, med)
    Xte = np.where(np.isfinite(Xte), Xte, med)
    mu, sd = Xtr.mean(axis=0), Xtr.std(axis=0)
    sd[sd == 0] = 1
    return (Xtr - mu) / sd, (Xte - mu) / sd


def ridge(Xtr, ytr, Xte, alpha=1.0):
    A, T = _prep(Xtr, Xte)
    ym = ytr.mean()
    beta = np.linalg.solve(A.T @ A + alpha * np.eye(A.shape[1]), A.T @ (ytr - ym))
    return ym + T @ beta


def logistic(Xtr, ytr, Xte, C=1.0, iters=30):
    A, T = _prep(Xtr, Xte)
    A1 = np.c_[np.ones(len(A)), A]
    T1 = np.c_[np.ones(len(T)), T]
    w = np.zeros(A1.shape[1])
    lam = np.r_[0.0, np.full(A.shape[1], 1.0 / C)]
    for _ in range(iters):
        p = 1 / (1 + np.exp(-A1 @ w))
        g = A1.T @ (p - ytr) + lam * w
        Hm = (A1 * (p * (1 - p))[:, None]).T @ A1 + np.diag(lam)
        step = np.linalg.solve(Hm, g)
        w -= step
        if np.abs(step).max() < 1e-8:
            break
    return 1 / (1 + np.exp(-T1 @ w))


def walk(df: pd.DataFrame, horizon_min: int, fit):
    """Out-of-sample predictions over the walk-forward test blocks; NaN on the training-only blocks."""
    pred = np.full(len(df), np.nan)
    for tr, te in holdout.walk_forward(df.ts, N_SPLITS, horizon_min, 1, MIN_TRAIN):
        if len(tr) < 200 or not len(te):
            continue
        pred[te] = fit(df.iloc[tr], df.iloc[te])
    return pred


# --- statistics --------------------------------------------------------------------------------------------------------
def bh(p: np.ndarray) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1)
    return out


def auc(y, s) -> float:
    r = stats.rankdata(s)
    n1 = y.sum()
    n0 = len(y) - n1
    return float((r[y == 1].sum() - n1 * (n1 + 1) / 2) / (n1 * n0)) if n1 and n0 else np.nan


def auc_p(y, s, week, rng) -> float:
    """One-sided p that AUC <= 0.5: weekly-block bootstrap of the AUC."""
    a0 = auc(y, s)
    wk = pd.factorize(week)[0]
    idx = [np.flatnonzero(wk == k) for k in range(wk.max() + 1)]
    bs = []
    for _ in range(B):
        pick = np.concatenate([idx[k] for k in rng.integers(0, len(idx), len(idx))])
        bs.append(auc(y[pick], s[pick]))
    bs = np.array(bs)
    return float(((bs - a0 + 0.5) >= a0).mean()) if np.isfinite(a0) else 1.0      # shifted to the null AUC 0.5


def weekly_t(diff: np.ndarray, week) -> tuple[float, int]:
    """One-sided p that the mean of weekly means of diff is <= 0."""
    w = pd.Series(diff).groupby(np.asarray(week)).mean().dropna()
    if len(w) < 5:
        return 1.0, len(w)
    t = stats.ttest_1samp(w, 0, alternative="greater")
    return float(t.pvalue), len(w)


def _week(ts) -> np.ndarray:
    return pd.DatetimeIndex(ts).to_period("W-SUN").astype(str).values


# --- data --------------------------------------------------------------------------------------------------------------
def load(root: Path, sym: str) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    F = store.load(root, sym, columns=["ts", "precision", *HOURLY])
    L = labels.load(root, sym)
    if F.empty or L.empty:
        raise SystemExit(f"{sym}: build the cube, features and labels first (README.md)")
    D = split.join_forward(F, L[["ts", "y_ret_60m", "y_range_60m", "y_ret_1440m", "y_range_1440m"]])
    D = D[D.ts >= D.ts.min().normalize() + pd.Timedelta(days=WARMUP_DAYS)]
    H = D[D.ts.dt.minute == 59]
    H = holdout.design(H, 60)
    H = H[np.isfinite(H.y_ret_60m) & (H.y_range_60m > 0) & np.isfinite(H.ret_60m)].reset_index(drop=True)
    Dd = D[(D.ts.dt.hour == 23) & (D.ts.dt.minute == 59)]
    Dd = holdout.design(Dd, 1440)
    Dd = Dd[np.isfinite(Dd.y_ret_1440m) & (Dd.y_range_1440m > 0) & np.isfinite(Dd.ret_1440m)].reset_index(drop=True)
    holdout.assert_design(H.ts + pd.Timedelta(minutes=60))
    holdout.assert_design(Dd.ts + pd.Timedelta(minutes=1440))
    with np.errstate(invalid="ignore", divide="ignore"):
        hi = 1 / (1 + Dd.day_high_dist_pct / 100)
        lo = 1 / (1 + Dd.day_low_dist_pct / 100)
        Dd["log_day_range"] = np.log((hi - lo) * 100)                 # today's full high-low, % of the 23:59 close
    Dd["log_range_7d"] = Dd.log_day_range.rolling(7, min_periods=7).mean()
    Dd["log_range_20d"] = Dd.log_day_range.rolling(20, min_periods=20).mean()
    Dd["weekend_next"] = ((Dd.dow + 1) % 7 >= 5).astype(float)
    meta = dict(hourly_rows=len(H), daily_rows=len(Dd), first=str(H.ts.min()), last=str(H.ts.max()),
                last_daily=str(Dd.ts.max()), signature=store.signature() if hasattr(store, "signature") else None)
    return H, Dd, meta


def _log(a):
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.log(np.asarray(a, float))


# --- the tests ---------------------------------------------------------------------------------------------------------
def test_a1(H, sym):
    y = _log(H.y_range_60m)
    H = H.assign(_y=y, _hw=H.hour_utc * 2 + H.is_weekend)

    def tod(tr, te):
        m = tr.groupby("_hw")._y.mean()
        return te._hw.map(m).fillna(tr._y.mean()).to_numpy()

    def fit(tr, te):
        cols = lambda d: np.c_[_log(d.range_60m_pct), _log(d.range_240m_pct), _log(d.rv_1440m_pct), _log(d.dvol),
                               tod(tr, d), d.is_weekend]
        return ridge(cols(tr), tr._y.to_numpy(), cols(te))
    pred = walk(H, 60, fit)
    base = walk(H, 60, lambda tr, te: tod(tr, te))
    ok = np.isfinite(pred) & np.isfinite(base) & np.isfinite(y)
    em, eb = np.abs(y - pred)[ok], np.abs(y - base)[ok]
    p, nw = weekly_t(eb - em, _week(H.ts[ok]))
    return dict(id=f"A1-{sym}", n=int(ok.sum()), weeks=nw, mae_model=round(float(em.mean()), 4),
                mae_base=round(float(eb.mean()), 4), skill_pct=round(100 * (1 - em.mean() / eb.mean()), 1), p=p,
                bar="skill>=5", effect=round(100 * (1 - em.mean() / eb.mean()), 1))


def test_a2(Dd, sym):
    y = _log(Dd.y_range_1440m)
    Dd = Dd.assign(_y=y)

    def fit(tr, te):
        cols = lambda d: np.c_[d.log_day_range, d.log_range_7d, _log(d.rv_1440m_pct), _log(d.dvol), d.weekend_next]
        return ridge(cols(tr), tr._y.to_numpy(), cols(te))
    pred = walk(Dd, 1440, fit)
    base = Dd.log_range_20d.to_numpy()
    ok = np.isfinite(pred) & np.isfinite(base) & np.isfinite(y)
    em, eb = np.abs(y - pred)[ok], np.abs(y - base)[ok]
    p, nw = weekly_t(eb - em, _week(Dd.ts[ok]))
    return dict(id=f"A2-{sym}", n=int(ok.sum()), weeks=nw, mae_model=round(float(em.mean()), 4),
                mae_base=round(float(eb.mean()), 4), skill_pct=round(100 * (1 - em.mean() / eb.mean()), 1), p=p,
                bar="skill>=5", effect=round(100 * (1 - em.mean() / eb.mean()), 1))


def _trades(df, prob, ret_col, hold, sym, stress=1.0):
    side = np.where(prob >= P_LONG, 1, np.where(prob <= P_SHORT, -1, 0))
    t = side != 0
    if not t.any():
        return dict(trades=0)
    net = costs.net_returns(df[ret_col].to_numpy()[t], sym, side[t], hold, df.funding_bps.to_numpy()[t] / 1e4,
                            entry_ts=df.ts[t] + pd.Timedelta(minutes=1), stress=stress)
    gross = side[t] * df[ret_col].to_numpy()[t]
    return dict(trades=int(t.sum()), hit_pct=round(100 * float((gross > 0).mean()), 1),
                gross_bps=round(100 * float(gross.mean()), 2), net_bps=round(100 * float(net.mean()), 2))


def _direction(df, sym, tid, hold, ret_col, cols, rng):
    y = (df[ret_col] > 0).astype(float).to_numpy()
    keep = (df[ret_col] != 0).to_numpy()
    df, y = df[keep].reset_index(drop=True), y[keep]
    df = df.assign(_y=y)
    prob = walk(df, hold, lambda tr, te: logistic(cols(tr), tr._y.to_numpy(), cols(te)))
    ok = np.isfinite(prob)
    a = auc(y[ok], prob[ok])
    p = auc_p(y[ok], prob[ok], _week(df.ts[ok]), rng)
    tr1 = _trades(df[ok], prob[ok], ret_col, hold, sym)
    tr2 = _trades(df[ok], prob[ok], ret_col, hold, sym, stress=2.0)
    return dict(id=f"{tid}-{sym}", n=int(ok.sum()), auc=round(a, 4), p=p, base_up_pct=round(100 * y[ok].mean(), 1),
                bar="auc>=0.52 and net>0", effect=round(a, 4), costs=tr1, costs_2x_slip=tr2)


def test_b1(H, sym, rng):
    def cols(d):
        hr = d.hour_utc.to_numpy() * 2 * np.pi / 24
        return np.c_[d.ret_60m, d.ret_240m, d.ret_1440m, d.funding_z_30d, d.basis_z_7d, d.oi_chg_60m_pct,
                     d.perp_taker_imb_60m, d.vwap24_z, np.sin(hr), np.cos(hr), d.is_weekend]
    return _direction(H, sym, "B1", 60, "y_ret_60m", cols, rng)


def test_b2(Dd, sym, rng):
    def cols(d):
        return np.c_[d.ret_1440m, d.log_day_range, d.funding_z_30d, d.basis_z_7d, d.oi_chg_24h_pct, d.vwap24_z,
                     d.weekend_next]
    return _direction(Dd, sym, "B2", 1440, "y_ret_1440m", cols, rng)


def test_c(Dd, sym, tid, col):
    ok = np.isfinite(Dd[col]) & np.isfinite(Dd.y_ret_1440m)
    r, p = stats.spearmanr(Dd[col][ok], Dd.y_ret_1440m[ok])
    q5 = pd.qcut(Dd[col][ok], 5, labels=False, duplicates="drop")
    by = (Dd.y_ret_1440m[ok].groupby(q5).mean() * 100).round(1)
    return dict(id=f"{tid}-{sym}", n=int(ok.sum()), rho=round(float(r), 4), p=float(p), bar="q<0.05",
                effect=round(float(r), 4), next_day_bps_by_quintile=[float(x) for x in by.values])


def test_d1(H, Dd, sym):
    a = np.abs(H.y_ret_60m.to_numpy())
    wk = _week(H.ts)
    end_hour = H.ts + pd.Timedelta(minutes=1)
    we = (end_hour.dt.weekday >= 5).to_numpy()
    per = pd.DataFrame({"wk": wk, "we": we, "a": a}).groupby(["wk", "we"]).a.mean().unstack().dropna()
    t = stats.ttest_ind(per[True], per[False], equal_var=False)      # PREREG: Welch t on weekly means
    size = dict(id=f"D1s-{sym}", n=int(len(a)), weeks=len(per), weekend_abs_bps=round(100 * per[True].mean(), 1),
                weekday_abs_bps=round(100 * per[False].mean(), 1), ratio=round(per[True].mean() / per[False].mean(), 3),
                p=float(t.pvalue), bar="q<0.05", effect=round(per[True].mean() / per[False].mean(), 3))
    r = Dd.y_ret_1440m.to_numpy()
    dw = ((Dd.ts + pd.Timedelta(minutes=1)).dt.weekday >= 5).to_numpy()
    t2 = stats.ttest_ind(r[dw], r[~dw], equal_var=False)
    diff = r[dw].mean() - r[~dw].mean()
    rt = costs.round_trip(sym, "perp", 1440) * 100
    dirn = dict(id=f"D1d-{sym}", n=int(len(r)), weekend_days=int(dw.sum()), weekend_bps=round(100 * r[dw].mean(), 1),
                weekday_bps=round(100 * r[~dw].mean(), 1), p=float(t2.pvalue), bar="q<0.05 and |diff|>round trip",
                effect=round(100 * diff, 1), round_trip_bps=round(100 * rt, 1), beats_costs=bool(abs(diff) > rt))
    return [size, dirn]


def test_d2(H, sym):
    out = []
    start_hour = (H.ts + pd.Timedelta(minutes=1)).dt.hour.to_numpy()
    rt = costs.round_trip(sym, "perp", 60)
    for hh in range(24):
        r = H.y_ret_60m.to_numpy()[start_hour == hh]
        t = stats.ttest_1samp(r, 0)
        out.append(dict(id=f"D2-{hh:02d}-{sym}", n=int(len(r)), mean_bps=round(100 * r.mean(), 2),
                        abs_bps=round(100 * np.abs(r).mean(), 1), p=float(t.pvalue), bar="q<0.05 and |mean|>round trip",
                        effect=round(100 * r.mean(), 2), beats_costs=bool(abs(r.mean()) > rt * 100)))
    return out


def test_d4(Dd, sym):
    out = []
    wd = (Dd.ts + pd.Timedelta(minutes=1)).dt.weekday.to_numpy()
    rt = costs.round_trip(sym, "perp", 1440)
    names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    for k in range(7):
        r = Dd.y_ret_1440m.to_numpy()[wd == k]
        t = stats.ttest_1samp(r, 0)
        out.append(dict(id=f"D4-{names[k]}-{sym}", n=int(len(r)), mean_bps=round(100 * r.mean(), 1), p=float(t.pvalue),
                        bar="q<0.05 and |mean|>round trip", effect=round(100 * r.mean(), 1),
                        beats_costs=bool(abs(r.mean()) > rt * 100)))
    return out


def describe(H, Dd, sym) -> dict:
    """Post-hoc descriptions (not tests): the hour-of-day size shape, typical ranges, funding level."""
    start_hour = (H.ts + pd.Timedelta(minutes=1)).dt.hour
    rng_by_h = (H.y_range_60m.groupby(start_hour).median()).round(3)
    return dict(
        median_next_hour_range_pct=round(float(H.y_range_60m.median()), 3),
        next_hour_range_p10_p90=[round(float(x), 3) for x in H.y_range_60m.quantile([0.1, 0.9])],
        median_next_day_range_pct=round(float(Dd.y_range_1440m.median()), 2),
        next_day_range_p10_p90=[round(float(x), 2) for x in Dd.y_range_1440m.quantile([0.1, 0.9])],
        busiest_hour_utc=int(rng_by_h.idxmax()), quietest_hour_utc=int(rng_by_h.idxmin()),
        busiest_quietest_ratio=round(float(rng_by_h.max() / rng_by_h.min()), 2),
        median_range_by_hour_pct={int(k): float(v) for k, v in rng_by_h.items()},
        funding_bps_median=round(float(Dd.funding_bps.median()), 2),
        funding_positive_pct=round(100 * float((Dd.funding_bps > 0).mean()), 1),
        dvol_median=round(float(Dd.dvol.median()), 1) if Dd.dvol.notna().any() else None,
    )


def posthoc(root: Path, H, Dd, sym, rng) -> dict:
    """Post-hoc checks, written after the tests ran (13 Oct 2026). Descriptions, not tests; not in the BH family.
    1. B1 with the entry one minute later (the label starts at t+1): an effect from the last trade's bounce vanishes.
    2. Single-input AUCs for B1 (sign chosen in the train period is not needed: AUC < 0.5 means the reverse sign).
    3. A1/A2 against simpler baselines: the last 60-minute range alone, and (BTC) DVOL alone, both fitted in train."""
    out = {}
    closes = pd.concat([pd.read_parquet(p, columns=["ts", "close"]) for p in
                        sorted((config.paths(root).cube(sym)).glob("*/*.parquet"))], ignore_index=True)
    c = closes.set_index("ts").close
    t1 = H.ts + pd.Timedelta(minutes=1)
    with np.errstate(invalid="ignore", divide="ignore"):
        y_late = np.log(c.reindex(t1 + pd.Timedelta(minutes=60)).to_numpy() / c.reindex(t1).to_numpy()) * 100
    Hl = H.assign(y_late=y_late)
    Hl = Hl[np.isfinite(Hl.y_late) & (Hl.y_late != 0)].reset_index(drop=True)

    def cols(d):
        hr = d.hour_utc.to_numpy() * 2 * np.pi / 24
        return np.c_[d.ret_60m, d.ret_240m, d.ret_1440m, d.funding_z_30d, d.basis_z_7d, d.oi_chg_60m_pct,
                     d.perp_taker_imb_60m, d.vwap24_z, np.sin(hr), np.cos(hr), d.is_weekend]
    yl = (Hl.y_late > 0).astype(float).to_numpy()
    Hl = Hl.assign(_y=yl)
    pl = walk(Hl, 61, lambda tr, te: logistic(cols(tr), tr._y.to_numpy(), cols(te)))
    ok = np.isfinite(pl)
    out["b1_entry_1min_later_auc"] = round(auc(yl[ok], pl[ok]), 4)
    out["b1_entry_1min_later_n"] = int(ok.sum())
    y = (H.y_ret_60m > 0).astype(float).to_numpy()
    single = {}
    for f in ["ret_60m", "ret_240m", "ret_1440m", "funding_z_30d", "basis_z_7d", "oi_chg_60m_pct", "perp_taker_imb_60m",
              "vwap24_z"]:
        v = H[f].to_numpy()
        m = np.isfinite(v) & (H.y_ret_60m != 0).to_numpy()
        single[f] = round(auc(y[m], v[m]), 4)
    out["b1_single_input_auc"] = single
    ly = _log(H.y_range_60m)
    H2 = H.assign(_y=ly)
    for name, f in (("last60", lambda d: np.c_[_log(d.range_60m_pct)]), ("dvol", lambda d: np.c_[_log(d.dvol)])):
        if name == "dvol" and not H.dvol.notna().any():
            continue
        pr = walk(H2, 60, lambda tr, te, f=f: ridge(f(tr), tr._y.to_numpy(), f(te)))
        ok = np.isfinite(pr)
        out[f"a1_mae_{name}_only"] = round(float(np.abs(ly - pr)[ok].mean()), 4)
    ld = _log(Dd.y_range_1440m)
    D2 = Dd.assign(_y=ld)
    for name, f in (("yesterday", lambda d: np.c_[d.log_day_range]), ("dvol", lambda d: np.c_[_log(d.dvol)])):
        if name == "dvol" and not Dd.dvol.notna().any():
            continue
        pr = walk(D2, 1440, lambda tr, te, f=f: ridge(f(tr), tr._y.to_numpy(), f(te)))
        ok = np.isfinite(pr)
        out[f"a2_mae_{name}_only"] = round(float(np.abs(ld - pr)[ok].mean()), 4)
    # a portable next-hour range formula, fitted on ALL design rows (like R16's): log range = a + b . inputs
    X = np.c_[_log(H.range_60m_pct), _log(H.range_240m_pct), _log(H.rv_1440m_pct)]
    m = np.isfinite(X).all(axis=1) & np.isfinite(ly)
    coef, *_ = np.linalg.lstsq(np.c_[np.ones(m.sum()), X[m]], ly[m], rcond=None)
    out["a1_formula"] = dict(inputs=["const", "log range60", "log range240", "log rv1440"],
                             coef=[round(float(x), 3) for x in coef], n=int(m.sum()))
    return out


def run(root: Path, symbols, out: Path = OUT) -> dict:
    rng = np.random.default_rng(SEED)
    tests, meta, desc = [], {}, {}
    for s in symbols:
        H, Dd, meta[s] = load(root, s)
        tests += [test_a1(H, s), test_a2(Dd, s), test_b1(H, s, rng), test_b2(Dd, s, rng),
                  test_c(Dd, s, "C1", "funding_z_30d"), test_c(Dd, s, "C2", "basis_z_7d"),
                  test_c(Dd, s, "C3", "oi_chg_24h_pct")]
        tests += test_d1(H, Dd, s) + test_d2(H, s) + test_d4(Dd, s)
        desc[s] = describe(H, Dd, s)
        desc[s]["posthoc"] = posthoc(root, H, Dd, s, rng)
        print(f"{s}: {meta[s]['hourly_rows']} hourly and {meta[s]['daily_rows']} daily design rows", flush=True)
    q = bh(np.array([t["p"] for t in tests]))
    for t, qq in zip(tests, q):
        t["q"] = round(float(qq), 4)
        t["p"] = round(float(t["p"]), 5)
        t["pass"] = bool(_passes(t))
    res = dict(study="C1", prereg="research/brain/crypto/PREREG.md", holdout_start=str(config.HOLDOUT_START),
               holdout_read=False, tests_in_family=len(tests), passed=[t["id"] for t in tests if t["pass"]],
               data=meta, describe=desc, tests=tests)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, indent=1) + "\n")
    print(f"{len(tests)} tests, {len(res['passed'])} pass: {res['passed']} -> {out}")
    return res


def _passes(t: dict) -> bool:
    if t["q"] >= 0.05:
        return False
    k = t["id"].split("-")[0]
    if k in ("A1", "A2"):
        return t["skill_pct"] >= 5
    if k in ("B1", "B2"):
        return t["auc"] >= 0.52 and t["costs"].get("net_bps", -1) > 0
    if k in ("D1d", "D2", "D4"):
        return t["beats_costs"]
    return True


def main(argv=None):
    from . import config as cfg
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.study")
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT", "ETHUSDT"])
    ap.add_argument("--cache", default=None)
    ap.add_argument("--out", default=str(OUT))
    a = ap.parse_args(argv)
    run(cfg.paths(a.cache).root, a.symbols, Path(a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
