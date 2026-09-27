"""BTC strategy suite from the research (docs/market_strategies_research.md), with the extra data.

  A. Trend with volatility targeting (long/flat and long/short), optional crowding and term-structure filters
  B. Weekly ATM straddles: sell with the research filters (DVOL above realised vol, term structure not
     inverted, alarm quiet), unhedged and band-hedged; plus the "switch" (buy when an alarm is high)
  C. Funding carry: long spot + short perpetual when funding is rich
  D. Neural networks with the extra inputs: do they call direction or volatility better than before?
  E. Portfolio of the parts, weights set on the development year

Every choice is made on the development year; the last 3 months are only reported. Writes
crypto/results/suite.json. Run after strategy.py (reuses its cached alarms).
"""
import json
import math
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.evaluate import auc  # noqa: E402
from marketlab.features import base_features, calendar_features  # noqa: E402
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.nn import Ensemble  # noqa: E402
from marketlab.strategy import bs_straddle  # noqa: E402
from marketlab.suite import funding_carry, split_stats, straddle_daily_pnl, vol_target_trend, weekly_straddles  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, EXTRA, RES = (os.path.join(HERE, x) for x in ("data", "data_extra", "results"))
COST, FEE = 0.0006, 0.0003


def load_extra():
    x = {}
    for name in ("options_surface_daily", "dvol_1h", "deribit_funding_1h", "binance_funding", "binance_metrics_1h",
                 "binance_perp_1h", "coinbase_1h", "fear_greed"):
        p = os.path.join(EXTRA, name + ".csv")
        x[name] = read(p) if os.path.exists(p) else None
        print(f"  {name}: {'missing' if x[name] is None else len(x[name])}")
    return x


def hedged_short_straddle(close_h, t0, t1, iv, band=0.025):
    """Short 1 unit of an ATM straddle, delta-hedged with the perpetual whenever price has moved more than
    `band` since the last hedge. Returns P&L as a fraction of entry price (before option fees)."""
    path = close_h[(close_h.index >= t0) & (close_h.index <= t1)]
    S0, K = path.iloc[0], path.iloc[0]
    T0 = (t1 - t0).total_seconds() / (365 * 86400)
    hedge, last_S, pnl = 0.0, S0, 0.0
    for t, S in path.items():
        T = max((t1 - t).total_seconds() / (365 * 86400), 1e-9)
        if t == path.index[0] or abs(S / last_S - 1) > band:
            d1 = (math.log(S / K) + 0.5 * iv * iv * T) / (iv * math.sqrt(T))
            delta = 2 * 0.5 * (1 + math.erf(d1 / math.sqrt(2))) - 1  # straddle delta (long)
            new = delta  # the short straddle is -delta; hold +delta in the perp to flatten it
            pnl -= abs(new - hedge) * S * COST
            hedge, last_S = new, S
        if t != path.index[-1]:
            nxt = path.iloc[path.index.get_loc(t) + 1]
            pnl += hedge * (nxt - S)
    prem = bs_straddle(S0, K, T0, iv)
    payoff = abs(path.iloc[-1] - K)
    return (prem - payoff + pnl) / S0


def nn_scan(d, extra_feats, dev_start, win_start):
    """Daily networks with and without the extra inputs: next-day direction (AUC) and next-7-day realised
    volatility (correlation), walk-forward, scored on the development year and the window."""
    r = np.log(d["close"]).diff()
    base = base_features(d, 365).join(calendar_features(d.index))
    tgt_dir = (r.shift(-1) > 0).astype(float).where(r.shift(-1).notna())
    fwd_rv = pd.concat([r.shift(-k) for k in range(1, 8)], axis=1).std(axis=1) * math.sqrt(365)
    fwd_rv = np.log(fwd_rv.where(pd.concat([r.shift(-k) for k in range(1, 8)], axis=1).notna().all(axis=1)))
    out = {}
    preds = {}
    for name, f in (("price only", base), ("price + extra data", base.join(extra_feats))):
        f = f.replace([np.inf, -np.inf], np.nan)
        ok = f.notna().mean(axis=1) > 0.8
        X = f[ok].values.astype(float)
        idx = f.index[ok]
        res = {}
        for tname, y_all, task in (("direction", tgt_dir, "clf"), ("vol7", fwd_rv, "reg")):
            y = y_all.reindex(idx).values
            s0 = int(np.searchsorted(idx, dev_start))
            pred = np.full(len(idx), np.nan)
            for i in range(s0, len(idx), 30):
                tr = np.arange(0, max(i - 7, 1))
                tr = tr[~np.isnan(y[tr])]
                m = Ensemble(n=5, task=task, hidden=(32, 16), dropout=0.2, l2=1e-3).fit(X[tr], y[tr])
                pred[i:i + 30] = m.predict(X[i:i + 30])
            for seg, (a, b) in (("dev", (dev_start, win_start)), ("window", (win_start, idx[-1] + pd.Timedelta(days=1)))):
                sel = (idx >= a) & (idx < b) & ~np.isnan(y) & ~np.isnan(pred)
                if tname == "direction":
                    res[f"{tname}_{seg}"] = {"auc": auc(y[sel], pred[sel]), "accuracy": float(((pred[sel] > 0.5) == (y[sel] == 1)).mean()), "n": int(sel.sum())}
                else:
                    res[f"{tname}_{seg}"] = {"corr": float(np.corrcoef(pred[sel], y[sel])[0, 1]), "n": int(sel.sum())}
            preds[(name, tname)] = pd.Series(pred, index=idx)
        out[name] = res
        print(f"  NN {name}: {json.dumps({k: round(list(v.values())[0], 3) for k, v in res.items()})}")
    return out, preds


