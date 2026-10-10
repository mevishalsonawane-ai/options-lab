"""h41: WHEN and HOW the Indian index market moves, measured on our own index minutes (no option P&L).

Run:  python3 -I research/hunt/h41/intraday.py   (light; reads the cached per-day index minutes of research/obuy)
Out:  scratchpad/hunt/h41/intraday.log + CSVs.

Sections
  A  volatility by time of day (mean |1-min return|, bps; ratio to the day's average minute)
  C  when the day's high / low is set (actual 1-min highs/lows) vs two random-walk benchmarks
  D  does the first 15 / 30 / 60 minutes' direction hold to the close (rest-of-day continuation)
  D2 intraday momentum: first 30 min (incl. gap) -> last 30 min (Gao et al.), rest-of-day -> last 30 (Baltussen et al.)
  E  gaps: fill vs go by gap size
  F  expiry day vs normal day path; morning-vs-afternoon (the Jane Street lens)
  G  regime summary: before 20 Nov 2024 / 20 Nov 2024 - 3 Jul 2025 / after the 4 Jul 2025 Jane Street order
Periods: P1 < 2024-11-20, P2 2024-11-20 .. 2025-07-03, P3 >= 2025-07-04 (data to 2026-10-06).
"""
from __future__ import annotations

import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h41")
os.makedirs(OUT, exist_ok=True)
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
P2_START, P3_START = date(2024, 11, 20), date(2025, 7, 4)
RNG = np.random.default_rng(41)
LOG = open(os.path.join(OUT, "intraday.log"), "w")


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s)
    LOG.write(s + "\n")
    LOG.flush()


def table(df, title, fmt="{:.1f}"):
    say(f"\n### {title}")
    say(df.to_string(float_format=lambda x: fmt.format(x)))


def period(d):
    return "P1 pre-Nov24" if d < P2_START else ("P2 Nov24-Jul25" if d < P3_START else "P3 post-JS")


BUCKETS = [(0, 15, "09:15-09:30"), (15, 45, "09:30-10:00"), (45, 105, "10:00-11:00"), (105, 165, "11:00-12:00"),
           (165, 225, "12:00-13:00"), (225, 285, "13:00-14:00"), (285, 345, "14:00-15:00"), (345, 375, "15:00-15:30")]
BNAMES = [b[2] for b in BUCKETS]


def bucket_of(col):
    for a, b, n in BUCKETS:
        if a <= col < b:
            return n
    return None


def tstat(x):
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    return x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan


# ------------------------------------------------------------------------------------------------ load
mk = D.market()
vix = mk.vix.daily.close
P = {}
for u in UNDS:
    ix = mk.index(u)
    M = ix.mat()
    dd = ix.daily()
    full = np.isfinite(M["c"]).sum(1) >= 370
    keep = full & dd.real.values
    days = [d for d, k in zip(ix.days, keep) if k]
    idx = np.nonzero(keep)[0]
    o, h, l, c = (M[k][idx] for k in ("o", "h", "l", "c"))
    # forward-fill the rare missing minute so paths are complete
    for A in (o, h, l, c):
        df = pd.DataFrame(A.T).ffill().bfill()
        A[:] = df.values.T
    exp = dd.exp.values[idx]
    # previous session close (only if the previous calendar trading row exists in the data and is within 5 days)
    allc = {d: M["c"][i][np.isfinite(M["c"][i])][-1] for i, d in enumerate(ix.days) if np.isfinite(M["c"][i]).any()}
    alld = ix.days
    pos = {d: i for i, d in enumerate(alld)}
    pc = np.array([allc.get(alld[pos[d] - 1], np.nan) if pos[d] > 0 and (d - alld[pos[d] - 1]).days <= 5 else np.nan
                   for d in days])
    P[u] = dict(days=days, o=o, h=h, l=l, c=c, exp=exp, pc=pc, per=np.array([period(d) for d in days]),
                year=np.array([d.year for d in days]),
                vix=np.array([float(vix[vix.index < d].iloc[-1]) if (vix.index < d).any() else np.nan for d in days]))
    say(f"{u}: {len(days)} clean days {days[0]} .. {days[-1]}, expiry days {int(exp.sum())}")
    mk.release(u)

