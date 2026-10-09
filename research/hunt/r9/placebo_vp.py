"""R9 A: placebo test of prior-day volume-profile levels (POC, VA edges, HVN, LVN) vs the same set randomly shifted.
    python3 -I placebo_vp.py design | hold | fut      (hold is run by holdout.py only)"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

ND = 20
DS = (0.001, 0.0025)
HOR = 60
T0, T1 = R.col(9, 20), R.col(14, 45)
BK = np.array([R.col(10, 30), R.col(12, 30)])        # bucket edges
FAMS = ["POC", "VAEDGE", "HVN", "LVN"]
CLAIM = {"POC": 1, "VAEDGE": 1, "HVN": 1, "LVN": -1}   # +1: reverses MORE than placebo


def events(h, l, c, lev, a=T0, b=T1):
    """R4 placebo.events: first touch of each level in columns [a, b]."""
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
    """R4 placebo.outcome: 1 reversal (back d before d through), 0 break, nan unresolved."""
    n = len(lev)
    out = np.full(n, np.nan)
    if n == 0:
        return out
    Wn = len(h)
    D = lev * d
    imm = np.where(sg == 1, h[j] >= lev + D, l[j] <= lev - D)
    idx = j[:, None] + 1 + np.arange(HOR)[None, :]
    valid = idx < Wn
    idx = np.minimum(idx, Wn - 1)
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


def famsets(lv):
    return {"POC": np.array([lv["POC"]]), "VAEDGE": np.array([lv["VAH"], lv["VAL"]]), "HVN": np.asarray(lv["HVN"]),
            "LVN": np.asarray(lv["LVN"])}


def run(u, period, src="optv", seed=0):
    P = R.panel(u)
    if period == "design":
        days = np.nonzero(P["ok"] & P["isdes"])[0]
    elif period == "hold":
        days = np.nonzero(P["ok"] & P["ishold"])[0]
    else:   # fut: sessions whose PRIOR day has futures minutes
        days = np.array([i for i in range(1, P["nd"]) if P["has_fut"][i - 1] and P["ok"][i]], int)
    rng = np.random.default_rng(1000 + seed + R.UNDS.index(u) * 7 + {"design": 0, "hold": 1, "fut": 2}[period])
    rows = []    # fam, d, di, kind(0 claimed / 1 placebo), bucket, n, rev
    for di in days:
        lv = R.levels(P, di, src)
        if lv is None:
            continue
        h, l, c = P["hh"][di], P["ll"][di], P["c"][di]
        pc = P["c"][di - 1][np.isfinite(P["c"][di - 1])][-1]
        shifts = rng.choice([-1, 1], ND) * rng.uniform(0.0015, 0.006, ND) * pc
        for fam, lev in famsets(lv).items():
            if len(lev) == 0:
                continue
            for kind, sets in ((0, [lev]), (1, [lev + s for s in shifts])):
                for L_ in sets:
                    lvv, j, sg = events(h, l, c, L_)
                    if len(lvv) == 0:
                        continue
                    b = np.searchsorted(BK, j, side="right")
                    for d in DS:
                        r = outcome(h, l, lvv, j, sg, d)
                        ok = np.isfinite(r)
                        for bb in range(3):
                            m = ok & (b == bb)
                            if m.any():
                                rows.append((fam, d, di, kind, bb, int(m.sum()), float(r[m].sum())))
    return pd.DataFrame(rows, columns=["fam", "d", "di", "kind", "bk", "n", "rev"])


def stat(g, B=1000, seed=5):
    """time-matched diff (claimed - placebo) and raw diff; day-cluster bootstrap one-sided p in the claimed direction."""
    days = np.unique(g.di.values)
    di_pos = {d: i for i, d in enumerate(days)}
    A = np.zeros((len(days), 2, 3, 2))       # day, kind, bucket, (n, rev)
    for r in g.itertuples():
        A[di_pos[r.di], r.kind, r.bk, 0] += r.n
        A[di_pos[r.di], r.kind, r.bk, 1] += r.rev

    def f(S):
        n, rv = S[..., 0], S[..., 1]
        with np.errstate(invalid="ignore", divide="ignore"):
            rc = rv[0] / n[0]
            rp = rv[1] / n[1]
            w = n[0] / n[0].sum()
            tm = np.nansum(w * (rc - rp))
            raw = rv[0].sum() / n[0].sum() - rv[1].sum() / n[1].sum()
        return tm, raw, rv[0].sum() / n[0].sum(), rv[1].sum() / n[1].sum(), n[0].sum()
    tm, raw, rc, rp, nc = f(A.sum(0))
    rng = np.random.default_rng(seed)
    bs = np.array([f(A[rng.integers(0, len(days), len(days))].sum(0))[0] for _ in range(B)])
    return dict(claimed=rc, placebo=rp, diff_raw=raw, diff_tm=tm, n_claimed=int(nc), days=len(days), boot=bs)


def summarize(df, u, period):
    out = []
    for (fam, d), g in df.groupby(["fam", "d"]):
        s = stat(g)
        sgn = CLAIM[fam]
        p = float((s["boot"] * sgn <= 0).mean())
        out.append(dict(und=u, period=period, fam=fam, d=d, claim="more" if sgn > 0 else "less", claimed=s["claimed"],
                        placebo=s["placebo"], diff_raw_pp=100 * s["diff_raw"], diff_tm_pp=100 * s["diff_tm"],
                        n_touches=s["n_claimed"], days=s["days"], p=p))
    return out


def main(period, src="optv"):
    allr = []
    for u in R.UNDS:
        if period == "fut" and u == "FINNIFTY":
            continue
        df = run(u, period, "futv" if period == "fut" else src)
        if df.empty:
            continue
        allr += summarize(df, u, period)
        print(u, period, "done", flush=True)
    res = pd.DataFrame(allr)
    if period == "design":
        res["q"] = R.bh(res.p.values)
    tag = period if src == "optv" else f"{period}_{src}"
    res.to_csv(os.path.join(R.OUT, f"A_placebo_{tag}.csv"), index=False)
    with pd.option_context("display.width", 200):
        print(res.round(4).to_string(index=False))
    return res


if __name__ == "__main__":
    per = sys.argv[1]
    if per == "hold" and not os.environ.get("R9_HOLDOUT_OK"):
        sys.exit("holdout runs only through holdout.py")
    main(per)
