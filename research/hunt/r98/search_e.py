"""R98e Part B (PREREG_E sections 3-4): the fixed rule grid, judged by SPA / RC, PBO and an anchored walk-forward.

    python3 -I research/hunt/r98/search_e.py     # -> results/search_e.md, results/search_e.json
"""
from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1]))                     # research/ (for obuy.overfit)
import r98data as R  # noqa: E402
from features_e import NEW  # noqa: E402
from hero2 import md_table, pct, rs  # noqa: E402
from obuy.overfit import pbo, spa  # noqa: E402

RES = HERE / "results"
KEYS = ["und", "day", "t", "strike", "side"]
OLD = ["f_dist_em", "f_r15", "f_r30", "f_rday", "f_pos", "f_brk15", "f_orb", "f_stx", "f_vix", "f_volb", "f_oi15", "f_iv15"]
FEATS = [f for f in NEW if f not in ("us_event", "dom_event")] + ["us_event", "dom_event"] + OLD
QS = (("ge", 0.7), ("ge", 0.8), ("ge", 0.9), ("le", 0.1), ("le", 0.2), ("le", 0.3))
WINDOWS = (("11:00-12:55", R.minute(11, 0), R.minute(12, 55)), ("13:00-14:55", R.minute(13, 0), R.minute(14, 55)))
BUCKETS = (("Rs 1-3", 1.0, 3.0), ("Rs 3-6", 3.0, 6.0), ("Rs 6-10", 6.0, 10.0001))
EXITS = (("hold", "x_inf_0"), ("-20% stop", "x_inf_0.8"), ("-50% stop", "x_inf_0.5"), ("10x target, -20% stop", "x_10_0.8"))
DESIGN_TO = 2023


def load():
    cols = KEYS + ["f_px", "f_dist_em", "y10"] + [c for _, c in EXITS] + [f for f in OLD if f != "f_dist_em"]
    rows = pd.read_parquet(R.OUT / "rows.parquet", columns=cols)
    fe = pd.read_parquet(R.OUT / "feat_e.parquet")
    df = rows.merge(fe, on=KEYS, how="left", validate="one_to_one")
    df["yr"] = pd.to_datetime(df.day).dt.year
    df = df.sort_values(["und", "day", "t", "f_dist_em"]).reset_index(drop=True)
    df["g"] = (df.und + df.day.astype(str)).astype("category").cat.codes
    return df


def conditions(df):
    """(name, boolean mask) for the control, every single and (later) pairs. Percentiles from DESIGN rows only."""
    des = df[df.yr <= DESIGN_TO]
    out = [("none", np.ones(len(df), bool))]
    for f in FEATS:
        x = df[f].values
        if f in ("us_event", "dom_event", "f_brk15", "f_orb"):
            out.append((f"{f} = 1", x == 1))
            out.append((f"{f} = 0", x == 0))
            continue
        for op, q in QS:
            thr = float(des[f].quantile(q))
            m = (x >= thr) if op == "ge" else (x <= thr)
            out.append((f"{f} {'>=' if op == 'ge' else '<='} p{int(q * 100)} ({thr:.3g})", m & ~np.isnan(x)))
    return out


def evaluate(df, masks, date_code, n_dates):
    """Every (condition, window, bucket, exit) -> (meta, per-ticket rows, daily P&L vector)."""
    t, px, g = df.t.values, df.f_px.values, df.g.values
    wb = {(wn, bn): (t >= a) & (t <= b) & (px >= lo) & (px < hi) for wn, a, b in WINDOWS for bn, lo, hi in BUCKETS}
    ex = {en: df[c].values for en, c in EXITS}
    meta, cols, idxs = [], [], []
    for cname, m in masks:
        for (wn, bn), w in wb.items():
            mm = m & w
            if not mm.any():
                continue
            pos = np.nonzero(mm)[0]
            _, first = np.unique(g[pos], return_index=True)       # rows are sorted: the first row of each index-day
            sel = pos[first]
            for en, v in ex.items():
                daily = np.bincount(date_code[sel], weights=v[sel], minlength=n_dates)
                meta.append(dict(cond=cname, window=wn, price=bn, exit=en, tickets=len(sel)))
                cols.append(daily)
                idxs.append((sel, en))
    return meta, np.stack(cols, axis=1), idxs


