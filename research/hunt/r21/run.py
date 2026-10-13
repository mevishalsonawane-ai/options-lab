"""R21 test run (PREREG.md). Reads the locked holdout ONCE: a flag file in <OUT> records the read.

    python3 research/hunt/r21/run.py all          # design + holdout + twins + BH + walk-forward -> results/
    R21_SOURCE=lake python3 research/hunt/r21/run.py all     # the same on the 6-year lake, once published
"""
from __future__ import annotations

import json
import math
import sys
from dataclasses import asdict

import numpy as np

import config as K
import data as D
import rules as R
from rules import Params, close_row

BIG = 5.0
VARIANTS = [
    Params(name="V0"),
    Params(name="V1", trigger="onesided", k=2.5),
    Params(name="V2", trigger="straddle_recent", st_exp=0.10, recent=15),
    Params(name="V3", trigger="onesided", k=2.5, last=close_row(15, 0)),
    Params(name="V4", trigger="onesided", k=2.5, last=close_row(15, 0), band=(1.0, 15.0)),
    Params(name="V5", trigger="onesided", k=2.5, last=close_row(15, 0), band=(1.0, 15.0), exits="lock"),
    Params(name="V6", trigger="onesided", k=2.5, last=close_row(15, 0), band=(1.0, 15.0), exits="trail"),
    Params(name="V7", trigger="onesided", k=2.5, last=close_row(15, 0), band=(1.0, 15.0), exits="plain"),
    Params(name="V8", trigger="blind", first=close_row(13, 30), last=close_row(13, 30)),
]


def split(ds: dict) -> tuple[list, list]:
    des, hold = [], []
    for u, days in ds.items():
        cut = D.holdout_cut(u, days)
        des += [d for d in days if d.day < cut]
        hold += [d for d in days if d.day >= cut]
    return des, hold


def big_days(days) -> set:
    """Hindsight: some Rs 1-10 option (traded close, bars starting 13:00-14:30) reached 5x before 15:05."""
    import anatomy as A
    return {(d.und, d.day) for d in days if A.best_cheap(d, R.EXIT_ROW).get("mult", 0) >= BIG}


def evaluate(days, p: Params, big: set) -> dict:
    trades = [t for t in (R.run_day(d, p) for d in days) if t is not None]
    return stats(trades, days, big)


def stats(trades, days, big: set) -> dict:
    nets = [t.net for t in sorted(trades, key=lambda x: (x.day, x.und))]
    eq = np.cumsum(nets) if nets else np.array([0.0])
    dd = float(np.min(eq - np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:])) if nets else 0.0
    caught = sum(1 for t in trades if (t.und, t.day) in big and t.peak_mult >= BIG)
    return dict(days=len(days), trades=len(trades), wins=sum(n > 0 for n in nets), net=round(sum(nets), 2),
                per_expiry=round(sum(nets) / max(len(days), 1), 2), worst=round(min(nets), 2) if nets else 0.0,
                best=round(max(nets), 2) if nets else 0.0, max_dd=round(dd, 2), big_days=len(big), big_caught=caught,
                big_missed=len(big) - caught,
                trades_list=[dict(und=t.und, day=str(t.day), side=t.side, strike=t.strike, sig=R_hm(t.sig_row),
                                  fill=R_hm(t.fill_row), entry=t.entry, lots=t.lots, qty=t.qty, net=t.net,
                                  peak_x=round(t.peak_mult, 2), exits=[(R_hm(r), q, px, w) for r, q, px, w in t.exits])
                             for t in trades])


def R_hm(r: int) -> str:
    m = 9 * 60 + 15 + r
    return f"{m // 60:02d}:{m % 60:02d}"


def twins(days, p: Params, trades: list, n: int, rng) -> np.ndarray:
    """Random-time twin sets: each of the variant's trades re-entered at a random minute of its window, same day,
    same side, same strike rule and exits. Returns n twin-set totals."""
    by = {(d.und, d.day): d for d in days}
    out = np.zeros(n)
    win = max(p.last, p.first + 15) if p.trigger == "blind" else p.last   # the blind buy's twin: 13:30-13:45
    tp = Params(**{**asdict(p), "last": win})
    for i in range(n):
        tot = 0.0
        for t in trades:
            d = by[(t["und"], D_date(t["day"]))]
            s = R.twin_signal(d, tp, t["side"], rng)
            if s is None:
                continue
            tr = R.simulate(d, s, tp)
            tot += tr.net if tr else 0.0
        out[i] = tot
    return out


def D_date(s):
    from datetime import date
    return date.fromisoformat(s)


