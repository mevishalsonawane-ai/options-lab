"""R140 Amendment K (PREREG.md, commit 651956f): Boss's five setups (15m, 60% lock) kept only when the signal falls
06:00-08:00 IST or 16:00-18:00 IST. US gold; information only. Signal source: XAUUSD spot candles.

    python -m research.hunt.r140_gold_0600.r140w design
    python -m research.hunt.r140_gold_0600.r140w holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
"""
from __future__ import annotations

import json
import sys
from datetime import datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140g as G
from research.hunt.r140_gold_0600 import r140j as J
from research.hunt.r140_gold_0600 import r140k as K
from research.hunt.r140_gold_0600 import r140p as P
from research.hunt.r140_gold_0600 import r140q as Q
from research.hunt.r140_gold_0600 import r140t as T

WINDOWS = {"am": (6 * 60, 8 * 60), "pm": (16 * 60, 18 * 60)}           # IST minutes of the day, entry time
SETUPS = ("F2", "R2", "K1", "T2", "J2")
RULES = {f"W{2 * n + 1}": (s, "am") for n, s in enumerate(SETUPS)} | {f"W{2 * n + 2}": (s, "pm") for n, s in enumerate(SETUPS)}
RULES = dict(sorted(RULES.items(), key=lambda kv: int(kv[0][1:])))
MIN_TRADES = 30


def setup_signals(X) -> dict:
    b = P.bars(X.df, 15)
    return {"F2": P.signals(X.df, 15), "R2": Q.signals(X.df, 15, True), "K1": K.setups(b, 15, "strict"),
            "T2": T.retests(b, 15), "J2": J.smc_signals(b, 15, True)}


def ist_minute(t_utc: pd.Timestamp) -> int:
    t = t_utc + pd.Timedelta(hours=5, minutes=30)
    return t.hour * 60 + t.minute


def in_window(sigs, win):
    a, z = WINDOWS[win]
    return [s for s in sigs if a <= ist_minute(s["t"]) < z]


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    base = setup_signals(X)
    sig = {k: in_window(base[s], w) for k, (s, w) in RULES.items()}
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 11)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json", "amendG_design.json",
                   "amendH_design.json", "amendI_design.json", "amendJ_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k in RULES:
            ts = P.run_trades(X, sig[k], True, False, R.XAU_DESIGN)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = P.twins(X, ts, R.XAU_DESIGN, True, rng)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 123
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
            s["setup"], s["window"] = RULES[k]
        (R.RESULTS / "amendK_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendK_design.md").write_text("# R140 Amendment K design results (Boss's setups, 06-08 / 16-18 IST)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, RULES[k], s["exits"], json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendK_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment K holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendK_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment K rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            ts = P.run_trades(X, sig[k], True, False, P.HOLD)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendK_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
