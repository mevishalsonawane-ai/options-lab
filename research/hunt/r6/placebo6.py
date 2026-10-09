"""R6 placebo-level test (PREREG.md), R4's method on MCX: do MCX prices react at Gann / Fibonacci / magic-number /
round-rupee levels more than at the same construction randomly shifted? Also the world proxies (XAUUSD 2015-2026,
XAGUSD / WTIUSD 2018-2024, USD levels, MCX hours in IST; labelled PROXY).
python3 -I placebo6.py [design|holdout|proxy] -> scratchpad/hunt/r6/placebo_<period>.csv
"""
from __future__ import annotations

import glob
import zlib
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib6 as L6  # noqa: E402
import signals6 as S6  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

ND = 20
# POST-HOC (added after the design table, before any holdout look): MCX prices move in Rs 1 / Rs 0.10 ticks, so a level
# on the tick lattice (round numbers, multiples of 9) can be touched exactly while a randomly shifted level can only be
# crossed. R6_TICK=1 rounds EVERY level (claimed and placebo) to the contract's tick -> placebo_<period>_tick.csv
TICKMODE = os.environ.get("R6_TICK", "0") == "1"
TICK = {"CRUDEOIL": 1.0, "NATURALGAS": 0.1, "GOLDM": 1.0, "GOLD": 1.0, "SILVERM": 1.0, "SILVER": 1.0,
        "PROXY_XAUUSD": 0.01, "PROXY_XAGUSD": 0.001, "PROXY_WTIUSD": 0.01}
DS = (0.001, 0.0025)
HOR = 60
T0, T1 = L6.col(9, 5), L6.col(23, 0)
MCX = ["CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM", "GOLD", "SILVER"]
# proxy level scales (USD): grids (B, A, MAJ), Gann point scale, NUM9 spacing
PROXY = {"XAUUSD": ((10, 50, 100), 0.1, 9.0), "XAGUSD": ((0.25, 0.5, 1.0), 0.01, 0.09),
         "WTIUSD": ((0.5, 1.0, 5.0), 0.1, 0.9)}


def events(h, l, c, lev, a=T0, b=T1):
    lev = np.asarray(lev, float)
    if len(lev) == 0:
        return lev, np.zeros(0, int), np.zeros(0, int)
    cp = np.r_[np.nan, c[:-1]]
    hh, ll, cc = h[a:b + 1], l[a:b + 1], cp[a:b + 1]
    with np.errstate(invalid="ignore"):
        fb = (cc[None, :] < lev[:, None]) & (hh[None, :] >= lev[:, None])
        fa = (cc[None, :] > lev[:, None]) & (ll[None, :] <= lev[:, None])
    t = fb | fa
    has = t.any(1)
    j = np.argmax(t, 1)
    sg = np.where(fb[np.arange(len(lev)), j], 1, -1)
    return lev[has], (a + j)[has], sg[has]


def outcome(h, l, lev, j, sg, d):
    n = len(lev)
    out = np.full(n, np.nan)
    if n == 0:
        return out
    Wd = len(h)
    D = lev * d
    imm = np.where(sg == 1, h[j] >= lev + D, l[j] <= lev - D)
    idx = j[:, None] + 1 + np.arange(HOR)[None, :]
    valid = idx < Wd
    idx = np.minimum(idx, Wd - 1)
    H, Lw = h[idx], l[idx]
    with np.errstate(invalid="ignore"):
        thru = np.where(sg[:, None] == 1, H >= (lev + D)[:, None], Lw <= (lev - D)[:, None]) & valid
        back = np.where(sg[:, None] == 1, Lw <= (lev - D)[:, None], H >= (lev + D)[:, None]) & valid
    BIG = 10 ** 6
    ft = np.where(thru.any(1), np.argmax(thru, 1), BIG)
    fbk = np.where(back.any(1), np.argmax(back, 1), BIG)
    out[(fbk < ft)] = 1.0
    out[(ft < fbk)] = 0.0
    out[imm] = 0.0
    return out


