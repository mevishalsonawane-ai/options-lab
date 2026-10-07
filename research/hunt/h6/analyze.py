"""h6 analysis: pair assembly, in-sample ranking (< 2025-10-01), random-pair / random-entry baselines, BH, SPA,
anchored walk-forward by year, then ONE holdout test of the single pre-chosen rule.

    python3 -I research/hunt/h6/analyze.py [holdout]
"""
import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import numpy as np, pandas as pd
from obuy import overfit as OF

H0 = cm.HOLD0
pd.set_option("display.width", 220); pd.set_option("display.max_rows", 300); pd.set_option("display.max_columns", 30)


def units(tr):
    """DIV / RPAIR legs -> one row per pair (both legs required); others: one row per trade."""
    pair = tr.set.str.startswith(("DIV", "RPAIR"))
    p = tr[pair]
    g = p.groupby(["set", "exe", "xn", "tag"])
    P = g.agg(day=("day", "first"), n=("net", "size"), gross=("gross", "sum"), net=("net", "sum"),
              charges=("charges", "sum"), outlay=("entry", lambda s: np.nan)).reset_index()
    out_lay = p.assign(o=p.entry * p.qty).groupby(["set", "exe", "xn", "tag"]).o.sum().values
    P["outlay"] = out_lay
    P = P[P.n == 2].drop(columns="n")
    P["key"] = P.tag.str.split("|").str[:2].str.join("|")
    S = tr[~pair].assign(outlay=lambda d: d.entry * d.qty, key=lambda d: d.cand.astype(str))
    S = S[["set", "exe", "xn", "tag", "day", "gross", "net", "charges", "outlay", "key"]]
    return pd.concat([P, S], ignore_index=True)


def load(groups):
    tr = pd.concat([pd.read_parquet(os.path.join(cm.SCR, f"trades_{g}.parquet")) for g in groups
                    if os.path.exists(os.path.join(cm.SCR, f"trades_{g}.parquet"))])
    pls = [pd.read_parquet(os.path.join(cm.SCR, f"pool_{g}.parquet")) for g in groups
           if os.path.exists(os.path.join(cm.SCR, f"pool_{g}.parquet"))]
    pool = pd.concat(pls) if pls else None
    return tr, pool


def dstats(u, days):
    """u: units of one variant (one exe). days: calendar of trading days for the period."""
    d = u.groupby("day").net.sum().reindex(days, fill_value=0.0)
    eq = d.cumsum().values
    dd = float((np.maximum.accumulate(np.maximum(eq, 0)) - eq).max()) if len(eq) else 0
    m = d.groupby([x.strftime("%Y-%m") for x in pd.to_datetime(pd.Index(days))]).sum()
    return dict(n=len(u), net=u.net.sum(), per_day=d.mean(), worst_day=d.min(), worst_month=m.min(),
                months_neg=f"{(m < 0).sum()}/{len(m)}", maxdd=dd)


def p_losing_month(daily, B=5000, seed=9):
    rng = np.random.default_rng(seed)
    x = daily.values
    s = rng.choice(x, size=(B, 21)).sum(axis=1)
    return float((s < 0).mean())


