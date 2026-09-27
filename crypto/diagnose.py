"""Why the BTC strategy did not make money: bar-by-bar and multi-bar network scans, what each session
offered, a post-mortem of every trade, and candidate fixes judged first on the development year
(Jul 2025 - Jun 2026, alarms out-of-sample) and only then on the 3-month test window.
Writes crypto/results/diagnose.json. Run after strategy.py (it reuses the cached alarms)."""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.diagnose import horizon_scan, sessions, summarize_bt, trade_autopsy  # noqa: E402
from marketlab.features import atr, rsi  # noqa: E402
from marketlab.io import daily_index, read, write_json  # noqa: E402
from marketlab.strategy import bs_straddle, hold_signal, pnl_from_positions  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
COST, OPT_FEE, PER_YEAR = 0.0006, 0.0003, 8760


def directional(h, al, q_up, q_dn, trend_filter=False, trail_atr=None, max_hold=24, daily=None):
    c = h["close"]
    r = np.log(c).diff()
    hi480 = c / c.rolling(480).max() - 1
    below200 = c < c.rolling(200).mean()
    vol_up = r.rolling(24).std() > r.rolling(480).std()
    pu, pdn = al["p_up"].reindex(h.index), al["p_dn"].reindex(h.index)
    rise = (pu >= q_up) & ((hi480 >= -0.005) | (rsi(c) > 70)) & (pdn < q_dn)
    drop = (pdn >= q_dn) & below200 & vol_up & (pu < q_up)
    if trend_filter and daily is not None:
        dtrend = (daily["close"] < daily["close"].rolling(200).mean()).shift(1)
        drop = drop & dtrend.reindex(h.index.floor("D")).fillna(False).values
    if trail_atr is None:
        pos = (hold_signal(rise, max_hold) - hold_signal(drop, max_hold)).clip(-1, 1)
    else:
        a = (atr(h, 14)).values
        cv, hv, lv = c.values, h["high"].values, h["low"].values
        rs, ds = rise.fillna(False).values, drop.fillna(False).values
        pos_arr = np.zeros(len(c))
        cur, best, since = 0, 0.0, 0
        for i in range(len(c)):
            if cur != 0:
                since += 1
                best = max(best, hv[i]) if cur > 0 else min(best, lv[i])
                stop = best - trail_atr * a[i] if cur > 0 else best + trail_atr * a[i]
                if (cur > 0 and cv[i] < stop) or (cur < 0 and cv[i] > stop) or since >= max_hold:
                    cur = 0
            if cur == 0:
                if rs[i]:
                    cur, best, since = 1, hv[i], 0
                elif ds[i]:
                    cur, best, since = -1, lv[i], 0
            pos_arr[i] = cur
        pos = pd.Series(pos_arr, index=c.index)
    lr, _ = pnl_from_positions(pos, r, COST)
    return pos, lr


def straddles(h, al, q_up, q_dn, od, dv, start, end, mode, dthr):
    """mode: 'sell_calm' (original: sell unless alarm/DVOL jump), 'switch' (sell when calm, BUY when an alarm is high)."""
    c = h["close"]
    out = []
    for t0 in [t for t in h.index if start <= t < end and t.dayofweek == 4 and t.hour == 8]:
        t1 = t0 + pd.Timedelta(days=7)
        if t1 > h.index[-1]:
            break
        pday = t0.normalize() - pd.Timedelta(days=1)
        win = al[(al.index > t0 - pd.Timedelta(hours=24)) & (al.index <= t0)]
        alarm = len(win) and (win["p_up"].max() >= q_up or win["p_dn"].max() >= q_dn)
        jump = dv["close"].diff(5).get(pday, np.nan) >= dthr
        iv = od["atm_iv_short"].get(pday, np.nan)
        if not np.isfinite(iv):
            iv = 0.9 * dv["close"].get(pday, np.nan)
        S0, ST = c[t0], c[c.index <= t1].iloc[-1]
        prem = bs_straddle(S0, S0, 7 / 365, iv / 100)
        short_pnl = (prem - abs(ST - S0)) / S0 - 2 * OPT_FEE
        if mode == "sell_calm":
            side = 0 if (alarm or jump) else -1
        elif mode == "switch":
            side = 1 if alarm else (0 if jump else -1)
        else:
            side = -1
        pnl = 0.0 if side == 0 else (short_pnl if side < 0 else -short_pnl - 4 * OPT_FEE)
        out.append({"date": str(t0.date()), "side": {-1: "sell", 0: "skip", 1: "buy"}[side], "iv": iv,
                    "week_move": float(ST / S0 - 1), "pnl": float(pnl), "alarm": bool(alarm)})
    return out


def period_stats(lr, s, e):
    x = lr[(lr.index >= s) & (lr.index < e)]
    return summarize_bt(x, PER_YEAR)


