"""Gold (XAU/USD) strategy built from the study, tested on the last 3 months with $100.

Rules (fixed before the test window; thresholds come only from data before it):
  1. Blow-off fade  - big-drop alarm in its top 10% AND price more than 8 % above its 20-day low AND
                      24h volatility above its 20-day level. Short, held 24 hours from the last signal.
  2. Buy the big drop - after a session whose drop is beyond 2x its normal size (60-day volatility),
                      buy at that session's close and hold 20 sessions.
Both run in one $100 account; the position is their sum, capped at one unit long or short.
Costs: 0.015 % a side (typical XAU/USD spread plus slippage).
"""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.io import read, write_json  # noqa: E402
from marketlab.strategy import build_report, detector_predictions, hold_signal, pnl_from_positions, trades_from_positions  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
CAPITAL, COST = 100.0, 0.00015


def main():
    d, h = read(f"{DATA}/xauusd_1d.csv"), read(f"{DATA}/xauusd_1h.csv")
    per_h = len(h) / max((h.index[-1] - h.index[0]).days / 365.25, 0.1)
    c = h["close"]
    end = h.index[-1]
    start = end.normalize() - pd.Timedelta(days=91)
    last30 = end.normalize() - pd.Timedelta(days=29)

    cache = f"{RES}/alarms_hourly.csv"
    if os.path.exists(cache) and "--fresh" not in sys.argv:
        al = read(cache)
    else:
        al = detector_predictions(h, per_h, step=600)
        al.to_csv(cache)
    pre = al[al.index < start]
    q_dn = float(pre["p_dn"].quantile(0.9))
    print(f"alarm threshold from {pre.index[0]} to {pre.index[-1]}: down {q_dn:.3f}")

    r = np.log(c).diff()
    lo480 = c / c.rolling(480).min() - 1
    vol_up = r.rolling(24).std() > r.rolling(480).std()
    pdn = al["p_dn"].reindex(h.index)
    fade_sig = (pdn >= q_dn) & (lo480 > 0.08) & vol_up
    short_p = hold_signal(fade_sig, 24)

    # buy the big drop: daily z-score against trailing 60-session volatility
    rd = np.log(d["close"]).diff()
    zd = rd / rd.rolling(60, min_periods=30).std().shift(1)
    big_drop_days = zd.index[(zd < -2).values]
    long_p = pd.Series(0.0, index=h.index)
    sessions = list(d.index)
    ny = h.index.tz_convert("America/New_York")
    session_of_bar = pd.Series(pd.to_datetime((ny + pd.Timedelta(hours=7)).date).tz_localize("UTC"), index=h.index)
    buy_notes = {}
    for day in big_drop_days:
        i = sessions.index(day)
        exit_day = sessions[min(i + 20, len(sessions) - 1)]
        bars_in = session_of_bar[(session_of_bar > day) & (session_of_bar <= exit_day)].index
        entry_bar = session_of_bar[session_of_bar == day].index
        if len(entry_bar) == 0:
            continue
        span = long_p.index[(long_p.index >= entry_bar[-1]) & (long_p.index < (bars_in[-1] if len(bars_in) else entry_bar[-1]))]
        long_p.loc[span] = 1.0
        if day >= start - pd.Timedelta(days=30):
            buy_notes[str(day.date())] = f"Big down day ({rd[day] * 100:.1f}%, {zd[day]:.1f}x normal): bought at the close, holding 20 sessions."
    pos = (long_p - short_p).clip(-1, 1)
    pos[pos.index < start - pd.Timedelta(hours=1)] = 0.0
    # a buy-the-drop position opened before the window keeps running into it
    carry = long_p[(long_p.index >= start - pd.Timedelta(hours=1))]
    pos.loc[carry.index] = (carry - short_p.reindex(carry.index)).clip(-1, 1)
    lr, _ = pnl_from_positions(pos, r, COST)
    trades = trades_from_positions(pos[pos.index >= start - pd.Timedelta(hours=1)], c, COST, "x")
    for t in trades:
        t["strategy"] = "Buy the big drop" if t["side"] == "long" else "Blow-off fade"
        if t["side"] == "long" and pd.Timestamp(t["entry_time"]) <= start:
            t["note"] = "carried in from a big down day before the window"
    comps = {"Blow-off fade": np.expm1(lr.where(pos.shift(1) < 0, 0.0)), "Buy the big drop": np.expm1(lr.where(pos.shift(1) > 0, 0.0))}
    # fees on bars where the position went to zero are attributed to the side just closed
    flat_fee = lr.where(pos.shift(1) == 0, 0.0)
    comps["Buy the big drop"] = comps["Buy the big drop"] + np.expm1(flat_fee.where(pos.shift(2) > 0, 0.0))
    comps["Blow-off fade"] = comps["Blow-off fade"] + np.expm1(flat_fee.where(pos.shift(2) < 0, 0.0))
    notes = dict(buy_notes)
    for t in trades:
        if t["side"] == "short":
            k = t["entry_time"][:10]
            notes[k] = (notes.get(k, "") + f" Blow-off alarm: shorted at {t['entry']:,.2f}.").strip()
    rep = build_report(c, comps, {"Blow-off fade": -short_p.where(pos < 0, 0.0), "Buy the big drop": long_p.where(pos > 0, 0.0)},
                       trades, al, CAPITAL, start, last30, per_h, notes)
    rep["market"] = "XAU/USD"
    rep["rules"] = __doc__
    rep["thresholds"] = {"alarm_down_top10": q_dn, "calibrated_on": [str(pre.index[0]), str(pre.index[-1])]}
    rep["big_drop_days_in_window"] = [{"date": str(t.date()), "ret": float(rd[t]), "z": float(zd[t])} for t in big_drop_days if t >= start]
    rep["signals_in_window"] = {"blowoff_short_hours": int(fade_sig[fade_sig.index >= start].sum())}
    rep["capital_note"] = ("With $100, 1 oz of gold (about $4,300) is too large for most brokers' smallest lot; the test assumes a "
                           "fractional position (e.g. a CFD with 0.001-lot sizing) worth the account balance.")
    write_json(rep, f"{RES}/strategy.json")
    s = rep["summary"]
    print(json.dumps({k: s[k] for k in ("start_capital", "end_capital", "pnl_usd", "benchmark_end_usd", "total_return", "max_drawdown",
                                        "benchmark_max_drawdown", "sharpe", "trades", "win_rate", "by_component_usd", "exposure")}, indent=1))
    print("months", rep["months"])
    for t in rep["trades"]:
        print(t["strategy"], t["side"], t["entry_time"][:16], t["entry"], "->", (t["exit_time"] or "open")[:16], t["exit"], f"{t['ret'] * 100:.2f}%")
    print("big drop days", rep["big_drop_days_in_window"], rep["signals_in_window"])


if __name__ == "__main__":
    main()
