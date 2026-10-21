"""F1 screen (PREREG 4): every feature's information about the next 15/30/60-minute index move and the ATM arms'
outcomes, on design data only. Direction from the fit split; the test on the selection split; BH over every test.

    python research/hunt/f1/screen.py featrows      # feature rows at the decision minutes -> <OUT>/featrows/<UND>.parquet
    python research/hunt/f1/screen.py run           # -> results/screen.csv (+ screen_summary.json)
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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402
import breadth as BR  # noqa: E402
import data as D  # noqa: E402

DIR_TARGETS = ["ret15", "ret30", "ret60"]
ARM_TARGETS = ["net_ce_atm_x1", "net_pe_atm_x1", "net_ce_atm_x2", "net_pe_atm_x2"]
SIZE_TARGETS = ["abs30"]
TARGETS = DIR_TARGETS + ARM_TARGETS + SIZE_TARGETS
NOT_FEATURES = {"ts", "day", "row"}


# ------------------------------------------------------------------------------------------------ inputs
def featrows(und: str, parts=("design",)) -> Path:
    """Feature values at RULE_ROWS for the days of `parts` (design; holdout only after the flag)."""
    if "holdout" in parts:
        assert K.HOLD_FLAG.exists(), "holdout not opened"
    base = K.CACHE.root / "features" / und
    keep = []
    for p in sorted(base.glob("*/*.parquet")):
        d = date.fromisoformat(p.stem)
        if K.part(d) not in parts:
            continue
        f = pd.read_parquet(p)
        mn = (f.ts - f.ts.dt.normalize()).dt.total_seconds().values // 60 - 555
        f = f[np.isin(mn, K.RULE_ROWS)].copy()
        f.insert(0, "row", (f.ts - f.ts.dt.normalize()).dt.total_seconds().values // 60 - 555)
        f.insert(0, "day", pd.Timestamp(d))
        for c in f.columns[3:]:
            if pd.api.types.is_numeric_dtype(f[c]):
                f[c] = f[c].astype(np.float32)
            else:
                f = f.drop(columns=c)                    # text columns (precision tags) are not features
        keep.append(f.drop(columns="ts"))
    tag = "_".join(parts)
    q = K.OUT / "featrows" / tag / f"{und}.parquet"
    q.parent.mkdir(parents=True, exist_ok=True)
    pd.concat(keep, ignore_index=True).to_parquet(q, compression="zstd", index=False)
    print(f"{und} {tag}: feature rows for {len(keep)} days -> {q}")
    return q


def frame(und: str, part: str = "design") -> pd.DataFrame:
    F = pd.read_parquet(K.OUT / "featrows" / part / f"{und}.parquet")
    T = D.load(und, part)
    X = F.merge(T, on=["day", "row"], how="inner")
    X = X.merge(BR.at_rows(pd.read_parquet(K.OUT / "breadth.parquet")), on=["day", "row"], how="left")
    X["und"] = und
    return X


# ------------------------------------------------------------------------------------------------ statistics
def zrank(a: np.ndarray) -> np.ndarray:
    r = stats.rankdata(a)
    s = r.std()
    return (r - r.mean()) / s if s > 0 else np.zeros_like(r)


def ic_fit(x, y) -> float:
    ok = np.isfinite(x) & np.isfinite(y)
    if ok.sum() < 200 or np.nanstd(x[ok]) == 0:
        return np.nan
    return float(stats.spearmanr(x[ok], y[ok])[0])


def ic_test(x, y, cluster, sign, groups=None):
    """Direction-signed rank IC with a cluster(day)-robust one-sided t-test. groups: rank within each group (pooled
    runs rank within each index). Returns (ic, t, p, n_rows, n_days)."""
    ok = np.isfinite(x) & np.isfinite(y)
    if ok.sum() < 200:
        return np.nan, np.nan, np.nan, int(ok.sum()), 0
    x, y, cl = x[ok], y[ok], cluster[ok]
    if groups is None:
        zx, zy = zrank(x), zrank(y)
    else:
        g = groups[ok]
        zx, zy = np.empty(len(x)), np.empty(len(x))
        for v in np.unique(g):
            m = g == v
            zx[m], zy[m] = zrank(x[m]), zrank(y[m])
    if zx.std() == 0:
        return np.nan, np.nan, np.nan, int(ok.sum()), 0
    prod = sign * zx * zy
    dm = pd.Series(prod).groupby(cl).mean()
    nd = len(dm)
    ic = float(prod.mean())
    sd = dm.std(ddof=1)
    t = float(dm.mean() / (sd / np.sqrt(nd))) if nd > 2 and sd > 0 else np.nan
    p = float(stats.t.sf(t, nd - 1)) if np.isfinite(t) else np.nan
    return ic, t, p, int(ok.sum()), nd


def bh(p: np.ndarray) -> np.ndarray:
    """Benjamini-Hochberg q-values (NaN p stays NaN and is not counted)."""
    p = np.asarray(p, float)
    q = np.full(p.shape, np.nan)
    ok = np.isfinite(p)
    m = ok.sum()
    if not m:
        return q
    ps = p[ok]
    o = np.argsort(ps)
    r = ps[o] * m / np.arange(1, m + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(m)
    qq[o] = np.minimum(r, 1.0)
    q[ok] = qq
    return q


# ------------------------------------------------------------------------------------------------ the screen
def columns(X: pd.DataFrame, fit: np.ndarray) -> list[tuple[str, str, float | None]]:
    """(column, feature name, level) to test: continuous features as they are, few-valued ones level by level."""
    out = []
    feats = [c for c in X.columns if c not in NOT_FEATURES and c not in D_COLS and c != "und"]
    for c in feats:
        v = X[c].values.astype(float)
        if np.isnan(v).mean() > K.MAX_NAN:
            continue
        u = np.unique(v[fit & np.isfinite(v)])
        if len(u) <= 1:
            continue
        if len(u) <= K.MAX_LEVELS:
            for lv in u:
                out.append((c, c, float(lv)))
        else:
            out.append((c, c, None))
    return out


D_COLS = set()


def values(X, col, level):
    v = X[col].values.astype(float)
    if level is None:
        return v
    return np.where(np.isfinite(v), (v == level).astype(float), np.nan)


def screen_one(X: pd.DataFrame, scope: str) -> list[dict]:
    pooled = scope == "POOLED"
    sub = X[(X.row.values % 15 == 0)].reset_index(drop=True)          # the screen grid: every 15 minutes
    day = sub.day.dt.date.values
    fit = np.array([K.within(d, K.FIT) for d in day])
    sel = np.array([K.within(d, K.SELECT) for d in day])
    groups = sub.und.values if pooled else None
    cl = sub.day.values
    cols = columns(sub, fit)
    rows = []
    cache = {}
    for tgt in TARGETS:
        y = sub[tgt].values.astype(float)
        fits = []
        for col, name, lv in cols:
            x = values(sub, col, lv)
            key = (col, lv)
            cache[key] = x
            icf = ic_fit_grouped(x[fit], y[fit], groups[fit] if pooled else None)
            if not np.isfinite(icf) or icf == 0:
                rows.append(dict(scope=scope, target=tgt, feature=name, level=lv, kind="single", ic_fit=icf,
                                 no_data=not np.isfinite(icf)))
                continue
            s = np.sign(icf)
            ic, t, p, n, nd = ic_test(x[sel], y[sel], cl[sel], s, groups[sel] if pooled else None)
            rows.append(dict(scope=scope, target=tgt, feature=name, level=lv, kind="single", ic_fit=icf, sign=s,
                             ic_sel=ic, t_sel=t, p=p, n_rows=n, n_days=nd))
            fits.append((abs(icf), name, col, lv, s))
        # pairs: the 12 strongest distinct names on the fit split
        fits.sort(key=lambda z: -z[0])
        top, seen = [], set()
        for a, name, col, lv, s in fits:
            if name in seen:
                continue
            seen.add(name)
            top.append((name, col, lv, s))
            if len(top) == K.TOP_K:
                break
        for i in range(len(top)):
            for j in range(i + 1, len(top)):
                (na, ca, la, sa), (nb, cb, lb, sb) = top[i], top[j]
                sc = pair_score(cache[(ca, la)], cache[(cb, lb)], sa, sb, groups)
                icf = ic_fit_grouped(sc[fit], y[fit], groups[fit] if pooled else None)
                if not np.isfinite(icf) or icf == 0:
                    continue
                s = np.sign(icf)
                ic, t, p, n, nd = ic_test(sc[sel], y[sel], cl[sel], s, groups[sel] if pooled else None)
                rows.append(dict(scope=scope, target=tgt, feature=f"{na}{lvtag(la)} + {nb}{lvtag(lb)}", level=None,
                                 kind="pair", a=na, la=la, sa=sa, b=nb, lb=lb, sb=sb, ic_fit=icf, sign=s, ic_sel=ic,
                                 t_sel=t, p=p, n_rows=n, n_days=nd))
    return rows


def lvtag(lv):
    return "" if lv is None or not np.isfinite(lv) else f"=={lv:g}"


def ic_fit_grouped(x, y, g):
    if g is None:
        return ic_fit(x, y)
    vals = []
    w = []
    for v in np.unique(g):
        m = g == v
        r = ic_fit(x[m], y[m])
        if np.isfinite(r):
            vals.append(r)
            w.append(m.sum())
    return float(np.average(vals, weights=w)) if vals else np.nan


def pair_score(xa, xb, sa, sb, groups=None):
    """Mean of the two direction-signed percentile ranks (within each index when pooled)."""
    def pct(x):
        out = np.full(len(x), np.nan)
        idx = [np.arange(len(x))] if groups is None else [np.flatnonzero(groups == v) for v in np.unique(groups)]
        for ii in idx:
            xi = x[ii]
            ok = np.isfinite(xi)
            if ok.sum():
                out[ii[ok]] = stats.rankdata(xi[ok]) / ok.sum()
        return out
    return (sa * (pct(xa) - 0.5) + sb * (pct(xb) - 0.5)) / 2


def run(unds=K.UNDS) -> pd.DataFrame:
    global D_COLS
    frames = []
    for u in unds:
        frames.append(frame(u))
    T0 = D.load(unds[0], "design")
    D_COLS = set(T0.columns) - {"day", "row"}
    rows = []
    for u, X in zip(unds, frames):
        print(f"screen {u}: {X.day.nunique()} days")
        rows += screen_one(X, u)
    allx = pd.concat(frames, ignore_index=True)
    print(f"screen POOLED: {allx.day.nunique()} calendar days")
    rows += screen_one(allx, "POOLED")
    R = pd.DataFrame(rows)
    R["q"] = bh(R.p.values)
    R["survivor"] = (R.q <= K.BH_Q) & (R.ic_sel.abs() >= K.MIN_IC) & (R.t_sel > 0)
    K.RESULTS.mkdir(exist_ok=True)
    R.to_csv(K.RESULTS / "screen.csv", index=False, float_format="%.5g")
    summ = dict(tests=int(R.p.notna().sum()), survivors=int(R.survivor.sum()),
                by_target=R[R.survivor].groupby("target").size().to_dict(),
                by_scope=R[R.survivor].groupby("scope").size().to_dict(),
                days={u: int(f.day.nunique()) for u, f in zip(unds, frames)})
    (K.RESULTS / "screen_summary.json").write_text(json.dumps(summ, indent=1))
    print(json.dumps(summ, indent=1))
    return R


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["featrows", "run"])
    ap.add_argument("--und", nargs="*", default=list(K.UNDS))
    a = ap.parse_args(argv)
    if a.cmd == "featrows":
        for u in a.und:
            featrows(u)
    else:
        run(tuple(a.und))


if __name__ == "__main__":
    main()
