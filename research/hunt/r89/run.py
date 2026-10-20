"""R89 run (PREREG): signals, straddles, random-entry twins, gates.

    python -m research.hunt.r89.run design      # design walk-forward (to 31 May 2026) -> results/design.json
    python -m research.hunt.r89.run oos         # once: 1 Jun - 19 Oct 2026 -> results/oos.json (refuses a 2nd run)
"""
from __future__ import annotations

import json
import sys
import warnings
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from research.hunt.r88 import cfg as R88
from research.hunt.r88 import data as D88
from research.hunt.r88 import run as RUN88
from research.hunt.r88 import stats as ST

from . import sim

warnings.filterwarnings("ignore")
HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
OOS_FLAG = RESULTS / "OOS_READ"
SYMS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "GOLDM", "SILVERM", "CRUDEOIL", "NATURALGAS")
TF, TOP, N_TWINS, SEED = 15, 0.90, 500, 89
WINDOW = {"index": (9 * 60 + 30, 14 * 60 + 30), "mcx": (9 * 60 + 15, 22 * 60)}
OOS_START, OOS_END = R88.HOLD_START, R88.END


def window(sym: str):
    return WINDOW["mcx" if R88.is_mcx(sym) else "index"]


def load_tape(sym: str) -> sim.OptionTape:
    a, b = D88.load_span(sym)
    flag = "MONTH" if R88.is_mcx(sym) else R88.BOOK_FLAG[sym]
    fs = [f for f in D88.lake_files(f"opt_{sym}_{flag}_*.parquet") if a.year <= int(f[-12:-8]) <= b.year]
    cols = ["ts", "offset", "side", "strike", "open", "close", "volume"]
    d = pd.concat([pd.read_parquet(f, columns=cols) for f in fs], ignore_index=True)
    d["ts"] = d.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None).dt.floor("min")
    d["side"] = d.side.astype(str)
    d["volume"] = d.volume.astype(float).where(d.volume >= 0)
    d = d[d.ts.dt.date >= a]
    return sim.OptionTape.build(sym, d)


def signals(sym: str) -> pd.DataFrame:
    """Every 15-minute candle with its walk-forward size prediction and the signal (top 10% of the model's own
    training predictions). Months 1-6 are training only (pred NaN)."""
    k = pd.read_parquet(RUN88.path(sym, TF))
    X = RUN88.features(k)
    month = k.day.dt.to_period("M")
    months = sorted(month.unique())
    pred, thr = np.full(len(k), np.nan), np.full(len(k), np.nan)
    for m in months[6:]:
        tr, te = (month < m).to_numpy() & k.S.notna().to_numpy(), (month == m).to_numpy()
        reg = HistGradientBoostingRegressor(max_iter=200, learning_rate=0.05, random_state=R88.SEED).fit(X[tr], k.S[tr])
        t = np.quantile(reg.predict(X[tr]), TOP)
        pred[te], thr[te] = reg.predict(X[te]), t
    om = sim.open_min(sym)
    tod = k.ts.dt.hour * 60 + k.ts.dt.minute
    lo, hi = window(sym)
    k = k.assign(pred=pred, thr=thr, m0=(tod - om).to_numpy(), tod=tod.to_numpy(),
                 eligible=((tod >= lo) & (tod <= hi)).to_numpy())
    k["signal"] = k.eligible & np.isfinite(k.pred) & (k.pred >= k.thr)
    return k[["ts", "day", "slot", "m0", "tod", "pred", "thr", "eligible", "signal", "c", "S"]]


def non_overlap(days, m0, m1) -> np.ndarray:
    """Keep entries in time order per day, skipping any that starts before the previous kept exit."""
    keep = np.zeros(len(m0), bool)
    last = {}
    for i in np.lexsort((m0, days)):
        d = days[i]
        if m0[i] >= last.get(d, -1):
            keep[i] = True
            last[d] = m1[i]
    return keep


def trades(sym, k, tape, expiries, hold) -> pd.DataFrame:
    s = k[k.signal].reset_index(drop=True)
    days = s.day.to_numpy()
    m1 = sim.exit_minute(sym, days, s.m0.to_numpy(), hold, expiries)
    keep = non_overlap(days, s.m0.to_numpy(), m1)
    s, m1 = s[keep].reset_index(drop=True), m1[keep]
    p = sim.price(tape, sym, s.day, s.m0.to_numpy(), m1)
    return pd.concat([s, p.drop(columns=[]).assign(m1=m1)], axis=1)