def fam_levels(P, di, rng, grids, gscale, n9):
    pc, ph, pl = P["pclose"][di], P["phigh"][di], P["plow"][di]
    op = P["dopen"][di]
    out = {}
    if not (np.isfinite(pc) and np.isfinite(ph) and np.isfinite(pl) and ph > pl and np.isfinite(op)):
        return out
    R = ph - pl
    for nm, step in (("SQ9_125", 0.125), ("SQ9_25", 0.25)):
        base = math.floor(math.sqrt(pc)) - 3
        i = np.arange(int(6 / step) + 1)
        out[nm] = ((base + step * i) ** 2, [(base + step * i + rng.uniform(0, step)) ** 2 for _ in range(ND)])
    gb, ga, gm = grids
    for nm, G, w in (("RND_B", gb, 0.05), ("RND_A", ga, 0.05), ("RND_MAJ", gm, 0.05), ("NUM9", n9, 0.02)):
        lo, hi = pc * (1 - w), pc * (1 + w)
        g = np.arange(math.floor(lo / G) * G, hi + G, G, dtype=float)
        out[nm] = (g, [g + rng.uniform(0, G) for _ in range(ND)])
    ks = np.array([45, 90, 144, 180, 360], float) * gscale
    e22 = 22.5 * gscale
    out["GANNPTS"] = (np.r_[op + ks, op - ks], [np.r_[op + ks + e, op - ks - e] for e in rng.uniform(-e22, e22, ND)])

    def shifted(r, lo, hi):
        return r + rng.choice([-1, 1]) * rng.uniform(lo, hi)
    P0 = (ph + pl + pc) / 3
    fr = np.array([0.382, 0.618, 1.0])
    out["FIBPIV"] = (np.r_[P0 + fr * R, P0 - fr * R],
                     [(lambda q: np.r_[P0 + q * R, P0 - q * R])(shifted(fr, 0.03, 0.12)) for _ in range(ND)])
    rr = np.array([0.236, 0.382, 0.5, 0.618, 0.786])
    out["PDRFIB"] = (pl + rr * R, [pl + shifted(rr, 0.015, 0.06) * R for _ in range(ND)])
    qq = np.array([0.25, 0.5, 0.75])
    out["GQTR"] = (pl + qq * R, [pl + shifted(qq, 0.03, 0.12) * R for _ in range(ND)])
    return out


SWR = np.array([0.382, 0.5, 0.618, 0.786, 1.272, 1.618])


def run_panel(P, mask, grids, gscale, n9, seed, tick=None):
    tk = (lambda x: np.round(np.asarray(x, float) / tick) * tick) if (TICKMODE and tick) else (lambda x: x)
    days = np.nonzero(mask)[0]
    rng = np.random.default_rng(seed)
    rows, touch = [], []
    for di in days:
        h, l, c = P["h"][di], P["l"][di], P["c"][di]
        o0 = P["dopen"][di]
        seg_h, seg_l = h[T0:L6.SQ], l[T0:L6.SQ]
        if not np.isfinite(seg_h).any():
            continue
        hmax, lmin = np.nanmax(seg_h), np.nanmin(seg_l)
        for fam, (cl, pls) in fam_levels(P, di, rng, grids, gscale, n9).items():
            for k, lev in enumerate([cl] + pls):
                lev = tk(lev)
                lev = lev[(lev > lmin * 0.99) & (lev < hmax * 1.01)] if len(lev) else lev
                near = np.abs(lev / o0 - 1) <= 0.005
                touch.append((fam, di, k - 1, int(near.sum()), int(((lev <= hmax) & (lev >= lmin) & near).sum())))
                lv, j, sg = events(h, l, c, lev)
                for d in DS:
                    r = outcome(h, l, lv, j, sg, d)
                    ok = np.isfinite(r)
                    rows.append((fam, d, di, k - 1, int(ok.sum()), float(np.nansum(r))))
    # SWFIB on the last 5-min ZigZag swing
    Mx = {"o": P["o"], "h": P["h"], "l": P["l"], "c": P["c"]}
    B = S6.H37.bars(Mx, 5)
    B["A"] = S6.H37.atr(B)
    pb, pp, pt, pcf = S6.H37.zigzag(B, B["A"], 2)
    okd = np.zeros(P["nd"], bool)
    okd[days] = True
    for jx in range(1, len(pb)):
        t0 = pcf[jx]
        di = int(B["dpos"][t0])
        if not okd[di]:
            continue
        t1 = pcf[jx + 1] if jx + 1 < len(pb) else len(B["c"]) - 1
        a = int(B["col_end"][t0]) + 1
        b = int(B["col_end"][t1]) if B["dpos"][t1] == di else T1
        b = min(b, T1)
        a = max(a, T0)
        if a > b:
            continue
        e, s0 = pp[jx], pp[jx - 1]
        rg = e - s0
        h, l, c = P["h"][di], P["l"][di], P["c"][di]
        sets = [SWR] + [SWR + rng.choice([-1, 1]) * rng.uniform(0.015, 0.06) for _ in range(ND)]
        for k, rr in enumerate(sets):
            lev = tk(e - rr * rg)
            lv, j, sg = events(h, l, c, lev, a, b)
            for d in DS:
                r = outcome(h, l, lv, j, sg, d)
                ok = np.isfinite(r)
                rows.append(("SWFIB", d, di, k - 1, int(ok.sum()), float(np.nansum(r))))
    # time placebo (FIB_TZ excludes its own anchor pivot, R4's POST-HOC fix adopted here in advance)
    piv_cols = {}
    for jx, t in enumerate(pb):
        di = int(B["dpos"][t])
        ce = int(B["col_end"][t])
        piv_cols.setdefault(di, []).append((ce - 4, ce, jx))

    def near_piv(di, m, excl=-1):
        return any(a0 - 5 <= m <= a1 + 5 for a0, a1, jj in piv_cols.get(di, []) if jj != excl)
    marks = {}
    for di in days:
        c = P["c"][di]
        for a, nm in ((0, "GANN_TOPEN"), (L6.col(17, 0), "GANN_TOPEN_EVE")):
            marks.setdefault(nm, []).extend((di, a + m, -1) for m in (45, 90, 180))
        seg = c[:60]
        if np.isfinite(seg).sum() > 30:
            an = int(max(np.nanargmax(seg), np.nanargmin(seg)))
            marks.setdefault("GANN_TEXT", []).extend((di, an + k, -1) for k in (90, 144))
    for jx in range(len(pb)):
        t0 = pb[jx]
        di = int(B["dpos"][t0])
        if not okd[di]:
            continue
        for k in (5, 8, 13, 21, 34, 55):
            t = t0 + k
            if t < len(B["c"]) and B["dpos"][t] == di and t > pcf[jx]:
                marks.setdefault("FIB_TZ", []).append((di, int(B["col_end"][t]), jx))
    trows = []
    for fam, lst in marks.items():
        for di, m, ex in lst:
            trows.append((fam, di, -1, int(near_piv(di, m, ex))))
            for k in range(ND):
                mm = m + rng.choice([-1, 1]) * rng.integers(10, 31)
                if T0 <= mm <= T1:
                    trows.append((fam, di, k, int(near_piv(di, mm, ex))))
    return (pd.DataFrame(rows, columns=["fam", "d", "di", "draw", "n", "rev"]),
            pd.DataFrame(touch, columns=["fam", "di", "draw", "nlev", "ntouch"]),
            pd.DataFrame(trows, columns=["fam", "di", "draw", "hit"]))


