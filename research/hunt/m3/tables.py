"""M3: markdown tables for research/MCX_INTRADAY.md from results/*.csv and work files. Prints to stdout."""
import sys, glob
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M
R = M.Path("/home/user/options-lab/research/hunt/m3/results")


def rs(x):
    return "-" if pd.isna(x) else f"{x:+,.0f}"


def cells(tag, title, only=None):
    d = pd.read_csv(R / f"cells_{tag}.csv")
    if only is not None:
        d = d[d.apply(lambda r: (r.und, r.inst, r.rule) in only, axis=1)]
    d = d.sort_values(["und", "inst", "rule"])
    print(f"\n#### {title}\n")
    print("| commodity | inst | rule | trades | /day | win | gross/trade | net/trade | gross/day | net/day | max DD | green months | random net/trade | random p | BH q |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in d.itertuples():
        print(f"| {r.sym if r.inst == 'FUT' else r.und} | {r.inst} | {r.rule} | {r.trades} | {r.per_day:.2f} | {r.win:.0%} | {rs(r.gross_tr)} | "
              f"{rs(r.net_tr)} | {rs(r.gross_day)} | {rs(r.net_day)} | {r.maxdd:,.0f} | {r.green} | {rs(r.rand_net_tr)} | "
              f"{r.p_rand:.2f} | {r.bh_q:.2f} |")


def hours():
    H = pd.concat([pd.read_csv(f) for f in glob.glob(str(M.WORK / "hours_*.csv"))])
    T = pd.read_csv(R / "hours_trades_design.csv")
    rnd = T[T.inst == "OPT"].groupby(["und", "h"]).gross_rand.mean()
    print("\n#### Mean absolute 1-hour futures move (%) by IST hour, design period; option volume share in brackets\n")
    hs = list(range(9, 24))
    print("| commodity | " + " | ".join(f"{h}" for h in hs) + " |")
    print("|---|" + "---|" * len(hs))
    for s in ["CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM", "COPPER"]:
        x = H[H.sym == s].set_index("h")
        cells_ = []
        for h in hs:
            if h in x.index:
                cells_.append(f"{x.loc[h, 'absmove']:.2f} ({x.loc[h, 'optvol_share']:.0f}%)")
            else:
                cells_.append("-")
        print(f"| {s} | " + " | ".join(cells_) + " |")
    print("\n#### Random-entry 1-ITM option buy, gross Rs per trade by entry hour (design; the 'any time' baseline)\n")
    print("| commodity | " + " | ".join(f"{h}" for h in hs) + " |")
    print("|---|" + "---|" * len(hs))
    for s in ["CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM", "COPPER"]:
        print(f"| {s} | " + " | ".join(rs(rnd.get((s, h), np.nan)) for h in hs) + " |")


def capital():
    P = pd.concat([pd.read_csv(f) for f in glob.glob(str(M.WORK / "prem_*.csv"))])
    P["day"] = pd.to_datetime(P.day)
    print("\n#### What Rs 1 lakh can carry (ATM call premium x lot at 15:00, Rs)\n")
    print("| option | median Aug25-Jul26 | median Jul-Oct 26 | max | days > Rs 1 lakh | lots at the recent median |")
    print("|---|---|---|---|---|---|")
    for s, g in P.groupby("sym"):
        a = g[g.day <= M.DESIGN_END].atm_call_rs; b = g[g.day >= M.HOLD_START].atm_call_rs
        print(f"| {s} | {a.median():,.0f} | {b.median():,.0f} | {g.atm_call_rs.max():,.0f} | {(g.atm_call_rs > 1e5).mean():.0%} | {int(1e5 // b.median()) if b.median() > 0 else '-'} |")


def months(tag, keys):
    m = pd.read_csv(R / f"months_{tag}.csv")
    for k in keys:
        x = m[(m.und == k[0]) & (m.inst == k[1]) & (m.rule == k[2])]
        print(f"\n{k}: " + ", ".join(f"{r.day} {r.net:+,.0f} ({r.trades})" for r in x.itertuples()))


if __name__ == "__main__":
    what = sys.argv[1]
    if what == "cells":
        cells(sys.argv[2], sys.argv[3])
    elif what == "hours":
        hours()
    elif what == "capital":
        capital()
    elif what == "months":
        months(sys.argv[2], [tuple(a.split(":")) for a in sys.argv[3:]])
