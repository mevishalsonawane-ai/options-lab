"""Gold strategy suite from the research (docs/market_strategies_research.md), with the extra data.

  A. Trend with volatility targeting (12% target), optional real-yield/dollar and COT-crowding filters
  B. Weekly ATM straddles on gold priced from GVZ (the gold options volatility index): sell when GVZ is
     above realised volatility and the drop alarm is quiet; the "switch" buys when the alarm is high
  C. The original blow-off fade + buy-the-big-drop rules
  D. Neural networks with the extra inputs (GVZ, VIX, real yields, breakevens, dollar, COT, gold/silver)
  E. Portfolio of the parts, weights set on the development year

Every choice is made on the development year; the last 3 months are only reported. Writes
forex/results/suite.json. Run after strategy.py (reuses its cached alarms).
"""
import json
import math
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
sys.path.insert(0, os.path.dirname(__file__))
from diagnose import rules as original_rules  # noqa: E402
from marketlab.evaluate import auc  # noqa: E402
from marketlab.features import base_features, calendar_features  # noqa: E402
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.nn import Ensemble  # noqa: E402
from marketlab.suite import split_stats, straddle_daily_pnl, vol_target_trend, weekly_straddles  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, EXTRA, RES = (os.path.join(HERE, x) for x in ("data", "data_extra", "results"))
COST, PER_YEAR = 0.00015, 252


def rd(name):
    p = os.path.join(EXTRA, name + ".csv")
    return daily_index(read(p)) if os.path.exists(p) else None


def nn_scan(d, extra_feats, dev_start, win_start):
    r = np.log(d["close"]).diff()
    base = base_features(d, PER_YEAR).join(calendar_features(d.index))
    tgt_dir = (r.shift(-1) > 0).astype(float).where(r.shift(-1).notna())
    fw = pd.concat([r.shift(-k) for k in range(1, 6)], axis=1)
    fwd_rv = np.log((fw.std(axis=1) * math.sqrt(PER_YEAR)).where(fw.notna().all(axis=1)))
    out = {}
    for name, f in (("price only", base), ("price + extra data", base.join(extra_feats))):
        f = f.replace([np.inf, -np.inf], np.nan)
        ok = f.notna().mean(axis=1) > 0.8
        X = f[ok].values.astype(float)
        idx = f.index[ok]
        res = {}
        for tname, y_all, task in (("direction", tgt_dir, "clf"), ("vol5", fwd_rv, "reg")):
            y = y_all.reindex(idx).values
            s0 = int(np.searchsorted(idx, dev_start))
            pred = np.full(len(idx), np.nan)
            for i in range(s0, len(idx), 20):
                tr = np.arange(0, max(i - 5, 1))
                tr = tr[~np.isnan(y[tr])]
                m = Ensemble(n=5, task=task, hidden=(32, 16), dropout=0.2, l2=1e-3).fit(X[tr], y[tr])
                pred[i:i + 20] = m.predict(X[i:i + 20])
            for seg, (a, b) in (("dev", (dev_start, win_start)), ("window", (win_start, idx[-1] + pd.Timedelta(days=1)))):
                sel = (idx >= a) & (idx < b) & ~np.isnan(y) & ~np.isnan(pred)
                if tname == "direction":
                    res[f"{tname}_{seg}"] = {"auc": auc(y[sel], pred[sel]), "accuracy": float(((pred[sel] > 0.5) == (y[sel] == 1)).mean()), "n": int(sel.sum())}
                else:
                    res[f"{tname}_{seg}"] = {"corr": float(np.corrcoef(pred[sel], y[sel])[0, 1]), "n": int(sel.sum())}
        out[name] = res
        print(f"  NN {name}: {json.dumps({k: round(list(v.values())[0], 3) for k, v in res.items()})}")
    return out


