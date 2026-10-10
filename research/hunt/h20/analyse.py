"""h20 analysis (pre-holdout only unless `holdout` is passed). See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h20/cache flock <scratch>/obuy.lock python3 -I research/hunt/h20/analyse.py pre
    ... analyse.py holdout     (run ONCE, after pre)
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sim  # noqa: E402  (puts research/ on the path)

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.engine import Exits, LADDER  # noqa: E402

ARMS = sim.ARMS
H = sim.HOLDOUT
OUT = sim.OUT


def specs_for(arm):
    tp = ARMS[arm][4]
    base = sim.Spec("B0_app_now", tgt=tp, ladder=sim.app_ladder(tp))
    nolock = sim.Spec("B1_no_lock", tgt=tp)
    alts = [sim.Spec("A1_t15", tgt=15.0),
            sim.Spec("A2_t20_be10", tgt=20.0, ladder=((10.0, 0.0),)),
            sim.Spec("A3_t30_be10_l20", tgt=30.0, ladder=((10.0, 0.0), (20.0, 10.0))),
            sim.Spec("A4_t40_tight", tgt=40.0, ladder=((8.0, 0.0), (15.0, 8.0), (20.0, 12.0), (30.0, 20.0))),
            sim.Spec("A5_t40_trail50", tgt=40.0, trail=(10.0, 0.5)),
            sim.Spec("A6_t40_lad_time30", tgt=40.0, ladder=sim.app_ladder(40.0), time=(30, 10.0))]
    return base, nolock, alts


def run(P, arm, sp, en="app", mode="app", part="pre", pool=False):
    pk, pl = P[(arm, en)]
    m = pk.meta
    sel = (m.day < H) if part == "pre" else (m.day >= H)
    tr = sim.sim(pk.subset(np.nonzero(sel.values)[0]), sp, sim.EXES[en], mode)
    tr = sim.positions(tr, ARMS[arm][3])
    if not pool:
        return tr
    pm = pl.meta
    ps = (pm.day < H) if part == "pre" else (pm.day >= H)
    pt = sim.sim(pl.subset(np.nonzero(ps.values)[0]), sp, sim.EXES[en], mode)
    return tr, pt


def days_of(P, part):
    m = P[("orb", "app")][0].meta
    # every BANKNIFTY session with a real index day in the data (signals or not): use obuy's index days
    from obuy.data import market
    ix = market().index("BANKNIFTY")
    ds = [d for d in ix.days if ix.d[d]["real"] and ((d < H) if part == "pre" else (d >= H))]
    ds = [d for d in ds if d >= m.day.min()]
    return pd.Index(sorted(ds))


def summ(tr, days):
    if tr is None or not len(tr):
        return dict(n=0)
    dly = tr.groupby("day").net.sum().reindex(days, fill_value=0.0)
    eq = dly.cumsum()
    dd = float((eq - eq.cummax()).min())
    mon = dly.groupby([d.strftime("%Y-%m") for d in dly.index]).sum()
    s = tr.net.std(ddof=1)
    return dict(n=len(tr), win=round(float((tr.net > 0).mean()) * 100, 1), gross=round(float(tr.gross.sum())),
                net=round(float(tr.net.sum())), per_trade=round(float(tr.net.mean()), 1),
                t=round(float(tr.net.mean() / (s / np.sqrt(len(tr)))), 2) if len(tr) > 2 and s > 0 else None,
                rs_day=round(float(dly.mean()), 1), maxdd=round(dd), worst_day=round(float(dly.min())),
                worst_month=round(float(mon.min())), months_neg=f"{int((mon < 0).sum())}/{len(mon)}",
                years=" ".join(f"{y}:{v:+.0f}" for y, v in tr.groupby("year").net.sum().items()))


def mfe_table(P, arm):
    """MFE of the arm's trades: entries as the app takes them today (app mode baseline), paths with the -40 stop only."""
    base, _, _ = specs_for(arm)
    bt = run(P, arm, base, "app", "app")
    so = sim.Spec("stop_only", tgt=None)
    pk = P[(arm, "app")][0]
    allt = sim.sim(pk.subset(np.nonzero((pk.meta.day < H).values)[0]), so, sim.EXES["app"], "tick")
    s = allt.set_index("cand").loc[bt.cand.values]
    pts = s.exit.values - s.entry.values
    r = dict(arm=arm, n=len(s))
    for x in (10, 15, 20, 30, 40, 60, 80):
        r[f"hi>={x}"] = round(float((s.mfe_h >= x).mean()) * 100, 1)
    for x in (10, 20, 30, 40):
        r[f"cl>={x}"] = round(float((s.mfe_c >= x).mean()) * 100, 1)
    r["spike10_not_close"] = round(float(((s.mfe_h >= 10) & (s.mfe_c < 10)).mean()) * 100, 1)
    r["med_mfe_hi"] = round(float(np.median(s.mfe_h)), 1)
    r["stop_hit"] = round(float((s.why == "stop").mean()) * 100, 1)
    for x in (10, 20, 30):
        m = s.mfe_h.values >= x
        r[f"giveback|>={x}_med"] = round(float(np.median(s.mfe_h.values[m] - pts[m])), 1) if m.any() else None
        r[f"end<0|>={x}"] = round(float((pts[m] < 0).mean()) * 100, 1) if m.any() else None
    return r