def main():
    d, h = read(f"{DATA}/btc_1d.csv"), read(f"{DATA}/btc_1h.csv")
    dv, od = daily_index(read(f"{DATA}/dvol_1d.csv")), daily_index(read(f"{DATA}/options_daily.csv"))
    al = read(f"{RES}/alarms_hourly.csv")
    st = json.load(open(f"{RES}/strategy.json"))
    end = h.index[-1]
    win_start = end.normalize() - pd.Timedelta(days=91)
    dev_start = al.index[0]
    pre = al[al.index < win_start]
    q_up, q_dn = float(pre["p_up"].quantile(0.9)), float(pre["p_dn"].quantile(0.9))
    dthr = float(dv["close"].diff(5)[dv.index < win_start].quantile(0.8))
    res = {"market": "BTC/USDT", "dev": [str(dev_start), str(win_start)], "window": [str(win_start), str(end)]}

    print("sessions")
    s_all, sd = sessions(h, win_start, point=(100, 500, 1000, 2000, 5000))
    res["sessions"] = s_all
    res["session_rows"] = [{"date": str(t.date()), "range_pts": float(r.range_pts), "range_pct": float(r.range_pct), "oc_pct": float(r.oc_pct)}
                           for t, r in sd.iterrows()]
    print("trade autopsy")
    res["autopsy"] = trade_autopsy(st["trades"], h)
    print("multi-horizon scan")
    scan, _ = horizon_scan(h, PER_YEAR, COST, split=win_start)
    res["scan"] = scan

    print("variants")
    variants = {}
    for name, kw in (("Original rules", {}), ("Short only below the 200-day average", {"trend_filter": True}),
                     ("+ trailing stop (2 ATR), up to 72h", {"trend_filter": True, "trail_atr": 2.0, "max_hold": 72}),
                     ("Longs only, trailing stop, up to 72h", {"trend_filter": True, "trail_atr": 2.0, "max_hold": 72, "_longs_only": True})):
        longs_only = kw.pop("_longs_only", False)
        pos, lr = directional(h, al, q_up, q_dn, daily=d, **kw)
        if longs_only:
            pos = pos.clip(lower=0)
            lr, _ = pnl_from_positions(pos, np.log(h["close"]).diff(), COST)
        variants[name] = {"dev": period_stats(lr, dev_start, win_start), "window": period_stats(lr, win_start, end + pd.Timedelta(hours=1)),
                          "exposure_window": float((pos[pos.index >= win_start] != 0).mean())}
    res["directional_variants"] = variants
    opt = {}
    for mode, label in (("always", "Sell every Friday"), ("sell_calm", "Original: sell unless an alarm is high"),
                        ("switch", "Switch: sell when calm, BUY when an alarm is high")):
        rows_dev = straddles(h, al, q_up, q_dn, od, dv, dev_start, win_start, mode, dthr)
        rows_win = straddles(h, al, q_up, q_dn, od, dv, win_start, end, mode, dthr)
        agg = lambda rows: {"weeks": len(rows), "traded": sum(r["side"] != "skip" for r in rows),  # noqa: E731
                            "total": float(sum(r["pnl"] for r in rows)), "worst": float(min([r["pnl"] for r in rows] or [0])),
                            "best": float(max([r["pnl"] for r in rows] or [0])),
                            "win_rate": float(np.mean([r["pnl"] > 0 for r in rows if r["side"] != "skip"])) if any(r["side"] != "skip" for r in rows) else None}
        opt[label] = {"dev": agg(rows_dev), "window": agg(rows_win), "weeks_window": rows_win}
    res["straddle_variants"] = opt
    # how well do alarms call big weeks? (magnitude vs direction)
    rows = straddles(h, al, q_up, q_dn, od, dv, dev_start, end, "always", dthr)
    big = [abs(r["week_move"]) for r in rows]
    thr = float(np.quantile(big, 0.8))
    a_on = [abs(r["week_move"]) for r in rows if r["alarm"]]
    a_off = [abs(r["week_move"]) for r in rows if not r["alarm"]]
    res["alarm_vs_week_size"] = {"weeks": len(rows), "alarm_weeks": len(a_on), "avg_abs_move_alarm": float(np.mean(a_on)) if a_on else None,
                                 "avg_abs_move_quiet": float(np.mean(a_off)) if a_off else None,
                                 "big_week_threshold": thr,
                                 "big_week_share_alarm": float(np.mean([x >= thr for x in a_on])) if a_on else None,
                                 "big_week_share_quiet": float(np.mean([x >= thr for x in a_off])) if a_off else None}
    write_json(res, f"{RES}/diagnose.json")
    print(json.dumps({k: res[k] for k in ("sessions", "directional_variants", "alarm_vs_week_size")}, indent=1, default=str))
    for k, v in opt.items():
        print(k, v["dev"], v["window"])


if __name__ == "__main__":
    main()