def boot_diff(g, num, den, B=1000, seed=5):
    c = g[g.draw == -1].groupby("di")[[num, den]].sum()
    p = g[g.draw >= 0].groupby("di")[[num, den]].sum()
    dd = c.index.union(p.index)
    c = c.reindex(dd, fill_value=0).values.astype(float)
    p = p.reindex(dd, fill_value=0).values.astype(float)
    rc, rp = c[:, 0].sum() / max(c[:, 1].sum(), 1), p[:, 0].sum() / max(p[:, 1].sum(), 1)
    rng = np.random.default_rng(seed)
    n = len(dd)
    diffs = np.empty(B)
    for b in range(B):
        ix = rng.integers(0, n, n)
        cs, ps = c[ix].sum(0), p[ix].sum(0)
        diffs[b] = cs[0] / max(cs[1], 1) - ps[0] / max(ps[1], 1)
    return rc, rp, int(c[:, 1].sum()), rc - rp, float((diffs <= 0).mean()), np.percentile(diffs, [2.5, 97.5])


def summarize(u, period, ev, tc, tm):
    res = []
    for (fam, d), g in ev.groupby(["fam", "d"]):
        rc, rp, n, df, p, ci = boot_diff(g, "rev", "n")
        res.append(dict(und=u, period=period, test="reversal", fam=fam, d=d, claimed=rc, placebo=rp, n_claimed=n,
                        diff=df, ci_lo=ci[0], ci_hi=ci[1], p=p))
    for fam, g in tc.groupby("fam"):
        rc, rp, n, df, p, ci = boot_diff(g, "ntouch", "nlev")
        res.append(dict(und=u, period=period, test="touch", fam=fam, d=np.nan, claimed=rc, placebo=rp,
                        n_claimed=n, diff=df, ci_lo=ci[0], ci_hi=ci[1], p=p))
    tm = tm.assign(one=1)
    for fam, g in tm.groupby("fam"):
        rc, rp, n, df, p, ci = boot_diff(g, "hit", "one")
        res.append(dict(und=u, period=period, test="time", fam=fam, d=np.nan, claimed=rc, placebo=rp,
                        n_claimed=n, diff=df, ci_lo=ci[0], ci_hi=ci[1], p=p))
    return res


