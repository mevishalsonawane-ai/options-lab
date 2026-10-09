"""R2 frozen candidates C1-C4 (PREREG amendment 1) on MCX minute data: mini futures and 1-ITM near-month option buys.
usage: cand.py design|holdout   (design = 5 Aug - 30 Sep 2025, the only pre-holdout MCX minutes)."""
import sys
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M
from lib import *

MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2025-08-01"), HOLD_START) if MODE == "design" else (HOLD_START, HOLD_END + pd.Timedelta(days=1))
HR = pd.read_parquet(OUT / f"hourly_{MODE}.parquet")
THR_RES = {"CRUDE": 12e-4, "NATGAS": 22e-4, "GOLD": 11e-4, "SILVER": 14e-4}
rng = np.random.default_rng(20261009)


def opt_trade(m, com, side, e, x_end, stop=None, wait=30):
    """Buy 1-ITM option (call side>0, put side<0) at first printed minute in [e, e+5]; exit at x_end (minute index) or
    -30% stop on the 1-min low. Returns gross, net Rs or None."""
    cp = 0 if side > 0 else 1
    k = m.itm[cp][e - 1]
    if not np.isfinite(k) or x_end <= e: return None
    o, h, lo, c, v = m.leg(cp, k, e, x_end + 10)
    pr = np.isfinite(c) & (v > 0)
    ins = np.where(pr[:wait + 1])[0]
    if not len(ins): return None
    i0 = ins[0]; fill = o[i0]
    if not (fill > 0): return None
    n_end = x_end - e
    px_out = None
    if stop is not None:
        sp = fill * (1 - stop)
        hit = np.where(pr[i0:n_end + 1] & (lo[i0:n_end + 1] <= sp))[0]
        if len(hit):
            j = i0 + hit[0]; px_out = min(o[j], sp) if j > i0 else sp; t_out = j
    if px_out is None:
        after = np.where(pr[n_end:])[0]
        if len(after):
            px_out = c[n_end + after[0]] if after[0] == 0 else o[n_end + after[0]]
        else:
            prev = np.where(pr[:n_end])[0]
            if not len(prev): return None
            px_out = c[prev[-1]]
        t_out = n_end
    mult = OPT_MULT[com]
    tod_in = m.tod[e + i0]; tod_out = m.tod[min(e + t_out, len(m.tod) - 1)]
    s_in = OPT_SPREAD[com][0 if tod_in >= 17 * 60 else 1]; s_out = OPT_SPREAD[com][0 if tod_out >= 17 * 60 else 1]
    b, s = fill * mult, px_out * mult
    gross = s - b
    net = gross - M.opt_charges(b, s) - 0.5 * s_in * b - 0.5 * s_out * s
    return gross, net, b


rows, trades = [], []
for com in COMS:
    m = M.Market(MCX_SYM[com])
    ts = pd.DatetimeIndex(m.ts)
    hr = HR[HR.com == com]
    days = [pd.Timestamp(d) for d in m.days if LO <= pd.Timestamp(d) < HI]
    recs = []
    for D in days:
        d64 = D.to_datetime64()
        ix = np.where(m.day == d64)[0]
        if len(ix) < 200: continue
        prev_ix = np.where(m.day < d64)[0]
        if not len(prev_ix): continue
        pc = prev_ix[-1]
        e = ix[np.searchsorted(m.tod[ix], 9 * 60 + 5)] if (m.tod[ix] >= 9 * 60 + 5).any() else None
        if e is None or m.tod[e] > 9 * 60 + 15 or m.seg[e] != m.seg[pc]: continue
        p_in, p_prev = m.F[e], m.F[pc]
        gap = np.log(p_in / p_prev)
        fair = np.nan
        if D in hr.index and np.isfinite(hr.loc[D, "us_on"]):
            fair = hr.loc[D, "us_on"] + (hr.loc[D, "inr_on"] if np.isfinite(hr.loc[D, "inr_on"]) else 0.0)
        res = gap - fair
        x14 = ix[np.searchsorted(m.tod[ix], 14 * 60, side="right") - 1]
        xe = m.day_cut[d64]
        opt_ok = not m.is_exp[e] and not (m.is_exp[min(ix[-1] + 1, len(m.ts) - 1)] and m.day[min(ix[-1] + 1, len(m.ts) - 1)] != d64)
        sigs = {"C1_follow_gap": np.sign(gap), "C2_follow_us": np.sign(fair) if np.isfinite(fair) else 0.0,
                "C3_fade_res": (-np.sign(res) if np.isfinite(res) and abs(res) > THR_RES[com] else 0.0)}
        for xn, xi in (("X14", x14), ("XEOD", xe)):
            # futures, both sides precomputed for the random baseline
            fr = np.log(m.F[xi] / p_in)
            fg, fn = {}, {}
            for sd in (1, -1):
                fg[sd], fn[sd] = fut_pnl(com, sd, p_in, m.F[xi])
            og, on_ = {}, {}
            for stop in (None, 0.30):
                for sd in (1, -1):
                    r = opt_trade(m, com, sd, e, xi, stop) if opt_ok else None
                    og[(stop, sd)] = r
            for sn, sd in sigs.items():
                if sd == 0: continue
                sd = int(sd)
                recs.append(dict(date=D, rule=sn, exit=xn, inst="FUT", side=sd, ret=fr, gross=fg[sd], net=fn[sd],
                                 rnd_net=fn[-sd], prem=np.nan))
                for stop in (None, 0.30):
                    a, b = og[(stop, sd)], og[(stop, -sd)]
                    if a is None or b is None: continue
                    recs.append(dict(date=D, rule=sn, exit=xn + ("" if stop is None else "_s30"), inst="OPT", side=sd, ret=fr,
                                     gross=a[0], net=a[1], rnd_net=b[1], prem=a[2]))
    R = pd.DataFrame(recs); R["com"] = com
    trades.append(R)
    print(com, "days", len(days), "trades", len(R), flush=True)

