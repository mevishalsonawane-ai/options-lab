"""h33 analysis: 3,072 pre-registered variants (arm P price shocks, arm N news events), random-time baseline, BH,
SPA / RC, anchored walk-forward by year; descriptive index continuation and news/shock overlap. See PREREG.md.

    python3 -I research/hunt/h33/test.py            # PRE only (< 2025-10-01)
    python3 -I research/hunt/h33/test.py --holdout  # once: survivors (or, if none, the best PRE variant per arm as INFO)
"""
from __future__ import annotations

import itertools
import json
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import ddate  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402
from obuy.stats import max_dd  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h33")
HOLD = date(2025, 10, 1)
HS = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016, "FINNIFTY": 0.0042, "MIDCPNIFTY": 0.0021, "SENSEX": 0.0020}
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
XS = ["P15", "P20", "P25", "P30", "LIQ", "LOCK", "T15", "T30"]
CLS = ["rbi", "govt", "bank_corp", "heavy_corp", "geo", "any"]
NS = (1, 3, 5)
B = 2000
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)
pd.set_option("display.max_rows", 200)


def sessions(holdout):
    S = {}
    for u in UNDS:
        d = [ddate(x) for x in np.load(os.path.join(OUT, f"feats_{u}.npz"))["days"]]
        S[u] = sorted(x for x in d if (x >= HOLD) == holdout and x.year >= 2020)
    return S


def load(holdout):
    tr = pd.concat([pd.read_parquet(os.path.join(OUT, f"trades_{m}.parquet")) for m in ("price", "news")],
                   ignore_index=True).drop_duplicates(["und", "day", "sig_min", "side", "X", "tag"])
    tr["day"] = pd.to_datetime(tr.day).dt.date
    tr = tr[(tr.day >= HOLD) if holdout else (tr.day < HOLD)].copy()
    hs = tr.und.map(HS).values
    sp = (tr.entry.values.astype(float) + tr.exit.values.astype(float)) * tr.qty.values
    tr["net1"] = tr.net.values - hs * sp
    tr["net15"] = tr.net.values - 1.5 * hs * sp
    tr["year"] = [d.year for d in tr.day]
    real = tr[tr.tag == "E"].drop(columns="tag")
    rand = tr[tr.tag == "R"].drop(columns="tag")
    sh = pd.read_parquet(os.path.join(OUT, "shocks.parquet"))
    sh["day"] = pd.to_datetime(sh.day).dt.date
    sh = sh[(sh.day >= HOLD) if holdout else (sh.day < HOLD)]
    nm = pd.read_parquet(os.path.join(OUT, "newsev.parquet"))
    nm["day"] = pd.to_datetime(nm.day).dt.date
    nm = nm[(nm.day >= HOLD) if holdout else (nm.day < HOLD)]
    return real, rand, sh, nm


def variants():
    out = []
    for u, W, Z, VB, N, S, X in itertools.product(UNDS, (1, 3), (5, 8), (0, 1), NS, ("follow", "fade"), XS):
        out.append(dict(arm="P", und=u, W=W, Z=Z, VB=VB, N=N, S=S, X=X))
    for c, I, Cf, N, S, X in itertools.product(CLS, ("any", "burst"), (0, 1), NS, ("follow", "fade"), XS):
        out.append(dict(arm="N", cls=c, I=I, Cf=Cf, N=N, S=S, X=X))
    return out


def vname(v):
    if v["arm"] == "P":
        return f"P|{v['und']}|W{v['W']}|Z{v['Z']}|VB{v['VB']}|N{v['N']}|{v['S']}|{v['X']}"
    return f"N|{v['cls']}|{v['I']}|C{v['Cf']}|N{v['N']}|{v['S']}|{v['X']}"


def signals(v, sh, nm):
    s = 1 if v["S"] == "follow" else -1
    if v["arm"] == "P":
        e = sh[(sh.und == v["und"]) & (sh.W == v["W"]) & (sh.Z == v["Z"])]
        if v["VB"]:
            e = e[e.vb >= 2]
        return pd.DataFrame(dict(und=e.und.values, day=e.day.values, sig_min=(e.tmin + v["N"]).values,
                                 side=(e.dir * s).values))
    e = nm[(nm.cls == v["cls"]) & (nm.N == v["N"])]
    if v["I"] == "burst":
        e = e[e.n20 >= 3]
    if v["Cf"]:
        e = e[e.zN.abs() >= 2]
    return pd.DataFrame(dict(und=e.und.values, day=e.day.values, sig_min=(e.hmin + v["N"]).values,
                             side=(e.dir * s).values))


