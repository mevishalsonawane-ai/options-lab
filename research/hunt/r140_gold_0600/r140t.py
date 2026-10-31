"""R140 Amendment I (PREREG.md, commit eeb5c1e): break and retest, from Boss's TradingView screenshot (31 Oct 2026).
A swing level breaks (a close beyond a +/- 0.25 ATR zone), price comes back into the zone and closes on the broken
side: trade it. SL just past the zone, TP 2R; the house 60% lock after 1R in T2-T4. US gold; information only.

Signal source: XAUUSD spot candles (the underlying).

    python -m research.hunt.r140_gold_0600.r140t design
    python -m research.hunt.r140_gold_0600.r140t holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
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

ZONE_HALF, SL_PAD, TP_R, MIN_GAP, WAIT = 0.25, 0.10, 2.0, 2, 30
RULES = {"T1": (15, False, False), "T2": (15, True, False), "T3": (5, True, False), "T4": (15, True, True)}
MIN_TRADES = 100


def retests(b: pd.DataFrame, minutes: int, htf: np.ndarray | None = None) -> list[dict]:
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    atr = P.rma_atr(b)
    L = P.PIVOT
    sh = sl_ = None                     # [price, broken?]
    st = {1: None, -1: None}            # side +1: broken swing HIGH -> buy the retest; -1: broken swing LOW -> sell
    out = []
    for i in range(len(b)):
        j = i - L
        if j - L >= 0:
            if all(h[j] > x for k, x in enumerate(h[j - L:i + 1]) if k != L):
                sh = [h[j], False]
            if all(l[j] < x for k, x in enumerate(l[j - L:i + 1]) if k != L):
                sl_ = [l[j], False]
        if not np.isfinite(atr[i]):
            continue
        t_close = b.index[i] + pd.Timedelta(minutes=minutes)
        # retests of open setups
        for side in (1, -1):
            s = st[side]
            if s is None or i <= s["brk"]:
                continue
            if i - s["brk"] > WAIT:
                st[side] = None
                continue
            lvl, lo_z, hi_z = s["lvl"], s["lo"], s["hi"]
            if side < 0 and c[i] > hi_z or side > 0 and c[i] < lo_z:
                st[side] = None                                       # level reclaimed
                continue
            if i - s["brk"] < MIN_GAP:
                continue
            touch = (h[i] >= lo_z and c[i] < lvl) if side < 0 else (l[i] <= hi_z and c[i] > lvl)
            if touch:
                ok = htf is None or (htf[i] < 0 if side < 0 else htf[i] > 0)
                stop = hi_z + SL_PAD * atr[i] if side < 0 else lo_z - SL_PAD * atr[i]
                risk = side * (c[i] - stop)
                if ok and risk > 0:
                    out.append(dict(t=t_close, side=side, sl=float(stop), tp=float(c[i] + side * TP_R * risk),
                                    close=float(c[i])))
                st[side] = None
        # new breaks on this close
        z = ZONE_HALF * atr[i]
        if sl_ is not None and not sl_[1] and c[i] < sl_[0] - z:
            sl_[1] = True
            st[-1] = dict(brk=i, lvl=sl_[0], lo=sl_[0] - z, hi=sl_[0] + z)
        if sh is not None and not sh[1] and c[i] > sh[0] + z:
            sh[1] = True
            st[1] = dict(brk=i, lvl=sh[0], lo=sh[0] - z, hi=sh[0] + z)
    return out


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    sig = {}
    for k, (m, lock, use_htf) in RULES.items():
        b = P.bars(X.df, m)
        sig[k] = retests(b, m, P.htf_trend(X.df, b, m) if use_htf else None)
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 9)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json", "amendG_design.json", "amendH_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k, (m, lock, _) in RULES.items():
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
        q = R.bh({**prior, **p})                                 # BH over 108
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendI_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendI_design.md").write_text("# R140 Amendment I design results (break and retest)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["exits"], round(s["avg_stop_usd"], 2), f"long ${s['long_net']:+,.0f} short ${s['short_net']:+,.0f}",
                  json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendI_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment I holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendI_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment I rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            m, lock, _ = RULES[k]
            ts = P.run_trades(X, sig[k], lock, False, P.HOLD)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendI_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
