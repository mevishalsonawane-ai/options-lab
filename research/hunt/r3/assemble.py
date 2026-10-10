"""R3: assemble research/HUNT_R3_GOLDM_MORNING.md from work/head.md + generated tables."""
import sys, subprocess
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"
head = open(W / "head.md").read()
xs = []
for tf in (5, 15, 60):
    S = pd.read_parquet(W / f"sig_XAU_{tf}.parquet").dropna(subset=["bp"])
    yrs = sorted(S.year.unique())
    parts = []
    for y in yrs:
        g = S[S.year == y]
        am, pm = g[g.ses == "AM"].bp.mean(), g[g.ses == "PM"].bp.mean()
        parts.append(f"{y} {am - pm:+.1f}")
    pos = sum(1 for y in yrs if S[(S.year == y) & (S.ses == "AM")].bp.mean() > S[(S.year == y) & (S.ses == "PM")].bp.mean())
    am, pm = S[S.ses == "AM"], S[S.ses == "PM"]
    xs.append(f"{tf}-min: morning {am.bp.mean():+.1f} bp (n={len(am)}) vs evening {pm.bp.mean():+.1f} bp (n={len(pm)}); "
              f"morning beat evening in {pos} of {len(yrs)} years (AM-PM bp by year: {', '.join(parts)})")
y0 = int(pd.read_parquet(W / "sig_XAU_5.parquet").year.min())
summary = (f"XAUUSD {y0} - Sep 2026, every MCX session (2,976 days), 60-min forward return after the same signal:\n"
           + "\n".join(f"   - {x}" for x in xs) +
           "\n   - On 5-min bars (the report's timeframe), every year's gap sits between -1.2 and +1.3 bp. Only 2019 clears zero"
           " (+1.3 bp, CI +0.1 to +2.6), which is about 1 year in 12 by chance."
           "\n   - On 60-min bars the yearly gaps swing from -10 to +6 bp, with few signals and CIs that include 0 in almost"
           " every year. 2023 goes clearly the other way (CI excludes 0), and 2026 nearly does."
           "\n   - Pooled over 12 years the gap is +0.2 bp (5m), -0.5 bp (15m) and +0.2 bp (60m). None of these is an edge.")
head = head.replace("XAU_SUMMARY", "\n   - " + summary)
win = subprocess.run([sys.executable, "-P", str(Path(__file__).parent / "windows.py")], capture_output=True, text=True).stdout
doc = [head, "\n## Tables\n", "### A. Futures level: 60-min forward return after the signal, morning vs evening (bp)\n",
       open(W / "fut_tables.md").read(), "\n### B. Option level (GOLDM, 1 lot = 10 x premium)\n", open(W / "opt_tables.md").read(),
       "\n### C. How often does an 11-session window look like the report? (all-signals system, 5-min, split by entry time)\n",
       "```\n" + win + "```\n"]
# reproduction list
R = pd.read_parquet(W / "trades_all.parquet")
t = R[(R.src == "GOLDM_NOV") & (R.tf == 5) & (R.strike == "ATM") & (R.filt == "ALL") & (R.spread_case == "nospread") & (R.fill == "RAW") &
      (R.days == "allday") & (R.status == "ok") & (R.day >= dt.date(2026, 9, 24)) & (R.day <= dt.date(2026, 10, 8))]
rep = {("2026-09-28", "15:20"), ("2026-09-30", "09:05"), ("2026-09-30", "14:25"), ("2026-10-01", "09:10"), ("2026-10-01", "12:15"),
       ("2026-10-05", "09:00"), ("2026-10-06", "09:00"), ("2026-10-06", "11:50"), ("2026-10-07", "09:00"), ("2026-10-08", "10:55")}
hm = lambda x: f"{int(x)//60:02d}:{int(x)%60:02d}"
doc.append("\n### D. Our trades in the report window (GOLDM NOV futures signals, near-month ATM, raw prints, flat Rs 100)\n")
doc.append("| date | bar | side | strike | entry Rs | exit Rs | exit | P&L Rs | also in report? |\n|---|---|---|---|---|---|---|---|---|")
for r in t.itertuples():
    doc.append(f"| {r.day} | {hm(r.start)} | {'CE' if r.side == 1 else 'PE'} | {r.K:.0f} | {r.E:,.0f} | {r.X:,.0f} | {r.why} | {r.gross - 100:+,.0f} | "
               f"{'yes' if (str(r.day), hm(r.start)) in rep else ''} |")
doc.append("\nAll the report's morning trades that we also took were winners for us too, except the 6 Oct 09:00 PE (+40). Its evening "
           "trades on 24-29 Sep mostly did not appear for us: our max-2-a-day was used up by morning signals.")
open("/home/user/options-lab/research/HUNT_R3_GOLDM_MORNING.md", "w").write("\n".join(doc))
print("written")
