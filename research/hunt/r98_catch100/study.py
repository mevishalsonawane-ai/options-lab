"""R98 study (PREREG.md). Commands:

    python -m research.hunt.r98_catch100.study design [rule ...]
    python -m research.hunt.r98_catch100.study holdout <rule>     # once, only for a rule passing every design gate

Twins: each trade re-run on a random OTHER design session of the same calendar month at the same clock minute and
side, same instrument and exits; 500 sets. BH over R96's 27 + R97's 7 + R98's 6 ideas. Gates as R97 (study.GATE).
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import study as S96
from research.hunt.r97_gold_reverse import data as D97
from research.hunt.r97_gold_reverse import study as S97
from research.hunt.r98_catch100 import cfg as K
from research.hunt.r98_catch100 import engine as EN
from research.hunt.r98_catch100 import rules as R

TWINS, SEED = 500, 98


def run_rule(rid: str, book, df: pd.DataFrame, last_day) -> list:
    rule = R.RULES[rid]
    out, busy, per_day = [], None, {}
    for r in rule.signals(df).itertuples():
        if busy is not None and book.m.ts[r.i_sig] < busy:
            continue
        if per_day.get(r.day, 0) >= rule.max_per_day:
            continue
        t = EN.simulate(book, rule.instrument, int(r.i_sig), int(r.side), last_day)
        if t is None:
            continue
        t.update(rule=rid, tod=int(r.tod))
        busy = np.datetime64(t["exit_ts"])
        per_day[r.day] = per_day.get(r.day, 0) + 1
        out.append(t)
    return out


def twins(rid: str, trades: list, book, period, n=TWINS, seed=SEED) -> np.ndarray:
    rule = R.RULES[rid]
    m = book.m
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    cache, pools = {}, {}
    for t in trades:
        d0 = np.datetime64(t["day"], "ns")
        key = (pd.Timestamp(d0).year, pd.Timestamp(d0).month)
        if key not in pools:
            pools[key] = [d for d in m.days if (pd.Timestamp(d).year, pd.Timestamp(d).month) == key
                          and D97.in_period(d, period)]
        pool = [d for d in pools[key] if d != d0]
        if not pool:
            continue
        for s in range(n):
            v = 0.0
            for _ in range(20):
                d = pool[rng.integers(len(pool))]
                ck = (d, t["tod"], t["side"])
                if ck not in cache:
                    i = m.at_or_after(d, t["tod"], until=t["tod"] + 2)
                    cache[ck] = None if i < 0 else EN.simulate(book, rule.instrument, i, t["side"], period[1])
                if cache[ck] is not None:
                    v = cache[ck]["net"]
                    break
            tot[s] += v
    return tot


def prior_ledger() -> list:
    from research.hunt.r97_gold_reverse import cfg as K97
    rows = S97.r96_ledger()
    r97 = json.loads((K97.RESULTS / "design.json").read_text())["rules"]
    return rows + [dict(round="R97", id=k, p=v["twin_p"]) for k, v in r97.items()]


def dataset(name: str) -> pd.DataFrame:
    return pd.read_parquet(K.CACHE / f"dataset_{name}.parquet")


def design(only=None):
    from research.hunt.r98_catch100 import model as MO
    book = D97.load_book(K.BOOK)
    df = dataset("design")
    df["pred"] = MO.walk_forward(df)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    res, frames = {}, []
    for rid in (only or R.ORDER):
        trades = run_rule(rid, book, df, K.DESIGN[1])
        fr = S96.frame(trades)
        frames.append(fr)
        s = S96.summarize(fr)
        fl = S97.folds(fr)
        tw = twins(rid, trades, book, K.DESIGN) if trades else np.zeros(TWINS)
        p = float((np.sum(tw >= s.get("net", 0)) + 1) / (TWINS + 1)) if trades else float("nan")
        res[rid] = dict(summary=s, folds=fl, twin_mean=float(tw.mean()), twin_p=p)
        print(rid, s.get("trades"), round(s.get("net", 0)), "p", round(p, 3), flush=True)
    prior = prior_ledger()
    qs = S96.bh([r.get("p") for r in prior] + [res[r]["twin_p"] for r in res])
    for rid, qv in zip(res, qs[len(prior):]):
        r = res[rid]
        r["bh_q"] = qv
        r["gates"] = S97.gates(r["summary"], r["folds"], r["twin_p"], qv)
        r["pass"] = bool(all(r["gates"].values()))
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), design=[str(x) for x in K.DESIGN],
               bh_family=dict(prior=len(prior), r98=len(res), total=len(prior) + len(res)), rules=res)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    pd.concat(frames, ignore_index=True).to_csv(K.RESULTS / "trades_design.csv", index=False)
    return out


def holdout(rid: str):
    from research.hunt.r98_catch100 import dataset as DS
    from research.hunt.r98_catch100 import model as MO
    lock = K.RESULTS / f"HOLDOUT_READ_{rid}.json"
    if lock.exists():
        raise SystemExit(f"holdout already read for {rid}")
    r = json.loads((K.RESULTS / "design.json").read_text())["rules"].get(rid)
    if r is None or not r.get("pass"):
        raise SystemExit(f"{rid} did not pass every design gate; the holdout stays locked")
    lock.write_text(json.dumps(dict(rule=rid, read_at=datetime.now(timezone.utc).isoformat()), indent=1))
    book = D97.load_book(K.BOOK)
    hold = DS.build(K.HOLDOUT, "holdout")
    hold["pred"] = MO.holdout_predict(dataset("design"), hold)
    trades = run_rule(rid, book, hold, K.HOLDOUT[1])
    fr = S96.frame(trades)
    s = S96.summarize(fr)
    tw = twins(rid, trades, book, K.HOLDOUT) if trades else np.zeros(TWINS)
    p = float((np.sum(tw >= s.get("net", 0)) + 1) / (TWINS + 1)) if trades else float("nan")
    ok = dict(net=s.get("net", 0) > 0, trades=s["trades"] >= S97.HOLD["min_trades"], twin_p=p <= S97.HOLD["p"],
              worst_month=s.get("worst_month", -np.inf) > S97.GATE["stop_month"],
              max_dd=s.get("max_dd", -np.inf) > S97.GATE["stop_dd"])
    out = dict(rule=rid, holdout=[str(x) for x in K.HOLDOUT], summary=s, twin_p=p, twin_mean=float(tw.mean()),
               gates=ok, pass_=bool(all(ok.values())))
    (K.RESULTS / f"holdout_{rid}.json").write_text(json.dumps(out, indent=1, default=str))
    fr.to_csv(K.RESULTS / f"trades_holdout_{rid}.csv", index=False)
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "design":
        design(only=sys.argv[2:] or None)
    elif cmd == "holdout":
        print(json.dumps(holdout(sys.argv[2]), indent=1, default=str))
    else:
        raise SystemExit(__doc__)
