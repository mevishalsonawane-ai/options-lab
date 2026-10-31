"""R140 Amendment L (PREREG.md, commit 51237dd): enhancing Boss's scripts (combined 15m signals of his five setups)
with H11b's vote, the busy-hour filter, high win-rate exits, the reverse bet and the 11-13 IST window. US gold;
information only. Signal source: XAUUSD spot candles.

    python -m research.hunt.r140_gold_0600.r140l design
    python -m research.hunt.r140_gold_0600.r140l holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
"""
from __future__ import annotations

import json
import sys
from datetime import datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140e as E
from research.hunt.r140_gold_0600 import r140g as G
from research.hunt.r140_gold_0600 import r140h as H
from research.hunt.r140_gold_0600 import r140p as P
from research.hunt.r140_gold_0600 import r140w as W

ORDER = ("F2", "R2", "K1", "T2", "J2")
# (vote, busy, high win-rate exits, reverse, 11-13 window)
RULES = {"L1": (0, 0, 0, 0, 0), "L2": (1, 0, 0, 0, 0), "L3": (0, 1, 0, 0, 0), "L4": (1, 1, 0, 0, 0),
         "L5": (1, 1, 1, 0, 0), "L6": (0, 0, 0, 1, 0), "L7": (1, 1, 0, 1, 0), "L8": (1, 1, 0, 0, 1)}
MIN_TRADES = 50


def combined(base: dict) -> list[dict]:
    seen, out = set(), []
    for k in ORDER:
        for s in base[k]:
            key = (s["t"], s["side"])
            if key not in seen:
                seen.add(key)
                out.append(dict(s, src=k))
    return sorted(out, key=lambda s: (s["t"], ORDER.index(s["src"])))


def context(X: R.Xau, ind: pd.DataFrame) -> pd.DataFrame:
    """Per 15m candle (index = candle start, UTC): vote direction (+1 / -1 / 0) and the busy flag, earlier data only."""
    ist_h = ((ind.index + pd.Timedelta(hours=5, minutes=30)).hour).values
    med = ind.atr14.groupby(ist_h).transform(lambda s: s.expanding().median().shift(1))
    busy = (ind.atr14 >= med) & med.notna()
    return pd.DataFrame({"busy": busy, "ema": np.sign(ind.ema_spread), "rsi": np.sign(ind.rsi - 50),
                         "macd": np.sign(ind.macd_hist)}, index=ind.index)


def vote_at(X, ctx, t_close) -> int:
    bar = t_close - pd.Timedelta(minutes=15)
    if bar not in ctx.index:
        return 0
    r = ctx.loc[bar]
    c3 = np.sign(X.close_before(t_close) / X.close_before(H.last_ny_close(t_close)) - 1)
    v = [r.ema, r.rsi, r.macd, c3]
    return 1 if all(x == 1 for x in v) else (-1 if all(x == -1 for x in v) else 0)


def busy_at(ctx, t_close) -> bool:
    bar = t_close - pd.Timedelta(minutes=15)
    return bool(ctx.at[bar, "busy"]) if bar in ctx.index else False


def transform(sigs, X, ctx, cfg):
    vote, busy, hiwin, rev, win = cfg
    out = []
    for s in sigs:
        side, c = s["side"], s["close"]
        risk = abs(c - s["sl"])
        if risk <= 0:
            continue
        if rev:
            s = dict(s, side=-side, sl=c + side * risk, tp=c - side * 2 * risk)
        elif hiwin:
            s = dict(s, tp=c + side * 1.0 * risk)
        if win and not (11 * 60 <= W.ist_minute(s["t"]) < 13 * 60):
            continue
        if busy and not busy_at(ctx, s["t"]):
            continue
        if vote and vote_at(X, ctx, s["t"]) != s["side"]:
            continue
        out.append(s)
    return out


def build():
    X = R.Xau(R.load_xau())
    base = W.setup_signals(X)
    ind = E.indicators(X.bars15())
    ctx = context(X, ind)
    comb = combined(base)
    sig = {k: transform(comb, X, ctx, cfg) for k, cfg in RULES.items()}
    return X, sig


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X, sig = build()
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 12)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json", "amendG_design.json",
                   "amendH_design.json", "amendI_design.json", "amendJ_design.json", "amendK_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        res, p = {}, {}
        for k, cfg in RULES.items():
            lr = 0.5 if cfg[2] else P.LOCK_R
            ts = P.run_trades(X, sig[k], True, False, R.XAU_DESIGN, lr)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = P.twins(X, ts, R.XAU_DESIGN, True, rng, lock_r=lr)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            s["signals"] = len([x for x in sig[k] if R.in_p(P.ist_date(x["t"]), R.XAU_DESIGN)])
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 131
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendL_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendL_design.md").write_text("# R140 Amendment L design results (Boss's scripts, enhanced)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["signals"], s["exits"], json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendL_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment L holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendL_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment L rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            lr = 0.5 if RULES[k][2] else P.LOCK_R
            ts = P.run_trades(X, sig[k], True, False, P.HOLD, lr)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendL_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
