"""R2 F4 (EIA crude Wed, EIA gas storage Thu, US CPI/NFP for gold & silver) and F7 (Asian-hours drift, overnight-cue
follow in the Indian morning) on US hourly bars (Yahoo 1h, 17 May 2024 -). Rs P&L = 1 mini lot at the MCX price level
of the previous day x the US return (MCX = US x USDINR). Usage: events.py design|holdout."""
import sys
import numpy as np, pandas as pd
from lib import *

MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2024-05-20"), HOLD_START) if MODE == "design" else (HOLD_START, HOLD_END + pd.Timedelta(days=1))
yh = yahoo("h")
def hourly(sym):
    y = yh[yh.sym == sym].set_index("ts").sort_index(); return y[~y.index.duplicated(keep="last")]
def px(y, t):
    i = y.index.searchsorted(t)
    if i < len(y) and y.index[i] == t: return y.open.iloc[i]
    if i > 0 and t - y.index[i - 1] <= pd.Timedelta(hours=2): return y.close.iloc[i - 1]
    return np.nan

CPI = pd.to_datetime("""2024-06-12 2024-07-11 2024-08-14 2024-09-11 2024-10-10 2024-11-13 2024-12-11 2025-01-15 2025-02-12
2025-03-12 2025-04-10 2025-05-13 2025-06-11 2025-07-15 2025-08-12 2025-09-11 2025-10-24 2025-12-18 2026-01-13 2026-02-13
2026-03-11 2026-04-10 2026-05-12 2026-06-10 2026-07-14 2026-08-12 2026-09-11""".split())
NFP = pd.to_datetime("""2024-06-07 2024-07-05 2024-08-02 2024-09-06 2024-10-04 2024-11-01 2024-12-06 2025-01-10 2025-02-07
2025-03-07 2025-04-04 2025-05-02 2025-06-06 2025-07-03 2025-08-01 2025-09-05 2025-11-20 2025-12-16 2026-01-09 2026-02-11
2026-03-06 2026-04-03 2026-05-08 2026-06-05 2026-07-02 2026-08-07 2026-09-04 2026-10-02""".split())

rows, trades = [], []
mcx = {c: load_mcx_daily(c) for c in COMS}
cl_days = us_daily("CL_F").index

def level(com, t):
    d = mcx[com]; i = d.index.searchsorted(t.normalize()) - 1
    return d.close.iloc[max(i, 0)]

def add(fam, rule, com, recs):
    if not recs:
        return
    R = pd.DataFrame(recs).dropna(subset=["ret", "side"])
    R = R[(R.side != 0) & (R.t >= LO) & (R.t < HI)]
    if len(R) == 0: rows.append(dict(fam=fam, rule=rule, com=com, n=0)); return
    lv = np.array([level(com, t) for t in R.t])
    g, n = fut_pnl(com, R.side.values, lv, lv * np.exp(R.ret.values))
    st = stats(R.side.values, R.ret.values, n, R.t.values)
    st.update(fam=fam, rule=rule, com=com, gross_tr=float(g.mean()), corr=float(np.corrcoef(R.sig, R.ret)[0, 1]) if len(R) > 3 else np.nan)
    rows.append(st)
    trades.append(pd.DataFrame(dict(fam=fam, rule=rule, com=com, date=R.t.values, side=R.side.values, ret=R.ret.values, gross=g, net=n)))

# ---------------- F4a EIA crude (Wed 10:30 ET; Thu if Mon-Wed holiday)
def weekly_surprise(path):
    x = pd.read_parquet(path).dropna().sort_values("date").set_index("date").v
    ch = x.diff()
    wk = ch.index.isocalendar().week.values
    avg = []
    for i, d in enumerate(ch.index):
        past = ch[(ch.index < d - pd.Timedelta(days=300)) & (ch.index >= d - pd.Timedelta(days=5 * 366 + 10))]
        pw = past.index.isocalendar().week.values
        sel = past[np.abs(pw - wk[i]) <= 1]
        avg.append(sel.mean() if len(sel) >= 5 else np.nan)
    return pd.DataFrame({"chg": ch, "surp": ch - np.array(avg)})