# ------------------------------------------------------------------------------------------------ A: volatility U
say("\n## A. Volatility by time of day: mean |1-min close-to-close return| in bps (first bar: |close/open-1|)")
rowsA = []
for u, X in P.items():
    c, o = X["c"], X["o"]
    r = np.abs(np.diff(np.log(c), axis=1)) * 1e4
    r = np.concatenate([np.abs(np.log(c[:, :1] / o[:, :1])) * 1e4, r], axis=1)
    X["absr"] = r
    for per in ["ALL", "P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]:
        sel = np.ones(len(r), bool) if per == "ALL" else X["per"] == per
        for e in ["all", "normal", "expiry"]:
            s2 = sel & (True if e == "all" else (X["exp"] if e == "expiry" else ~X["exp"]))
            if s2.sum() < 5:
                continue
            m = r[s2].mean(0)
            row = dict(und=u, per=per, day=e, n=int(s2.sum()), day_avg=m.mean())
            for a, b, n in BUCKETS:
                row[n] = m[a:b].mean()
            row["min1"] = m[0]
            row["last5"] = m[370:].mean()
            rowsA.append(row)
A = pd.DataFrame(rowsA)
A.to_csv(os.path.join(OUT, "A_vol_by_bucket.csv"), index=False)
t = A[(A.per == "ALL") & (A.day == "all")].set_index("und")[["n", "day_avg", "min1"] + BNAMES + ["last5"]]
table(t, "A1 mean |1-min return| bps, all days, by bucket")
rat = t[BNAMES].div(t["day_avg"], axis=0)
table(rat, "A2 same, as a ratio to the day's average minute (U-shape)", "{:.2f}")
t2 = A[(A.day == "all") & (A.und.isin(["NIFTY", "BANKNIFTY", "SENSEX"]))].set_index(["und", "per"])
table(t2[BNAMES].div(t2["day_avg"], axis=0).assign(day_avg_bps=t2.day_avg, n=t2.n), "A3 U-shape ratio by period", "{:.2f}")
t3 = A[(A.per == "ALL") & (A.day != "all")].set_index(["und", "day"])
table(t3[["n", "day_avg"] + BNAMES], "A4 expiry vs normal: |1-min return| bps by bucket")
# minute-by-minute profile (NIFTY, all) for the report's sparkline
prof = pd.DataFrame({u: P[u]["absr"].mean(0) for u in P})
prof.index = [f"{(555 + i) // 60:02d}:{(555 + i) % 60:02d}" for i in range(C.W)]
prof.to_csv(os.path.join(OUT, "A_minute_profile.csv"))
say("\nA5 NIFTY minute profile (bps): " + ", ".join(f"{k} {v:.2f}" for k, v in prof.NIFTY.iloc[[0, 1, 2, 5, 10, 15, 30, 60, 120, 180, 240, 300, 330, 345, 360, 370, 374]].items()))

# ------------------------------------------------------------------------------------------------ C: high/low timing
say("\n## C. When is the day's high / low set? (actual 1-min highs and lows; ties -> first)")
HLB = [(0, 15, "09:15-09:30"), (15, 60, "09:30-10:15"), (60, 165, "10:15-12:00"), (165, 285, "12:00-14:00"),
       (285, 345, "14:00-15:00"), (345, 375, "15:00-15:30")]


def hl_dist(imax, imin):
    out = {}
    for a, b, n in HLB:
        out["H " + n] = 100 * np.mean((imax >= a) & (imax < b))
    for a, b, n in HLB:
        out["L " + n] = 100 * np.mean((imin >= a) & (imin < b))
    first = np.minimum(imax, imin)
    out["either in 1st 15m"] = 100 * np.mean(first < 15)
    out["either in 1st 60m"] = 100 * np.mean(first < 60)
    out["either in last 30m"] = 100 * np.mean(np.maximum(imax, imin) >= 345)
    out["both in 10:15-15:00"] = 100 * np.mean((first >= 60) & (np.maximum(imax, imin) < 345))
    return out


rowsC = []
for u, X in P.items():
    h, l, c, o = X["h"], X["l"], X["c"], X["o"]
    imax, imin = h.argmax(1), l.argmin(1)
    X["imax"], X["imin"] = imax, imin
    H, L, O, Cl = h.max(1), l.min(1), o[:, 0], c[:, -1]
    eff = np.abs(Cl - O) / (H - L)
    X["eff"] = eff
    X["dtype"] = np.where(eff >= 0.6, "trend", np.where(eff <= 0.25, "range", "middle"))
    groups = [("ALL", np.ones(len(c), bool))]
    groups += [(f"Y{y}", X["year"] == y) for y in sorted(set(X["year"]))]
    groups += [(p, X["per"] == p) for p in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]]
    groups += [(f"type={t}", X["dtype"] == t) for t in ["trend", "middle", "range"]]
    groups += [("expiry", X["exp"]), ("normal", ~X["exp"])]
    for g, s in groups:
        if s.sum() < 20:
            continue
        rowsC.append(dict(und=u, group=g, n=int(s.sum()), **hl_dist(imax[s], imin[s])))
    # benchmarks on the CLOSE path: (i) data, (ii) iid shuffle of the day's own minute returns,
    # (iii) cross-day shuffle at the same minute (keeps the U-shaped vol, kills any time-of-day drift / reversal)
    lc = np.log(c)
    rr = np.diff(np.concatenate([np.log(o[:, :1]), lc], axis=1), axis=1)
    path = np.cumsum(rr, 1)
    b1 = np.array([np.cumsum(RNG.permutation(x)) for x in rr])
    b2 = np.cumsum(np.stack([RNG.permutation(rr[:, j]) for j in range(rr.shape[1])], 1), 1)
    for name, pth in [("close-path data", path), ("bench iid shuffle", b1), ("bench same-minute shuffle", b2)]:
        pth0 = np.concatenate([np.zeros((len(pth), 1)), pth], 1)  # include the open as a candidate
        im, iM = pth0.argmin(1) - 1, pth0.argmax(1) - 1
        im, iM = np.clip(im, 0, None), np.clip(iM, 0, None)
        rowsC.append(dict(und=u, group=name, n=len(pth), **hl_dist(iM, im)))