def one_at_a_time(t):
    if len(t) < 2:
        return t
    t = t.sort_values(["und", "day", "sig_min"])
    keep = np.ones(len(t), bool)
    last = {}
    for i, (u, d, sm, xm) in enumerate(zip(t.und.values, t.day.values, t.sig_min.values, t.exit_min.values)):
        k = (u, d)
        if k in last and sm < last[k]:
            keep[i] = False
            continue
        last[k] = xm
    return t[keep]


class Bank:
    """Random-time trades per (und, day) for one exit set."""

    def __init__(self, rand, X):
        r = rand[rand.X == X].sort_values(["und", "day"])
        self.v = r.net1.values
        key = list(zip(r.und.values, r.day.values))
        self.start, self.cnt = {}, {}
        for i, k in enumerate(key):
            if k not in self.start:
                self.start[k] = i
                self.cnt[k] = 0
            self.cnt[k] += 1

    def p(self, t, rng):
        st = np.array([self.start.get(k, -1) for k in zip(t.und.values, t.day.values)])
        ct = np.array([self.cnt.get(k, 0) for k in zip(t.und.values, t.day.values)])
        ok = ct > 0
        if ok.sum() < 5:
            return np.nan, np.nan
        st, ct = st[ok], ct[ok]
        idx = st[:, None] + (rng.random((len(st), B)) * ct[:, None]).astype(int)
        means = self.v[idx].mean(axis=0)
        real = t.net1.values[ok].mean()
        return float((1 + (means >= real).sum()) / (B + 1)), float(means.mean())


def evaluate(holdout, only=None):
    real, rand, sh, nm = load(holdout)
    S = sessions(holdout)
    alldays = sorted(set().union(*[set(x) for x in S.values()]))
    dpos = {d: i for i, d in enumerate(alldays)}
    key = ["und", "day", "sig_min", "side"]
    RX = {x: real[real.X == x] for x in XS}
    banks = {x: Bank(rand, x) for x in XS}
    rng = np.random.default_rng(5)
    rows, daily, trades = [], [], {}
    vl = variants()
    if only is not None:
        vl = [v for v in vl if vname(v) in only]
    for v in vl:
        sg = signals(v, sh, nm)
        t = sg.merge(RX[v["X"]], on=key, how="inner")
        t = one_at_a_time(t)
        u = v.get("und", "BANKNIFTY" if v.get("cls") in ("rbi", "bank_corp") else "NIFTY")
        nses = len(S[u])
        dv = np.zeros(len(alldays))
        if len(t):
            g = t.groupby("day").net1.sum()
            dv[[dpos[d] for d in g.index]] = g.values
        r = dict(name=vname(v), arm=v["arm"], und=u, n=len(t), n_sig=len(sg), net1=t.net1.sum(), net15=t.net15.sum(),
                 gross=t.gross.sum(), per_tr=t.net1.mean() if len(t) else np.nan,
                 win=(t.net1 > 0).mean() if len(t) else np.nan, rs_day=t.net1.sum() / nses,
                 rs_day_gross=t.gross.sum() / nses, maxdd=max_dd(np.cumsum(dv)),
                 worst_day=dv.min(), prem=(t.entry * t.qty).mean() if len(t) else np.nan)
        for y in range(2020, 2027):
            r[f"y{y}"] = t.net1[t.year == y].sum()
        if len(t) >= 5 and r["net1"] > 0:
            r["p_rand"], r["rand_mean"] = banks[v["X"]].p(t, rng)
        else:
            r["p_rand"], r["rand_mean"] = 1.0, np.nan
        rows.append(r)
        daily.append(dv)
        trades[r["name"]] = t
    res = pd.DataFrame(rows)
    return res, np.array(daily).T, trades, alldays


