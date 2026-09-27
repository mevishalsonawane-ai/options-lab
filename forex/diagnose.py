"""Why the gold strategy made so little: bar-by-bar and multi-bar network scans, what each session
offered, a post-mortem of every trade, and candidate fixes judged first on the development year and
only then on the 3-month test window. Writes forex/results/diagnose.json. Run after strategy.py."""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.diagnose import horizon_scan, sessions, summarize_bt, trade_autopsy  # noqa: E402
from marketlab.features import atr  # noqa: E402
from marketlab.io import read, write_json  # noqa: E402
from marketlab.strategy import hold_signal, pnl_from_positions  # noqa: E402

HERE = os.path.dirname(__file__)
DATA, RES = os.path.join(HERE, "data"), os.path.join(HERE, "results")
COST = 0.00015


def rules(h, d, al, q_dn, stretch=0.08, trail_atr=None, max_hold=24, buy_drop=True):
    c = h["close"]
    r = np.log(c).diff()
    lo480 = c / c.rolling(480).min() - 1
    vol_up = r.rolling(24).std() > r.rolling(480).std()
    fade = (al["p_dn"].reindex(h.index) >= q_dn) & (lo480 > stretch) & vol_up
    if trail_atr is None:
        short_p = hold_signal(fade, max_hold)
    else:
        a = atr(h, 14).values
        cv, lv, fs = c.values, h["low"].values, fade.fillna(False).values
        arr, cur, best, since = np.zeros(len(c)), 0, 0.0, 0
        for i in range(len(c)):
            if cur:
                since += 1
                best = min(best, lv[i])
                if cv[i] > best + trail_atr * a[i] or since >= max_hold:
                    cur = 0
            if not cur and fs[i]:
                cur, best, since = 1, lv[i], 0
            arr[i] = cur
        short_p = pd.Series(arr, index=c.index)
    long_p = pd.Series(0.0, index=h.index)
    if buy_drop:
        rd = np.log(d["close"]).diff()
        zd = rd / rd.rolling(60, min_periods=30).std().shift(1)
        ny = h.index.tz_convert("America/New_York")
        sess = pd.Series(pd.to_datetime((ny + pd.Timedelta(hours=7)).date).tz_localize("UTC"), index=h.index)
        days = list(d.index)
        for day in zd.index[(zd < -2).values]:
            i = days.index(day)
            ex = days[min(i + 20, len(days) - 1)]
            ent = sess[sess == day].index
            inb = sess[(sess > day) & (sess <= ex)].index
            if len(ent) and len(inb):
                long_p.loc[(long_p.index >= ent[-1]) & (long_p.index < inb[-1])] = 1.0
    pos = (long_p - short_p).clip(-1, 1)
    lr, _ = pnl_from_positions(pos, r, COST)
    return pos, lr, fade


def main():
    d, h = read(f"{DATA}/xauusd_1d.csv"), read(f"{DATA}/xauusd_1h.csv")
    per_h = len(h) / max((h.index[-1] - h.index[0]).days / 365.25, 0.1)
    al = read(f"{RES}/alarms_hourly.csv")
    st = json.load(open(f"{RES}/strategy.json"))
    end = h.index[-1]
    win_start = end.normalize() - pd.Timedelta(days=91)
    dev_start = al.index[0]
    pre = al[al.index < win_start]
    q10, q05 = float(pre["p_dn"].quantile(0.9)), float(pre["p_dn"].quantile(0.95))
    res = {"market": "XAU/USD", "dev": [str(dev_start), str(win_start)], "window": [str(win_start), str(end)]}
    s_all, sd = sessions(h, win_start, point=(10, 25, 50, 100, 150))
    res["sessions"] = s_all
    res["session_rows"] = [{"date": str(t.date()), "range_pts": float(r.range_pts), "range_pct": float(r.range_pct), "oc_pct": float(r.oc_pct)}
                           for t, r in sd.iterrows()]
    res["autopsy"] = trade_autopsy(st["trades"], h)
    print("multi-horizon scan")
    scan, _ = horizon_scan(h, per_h, COST, split=win_start, step=1200)
    res["scan"] = scan
    variants = {}
    for name, kw in (("Original rules", {}), ("Stricter alarm (top 5%)", {"_q": q05}),
                     ("Trailing stop (2 ATR), up to 72h", {"trail_atr": 2.0, "max_hold": 72}),
                     ("Stricter alarm + trailing stop", {"_q": q05, "trail_atr": 2.0, "max_hold": 72})):
        q = kw.pop("_q", q10)
        pos, lr, fade = rules(h, d, al, q, **kw)
        sl = lambda s, e: summarize_bt(lr[(lr.index >= s) & (lr.index < e)], per_h)  # noqa: E731
        variants[name] = {"dev": sl(dev_start, win_start), "window": sl(win_start, end + pd.Timedelta(hours=1)),
                          "short_entries_dev": int(((pos < 0) & (pos.shift(1) >= 0))[(pos.index >= dev_start) & (pos.index < win_start)].sum()),
                          "exposure_window": float((pos[pos.index >= win_start] != 0).mean())}
    res["variants"] = variants
    write_json(res, f"{RES}/diagnose.json")
    print(json.dumps({k: res[k] for k in ("sessions", "variants")}, indent=1))
    for a in res["autopsy"]:
        print(a["side"], a["entry_time"][:16], f"ret {a['ret']:+.3%} best {a['mfe']:+.3%} worst {a['mae']:+.3%} best after {a['hours_to_best']:.0f}h, move before entry {a['move_before_entry_24h']:+.2%}")


if __name__ == "__main__":
    main()