# ------------------------------------------------------------------------------------------------ proxies
def proxy_panel(name):
    """MCX-hours (09:00-23:59 IST) minute panel of a USD proxy; prev-day = previous MCX session."""
    if name == "XAUUSD":
        df = pd.read_parquet(L6.SP / "hunt" / "r3" / "work" / "min_XAU.parquet")
        df["day"] = pd.to_datetime(df["day"])
    else:
        fs = sorted(glob.glob(str(L6.OUT / "raw" / f"{name}_*.parquet")))
        df = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
        t = df.ts.dt.tz_localize("UTC").dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df = pd.DataFrame(dict(o=df.open.values, h=df.high.values, l=df.low.values, c=df.close.values,
                               day=t.dt.normalize(), tod=(t.dt.hour * 60 + t.dt.minute).values))
    df = df[(df.tod >= L6.OPEN_M) & (df.day.dt.weekday < 5)]
    days = pd.DatetimeIndex(np.sort(df.day.unique()))
    dpos = pd.Series(np.arange(len(days)), index=days)
    nd = len(days)
    A = {k: np.full((nd, L6.W), np.nan) for k in "ohlc"}
    di = dpos.reindex(df.day).values
    cc = (df.tod.values - L6.OPEN_M).astype(int)
    for k in "ohlc":
        A[k][di, cc] = df[k].values
    nreal = np.isfinite(A["c"]).sum(1)
    C = pd.DataFrame(A["c"]).T.ffill().T.to_numpy(copy=True)
    H = np.where(np.isfinite(A["h"]), A["h"], C)
    Lw = np.where(np.isfinite(A["l"]), A["l"], C)
    O = np.where(np.isfinite(A["o"]), A["o"], C)
    with np.errstate(all="ignore"):
        dh, dl = np.nanmax(H, 1), np.nanmin(Lw, 1)
    dc = np.array([r[np.isfinite(r)][-1] if np.isfinite(r).any() else np.nan for r in A["c"]])
    dop = np.array([r[np.isfinite(r)][0] if np.isfinite(r).any() else np.nan for r in A["o"]])
    ok = nreal >= 600
    gap = np.r_[True, (days[1:] - days[:-1]).days > 4]
    P = dict(u=name, days=list(days.date), nd=nd, o=O, h=H, l=Lw, c=C, dopen=dop, dhigh=dh, dlow=dl, dclose=dc,
             ok=ok, last=np.full(nd, L6.W - 1))
    for k, src in (("pclose", dc), ("phigh", dh), ("plow", dl)):
        x = np.r_[np.nan, src[:-1]]
        x[gap | ~np.r_[False, ok[:-1]]] = np.nan
        P[k] = x
    P["design"] = ok & (days < L6.HOLD0)
    P["holdout"] = ok & (days >= L6.HOLD0) & (days <= L6.HOLD1)
    return P


def main():
    period = sys.argv[1] if len(sys.argv) > 1 else "design"
    res = []
    if period in ("design", "holdout"):
        for u in MCX:
            t0 = time.time()
            P = L6.load_panel(u)
            mask = (P["design"] if period == "design" else P["holdout"]) & ~P["roll"]
            ev, tc, tm = run_panel(P, mask, L6.GRID[u], L6.GANN_SCALE[u], L6.NUM9[u], seed=zlib.crc32(f'{u}{period}'.encode()),
                                tick=TICK[u])
            print(u, period, int(mask.sum()), "days", len(ev), f"{time.time() - t0:.0f}s", flush=True)
            res += summarize(u, period, ev, tc, tm)
    else:
        for name, (grids, gs, n9) in PROXY.items():
            t0 = time.time()
            P = proxy_panel(name)
            for per in ("design", "holdout"):
                mask = P[per]
                if mask.sum() < 20:
                    continue
                ev, tc, tm = run_panel(P, mask, grids, gs, n9, seed=zlib.crc32(f'{name}{per}'.encode()),
                                tick=TICK['PROXY_' + name])
                print(name, per, int(mask.sum()), "days", len(ev), f"{time.time() - t0:.0f}s", flush=True)
                res += summarize("PROXY_" + name, per, ev, tc, tm)
    out = pd.DataFrame(res)
    out["q_bh"] = L6.bh(out.p.values)
    out.to_csv(os.path.join(str(L6.OUT), f"placebo_{period}{'_tick' if TICKMODE else ''}.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 1000)
    print(out.round(4).to_string())


if __name__ == "__main__":
    main()
