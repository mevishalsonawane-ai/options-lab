"""R4 placebo-level test (PREREG.md): do prices react at Gann / Fibonacci / magic-number levels more than at the same
construction randomly shifted? Index minutes only. python3 -I placebo.py -> scratchpad/hunt/r4/placebo_*.csv
"""
from __future__ import annotations

import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib4 as L4  # noqa: E402
import signals4 as S4  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

L = L4.L
ND = 20                 # placebo draws per day
DS = (0.001, 0.0025)    # reaction distance d
HOR = 60
T0, T1 = L4.col(9, 20), L4.col(14, 45)


def events(h, l, c, lev, a=T0, b=T1):
    """first touch of each level in columns [a, b]; returns (lev, j, sgn) for touched levels."""
    lev = np.asarray(lev, float)
    if len(lev) == 0:
        return lev, np.zeros(0, int), np.zeros(0, int)
    cp = np.r_[np.nan, c[:-1]]
    hh, ll, cc = h[a:b + 1], l[a:b + 1], cp[a:b + 1]
    fb = (cc[None, :] < lev[:, None]) & (hh[None, :] >= lev[:, None])
    fa = (cc[None, :] > lev[:, None]) & (ll[None, :] <= lev[:, None])
    t = fb | fa
    has = t.any(1)
    j = np.argmax(t, 1)
    sg = np.where(fb[np.arange(len(lev)), j], 1, -1)
    return lev[has], (a + j)[has], sg[has]


def outcome(h, l, lev, j, sg, d):
    """1 = reversal (retreat d before moving d through), 0 = break, nan = unresolved."""
    n = len(lev)
    out = np.full(n, np.nan)
    if n == 0:
        return out
    W = len(h)
    D = lev * d
    imm = np.where(sg == 1, h[j] >= lev + D, l[j] <= lev - D)
    idx = j[:, None] + 1 + np.arange(HOR)[None, :]
    valid = idx < W
    idx = np.minimum(idx, W - 1)
    H, Lw = h[idx], l[idx]
    thru = np.where(sg[:, None] == 1, H >= (lev + D)[:, None], Lw <= (lev - D)[:, None]) & valid
    back = np.where(sg[:, None] == 1, Lw <= (lev - D)[:, None], H >= (lev + D)[:, None]) & valid
    BIG = 10 ** 6
    ft = np.where(thru.any(1), np.argmax(thru, 1), BIG)
    fbk = np.where(back.any(1), np.argmax(back, 1), BIG)
    out[(fbk < ft)] = 1.0
    out[(ft < fbk)] = 0.0
    out[imm] = 0.0
    return out


def fam_levels(u, P, di, rng):
    """dict fam -> (claimed levels, list of ND placebo level arrays)."""
    pc, ph, pl = P["pclose"][di], P["phigh"][di], P["plow"][di]
    o = P["o"][di]
    op = o[0] if np.isfinite(o[0]) else P["c"][di][0]
    out = {}
    if not (np.isfinite(pc) and np.isfinite(ph) and np.isfinite(pl) and ph > pl):
        return out
    R = ph - pl
    for nm, step in (("SQ9_125", 0.125), ("SQ9_25", 0.25)):
        base = math.floor(math.sqrt(pc)) - 3
        i = np.arange(int(6 / step) + 1)
        out[nm] = ((base + step * i) ** 2, [(base + step * i + rng.uniform(0, step)) ** 2 for _ in range(ND)])
    grids = {"RND_A": 500 if u == "BANKNIFTY" else 100, "RND_B": 100 if u == "BANKNIFTY" else 50,
             "RND_MAJ": 1000 if u == "BANKNIFTY" else 500, "NUM9": 9}
    for nm, G in grids.items():
        w = 0.02 if nm == "NUM9" else 0.05
        lo, hi = pc * (1 - w), pc * (1 + w)
        g = np.arange(math.floor(lo / G) * G, hi + G, G, dtype=float)
        out[nm] = (g, [g + rng.uniform(0, G) for _ in range(ND)])
    ks = np.array([45, 90, 144, 180, 360], float)
    out["GANNPTS"] = (np.r_[op + ks, op - ks], [np.r_[op + ks + e, op - ks - e] for e in rng.uniform(-22.5, 22.5, ND)])

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


