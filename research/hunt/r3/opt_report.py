"""R3: markdown tables for the option-level study (reads work/trades_all.parquet) -> work/opt_tables.md"""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
from opt_tables import summ, BLOCKS, R
W = R3 / "work"
R = R.copy()
R["net100"] = R.gross - 100
L = []
P = L.append


def sel(src="GOLDM_C", tf=5, strike="ATM", sp="base", fill="CLEAN", days="allday"):
    return R[(R.src == src) & (R.tf == tf) & (R.strike == strike) & (R.spread_case == sp) & (R.fill == fill) & (R.days == days)]


def row(name, T, blk, col="net"):
    s = summ(T, blk, col)
    if s["n"] == 0:
        return f"| {name} | 0 | | | | | | | | |"
    return (f"| {name} | {s['n']} | {s['win']:.0f}% | {s['per_trade']:+,.0f} [{s['ci'][1]:+,.0f}, {s['ci'][2]:+,.0f}] | {s['per_day']:+,.0f} | "
            f"{s['total']:+,.0f} | {s['maxdd']:+,.0f} | {s['green_months']} | {s['gross_pt']:+,.0f} | {s['cost_pt']:,.0f} |")


HDR = "| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |\n|---|---|---|---|---|---|---|---|---|---|"
for blk in ("Oct25-Oct26", "Aug-Sep25"):
    P(f"\n### {blk}: base costs (charges + spread 0.80% before 17:00 / 0.40% after), clean fills\n")
    P(HDR)
    for tf in (5, 15, 60):
        for strike in ("ATM", "ITM1"):
            T = sel(tf=tf, strike=strike)
            for f in ("ALL", "AM", "PM"):
                P(row(f"{tf}m {strike} {f}", T[T.filt == f], blk))
P("\n### Cost / fill sensitivity, 5-min, 1 Oct 2025 - 8 Oct 2026\n")
P(HDR)
for strike in ("ATM", "ITM1"):
    for lab, kw, col in [("base (0.80/0.40% spread)", dict(sp="base"), "net"), ("M4 spread (0.60/0.30%)", dict(sp="m4"), "net"),
                         ("charges only, no spread", dict(sp="nospread"), "net"), ("RAW fills, charges only", dict(sp="nospread", fill="RAW"), "net"),
                         ("RAW fills, report's flat Rs 100", dict(sp="nospread", fill="RAW"), "net100"),
                         ("base, expiry days skipped", dict(sp="base", days="skipexp"), "net")]:
        T = sel(strike=strike, **kw)
        for f in ("ALL", "AM", "PM"):
            P(row(f"{strike} {f} - {lab}", T[T.filt == f], "Oct25-Oct26", col))
P("\n### By month, 5-min ATM, base costs, clean fills (net Rs; trades)\n")
T = sel()
T = T[T.status == "ok"]
T["mon"] = [d.strftime("%Y-%m") for d in T.day]
P("| month | ALL | AM only | PM only | AM gross |\n|---|---|---|---|---|")
for mo, g in T.groupby("mon"):
    c = {f: g[g.filt == f] for f in ("ALL", "AM", "PM")}
    P(f"| {mo} | {c['ALL'].net.sum():+,.0f} ({len(c['ALL'])}) | {c['AM'].net.sum():+,.0f} ({len(c['AM'])}) | {c['PM'].net.sum():+,.0f} ({len(c['PM'])}) | {c['AM'].gross.sum():+,.0f} |")
# AM - PM per-trade difference, Oct25-Oct26
P("\n### AM-only minus PM-only, Rs per trade, 1 Oct 2025 - 8 Oct 2026 (day-block bootstrap)\n")
P("| config | AM net/tr | PM net/tr | diff [95% CI] | AM gross/tr | PM gross/tr |\n|---|---|---|---|---|---|")
a0, b0 = BLOCKS["Oct25-Oct26"]
for tf in (5, 15, 60):
    for strike in ("ATM", "ITM1"):
        T = sel(tf=tf, strike=strike)
        T = T[(T.status == "ok") & (T.day >= a0) & (T.day <= b0)]
        am, pm = T[T.filt == "AM"], T[T.filt == "PM"]
        rng = np.random.default_rng(0)
        ua, up = np.unique(am.day), np.unique(pm.day)
        ga, gp = am.groupby("day").net.agg(["sum", "count"]), pm.groupby("day").net.agg(["sum", "count"])
        bs = []
        for _ in range(3000):
            xa = ga.iloc[rng.integers(0, len(ga), len(ga))].sum(); xp = gp.iloc[rng.integers(0, len(gp), len(gp))].sum()
            bs.append(xa["sum"] / xa["count"] - xp["sum"] / xp["count"])
        P(f"| {tf}m {strike} | {am.net.mean():+,.0f} | {pm.net.mean():+,.0f} | {am.net.mean()-pm.net.mean():+,.0f} [{np.percentile(bs,2.5):+,.0f}, {np.percentile(bs,97.5):+,.0f}] | {am.gross.mean():+,.0f} | {pm.gross.mean():+,.0f} |")
# report window
P("\n### Report window 24 Sep - 8 Oct 2026, 5-min, signals from the GOLDM NOV futures (real OHLC)\n")
P(HDR)
for strike in ("ATM", "ITM1"):
    for lab, kw, col in [("RAW fills, flat Rs 100 (report's costs)", dict(sp="nospread", fill="RAW"), "net100"), ("clean fills, base costs", dict(sp="base"), "net")]:
        T = sel(src="GOLDM_NOV", strike=strike, **kw)
        for f in ("ALL", "AM", "PM"):
            P(row(f"{strike} {f} - {lab}", T[T.filt == f], "RptWin", col))
P("\n### Signal source check, 7 May - 8 Oct 2026: continuous close-only series (GOLDM_C) vs GOLDM NOV futures OHLC\n")
P(HDR)
for src in ("GOLDM_C", "GOLDM_NOV"):
    for strike in ("ATM", "ITM1"):
        T = sel(src=src, strike=strike)
        for f in ("ALL", "AM", "PM"):
            P(row(f"{src} {strike} {f}", T[T.filt == f], "May-Oct26"))
P("\n### GOLDM NOV futures OHLC source, 7 May - 8 Oct 2026, RAW fills + flat Rs 100\n")
P(HDR)
for strike in ("ATM", "ITM1"):
    T = sel(src="GOLDM_NOV", strike=strike, sp="nospread", fill="RAW")
    for f in ("ALL", "AM", "PM"):
        P(row(f"{strike} {f} - RAW, flat Rs 100", T[T.filt == f], "May-Oct26", "net100"))
open(W / "opt_tables.md", "w").write("\n".join(L))
print("\n".join(L))
