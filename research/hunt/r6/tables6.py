"""R6 report tables -> scratchpad/hunt/r6/tables.md, small CSVs -> research/hunt/r6/results/. python3 -I tables6.py"""
from __future__ import annotations

import glob
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib6 as L6  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

O = str(L6.OUT)
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
os.makedirs(RES, exist_ok=True)
out = []


def md(df, floatfmt=None):
    cols = list(df.columns)
    s = "| " + " | ".join(map(str, cols)) + " |\n|" + "---|" * len(cols) + "\n"
    for r in df.itertuples(index=False):
        s += "| " + " | ".join("" if (isinstance(v, float) and np.isnan(v)) else str(v) for v in r) + " |\n"
    return s


def pct(x):
    return f"{100 * x:+.1f}" if np.isfinite(x) else ""


def placebo():
    D = pd.read_csv(os.path.join(O, "placebo_design.csv"))
    H = pd.read_csv(os.path.join(O, "placebo_holdout.csv"))
    X = pd.read_csv(os.path.join(O, "placebo_proxy.csv")) if os.path.exists(os.path.join(O, "placebo_proxy.csv")) else None
    Dt = pd.read_csv(os.path.join(O, "placebo_design_tick.csv")) if os.path.exists(os.path.join(O, "placebo_design_tick.csv")) else None
    Ht = pd.read_csv(os.path.join(O, "placebo_holdout_tick.csv")) if os.path.exists(os.path.join(O, "placebo_holdout_tick.csv")) else None
    key = ["und", "test", "fam", "d"]
    for df in (D, H, X, Dt, Ht):
        if df is not None:
            df["d"] = df["d"].fillna(-1)
    m = D[key + ["claimed", "placebo", "n_claimed", "diff", "p", "q_bh"]].merge(
        H[key + ["claimed", "placebo", "n_claimed", "diff", "p", "q_bh"]], on=key, suffixes=("_des", "_hold"))
    prox = {"GOLDM": "PROXY_XAUUSD", "GOLD": "PROXY_XAUUSD", "SILVERM": "PROXY_XAGUSD", "SILVER": "PROXY_XAGUSD",
            "CRUDEOIL": "PROXY_WTIUSD"}
    if X is not None:
        xd = X[X.period == "design"][key + ["diff", "p", "q_bh", "n_claimed"]].rename(
            columns={"und": "pund", "diff": "diff_px", "p": "p_px", "q_bh": "q_px", "n_claimed": "n_px"})
        m["pund"] = m.und.map(prox)
        m = m.merge(xd, on=["pund", "test", "fam", "d"], how="left")
    if Dt is not None and Ht is not None:
        t = Dt[key + ["diff", "p", "q_bh"]].merge(Ht[key + ["diff", "p", "q_bh"]], on=key, suffixes=("_dtick", "_htick"))
        m = m.merge(t, on=key, how="left")
    m["matters"] = ((m.q_bh_des < 0.05) | (m.get("q_px", pd.Series(np.nan, index=m.index)) < 0.05)) & \
        (m.p_hold < 0.05) & (m.diff_hold > 0) & ((m.diff_des > 0) | (m.get("diff_px", 0) > 0))
    if "q_bh_dtick" in m:
        m["matters_tick"] = ((m.q_bh_dtick < 0.05) | (m.get("q_px", pd.Series(np.nan, index=m.index)) < 0.05)) & \
            (m.p_htick < 0.05) & (m.diff_htick > 0) & ((m.diff_dtick > 0) | (m.get("diff_px", 0) > 0))
    m.to_csv(os.path.join(RES, "placebo_merged.csv"), index=False)
    out.append("## Placebo: confirmed families (rule: design or proxy-design BH q<0.05 AND MCX holdout p<0.05, same sign)\n")
    out.append(f"pre-registered version: {int(m.matters.sum())} of {len(m)}; tick-fair (POST-HOC): "
               f"{int(m.get('matters_tick', pd.Series(False)).sum())}\n")
    out.append(md(m[m.matters | m.get("matters_tick", False)][key + ["diff_des", "p_des", "q_bh_des", "diff_hold", "p_hold",
                                                                     "q_bh_hold"] + [c for c in ("diff_px", "q_px", "diff_dtick", "q_bh_dtick", "diff_htick", "p_htick") if c in m]].round(4)))
    out.append(f"MCX holdout BH q<0.05 (info): {int((m.q_bh_hold < 0.05).sum())} rows\n")
    out.append(md(m[m.q_bh_hold < 0.05][key + ["diff_des", "p_des", "diff_hold", "p_hold", "q_bh_hold"] +
                    [c for c in ("diff_htick", "p_htick") if c in m]].round(4)))
    # main table: reversal d=0.10% per commodity x family
    for d in (0.001, 0.0025):
        r = m[(m.test == "reversal") & (m.d == d)]
        rows = []
        for fam in ["SQ9_125", "SQ9_25", "RND_B", "RND_A", "RND_MAJ", "NUM9", "GANNPTS", "FIBPIV", "PDRFIB", "GQTR", "SWFIB"]:
            row = {"family": fam}
            for u in ["CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM", "GOLD", "SILVER"]:
                x = r[(r.fam == fam) & (r.und == u)]
                if len(x):
                    x = x.iloc[0]
                    tk = f" / {pct(x.diff_htick)}" if "diff_htick" in x and np.isfinite(x.diff_htick) else ""
                    row[u] = f"{pct(x.diff_des)} / {pct(x.diff_hold)}{tk} (n {int(x.n_claimed_hold)})"
            rows.append(row)
        out.append(f"\n### Reversal rate, claimed minus placebo, points; d = {d * 100:.2f}%: design / holdout / holdout tick-fair (holdout touches)\n")
        out.append(md(pd.DataFrame(rows)))
    for test in ("touch", "time"):
        r = m[m.test == test]
        out.append(f"\n### {test}: claimed vs placebo (design diff, holdout claimed / placebo, p)\n")
        out.append(md(r[["und", "fam", "diff_des", "p_des", "claimed_hold", "placebo_hold", "diff_hold", "p_hold", "n_claimed_hold"]]
                      .round(3)))
    if X is not None:
        out.append("\n### PROXY placebo (USD levels, MCX hours IST)\n")
        xr = X[X.test == "reversal"].copy()
        out.append(md(xr[["und", "period", "fam", "d", "claimed", "placebo", "n_claimed", "diff", "p", "q_bh"]].round(4)))
        out.append(md(X[X.test != "reversal"][["und", "period", "test", "fam", "claimed", "placebo", "n_claimed", "diff", "p", "q_bh"]].round(4)))