Cdf = pd.DataFrame(rowsC)
Cdf.to_csv(os.path.join(OUT, "C_highlow_timing.csv"), index=False)
cols = ["n", "H 09:15-09:30", "H 09:30-10:15", "H 15:00-15:30", "L 09:15-09:30", "L 09:30-10:15", "L 15:00-15:30",
        "either in 1st 15m", "either in 1st 60m", "either in last 30m", "both in 10:15-15:00"]
table(Cdf[Cdf.group == "ALL"].set_index("und")[cols], "C1 % of days the high / low is set in each window (all days)")
table(Cdf[Cdf.group.str.startswith(("close-path", "bench"))].set_index(["und", "group"])[cols],
      "C2 data vs random-walk benchmarks (close path)")
table(Cdf[Cdf.group.str.startswith("type=")].set_index(["und", "group"])[cols], "C3 by day type (trend: |C-O|>=60% of range; range: <=25%)")
table(Cdf[Cdf.group.str.startswith(("P1", "P2", "P3"))].set_index(["und", "group"])[cols], "C4 by period")
table(Cdf[Cdf.group.str.startswith("Y")].set_index(["und", "group"])[cols], "C5 by year")
table(Cdf[Cdf.group.isin(["expiry", "normal"])].set_index(["und", "group"])[cols], "C6 expiry vs normal")
dt = pd.DataFrame([dict(und=u, per=p, n=int((X["per"] == p).sum()),
                        trend=100 * np.mean(X["dtype"][X["per"] == p] == "trend"),
                        range_=100 * np.mean(X["dtype"][X["per"] == p] == "range"),
                        eff=np.mean(X["eff"][X["per"] == p]))
                   for u, X in P.items() for p in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"] if (X["per"] == p).sum() > 20])
table(dt.set_index(["und", "per"]), "C7 share of trend / range days by period (%)")

# ------------------------------------------------------------------------------------------------ D: first-period direction
say("\n## D. Does the opening direction hold? r1 = open -> close of minute k; rest = that close -> 15:29 close")
rowsD = []
for u, X in P.items():
    c, o = X["c"], X["o"]
    O, Cl = o[:, 0], c[:, -1]
    for k, nm in [(15, "first 15m"), (30, "first 30m"), (60, "first 60m"), (150, "to 11:45")]:
        r1 = np.log(c[:, k - 1] / O)
        rest = np.log(Cl / c[:, k - 1])
        day = np.log(Cl / O)
        for g, s in [("ALL", np.ones(len(c), bool))] + [(p, X["per"] == p) for p in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]] + [("expiry", X["exp"]), ("normal", ~X["exp"])]:
            if s.sum() < 20:
                continue
            nz = s & (r1 != 0)
            sg = np.sign(r1[nz])
            cont = rest[nz] * sg * 1e4
            big = nz & (np.abs(r1) > np.nanpercentile(np.abs(r1[nz]), 75))
            rowsD.append(dict(und=u, window=nm, group=g, n=int(nz.sum()),
                              close_same_side_pct=100 * np.mean(np.sign(day[nz]) == sg),
                              rest_continues_pct=100 * np.mean(np.sign(rest[nz]) == sg),
                              rest_cont_bps=cont.mean(), t=tstat(cont),
                              big_q4_rest_continues_pct=100 * np.mean(np.sign(rest[big]) == np.sign(r1[big])),
                              big_q4_cont_bps=(rest[big] * np.sign(r1[big]) * 1e4).mean(),
                              corr=np.corrcoef(r1[s], rest[s])[0, 1]))
Ddf = pd.DataFrame(rowsD)
Ddf.to_csv(os.path.join(OUT, "D_first_period.csv"), index=False)
table(Ddf[Ddf.group == "ALL"].set_index(["und", "window"]).drop(columns="group"), "D1 all days", "{:.2f}")
table(Ddf[(Ddf.window == "first 60m") & Ddf.group.str.startswith("P")].set_index(["und", "group"]).drop(columns="window"), "D2 first hour, by period", "{:.2f}")
table(Ddf[(Ddf.window == "first 60m") & Ddf.group.isin(["expiry", "normal"])].set_index(["und", "group"]).drop(columns="window"), "D3 first hour, expiry vs normal", "{:.2f}")

say("\n## D2. Intraday momentum: (a) prev close -> 09:44 close vs 14:59 -> 15:29 (Gao et al.); (b) prev close -> 14:59 vs last 30 (Baltussen)")
rowsM = []
for u, X in P.items():
    c, pc = X["c"], X["pc"]
    ok = np.isfinite(pc)
    f30 = np.log(c[:, 29] / pc)
    rod = np.log(c[:, 344] / pc)
    l30 = np.log(c[:, -1] / c[:, 344])
    for g, s in [("ALL", ok)] + [(p, ok & (X["per"] == p)) for p in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]] + [("expiry", ok & X["exp"]), ("normal", ok & ~X["exp"])]:
        if s.sum() < 30:
            continue
        x1 = l30[s] * np.sign(f30[s]) * 1e4
        x2 = l30[s] * np.sign(rod[s]) * 1e4
        rowsM.append(dict(und=u, group=g, n=int(s.sum()), corr_f30_l30=np.corrcoef(f30[s], l30[s])[0, 1],
                          signed_l30_bps=x1.mean(), t1=tstat(x1),
                          corr_rod_l30=np.corrcoef(rod[s], l30[s])[0, 1], signed2_bps=x2.mean(), t2=tstat(x2),
                          mean_abs_l30_bps=np.abs(l30[s]).mean() * 1e4))
