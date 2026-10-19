"""R87 Part 1: the candle atlas (PREREG sections 3-7).

    python -m research.hunt.r87_candle_anatomy.atlas design     # design window only: results/atlas_design.*
    python -m research.hunt.r87_candle_anatomy.atlas final      # the locked holdout, ONCE (with rules.py final)

Families tested (each its own Benjamini-Hochberg family, primary outcome f_ret5 = the move over the next 5 candles of
the same session, in ATR, minus the matched baseline):
  P1  instrument x timeframe (1m, 5m, 15m) x candle family (shapes, patterns, footprints)
  P2  instrument x timeframe (5m, 15m) x candle family x context condition
  P3  instrument x timeframe (5m, 15m) x data-driven grid cell (n >= 200)
Secondary outcomes (descriptive, own BH per outcome over P1): f_ret1, f_ret3, f_ret10, f_ret20, f_upfirst20,
f_lobrk10, f_hibrk10, f_mfe10, f_mae10.
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from . import build as B
from . import candles as CA
from . import cfg
from . import context as CX
from . import outcome as OC
from . import stats as ST

PRIMARY = "f_ret5"
SECONDARY = ("f_ret1", "f_ret3", "f_ret10", "f_ret20", "f_upfirst20", "f_lobrk10", "f_hibrk10", "f_mfe10", "f_mae10")
FAMS = CA.FAMILIES + CX.FOOTPRINTS
PATH_AT = (-50, -20, -10, -5, -3, -1, 0, 1, 3, 5, 10, 20, 50)


def load_all(frm: date, to: date, tfs=cfg.TFS) -> pd.DataFrame:
    parts = []
    for u in cfg.UNDS:
        for tf in tfs:
            k = B.load(u, tf, frm, to)
            parts.append(k)
    df = pd.concat(parts, ignore_index=True)
    df["dkey"] = df.und + "|" + df.day.dt.strftime("%Y-%m-%d")
    return df


def fold_of(day: pd.Series, folds) -> np.ndarray:
    d = day.dt.date.to_numpy()
    out = np.full(len(d), -1)
    for i, (a, b) in enumerate(folds):
        out[(d >= a) & (d <= b)] = i
    return out


def run_family(df: pd.DataFrame, bases: dict, mask_fn, label: dict, folds=None, extra_window=None) -> dict:
    """One tested cell: effects on the primary and secondary outcomes (+ per-fold primary effects, points)."""
    y = df[PRIMARY].to_numpy(float)
    day = df.dkey.to_numpy()
    m = mask_fn(df)
    r = dict(label)
    e = ST.effect(y, bases[PRIMARY], day, m)
    r.update(e)
    ok = m & np.isfinite(y)
    pts = (y * df.atr.to_numpy(float))[ok]
    r["move_pts"] = float(np.mean(pts)) if len(pts) else np.nan
    r["up_rate"] = float(np.mean(y[ok] > 0)) if ok.any() else np.nan
    r["base_up_rate"] = float(np.mean(bases["_up"][ok])) if ok.any() else np.nan
    for s in SECONDARY:
        es = ST.effect(df[s].to_numpy(float), bases[s], day, m)
        r[s + "_n"], r[s + "_raw"], r[s + "_base"], r[s + "_eff"], r[s + "_p"] = (
            es["n"], es["raw"], es["base"], es["eff"], es["p"])
    if folds is not None:
        fo = df["_fold"].to_numpy()
        r["fold_eff"] = [ST.effect(y, bases[PRIMARY], day, m & (fo == i))["eff"] for i in range(len(folds))]
    if extra_window is not None:
        w = df["_rulewin"].to_numpy(bool)
        ew = ST.effect(y, bases[PRIMARY], day, m & w)
        okw = m & w & np.isfinite(y)
        r["rw_n"], r["rw_eff"], r["rw_p"] = ew["n"], ew["eff"], ew["p"]
        r["rw_move_pts"] = float(np.mean((y * df.atr.to_numpy(float))[okw])) if okw.any() else np.nan
    return r


def compute_bases(df: pd.DataFrame) -> dict:
    b = {s: ST.baseline(df, s) for s in (PRIMARY,) + SECONDARY}
    df["_upflag"] = (df[PRIMARY] > 0).astype(float).where(df[PRIMARY].notna())
    b["_up"] = ST.baseline(df, "_upflag")
    return b


def paths(df_all: pd.DataFrame, fams, purge_after: date | None) -> list:
    """Mean path from 50 candles before to 50 after (f_any style, sessions crossed) per instrument x tf x family,
    beside the mean path of all candles of the same colour (the simple baseline)."""
    out = []
    K = OC.PATH_K
    at = np.array(PATH_AT) + K
    for (u, tf), k in df_all.groupby(["und", "tf"]):
        k = k.reset_index(drop=True)
        c, atr = k.c.to_numpy(float), k.atr.to_numpy(float)
        ok = np.isfinite(atr)
        if purge_after is not None:                       # the after-part must not reach past the window
            ok &= np.r_[k.day.dt.date.to_numpy()[K:] <= purge_after, np.zeros(K, bool)]
        colv = k.col.to_numpy()
        base = {}
        for cc in (-1, 1):
            ii = np.flatnonzero(ok & (colv == cc))
            if len(ii) > 20000:
                ii = np.random.default_rng(cfg.SEED).choice(ii, 20000, replace=False)
            base[cc] = np.nanmean(OC.path_matrix(c, atr, ii), 0)[at] if len(ii) else np.full(len(at), np.nan)
        for f in fams:
            ii = np.flatnonzero(ok & k[f].to_numpy(bool))
            if len(ii) < cfg.MIN_N:
                continue
            P = OC.path_matrix(c, atr, ii)[:, at]
            sub = colv[ii]
            bl = np.where(sub[:, None] > 0, base[1][None, :], np.where(sub[:, None] < 0, base[-1][None, :], np.nan))
            out.append(dict(und=u, tf=int(tf), family=f, n=int(len(ii)), at=list(PATH_AT),
                            path=[round(float(x), 3) for x in np.nanmean(P, 0)],
                            base=[round(float(x), 3) for x in np.nanmean(bl, 0)]))
    return out


def atlas(df: pd.DataFrame, folds=None, rule_window=None) -> dict:
    df = df.reset_index(drop=True)
    if folds is not None:
        df["_fold"] = fold_of(df.day, folds)
    if rule_window is not None:
        df["_rulewin"] = (df.day.dt.date >= rule_window[0]).to_numpy()
    bases_all = compute_bases(df)
    und, tf = df.und.to_numpy(), df.tf.to_numpy()
    res = {"P1": [], "P2": [], "P3": []}
    for u in cfg.UNDS:
        for t in cfg.TFS:
            sel = (und == u) & (tf == t)
            if not sel.any():
                continue
            sub = df[sel].reset_index(drop=True)              # baselines were computed on the whole period
            bases = {kk: v[sel] for kk, v in bases_all.items()}
            for f in FAMS:
                res["P1"].append(run_family(sub, bases, lambda d, f=f: d[f].to_numpy(bool),
                                            dict(und=u, tf=int(t), family=f, cond=""), folds, rule_window))
            if t == 1:
                continue
            for f in FAMS:
                fm = sub[f].to_numpy(bool)
                if fm.sum() < cfg.MIN_N:
                    continue
                for cnd in CX.CONDITIONS:
                    mm = fm & sub["c_" + cnd].to_numpy(bool)
                    if mm.sum() < cfg.MIN_N:
                        continue
                    res["P2"].append(run_family(sub, bases, lambda d, mm=mm: mm,
                                                dict(und=u, tf=int(t), family=f, cond=cnd), folds, rule_window))
            cells = sub["cell"].value_counts()
            for cell in cells[cells >= cfg.MIN_N_GRID].index:
                mm = sub.cell.to_numpy() == cell
                res["P3"].append(run_family(sub, bases, lambda d, mm=mm: mm,
                                            dict(und=u, tf=int(t), family=cell, cond="grid"), folds, rule_window))
    out = {}
    for fam, rows in res.items():
        t = pd.DataFrame(rows)
        if t.empty:
            out[fam] = pd.DataFrame(columns=["und", "tf", "family", "cond", "n", "eff", "z", "p", "q", "tested"])
            continue
        t["tested"] = t.n >= (cfg.MIN_N_GRID if fam == "P3" else cfg.MIN_N)
        t["q"] = np.nan
        t.loc[t.tested, "q"] = ST.bh(t.loc[t.tested, "p"].to_numpy())
        for s in SECONDARY:
            t[s + "_q"] = np.nan
            if fam == "P1":
                t.loc[t.tested, s + "_q"] = ST.bh(t.loc[t.tested, s + "_p"].to_numpy())
        out[fam] = t
    return out


def save(out: dict, tag: str, extra: dict | None = None) -> None:
    cfg.RESULTS.mkdir(parents=True, exist_ok=True)
    for fam, t in out.items():
        if "fold_eff" in t:
            t = t.assign(fold_eff=t.fold_eff.apply(lambda v: json.dumps([None if x != x else x for x in v])))
        t.to_csv(cfg.RESULTS / f"atlas_{tag}_{fam}.csv.gz", index=False, float_format="%.6g")
    summ = {fam: dict(cells=int(len(t)), tested=int(t.tested.sum()), bh_pass=int((t.q < cfg.Q).sum()))
            for fam, t in out.items()}
    if extra:
        summ.update(extra)
    (cfg.RESULTS / f"atlas_{tag}_summary.json").write_text(json.dumps(summ, indent=1, default=str))


def design() -> None:
    df = load_all(cfg.START, cfg.DESIGN_END)
    df = df[df.day.dt.date <= cfg.DESIGN_END]
    out = atlas(df, folds=cfg.FOLDS, rule_window=(cfg.RULE_START, cfg.DESIGN_END))
    pth = paths(df, FAMS, purge_after=cfg.DESIGN_END)
    (cfg.RESULTS / "paths_design.json").write_text(json.dumps(pth))
    cov = {f"{u}_{t}m": dict(candles=int(((df.und == u) & (df.tf == t)).sum()),
                             days=int(df[(df.und == u) & (df.tf == t)].day.nunique()),
                             act_known=round(float(np.isfinite(df[(df.und == u) & (df.tf == t)].act.to_numpy(float))
                                                   .mean()), 3))
           for u in cfg.UNDS for t in cfg.TFS}
    save(out, "design", dict(coverage=cov))


if __name__ == "__main__":
    if sys.argv[1:] == ["design"]:
        design()
    else:
        raise SystemExit("usage: atlas.py design   (the holdout is read by final.py)")