def run_index(u, period):
    P = L.load_index(u)
    mask = (P["ok"] & ~P["hold"]) if period == "design" else (P["ok"] & P["hold"] & P["inrange"])
    days = np.nonzero(mask)[0]
    rng = np.random.default_rng(abs(hash((u, period))) % 2 ** 32)
    rows = []          # (fam, d, di, draw(-1 claimed), n, rev)
    touch = []         # (fam, di, draw, nlev, ntouch) for levels within 0.5% of the open
    for di in days:
        h, l, c = P["h"][di], P["l"][di], P["c"][di]
        h = np.where(np.isfinite(h), h, c)
        l = np.where(np.isfinite(l), l, c)
        o0 = P["o"][di][0] if np.isfinite(P["o"][di][0]) else c[0]
        hmax, lmin = np.nanmax(h[T0:L4.SQ]), np.nanmin(l[T0:L4.SQ])
        for fam, (cl, pls) in fam_levels(u, P, di, rng).items():
            for k, lev in enumerate([cl] + pls):
                lev = lev[(lev > lmin * 0.99) & (lev < hmax * 1.01)] if len(lev) else lev
                near = np.abs(lev / o0 - 1) <= 0.005
                touch.append((fam, di, k - 1, int(near.sum()), int(((lev <= hmax) & (lev >= lmin) & near).sum())))
                lv, j, sg = events(h, l, c, lev)
                for d in DS:
                    r = outcome(h, l, lv, j, sg, d)
                    ok = np.isfinite(r)
                    rows.append((fam, d, di, k - 1, int(ok.sum()), float(np.nansum(r))))
    # SWFIB: last 5-min ZigZag swing (k = 2), valid from its confirmation to the next confirmation, same day
    B = S4.mk_bars(P, 5)
    pb, pp, pt, pcf = S4.piv(B, 2)
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
        if a > b:
            continue
        e, s0 = pp[jx], pp[jx - 1]
        rg = e - s0
        h, l, c = P["h"][di], P["l"][di], P["c"][di]
        h = np.where(np.isfinite(h), h, c)
        l = np.where(np.isfinite(l), l, c)
        sets = [SWR] + [SWR + rng.choice([-1, 1]) * rng.uniform(0.015, 0.06) for _ in range(ND)]
        for k, rr in enumerate(sets):
            lev = e - rr * rg
            lv, j, sg = events(h, l, c, lev, a, b)
            for d in DS:
                r = outcome(h, l, lv, j, sg, d)
                ok = np.isfinite(r)
                rows.append(("SWFIB", d, di, k - 1, int(ok.sum()), float(np.nansum(r))))
    # time placebo: Gann minute marks and Fibonacci time zones vs shifted marks; hit = within 5 min of a pivot bar
    piv_cols = {}
    for t in pb:
        di = int(B["dpos"][t])
        ce = int(B["col_end"][t])
        piv_cols.setdefault(di, []).append((ce - 4, ce))
    trows = []

    def near_piv(di, m):
        return any(a0 - 5 <= m <= a1 + 5 for a0, a1 in piv_cols.get(di, []))
    marks = {}
    for di in days:
        h, l = P["h"][di], P["l"][di]
        marks.setdefault("GANN_TOPEN", []).extend((di, m) for m in (45, 90, 180))
        hh, ll = np.nanargmax(h[:60]), np.nanargmin(l[:60])
        an = int(max(hh, ll))
        marks.setdefault("GANN_TEXT", []).extend((di, an + k) for k in (90, 144) if an + k <= 360)
    for jx in range(len(pb)):
        t0 = pb[jx]
        di = int(B["dpos"][t0])
        if not okd[di]:
            continue
        for k in (5, 8, 13, 21, 34, 55):
            t = t0 + k
            if t < len(B["c"]) and B["dpos"][t] == di and t > pcf[jx]:
                marks.setdefault("FIB_TZ", []).append((di, int(B["col_end"][t])))
    for fam, lst in marks.items():
        for di, m in lst:
            # the claimed mark itself is excluded as a pivot source only if it IS the anchor (TEXT anchor is a pivot)
            trows.append((fam, di, -1, int(near_piv(di, m))))
            for k in range(ND):
                mm = m + rng.choice([-1, 1]) * rng.integers(10, 31)
                if 5 <= mm <= 370:
                    trows.append((fam, di, k, int(near_piv(di, mm))))
    L.D.market().release()
    return (pd.DataFrame(rows, columns=["fam", "d", "di", "draw", "n", "rev"]),
            pd.DataFrame(touch, columns=["fam", "di", "draw", "nlev", "ntouch"]),
            pd.DataFrame(trows, columns=["fam", "di", "draw", "hit"]))


def boot_diff(g, num, den, B=1000, seed=5):
    """day-cluster bootstrap of rate(claimed) - rate(placebo). g has columns di, draw, num, den."""
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


def main():
    period = sys.argv[1] if len(sys.argv) > 1 else "design"
    res = []
    for u in L4.UNDS:
        t0 = time.time()
        ev, tc, tm = run_index(u, period)
        print(u, period, len(ev), len(tc), len(tm), f"{time.time() - t0:.0f}s", flush=True)
        for (fam, d), g in ev.groupby(["fam", "d"]):
            rc, rp, n, df, p, ci = boot_diff(g, "rev", "n")
            res.append(dict(und=u, period=period, test="reversal", fam=fam, d=d, claimed=rc, placebo=rp, n_claimed=n,
                            diff=df, ci_lo=ci[0], ci_hi=ci[1], p=p))
        for fam, g in tc.groupby("fam"):
            rc, rp, n, df, p, ci = boot_diff(g, "ntouch", "nlev")
            res.append(dict(und=u, period=period, test="touch", fam=fam, d=np.nan, claimed=rc, placebo=rp,
                            n_claimed=n, diff=df, ci_lo=ci[0], ci_hi=ci[1], p=p))
        tm["one"] = 1
        for fam, g in tm.groupby("fam"):
            rc, rp, n, df, p, ci = boot_diff(g, "hit", "one")
            res.append(dict(und=u, period=period, test="time", fam=fam, d=np.nan, claimed=rc, placebo=rp,
                            n_claimed=n, diff=df, ci_lo=ci[0], ci_hi=ci[1], p=p))
    out = pd.DataFrame(res)
    out["q_bh"] = L.bh(out.p.values)
    out.to_csv(os.path.join(L4.OUT, f"placebo_{period}.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 500)
    print(out.round(4).to_string())


if __name__ == "__main__":
    main()
