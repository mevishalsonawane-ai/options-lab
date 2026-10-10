"""R2 C4/C5 US_LEAD (PREREG amendments 1-2): 5-min catch-up between the US benchmark minute (Dukascopy) and the MCX
futures minute (Dhan spot); enter next minute when |catch-up| > design q99.7 / q99, hold 15 min. usage: lead_cand.py design|holdout"""
import sys
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M
from lib import *
from optsim import opt_trade
MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
DLO, DHI = pd.Timestamp("2025-08-01"), HOLD_START
LO, HI = (DLO, DHI) if MODE == "design" else (HOLD_START, HOLD_END + pd.Timedelta(days=1))
SRC = {"GOLD": None, "SILVER": "XAGUSD", "CRUDE": "LIGHTCMDUSD", "NATGAS": "GASCMDUSD"}
rng = np.random.default_rng(5)
rows, trades = [], []
for com in COMS:
    if com == "GOLD":
        x = pd.read_csv(S / "xauusd_m1_bid.csv.gz"); u0 = pd.Series(x.close.values, index=pd.to_datetime(x.timestamp, unit="ms"))
    else:
        p = D / f"duka_{SRC[com]}.parquet"
        if not p.exists(): print("no data", com); continue
        u0 = pd.read_parquet(p).set_index("ts").close.sort_index()
    u0.index = u0.index + pd.Timedelta(hours=5, minutes=30)  # -> IST
    m = M.Market(MCX_SYM[com]); ts = pd.DatetimeIndex(m.ts)
    u = u0.reindex(ts).ffill(limit=2).values
    lf, lu = np.log(m.F), np.log(u)
    cont = np.r_[np.full(5, False), (ts[5:] - ts[:-5]) == pd.Timedelta(minutes=5)]
    cu = np.full(len(ts), np.nan); cu[5:] = (lu[5:] - lu[:-5]) - (lf[5:] - lf[:-5]); cu[~cont] = np.nan
    dwin = (ts >= DLO) & (ts < DHI)
    us_days = set(u0.index.normalize())
    cover = np.array([d in us_days for d in ts.normalize()])
    win = (ts >= LO) & (ts < HI) & cover
    for q in (0.997, 0.99):
        thr = np.nanquantile(np.abs(cu[dwin]), q)
        busy = -1; nm = f"US_LEAD_q{q}"
        for i in np.where(win & (np.abs(cu) > thr))[0]:
            e = i + 1
            if e <= busy or e + 15 >= len(ts) or m.day[e] != m.day[i]: continue
            xi = e + 15
            if m.day[xi] != m.day[e] or xi > m.day_cut[m.day[e]]: continue
            sd = int(np.sign(cu[i]))
            fg, fn = fut_pnl(com, sd, m.F[e], m.F[xi]); _, fr = fut_pnl(com, -sd, m.F[e], m.F[xi])
            trades.append(dict(com=com, rule=nm, inst="FUT", date=ts[e], side=sd, ret=np.log(m.F[xi] / m.F[e]), gross=fg, net=fn, rnd_net=fr, thr_bp=1e4 * thr))
            if not m.is_exp[e]:
                a, b = opt_trade(m, com, sd, e, xi, wait=2), opt_trade(m, com, -sd, e, xi, wait=2)
                if a is not None and b is not None:
                    trades.append(dict(com=com, rule=nm, inst="OPT", date=ts[e], side=sd, ret=np.log(m.F[xi] / m.F[e]), gross=a[0], net=a[1], rnd_net=b[1], thr_bp=1e4 * thr))
            busy = xi
    rows.append(dict(com=com, days_covered=int(len(set(ts[win].normalize())))))
T = pd.DataFrame(trades); T.to_parquet(OUT / f"lead_trades_{MODE}.parquet")
out = []
for (com, rule, inst), g in T.groupby(["com", "rule", "inst"]):
    net = g.net.values; n = len(g)
    sims = np.where(rng.random((4000, n)) < 0.5, g.net.values, g.rnd_net.values).mean(1)
    p = (np.sum(sims >= net.mean()) + 1) / 4001
    mos = g.groupby(pd.DatetimeIndex(g.date).to_period("M")).net.sum()
    eq = np.cumsum(net); dd = float(np.max(np.maximum.accumulate(np.r_[0, eq])[1:] - eq))
    nd = [r["days_covered"] for r in rows if r["com"] == com][0]
    out.append(dict(com=com, rule=rule, inst=inst, thr_bp=g.thr_bp.iloc[0], n=n, hit=float((g.side * g.ret > 0).mean()), win=float((net > 0).mean()),
                    gross_tr=g.gross.mean(), net_tr=net.mean(), net_tot=net.sum(), days=nd, net_day=net.sum() / max(nd, 1), maxdd=dd,
                    mos_pos=float((mos > 0).mean()), n_mos=len(mos), p_rand=p))
O = pd.DataFrame(out); O.to_csv(OUT / f"lead_{MODE}.csv", index=False)
print(pd.DataFrame(rows).to_string()); print(O.round(2).to_string())