def bh(ps: list[float]) -> list[float]:
    """Benjamini-Hochberg adjusted q-values."""
    m = len(ps)
    order = np.argsort(ps)
    q = np.empty(m)
    prev = 1.0
    for rank in range(m, 0, -1):
        i = order[rank - 1]
        prev = min(prev, ps[i] * m / rank)
        q[i] = prev
    return list(q)


def walk_forward(all_days, big: set) -> dict:
    """V4 with k re-chosen each month on all earlier expiry days (>= 10), the PREREG rule (best net, ties -> larger k)."""
    days = sorted(all_days, key=lambda d: d.day)
    months = sorted({(d.day.year, d.day.month) for d in days})
    out, trades = [], []
    for ym in months:
        past = [d for d in days if (d.day.year, d.day.month) < ym]
        cur = [d for d in days if (d.day.year, d.day.month) == ym]
        if len(past) < 10:
            continue
        best_k, best_net = None, -math.inf
        for k in (1.5, 2.0, 2.5, 3.0, 4.0):
            p = Params(name="wf", trigger="onesided", k=k, last=close_row(15, 0), band=(1.0, 15.0))
            net = sum(t.net for t in (R.run_day(d, p) for d in past) if t)
            if net > best_net + 1e-9 or abs(net - best_net) <= 1e-9:
                best_k, best_net = k, net
        p = Params(name="wf", trigger="onesided", k=best_k, last=close_row(15, 0), band=(1.0, 15.0))
        tr = [t for t in (R.run_day(d, p) for d in cur) if t]
        trades += tr
        out.append(dict(month=f"{ym[0]}-{ym[1]:02d}", k=best_k, days=len(cur), trades=len(tr),
                        net=round(sum(t.net for t in tr), 2)))
    return dict(months=out, total=round(sum(t.net for t in trades), 2), trades=len(trades),
                wins=sum(t.net > 0 for t in trades))


def main(force: bool = False):
    ds = D.cached()
    des, hold = split(ds)
    flag = K.OUT / f"holdout_read_{K.SOURCE}.flag"
    if flag.exists() and not force:
        sys.exit(f"The R21 holdout was already read ({flag}). One read only: a new question needs a new pre-registration.")
    big_d, big_h = big_days(des), big_days(hold)
    res = {}
    for p in VARIANTS:
        res[p.name] = dict(params=asdict(p), design=evaluate(des, p, big_d))
    # ---- the single holdout read ----
    K.OUT.mkdir(parents=True, exist_ok=True)
    flag.write_text("read\n")
    rng = np.random.default_rng(K.SEED)
    for p in VARIANTS:
        h = evaluate(hold, p, big_h)
        sp = Params(**{**asdict(p), "spread_mult": 2.0})
        h2 = evaluate(hold, sp, big_h)
        tw = twins(hold, p, h["trades_list"], K.TWINS, rng) if h["trades"] else np.zeros(K.TWINS)
        pval = float((np.sum(tw >= h["net"] - 1e-9) + 1) / (len(tw) + 1)) if h["trades"] else 1.0
        res[p.name].update(holdout=h, holdout_2x_spread=dict(net=h2["net"], trades=h2["trades"]),
                           twin=dict(mean=round(float(tw.mean()), 2), p=round(pval, 4)))
    qs = bh([res[p.name]["twin"]["p"] for p in VARIANTS])
    v0 = res["V0"]["holdout"]["net"]
    for p, q in zip(VARIANTS, qs):
        r = res[p.name]
        g = dict(g1_design_net=bool(r["design"]["net"] > 0),
                 g2_holdout=bool(r["holdout"]["trades"] >= 5 and r["holdout"]["net"] > 0),
                 g3_twin_bh=bool(q < 0.05),
                 g4_stress=bool(r["holdout_2x_spread"]["net"] > 0),
                 g5_beats_v0=bool(r["holdout"]["net"] > v0) if p.name != "V0" else False)
        r["twin"]["bh_q"] = round(float(q), 4)
        r["gates"] = g
        r["passes"] = all(g.values())
    wf = walk_forward(des + hold, big_d | big_h)
    out = dict(source=K.SOURCE, design_days=len(des), holdout_days=len(hold), big_design=len(big_d),
               big_holdout=len(big_h), variants=res, walk_forward=wf)
    (K.RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({k: dict(design=(v["design"]["trades"], v["design"]["net"]),
                              hold=(v["holdout"]["trades"], v["holdout"]["wins"], v["holdout"]["net"]),
                              p=v["twin"]["p"], q=v["twin"]["bh_q"], passes=v["passes"]) for k, v in res.items()}, indent=1))
    print(json.dumps(wf, indent=1))


if __name__ == "__main__":
    main(force="--force" in sys.argv)
