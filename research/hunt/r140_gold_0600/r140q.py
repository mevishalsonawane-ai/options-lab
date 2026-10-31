"""R140 Amendment H (PREREG.md, commit 67651d5): Boss's refined Pine "5-Step Liquidity Sweep POI [Refined]" ported
as written onto XAUUSD (information only; not tradable via Zerodha). Signal source: XAUUSD spot candles.

    python -m research.hunt.r140_gold_0600.r140q design
    python -m research.hunt.r140_gold_0600.r140q holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
"""
from __future__ import annotations

import json
import sys
from datetime import datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140g as G
from research.hunt.r140_gold_0600 import r140p as P

RULES = {"R1": (15, True, False), "R2": (15, True, True), "R3": (5, True, False), "R4": (5, True, True),
         "R5": (15, False, True)}                      # (chart minutes, HTF filter, lock)
MIN_TRADES = 100
TP_ATR_FALLBACK, SL_ZONE_FRAC = 3.0, 0.5


def htf_trend_lagged(df: pd.DataFrame, b: pd.DataFrame, minutes: int) -> np.ndarray:
    """close[1] vs ema(close, 50)[1] of the 60-minute candles, lookahead_off on history: the 60m candle BEFORE the last
    completed one at each chart candle's close."""
    h = P.bars(df, 60)
    ema = h.close.ewm(span=P.HTF_EMA, adjust=False).mean()
    trend = np.sign(h.close - ema).values
    h_close_time = (h.index + pd.Timedelta(minutes=60)).values
    c_close_time = (b.index + pd.Timedelta(minutes=minutes)).values
    k = np.searchsorted(h_close_time, c_close_time, side="right") - 1 - 1        # one more completed candle back
    return np.where(k >= P.HTF_EMA - 1, trend[np.clip(k, 0, None)], 0.0)


def signals(df: pd.DataFrame, minutes: int, use_htf: bool = True) -> list[dict]:
    b = P.bars(df, minutes)
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    atr = P.rma_atr(b)
    tr = htf_trend_lagged(df, b, minutes)
    L = P.PIVOT
    act_hi = act_lo = tgt_hi = tgt_lo = np.nan
    out = []
    for i in range(len(b)):
        j = i - L
        if j - L >= 0:
            if all(h[j] > x for k, x in enumerate(h[j - L:i + 1]) if k != L):
                tgt_hi, act_hi = act_hi, h[j]
            if all(l[j] < x for k, x in enumerate(l[j - L:i + 1]) if k != L):
                tgt_lo, act_lo = act_lo, l[j]
        if i == 0 or not np.isfinite(atr[i]):
            continue
        a = atr[i]
        z = a * P.ZONE_ATR
        bull = (not use_htf) or tr[i] > 0
        bear = (not use_htf) or tr[i] < 0
        in_dem = np.isfinite(act_lo) and l[i] <= act_lo + z and h[i] >= act_lo - z
        in_sup = np.isfinite(act_hi) and h[i] >= act_hi - z and l[i] <= act_hi + z
        bull_sw = np.isfinite(act_lo) and l[i] < act_lo and c[i] > act_lo
        bear_sw = np.isfinite(act_hi) and h[i] > act_hi and c[i] < act_hi
        bull_cf = c[i] > o[i] and c[i] > h[i - 1]
        bear_cf = c[i] < o[i] and c[i] < l[i - 1]
        t_close = b.index[i] + pd.Timedelta(minutes=minutes)
        if bull and in_dem and bull_sw and bull_cf:
            tp = tgt_hi if (np.isfinite(tgt_hi) and tgt_hi > c[i]) else (act_hi if (np.isfinite(act_hi) and act_hi > c[i])
                                                                          else c[i] + TP_ATR_FALLBACK * a)
            sl = min(l[i], act_lo - z * SL_ZONE_FRAC if np.isfinite(act_lo) else l[i] - a)
            risk, reward = c[i] - sl, tp - c[i]
            if risk > 0 and reward / risk >= P.RR_MIN:
                out.append(dict(t=t_close, side=1, sl=float(sl), tp=float(tp), close=float(c[i])))
        if bear and in_sup and bear_sw and bear_cf:
            tp = tgt_lo if (np.isfinite(tgt_lo) and tgt_lo < c[i]) else (act_lo if (np.isfinite(act_lo) and act_lo < c[i])
                                                                          else c[i] - TP_ATR_FALLBACK * a)
            sl = max(h[i], act_hi + z * SL_ZONE_FRAC if np.isfinite(act_hi) else h[i] + a)
            risk, reward = sl - c[i], c[i] - tp
            if risk > 0 and reward / risk >= P.RR_MIN:
                out.append(dict(t=t_close, side=-1, sl=float(sl), tp=float(tp), close=float(c[i])))
    return out


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    sig = {k: signals(X.df, m, htf) for k, (m, htf, _) in RULES.items()}
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 8)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json", "amendG_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k, (m, htf, lock) in RULES.items():
            ts = P.run_trades(X, sig[k], lock, False, R.XAU_DESIGN)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = P.twins(X, ts, R.XAU_DESIGN, lock, rng)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            s["long_net"] = float(sum(t["net"] for t in ts if t["side"] > 0))
            s["short_net"] = float(sum(t["net"] for t in ts if t["side"] < 0))
            s["avg_rr"] = float(np.mean([t["tp_usd"] / t["stop_usd"] for t in ts])) if ts else 0.0
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 104
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendH_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendH_design.md").write_text("# R140 Amendment H design results (Boss's refined Pine)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["exits"], round(s["avg_rr"], 2), f"long ${s['long_net']:+,.0f} short ${s['short_net']:+,.0f}",
                  json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendH_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment H holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendH_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment H rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            m, htf, lock = RULES[k]
            ts = P.run_trades(X, sig[k], lock, False, P.HOLD)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendH_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