def main():
    df = load()
    dates = np.array(sorted(df.day.unique()))
    dpos = {d: i for i, d in enumerate(dates)}
    date_code = df.day.map(dpos).values
    years = pd.to_datetime(pd.Series(dates)).dt.year.values
    singles = conditions(df)
    meta, X, idxs = evaluate(df, singles, date_code, len(dates))
    M = pd.DataFrame(meta)
    des_rows = years <= DESIGN_TO
    M["design_mean_day"] = X[des_rows].mean(axis=0)
    # pairs: the 20 single conditions (not the control) with the best design mean, distinct features
    best_single = M[M.cond != "none"].groupby("cond").design_mean_day.max().sort_values(ascending=False)
    top, used = [], set()
    for c in best_single.index:
        f = c.split(" ")[0]
        top.append(c)
        if len(top) == 20:
            break
    mask_of = dict(singles)
    pairs = [(f"{a} AND {b}", mask_of[a] & mask_of[b]) for a, b in itertools.combinations(top, 2)
             if a.split(" ")[0] != b.split(" ")[0]]
    meta2, X2, idxs2 = evaluate(df, pairs, date_code, len(dates))
    M = pd.concat([M.drop(columns="design_mean_day"), pd.DataFrame(meta2)], ignore_index=True)
    X = np.concatenate([X, X2], axis=1)
    idxs = idxs + idxs2
    T, N = X.shape
    print("variants", N, "days", T, flush=True)
    # ---- the tests
    sp = spa(X, B=1000, mean_block=5.0, seed=98)
    pb = pbo(X, S=16, metric="mean", max_combos=3000, seed=98)
    # anchored walk-forward on yearly totals
    yrs = sorted(set(years))
    tot = {y: X[years == y].sum(axis=0) for y in yrs}
    wf = []
    for y in [y for y in yrs if y >= 2023]:
        past = sum(tot[z] for z in yrs if z < y)
        k = int(np.argmax(past))
        wf.append(dict(year=int(y), picked=f"{M.cond[k]} | {M.window[k]} | {M.price[k]} | {M.exit[k]}", oos=float(tot[y][k])))
    wf_total = sum(w["oos"] for w in wf)
    wf_pos = sum(w["oos"] > 0 for w in wf)
    # the SPA-best variant
    k = sp["best"]
    sel, en = idxs[k]
    v = df[dict(EXITS)[en]].values[sel]
    tk = df.iloc[sel].assign(pnl=v)
    by = tk.groupby(["und", "day"]).pnl.sum().sort_values(ascending=False)
    wo5 = float((v.sum() - by.head(5).sum()) / len(v))
    found = sp["spa_p"] < 0.05 and pb["pbo"] < 0.25 and wf_total > 0 and wf_pos >= 3 and wo5 > 0
    S = dict(variants=N, days=T, spa=sp, pbo=pb, walk_forward=wf, wf_total=wf_total, wf_years_pos=wf_pos,
             best=dict(**M.iloc[k].to_dict(), mean_ticket=float(v.mean()), wo5=wo5, hit10=float(tk.y10.mean()),
                       by_year={int(y): float(g.pnl.sum()) for y, g in tk.groupby("yr")}), found=bool(found))
    # top 15 by t-stat, with design / holdout halves
    mu = X.mean(axis=0)
    sd = X.std(axis=0, ddof=1)
    tstat = np.where(sd > 0, mu / sd * np.sqrt(T), 0)
    order = np.argsort(-tstat)[:15]
    tops = []
    for j in order:
        sel, en = idxs[j]
        vv = df[dict(EXITS)[en]].values[sel]
        d_ = df.iloc[sel]
        des = vv[d_.yr.values <= DESIGN_TO]
        hol = vv[d_.yr.values > DESIGN_TO]
        tops.append({"rule": f"{M.cond[j]} | {M.window[j]} | {M.price[j]} | {M.exit[j]}", "tickets": len(vv),
                     "t": f"{tstat[j]:.2f}", "mean Rs": rs(vv.mean()), "2021-23 mean": rs(des.mean()) if len(des) else "-",
                     "2024-26 mean": rs(hol.mean()) if len(hol) else "-", "hit 10x": pct(d_.y10.mean(), 1)})
    S["top15"] = tops
    md = ["# R98e Part B: the rule search, judged for data-snooping (generated by search_e.py)\n",
          f"- Variants searched: **{N:,}** (single conditions and pairs × 2 windows × 3 price buckets × 4 exits), "
          f"one ticket per index-day, over **{T}** expiry dates.",
          f"- **Hansen SPA p = {sp['spa_p']:.3f}**, White RC p = {sp['rc_p']:.3f} (the best studentised t = {sp['t_best']:.2f}).",
          f"- **PBO = {pb['pbo']:.2f}** over {pb['n_combos']} CSCV splits.",
          f"- Anchored walk-forward 2023-26: total Rs {wf_total:,.0f}, years > 0: {wf_pos} of {len(wf)}.",
          f"- SPA-best variant: {M.cond[k]} | {M.window[k]} | {M.price[k]} | {M.exit[k]}: {len(v)} tickets, mean Rs "
          f"{v.mean():,.0f}, without the 5 best days Rs {wo5:,.0f}, hit 10× {pct(tk.y10.mean(), 1)}.",
          f"- **Found (all four tests): {'YES' if found else 'no'}.**", "",
          "## Walk-forward picks\n", md_table(pd.DataFrame(wf).assign(oos=lambda x: x.oos.map(rs))), "",
          "## The 15 variants with the highest t-statistic (before any correction)\n", md_table(pd.DataFrame(tops)), ""]
    RES.mkdir(exist_ok=True)
    (RES / "search_e.md").write_text("\n".join(md) + "\n")
    (RES / "search_e.json").write_text(json.dumps(S, indent=1, default=str))
    print("\n".join(md))


if __name__ == "__main__":
    main()
