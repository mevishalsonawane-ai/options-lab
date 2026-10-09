"""r10: VWAP-family and Market-Profile day-structure REGIME FILTERS on existing arms (see PREREG.md).

    python3 -I research/hunt/r10/eval.py pre     # design (< 2025-10-01): filters, BH, White RC, day types
    python3 -I research/hunt/r10/eval.py hold    # locked holdout ONCE (writes HOLD_OPENED; refuses a second run)
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats  # noqa: E402
import feats as F  # noqa: E402
from obuy.overfit import bh  # noqa: E402

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt"
OUT = f"{S}/r10"
HOLD0, HOLD1 = pd.Timestamp("2025-10-01"), pd.Timestamp("2026-10-06")
TREND = ["orb", "orb_fresh", "orb_sweep", "liq_bn", "liq_oth"]
UNDS = ["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
B = 5000


def trades():
    t = pd.read_parquet(f"{S}/h19/trades.parquet")
    t = t[t.arm.isin(["orb", "orb_fresh", "orb_sweep", "range_fade"])].copy()
    t["net"] = t.net - 0.0016 * (t.entry + t.exit) * t.lot
    a = t[["arm", "und", "day", "entry_min", "side", "net"]]
    l = pd.read_parquet(f"{S}/h44/trades44.parquet")
    l["arm"] = np.where(l.und == "BANKNIFTY", "liq_bn", "liq_oth")
    l["net"] = l["net1_0.02"]
    T = pd.concat([a, l[["arm", "und", "day", "entry_min", "side", "net"]]], ignore_index=True)
    T["day"] = pd.to_datetime(T.day).dt.normalize()
    parts = []
    for u, g in T.groupby("und"):
        parts.append(pd.concat([g, F.trade_features(u, g)], axis=1))
    T = pd.concat(parts).sort_index()
    return T[T.ok == True].copy()  # noqa: E712


def masks(T):
    """keep-masks per filter id; rows where a rule is not yet knowable are kept."""
    s = T.side.values.astype(float)
    rf = (T.arm == "range_fade").values
    t = T.t.values
    out = {}

    def tr_rf(tr, fa):
        return np.where(rf, fa, tr)
    for k in ("vw", "tw"):
        sfx = "" if k == "vw" else "_tw"
        d = s * (T.px.values - T[k].values)
        z = T[k + "_z"].values
        out["V1" + sfx] = tr_rf(d > 0, d < 0)
        out["V2" + sfx] = tr_rf(s * z < 2, np.abs(z) <= 1)
    z = T.vw_z.values
    out["V3"] = np.abs(z) < 2
    out["V4"] = np.where(rf, np.nan, s * T.vw_slope.values > 0)
    for nm, col in (("V5", "pdvw"), ("V6", "avpc")):
        d = s * (T.px.values - T[col].values)
        out[nm] = np.where(np.isnan(d), True, tr_rf(d > 0, d < 0))
    out["V7"] = np.where(rf, np.nan, (out["V1"] == 1) & (out["V2"] == 1))
    known30, known60 = t >= 30, t >= 60
    od = T.odir.values
    out["T1"] = ~(known30 & (od == -s))
    oa = T.otype.values == 0
    out["T2"] = tr_rf(~(known30 & oa), ~known30 | oa)
    r = T.ib_ratio.values
    out["T3"] = np.where(~known60 | np.isnan(r), True, tr_rf(r < 1.0, r >= 1.0))
    out["T4"] = np.where(rf, np.nan, np.where(~known60 | np.isnan(r), True, ~(r > 1.3)))
    x = T.ibx.values
    out["T5"] = np.where(~known60, True, tr_rf(~((x == -s) | (x == 2)), x == 0))
    q = T.or_ratio.values
    out["T6"] = np.where(~known30 | np.isnan(q), True, tr_rf(q < 1.0, q >= 1.0))
    return out


FIDS = ["V1", "V2", "V3", "V4", "V5", "V6", "V7", "T1", "T2", "T3", "T4", "T5", "T6"]
ROBUST = ["V1_tw", "V2_tw"]


def ndays(arm_df, lo, hi):
    cal = set()
    for u in arm_df.und.unique():
        days, *_ = F.load(u)
        cal |= set(d for d in days if lo <= d < hi)
    first = arm_df.day.min()
    return len([d for d in cal if d >= first])


def evaluate(T, M, lo, hi, rng, white=False):
    rows, Z = [], []
    sel = (T.day >= lo) & (T.day < hi)
    for arm in ["orb", "orb_fresh", "orb_sweep", "range_fade", "liq_bn", "liq_oth"]:
        a = (T.arm == arm).values & sel.values
        net = T.net.values[a]
        n = len(net)
        nd = ndays(T[a], lo, hi)
        base = net.sum() / nd
        fl = [f for f in FIDS + ROBUST if not np.isnan(np.asarray(M[f][a], float)).any()]
        R = rng.random((B, n))
        for f in fl:
            keep = np.asarray(M[f][a], float) == 1
            k = int((~keep).sum())
            dlt = -net[~keep].sum() / nd
            if 0 < k < n:
                idx = np.argpartition(R, k, axis=1)[:, :k]
                rd = -net[idx].sum(axis=1) / nd
                p = float((rd >= dlt).mean())
                mu, sd = rd.mean(), rd.std()
            else:
                p, mu, sd = 1.0, 0.0, 0.0
            pw = stats.ttest_ind(net[keep], net[~keep], equal_var=False).pvalue if 2 < k < n - 2 else np.nan
            rows.append(dict(arm=arm, f=f, n=n, kept=int(keep.sum()), skip=k, days=nd, base_day=base,
                             kept_day=net[keep].sum() / nd, delta_day=dlt,
                             kept_tr=net[keep].mean() if keep.any() else np.nan,
                             skip_tr=net[~keep].mean() if k else np.nan, p_twin=p, p_welch=pw))
            if white and f in FIDS and sd > 0:
                Z.append((arm, f, a, keep, mu, sd, (dlt - mu) / sd))
    R = pd.DataFrame(rows)
    rc = None
    if white:
        zobs = max(z[-1] for z in Z)
        Bw = 2000
        mx = np.full(Bw, -np.inf)
        arms = {}
        for (arm, f, a, keep, mu, sd, z) in Z:
            arms.setdefault(arm, []).append((a, keep, mu, sd))
        for arm, lst in arms.items():
            a = lst[0][0]
            net = T.net.values[a]
            nd = ndays(T[a], lo, hi)
            P = np.argsort(rng.random((Bw, len(net))), axis=1)
            perm = net[P]
            for (_, keep, mu, sd) in lst:
                d = -perm[:, ~keep].sum(axis=1) / nd
                mx = np.maximum(mx, (d - mu) / sd)
        rc = dict(z_max=float(zobs), p=float((mx >= zobs).mean()))
    return R, rc


def daytypes(lo, hi, tag):
    out = []
    for u in UNDS:
        days, o, h, l, c, v, exp = F.load(u)
        ds = F.day_structure(o, h, l, c)
        sel = (days >= lo) & (days < hi) & ~np.isnan(ds["ib_ratio"])
        D = pd.DataFrame(dict(und=u, ib=ds["ib_ratio"][sel], ot=ds["otype"][sel], od=ds["odir"][sel],
                              trend=ds["trend"][sel], rng=ds["range_day"][sel], post=ds["post"][sel],
                              pabs=ds["post_abs"][sel], big=ds["big_day"][sel]))
        out.append(D)
    D = pd.concat(out, ignore_index=True)
    lines = [f"### day types, {tag}  (days {len(D)})"]
    for name, g in (("all 5", D), ("BANKNIFTY", D[D.und == "BANKNIFTY"])):
        g = g.copy()
        g["ibq"] = pd.cut(g.ib, [0, 0.75, 1.0, 1.3, 99], labels=["<0.75", "0.75-1", "1-1.3", ">1.3"])
        t1 = g.groupby("ibq", observed=True).agg(days=("trend", "size"), trend=("trend", "mean"), range_=("rng", "mean"),
                                                    post_move=("pabs", "mean"), day_rng=("big", "mean"))
        ct = pd.crosstab(g.ibq, g.trend)
        p1 = stats.chi2_contingency(ct)[1]
        g["otn"] = g.ot.map({0: "OA", 1: "OD", 2: "ORR", 3: "OTD"})
        t2 = g.groupby("otn").agg(days=("trend", "size"), trend=("trend", "mean"), range_=("rng", "mean"),
                                                    post_move=("pabs", "mean"), day_rng=("big", "mean"))
        p1b = stats.kruskal(*[x.pabs.dropna().values for _, x in g.groupby("ibq", observed=True)]).pvalue
        lines.append(f"(IB-independent: Kruskal p of rest-of-day move across IB groups = {p1b:.3g})")
        p2 = stats.chi2_contingency(pd.crosstab(g.otn, g.trend))[1]
        hit = g[g.od != 0].groupby("otn").apply(lambda x: pd.Series(dict(n=len(x), dir_hit=(x.od == x.post).mean())))
        lines += [f"#### {name}: by IB ratio (chi2 p={p1:.3g})", t1.round(3).to_string(),
                  f"#### {name}: by opening type (chi2 p={p2:.3g})", t2.round(3).to_string(),
                  f"#### {name}: opening direction vs rest-of-day (after 10:14) direction", hit.round(3).to_string()]
    return "\n".join(lines)


def main(mode):
    rng = np.random.default_rng(10)
    T = trades()
    M = masks(T)
    if mode == "pre":
        R, rc = evaluate(T, M, pd.Timestamp("2020-01-01"), HOLD0, rng, white=True)
        fam = R[R.f.isin(FIDS)]
        R.loc[fam.index, "q_bh"] = bh(fam.p_twin.values)
        R.to_csv(f"{OUT}/pre.csv", index=False)
        with open(f"{OUT}/pre.log", "w") as fh:
            fh.write(R.round(4).to_string() + f"\n\nWhite RC over {len(fam)} filters: {rc}\n\n")
            fh.write(daytypes(pd.Timestamp("2020-01-01"), HOLD0, "design < 2025-10-01"))
        print(open(f"{OUT}/pre.log").read())
    else:
        flag = f"{OUT}/HOLD_OPENED"
        if os.path.exists(flag):
            sys.exit("holdout already opened")
        if not os.path.exists(f"{OUT}/pre.csv"):
            sys.exit("run pre first")
        open(flag, "w").write(pd.Timestamp.now().isoformat())
        R, _ = evaluate(T, M, HOLD0, HOLD1 + pd.Timedelta(days=1), rng)
        P = pd.read_csv(f"{OUT}/pre.csv")
        R = R.merge(P[["arm", "f", "delta_day", "q_bh"]].rename(columns=dict(delta_day="pre_delta")), on=["arm", "f"])
        R["PASS"] = (R.pre_delta > 0) & (R.q_bh <= 0.10) & (R.delta_day > 0) & (R.p_twin <= 0.10)
        R.to_csv(f"{OUT}/hold.csv", index=False)
        with open(f"{OUT}/hold.log", "w") as fh:
            fh.write(R.round(4).to_string() + "\n\n")
            fh.write(daytypes(HOLD0, HOLD1 + pd.Timedelta(days=1), "holdout 2025-10-01 .. 2026-10-06"))
        print(open(f"{OUT}/hold.log").read())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "pre")