Mdf = pd.DataFrame(rowsM)
Mdf.to_csv(os.path.join(OUT, "D2_intraday_momentum.csv"), index=False)
table(Mdf.set_index(["und", "group"]), "D2 last-30-minute momentum", "{:.3f}")

# ------------------------------------------------------------------------------------------------ E: gaps
say("\n## E. Gaps: gap = 09:15 open / previous 15:29 close - 1. fill = touched previous close later that day.")
GB = [(0, 0.1, "<0.1%"), (0.1, 0.3, "0.1-0.3%"), (0.3, 0.6, "0.3-0.6%"), (0.6, 1.0, "0.6-1.0%"), (1.0, 99, ">1.0%")]
rowsE = []
for u, X in P.items():
    o, h, l, c, pc = X["o"], X["h"], X["l"], X["c"], X["pc"]
    ok = np.isfinite(pc)
    O = o[:, 0]
    gap = (O / pc - 1) * 100
    up = gap > 0
    # filled: for gap up, low <= prev close at some minute; for gap down, high >= prev close
    filled_by = np.where(up[:, None], l <= pc[:, None], h >= pc[:, None])
    filled = filled_by.any(1)
    first_fill = np.where(filled, filled_by.argmax(1), -1)
    go = np.log(c[:, -1] / O) * np.sign(gap) * 1e4   # open->close in gap direction
    for g, s0 in [("ALL", ok)] + [(p, ok & (X["per"] == p)) for p in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]]:
        for a, b, nm in GB:
            s = s0 & (np.abs(gap) >= a) & (np.abs(gap) < b)
            if s.sum() < 10:
                continue
            rowsE.append(dict(und=u, group=g, gap=nm, n=int(s.sum()), share_of_days=100 * s.sum() / s0.sum(),
                              fill_pct=100 * filled[s].mean(), fill_by_1015_pct=100 * np.mean(filled[s] & (first_fill[s] < 60)),
                              median_fill_min=np.median(first_fill[s][filled[s]]) if filled[s].any() else np.nan,
                              close_beyond_open_pct=100 * np.mean(go[s] > 0), open_to_close_in_gap_dir_bps=go[s].mean(),
                              t=tstat(go[s]), close_beyond_prevclose_pct=100 * np.mean(np.sign(c[s, -1] - pc[s]) == np.sign(gap[s]))))