def baseline_detail(P, arm):
    base, nolock, _ = specs_for(arm)
    out = {}
    for mode in ("app", "tick"):
        for sp in (base, nolock):
            tr = run(P, arm, sp, "app", mode)
            w = tr.why.value_counts(normalize=True).mul(100).round(1).to_dict()
            lk = tr[tr.why == "lock"]
            d = dict(n=len(tr), net=round(float(tr.net.sum())), per_trade=round(float(tr.net.mean()), 1),
                     gross=round(float(tr.gross.sum())), why=w)
            if len(lk):
                d["lock_n"] = len(lk)
                d["lock_fill_minus_level_med"] = round(float(np.median(lk.exit - lk.entry - (lk.lock_at - lk.entry))), 2)
                d["lock_net_negative_pct"] = round(float((lk.net < 0).mean()) * 100, 1)
                d["lock_avg_net"] = round(float(lk.net.mean()), 1)
            out[f"{mode}|{sp.name}"] = d
    return out


def pre(P):
    days = days_of(P, "pre")
    rep = {"mfe": [], "baseline": {}, "variants": []}
    for arm in ARMS:
        rep["mfe"].append(mfe_table(P, arm))
        rep["baseline"][arm] = baseline_detail(P, arm)
        print("mfe/baseline", arm, flush=True)
    cols, pvals, rows = {}, [], []
    for arm in ARMS:
        base, nolock, alts = specs_for(arm)
        for sp in [base, nolock] + alts:
            tr, pt = run(P, arm, sp, "app", "app", pool=True)
            g = run(P, arm, sp, "gross", "app")
            rl = run(P, arm, sp, "real", "app")
            tk = run(P, arm, sp, "app", "tick")
            rb = OF.random_baseline(tr, pt)
            s = summ(tr, days)
            row = dict(arm=arm, variant=sp.name, alt=sp.name.startswith("A"), **s, gross_headline=round(float(g.gross.sum())),
                       net_real=round(float(rl.net.sum())), net_tick=round(float(tk.net.sum())),
                       rand_p=round(rb["p"], 4), rand_mean=round(rb.get("null_mean", np.nan), 1))
            # walk-forward-style check: per-year sign
            yrs = tr.groupby("year").net.sum()
            row["years_pos"] = f"{int((yrs > 0).sum())}/{len(yrs)}"
            rows.append(row)
            if sp.name.startswith("A"):
                cols[f"{arm}|{sp.name}"] = tr.groupby("day").net.sum().reindex(days, fill_value=0.0).values
            print(arm, sp.name, s.get("net"), rb["p"], flush=True)
    df = pd.DataFrame(rows)
    a = df.alt.values
    df.loc[a, "bh_q"] = OF.bh(df.loc[a, "rand_p"].values).round(4)
    X = np.column_stack(list(cols.values()))
    sp_ = OF.spa(X, B=1000)
    rep["spa"] = dict(sp_, best_name=list(cols)[sp_["best"]])
    # anchored walk-forward per arm over the 8 exits (baseline, no-lock, 6 alts): pick best net on earlier years
    wf = []
    for arm in ARMS:
        base, nolock, alts = specs_for(arm)
        trs = {sp.name: run(P, arm, sp, "app", "app") for sp in [base, nolock] + alts}
        by = pd.DataFrame({k: v.groupby("year").net.sum() for k, v in trs.items()}).T.fillna(0.0)
        years = sorted(by.columns)
        tot = 0.0
        for i, y in enumerate(years):
            if i < 2:
                continue
            past = by[years[:i]].sum(axis=1)
            pick = past.idxmax()
            tot += by.loc[pick, y]
            wf.append(dict(arm=arm, year=y, picked=pick, test_net=round(float(by.loc[pick, y]))))
        wf.append(dict(arm=arm, year="total", picked="", test_net=round(tot)))
    rep["wf"] = wf
    df.to_csv(os.path.join(HERE, "pre_variants.csv"), index=False)
    with open(os.path.join(HERE, "pre_report.json"), "w") as f:
        json.dump(rep, f, indent=1, default=str)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 40)
    print(pd.DataFrame(rep["mfe"]).to_string())
    print(json.dumps(rep["baseline"], indent=1))
    print(df.to_string())
    print(rep["spa"])
    print(pd.DataFrame(wf).to_string())


