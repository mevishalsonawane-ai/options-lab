"""R140 Amendment G (PREREG.md, commit 0d7e3e4): Boss's internal-origin-low sweep after a BOS ("turtle soup" of the
low that launched the break), built from his description and diagram (31 Oct 2026). US gold; information only.

Signal source: XAUUSD spot candles (the underlying). Entry after the sweep; SL under the sweep; TP at the impulse
high; 60% lock after +1R; 12-hour cap.

    python -m research.hunt.r140_gold_0600.r140k design
    python -m research.hunt.r140_gold_0600.r140k holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
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

WAIT, SIMPLE_RECOVER = 30, 3
RULES = {"K1": (15, "strict", False), "K2": (15, "simple", False), "K3": (5, "strict", False), "K4": (15, "strict", True)}
MIN_TRADES = 100


def setups(b: pd.DataFrame, minutes: int, mode: str, htf: np.ndarray | None = None) -> list[dict]:
    """BUY and SELL triggers in time order. State per side: the last confirmed swing, the open setup."""
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    L = P.PIVOT
    sh = sl_ = None                     # (price, bar index, broken?)
    up = dn = None                      # open setups
    out = []
    for i in range(len(b)):
        j = i - L
        if j - L >= 0:
            if all(h[j] > x for k, x in enumerate(h[j - L:i + 1]) if k != L):
                sh = [h[j], j, False]
            if all(l[j] < x for k, x in enumerate(l[j - L:i + 1]) if k != L):
                sl_ = [l[j], j, False]
        t_close = b.index[i] + pd.Timedelta(minutes=minutes)
        # ---- open setups: check the trigger on this candle first (a BOS on this candle starts a new one below)
        for side in (1, -1):
            s = up if side > 0 else dn
            if s is None or i <= s["bos"]:
                continue
            if i - s["bos"] > WAIT:
                s = None
            else:
                key, ext = s["key"], s["ext"]
                crossed = (l[i] < key) if side > 0 else (h[i] > key)
                back = (c[i] > key) if side > 0 else (c[i] < key)
                fire = False
                if mode == "strict":
                    if crossed and back:
                        stop, fire = (l[i] if side > 0 else h[i]), True
                    elif crossed:
                        s = None
                else:
                    if crossed and s["dip"] is None:
                        s["dip"], s["dip_ext"] = i, (l[i] if side > 0 else h[i])
                    elif crossed:
                        s["dip_ext"] = min(s["dip_ext"], l[i]) if side > 0 else max(s["dip_ext"], h[i])
                    if s["dip"] is not None and back:
                        stop, fire = s["dip_ext"], True
                    elif s["dip"] is not None and i - s["dip"] >= SIMPLE_RECOVER:
                        s = None
                if fire:
                    ok_trend = htf is None or (htf[i] > 0 if side > 0 else htf[i] < 0)
                    risk, reward = side * (c[i] - stop), side * (ext - c[i])
                    if ok_trend and risk > 0 and reward > 0 and reward / risk >= P.RR_MIN:
                        out.append(dict(t=t_close, side=side, sl=float(stop), tp=float(ext), close=float(c[i])))
                    s = None
                elif s is not None:
                    s["ext"] = max(ext, h[i]) if side > 0 else min(ext, l[i])
            if side > 0:
                up = s
            else:
                dn = s
        # ---- BOS on this candle's close: a new setup
        if sh is not None and not sh[2] and c[i] > sh[0]:
            sh[2] = True
            up = dict(bos=i, key=float(l[sh[1]:i + 1].min()), ext=float(h[i]), dip=None, dip_ext=None)
        if sl_ is not None and not sl_[2] and c[i] < sl_[0]:
            sl_[2] = True
            dn = dict(bos=i, key=float(h[sl_[1]:i + 1].max()), ext=float(l[i]), dip=None, dip_ext=None)
    return out


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    sig = {}
    for k, (m, trig, use_htf) in RULES.items():
        b = P.bars(X.df, m)
        sig[k] = setups(b, m, trig, P.htf_trend(X.df, b, m) if use_htf else None)
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 7)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k in RULES:
            ts = P.run_trades(X, sig[k], True, False, R.XAU_DESIGN)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = P.twins(X, ts, R.XAU_DESIGN, True, rng)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["signals_total"] = len([x for x in sig[k] if R.in_p(P.ist_date(x["t"]), R.XAU_DESIGN)])
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            s["long_net"] = float(sum(t["net"] for t in ts if t["side"] > 0))
            s["short_net"] = float(sum(t["net"] for t in ts if t["side"] < 0))
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 99
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendG_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendG_design.md").write_text("# R140 Amendment G design results (internal-origin-low sweep)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["signals_total"], s["exits"], "longs", s["longs"], f"long ${s['long_net']:+,.0f} short ${s['short_net']:+,.0f}",
                  json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendG_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment G holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendG_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment G rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            ts = P.run_trades(X, sig[k], True, False, P.HOLD)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendG_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
