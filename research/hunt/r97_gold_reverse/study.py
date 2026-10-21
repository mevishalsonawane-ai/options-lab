"""R97 step 4 study (PREREG.md). Commands:

    python -m research.hunt.r97_gold_reverse.study design [rule ...]   # the pre-registered rules on the design period
    python -m research.hunt.r97_gold_reverse.study holdout <rule>      # the LOCKED holdout, once, only for a full pass

Twins: each trade is re-run on a random OTHER session of the same book and calendar month (design days only), at the
same clock minute, same direction, same strike rule, same exits (its stop distance in points kept), 500 sets;
p = share of twin totals >= the rule's net (+1 smoothing). BH over the R96 ledger (all its rounds' twin p) plus every
R97 rule. Gates: as R96 PREREG s7 / round 2 (see cfg GATES), on 1x and 2x half-spreads.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import study as S96
from research.hunt.r97_gold_reverse import cfg as K
from research.hunt.r97_gold_reverse import data as D
from research.hunt.r97_gold_reverse import engine as EN

TWINS = 500
SEED = 97
GATE = dict(min_trades=30, p=0.05, q=0.10, folds_pos=3, stop_month=-13000.0, stop_dd=-40000.0, wo_top=3,
            model_share=0.10)
HOLD = dict(min_trades=10, p=0.10)


def rules_mod():
    from research.hunt.r97_gold_reverse import rules as R
    return R


def simulate(book, sp: dict, last_day):
    return EN.sim(book, sp["i_sig"], sp["side"], sp["ustop"], sp.get("trail_x"), sp.get("exit_tod"),
                  sp.get("itm", 0), sp.get("fill_wait", 5), last_day, sp.get("prem_stop", 0.40),
                  sp.get("ladder_pts"), sp.get("trail_gap_pts", 0.0))


def run_rule(rid: str, books: dict, period) -> list:
    """All trades of rule rid over `period`: one position per book at a time, at most rule.max_per_day a session."""
    R = rules_mod()
    rule = R.RULES[rid]
    out = []
    for name in rule.books:
        b = books[name]
        busy_until = None
        per_day = {}
        for sp in sorted(rule.candidates(b, period), key=lambda s: s["i_sig"]):
            if busy_until is not None and b.m.ts[sp["i_sig"]] < busy_until:
                continue
            dkey = sp["day"]
            if per_day.get(dkey, 0) >= rule.max_per_day:
                continue
            t = simulate(b, sp, period[1])
            if t is None:
                continue
            t["rule"], t["spec"] = rid, sp
            busy_until = np.datetime64(t["exit_ts"])
            per_day[dkey] = per_day.get(dkey, 0) + 1
            out.append(t)
    return out


def twin_spec(book, sp: dict, day):
    """The same trade on another session `day`: the same clock minute, direction, strike rule and exits."""
    m = book.m
    i = m.at_or_after(day, sp["tod"], until=sp["tod"] + 2)
    if i < 0:
        return None
    t = dict(sp)
    t["i_sig"] = i
    t["day"] = day
    t["ustop"] = float(m.F[i] - sp["side"] * sp["stop_pts"]) if np.isfinite(sp.get("stop_pts", np.nan)) else np.nan
    return t


def twins(trades: list, books: dict, period, n: int = TWINS, seed: int = SEED) -> np.ndarray:
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    cache, pools = {}, {}
    for t in trades:
        b = books[t["book"]]
        d0 = np.datetime64(t["day"], "ns")
        key = (t["book"], pd.Timestamp(d0).year, pd.Timestamp(d0).month)
        if key not in pools:
            pools[key] = [d for d in b.m.days if pd.Timestamp(d).year == key[1] and pd.Timestamp(d).month == key[2]
                          and D.in_period(d, period)]
        pool = [d for d in pools[key] if d != d0]
        if not pool:
            continue
        sp = t["spec"]
        for s in range(n):
            v = 0.0
            for _ in range(20):
                d = pool[rng.integers(len(pool))]
                ck = (t["book"], d, sp["tod"], sp["side"], sp.get("itm", 0), sp.get("stop_pts"), sp.get("trail_x"),
                      sp.get("exit_tod"))
                if ck not in cache:
                    ts = twin_spec(b, sp, d)
                    cache[ck] = None if ts is None else simulate(b, ts, period[1])
                x = cache[ck]
                if x is not None:
                    v = x["net"]
                    break
            tot[s] += v
    return tot


def folds(df: pd.DataFrame) -> list:
    out = []
    for k, (a, b) in enumerate(K.FOLDS):
        days = pd.DatetimeIndex(df.day).date if len(df) else []
        g = df[[(a <= x <= b) for x in days]] if len(df) else df
        out.append(dict(fold=k + 1, start=str(a), end=str(b), trades=int(len(g)),
                        net=float(g.net.sum()) if len(g) else 0.0))
    return out


def gates(s: dict, fl: list, p: float, q: float) -> dict:
    n = s["trades"]
    return dict(
        trades=n >= GATE["min_trades"], net=s.get("net", 0) > 0, net2=s.get("net2", 0) > 0,
        twin_p=bool(np.isfinite(p) and p < GATE["p"]), bh_q=bool(np.isfinite(q) and q <= GATE["q"]),
        folds=sum(f["net"] > 0 for f in fl) >= GATE["folds_pos"],
        worst_month=bool(n and s["worst_month"] > GATE["stop_month"]),
        max_dd=bool(n and s["max_dd"] > GATE["stop_dd"]), wo_top3=s.get("wo_top3", 0) > 0,
        real_exits=bool(n and s["model_share"] <= GATE["model_share"] and s["real_exit_net"] > 0))


def r96_ledger() -> list:
    """R96's twin p values, counted in R97's BH family: the snapshot of its cumulative ledger (rounds 1-3, 27 ideas,
    branch claude/r96-mcx-buy-hunt-r3 at c02f77b4, 21 Oct 2026), committed as results/r96_ledger_snapshot.json."""
    p = K.RESULTS / "r96_ledger_snapshot.json"
    return json.loads(p.read_text()) if p.exists() else []


def load_books():
    return {n: D.load_book(n) for n in K.BOOKS}


def design(only=None):
    R = rules_mod()
    books = load_books()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    res, frames = {}, []
    for rid in (only or R.ORDER):
        trades = run_rule(rid, books, K.DESIGN)
        df = S96.frame(trades)
        frames.append(df)
        s = S96.summarize(df)
        fl = folds(df)
        tw = twins(trades, books, K.DESIGN) if trades else np.zeros(TWINS)
        p = float((np.sum(tw >= s.get("net", 0)) + 1) / (TWINS + 1)) if trades else float("nan")
        res[rid] = dict(summary=s, folds=fl, twin_mean=float(tw.mean()), twin_p=p)
        print(rid, s.get("trades"), round(s.get("net", 0)), "p", round(p, 3), flush=True)
    prior = r96_ledger()
    ps = [r.get("p") for r in prior] + [res[r]["twin_p"] for r in res]
    qs = S96.bh(ps)
    for rid, qv in zip(res, qs[len(prior):]):
        r = res[rid]
        r["bh_q"] = qv
        r["gates"] = gates(r["summary"], r["folds"], r["twin_p"], qv)
        r["pass"] = bool(all(r["gates"].values()))
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), design=[str(x) for x in K.DESIGN],
               bh_family=dict(r96_ideas=len(prior), r97_ideas=len(res), total=len(ps)), rules=res)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    if frames:
        pd.concat(frames, ignore_index=True).to_csv(K.RESULTS / "trades_design.csv", index=False)
    return out


def holdout(rid: str):
    lock = K.RESULTS / f"HOLDOUT_READ_{rid}.json"
    if lock.exists():
        raise SystemExit(f"holdout already read for {rid}: {lock}")
    dj = json.loads((K.RESULTS / "design.json").read_text())
    r = dj["rules"].get(rid)
    if r is None or not r.get("pass"):
        raise SystemExit(f"{rid} did not pass every design gate; the holdout stays locked")
    lock.write_text(json.dumps(dict(rule=rid, read_at=datetime.now(timezone.utc).isoformat()), indent=1))
    books = load_books()
    trades = run_rule(rid, books, K.HOLDOUT)
    df = S96.frame(trades)
    s = S96.summarize(df)
    tw = twins(trades, books, K.HOLDOUT) if trades else np.zeros(TWINS)
    p = float((np.sum(tw >= s.get("net", 0)) + 1) / (TWINS + 1)) if trades else float("nan")
    ok = dict(net=s.get("net", 0) > 0, trades=s["trades"] >= HOLD["min_trades"], twin_p=p <= HOLD["p"],
              worst_month=s.get("worst_month", -np.inf) > GATE["stop_month"],
              max_dd=s.get("max_dd", -np.inf) > GATE["stop_dd"])
    out = dict(rule=rid, holdout=[str(x) for x in K.HOLDOUT], summary=s, twin_p=p, twin_mean=float(tw.mean()),
               gates=ok, pass_=bool(all(ok.values())))
    (K.RESULTS / f"holdout_{rid}.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "design":
        design(only=sys.argv[2:] or None)
    elif cmd == "holdout":
        print(json.dumps(holdout(sys.argv[2]), indent=1, default=str))
    else:
        raise SystemExit(__doc__)