def parity(P):
    """Tick-mode baseline vs obuy's own run_exits (same ladder) on ORB pre-holdout candidates."""
    pk = P[("orb", "app")][0]
    sub = pk.subset(np.nonzero((pk.meta.day < H).values)[0][:4000])
    a = sim.sim(sub, specs_for("orb")[0], sim.EXES["app"], "tick")
    b = sub.run(Exits(stop_pts=40, tgt_pts=40, ladder=LADDER, ladder_ref_pts=40, sq_off=sim.SQ), sim.EXES["app"])
    m = a.merge(b, on="cand", suffixes=("", "_o"))
    print("parity rows", len(m), "net diff max", float((m.net - m.net_o).abs().max()), "exit-min mismatches",
          int((m.exit_min != m.exit_min_o).sum()))


def holdout(P):
    days = days_of(P, "ho")
    pre_df = pd.read_csv(os.path.join(HERE, "pre_variants.csv"))
    rows = []
    for arm in ARMS:
        base, nolock, alts = specs_for(arm)
        d = pre_df[(pre_df.arm == arm) & pre_df.alt]
        best = d.sort_values("net", ascending=False).iloc[0].variant
        for sp in [base, nolock] + [s for s in alts if s.name == best]:
            tr, pt = run(P, arm, sp, "app", "app", part="ho", pool=True)
            g = run(P, arm, sp, "gross", "app", part="ho")
            rl = run(P, arm, sp, "real", "app", part="ho")
            tk = run(P, arm, sp, "app", "tick", part="ho")
            rb = OF.random_baseline(tr, pt)
            rows.append(dict(arm=arm, variant=sp.name, **summ(tr, days), gross_headline=round(float(g.gross.sum())),
                             net_real=round(float(rl.net.sum())), net_tick=round(float(tk.net.sum())), rand_p=round(rb["p"], 4)))
    df = pd.DataFrame(rows)
    df.to_csv(os.path.join(HERE, "holdout.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 40)
    print("holdout sessions", len(days), days.min(), days.max())
    print(df.to_string())


if __name__ == "__main__":
    P = sim.load()
    {"pre": pre, "parity": parity, "holdout": holdout}[sys.argv[1]](P)
