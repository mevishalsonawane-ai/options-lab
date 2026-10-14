"""R25 Step 2: the anatomy of jackpots vs duds (PREREG.md Step 2). Design days only.

    python3 research/hunt/r25/anatomy.py build      # -> <OUT>/anatomy_design.parquet (one row per decision x option)
    python3 research/hunt/r25/anatomy.py rank       # -> results/anatomy.json, results/anatomy.md

Decisions: every 5 minutes, bar closes 11:00 .. 14:55; every OTM option whose traded close is Rs 1-10 and that traded in
the last 5 bars. `features(day, t)` reads rows <= t only; the look-ahead guard (test_r25.py) recomputes it on a day whose
future is blanked out and on one whose future is absurd, and requires the same values.
Features are aligned to the option: "with" = up for a call, down for a put.
"""
from __future__ import annotations

import json
import math
import sys
from itertools import combinations
from multiprocessing import Pool

import numpy as np
import pandas as pd

import r25cfg as K
import r25data as D
import r25lib as R
from r25data import C, H, L, N, O, OI, V, minute

DECISIONS = list(range(R.close_row(11, 0), R.close_row(14, 55) + 1, 5))
BAND = (1.0, 10.0)
OPEN_ROW = 0
OR_END = minute(9, 29)             # the 15-minute opening range: bars 09:15 .. 09:29
FROM_11 = R.close_row(11, 0)


def _nan(x):
    return float(x) if x is not None and np.isfinite(x) else math.nan


def day_state(day: D.Day, t: int) -> dict:
    """Index-level features at row t (rows <= t only)."""
    ix = day.ix[: t + 1]
    s = R.spot(day, t)
    out = {"spot": s}

    def back(k):
        r = t - k
        return day.ix[r, 3] if r >= 0 else math.nan

    for k in (5, 15, 30):
        b = back(k)
        out[f"mv{k}"] = (s / b - 1) * 100 if b == b and b > 0 else math.nan
    cl = ix[:, 3]
    d5 = np.abs(cl[5:] - cl[:-5]) / cl[:-5] * 100 if len(cl) > 5 else np.array([])
    m5 = np.nanmean(d5) if len(d5) and np.isfinite(d5).any() else math.nan
    out["mean_abs5"] = m5
    hi, lo = np.nanmax(ix[:, 1]), np.nanmin(ix[:, 2])
    out["day_hi"], out["day_lo"] = hi, lo
    if t >= 15:
        prev_hi = np.nanmax(day.ix[: t - 14, 1])
        prev_lo = np.nanmin(day.ix[: t - 14, 2])
        out["brk_hi15"] = float(np.nanmax(day.ix[t - 14: t + 1, 1]) > prev_hi)
        out["brk_lo15"] = float(np.nanmin(day.ix[t - 14: t + 1, 2]) < prev_lo)
    else:
        out["brk_hi15"] = out["brk_lo15"] = math.nan
    orh, orl = np.nanmax(day.ix[: OR_END + 1, 1]), np.nanmin(day.ix[: OR_END + 1, 2])
    out["or_up"] = float(s > orh) if s == s else math.nan
    out["or_dn"] = float(s < orl) if s == s else math.nan
    op = day.ix[0, 0]
    out["trend_open"] = (s / op - 1) * 100 if op == op and op > 0 else math.nan
    r12 = R.LOW_FROM
    out["trend_12"] = (s / day.ix[r12, 3] - 1) * 100 if t >= r12 and day.ix[r12, 3] == day.ix[r12, 3] else math.nan
    out["gap"] = (op / day.prev_close - 1) * 100 if day.prev_close == day.prev_close and op == op else math.nan
    v = day.vix[: t + 1] if day.vix is not None else np.array([])
    vv = v[~np.isnan(v)] if len(v) else v
    out["vix"] = float(vv[-1]) if len(vv) else math.nan
    out["vix_ch"] = float(vv[-1] - vv[0]) if len(vv) else math.nan
    mins_left = (15 * 60 + 30) - (9 * 60 + 15 + t + 1)
    out["em"] = s * out["vix"] / 100 * math.sqrt(max(mins_left, 1) / (252 * 375)) if out["vix"] == out["vix"] else math.nan
    out["rng"] = hi - lo
    # ATM straddle now, 15 min ago, and its low since 12:00 (or since 11:00 before 12:00)
    st = R.straddle(day, t)
    st15 = R.straddle(day, t - 15) if t >= 15 else math.nan
    lo_from = R.LOW_FROM if t >= R.LOW_FROM else FROM_11
    path = [R.straddle(day, r) for r in range(lo_from, t + 1, 3)] + [st]
    path = [x for x in path if x == x]
    out["st15"] = (st / st15 - 1) * 100 if st == st and st15 == st15 and st15 > 0 else math.nan
    out["st_low"] = (st / min(path) - 1) * 100 if st == st and path and min(path) > 0 else math.nan
    # OI: PCR over stored strikes now and 30 min ago; max pain over stored strikes now
    def oi_at(r):
        c, p, strikes = {}, {}, set()
        for (k, sd), a in day.opts.items():
            x = a[: r + 1, OI]
            ok = np.nonzero(~np.isnan(x))[0]
            if not len(ok) or ok[-1] < r - 30:
                continue
            (c if sd == "C" else p)[k] = float(x[ok[-1]])
            strikes.add(k)
        return c, p, strikes
    c, p, ks = oi_at(t)
    sc, sp = sum(c.values()), sum(p.values())
    out["pcr"] = sp / sc if sc > 0 else math.nan
    if t >= 30:
        c0, p0, _ = oi_at(t - 30)
        s0c, s0p = sum(c0.values()), sum(p0.values())
        out["pcr_ch30"] = out["pcr"] - (s0p / s0c) if s0c > 0 and out["pcr"] == out["pcr"] else math.nan
    else:
        out["pcr_ch30"] = math.nan
    if ks:
        kk = np.array(sorted(ks))
        pain = [sum(v * max(x - k, 0) for k, v in c.items()) + sum(v * max(k - x, 0) for k, v in p.items()) for x in kk]
        out["maxpain"] = float(kk[int(np.argmin(pain))])
    else:
        out["maxpain"] = math.nan
    out["_oi_c"], out["_oi_p"] = c, p
    return out