def twins(sym, k, tape, expiries, hold, rule: pd.DataFrame, n=N_TWINS, seed=SEED) -> np.ndarray:
    """Net of n random-entry twins: on each day the rule traded (priced trades), the same number of straddles at
    random eligible 15-minute candle starts, non-overlapping, priced the same way."""
    rng = np.random.default_rng(seed)
    elig = k[k.eligible].groupby("day").m0.apply(np.array).to_dict()
    per_day = rule[rule.ok].groupby("day").size()
    rows = []
    for t in range(n):
        for d, cnt in per_day.items():
            pool = elig.get(d)
            if pool is None or not len(pool):
                continue
            for _ in range(20):
                pick = np.sort(rng.choice(pool, min(cnt, len(pool)), replace=False))
                ex = sim.exit_minute(sym, [d] * len(pick), pick, hold, expiries)
                if (pick[1:] >= ex[:-1]).all():
                    break
            rows.extend((t, d, m0, m1) for m0, m1 in zip(pick, ex))
    tw = pd.DataFrame(rows, columns=["twin", "day", "m0", "m1"])
    p = sim.price(tape, sym, tw.day, tw.m0.to_numpy(), tw.m1.to_numpy())
    return p.net.fillna(0).groupby(tw.twin.to_numpy()).sum().reindex(range(n), fill_value=0).to_numpy()


def summary(t: pd.DataFrame) -> dict:
    t = t[t.ok]
    if not len(t):
        return dict(trades=0)
    win, loss = t.net[t.net > 0], t.net[t.net <= 0]
    cum = t.sort_values("ts").net.cumsum()
    by_m = t.groupby(t.day.dt.to_period("M")).net.sum()
    return dict(trades=int(len(t)), sessions=int(t.day.nunique()), win_rate=float((t.net > 0).mean()),
                avg_win=float(win.mean()) if len(win) else None, avg_loss=float(loss.mean()) if len(loss) else None,
                net=float(t.net.sum()), net_per_trade=float(t.net.mean()), gross=float(t.gross.sum()),
                cost=float(t.cost.sum()), gross_positive_share=float((t.gross > 0).mean()),
                median_entry_premium=float(t.entry_prem.median()), stale_exits=int(t.stale.sum()),
                max_drawdown=float((cum - cum.cummax()).min()),
                months={str(m): float(v) for m, v in by_m.items()},
                green_months=f"{int((by_m > 0).sum())}/{len(by_m)}")


def evaluate(part: str) -> list[dict]:
    out = []
    for sym in SYMS:
        k = signals(sym)
        tape = load_tape(sym)
        exps = D88.detect_expiries(pd.read_parquet(R88.CACHE / f"{sym}_chain.parquet"))
        d = k.day.dt.date
        sel = (d <= R88.DESIGN_END) if part == "design" else ((d >= OOS_START) & (d <= OOS_END))
        kp = k[sel].reset_index(drop=True)
        for hname, hold in sim.HOLDS.items():
            t = trades(sym, kp, tape, exps, hold)
            tw = twins(sym, kp, tape, exps, hold, t)
            s = summary(t)
            net = s.get("net", 0.0)
            s.update(sym=sym, hold=hname, part=part, skipped_no_print=int((~t.ok).sum()),
                     twin_median=float(np.median(tw)), twin_p=float((np.sum(tw >= net) + 1) / (len(tw) + 1)))
            out.append(s)
            t.to_csv(RESULTS / f"trades_{part}_{sym}_{hname}.csv", index=False)
            print(part, sym, hname, s.get("trades"), "win", round(s.get("win_rate", 0) or 0, 3), "net",
                  round(net), "twin p", round(s["twin_p"], 3), flush=True)
    return out


def main(part: str) -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    if part == "oos":
        if OOS_FLAG.exists():
            raise SystemExit("the out-of-sample run was already made; it runs once")
        if not (RESULTS / "design.json").exists():
            raise SystemExit("run design first")
        OOS_FLAG.write_text(datetime.utcnow().isoformat(timespec="seconds") + " UTC out-of-sample run started\n")
    res = evaluate(part)
    if part == "design":
        q = ST.bh([r["twin_p"] for r in res])
        for r, qq in zip(res, q):
            r["twin_q"] = float(qq)
            r["design_ok"] = bool(r.get("net", 0) > 0 and qq < 0.10)
    json.dump(res, open(RESULTS / f"{part}.json", "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