def main(holdout=False):
    tr, pool = load(["div", "heavy"])
    tr["day"] = pd.to_datetime(tr.day).dt.date
    U = units(tr)
    A = cm.aligned()
    alldays = [d for d in A["days"]]
    ins_days = [d for d in alldays if d < H0]
    U["year"] = [d.year for d in U.day]
    ctrl = U.set.str.startswith("RPAIR")
    V = U[~ctrl]
    vids = sorted((V.set + "|" + V.xn).unique())
    rows, daily = [], {}
    ins = V[V.day < H0]
    for vid in vids:
        s, xn = vid.split("|")
        r = ins[(ins.set == s) & (ins.xn == xn)]
        R = r[r.exe == "real"]; G = r[r.exe == "gross"]; Ap = r[r.exe == "app"]
        first = min(R.day) if len(R) else H0
        cal = [d for d in ins_days if d >= (pd.Timestamp("2024-10-07").date() if s.startswith("HEAVY") else first)]
        st = dstats(R, cal)
        daily[vid] = R.groupby("day").net.sum().reindex(ins_days, fill_value=0.0)
        # baseline p (real exe)
        if s.startswith("DIV"):
            T, k = s.split("_")[2], s.split("_")[3]
            c = U[(U.set == f"RPAIR_{T}_{k}") & (U.xn == xn) & (U.exe == "real") & (U.day < H0)]
            real = R.assign(cand=R.key); pl = c.assign(parent=c.key)
        else:
            real = R.assign(cand=R.key.astype(int))
            pl = pool[(pool.set == s) & (pool.xn == xn) & (pool.exe == "real")] if pool is not None else None
            if pl is not None:
                pl = pl[pd.to_datetime(pl.day).dt.date < H0]
        bl = OF.random_baseline(real, pl) if st["net"] > 0 else dict(p=1.0, null_mean=np.nan)
        if bl.get("null_mean") is np.nan and pl is not None and len(pl):
            bl["null_mean"] = pl.net.mean()
        rows.append(dict(vid=vid, n=st["n"], gross=G.net.sum(), app=Ap.net.sum(), net=st["net"],
                         net_tr=R.net.mean() if len(R) else np.nan, rand_tr=bl.get("null_mean", np.nan),
                         p_rand=bl["p"], per_day=st["per_day"], maxdd=st["maxdd"],
                         yrs_pos=f"{(R.groupby('year').net.sum() > 0).sum()}/{R.year.nunique()}"))
    T = pd.DataFrame(rows)
    T["q_bh"] = OF.bh(T.p_rand.values); T["p_holm"] = OF.holm(T.p_rand.values)
    X = pd.DataFrame(daily)
    sp = OF.spa(X.values, B=1000)
    print("VARIANTS tried:", len(T), "(+ RPAIR controls)")
    print(T.sort_values("net", ascending=False).round(2).to_string(index=False))
    print("SPA/White RC over in-sample daily net (real):", {k: (round(v, 3) if isinstance(v, float) else v) for k, v in sp.items()},
          "best:", X.columns[sp["best"]])
    # random pair controls themselves
    C_ = U[ctrl & (U.day < H0)]
    print("\nRPAIR controls (in-sample, per pair):")
    print(C_.groupby(["set", "xn", "exe"]).net.agg(["size", "mean", "sum"]).round(1).to_string())
    # anchored walk-forward by calendar year on index families (DIV, CATCH), HEAVY separately
    for fam, years, ty in (("idx", [2021, 2022, 2023, 2024, 2025], 1), ("HEAVY", [2024, 2025], 1)):
        sel = [v for v in vids if v.startswith("HEAVY") == (fam == "HEAVY")]
        by = pd.DataFrame({v: ins[(ins.set + "|" + ins.xn == v) & (ins.exe == "real")].groupby("year").net.sum()
                           for v in sel}).T.reindex(columns=years).fillna(0)
        if by.empty: continue
        tot = 0; out = []
        for y in years[ty:]:
            past = by[[c for c in years if c < y]].sum(axis=1)
            pick = past.idxmax(); val = by.loc[pick, y]; tot += val
            out.append(f"{y}: {pick} -> {val:,.0f}")
        print(f"\nWALK-FORWARD ({fam}, in-sample only; 2025 = Jan-Sep):", "; ".join(out), f"| total {tot:,.0f}")
    best = T.sort_values("net", ascending=False).iloc[0].vid
    json.dump(dict(best=best), open(os.path.join(cm.SCR, "pick.json"), "w"))
    print("\nPICK (best in-sample real net):", best)
    if holdout:
        s, xn = best.split("|")
        hd = [d for d in alldays if d >= H0]
        print(f"\nHOLDOUT {hd[0]} .. {hd[-1]} ({len(hd)} days), rule {best}")
        for exe in ("gross", "app", "real"):
            h = U[(U.set == s) & (U.xn == xn) & (U.exe == exe) & (U.day >= H0)]
            st = dstats(h, hd)
            d = h.groupby("day").net.sum().reindex(hd, fill_value=0.0)
            print(exe, {k: (round(v, 1) if isinstance(v, float) else v) for k, v in st.items()},
                  "P(losing 21-day month) %.2f" % p_losing_month(d), "avg outlay/trade %.0f" % h.outlay.mean())
        if s.startswith("DIV"):
            T_, k_ = s.split("_")[2], s.split("_")[3]
            c = U[(U.set == f"RPAIR_{T_}_{k_}") & (U.xn == xn) & (U.day >= H0)]
            print("holdout random pairs per pair:", c.groupby("exe").net.mean().round(1).to_dict())
            h = U[(U.set == s) & (U.xn == xn) & (U.exe == "real") & (U.day >= H0)]
            print("holdout random-pair p:", OF.random_baseline(h.assign(cand=h.key), c[c.exe == "real"].assign(parent=c.key)))
        else:
            h = U[(U.set == s) & (U.xn == xn) & (U.exe == "real") & (U.day >= H0)]
            pl = pool[(pool.set == s) & (pool.xn == xn) & (pool.exe == "real")]
            pl = pl[pd.to_datetime(pl.day).dt.date >= H0]
            print("holdout random-entry:", OF.random_baseline(h.assign(cand=h.key.astype(int)), pl))
        # all variants on holdout (for information only, not for choosing)
        H = V[(V.day >= H0) & (V.exe == "real")].groupby(V.set + "|" + V.xn).net.agg(["size", "sum"])
        Hg = V[(V.day >= H0) & (V.exe == "gross")].groupby(V.set + "|" + V.xn).net.sum().rename("gross")
        print("\n(info) all variants on holdout, real net / gross:")
        print(H.join(Hg).sort_values("sum", ascending=False).round(0).to_string())


if __name__ == "__main__":
    main(len(sys.argv) > 1 and sys.argv[1] == "holdout")