def main():
    d, h = read(f"{DATA}/btc_1d.csv"), read(f"{DATA}/btc_1h.csv")
    dv = daily_index(read(f"{DATA}/dvol_1d.csv"))
    od = daily_index(read(f"{DATA}/options_daily.csv"))
    al = read(f"{RES}/alarms_hourly.csv")
    print("extra data")
    X = load_extra()
    end = h.index[-1]
    win_start = end.normalize() - pd.Timedelta(days=91)
    dev_start = al.index[0].normalize() + pd.Timedelta(days=1)
    pre = al[al.index < win_start]
    q_up, q_dn = float(pre["p_up"].quantile(0.9)), float(pre["p_dn"].quantile(0.9))
    c = d["close"]
    r = np.log(c).diff()
    rv30 = r.rolling(30).std() * math.sqrt(365) * 100
    res = {"market": "BTC/USDT", "dev": [str(dev_start), str(win_start)], "window": [str(win_start), str(end)]}

    # ---------- extra daily features
    ef = pd.DataFrame(index=d.index)
    surf = X["options_surface_daily"]
    if surf is not None:
        s = surf.reindex(d.index)
        ef["rr25"] = s["rr25"].rolling(3, min_periods=1).mean()
        ef["fly25"] = s["fly25"].rolling(3, min_periods=1).mean()
        ef["term_slope"] = (s["atm_iv_10_45d"] - s["atm_iv_2_10d"]).rolling(3, min_periods=1).mean()
        ef["pc_notional"] = np.log(s["pc_notional"]).rolling(3, min_periods=1).mean()
        ef["net_call_taker"] = (s["c_net_taker"] / s["notional_usd"]).rolling(3, min_periods=1).mean()
        ef["net_put_taker"] = (s["p_net_taker"] / s["notional_usd"]).rolling(3, min_periods=1).mean()
        ef["block_share"] = s["block_share"].rolling(3, min_periods=1).mean()
    if dv is not None:
        ef["vrp"] = dv["close"].reindex(d.index) - rv30
    bf = X["binance_funding"]
    fund_daily = None
    if bf is not None:
        fund_daily = bf["funding_rate"].resample("D").sum().reindex(d.index)
        ef["funding_7d"] = fund_daily.rolling(7).mean() * 365
    met = X["binance_metrics_1h"]
    if met is not None:
        m = met.resample("D").last().reindex(d.index)
        oi = m.get("sum_open_interest_value")
        if oi is not None:
            ef["oi_chg_7d"] = np.log(oi).diff(7)
        for col in ("sum_toptrader_long_short_ratio", "count_long_short_ratio", "sum_taker_long_short_vol_ratio"):
            if col in m:
                ef[col] = np.log(m[col])
    cb, pp = X["coinbase_1h"], X["binance_perp_1h"]
    if cb is not None:
        prem = (cb["close"] / h["close"].reindex(cb.index) - 1).resample("D").mean().reindex(d.index)
        ef["coinbase_premium"] = prem.rolling(3, min_periods=1).mean()
    if pp is not None:
        basis = (pp["close"] / h["close"].reindex(pp.index) - 1).resample("D").mean().reindex(d.index)
        ef["perp_basis"] = basis
    fg = X["fear_greed"]
    if fg is not None:
        ef["fear_greed"] = daily_index(fg)["value"].reindex(d.index)
    res["extra_features"] = list(ef.columns)

    # ---------- D. networks with and without the extra data
    print("networks")
    res["nn"], preds = nn_scan(d, ef, dev_start, win_start)

    # ---------- A. trend with volatility targeting
    print("trend")
    trend = {}
    term_inv = None
    if surf is not None:
        s = surf.reindex(d.index)
        term_inv = (s["atm_iv_2_10d"] > s["atm_iv_10_45d"] + 2).rolling(2, min_periods=1).max().astype(bool)
    crowd = None
    if "funding_7d" in ef:
        thr = ef["funding_7d"][ef.index < win_start].quantile(0.9)
        crowd = ef["funding_7d"] > thr
    variants = {"Long/flat, 20/60/120-day ensemble, 40% vol target": dict(long_only=True),
                "Long/short, same": dict(long_only=False),
                "Long/flat, 50-day only": dict(long_only=True, lookbacks=(50,))}
    if term_inv is not None or crowd is not None:
        risk_off = (term_inv if term_inv is not None else False) | (crowd if crowd is not None else False)
        variants["Long/flat + risk-off when funding is crowded or the vol curve inverts"] = dict(long_only=True, trend_filter=~risk_off.fillna(False))
    trend_pnl = {}
    for k, kw in variants.items():
        w, pnl = vol_target_trend(c, cost=COST, **kw)
        trend[k] = split_stats(pnl, dev_start, win_start) | {"exposure_window": float((w[w.index >= win_start] != 0).mean())}
        trend_pnl[k] = pnl
    res["trend"] = trend

    # ---------- B. weekly straddles
    print("straddles")
    iv_w = None
    if surf is not None:
        iv_w = surf["atm_iv_2_10d"].reindex(d.index).ffill(limit=2)
    if iv_w is None or iv_w.notna().sum() < 100:
        iv_w = od["atm_iv_short"].reindex(d.index) if od is not None else dv["close"].reindex(d.index) * 0.9
    ch = h["close"]
    dvol = dv["close"].reindex(d.index)

    def alarm(t0):
        w = al[(al.index > t0 - pd.Timedelta(hours=24)) & (al.index <= t0)]
        return bool(len(w) and (w["p_up"].max() >= q_up or w["p_dn"].max() >= q_dn))

    def filt_ok(pday):
        vrp_ok = (dvol.get(pday, np.nan) - rv30.get(pday, np.nan)) > 5
        inv = bool(term_inv.get(pday, False)) if term_inv is not None else False
        return vrp_ok and not inv

    rules = {
        "Sell every Friday": lambda t0, p: -1,
        "Sell only with research filters (IV > RV + 5, curve not inverted)": lambda t0, p: -1 if filt_ok(p) else 0,
        "Research filters + alarm quiet": lambda t0, p: -1 if (filt_ok(p) and not alarm(t0)) else 0,
        "Switch: buy on alarm, sell when filters pass": lambda t0, p: 1 if alarm(t0) else (-1 if filt_ok(p) else 0),
    }
    strad = {}
    strad_trades = {}
    for k, fn in rules.items():
        tr = weekly_straddles(ch, iv_w, fn, extra_cost_vol=1.0)
        pnl = straddle_daily_pnl(tr, d.index)
        strad[k] = split_stats(pnl, dev_start, win_start) | {
            "weeks_traded_dev": int(((tr.side != 0) & (tr.start < win_start) & (tr.start >= dev_start)).sum()),
            "weeks_traded_window": int(((tr.side != 0) & (tr.start >= win_start)).sum())}
        strad_trades[k] = (tr, pnl)
    # band-hedged short straddle with the research filters
    rows = []
    for t0 in ch.index[(ch.index.dayofweek == 4) & (ch.index.hour == 8)]:
        t1 = t0 + pd.Timedelta(days=7)
        if t1 > ch.index[-1]:
            break
        p = t0.normalize() - pd.Timedelta(days=1)
        iv = iv_w.get(p, np.nan)
        if not np.isfinite(iv) or not filt_ok(p) or alarm(t0):
            rows.append({"start": t0, "end": t1, "side": 0, "pnl": 0.0})
            continue
        pnl = hedged_short_straddle(ch, t0, t1, max(iv - 1.0, 1) / 100) - 2 * FEE
        rows.append({"start": t0, "end": t1, "side": -1, "iv": iv, "move": float(ch[ch.index <= t1].iloc[-1] / ch[t0] - 1), "pnl": pnl})
    trh = pd.DataFrame(rows)
    pnl_h = straddle_daily_pnl(trh, d.index)
    strad["Research filters + alarm quiet, delta-hedged every 2.5%"] = split_stats(pnl_h, dev_start, win_start) | {
        "weeks_traded_dev": int(((trh.side != 0) & (trh.start < win_start) & (trh.start >= dev_start)).sum()),
        "weeks_traded_window": int(((trh.side != 0) & (trh.start >= win_start)).sum())}
    strad_trades["Research filters + alarm quiet, delta-hedged every 2.5%"] = (trh, pnl_h)
    res["straddles"] = strad

    # ---------- C. funding carry
    carry = {}
    carry_pnl = {}
    if fund_daily is not None:
        for k, rule in (("Hold while 7-day funding > 0", None),
                        ("Hold while 7-day funding > 10% a year", lambda f: (f.rolling(7).mean() * 365 > 0.10).shift(1).fillna(False))):
            hold, pnl = funding_carry(fund_daily, entry_rule=rule)
            carry[k] = split_stats(pnl, dev_start, win_start) | {"held_window": float(hold[hold.index >= win_start].mean())}
            carry_pnl[k] = pnl
    res["carry"] = carry

    # ---------- E. portfolios: the best variant of each family by Sharpe over everything BEFORE the window
    def best(fam, pnls):
        k = max(fam, key=lambda n: fam[n]["pre"].get("sharpe", -9))
        return k, pnls[k]
    parts = {}
    k, p = best(trend, trend_pnl)
    parts["Trend: " + k] = p
    k, p = best(strad, {n: v[1] for n, v in strad_trades.items()})
    parts["Straddle: " + k] = p
    if carry:
        k, p = best(carry, carry_pnl)
        parts["Carry: " + k] = p
    dfp = pd.DataFrame(parts).fillna(0.0)
    res["portfolio"] = {"parts": list(parts), "selected_by": "highest Sharpe before the test window (about 2.75 years)",
                        "parts_stats": {k: split_stats(v, dev_start, win_start, 365) for k, v in parts.items()}}
    for pname, wts in (("balanced", pd.Series(1 / len(parts), index=dfp.columns)), ("stacked", pd.Series(1.0, index=dfp.columns))):
        port = (dfp * wts).sum(axis=1)
        wn = port[port.index >= win_start]
        eq = 100 * (1 + wn).cumprod()
        prev = eq.shift(1).fillna(100)
        bh = 100 * np.exp(r[r.index >= win_start].fillna(0).cumsum())
        pw = dfp[dfp.index >= win_start] * wts
        mret = eq.resample("ME").last()
        res["portfolio"][pname] = {"weights": {k: float(v) for k, v in wts.items()}, **split_stats(port, dev_start, win_start, 365),
                                   "account": {"end": float(eq.iloc[-1]), "hold_end": float(bh.iloc[-1]),
                                               "months": [{"month": str(t.date())[:7], "end": float(v)} for t, v in mret.items()],
                                               "by_part_usd": {k: float((pw[k] * prev).sum()) for k in pw.columns},
                                               "curve": {"t": [str(t.date()) for t in eq.index], "eq": [round(float(v), 3) for v in eq], "hold": [round(float(v), 3) for v in bh]},
                                               "days": [{"date": str(t.date()), "start": float(prev[t]), "end": float(eq[t]), "pnl": float(eq[t] - prev[t]),
                                                         "market_ret": float(math.expm1(r.get(t, 0) or 0)),
                                                         "parts": {k: float(pw.loc[t, k] * prev[t]) for k in pw.columns}} for t in eq.index[-30:]]}}
    sel = [p for p in parts if p.startswith("Straddle: ")][0].split(": ", 1)[1]
    tr_sel = strad_trades[sel][0]
    res["straddle_weeks_window"] = [{"start": str(t.start), "side": int(t.side), "iv": float(t["iv"]) if "iv" in t and pd.notna(t["iv"]) else None,
                                     "move": float(t["move"]) if "move" in t and pd.notna(t["move"]) else None, "pnl": float(t.pnl)}
                                    for _, t in tr_sel[tr_sel.start >= win_start].iterrows()]
    write_json(res, f"{RES}/suite.json")
    P = res["portfolio"]
    print("parts", P["parts"])
    for pname in ("balanced", "stacked"):
        q = P[pname]
        print(pname, "| pre", {k: round(v, 3) for k, v in q["pre"].items() if k in ("total_return", "sharpe", "max_drawdown")},
              "| window", {k: round(v, 3) for k, v in q["window"].items() if k in ("total_return", "sharpe", "max_drawdown")},
              "| $100 ->", round(q["account"]["end"], 2), "hold", round(q["account"]["hold_end"], 2), q["account"]["by_part_usd"])


if __name__ == "__main__":
    main()