def main():
    d, h = read(f"{DATA}/xauusd_1d.csv"), read(f"{DATA}/xauusd_1h.csv")
    al = read(f"{RES}/alarms_hourly.csv")
    end = h.index[-1]
    win_start = end.normalize() - pd.Timedelta(days=91)
    dev_start = al.index[0].normalize() + pd.Timedelta(days=1)
    pre = al[al.index < win_start]
    q_dn = float(pre["p_dn"].quantile(0.9))
    di = d.index
    c = d["close"]
    r = np.log(c).diff()
    rv20 = r.rolling(20).std() * math.sqrt(PER_YEAR) * 100
    res = {"market": "XAU/USD", "dev": [str(dev_start), str(win_start)], "window": [str(win_start), str(end)]}

    gvz, vix, dxy, silver = rd("gvz_1d"), rd("vix_1d"), rd("dxy_1d"), rd("silver_1d")
    ry, be, cot = rd("fred_DFII10"), rd("fred_T10YIE"), rd("cot_gold")
    ef = pd.DataFrame(index=di)
    al_ = lambda s: s.reindex(di.normalize()).ffill(limit=5).values  # noqa: E731
    if gvz is not None:
        ef["gvz"] = al_(gvz["close"])
        ef["gvz_minus_rv"] = ef["gvz"] - rv20.values
        ef["gvz_chg5"] = al_(gvz["close"].diff(5))
    if vix is not None:
        ef["vix"] = al_(vix["close"])
    if dxy is not None:
        ef["dxy_chg5"] = al_(np.log(dxy["close"]).diff(5))
        ef["dxy_chg20"] = al_(np.log(dxy["close"]).diff(20))
    if ry is not None:
        ef["real_yield"] = al_(ry["value"])
        ef["real_yield_chg20"] = al_(ry["value"].diff(20))
    if be is not None:
        ef["breakeven_chg20"] = al_(be["value"].diff(20))
    if silver is not None:
        gsr = np.log(c.values / pd.Series(al_(silver["close"]), index=di))
        ef["gold_silver_z"] = (gsr - gsr.rolling(250, min_periods=60).mean()) / gsr.rolling(250, min_periods=60).std()
    if cot is not None and "mm_long" in cot:
        net = (cot["mm_long"] - cot["mm_short"]) / cot["open_interest"]
        net.index = net.index + pd.Timedelta(days=3)  # Tuesday data, released Friday
        ef["cot_mm_net"] = al_(net.resample("D").last().ffill())
    res["extra_features"] = list(ef.columns)

    print("networks")
    res["nn"] = nn_scan(d, ef, dev_start, win_start)

    print("trend")
    trend, trend_pnl = {}, {}
    variants = {"Long/flat, 20/60/120-day ensemble, 12% vol target": dict(long_only=True, target_vol=0.12),
                "Long/short, same": dict(long_only=False, target_vol=0.12),
                "Long/flat, 12-month + 50/200-day": dict(long_only=True, target_vol=0.12, lookbacks=(50, 200, 250))}
    if "real_yield_chg20" in ef and "dxy_chg20" in ef:
        macro_ok = ~((ef["real_yield_chg20"] > 0) & (ef["dxy_chg20"] > 0))
        variants["Long/flat + off when real yields AND the dollar are both rising"] = dict(long_only=True, target_vol=0.12, trend_filter=macro_ok.fillna(True))
    if "cot_mm_net" in ef:
        crowded = ef["cot_mm_net"] > ef["cot_mm_net"][ef.index < win_start].quantile(0.9)
        variants["Long/flat + off when managed money is most crowded (top 10%)"] = dict(long_only=True, target_vol=0.12, trend_filter=~crowded.fillna(False))
    for k, kw in variants.items():
        w, pnl = vol_target_trend(c, per_year=PER_YEAR, cost=COST, **kw)
        trend[k] = split_stats(pnl, dev_start, win_start, PER_YEAR) | {"exposure_window": float((w[w.index >= win_start] != 0).mean())}
        trend_pnl[k] = pnl
    res["trend"] = trend

    print("straddles")
    strad, strad_trades = {}, {}
    if gvz is not None:
        iv = pd.Series(al_(gvz["close"]), index=di)
        ch = h["close"]

        def alarm(t0):
            w = al[(al.index > t0 - pd.Timedelta(hours=24)) & (al.index <= t0)]
            return bool(len(w) and w["p_dn"].max() >= q_dn)

        def rich(p):
            return (iv.get(p, np.nan) - rv20.get(p, np.nan)) > 2
        rules = {"Sell every Friday": lambda t0, p: -1,
                 "Sell when GVZ > realised vol + 2": lambda t0, p: -1 if rich(p) else 0,
                 "GVZ rich + alarm quiet": lambda t0, p: -1 if (rich(p) and not alarm(t0)) else 0,
                 "Switch: buy on alarm, sell when GVZ rich": lambda t0, p: 1 if alarm(t0) else (-1 if rich(p) else 0)}
        # weekly gold options stop on Friday; strike Friday 15:00 UTC (US session), hold 7 days
        for k, fn in rules.items():
            tr = weekly_straddles(ch, iv, fn, weekday=4, hour=15, fee=0.0005, extra_cost_vol=0.5)
            pnl = straddle_daily_pnl(tr, di)
            strad[k] = split_stats(pnl, dev_start, win_start, PER_YEAR) | {
                "weeks_traded_dev": int(((tr.side != 0) & (tr.start < win_start) & (tr.start >= dev_start)).sum()),
                "weeks_traded_window": int(((tr.side != 0) & (tr.start >= win_start)).sum())}
            strad_trades[k] = (tr, pnl)
    res["straddles"] = strad

    print("original rules")
    pos, lr, _ = original_rules(h, d, al, q_dn)
    ny = lr.index.tz_convert("America/New_York")
    sess = pd.to_datetime((ny + pd.Timedelta(hours=7)).date).tz_localize("UTC")  # hour -> its 17:00 New York session
    orig = lr.groupby(sess).sum().reindex(di).fillna(0.0)
    res["original"] = split_stats(orig, dev_start, win_start, PER_YEAR)

    # ---------- E. portfolios: the best variant of each family by Sharpe over everything BEFORE the window
    def best(fam, pnls):
        k = max(fam, key=lambda n: fam[n]["pre"].get("sharpe", -9))
        return k, pnls[k]
    parts = {}
    k, p = best(trend, trend_pnl)
    parts["Trend: " + k] = p
    if strad:
        k, p = best(strad, {n: v[1] for n, v in strad_trades.items()})
        parts["Straddle: " + k] = p
    parts["Original: blow-off fade + buy the big drop"] = orig
    dfp = pd.DataFrame(parts).fillna(0.0)
    res["portfolio"] = {"parts": list(parts), "selected_by": "highest Sharpe before the test window (about 2.75 years)",
                        "parts_stats": {k: split_stats(v, dev_start, win_start, PER_YEAR) for k, v in parts.items()}}
    for pname, wts in (("balanced", pd.Series(1 / len(parts), index=dfp.columns)), ("stacked", pd.Series(1.0, index=dfp.columns))):
        port = (dfp * wts).sum(axis=1)
        wn = port[port.index >= win_start]
        eq = 100 * (1 + wn).cumprod()
        prev = eq.shift(1).fillna(100)
        bh = 100 * np.exp(r[r.index >= win_start].fillna(0).cumsum())
        pw = dfp[dfp.index >= win_start] * wts
        mret = eq.resample("ME").last()
        res["portfolio"][pname] = {"weights": {k: float(v) for k, v in wts.items()}, **split_stats(port, dev_start, win_start, PER_YEAR),
                                   "account": {"end": float(eq.iloc[-1]), "hold_end": float(bh.iloc[-1]),
                                               "months": [{"month": str(t.date())[:7], "end": float(v)} for t, v in mret.items()],
                                               "by_part_usd": {k: float((pw[k] * prev).sum()) for k in pw.columns},
                                               "curve": {"t": [str(t.date()) for t in eq.index], "eq": [round(float(v), 3) for v in eq], "hold": [round(float(v), 3) for v in bh]},
                                               "days": [{"date": str(t.date()), "start": float(prev[t]), "end": float(eq[t]), "pnl": float(eq[t] - prev[t]),
                                                         "market_ret": float(math.expm1(r.get(t, 0) or 0)),
                                                         "parts": {k: float(pw.loc[t, k] * prev[t]) for k in pw.columns}} for t in eq.index[-22:]]}}
    if strad:
        sel = [p for p in parts if p.startswith("Straddle: ")][0].split(": ", 1)[1]
        tr = strad_trades[sel][0]
        res["straddle_weeks_window"] = [{"start": str(t.start), "side": int(t.side), "iv": float(t.iv), "move": float(t.move), "pnl": float(t.pnl)}
                                        for _, t in tr[tr.start >= win_start].iterrows()]
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
