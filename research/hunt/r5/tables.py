"""R5 tables for HUNT_R5_GUIDE_PINE.md (markdown to stdout). python3 -I tables.py"""
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r5/"


def f(x, d=0):
    return "-" if pd.isna(x) else f"{x:,.{d}f}"


def main():
    for per in ("design", "holdout"):
        A = pd.read_csv(os.path.join(R, f"{per}_A.csv"))
        try:
            B = pd.read_csv(os.path.join(R, f"{per}_B.csv"))
        except pd.errors.EmptyDataError:
            B = pd.DataFrame(columns=["und", "var"])
        if len(B):
            sp = B["var"].str.split("|")
            B["cfg"], B["tf"], B["setting"], B["ex"] = sp.str[0], sp.str[1].str[2:].astype(int), sp.str[2], sp.str[3]
        print(f"\n### {per.upper()}: primary setting (pivot 10, minSwing 1.0, cooldown 15)\n")
        print("| index | TF | config | (A) trades | (A) win% | (A) pts/trade net | (A) worst DD pts | (A) instant stop-outs | "
              "(B) NAT trades | NAT win% | NAT Rs/trade | NAT Rs/day | NAT worst DD Rs | NAT p_rand | NAT BH q | "
              "(B) OPT trades | OPT Rs/trade | OPT Rs/day | OPT p_rand |")
        print("|" + "---|" * 19)
        for r in A[A.primary].drop_duplicates(["und", "tf", "cfg"]).itertuples():
            row = [r.und, f"{r.tf}m", r.cfg, f(r.A_trades), f(100 * r.A_win) if r.A_trades else "-",
                   f(r.A_net_pts, 1), f(r.A_maxdd_pts, 0), f(100 * r.A_imm) + "%" if r.A_trades else "-"]
            for ex in ("NAT", "OPT"):
                g = B[(B.und == r.und) & (B.tf == r.tf) & (B.cfg == r.cfg) & (B.setting == "p10m1.0c15") & (B.ex == ex)] \
                    if len(B) else B
                if len(g):
                    g = g.iloc[0]
                    if ex == "NAT":
                        row += [f(g.trades), f(100 * g.win), f(g.rs_trade), f(g.rs_day, 1), f(g.maxdd), f(g.p_rand, 2),
                                f(g.get("q_bh", np.nan), 2)]
                    else:
                        row += [f(g.trades), f(g.rs_trade), f(g.rs_day, 1), f(g.p_rand, 2)]
                else:
                    row += ["0", "-", "-", "0", "-", "-", "-"] if ex == "NAT" else ["0", "-", "0", "-"]
            print("| " + " | ".join(row) + " |")
        print(f"\n### {per.upper()}: all grid settings per index x TF x config (8-9 settings each)\n")
        print("| index | TF | config | (A) trades, sum over settings | (A) pts/trade (pooled) | (B) NAT trades (sum) | "
              "NAT Rs/day min..max | (B) OPT trades (sum) | OPT Rs/day min..max | best NAT t | min BH q |")
        print("|" + "---|" * 11)
        for (u, tf, cfg), g in A.groupby(["und", "tf", "cfg"], sort=False):
            n = int(g.A_trades.sum())
            pts = (g.A_net_pts * g.A_trades).sum() / n if n else np.nan
            row = [u, f"{tf}m", cfg, f(n), f(pts, 1)]
            for ex in ("NAT", "OPT"):
                b = B[(B.und == u) & (B.tf == tf) & (B.cfg == cfg) & (B.ex == ex)] if len(B) else B
                if len(b):
                    row += [f(b.trades.sum()), f"{f(b.rs_day.min(), 1)} .. {f(b.rs_day.max(), 1)}"]
                else:
                    row += ["0", "-"]
            b = B[(B.und == u) & (B.tf == tf) & (B.cfg == cfg)] if len(B) else B
            row += [f(b.t.max(), 2) if len(b) else "-", f(b.q_bh.min(), 2) if len(b) and "q_bh" in b else "-"]
            print("| " + " | ".join(row) + " |")


if __name__ == "__main__":
    main()
