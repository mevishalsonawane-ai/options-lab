"""M2 option-buying implementation (c). Black-76 modelled prices (label: MODELLED) with IV = vol20 x ratio.
Contracts: CRUDEOIL (100 bbl), NATGASMINI (250 mmBtu), GOLDM (100 g, price/10 g), SILVERM (5 kg)."""
import numpy as np, pandas as pd, glob, os
from scipy.stats import norm
import engine as E
OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2"
# name -> (price multiplier from panel price, pv Rs per price unit per lot, strike step, rt spread fraction, Dhan rolling sym)
OSPEC = {"CRUDE": dict(mult=1, pv=100, step=50, spr=0.005, dsym="CRUDEOIL", contract="CRUDEOIL opt (100 bbl)"),
         "NATGAS": dict(mult=1, pv=250, step=5, spr=0.015, dsym="NATGASMINI", contract="NATGASMINI opt (250 mmBtu)"),
         "GOLD": dict(mult=10, pv=10, step=500, spr=0.015, dsym="GOLDM", contract="GOLDM opt (100 g)"),
         "SILVER": dict(mult=1, pv=5, step=1000, spr=0.015, dsym="SILVERM", contract="SILVERM opt (5 kg)")}
R_RATE = 0.065

def b76(F, K, T, sig, call):
    T = max(T, 1e-6); sig = max(sig, 1e-4)
    if F <= 0: return max(K - F, 0.0) if not call else 0.0
    d1 = (np.log(F / K) + 0.5 * sig * sig * T) / (sig * np.sqrt(T)); d2 = d1 - sig * np.sqrt(T)
    df = np.exp(-R_RATE * T)
    return df * (F * norm.cdf(d1) - K * norm.cdf(d2)) if call else df * (K * norm.cdf(-d2) - F * norm.cdf(-d1))

def opt_charges(prem_rs, buy):
    brok = 20.0; txn = 0.000418 * prem_rs; sebi = 1e-6 * prem_rs
    gst = 0.18 * (brok + txn + sebi)
    return brok + txn + sebi + gst + (0.00003 * prem_rs if buy else 0.0005 * prem_rs)

def calibrate(data):
    """IV/RV ratio per commodity from Dhan rolling ATM near-month IV (last bar each day) vs 20d realised vol."""
    out = {}
    for n, o in OSPEC.items():
        fs = glob.glob(f"{OUT}/data/opts/{o['dsym']}_c1_+0_C.parquet")
        if not fs and n == "CRUDE":
            fs = [f"{OUT}/../strad_crude/cache/c1_ATM_CALL.parquet"]
        if not fs: out[n] = None; continue
        d = pd.read_parquet(fs[0]); d["ts"] = pd.to_datetime(d.ts)
        if d.ts.dt.tz is not None: d["ts"] = d.ts.dt.tz_localize(None)
        d = d[d.iv > 1]
        iv = d.groupby(d.ts.dt.normalize()).iv.last() / 100.0
        g = data[n]; rv = (g.ret.rolling(20).std() * np.sqrt(252)).reindex(iv.index)
        ratio = (iv / rv).replace([np.inf, -np.inf], np.nan).dropna()
        out[n] = dict(ratio=float(ratio.median()), q25=float(ratio.quantile(.25)), q75=float(ratio.quantile(.75)),
                      days=int(len(ratio)), iv_med=float(iv.median()), rv_med=float(rv.median()))
    return out

def expiries(g, n):
    """Synthetic monthly option expiries: last trading day before each detected roll (crude/natgas) else last trading day of month."""
    idx = g.index
    if n in ("CRUDE", "NATGAS"):
        r = np.flatnonzero(g.roll.values)
        e = [idx[i - 1] for i in r if i > 0]
    else:
        s = pd.Series(idx, index=idx); e = list(idx[(s.dt.month != s.shift(-1).dt.month).values])
    return pd.DatetimeIndex(e)

