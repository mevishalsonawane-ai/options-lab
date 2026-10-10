"""h46 analysis: variant table, BH, SPA, walk-forward, primary, chart-day comparison.

    python3 -I research/hunt/h46/analyze.py            # in-sample (writes scratchpad/hunt/h46/variants.csv, an.txt)
    python3 -I research/hunt/h46/analyze.py --holdout  # the locked holdout for the primary + survivors (run once)
"""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h46")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
KEY = ["und", "tf", "src", "rej", "xm", "money"]
VARS = [(mx, ex) for mx in (3, 5, 99) for ex in ("skip", "allow")]
HO = pd.Timestamp("2025-10-01").date()
STK0 = pd.Timestamp("2024-10-07").date()
PRIMARY = dict(tf=5, src="TWAP", rej="R1", xm="CLOSE", money=0, mx=99, ex="skip")


def day_lists(holdout=False):
    mk = market()
    out = {}
    for u in UNDS:
        ix = mk.index(u)
        ds = [d for d in ix.days if (d >= HO if holdout else d < HO)]
        out[u] = pd.DataFrame(dict(day=ds, exp=[ix.d[d]["exp"] for d in ds]))
    return out


def vdays(dl, u, src, ex):
    d = dl[u]
    if ex == "skip":
        d = d[~d.exp]
    if src == "STK":
        d = d[d.day >= STK0]
    return d.day.values


def stats(v, days):
    daily = v.groupby("day").net.sum().reindex(days, fill_value=0.0)
    eq = daily.cumsum().values
    dd = float((np.maximum.accumulate(np.concatenate([[0], eq])) - np.concatenate([[0], eq])).max())
    gd = v.groupby("day").gross.sum().reindex(days, fill_value=0.0)
    return dict(n=len(v), days=len(days), tpd=len(v) / max(len(days), 1), win=float((v.net > 0).mean()) if len(v) else np.nan,
                win_g=float((v.gross > 0).mean()) if len(v) else np.nan,
                gross_t=v.gross.mean(), net_t=v.net.mean(), chg_t=v.chg.mean(), spr_t=v.spr.mean(),
                gross_d=float(gd.mean()), net_d=float(daily.mean()), net=float(v.net.sum()), gross=float(v.gross.sum()),
                maxdd=dd, worst_day=float(daily.min()), best_day=float(daily.max()),
                pos_days=float((daily > 0).mean()), prem=v.e.mean()), daily


def load(holdout=False):
    tag = "_ho" if holdout else ""
    tr = pd.concat([pd.read_parquet(os.path.join(OUT, f"trades_{u}{tag}.parquet")) for u in UNDS
                    if os.path.exists(os.path.join(OUT, f"trades_{u}{tag}.parquet"))], ignore_index=True)
    tr["year"] = [d.year for d in tr.day]
    return tr


