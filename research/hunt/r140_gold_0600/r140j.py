"""R140 Amendment J (PREREG.md, commit 0a20db7): Boss's Pine v5 "Internal SMC BOS & Sweep Signals (HL/LH)" ported as
written onto XAUUSD (information only). The script has no exits: SL = the sweep candle's wick, TP = 2R, 12-hour cap;
J2-J5 add the house 60% lock after 1R. Signal source: XAUUSD spot candles.

    python -m research.hunt.r140_gold_0600.r140j design
    python -m research.hunt.r140_gold_0600.r140j holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
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

TP_R = 2.0
RULES = {"J1": (15, True, False, False), "J2": (15, True, True, False), "J3": (15, False, True, False),
         "J4": (5, True, True, False), "J5": (15, True, True, True)}      # (chart, BOS on close, lock, HTF)
MIN_TRADES = 100


def smc_signals(b: pd.DataFrame, minutes: int, confirm_close: bool = True, htf: np.ndarray | None = None) -> list[dict]:
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    L = P.PIVOT
    last_hi = last_lo = np.nan
    prev_hi = prev_lo = np.nan          # lastHi / lastLo at the END of the previous bar (Pine series history)
    trend = 0
    track_hl = track_lh = False
    t_hl = t_lh = np.nan
    out = []
    for i in range(len(b)):
        j = i - L
        if j - L >= 0:
            if all(h[j] > x for k, x in enumerate(h[j - L:i + 1]) if k != L):
                last_hi = h[j]
            if all(l[j] < x for k, x in enumerate(l[j - L:i + 1]) if k != L):
                last_lo = l[j]
        src_up = c if confirm_close else h
        src_dn = c if confirm_close else l
        brk_hi = (i > 0 and np.isfinite(last_hi) and np.isfinite(prev_hi)
                  and src_up[i] > last_hi and src_up[i - 1] <= prev_hi)
        brk_lo = (i > 0 and np.isfinite(last_lo) and np.isfinite(prev_lo)
                  and src_dn[i] < last_lo and src_dn[i - 1] >= prev_lo)
        if np.isfinite(last_hi) and brk_hi:
            if trend != 1:
                trend, track_lh = 1, False
            elif np.isfinite(last_lo):
                t_hl, track_hl = last_lo, True
            last_hi = np.nan
        if np.isfinite(last_lo) and brk_lo:
            if trend != -1:
                trend, track_hl = -1, False
            elif np.isfinite(last_hi):
                t_lh, track_lh = last_hi, True
            last_lo = np.nan
        t_close = b.index[i] + pd.Timedelta(minutes=minutes)
        if track_hl and np.isfinite(t_hl):
            if l[i] < t_hl and c[i] > t_hl:
                risk = c[i] - l[i]
                if risk > 0 and (htf is None or htf[i] > 0):
                    out.append(dict(t=t_close, side=1, sl=float(l[i]), tp=float(c[i] + TP_R * risk), close=float(c[i])))
                track_hl, t_hl = False, np.nan
            elif c[i] < t_hl:
                track_hl, t_hl = False, np.nan
        if track_lh and np.isfinite(t_lh):
            if h[i] > t_lh and c[i] < t_lh:
                risk = h[i] - c[i]
                if risk > 0 and (htf is None or htf[i] < 0):
                    out.append(dict(t=t_close, side=-1, sl=float(h[i]), tp=float(c[i] - TP_R * risk), close=float(c[i])))
                track_lh, t_lh = False, np.nan
            elif c[i] > t_lh:
                track_lh, t_lh = False, np.nan
        prev_hi, prev_lo = last_hi, last_lo
    return out


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    sig = {}
    for k, (m, cc, lock, use_htf) in RULES.items():
        b = P.bars(X.df, m)
        sig[k] = smc_signals(b, m, cc, P.htf_trend(X.df, b, m) if use_htf else None)
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 10)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json", "amendG_design.json",
                   "amendH_design.json", "amendI_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k, (m, cc, lock, _) in RULES.items():
            ts = P.run_trades(X, sig[k], lock, False, R.XAU_DESIGN)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = P.twins(X, ts, R.XAU_DESIGN, lock, rng)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            s["long_net"] = float(sum(t["net"] for t in ts if t["side"] > 0))
            s["short_net"] = float(sum(t["net"] for t in ts if t["side"] < 0))
            s["avg_stop_usd"] = float(np.mean([t["stop_usd"] for t in ts])) if ts else 0.0
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 113
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendJ_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendJ_design.md").write_text("# R140 Amendment J design results (Internal SMC BOS & HL/LH sweep)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["exits"], round(s["avg_stop_usd"], 2), f"long ${s['long_net']:+,.0f} short ${s['short_net']:+,.0f}",
                  json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendJ_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment J holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendJ_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment J rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            m, cc, lock, _ = RULES[k]
            ts = P.run_trades(X, sig[k], lock, False, P.HOLD)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendJ_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
