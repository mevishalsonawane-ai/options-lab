"""R3: markdown tables from fut.py outputs (work/sig_<NAME>_<tf>.parquet, all_<NAME>_<tf>.parquet) -> work/fut_tables.md"""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"
L = []
P = L.append


def diff_ci(am, pm, B=2000, seed=1):
    rng = np.random.default_rng(seed)
    ga, gp = am.groupby("day").bp.agg(["sum", "count"]), pm.groupby("day").bp.agg(["sum", "count"])
    if len(ga) < 3 or len(gp) < 3:
        return np.nan, np.nan
    bs = []
    for _ in range(B):
        a = ga.iloc[rng.integers(0, len(ga), len(ga))].sum(); p = gp.iloc[rng.integers(0, len(gp), len(gp))].sum()
        bs.append(a["sum"] / a["count"] - p["sum"] / p["count"])
    return np.percentile(bs, 2.5), np.percentile(bs, 97.5)


def f(t):
    return f"{t[0]:+.1f} [{t[1]:+.1f}, {t[2]:+.1f}]"


NAMES = [("GOLDM_C", "GOLDM near-month futures (Dhan, close-only bars), Aug 2025 - Oct 2026"),
         ("GOLDM_NOV", "GOLDM NOV futures (real OHLC), May - Oct 2026"),
         ("GOLD_C", "GOLD near-month futures (Dhan, close-only), Aug 2025 - Oct 2026"),
         ("GOLDPETAL_OCT", "GOLDPETAL OCT futures (real OHLC), May - Oct 2026"),
         ("XAU", "XAUUSD spot proxy, 1-min (histdata.com Jan 2015 - Sep 2023, Dukascopy Oct 2023 - Sep 2026), MCX hours IST")]
for name, title in NAMES:
    if not (W / f"sig_{name}_5.parquet").exists():
        continue
    P(f"\n#### {title}\n")
    P("| bars | year | AM n | AM bp [95% CI] | AM hit | PM n | PM bp [95% CI] | PM hit | AM-PM [95% CI] | AM longs / shorts bp | random LONG at AM bar times | random LONG PM |")
    P("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for tf in (5, 15, 60):
        S = pd.read_parquet(W / f"sig_{name}_{tf}.parquet").dropna(subset=["bp"])
        A = pd.read_parquet(W / f"all_{name}_{tf}.parquet")
        years = sorted(S.year.unique()) + ["all"]
        for y in years:
            g = S if y == "all" else S[S.year == y]
            a = A if y == "all" else A[A.year == y]
            am, pm = g[g.ses == "AM"], g[g.ses == "PM"]
            lo, hi = diff_ci(am, pm)
            P(f"| {tf}m | {y} | {len(am)} | {f(boot_ci(am.bp, am.day))} | {100*(am.bp>0).mean():.0f}% | {len(pm)} | {f(boot_ci(pm.bp, pm.day))} | {100*(pm.bp>0).mean():.0f}% | "
              f"{am.bp.mean()-pm.bp.mean():+.1f} [{lo:+.1f}, {hi:+.1f}] | {am[am.side==1].bp.mean():+.1f} / {am[am.side==-1].bp.mean():+.1f} | "
              f"{a[a.ses=='AM'].raw_bp.mean():+.2f} | {a[a.ses=='PM'].raw_bp.mean():+.2f} |")
    S = pd.read_parquet(W / f"sig_{name}_5.parquet").dropna(subset=["bp"])
    P("\n5-min, by entry hour (all years): " + ", ".join(f"{h:02d}h {g.bp.mean():+.1f} bp (n={len(g)})" for h, g in S.groupby("hour")))
    w = S[(S.day >= dt.date(2026, 9, 24)) & (S.day <= dt.date(2026, 10, 8))]
    if len(w):
        P("\n5-min, report window 24 Sep - 8 Oct 2026: " + "; ".join(f"{s} n={len(g)}, {g.bp.mean():+.1f} bp, hit {100*(g.bp>0).mean():.0f}%" for s, g in w.groupby("ses")))
open(W / "fut_tables.md", "w").write("\n".join(L))
print("\n".join(L))