def main():
    dl = day_lists()
    tr = load()
    base = pd.concat([pd.read_parquet(os.path.join(OUT, f"base_{u}.parquet")) for u in UNDS
                      if os.path.exists(os.path.join(OUT, f"base_{u}.parquet"))], ignore_index=True)
    rows, dailies = [], {}
    for key, t in tr.groupby(KEY):
        kd = dict(zip(KEY, key))
        for mx, ex in VARS:
            v = t[(t.k < mx) & ((ex == "allow") | ~t.exp)]
            days = vdays(dl, kd["und"], kd["src"], ex)
            s, daily = stats(v, days)
            vid = "|".join(str(x) for x in (*key, mx, ex))
            by = v.groupby("year").net.sum()
            rows.append(dict(vid=vid, **kd, mx=mx, ex=ex, **s, **{f"y{y}": float(by.get(y, 0.0)) for y in range(2020, 2026)}))
            dailies[vid] = daily
    V = pd.DataFrame(rows)
    b = base.rename(columns={"p": "p_base"})[["und", "tf", "src", "rej", "xm", "money", "mx", "ex", "p_base", "null_mean"]]
    V = V.merge(b, on=["und", "tf", "src", "rej", "xm", "money", "mx", "ex"], how="left")
    V["p_base"] = V.p_base.fillna(1.0)
    V["q_bh"] = bh(V.p_base.values)
    # SPA per family (TWAP, STK): days x variants over the union of that family's days (0 = not trading)
    spa_res = {}
    for src in ("TWAP", "STK"):
        ids = V[V.src == src].vid.tolist()
        alld = sorted(set().union(*[set(dailies[i].index) for i in ids]))
        X = np.column_stack([dailies[i].reindex(alld, fill_value=0.0).values for i in ids])
        r = spa(X, B=500)
        r["best_vid"] = ids[r["best"]]
        spa_res[src] = r
        print(src, "SPA", r, flush=True)
    # walk-forward (anchored by year, first 2 years train) per index, TWAP family (240 each)
    wf = []
    for u in UNDS:
        sub = V[(V.und == u) & (V.src == "TWAP")].set_index("vid")
        ycols = [f"y{y}" for y in range(2020, 2026) if sub[f"y{y}"].abs().sum() > 0]
        for i, yc in enumerate(ycols[2:], start=2):
            past = sub[ycols[:i]].sum(axis=1)
            pick = past.idxmax()
            wf.append(dict(und=u, year=yc[1:], pick=pick, train=float(past[pick]), test=float(sub.loc[pick, yc])))
    WF = pd.DataFrame(wf)
    V.to_csv(os.path.join(OUT, "variants.csv"), index=False)
    WF.to_csv(os.path.join(OUT, "wf.csv"), index=False)
    # primary: NIFTY + BANKNIFTY combined
    pm = (V.tf == 5) & (V.src == "TWAP") & (V.rej == "R1") & (V.xm == "CLOSE") & (V.money == 0) & (V.mx == 99) & (V.ex == "skip")
    prim = V[pm & V.und.isin(["NIFTY", "BANKNIFTY"])]
    pt = tr[(tr.und.isin(["NIFTY", "BANKNIFTY"])) & (tr.tf == 5) & (tr.src == "TWAP") & (tr.rej == "R1") & (tr.xm == "CLOSE")
            & (tr.money == 0) & ~tr.exp]
    cd = sorted(set(vdays(dl, "NIFTY", "TWAP", "skip")) | set(vdays(dl, "BANKNIFTY", "TWAP", "skip")))
    ps, pdaily = stats(pt, cd)
    by = pt.groupby("year").agg(n=("net", "size"), gross=("gross", "sum"), net=("net", "sum"), win=("net", lambda x: (x > 0).mean()))
    # chart-day comparison (primary, BANKNIFTY+NIFTY): the best days vs the average day
    q = pdaily.sort_values()
    chart = dict(mean=float(q.mean()), median=float(q.median()), top5pct_mean=float(q[q >= q.quantile(0.95)].mean()),
                 top10_mean=float(q.tail(10).mean()), share_days_ge_5000=float((q >= 5000).mean()),
                 share_days_pos=float((q > 0).mean()), bottom5pct_mean=float(q[q <= q.quantile(0.05)].mean()))
    # what does a "chart day" look like: per index-day P&L for primary per index
    perday = pt.groupby(["und", "day"]).agg(net=("net", "sum"), gross=("gross", "sum"), n=("net", "size"))
    chart["per_index_day_top5pct"] = float(perday.net[perday.net >= perday.net.quantile(0.95)].mean())
    chart["per_index_day_median"] = float(perday.net.median())
    chart["per_index_day_share_ge_3_trades_all_green"] = float(
        pt.groupby(["und", "day"]).net.apply(lambda x: len(x) >= 2 and (x > 0).all()).mean())
    surv = V[(V.q_bh < 0.10) & (V.net > 0)]
    wfsum = WF.groupby("und").test.sum().to_dict()
    res = dict(n_variants=len(V), n_pos_net=int((V.net > 0).sum()), n_pos_gross=int((V.gross > 0).sum()),
               n_p05=int((V.p_base < 0.05).sum()), n_bh10=int((V.q_bh < 0.10).sum()), spa=spa_res,
               wf_total=float(WF.test.sum()), wf_by_und=wfsum, primary=ps, primary_by_index=prim[["und", "n", "tpd", "win", "gross_t", "net_t", "net_d", "maxdd", "p_base"]].to_dict("records"),
               primary_by_year=by.reset_index().to_dict("records"), chart=chart, survivors=surv.vid.tolist())
    with open(os.path.join(OUT, "an.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    pd.set_option("display.width", 250, "display.max_columns", 40)
    print(json.dumps(res, indent=1, default=str))
    cols = ["vid", "n", "tpd", "win", "gross_t", "net_t", "net_d", "gross_d", "maxdd", "p_base", "q_bh"]
    print("TOP by net:\n", V.sort_values("net", ascending=False)[cols].head(25).to_string())
    print("TOP by gross/day:\n", V.sort_values("gross_d", ascending=False)[cols].head(10).to_string())
    print(WF.to_string())
    # summaries by dimension (mean net/trade across variants)
    for dim in ("und", "tf", "src", "rej", "xm", "money", "mx", "ex"):
        print(V.groupby(dim)[["gross_t", "net_t", "net_d", "win", "tpd"]].mean().round(2).to_string())


def holdout(vids):
    dl = day_lists(holdout=True)
    tr = load(holdout=True)
    out = []
    for vid in vids:
        u, tf, src, rej, xm, money, mx, ex = vid.split("|")
        unds = ["NIFTY", "BANKNIFTY"] if u == "PRIMARY" else [u]
        t = tr[tr.und.isin(unds) & (tr.tf == int(tf)) & (tr.src == src) & (tr.rej == rej) & (tr.xm == xm)
               & (tr.money == int(money)) & (tr.k < int(mx)) & ((ex == "allow") | ~tr.exp)]
        days = sorted(set().union(*[set(vdays(dl, x, src, ex)) for x in unds]))
        s, daily = stats(t, days)
        by = t.groupby(t.day.map(lambda d: f"{d.year}-{d.month:02d}")).net.sum()
        out.append(dict(vid=vid, **s, worst_month=float(by.min()) if len(by) else np.nan,
                        months_pos=f"{int((by > 0).sum())}/{len(by)}"))
    H = pd.DataFrame(out)
    H.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    pd.set_option("display.width", 250, "display.max_columns", 40)
    print(H.T.to_string())


if __name__ == "__main__":
    if "--holdout" in sys.argv:
        holdout([x for x in sys.argv[1:] if x != "--holdout"])
    else:
        main()
