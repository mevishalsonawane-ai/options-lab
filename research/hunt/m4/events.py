"""M4 amendment-3 tests: spread proxy, event breakouts (#6 NG storage, #7 CPI bullion), vol-gated straddle (#9).
python -I events.py spread CRUDEOIL NATURALGAS GOLDM SILVERM | events | straddle"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, "/home/user/options-lab/research/hunt/m4")
import json
import datetime as dt
import numpy as np
import pandas as pd
from build import load as raw_load, event_times, SP
from analyze import (W, RES, HOLD0, MULT, fees, rel_spread, load as ent_load, add_pnl, trades_for, summarize)
import analyze as A

CRUDE_ATM = 0.0028836


def spread_proxy(sym):
    L, S, _ = raw_load(sym)
    if sym == "CRUDEOIL":  # need high/low: crude cache has them
        C = SP / "strad_crude" / "cache"
        L = pd.concat([pd.read_parquet(C / f"c1_ATM_{s}.parquet", columns=["ts", "high", "low", "close", "volume"])
                       for s in ("CALL", "PUT")])
    else:
        L = pd.read_parquet(SP / "m3" / "raw" / f"opt_{sym}.parquet", columns=["ts", "k", "high", "low", "close", "volume"])
        L = L[L.k == 0]
    L = L[(L.ts < HOLD0.tz_localize("Asia/Kolkata")) & (L.volume > 0) & (L.close > 0)]
    tod = L.ts.dt.hour * 60 + L.ts.dt.minute
    ev = L[(tod >= 17 * 60 + 30) & (tod <= 23 * 60)]
    am = L[(tod >= 9 * 60 + 15) & (tod < 14 * 60)]
    f = lambda x: float(((x.high - x.low) / x.close).median())
    return dict(sym=sym, proxy_evening=f(ev), proxy_morning=f(am), n=len(ev))


def spreads(syms):
    out = [spread_proxy(s) for s in syms]
    base = [o for o in out if o["sym"] == "CRUDEOIL"][0]["proxy_evening"]
    for o in out:
        o["est_atm_spread"] = CRUDE_ATM * o["proxy_evening"] / base
        o["morning_ratio"] = o["proxy_morning"] / o["proxy_evening"]
    json.dump(out, open(RES / "spread_estimates.json", "w"), indent=1)
    print(pd.DataFrame(out).to_string())


def opt_series(sym):
    L, S, _ = raw_load(sym)
    L = L[L.close > 0]
    return L, S.sort_index()


def event_test(sym, kind, pseudo=False):
    L, S = opt_series(sym)
    M = pd.read_parquet(W / f"min_{sym}.parquet")
    days = sorted({d.date() for d in M.day})
    ev = event_times(days)[kind]
    F = M.F
    tod = M.tod
    piv = {cp: L[L.cp == cp].pivot_table(index="ts", columns="strike", values="close") for cp in (0, 1)}
    step = float(pd.Series(np.diff(np.sort(L.strike.unique()))).replace(0, np.nan).dropna().mode().iloc[0])
    rows = []
    allT = sorted(set(ev))
    if pseudo:  # same IST clock on non-event trading days (clock of the nearest real event)
        evd = {e.date() for e in allT}
        ps = []
        for d in days:
            if d in evd or d.weekday() > 4:
                continue
            near = min(allT, key=lambda e: abs((e.date() - d).days))
            ps.append(pd.Timestamp(dt.datetime.combine(d, near.time().replace(tzinfo=None)), tz="Asia/Kolkata"))
        realT = allT
        allT = ps
    for e in allT:
        for eoff in (5, 15):
            t_ref, t_in = e - pd.Timedelta(minutes=1), e + pd.Timedelta(minutes=eoff)
            if t_ref not in F.index or t_in not in F.index:
                continue
            m = np.log(F[t_in] / F[t_ref])
            # baseline: same clock window on the previous 20 non-event trading days
            hist = []
            for back in range(1, 40):
                d0 = e - pd.Timedelta(days=back)
                if any(abs((d0 - x).total_seconds()) < 3600 for x in (realT if pseudo else allT)):
                    continue
                a, b = d0 - pd.Timedelta(minutes=1), d0 + pd.Timedelta(minutes=eoff)
                if a in F.index and b in F.index:
                    hist.append(abs(np.log(F[b] / F[a])))
                if len(hist) >= 20:
                    break
            med = np.median(hist) if len(hist) >= 10 else np.nan
            K = round(F[t_in] / step) * step
            cp = 0 if m > 0 else 1
            for x in (15, 60):
                t_out = t_in + pd.Timedelta(minutes=x)
                p = piv[cp]
                row = p[(p.index >= t_in) & (p.index <= t_in + pd.Timedelta(minutes=2))]
                avail = [c for c in row.columns if row[c].notna().any()]
                if not avail:
                    continue
                K = min(avail, key=lambda c: abs(c - F[t_in]))  # nearest listed strike with a print
                s = p[K]
                p0 = s.reindex([t_in]).iloc[0]
                if not np.isfinite(p0):
                    s2 = s[(s.index > t_in) & (s.index <= t_in + pd.Timedelta(minutes=2))].dropna()
                    if not len(s2):
                        continue
                    p0 = s2.iloc[0]
                s1 = s[(s.index <= t_out) & (s.index > t_out - pd.Timedelta(minutes=5))].dropna()
                if not len(s1):
                    continue
                rows.append(dict(sym=sym, ts=t_in, day=pd.Timestamp(e.date()), eoff=eoff, x=x, m=m, med=med,
                                 ratio=abs(m) / med if med > 0 else np.nan, cp=cp, K=K, P0=p0, P1=s1.iloc[-1],
                                 tod=t_in.hour * 60 + t_in.minute, mny=0))
    R = pd.DataFrame(rows)
    mult = MULT[sym]
    R["gross"] = (R.P1 - R.P0) * mult
    R["net"] = R.gross - fees(R.P0 * mult, R.P1 * mult) - 0.5 * rel_spread(sym, R.mny.values, R.tod.values) * (
            R.P0 + R.P1) * mult
    R["hold"] = R.day >= HOLD0.tz_localize(None)
    out = []
    for (eoff, x), g in R.groupby(["eoff", "x"]):
        for k in (0, 1, 2):
            sel = g[g.ratio > k] if k else g
            for part, h in (("design", sel[~sel.hold]), ("holdout", sel[sel.hold])):
                out.append(dict(sym=sym, event=kind, entry=f"T+{eoff}", exit=f"{x}m", k=k if k else "none (baseline)",
                                part=part, days="non-event" if pseudo else "event", trades=len(h), hit=(h.net > 0).mean() if len(h) else np.nan,
                                gross_tr=h.gross.mean(), net_tr=h.net.mean(), net_tot=h.net.sum(),
                                prem_med=(h.P0 * mult).median()))
    return pd.DataFrame(out), R


def events():
    res, raw = [], []
    for sym, kind in (("NATURALGAS", "ngs"), ("GOLDM", "cpi"), ("SILVERM", "cpi"), ("CRUDEOIL", "eia")):
        for ps in (False, True):
            try:
                O, R = event_test(sym, kind, ps)
            except FileNotFoundError as ex:
                print("missing", sym, ex)
                continue
            res.append(O)
            raw.append(R.assign(pseudo=ps))
            print(O.round(2).to_string())
    pd.concat(res).to_csv(RES / "events_breakout.csv", index=False)
    pd.concat(raw).to_csv(RES / "events_breakout_trades.csv", index=False)


def straddle(syms):
    out = []
    for sym in syms:
        E = ent_load(sym)
        M = pd.read_parquet(W / f"min_{sym}.parquet")
        rv60 = (M.r ** 2).groupby(M.day).transform(lambda s: s.rolling(60, min_periods=30).mean())
        X = E[(E.k == 0) & (E.dte > 0) & E.h60_P.notna()]
        g, n, m = add_pnl(X, "h60", mult=MULT[sym])
        X = X.assign(gross=g, net=n)
        C, P = X[X.cp == 0].set_index("ts"), X[X.cp == 1].set_index("ts")
        J = C[["gross", "net", "day", "month", "hold", "tod", "iv0", "tau0", "N_rem", "P0"]].join(
            P[["gross", "net", "P0"]], rsuffix="_p", how="inner")
        J["gross"] += J.gross_p
        J["net"] += J.net_p
        J["rv"] = rv60.reindex(J.index).values
        J["impl"] = J.iv0.astype(float) ** 2 * J.tau0 / J.N_rem
        J["ratio"] = J.rv / J.impl
        thr = J[~J.hold].ratio.quantile(0.8)
        for nm, sel in (("always", J), ("gated rv/iv>=q80", J[J.ratio >= thr])):
            for part, h in (("design", sel[~sel.hold]), ("holdout", sel[sel.hold])):
                h = h.reset_index().rename(columns={"index": "ts"})
                T = trades_for(h, "h60", "E1")
                out.append(dict(sym=sym, rule=nm, part=part, trades=len(T), gross_tr=T.gross.mean(), net_tr=T.net.mean(),
                                net_tot=T.net.sum(), hit=(T.net > 0).mean(), green_months=f"{(T.groupby('month').net.sum() > 0).sum()}/{T.month.nunique()}"))
    O = pd.DataFrame(out)
    O.to_csv(RES / "straddle_gated.csv", index=False)
    print(O.round(1).to_string())


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "spread":
        spreads(sys.argv[2:])
    elif cmd == "events":
        events()
    elif cmd == "straddle":
        straddle(sys.argv[2:])


def roll_spread(sym):
    """Roll (1984) estimator on delta-adjusted 1-min ATM option price changes: s = 2*sqrt(-cov(u_t, u_t-1))."""
    if sym == "CRUDEOIL":
        C = SP / "strad_crude" / "cache"
        parts = []
        for s, cp in (("CALL", 0), ("PUT", 1)):
            d = pd.read_parquet(C / f"c1_ATM_{s}.parquet", columns=["ts", "strike", "close", "volume", "spot"])
            parts.append(d.assign(cp=cp))
        L = pd.concat(parts)
    else:
        L = pd.read_parquet(SP / "m3" / "raw" / f"opt_{sym}.parquet", columns=["ts", "k", "cp", "strike", "close", "volume"])
        L = L[L.k == 0]
        S = pd.read_parquet(SP / "m3" / "raw" / f"spot_{sym}.parquet").drop_duplicates("ts").set_index("ts").spot
        L["spot"] = S.reindex(L.ts).values
    L = L[L.ts < HOLD0.tz_localize("Asia/Kolkata")].sort_values(["cp", "ts"])
    out = {}
    for nm, lo, hi in (("evening", 17 * 60 + 30, 23 * 60), ("morning", 9 * 60 + 15, 14 * 60)):
        rs = []
        for cp, d in L.groupby("cp"):
            d = d.copy()
            d["tod"] = d.ts.dt.hour * 60 + d.ts.dt.minute
            dP, dF = d.close.diff(), d.spot.diff()
            gap = d.ts.diff() == pd.Timedelta(minutes=1)
            same = d.strike.diff() == 0
            u = dP - (0.5 if cp == 0 else -0.5) * dF
            ok = gap & same & (d.volume > 0) & (d.volume.shift() > 0) & d.tod.between(lo, hi)
            u = u.where(ok)
            day = d.ts.dt.date
            # covariance within day, pooled; relative to the median price
            x = pd.DataFrame({"u": u, "u1": u.groupby(day).shift(), "p": d.close}).dropna()
            c = np.cov(x.u, x.u1)[0, 1]
            rs.append(2 * np.sqrt(-c) / x.p.median() if c < 0 else 0.0)
        out[nm] = float(np.mean(rs))
    return out


if __name__ == "__main__" and sys.argv[1] == "roll":
    res = {s: roll_spread(s) for s in sys.argv[2:]}
    print(json.dumps(res, indent=1))
    json.dump(res, open(RES / "spread_roll.json", "w"), indent=1)