Edf = pd.DataFrame(rowsE)
Edf.to_csv(os.path.join(OUT, "E_gaps.csv"), index=False)
table(Edf[Edf.group == "ALL"].set_index(["und", "gap"]).drop(columns="group"), "E1 gap fill vs go, all days", "{:.1f}")
table(Edf[(Edf.group != "ALL") & Edf.und.isin(["NIFTY", "BANKNIFTY"])].set_index(["und", "group", "gap"]), "E2 by period (NIFTY, BANKNIFTY)", "{:.1f}")

# ------------------------------------------------------------------------------------------------ F: expiry path
say("\n## F. Expiry day vs normal day")
rowsF = []
for u, X in P.items():
    o, h, l, c = X["o"], X["h"], X["l"], X["c"]
    O, Cl, H, L = o[:, 0], c[:, -1], h.max(1), l.min(1)
    rng = (H / L - 1) * 1e4
    late_rng = (h[:, 315:].max(1) / l[:, 315:].min(1) - 1) * 1e4     # 14:30-15:29
    am = np.log(c[:, 149] / O)                                        # 09:15 -> 11:44 close
    pm = np.log(Cl / c[:, 149])                                       # 11:44 -> 15:29
    last60 = np.log(Cl / c[:, 314])
    for g in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS", "ALL"]:
        s0 = np.ones(len(c), bool) if g == "ALL" else X["per"] == g
        for e, se in [("expiry", X["exp"]), ("normal", ~X["exp"])]:
            s = s0 & se
            if s.sum() < 8:
                continue
            bigam = s & (np.abs(am) > 0.005)
            rowsF.append(dict(und=u, per=g, day=e, n=int(s.sum()), range_bps=np.mean(rng[s]),
                              range_over_vix=np.nanmean(rng[s] / (X["vix"][s] / np.sqrt(252) * 100)),
                              abs_oc_bps=np.mean(np.abs(np.log(Cl[s] / O[s]))) * 1e4,
                              last_hour_range_bps=np.mean(late_rng[s]), last_hour_share=np.mean(late_rng[s] / rng[s]),
                              abs_last60_bps=np.mean(np.abs(last60[s])) * 1e4,
                              corr_am_pm=np.corrcoef(am[s], pm[s])[0, 1],
                              n_bigam=int(bigam.sum()),
                              pm_after_big_am_bps=np.mean(pm[bigam] * np.sign(am[bigam])) * 1e4 if bigam.sum() else np.nan,
                              pm_reverses_pct=100 * np.mean(np.sign(pm[bigam]) == -np.sign(am[bigam])) if bigam.sum() else np.nan))
Fdf = pd.DataFrame(rowsF)
Fdf.to_csv(os.path.join(OUT, "F_expiry.csv"), index=False)
table(Fdf.set_index(["und", "per", "day"]), "F1 expiry vs normal by period", "{:.2f}")

# ------------------------------------------------------------------------------------------------ G: regime summary
say("\n## G. Regime summary (normal + expiry days pooled)")
rowsG = []
for u, X in P.items():
    o, h, l, c = X["o"], X["h"], X["l"], X["c"]
    O, Cl, H, L = o[:, 0], c[:, -1], h.max(1), l.min(1)
    rng = (H / L - 1) * 1e4
    fh = (h[:, :60].max(1) / l[:, :60].min(1) - 1) * 1e4
    for g in ["P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]:
        s = X["per"] == g
        if s.sum() < 20:
            continue
        r = X["absr"][s].mean(0)
        rowsG.append(dict(und=u, per=g, n=int(s.sum()), vix=np.nanmean(X["vix"][s]), range_bps=rng[s].mean(),
                          range_over_vixday=np.nanmean(rng[s] / (X["vix"][s] / np.sqrt(252) * 100)),
                          first_hour_share_of_range=np.mean(fh[s] / rng[s]),
                          open15_vs_midday=r[:15].mean() / r[165:225].mean(), close30_vs_midday=r[345:].mean() / r[165:225].mean(),
                          trend_days_pct=100 * np.mean(X["dtype"][s] == "trend")))
Gdf = pd.DataFrame(rowsG)
Gdf.to_csv(os.path.join(OUT, "G_regimes.csv"), index=False)
table(Gdf.set_index(["und", "per"]), "G1 regimes", "{:.2f}")
say("\ndone")