def trading():
    Dv = pd.read_csv(os.path.join(O, "design_variants.csv"))
    Hv = pd.read_csv(os.path.join(O, "holdout_variants.csv"))
    Dv.to_csv(os.path.join(RES, "design_variants.csv"), index=False)
    Hv.to_csv(os.path.join(RES, "holdout_variants.csv"), index=False)
    shutil.copy(os.path.join(O, "frozen.json"), os.path.join(RES, "frozen.json"))
    m = Dv.merge(Hv, on=["book", "var"], suffixes=("_d", "_h"), how="outer")
    m["passA"] = (m.rs_day_d > 0) & (m.q_bh_d < 0.10) & (m.p_rand_d < 0.05) & (m.rc_p_d < 0.10) & (m.rs_day_h > 0) & \
        (m.p_rand_h < 0.10)
    m["passB"] = (m.rs_day_h > 0) & (m.q_bh_h < 0.05) & (m.p_rand_h < 0.05) & (m.rc_p_h < 0.10) & (m.rs_day_d > 0)
    m.to_csv(os.path.join(RES, "all_variants.csv"), index=False)
    out.append(f"\n## Trading: variants design {len(Dv)}, holdout {len(Hv)}; PASS-A {int(m.passA.sum())}, PASS-B {int(m.passB.sum())}\n")
    out.append(f"design: positive {int((Dv.rs_day > 0).sum())}, min BH q {Dv.q_bh.min():.3f}, min q_rand {Dv.q_bh_rand.min():.3f}, min RC p {Dv.rc_p.min():.3f}\n")
    out.append(f"holdout(all): positive {int((Hv.rs_day > 0).sum())}, min BH q {Hv.q_bh.min():.3f}, min q_rand {Hv.q_bh_rand.min():.3f}, min RC p {Hv.rc_p.min():.3f}\n")
    # per book summary
    rows = []
    for b, g in m.groupby("book"):
        pk = g[g.is_pick == True]  # noqa: E712
        rows.append(dict(book=b, variants=len(g), des_pos=int((g.rs_day_d > 0).sum()),
                         des_best=f"{g.rs_day_d.max():+.0f}", hold_pos=int((g.rs_day_h > 0).sum()),
                         hold_median=f"{g.rs_day_h.median():+.0f}", hold_best=f"{g.rs_day_h.max():+.0f}",
                         picks=len(pk), picks_pos=int((pk.rs_day_h > 0).sum()),
                         picks_median=f"{pk.rs_day_h.median():+.0f}",
                         passA=int(g.passA.sum()), passB=int(g.passB.sum())))
    out.append(md(pd.DataFrame(rows)))
    # per family per book: picks
    pk = m[m.is_pick == True].copy()  # noqa: E712
    pk["fam_"] = pk["var"].str.split("|").str[0]
    out.append("\n### Frozen picks in the holdout (Rs/day over ~250 sessions, 1 lot, after costs)\n")
    out.append(md(pk[["book", "var", "trades_d", "rs_day_d", "t_d", "trades_h", "rs_day_h", "rs_trade_h", "win_h",
                      "rand_trade_h", "p_rand_h", "maxdd_h", "green_months_h"]].round(2)))
    # best holdout variants overall (info)
    out.append("\n### Top 15 holdout variants by Rs/day (all variants, info; BH across all holdout variants)\n")
    out.append(md(m.sort_values("rs_day_h", ascending=False).head(15)[
        ["book", "var", "trades_d", "rs_day_d", "trades_h", "rs_day_h", "rs_trade_h", "t_h", "q_bh_h", "p_rand_h",
         "q_bh_rand_h", "rc_p_h", "green_months_h"]].round(3)))
    # method-family level per book (holdout): median Rs/day over variants of the topic
    m["topic"] = m["var"].str[:1].map({"M": "1 magic", "G": "2 Gann", "F": "3 Fib", "H": "4 harmonics"})
    m.loc[m["var"].str.startswith("M9_ORFIB"), "topic"] = "1 magic"
    t = m.groupby(["book", "topic"]).agg(n=("var", "size"), hold_pos=("rs_day_h", lambda x: int((x > 0).sum())),
                                         hold_median=("rs_day_h", "median"), hold_best=("rs_day_h", "max"),
                                         des_median=("rs_day_d", "median")).round(0).reset_index()
    out.append("\n### By topic and book: Rs/day across variants (holdout ~250 sessions; design ~37)\n")
    out.append(md(t))
    # pooled trades: gross, net, random twins (holdout)
    rows = []
    for f in sorted(glob.glob(os.path.join(O, "holdout_trades_*.parquet"))):
        b = os.path.basename(f)[15:-8].replace("_", " ")
        tr = pd.read_parquet(f)
        rd = pd.read_parquet(f.replace("holdout_trades_", "holdout_rand_"))
        rows.append(dict(book=b, trades=len(tr), gross_trade=round(tr.gross.mean(), 1), net_trade=round(tr.net.mean(), 1),
                         cost_trade=round((tr.gross - tr.net).mean(), 1), rand_gross=round(rd.gross.mean(), 1),
                         rand_net=round(rd.net.mean(), 1), win=round((tr.net > 0).mean(), 3)))
    out.append("\n### Pooled holdout trades (all variants): Rs per trade, 1 lot\n")
    out.append(md(pd.DataFrame(rows)))


if __name__ == "__main__":
    try:
        placebo()
    except Exception as e:  # noqa: BLE001
        out.append(f"placebo tables failed: {e}\n")
    try:
        trading()
    except Exception as e:  # noqa: BLE001
        out.append(f"trading tables failed: {e}\n")
    for f in ("placebo_design.csv", "placebo_holdout.csv", "placebo_proxy.csv", "placebo_design_tick.csv",
              "placebo_holdout_tick.csv"):
        if os.path.exists(os.path.join(O, f)):
            shutil.copy(os.path.join(O, f), os.path.join(RES, f))
    open(os.path.join(O, "tables.md"), "w").write("\n".join(out))
    print("\n".join(out))
