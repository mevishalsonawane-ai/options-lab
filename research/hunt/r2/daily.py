"""R2 daily families F1 (overnight cue -> MCX open->close), F5 (slow macro / COT -> 20 days), F6 (US holidays),
F8 (FOMC gaps). Usage: daily.py design|holdout. Holdout mode refuses unless holdout.py created the lock."""
import sys
import numpy as np, pandas as pd
from lib import *

MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout":
    assert (R2 / "holdout.lock").exists(), "holdout not opened"
LO, HI = (pd.Timestamp("2012-01-01"), HOLD_START - pd.Timedelta(days=1)) if MODE == "design" else (HOLD_START, HOLD_END)

FOMC = pd.to_datetime("""2021-01-27 2021-03-17 2021-04-28 2021-06-16 2021-07-28 2021-09-22 2021-11-03 2021-12-15
2022-01-26 2022-03-16 2022-05-04 2022-06-15 2022-07-27 2022-09-21 2022-11-02 2022-12-14 2023-02-01 2023-03-22 2023-05-03
2023-06-14 2023-07-26 2023-09-20 2023-11-01 2023-12-13 2024-01-31 2024-03-20 2024-05-01 2024-06-12 2024-07-31 2024-09-18
2024-11-07 2024-12-18 2025-01-29 2025-03-19 2025-05-07 2025-06-18 2025-07-30 2025-09-17 2025-10-29 2025-12-10 2026-01-28
2026-03-18 2026-04-29 2026-06-17 2026-07-29 2026-09-16""".split())

rows, trades = [], []


def cell(fam, name, com, sig_side, df, horizon_ret, p_in, p_out):
    m = np.isfinite(sig_side) & (sig_side != 0) & np.isfinite(horizon_ret)
    side = np.sign(sig_side[m]); dts = df.index[m]
    g, n = fut_pnl(com, side, p_in[m], p_out[m])
    st = stats(side, horizon_ret[m], n, dts)
    st.update(fam=fam, rule=name, com=com, gross_tr=float(np.mean(g)) if len(g) else np.nan,
              days=int(len(df)), net_day=float(np.sum(n) / max(len(df), 1)))
    rows.append(st)
    trades.append(pd.DataFrame(dict(fam=fam, rule=name, com=com, date=dts, side=side, ret=horizon_ret[m], gross=g, net=n)))


