"""R4 report tables -> scratchpad/hunt/r4/tables_*.md and research/hunt/r4/results/*.csv"""
import os, sys, shutil
sys.path.append('/root/.local/lib/python3.11/site-packages')
import pandas as pd, numpy as np
O = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r4"
R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
os.makedirs(R, exist_ok=True)
d = pd.read_csv(f"{O}/design_variants.csv")
h = pd.read_csv(f"{O}/holdout_picks.csv")
TOP = {"M": "Magic", "G": "Gann", "F": "Fibonacci", "H": "Harmonic"}
def topic(f):
    if f.startswith(("M1", "M2")): return "Gann SQ9 ('magic levels')"
    return TOP[f[0]]
d["topic"] = d.fam.map(topic)
m = d.merge(h[["und", "var", "trades", "rs_day", "win", "maxdd", "p_rand", "green_months"]], on=["und", "var"], how="left", suffixes=("", "_h"))
m["ok"] = (m.rs_day > 0) & (m.q_bh < 0.10) & (m.p_rand < 0.05) & (m.rc_p < 0.10) & (m.rs_day_h > 0) & (m.p_rand_h < 0.10)
m.to_csv(f"{R}/all_variants.csv", index=False)
for f in ("placebo_merged.csv", "placebo_xau.csv", "posthoc_time.csv", "hindsight.csv", "frozen.json"):
    shutil.copy(f"{O}/{f}", R)
def fmt(x, nd=0):
    return "" if pd.isna(x) else (f"{x:,.{nd}f}")
# picks table
pk = m[m.rs_day_h.notna()].sort_values(["topic", "fam", "und"])
L = ["| topic | index | variant | design trades | design Rs/day | win | worst DD | p twin | BH q | RC p | holdout trades | holdout Rs/day | holdout win | holdout DD | holdout p twin | green months | pass |", "|" + "---|" * 17]
for r in pk.itertuples():
    L.append(f"| {r.topic} | {r.und} | `{r.var.replace(chr(124), chr(47))}` | {r.trades} | {fmt(r.rs_day)} | {r.win:.0%} | {fmt(r.maxdd)} | {r.p_rand:.2f} | {r.q_bh:.2f} | {r.rc_p:.2f} | {int(r.trades_h)} | **{fmt(r.rs_day_h)}** | {r.win_h:.0%} | {fmt(r.maxdd_h)} | {r.p_rand_h:.2f} | {int(r.green_months)}/13 | {'PASS' if r.ok else 'FAIL'} |")
open(f"{O}/tables_picks.md", "w").write("\n".join(L))
# family summary
g = m.groupby(["topic", "fam"]).apply(lambda x: pd.Series(dict(variants=len(x), pos=int((x.rs_day > 0).sum()), trades=int(x.trades.sum()),
    gross=(x.gross_trade * x.trades).sum() / x.trades.sum(), net=(x.rs_trade * x.trades).sum() / x.trades.sum(),
    rand=(x.rand_trade * x.trades).sum() / x.trades.sum(), best=x.rs_day.max(), med=x.rs_day.median(), pr=x.p_rand.min(), q=x.q_bh_rand.min()))).reset_index()
L = ["| topic | family | variants (3 indices) | positive | trades | gross Rs/trade | net Rs/trade | random twin Rs/trade | best Rs/day | median Rs/day | lowest p twin | lowest BH q (twin) |", "|" + "---|" * 12]
for r in g.itertuples():
    L.append(f"| {r.topic} | {r.fam} | {int(r.variants)} | {int(r.pos)} | {int(r.trades):,} | {r.gross:+.0f} | {r.net:+.0f} | {r.rand:+.0f} | {r.best:+.0f} | {r.med:+.0f} | {r.pr:.3f} | {r.q:.2f} |")
tt = m.groupby("topic").apply(lambda x: pd.Series(dict(variants=len(x), pos=int((x.rs_day > 0).sum()), trades=int(x.trades.sum()),
    gross=(x.gross_trade * x.trades).sum() / x.trades.sum(), net=(x.rs_trade * x.trades).sum() / x.trades.sum(),
    rand=(x.rand_trade * x.trades).sum() / x.trades.sum()))).reset_index()
L.append("")
L.append("| topic | variants | positive design Rs/day | trades | gross Rs/trade | net Rs/trade | random twin Rs/trade | net minus twin |")
L.append("|" + "---|" * 8)
for r in tt.itertuples():
    L.append(f"| {r.topic} | {int(r.variants)} | {int(r.pos)} | {int(r.trades):,} | {r.gross:+.0f} | {r.net:+.0f} | {r.rand:+.0f} | {r.net - r.rand:+.0f} |")
a = m
L.append(f"| **all** | {len(a)} | {int((a.rs_day>0).sum())} | {int(a.trades.sum()):,} | {(a.gross_trade*a.trades).sum()/a.trades.sum():+.0f} | {(a.rs_trade*a.trades).sum()/a.trades.sum():+.0f} | {(a.rand_trade*a.trades).sum()/a.trades.sum():+.0f} | {((a.rs_trade-a.rand_trade)*a.trades).sum()/a.trades.sum():+.0f} |")
open(f"{O}/tables_fam.md", "w").write("\n".join(L))
# appendix: all variants
L = ["| index | variant | trades | design Rs/day | win | worst DD | p twin | BH q | holdout Rs/day | result |", "|" + "---|" * 10]
for r in m.sort_values(["fam", "var", "und"]).itertuples():
    L.append(f"| {r.und[:4]} | `{r.var.replace(chr(124), chr(47))}` | {r.trades} | {r.rs_day:+.0f} | {r.win:.0%} | {r.maxdd/1000:.0f}k | {r.p_rand:.2f} | {r.q_bh:.2f} | {fmt(r.rs_day_h)} | FAIL |")
open(f"{O}/tables_all.md", "w").write("\n".join(L))
print(len(m), int(m["ok"].sum()), "positive design", int((m.rs_day>0).sum()), "holdout pos", int((m.rs_day_h>0).sum()), "of", int(m.rs_day_h.notna().sum()))
print("min q", m.q_bh.min(), "min q twin", m.q_bh_rand.min(), "min rc", m.rc_p.min(), "n<30", int((m.trades<30).sum()))
print(open(f"{O}/tables_fam.md").read())