def walk_forward(res, trades):
    out = []
    for arm in ("P", "N", "all"):
        sub = res if arm == "all" else res[res.arm == arm]
        tot = 0.0
        for Y in (2022, 2023, 2024, 2025):
            best, bv = None, -np.inf
            for nm_ in sub.name:
                t = trades[nm_]
                tt = t[t.year < Y]
                if len(tt) >= 20 and tt.net1.sum() > bv:
                    best, bv = nm_, tt.net1.sum()
            if best is None:
                continue
            t = trades[best]
            got = t.net1[t.year == Y].sum()
            tot += got
            out.append(dict(arm=arm, year=Y, pick=best, train_net=bv, test_net=got, test_n=int((t.year == Y).sum())))
        out.append(dict(arm=arm, year="total", pick="", train_net=np.nan, test_net=tot, test_n=0))
    return pd.DataFrame(out)


def descriptive(sh, nm, hl):
    """Index continuation (follow sign) after events, in units of the 1-min sigma and bp; overlap of news and shocks."""
    F = {u: dict(np.load(os.path.join(OUT, f"feats_{u}.npz"))) for u in UNDS}
    pos = {u: {ddate(d): i for i, d in enumerate(F[u]["days"])} for u in UNDS}
    rows = []

    def cont(u, d, m0, direc, tag):
        i = pos[u].get(d)
        if i is None:
            return
        c = F[u]["c"][i]
        col = m0 - C.OPEN_M
        rec = dict(tag=tag, und=u)
        for H in (15, 30):
            if col + H < C.W and np.isfinite(c[col]) and np.isfinite(c[col + H]):
                rec[f"r{H}"] = direc * (c[col + H] / c[col] - 1) * 1e4
        rows.append(rec)

    for e in sh[(sh.W == 1) & (sh.Z == 5)].itertuples():
        for N in NS:
            cont(e.und, e.day, e.tmin + N, e.dir, f"P W1 Z5 N{N}")
    for e in sh[(sh.W == 1) & (sh.Z == 8)].itertuples():
        cont(e.und, e.day, e.tmin + 3, e.dir, "P W1 Z8 N3")
    for e in nm.itertuples():
        cont(e.und, e.day, e.hmin + e.N, e.dir, f"N {e.cls} N{e.N}")
        if e.n20 >= 3:
            cont(e.und, e.day, e.hmin + e.N, e.dir, f"N burst N{e.N}")
    d = pd.DataFrame(rows)
    g = d.groupby("tag").agg(n=("r15", "size"), r15_bp=("r15", "mean"), r15_t=("r15", lambda x: x.mean() / x.std() * np.sqrt(x.count())),
                             r30_bp=("r30", "mean"), r30_t=("r30", lambda x: x.mean() / x.std() * np.sqrt(x.count())))
    # overlap
    ov = []
    n1 = nm[nm.N == 1]
    shp = sh[(sh.Z == 5)]
    shk = {(u, d): np.array(sorted(set(g_.tmin))) for (u, d), g_ in shp.groupby(["und", "day"])}
    for e in n1.itertuples():
        a = shk.get((e.und, e.day), np.array([]))
        ov.append(dict(kind="news->shock", cls=e.cls, burst=e.n20 >= 3,
                       hit=bool(((a >= e.hmin - 5) & (a <= e.hmin + 15)).any())))
    hlm = {d: np.array(sorted(g_["min"])) for d, g_ in hl.groupby("day")}
    for e in shp[shp.W == 1].drop_duplicates(["und", "day", "tmin"]).itertuples():
        a = hlm.get(e.day, np.array([]))
        ov.append(dict(kind="shock->news", cls=e.und, burst=False, hit=bool(((a >= e.tmin - 15) & (a <= e.tmin + 10)).any())))
    # base rate: random minutes
    rng = np.random.default_rng(1)
    for d in list(hlm)[:: 1]:
        m = int(rng.integers(9 * 60 + 25, 14 * 60 + 31))
        a = hlm[d]
        ov.append(dict(kind="random->news", cls="", burst=False, hit=bool(((a >= m - 15) & (a <= m + 10)).any())))
    o = pd.DataFrame(ov).groupby(["kind", "cls", "burst"]).hit.agg(["size", "mean"])
    return g, o