for com, path, wd, sign in (("CRUDE", D / "eia_WCESTUS1w.parquet", 2, -1), ("NATGAS", D / "eia_NW2_EPG0_SWO_R48_BCFw.parquet", 3, -1)):
    W = weekly_surprise(path)
    y = hourly(US_SYM[com])
    rec_f, rec_m, rec_pre = [], [], []
    for wend, r in W.iterrows():
        rel = wend + pd.Timedelta(days=(5 if com == "CRUDE" else 6))   # Fri week-end -> Wed (crude) / Thu (gas)
        mon = rel - pd.Timedelta(days=rel.weekday())
        if any((mon + pd.Timedelta(days=k)) not in cl_days for k in range(rel.weekday())):
            rel = rel + pd.Timedelta(days=1)
        t_rel = et_to_utc(rel, 10, 30)
        if t_rel < LO - pd.Timedelta(days=3) or t_rel > HI: continue
        t_bar = t_rel.floor("h"); t_in = t_bar + pd.Timedelta(hours=1); t_out = rel + pd.Timedelta(hours=18)
        p_pre, p_in, p_out, p_m60 = px(y, t_bar), px(y, t_in), px(y, t_out), px(y, t_bar - pd.Timedelta(hours=3))
        ret = np.log(p_out / p_in)
        rec_f.append(dict(t=t_in, sig=sign * r.surp, side=np.sign(sign * r.surp), ret=ret))       # fundamental surprise
        mv = np.log(p_in / p_pre)
        rec_m.append(dict(t=t_in, sig=mv, side=np.sign(mv), ret=ret))                           # follow release hour
        pre = np.log(p_pre / p_m60)
        rec_pre.append(dict(t=t_bar, sig=pre, side=np.sign(pre), ret=np.log(p_in / p_pre)))      # pre-release drift -> release hour
    add("F4", "eia_surprise_follow", com, rec_f)
    add("F4", "eia_releasehour_follow", com, rec_m)
    add("F4", "eia_predrift_follow", com, rec_pre)

# ---------------- F4b CPI / NFP for gold and silver (pooled 'macro')
for com in ("GOLD", "SILVER"):
    y = hourly(US_SYM[com]); rec = []
    for ev in sorted(set(CPI) | set(NFP)):
        t_rel = et_to_utc(ev, 8, 30); t_bar = t_rel.floor("h"); t_in = t_bar + pd.Timedelta(hours=1)
        t_out = ev + pd.Timedelta(hours=18)
        p0, p1, p2 = px(y, t_bar), px(y, t_in), px(y, t_out)
        mv = np.log(p1 / p0); rec.append(dict(t=t_in, sig=mv, side=np.sign(mv), ret=np.log(p2 / p1)))
    add("F4", "macro_cpi_nfp_follow", com, rec)

# ---------------- F7 Asian hours (09:30 -> 14:30 IST = 04:00 -> 09:00 UTC): unconditional long, follow US overnight
for com in COMS:
    y = hourly(US_SYM[com])
    days = pd.DatetimeIndex(sorted(set(y.index.normalize())))
    days = days[(days.weekday < 5) & (days >= LO) & (days < HI)]
    r_long, r_on = [], []
    for d in days:
        prevd = d - pd.Timedelta(days=3 if d.weekday() == 0 else 1)
        a, b, c = px(y, prevd + pd.Timedelta(hours=18)), px(y, d + pd.Timedelta(hours=4)), px(y, d + pd.Timedelta(hours=9))
        ret = np.log(c / b); on = np.log(b / a)
        r_long.append(dict(t=d + pd.Timedelta(hours=4), sig=1.0, side=1.0, ret=ret))
        r_on.append(dict(t=d + pd.Timedelta(hours=4), sig=on, side=np.sign(on), ret=ret))
    add("F7", "asia_long_0930_1430", com, r_long)
    add("F7", "asia_follow_overnight", com, r_on)

R = pd.DataFrame(rows); R.to_csv(OUT / f"events_{MODE}.csv", index=False)
if trades: pd.concat(trades).to_parquet(OUT / f"events_trades_{MODE}.parquet")
pd.set_option("display.width", 250)
print(R[["fam", "rule", "com", "n", "hit", "mean_bp", "t", "corr", "p_rand", "gross_tr", "net_tr", "net_tot", "maxdd", "mos_pos"]].round(3).to_string())
