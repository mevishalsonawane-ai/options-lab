"""R131 descriptive checks AFTER the holdout read (not pre-registered, not gates; labelled so in the write-up):
the GAP_T passes' robustness.

1. B0: buy the ATM call at 14:55 EVERY day and sell at 09:15 next day (does the model's day choice add anything?)
2. why trades were skipped (contract rolls overnight vs no traded print at entry / exit), and the index move on
   skipped vs traded calls (is skipping biased?)
3. exit 5 minutes later (the 09:19 bar's close) instead of the 09:15 bar's close
4. calls and hits by index and by year; holdout months
5. the largest LR weights of GAP_T IDX LR (refit on the design rows, standardised inputs)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2]))
import r131cfg as K  # noqa: E402
import r131feat as F  # noqa: E402
import r131model as M  # noqa: E402
import r131money as Y  # noqa: E402
import run as R  # noqa: E402

sys.path.insert(0, str(HERE.parent / "r123_neural_70"))
import r123data as RD  # noqa: E402

PASS = [("IDX+CHAIN", "LR", 0.1), ("IDX+CAL", "LR", 0.1), ("IDX+FUT", "LR", 0.1), ("IDX", "LR", 0.1),
        ("IDX+PART", "GBM", 0.5), ("ALL", "GBM", 0.5)]


def trades(sel: pd.DataFrame, rows: pd.DataFrame, near: dict, grids, lake, cache, exits=(0, 4)) -> pd.DataFrame:
    meta = rows.set_index(["und", "day", "row"])
    out = []
    for r in sel.sort_values(["und", "day"]).itertuples():
        u = r.und
        mr = meta.loc[(u, r.day, r.row)]
        d_in, d_out = pd.Timestamp(mr["dday"]), r.day
        rec = {"und": u, "day": r.day, "fold": r.fold, "move": r.move, "y": r.y, "why": "", "p": r.p}
        if u in near:
            e_in, e_out = near[u].get(d_in, pd.NaT), near[u].get(d_out, pd.NaT)
            roll = pd.isna(e_in) or e_in == d_in or e_in != e_out
        else:
            roll = bool(mr.get("rk_is_expiry", 0) == 1)
        if u not in grids:
            grids[u] = RD.load_grid(u, K.HOLDOUT[1])
            lake[u] = RD.LakeOptions(u, K.HOLDOUT[1])
        gr = grids[u]
        i = gr.pos().get(d_in.date())
        if roll or i is None or i + 1 >= len(gr.days):
            rec["why"] = "roll"
            out.append(rec)
            continue
        t = int(r.row)

        def day(d, j):
            if (u, d) not in cache:
                o = lake[u].day(d, closes=gr.c[j])
                cache[(u, d)] = None if o is None else {k: v.astype(np.float32) for k, v in o.contracts.items()}
            return cache[(u, d)]
        ci, co = day(d_in.date(), i), day(d_out.date(), i + 1)
        side = "C" if r.p >= 0.5 else "P"
        spot = gr.c[i, t] if np.isfinite(gr.c[i, t]) else mr["close"]
        kk = RD.atm(float(spot), K.STEP[u])
        a_in = ci.get((side, kk)) if ci else None
        a_out = co.get((side, kk)) if co else None
        if a_in is None or not np.isfinite(a_in[t, 3]):
            rec["why"] = "no entry print"
            out.append(rec)
            continue
        for e in exits:
            x = Y.opt_overnight(a_in, t, a_out, e, K.LOT[u], d_in.date(), d_out.date(), u, RD.FILLS, RD.charge)
            rec[f"net_{e}"] = np.nan if x is None else x["net"]
        if not np.isfinite(rec.get("net_0", np.nan)):
            rec["why"] = "no exit print"
        out.append(rec)
    return pd.DataFrame(out)


def summ(x: pd.DataFrame, col="net_0") -> dict:
    t = x[x[col].notna()] if col in x else x.iloc[:0]
    return {"calls": int(len(x)), "trades": int(len(t)), "win": float((t[col] > 0).mean()) if len(t) else None,
            "mean": float(t[col].mean()) if len(t) else None, "total": float(t[col].sum()) if len(t) else None}


def main():
    h = "GAP_T"
    rows = R.load_rows(h)
    wf = pd.read_parquet(K.OUT / "preds_wf.parquet")
    ho = pd.read_parquet(K.OUT / "preds_holdout.parquet")
    idd = R.S.index_daily()
    exb = R.expiries_from_bhav()
    near = {}
    for u in K.NSE_UNDS:
        cal = pd.DatetimeIndex(sorted(idd[idd.symbol == u].date))
        ei = pd.DatetimeIndex(sorted({cal[cal <= e].max() for e in exb[u] if (cal <= e).any()}))
        near[u] = {d: (ei[ei >= d].min() if (ei >= d).any() else pd.NaT) for d in cal[cal >= pd.Timestamp("2021-01-01")]}
    grids, lake, cache = {}, {}, {}
    out = {}
    for part, P in (("wf", wf), ("holdout", ho)):
        g0 = P[(P.H == h) & (P.set == "BASE") & (P.model == "B0")]
        tb = trades(g0, rows, near, grids, lake, cache)
        out[f"B0_every_day_call|{part}"] = {"exit_0915": summ(tb), "exit_0919": summ(tb, "net_4"),
                                            "skips": tb.why.value_counts().to_dict(),
                                            "by_und": {u: summ(x) for u, x in tb.groupby("und")}}
        print(part, "B0", out[f"B0_every_day_call|{part}"]["exit_0915"], flush=True)
        for s, m, k in PASS:
            g = P[(P.H == h) & (P.set == s) & (P.model == m)]
            g = g[np.abs(g.p - 0.5) >= g[f"cut_{int(k * 100)}"]]
            tr = trades(g, rows, near, grids, lake, cache)
            hit = (tr.y == (tr.p >= 0.5).astype(int))
            key = f"{s}|{m}|{k}|{part}"
            out[key] = {
                "exit_0915": summ(tr), "exit_0919": summ(tr, "net_4"),
                "skips": tr.why.value_counts().to_dict(),
                "hit_traded": float(hit[tr.why == ""].mean()) if (tr.why == "").any() else None,
                "hit_skipped": float(hit[tr.why != ""].mean()) if (tr.why != "").any() else None,
                "move_traded": float(tr.move[tr.why == ""].mean()) if (tr.why == "").any() else None,
                "move_skipped": float(tr.move[tr.why != ""].mean()) if (tr.why != "").any() else None,
                "by_und": {u: {"calls": int(len(x)), "hit": float((x.y == (x.p >= 0.5)).mean()), **summ(x)}
                           for u, x in tr.groupby("und")},
                "by_year": {str(y): {"calls": int(len(x)), "hit": float((x.y == (x.p >= 0.5)).mean()), **summ(x)}
                            for y, x in tr.groupby(pd.to_datetime(tr.day).dt.year)},
                "mean_move_pct": float(tr.move.mean()), "median_abs_move_pct": float(tr.move.abs().median()),
            }
            if part == "holdout":
                out[key]["by_month"] = {str(mm): {"calls": int(len(x)), "hit": float((x.y == (x.p >= 0.5)).mean())}
                                        for mm, x in tr.groupby(pd.to_datetime(tr.day).dt.to_period("M"))}
            print(part, s, m, k, out[key]["exit_0915"], out[key]["skips"], flush=True)
    # 5. LR weights (design rows, standardised)
    tr = rows[rows.day <= pd.Timestamp(K.DESIGN_END)]
    cols = F.feature_cols(rows, "IDX")
    X = tr[cols].to_numpy(np.float64)
    keep = np.isfinite(X).any(0)
    pr = M.Prep().fit(X[:, keep])
    from sklearn.linear_model import LogisticRegression
    lr = LogisticRegression(C=K.LR_C, max_iter=2000).fit(pr.transform(X[:, keep]), tr.y.values)
    w = pd.Series(lr.coef_[0], index=np.array(cols)[keep]).sort_values(key=np.abs, ascending=False)
    out["lr_weights_IDX"] = {k: float(v) for k, v in w.head(10).items()}
    print(out["lr_weights_IDX"])
    (K.RESULTS / "extra_gap_t.json").write_text(json.dumps(out, indent=1, default=float))


if __name__ == "__main__":
    main()