def run_options(data, sigs, ratio, start, end, iv_scale=1.0, spr_scale=1.0, real_iv=None, min_dte=8, roll_dte=6):
    """sigs: dict name->target series. One lot per name. Premium tied up <= equity. Returns daily DataFrame."""
    names = list(sigs)
    idx = pd.DatetimeIndex(sorted(set().union(*[data[n].index for n in names])))
    idx = idx[(idx >= start) & (idx <= end)]
    C = {}
    for n in names:
        g = data[n].reindex(idx); o = OSPEC[n]
        v = (data[n].ret.rolling(20).std() * np.sqrt(252)).reindex(idx).ffill().values
        iv = v * ratio[n] * iv_scale
        if real_iv is not None and n in real_iv:
            r = real_iv[n].reindex(idx).values
            iv = np.where(np.isnan(r), iv, r)
        C[n] = dict(o=g.open.values * o["mult"], c=g.close.values * o["mult"], cl=(g.close.ffill().values * o["mult"]),
                    has=g.open.notna().values, s=sigs[n].reindex(idx).ffill().fillna(0.0).values, iv=iv,
                    exp=expiries(data[n], n), spec=o)
    eq = E.CAP0; pos = {n: None for n in names}
    T = len(idx); pnl = np.zeros(T); cost = np.zeros(T); prem = np.zeros(T); ntr = np.zeros(T); held = np.zeros(T)
    def price(n, t, F, p):
        tau = (p["exp"] - idx[t]).days / 365.0
        return b76(F, p["K"], tau, C[n]["iv"][t], p["call"]) if tau > 0 else max((F - p["K"]) if p["call"] else (p["K"] - F), 0.0)
    for t in range(1, T):
        dp = 0.0; dc = 0.0
        for n in names:
            k = C[n]; o = k["spec"]
            if not k["has"][t]: continue
            want = k["s"][t - 1]; p = pos[n]; F0 = k["o"][t]
            # mark from previous close to today's open
            if p is not None:
                v_open = price(n, t, F0, p)
                dp += (v_open - p["mark"]) * o["pv"]; p["mark"] = v_open
                dte = (p["exp"] - idx[t]).days
                exit_ = (want == 0) or ((want > 0) != p["call"]) or dte <= roll_dte
                if exit_:
                    sell = v_open * (1 - o["spr"] * spr_scale / 2)
                    dp += (sell - v_open) * o["pv"]; dc += opt_charges(sell * o["pv"], False)
                    pos[n] = None; p = None
            if p is None and want != 0 and not np.isnan(F0) and k["iv"][t] > 0:
                exps = k["exp"][k["exp"] >= idx[t] + pd.Timedelta(days=min_dte)]
                if len(exps):
                    call = want > 0
                    atm = round(F0 / o["step"]) * o["step"]
                    K = atm - o["step"] if call else atm + o["step"]
                    q = dict(K=K, call=call, exp=exps[0])
                    v = price(n, t, F0, q)
                    buy = v * (1 + o["spr"] * spr_scale / 2)
                    tied = sum(pos[m]["mark"] * C[m]["spec"]["pv"] for m in names if pos[m] is not None)
                    if buy * o["pv"] + tied <= eq and v > 0:
                        q["mark"] = v; pos[n] = q
                        dp += (v - buy) * o["pv"]; dc += opt_charges(buy * o["pv"], True); ntr[t] += 1
            p = pos[n]
            if p is not None:
                v_close = price(n, t, k["cl"][t], p)
                dp += (v_close - p["mark"]) * o["pv"]; p["mark"] = v_close
        pnl[t] = dp - dc; cost[t] = dc; eq += pnl[t]
        prem[t] = sum(pos[m]["mark"] * C[m]["spec"]["pv"] for m in names if pos[m] is not None)
        held[t] = sum(pos[m] is not None for m in names)
        if eq < 25000:
            break
    return pd.DataFrame(dict(pnl=pnl, cost=cost, margin=prem, trades=ntr, held=held), index=idx)