def option_rows(day: D.Day, t: int, st: dict) -> list[dict]:
    """Every eligible Rs 1-10 OTM option at row t with its features (rows <= t only)."""
    s = st["spot"]
    if s != s:
        return []
    rows = []
    am = R.atm(day, s)
    step = K.STEP[day.und]
    for sgn in (1, -1):
        side = "C" if sgn > 0 else "P"
        for k, p in R.eligible(day, sgn, t, BAND):
            a = day.opts[(k, side)]
            f = {"und": day.und, "day": day.day, "year": day.day.year, "t": t, "strike": k, "side": side, "price": p}
            for x in ("mv5", "mv15", "mv30", "trend_open", "trend_12", "gap"):
                f[x] = sgn * st[x] if st[x] == st[x] else math.nan
            f["speed5"] = sgn * st["mv5"] / st["mean_abs5"] if st["mean_abs5"] and st["mean_abs5"] > 0 and st["mv5"] == st["mv5"] else math.nan
            f["brk15"] = st["brk_hi15"] if sgn > 0 else st["brk_lo15"]
            f["or_brk"] = st["or_up"] if sgn > 0 else st["or_dn"]
            dist = abs(k - s)
            f["dist_pct"] = dist / s * 100
            f["dist_k"] = abs(k - am) / step
            f["dist_rng"] = dist / st["rng"] if st["rng"] > 0 else math.nan
            f["dist_prev"] = dist / day.prev_range if day.prev_range == day.prev_range and day.prev_range > 0 else math.nan
            f["dist_em"] = dist / st["em"] if st["em"] == st["em"] and st["em"] > 0 else math.nan
            iv = a[: t + 1, D.IV]
            ok = np.nonzero(np.nan_to_num(iv) > 0)[0]
            f["iv"] = float(iv[ok[-1]]) if len(ok) and ok[-1] >= t - 5 else math.nan
            ok15 = ok[ok <= t - 15]
            f["iv_ch15"] = f["iv"] - float(iv[ok15[-1]]) if f["iv"] == f["iv"] and len(ok15) and ok15[-1] >= t - 20 else math.nan
            oi = a[: t + 1, OI]
            f["oi_ch30"] = _pct(oi, t, 30)
            mirror = am - (k - am)
            b = day.opts.get((mirror, "P" if sgn > 0 else "C"))
            f["opp_oi_ch30"] = _pct(b[: t + 1, OI], t, 30) if b is not None else math.nan
            f["pcr_ch30"] = sgn * st["pcr_ch30"] if st["pcr_ch30"] == st["pcr_ch30"] else math.nan
            f["st15"], f["st_low"] = st["st15"], st["st_low"]
            vol = np.nan_to_num(a[: t + 1, V])
            base = vol[FROM_11: t + 1] if t >= FROM_11 else vol
            m5 = base.sum() / max(len(base), 1) * 5
            f["volb"] = vol[t - 4: t + 1].sum() / m5 if m5 > 0 else math.nan
            f["vix"], f["vix_ch"] = st["vix"], st["vix_ch"]
            f["maxpain"] = sgn * (st["maxpain"] - s) / s * 100 if st["maxpain"] == st["maxpain"] else math.nan
            c11 = a[FROM_11: t + 1, C]
            tr = np.nan_to_num(a[FROM_11: t + 1, V]) > 0
            lo = np.nanmin(c11[tr]) if tr.any() else math.nan
            f["own_run"] = p / lo if lo == lo and lo > 0 else math.nan
            f["mins_left"] = (15 * 60 + 30) - (9 * 60 + 15 + t + 1)
            rows.append(f)
    return rows


