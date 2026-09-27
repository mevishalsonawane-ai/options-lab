"""BTC strategy built from the study, tested on the last 3 months with $100.

Rules (fixed before the test window; thresholds come only from data before it):
  1. Breakout long   - big-rise alarm in its top 10% AND (price at the 20-day high OR hourly RSI > 70)
                       AND the big-drop alarm not in its top 10%. Hold 24 hours from the last signal.
  2. Risk short      - big-drop alarm in its top 10% AND price under its 200-hour average AND
                       24h volatility above its 20-day level AND the big-rise alarm quiet. Hold 24 hours.
  3. Weekly straddle - every Friday 08:00 UTC sell a 7-day at-the-money straddle (Deribit weekly expiry)
                       sized to the account, priced at the short-dated ATM IV seen the day before,
                       UNLESS either alarm was in its top 10% in the last 24 hours or DVOL jumped
                       (5-day change in its top fifth). Held to expiry, marked each hour.
Costs: 0.06 % a side on BTC trades; 0.03 % of the underlying per option leg.
"""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.features import rsi  # noqa: E402
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.strategy import (bs_straddle, build_report, detector_predictions, hold_signal,  # noqa: E402
                                pnl_from_positions, trades_from_positions)

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
CAPITAL, COST, OPT_FEE = 100.0, 0.0006, 0.0003