panel = {}
for com in COMS:
    df = load_mcx_daily(com)
    df = df[(df.index >= LO - pd.Timedelta(days=400)) & (df.index <= HI)]
    days = df.index
    us = prior_us(US_SYM[com], days)
    es = prior_us("ES_F", days)
    dxy = prior_us("DX_Y_NYB", days)
    tnx = prior_us("_TNX", days, kind="diff")
    inr = prior_us("INR_X", days)
    df["us"], df["es"], df["dxy"], df["tnx"], df["inr"] = us.values, es.values, dxy.values, tnx.values, inr.values
    panel[com] = df
    d = df[(df.index >= LO)]
    O, C, oc = d.open.values, d.close.values, d.oc.values
    z = lambda x: (x - x.rolling(250, min_periods=60).mean().shift()) / x.rolling(250, min_periods=60).std().shift()
    sigs = {"gap": d.gap, "us": d.us, "es": d.es, "inr": d.inr}
    if com in ("GOLD", "SILVER"):
        sigs["dxy_rev"] = -d.dxy; sigs["tnx_rev"] = -d.tnx
    for k, s in sigs.items():
        s = s.values.astype(float)
        cell("F1", f"follow_{k}", com, np.sign(s), d, oc, O, C)
        zz = z(pd.Series(s, index=d.index)).values
        cell("F1", f"follow_{k}_z1", com, np.where(np.abs(zz) > 1, np.sign(s), 0), d, oc, O, C)
    # effect sizes (descriptive)
    e = d[["gap", "oc", "us", "es"]].dropna()
    rows.append(dict(fam="EFF", rule="corr", com=com, n=len(e), corr_us_gap=e.us.corr(e.gap), corr_us_oc=e.us.corr(e.oc),
                     corr_gap_oc=e.gap.corr(e.oc), corr_es_oc=e.es.corr(e.oc), corr_es_gap=e.es.corr(e.gap)))

    # F5 slow macro: 20-day blocks, enter open of D, exit close of D+19
    idx = np.arange(len(d))[::20]
    ent = idx[idx + 19 < len(d)]
    p_in, p_out = O[ent], C[ent + 19]
    hret = np.log(p_out / p_in) - np.array([np.nansum(np.nan_to_num(d.gap.values[i + 1:i + 20])) * 0 for i in ent])
    # roll-safe 20-day return: sum of daily oc + non-roll gaps
    cum = np.array([d.oc.values[i] + np.nansum(d.ret.values[i + 1:i + 20]) for i in ent])
    # Rs P&L uses the roll-safe return applied to the entry price
    p_out_adj = p_in * np.exp(cum)
    dd_ = d.iloc[ent]
    fred = pd.read_parquet(D / "fred.parquet")
    def fred_chg(fid, lagdays=2, look=20):
        f = fred[fred.id == fid].set_index("date").v.sort_index()
        ch = f - f.shift(look)
        ix = np.searchsorted(ch.index.values, (dd_.index - pd.Timedelta(days=lagdays)).values, side="right") - 1
        return np.where(ix >= 0, ch.values[np.clip(ix, 0, None)], np.nan)
    sub = d.iloc[ent].copy(); sub.index = d.index[ent]
    if com in ("GOLD", "SILVER"):
        cell("F5", "dfii10_20d_rev", com, -np.sign(fred_chg("DFII10")), sub, cum, p_in, p_out_adj)
        cell("F5", "dtwex_20d_rev", com, -np.sign(fred_chg("DTWEXBGS")), sub, cum, p_in, p_out_adj)
    # COT contrarian: managed money net % OI, 156-week percentile
    cot = pd.read_parquet(D / "cot.parquet")
    cc = cot[cot.code == {"CRUDE": "CL", "NATGAS": "NG", "GOLD": "GC", "SILVER": "SI"}[com]].set_index("date").sort_index()
    net = (cc.mm_long - cc.mm_short) / cc.oi
    pct = net.rolling(156, min_periods=52).apply(lambda x: (x[:-1] < x[-1]).mean(), raw=True)
    avail = pct.copy(); avail.index = avail.index + pd.Timedelta(days=4)  # Tue report -> usable Sat, i.e. Monday MCX
    ix = np.searchsorted(avail.index.values, sub.index.values, side="right") - 1
    pv = np.where(ix >= 0, avail.values[np.clip(ix, 0, None)], np.nan)
    cell("F5", "cot_contra_90_10", com, np.where(pv > 0.9, -1, np.where(pv < 0.1, 1, 0)).astype(float), sub, cum, p_in, p_out_adj)
    cell("F5", "cot_follow_sign", com, np.where(pv > 0.5, 1, -1).astype(float) * np.isfinite(pv), sub, cum, p_in, p_out_adj)

    # F6 US holidays (weekday with no CL=F daily bar but an MCX session)
    usd = us_daily("CL_F").index
    wk = d.index[d.index.weekday < 5]
    hol = wk[~wk.isin(usd) & (wk > usd.min()) & (wk < usd.max())]
    rows.append(dict(fam="F6", rule="us_holiday_range", com=com, n=len(hol), rng_hol=d.loc[hol, "rng"].median(),
                     rng_all=d.rng.median(), absoc_hol=d.loc[hol, "oc"].abs().median(), absoc_all=d.oc.abs().median(),
                     oc_mean_hol_bp=1e4 * d.loc[hol, "oc"].mean()))
    # F8 FOMC: next MCX day's |gap|
    nxt = [d.index[d.index > f][0] for f in FOMC if (d.index > f).any() and f >= d.index[0]]
    nxt = pd.DatetimeIndex(nxt)
    nxt = nxt[nxt.isin(d.index)]
    g = d.gap.abs()
    rows.append(dict(fam="F8", rule="fomc_next_gap", com=com, n=len(nxt), absgap_fomc=g.loc[nxt].median(),
                     absgap_all=g[d.index >= "2021-01-01"].median(), absgap_fomc_mean=g.loc[nxt].mean(),
                     absgap_all_mean=g[d.index >= "2021-01-01"].mean(), oc_fomc_abs=d.loc[nxt, "oc"].abs().median()))

R = pd.DataFrame(rows)
T = pd.concat(trades)
R.to_csv(OUT / f"daily_{MODE}.csv", index=False); T.to_parquet(OUT / f"daily_trades_{MODE}.parquet")
pd.set_option("display.width", 250); pd.set_option("display.max_columns", 40); pd.set_option("display.max_rows", 300)
c = ["fam", "rule", "com", "n", "hit", "mean_bp", "t", "p_rand", "gross_tr", "net_tr", "net_day", "maxdd", "yrs_pos", "mos_pos"]
print(R[R.fam.isin(["F1", "F5"])][c].round(3).to_string())
print(R[R.fam == "EFF"].dropna(axis=1).round(3).to_string())
print(R[R.fam.isin(["F6", "F8"])].dropna(axis=1, how="all").drop(columns=[x for x in c[3:] if x in R and x != "n"], errors="ignore").round(4).to_string())