def _pct(x: np.ndarray, t: int, k: int) -> float:
    ok = np.nonzero(~np.isnan(x[: t + 1]))[0]
    if not len(ok) or ok[-1] < t - 5:
        return math.nan
    old = ok[ok <= t - k]
    if not len(old) or old[-1] < t - k - 10:
        return math.nan
    a, b = x[ok[-1]], x[old[-1]]
    return (a / b - 1) * 100 if b > 0 else math.nan


def features(day: D.Day, t: int) -> list[dict]:
    """All decision rows at t (the look-ahead guard's unit)."""
    return option_rows(day, t, day_state(day, t))


def outcomes(day: D.Day, f: dict) -> dict:
    """Hindsight labels of one decision row (never read by features)."""
    sgn = 1 if f["side"] == "C" else -1
    a = day.opts[(f["strike"], f["side"])]
    t, p = f["t"], f["price"]
    seg = a[t + 1: minute(15, 4) + 1, H]
    peak = float(np.nanmax(seg)) if np.isfinite(seg).any() else p
    o = {"peak_mult": max(peak, p) / p, "jackpot": peak >= 10 * p - R.EPS, "dud": peak < 2 * p - R.EPS}
    for ex in ("hold", "half5"):
        tr = R.simulate(day, f["strike"], sgn, t, p, 1000.0, ex)
        o[f"p1000_{ex}"] = tr.per_1000 if tr else math.nan
        o[f"net_{ex}"] = tr.net if tr else math.nan
    return o


_CACHE: dict = {}


def _day(args):
    und, i = args
    if und not in _CACHE:
        _CACHE.clear()
        _CACHE[und] = D.load(und, "design")
    day = _CACHE[und][i]
    out = []
    for t in DECISIONS:
        for f in features(day, t):
            f.update(outcomes(day, f))
            out.append(f)
    return out


def build(procs: int = 4) -> pd.DataFrame:
    jobs = [(u, i) for u in K.UNDS for i in range(len(D.load(u, "design")))]
    rows = []
    with Pool(procs) as pool:
        for r in pool.imap(_day, jobs, chunksize=4):
            rows += r
    df = pd.DataFrame(rows)
    df.to_parquet(K.OUT / "anatomy_design.parquet")
    return df


FEATS = ["mv5", "mv15", "mv30", "speed5", "brk15", "or_brk", "dist_pct", "dist_k", "dist_rng", "dist_prev", "dist_em",
         "iv", "iv_ch15", "oi_ch30", "opp_oi_ch30", "pcr_ch30", "st15", "st_low", "volb", "vix", "vix_ch", "maxpain",
         "gap", "trend_open", "trend_12", "own_run", "price", "mins_left"]
BINARY = {"brk15", "or_brk"}
MIN_ROWS, MIN_DAYS = 2000, 60