def main():
    holdout = "--holdout" in sys.argv
    if not holdout:
        res, X, trades, days = evaluate(False)
        res["q_bh"] = bh(res.p_rand.fillna(1).values)
        sp = spa(X, B=1000)
        sp_p = spa(X[:, (res.arm == "P").values], B=1000)
        sp_n = spa(X[:, (res.arm == "N").values], B=1000)
        wf = walk_forward(res, trades)
        res = res.sort_values("net1", ascending=False)
        res.to_csv(os.path.join(OUT, "variants_pre.csv"), index=False)
        wf.to_csv(os.path.join(OUT, "wf.csv"), index=False)
        print("variants", len(res), "with trades", int((res.n > 0).sum()), " net>0:", int((res.net1 > 0).sum()))
        print("SPA all", sp, "\nSPA P", sp_p, "\nSPA N", sp_n)
        cols = ["name", "n", "net1", "net15", "gross", "per_tr", "win", "rs_day", "rs_day_gross", "maxdd", "p_rand",
                "q_bh", "y2021", "y2022", "y2023", "y2024", "y2025"]
        print("\nTOP 25 by net (real spread)\n", res[cols].head(25).to_string(index=False))
        print("\nTOP by p_rand (n>=30)\n", res[res.n >= 30].sort_values("p_rand")[cols].head(15).to_string(index=False))
        print("\nWalk-forward\n", wf.to_string(index=False))
        # arm summaries
        for arm in ("P", "N"):
            s = res[res.arm == arm]
            print(f"\narm {arm}: median per-trade net {s.per_tr.median():.0f}, share net>0 {(s.net1 > 0).mean():.2f}")
            print(s.assign(side=s.name.str.contains("follow").map({True: "follow", False: "fade"}),
                           X=s.name.str.split("|").str[-1]).groupby(["side", "X"]).per_tr.median().unstack().round(0))
        surv = res[(res.net1 > 0) & (res.net15 > 0) & (res.q_bh < 0.05) & (res.n >= 30)]
        wf_ok = wf[(wf.year == "total")].set_index("arm").test_net
        ok = sp["spa_p"] < 0.10
        surv = surv[np.array([wf_ok.get(a, -1) > 0 and ok for a in surv.arm], dtype=bool)]
        print("\nSURVIVORS", len(surv), surv.name.tolist())
        info = {a: res[(res.arm == a) & (res.n >= 30)].sort_values("net1", ascending=False).name.iloc[0] for a in ("P", "N")}
        json.dump(dict(survivors=surv.name.tolist(), info=info, spa=sp, spa_P=sp_p, spa_N=sp_n),
                  open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)
        real, rand, sh, nm = load(False)
        hl = pd.read_parquet(os.path.join(OUT, "news_hl.parquet"))
        hl = hl[(hl["min"] >= 555) & (hl["min"] <= 930)]
        g, o = descriptive(sh, nm, hl)
        print("\nINDEX CONTINUATION after events (follow sign; bp of index)\n", g.round(2).to_string())
        print("\nOVERLAP\n", o.round(3).to_string())
        g.to_csv(os.path.join(OUT, "continuation_pre.csv"))
        o.to_csv(os.path.join(OUT, "overlap_pre.csv"))
        # random-entry blind level for the same exits
        print("\nBLIND random-time per-trade net1 by exit and index (PRE)\n",
              rand.groupby(["und", "X"]).net1.mean().unstack().round(0).to_string())
    else:
        ch = json.load(open(os.path.join(OUT, "choice.json")))
        names = ch["survivors"] or list(ch["info"].values())
        res, X, trades, days = evaluate(True, only=set(names))
        print("HOLDOUT", "survivors" if ch["survivors"] else "INFO ONLY (no survivors)")
        cols = ["name", "n", "net1", "net15", "gross", "per_tr", "win", "rs_day", "rs_day_gross", "maxdd", "worst_day",
                "p_rand"]
        print(res[cols].to_string(index=False))
        res.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
        real, rand, sh, nm = load(True)
        hl = pd.read_parquet(os.path.join(OUT, "news_hl.parquet"))
        hl = hl[(hl["min"] >= 555) & (hl["min"] <= 930)]
        g, o = descriptive(sh, nm, hl)
        print("\nINDEX CONTINUATION (holdout)\n", g.round(2).to_string())


if __name__ == "__main__":
    main()