# ---- C4 XAU lead (gold only)
x = pd.read_csv(S / "xauusd_m1_bid.csv.gz"); x.index = pd.to_datetime(x.timestamp, unit="ms") + pd.Timedelta(hours=5, minutes=30)
m = M.Market("GOLDM")
ts = pd.DatetimeIndex(m.ts)
u = x.close.reindex(ts).ffill(limit=2).values
lf, lu = np.log(m.F), np.log(u)
cont5 = np.r_[np.full(5, False), (ts[5:] - ts[:-5]) == pd.Timedelta(minutes=5)]
cu = np.full(len(ts), np.nan); cu[5:] = (lu[5:] - lu[:-5]) - (lf[5:] - lf[:-5]); cu[~cont5] = np.nan
sel_win = (ts >= LO) & (ts < HI)
c4 = []
for thr, nm in ((23.8e-4, "C4_xau_lead_q997"), (15.3e-4, "C4_xau_lead_q99")):
    busy = -1
    for i in np.where(sel_win & (np.abs(cu) > thr))[0]:
        e = i + 1
        if e <= busy or e + 15 >= len(ts) or m.day[e] != m.day[i]: continue
        xi = e + 15
        if m.day[xi] != m.day[e] or xi > m.day_cut[m.day[e]]: continue
        sd = int(np.sign(cu[i]))
        fg, fn = fut_pnl("GOLD", sd, m.F[e], m.F[xi]); _, fr_ = fut_pnl("GOLD", -sd, m.F[e], m.F[xi])
        c4.append(dict(date=ts[e], rule=nm, exit="15m", inst="FUT", side=sd, ret=np.log(m.F[xi] / m.F[e]), gross=fg, net=fn, rnd_net=fr_, prem=np.nan, com="GOLD"))
        if not m.is_exp[e]:
            a, b = opt_trade(m, "GOLD", sd, e, xi, wait=2), opt_trade(m, "GOLD", -sd, e, xi, wait=2)
            if a is not None and b is not None:
                c4.append(dict(date=ts[e], rule=nm, exit="15m", inst="OPT", side=sd, ret=np.log(m.F[xi] / m.F[e]), gross=a[0], net=a[1], rnd_net=b[1], prem=a[2], com="GOLD"))
        busy = xi
trades.append(pd.DataFrame(c4))
T = pd.concat(trades, ignore_index=True)
T.to_parquet(OUT / f"cand_trades_{MODE}.parquet")

out = []
for (com, rule, ex, inst), g in T.groupby(["com", "rule", "exit", "inst"]):
    g = g.sort_values("date")
    n = len(g); net = g.net.values
    # random side: per trade choose the opposite side with prob 1/2 (same entries, same exits)
    sims = np.where(rng.random((4000, n)) < 0.5, g.net.values, g.rnd_net.values).mean(1)
    p = (np.sum(sims >= net.mean()) + 1) / 4001
    mos = g.groupby(pd.DatetimeIndex(g.date).to_period("M")).net.sum()
    eq = np.cumsum(net); dd = float(np.max(np.maximum.accumulate(np.r_[0, eq])[1:] - eq))
    ndays = T[(T.com == com)].date.dt.normalize().nunique() if rule != "" else n
    out.append(dict(com=com, rule=rule, exit=ex, inst=inst, n=n, hit=float((g.side * g.ret > 0).mean()), win=float((net > 0).mean()),
                    gross_tr=g.gross.mean(), net_tr=net.mean(), net_tot=net.sum(), net_day=net.sum() / max(ndays, 1),
                    maxdd=dd, mos_pos=float((mos > 0).mean()), n_mos=len(mos), p_rand=p, med_prem=g.prem.median()))
O = pd.DataFrame(out)
O.to_csv(OUT / f"cand_{MODE}.csv", index=False)
pd.set_option("display.width", 250); pd.set_option("display.max_rows", 300)
print(O.round(3).to_string())
