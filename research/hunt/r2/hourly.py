"""R2 F2: US overnight move (MCX close -> 08:30 IST) and the MCX gap residual vs the MCX day, using Yahoo 1h
(17 May 2024 -) and Dukascopy XAUUSD 1m (Oct 2023 -) for gold. Also whether the US contract itself shows
overnight->day continuation (global effect) or only MCX does (MCX lag). Usage: hourly.py design|holdout."""
import sys
import numpy as np, pandas as pd
from lib import *

MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout":
    assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2023-10-02"), HOLD_START - pd.Timedelta(days=1)) if MODE == "design" else (HOLD_START, HOLD_END)

yh = yahoo("h")


def us_hourly(sym):
    y = yh[yh.sym == sym].set_index("ts").sort_index()
    return y[~y.index.duplicated(keep="last")]


def px_at(y, t, col="open"):
    """price at UTC time t: open of the bar starting at t, else close of the last bar starting before t (<=2h old)."""
    i = y.index.searchsorted(t)
    if i < len(y) and y.index[i] == t:
        return y[col].iloc[i]
    if i > 0 and t - y.index[i - 1] <= pd.Timedelta(hours=2):
        return y["close"].iloc[i - 1]
    return np.nan


inr = us_hourly("INR_X")
rows, out, cells = [], [], []
xau = None
if True:
    x = pd.read_csv(S / "xauusd_m1_bid.csv.gz")
    x.index = pd.to_datetime(x.timestamp, unit="ms"); xau = x.close
for com in COMS:
    df = load_mcx_daily(com)
    y = us_hourly(US_SYM[com])
    d = df[(df.index >= max(LO, y.index.min().normalize() + pd.Timedelta(days=2))) & (df.index <= HI)]
    rec = []
    prev = None
    for D in d.index:
        if prev is None:
            prev = D; continue
        cl_prev = et_to_utc(prev, 13, 55) + pd.Timedelta(minutes=5) if False else None
        # MCX close in UTC: 23:30 IST (US summer) = 18:00 UTC; 23:55 IST (US winter) = 18:25 UTC -> use 18:00 bar
        t_c_prev = prev + pd.Timedelta(hours=18)
        t_pre = D + pd.Timedelta(hours=3)          # 08:30 IST
        t_open = D + pd.Timedelta(hours=3, minutes=30)  # 09:00 IST
        t_c = D + pd.Timedelta(hours=18)
        a, b, c = px_at(y, t_c_prev), px_at(y, t_pre), px_at(y, t_c)
        i0, i1 = px_at(inr, t_c_prev), px_at(inr, t_pre)
        r = dict(date=D, us_on=np.log(b / a), us_day=np.log(c / b), inr_on=np.log(i1 / i0) if i0 > 0 else np.nan,
                 gap=d.gap.get(D), oc=d.oc.get(D))
        if com == "GOLD" and xau is not None:
            def xp(t):
                j = xau.index.searchsorted(t, side="right") - 1
                return xau.iloc[j] if j >= 0 and t - xau.index[j] < pd.Timedelta(minutes=10) else np.nan
            xa, xo, xc = xp(t_c_prev), xp(t_open), xp(t_c)
            r.update(x_on=np.log(xo / xa), x_day=np.log(xc / xo))
        rec.append(r); prev = D
    R = pd.DataFrame(rec).set_index("date")
    R["fair_gap"] = R.us_on + R.inr_on.fillna(0)
    R["res"] = R.gap - R.fair_gap
    R["com"] = com
    out.append(R)
    e = R.dropna(subset=["us_on", "us_day", "gap", "oc"])
    row = dict(com=com, n=len(e), corr_uson_gap=e.us_on.corr(e.gap), corr_uson_oc=e.us_on.corr(e.oc),
               corr_gap_oc=e.gap.corr(e.oc), corr_res_oc=e.res.corr(e.oc), corr_uson_usday=e.us_on.corr(e.us_day),
               corr_gap_usday=e.gap.corr(e.us_day), corr_usday_oc=e.us_day.corr(e.oc),
               hit_gap_oc=np.mean(np.sign(e.gap) == np.sign(e.oc)), hit_uson_usday=np.mean(np.sign(e.us_on) == np.sign(e.us_day)),
               sd_res_bp=1e4 * e.res.std(), sd_gap_bp=1e4 * e.gap.std())
    if "x_on" in e:
        ee = e.dropna(subset=["x_on", "x_day"])
        row.update(n_x=len(ee), corr_xon_xday=ee.x_on.corr(ee.x_day), corr_gap_xday=ee.gap.corr(ee.x_day),
                   corr_xday_oc=ee.x_day.corr(ee.oc), corr_xon_gap=ee.x_on.corr(ee.gap))
    rows.append(row)
    # F2 trade cells on MCX daily open->close (1 mini lot), design gate input
    ee = e.copy(); d0 = df.loc[ee.index]
    thr = ee.res.abs().expanding(20).median().shift()
    for nm, sd in (("follow_us_overnight", np.sign(ee.us_on)), ("fade_gap_residual", np.where(ee.res.abs() > thr, -np.sign(ee.res), 0))):
        sd = np.asarray(sd, float)
        g, n = fut_pnl(com, sd, d0.open.values, d0.close.values)
        n = np.where(sd != 0, n, np.nan); g = np.where(sd != 0, g, np.nan)
        m = sd != 0
        st = stats(sd[m], ee.oc.values[m], n[m], ee.index.values[m]); st.update(fam="F2", rule=nm, com=com, gross_tr=float(np.nanmean(g)))
        cells.append(st)
pd.set_option("display.width", 250); pd.set_option("display.max_columns", 40)
T = pd.DataFrame(rows); print(T.round(3).T.to_string())
pd.concat(out).to_parquet(OUT / f"hourly_{MODE}.parquet")
T.to_csv(OUT / f"hourly_eff_{MODE}.csv", index=False)
C = pd.DataFrame(cells); C.to_csv(OUT / f"hourly_cells_{MODE}.csv", index=False)
print(C[["rule","com","n","hit","mean_bp","t","p_rand","gross_tr","net_tr","maxdd","mos_pos"]].round(3).to_string())
