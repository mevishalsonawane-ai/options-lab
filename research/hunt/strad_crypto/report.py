"""Tables for research/STRAD_CRYPTO.md from the outputs of models.py, sim.py, rules.py.
Usage: python3 -I report.py SCRATCH > SCRATCH/logs/report_tables.md"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import json
import numpy as np, pandas as pd
from scipy.stats import spearmanr

SCR = sys.argv[1]
HOLD = pd.Timestamp("2026-04-01", tz="UTC")
E0 = pd.Timestamp("2022-01-01", tz="UTC")


def md(df, fl="%.2f"):
    cols = list(df.columns)
    out = ["| " + " | ".join(map(str, cols)) + " |", "|" + "---|" * len(cols)]
    for _, r in df.iterrows():
        out.append("| " + " | ".join((fl % v) if isinstance(v, (float, np.floating)) and not np.isnan(v) else ("" if isinstance(v, float) else str(v)) for v in r.values) + " |")
    return "\n".join(out)


print("## Model skill (out-of-sample 2022-01..2026-03)\n")
rows = []
for cur in ["BTC", "ETH"]:
    f = pd.read_parquet(f"{SCR}/data/{cur}_feat.parquet"); fc = pd.read_parquet(f"{SCR}/data/{cur}_fc.parquet")
    f = f.loc[fc.index]
    m = (fc.index >= E0) & (fc.index < HOLD)
    for h in (1, 4, 24):
        for mdl, nm in [("fh", "HAR"), ("fn", "NN")]:
            x = fc[f"{mdl}{h}"][m]; y = f[f"rv{h}f"][m]; z = f[f"mv{h}f"][m]
            ok = x.notna() & y.notna() & z.notna()
            rows.append(dict(asset=cur, h=h, model=nm, rank_corr_RV=spearmanr(x[ok], y[ok])[0],
                             rank_corr_abs_move=spearmanr(x[ok], z[ok])[0]))
print(md(pd.DataFrame(rows), "%.3f"))

print("\n## Implied vs realised\n")
g = pd.read_parquet(f"{SCR}/data/gap.parquet")
g = g[g.ok & g.rv.notna()]
g["per"] = np.where(g.H >= HOLD, "holdout", "pre")
H = {"1": 1, "4": 4, "24": 24, "X": 24}
g["h"] = g.hz.map(H)
g["rv_ann"] = g.rv * np.sqrt(8760 / g.h)
x = g[g.hz != "X"]
t = x.groupby(["cur", "hz", "per"]).agg(n=("rv", "size"), IV_med=("iv0", "median"), RV_ann_med=("rv_ann", "median"),
                                         RV_over_implied_mean=("rv", "sum")).reset_index()
t["RV_over_implied_mean"] = x.groupby(["cur", "hz", "per"]).apply(lambda d: d.rv.sum() / d.imp.sum()).values
t["share_hours_RV_gt_implied"] = x.groupby(["cur", "hz", "per"]).apply(lambda d: (d.rv > d.imp).mean()).values
t["IV_med"] *= 100; t["RV_ann_med"] *= 100
print(md(t))
print("\nBy year (h = 24 h):\n")
y = x[x.hz == "24"].copy(); y["year"] = y.H.dt.year
t = y.groupby(["cur", "year"]).apply(lambda d: pd.Series(dict(IV_med=d.iv0.median() * 100, RV_ann_med=d.rv_ann.median() * 100,
                                                                  RV_over_implied=d.rv.sum() / d.imp.sum()))).reset_index()
print(md(t))
print("\nBy hour of day (UTC; h = 1 h, pre-holdout): realised / implied\n")
y = x[(x.hz == "1") & (x.per == "pre")]
t = y.groupby(["hod", "cur"]).apply(lambda d: d.rv.sum() / d.imp.sum()).unstack()
print(md(t.reset_index()))
print("\nHold to expiry (26-50 h straddle): payoff at expiry / mid price paid\n")
z = g[g.hz == "X"].copy(); z["year"] = z.H.dt.year
t = z.groupby(["cur", "per"]).apply(lambda d: pd.Series(dict(n=len(d), payoff_over_price=d.end_mid.sum() / d.mid0.sum(),
                                                              share_payoff_gt_price=(d.end_mid > d.mid0).mean()))).reset_index()
print(md(t, "%.3f"))
t = z.groupby(["cur", "year"]).apply(lambda d: d.end_mid.sum() / d.mid0.sum()).unstack()
print("\n" + md(t.reset_index(), "%.3f"))
print("\nPricing source at entry (share priced from Deribit trade IV vs DVOL fallback):\n")
t = g.groupby(["cur", "per"]).apply(lambda d: pd.Series(dict(trade_iv=1 - d.src.mean(), dvol_fallback=d.src.mean()))).reset_index()
print(md(t, "%.4f"))

R = pd.read_csv(f"{SCR}/logs/rules_all.csv")
cols = ["rule", "trades", "hit", "gross_pct", "net_pct", "net_pct_deribit", "net_pct_delta2x", "fixed5k_gross_rs", "fixed5k_net_rs", "fixed5k_taxA_rs", "gross_rs", "net_rs", "taxA_rs", "taxB_rs",
        "rs_day_net", "rs_day_A", "rs_day_B", "maxdd_rs", "maxdd_A", "green_months", "green_months_A", "ruin", "rand_mean_pct", "p_rand", "q_bh",
        "randhod_mean_pct", "p_rand_hod", "p_pos"]
pre = R[R.period == "pre"].copy()
print("\n## Pre-holdout (2022-01..2026-03): top 20 of 280 by mean net % per trade\n")
print(md(pre.sort_values("net_pct", ascending=False).head(20)[cols]))
print("\n## Baselines and each signal set (pre-holdout), time exit\n")
for hz in ["1", "4", "24", "X"]:
    print(f"\n### horizon {hz}\n")
    print(md(pre[(pre.hz.astype(str) == hz) & (pre.ex == "time")][cols]))
print("\n## Best exit per signal set (pre-holdout)\n")
b = pre.sort_values("net_pct", ascending=False).groupby("sig").head(1)
print(md(b[cols]))
print("\n## Counts\n")
print("variants with net_pct > 0:", int((pre.net_pct > 0).sum()), "of", len(pre))
print("variants with gross_pct > 0:", int((pre.gross_pct > 0).sum()))
print("variants with p_rand < 0.05:", int((pre.p_rand < 0.05).sum()), "; q_bh < 0.10:", int((pre.q_bh < 0.10).sum()))
print("\n## Walk-forward\n")
W = pd.read_csv(f"{SCR}/logs/walkforward.csv")
print(md(W))
F = json.load(open(f"{SCR}/logs/final.json"))
print("\n## Final rule and holdout (partly seen)\n")
print("final:", F["final"])
print(json.dumps(F, indent=1, default=str))
hold = R[R.period == "hold"].copy()
print("\n## Holdout, descriptive, all signal sets with time exit\n")
for hz in ["1", "4", "24", "X"]:
    print(f"\n### horizon {hz}\n")
    print(md(hold[(hold.hz.astype(str) == hz) & (hold.ex == "time")][cols]))
print("\nholdout variants with net_pct > 0:", int((hold.net_pct > 0).sum()), "of", len(hold))
