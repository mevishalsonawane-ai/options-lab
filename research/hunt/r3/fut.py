"""R3 part 1: futures-level test of the GOLDM EMA(8)/EMA(21)+ADX(14)>15 signal by time of day.
Usage: python3 -P fut.py  -> scratchpad/hunt/r3/work/sig_<NAME>_<tf>.parquet and printed tables (fut.log)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"
NAMES = sys.argv[1:] or ["GOLDM_C", "GOLD_C", "GOLDM_NOV", "GOLDPETAL_OCT", "XAU"]
CUT = 23 * 60 + 15

def run(name, tf):
    m = pd.read_parquet(W / f"min_{name}.parquet")
    G = grid(m)
    B = make_bars(m, tf)
    S = signals(B)
    S = S[S.sig_min < CUT - 1]
    # warm-up: drop signals on the first day after a data gap of > 4 calendar days (sampled XAU weeks)
    ud = sorted(m.day.unique())
    warm = {ud[0]} | {b for a, b in zip(ud[:-1], ud[1:]) if (b - a).days > 4}
    S = S[~S.day.isin(warm)]
    S = fwd(m, S, G=G)
    # baseline: every bar of the same year at the same bar time, long (raw_bp)
    A = B[B.sig_min < CUT - 1].assign(side=1)
    A = fwd(m, A, G=G)
    A["year"] = [d.year for d in A.day]
    base = A.groupby(["year", "sig_min"]).raw_bp.mean()
    S["year"] = [d.year for d in S.day]
    S["base_long"] = base.reindex(pd.MultiIndex.from_arrays([S.year, S.sig_min])).values
    S["base"] = S.base_long * S.side          # same time, same side, random day of that year
    S["exc"] = S.bp - S.base
    S["ses"] = sess_label(S.start.values)
    S["hour"] = S.start // 60
    A["ses"] = sess_label(A.start.values)
    S.to_parquet(W / f"sig_{name}_{tf}.parquet")
    A[["day", "start", "sig_min", "raw_bp", "year", "ses"]].to_parquet(W / f"all_{name}_{tf}.parquet")
    return S, A


def fmt(t):
    return f"{t[0]:+.1f} [{t[1]:+.1f},{t[2]:+.1f}]"


for name in NAMES:
    for tf in (5, 15, 60):
        S, A = run(name, tf)
        S = S.dropna(subset=["bp"])
        print(f"\n=== {name} {tf}-min: {len(S)} signals, {S.day.min()}..{S.day.max()}")
        print("year ses   n   mean_bp [95% day-block CI]   hit%  long_bp(n)  short_bp(n)  base_bp  excess_bp   randLONG_bp")
        for (y, ses), g in S.groupby(["year", "ses"]):
            L, Sh = g[g.side == 1], g[g.side == -1]
            rl = A[(A.year == y) & (A.ses == ses)].raw_bp
            print(f"{y} {ses} {len(g):4d}  {fmt(boot_ci(g.bp, g.day)):24s} {100*(g.bp>0).mean():5.1f}  "
                  f"{L.bp.mean():+6.1f}({len(L)})  {Sh.bp.mean():+6.1f}({len(Sh)})  {g.base.mean():+6.1f}  {fmt(boot_ci(g.exc, g.day)):24s} {rl.mean():+5.2f}")
        for ses, g in S.groupby("ses"):
            print(f"ALL  {ses} {len(g):4d}  {fmt(boot_ci(g.bp, g.day))}  hit {100*(g.bp>0).mean():.1f}  exc {fmt(boot_ci(g.exc, g.day))}")
        # AM - PM difference per year (day-block bootstrap)
        for y, g in S.groupby("year"):
            am, pm = g[g.ses == "AM"], g[g.ses == "PM"]
            rng = np.random.default_rng(1); da, dp = am.day.values, pm.day.values
            ua, up = np.unique(da), np.unique(dp)
            bs = []
            for _ in range(2000):
                xa = am[np.isin(da, rng.choice(ua, len(ua)))].bp.mean() if len(ua) else np.nan
                xp = pm[np.isin(dp, rng.choice(up, len(up)))].bp.mean() if len(up) else np.nan
                bs.append(xa - xp)
            print(f"  {y} AM-PM diff {am.bp.mean()-pm.bp.mean():+.1f} bp  CI [{np.nanpercentile(bs,2.5):+.1f},{np.nanpercentile(bs,97.5):+.1f}]")
        if tf == 5:
            print("  by entry hour (all years):", " ".join(f"{h:02d}:{g.bp.mean():+.1f}/{len(g)}" for h, g in S.groupby("hour")))
            w = S[(S.day >= dt.date(2026, 9, 24)) & (S.day <= dt.date(2026, 10, 8))]
            if len(w):
                print("  REPORT WINDOW 24 Sep-8 Oct 2026:", " | ".join(f"{s}: n={len(g)} mean {g.bp.mean():+.1f} bp hit {100*(g.bp>0).mean():.0f}%" for s, g in w.groupby("ses")))