def bins(df: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """Tercile bins (cut points from these design rows); binary features keep 0 / 1."""
    out = pd.DataFrame(index=df.index)
    cuts = {}
    for f in FEATS:
        x = df[f]
        if f in BINARY:
            out[f] = x.map({0.0: "no", 1.0: "yes"})
            continue
        q = x.quantile([1 / 3, 2 / 3]).values
        cuts[f] = [float(q[0]), float(q[1])]
        out[f] = np.where(x.isna(), None, np.where(x <= q[0], "lo", np.where(x <= q[1], "mid", "hi")))
    return out, cuts


def rank(df: pd.DataFrame) -> dict:
    df = df[df.p1000_hold.notna()].reset_index(drop=True)
    b, cuts = bins(df)
    base_j, base_d = df.jackpot.mean(), df.dud.mean()
    dkey = df.und + "|" + df.day.astype(str)

    def stat(m) -> dict | None:
        n = int(m.sum())
        if n < MIN_ROWS:
            return None
        nd = int(dkey[m].nunique())
        if nd < MIN_DAYS:
            return None
        g = df[m]
        return {"rows": n, "days": nd, "jackpot_pct": round(g.jackpot.mean() * 100, 2),
                "lift": round(g.jackpot.mean() / base_j, 2), "dud_pct": round(g.dud.mean() * 100, 1),
                "dud_lift": round(g.dud.mean() / base_d, 2),
                "exp_hold": round(float(g.p1000_hold.mean()), 1), "exp_half5": round(float(g.p1000_half5.mean()), 1),
                "share_profitable_hold": round(float((g.p1000_hold > 0).mean()) * 100, 1)}

    single = []
    for f in FEATS:
        for v in b[f].dropna().unique():
            s = stat((b[f] == v).values)
            if s:
                single.append({"state": f"{f}={v}", **s})
    single.sort(key=lambda r: -r["lift"])
    pairs = []
    top = [r["state"] for r in single[:20]]
    for x, y in combinations(top, 2):
        fx, vx = x.split("=")
        fy, vy = y.split("=")
        if fx == fy:
            continue
        s = stat(((b[fx] == vx) & (b[fy] == vy)).values)
        if s:
            pairs.append({"state": f"{x} & {y}", **s})
    pairs.sort(key=lambda r: -r["lift"])
    by_exp = sorted(single + pairs, key=lambda r: -r["exp_half5"])
    return {"rows": int(len(df)), "days": int(dkey.nunique()), "base_jackpot_pct": round(base_j * 100, 2),
            "base_dud_pct": round(base_d * 100, 1), "base_exp_hold": round(float(df.p1000_hold.mean()), 1),
            "base_exp_half5": round(float(df.p1000_half5.mean()), 1), "cuts": cuts, "single": single,
            "pairs": pairs[:40], "by_expectancy": by_exp[:30],
            "jackpot_vs_dud_medians": {f: [_nan(df.loc[df.jackpot, f].median()), _nan(df.loc[df.dud, f].median()),
                                           _nan(df[f].median())] for f in FEATS}}


def md(r: dict) -> str:
    L = [f"# R25 Step 2: anatomy (design days only)", "",
         f"{r['rows']:,} decision rows (every 5 minutes, 11:00-14:55, every Rs 1-10 OTM option) on {r['days']} expiry days.",
         f"Jackpot (best high before 15:05 >= 10x) {r['base_jackpot_pct']}% of rows; dud (never 2x) {r['base_dud_pct']}%.",
         f"Mean net per Rs 1,000: held to 15:05 {r['base_exp_hold']}, half at 5x {r['base_exp_half5']}.", "",
         "## Medians: jackpot rows / dud rows / all rows", "", "| feature | jackpot | dud | all |", "|---|---|---|---|"]
    for f, (a, b, c) in r["jackpot_vs_dud_medians"].items():
        L.append(f"| {f} | {a:.3g} | {b:.3g} | {c:.3g} |")

    def table(title, rows):
        L.extend(["", f"## {title}", "", "| state | rows | days | jackpot % | lift | dud % | dud lift | Rs/1,000 hold | Rs/1,000 half 5x | % rows profitable (hold) |",
                  "|---|---|---|---|---|---|---|---|---|---|"])
        for x in rows:
            L.append(f"| {x['state']} | {x['rows']:,} | {x['days']} | {x['jackpot_pct']} | {x['lift']} | {x['dud_pct']} | "
                     f"{x['dud_lift']} | {x['exp_hold']} | {x['exp_half5']} | {x['share_profitable_hold']} |")
    table("Single states by jackpot lift (top 25)", r["single"][:25])
    table("Single states by jackpot lift (bottom 10)", r["single"][-10:])
    table("Two-state combinations by lift (top 25)", r["pairs"][:25])
    table("All states by expectancy (half at 5x; top 20)", r["by_expectancy"][:20])
    L.extend(["", "Tercile cut points (design rows): " + json.dumps({k: [round(a, 4), round(b, 4)] for k, (a, b) in r["cuts"].items()})])
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "build":
        df = build()
        print(len(df))
    if cmd in ("build", "rank"):
        df = pd.read_parquet(K.OUT / "anatomy_design.parquet")
        r = rank(df)
        (K.RESULTS / "anatomy.json").write_text(json.dumps(r, indent=1, default=str))
        (K.RESULTS / "anatomy.md").write_text(md(r))
        print(md(r)[:6000])