def main():
    d, h = read(f"{DATA}/btc_1d.csv"), read(f"{DATA}/btc_1h.csv")
    dv, od = daily_index(read(f"{DATA}/dvol_1d.csv")), daily_index(read(f"{DATA}/options_daily.csv"))
    c = h["close"]
    end = h.index[-1]
    start = end.normalize() - pd.Timedelta(days=91)
    last30 = end.normalize() - pd.Timedelta(days=29)

    cache = f"{RES}/alarms_hourly.csv"
    if os.path.exists(cache) and "--fresh" not in sys.argv:
        al = read(cache)
    else:
        al = detector_predictions(h, 8760, extra_daily={"dvol": dv["close"]} if dv is not None else None)
        al.to_csv(cache)
    pre = al[al.index < start]
    q_up, q_dn = float(pre["p_up"].quantile(0.9)), float(pre["p_dn"].quantile(0.9))
    print(f"alarm thresholds from {pre.index[0]} to {pre.index[-1]}: up {q_up:.3f} down {q_dn:.3f}")

    r = np.log(c).diff()
    hi480 = c / c.rolling(480).max() - 1
    rsi_h = rsi(c)
    below200 = c < c.rolling(200).mean()
    vol_up = r.rolling(24).std() > r.rolling(480).std()
    pu, pdn = al["p_up"].reindex(h.index), al["p_dn"].reindex(h.index)
    rise_sig = (pu >= q_up) & ((hi480 >= -0.005) | (rsi_h > 70)) & (pdn < q_dn)
    drop_sig = (pdn >= q_dn) & below200 & vol_up & (pu < q_up)
    long_p = hold_signal(rise_sig, 24)
    short_p = hold_signal(drop_sig, 24)
    pos = (long_p - short_p).clip(-1, 1)
    pos[pos.index < start - pd.Timedelta(hours=1)] = 0.0
    dir_lr, _ = pnl_from_positions(pos, r, COST)
    dir_ret = np.expm1(dir_lr)
    trades = trades_from_positions(pos[pos.index >= start - pd.Timedelta(hours=1)], c, COST, "Long/short trades")
    for t in trades:
        t["strategy"] = "Breakout long" if t["side"] == "long" else "Risk short"

    # ---- weekly straddle
    dvol = dv["close"] if dv is not None else None
    dchg5 = dvol.diff(5) if dvol is not None else None
    dthr = float(dchg5[dchg5.index < start].quantile(0.8)) if dchg5 is not None else np.inf
    iv_short = od["atm_iv_short"] if od is not None else None
    opt_ret = pd.Series(0.0, index=h.index)
    opt_pos = pd.Series(0.0, index=h.index)
    opt_trades, skipped = [], []
    fridays = [t for t in h.index if t >= start and t.dayofweek == 4 and t.hour == 8]
    for t0 in fridays:
        t1 = t0 + pd.Timedelta(days=7)
        prev_day = t0.normalize() - pd.Timedelta(days=1)
        win = al[(al.index > t0 - pd.Timedelta(hours=24)) & (al.index <= t0)]
        reasons = []
        if len(win) and win["p_up"].max() >= q_up:
            reasons.append("big-rise alarm high")
        if len(win) and win["p_dn"].max() >= q_dn:
            reasons.append("big-drop alarm high")
        if dchg5 is not None and dchg5.get(prev_day, np.nan) >= dthr:
            reasons.append("DVOL jumping")
        if reasons:
            skipped.append({"date": str(t0.date()), "reasons": reasons})
            continue
        iv = iv_short.get(prev_day, np.nan) if iv_short is not None else np.nan
        if not np.isfinite(iv):
            iv = 0.9 * dvol.get(prev_day, np.nan)
        iv /= 100
        S0 = c[t0]
        qty = CAPITAL / S0  # notional = starting capital; P&L scaled to the account below
        path = c[(c.index >= t0) & (c.index <= t1)]
        vals = [bs_straddle(s, S0, max((t1 - t).total_seconds() / (365 * 86400), 0), iv) for t, s in path.items()]
        vals = pd.Series(vals, index=path.index)
        prem = vals.iloc[0]
        fee = 2 * OPT_FEE * S0
        # P&L per hour for the seller, as a fraction of starting capital
        hourly = -(vals.diff().fillna(0.0)) * qty / CAPITAL
        hourly.iloc[0] -= fee * qty / CAPITAL
        opt_ret.loc[hourly.index[1:]] += hourly.iloc[1:].values
        opt_ret.loc[hourly.index[1]] += hourly.iloc[0]
        opt_pos.loc[path.index[:-1]] = -1.0
        settle = vals.iloc[-1]
        opt_trades.append({"strategy": "Weekly straddle", "side": "short straddle", "entry_time": str(t0), "entry": round(float(S0), 2),
                           "exit_time": str(path.index[-1]), "exit": round(float(path.iloc[-1]), 2), "hours": 168.0,
                           "iv": round(iv * 100, 1), "premium_pct": round(prem / S0, 5),
                           "ret": round(float((prem - settle - fee) / S0), 5),
                           **({"open": True} if path.index[-1] < t1 else {})})
    # returns are measured on starting capital per trade; convert to returns on current equity approx.
    comps = {"Long/short trades": dir_ret, "Weekly straddle": opt_ret}
    # scale the straddle leg to current equity (it was sized on starting capital)
    notes = {}
    for sk in skipped:
        notes[sk["date"]] = "Straddle skipped: " + ", ".join(sk["reasons"])
    for t in opt_trades:
        notes[t["entry_time"][:10]] = (notes.get(t["entry_time"][:10], "") + f" Sold 7-day straddle at {t['entry']:,.0f}, IV {t['iv']}%, premium {t['premium_pct'] * 100:.2f}% of spot.").strip()
    rep = build_report(c, comps, {"Long/short trades": pos, "Weekly straddle": opt_pos},
                       trades + opt_trades, al, CAPITAL, start, last30, 8760, notes)
    rep["market"] = "BTC/USDT"
    rep["rules"] = __doc__
    rep["thresholds"] = {"alarm_up_top10": q_up, "alarm_down_top10": q_dn, "dvol_5d_jump": dthr,
                         "calibrated_on": [str(pre.index[0]), str(pre.index[-1])]}
    rep["straddles"] = {"sold": opt_trades, "skipped": skipped}
    rep["signals_in_window"] = {"breakout_long_hours": int(rise_sig[rise_sig.index >= start].sum()),
                                "risk_short_hours": int(drop_sig[drop_sig.index >= start].sum())}
    rep["capital_note"] = ("Deribit's smallest option is 0.1 BTC, so with $100 the straddle leg is a modelled fractional "
                           "position; the spot/perpetual leg can be traded at this size.")
    write_json(rep, f"{RES}/strategy.json")
    s = rep["summary"]
    print(json.dumps({k: s[k] for k in ("start_capital", "end_capital", "pnl_usd", "benchmark_end_usd", "total_return", "max_drawdown",
                                        "sharpe", "trades", "win_rate", "by_component_usd")}, indent=1))
    print("months", rep["months"])
    print("straddles", len(opt_trades), "skipped", skipped)


if __name__ == "__main__":
    main()
