"""Print compact report tables from results/*.csv.  python -I tables.py SYM..."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd
pd.set_option("display.width", 250)
R = "/home/user/options-lab/research/hunt/m4/results/"
for sym in sys.argv[1:]:
    S = pd.read_csv(R + f"q1_summary_{sym}.csv")
    print("=====", sym)
    for by in ("all", "tb", "mb", "cell_m", "db"):
        x = S[S.by == by]
        col = None if by == "all" else by
        for met in ("vr", "dh", "ug", "un"):
            p = x.pivot_table(index=[c for c in [col] if c] + ["part"] if col else ["part"], columns="H", values=met)
            p = p[[c for c in ["h15", "h60", "h240", "eod"] if c in p.columns]]
            print(f"-- {met} by {by}\n", (p * (100 if met != "vr" else 1)).round(2).to_string())
    n = S[S.by == "all"][["part", "H", "n", "days", "net_rs", "gross_rs", "prem_rs"]]
    print(n.round(0).to_string())
    Ev = pd.read_csv(R + f"q1_events_{sym}.csv")
    Ev = Ev[Ev.cell_m == "all"]
    print("-- events\n", Ev.pivot_table(index=["event"], columns=["part", "H"], values=["vr"]).round(2).to_string())
    print(Ev.pivot_table(index=["event"], columns=["part", "H"], values=["dh"]).mul(100).round(2).to_string())
    print(Ev.pivot_table(index=["event"], columns=["part", "H"], values=["n"]).to_string())
    X = pd.read_csv(R + f"q1_expiry_{sym}.csv")
    print("-- to expiry\n", X.round(3).to_string())
