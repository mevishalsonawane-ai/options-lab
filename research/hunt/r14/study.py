"""R14 step 2: re-entry churn and target vs trail on ORB / ORB Fresh (rules: PREREG.md, frozen before this ran).

    flock <scratch>/obuy.lock python3 -I research/hunt/r14/study.py design     # churn + R0-R5 on design, the pick
    flock <scratch>/obuy.lock python3 -I research/hunt/r14/study.py holdout    # ONE read: the picks, then all for info
-> <scratch>/hunt/r14/{design,holdout}_*.csv / .json, trades_*.csv.gz (small); the doc's tables are printed.

Exits are h20's simulator (research/hunt/h20/sim.py, mode "fixed" = the app since 07 Oct) with the "real" fills and the app's
charges; this file only adds the re-entry rules (which candidate is taken, with which exits).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import data as D  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = D.OUT
HOLDOUT = pd.Timestamp("2025-10-01").date()
RULES = ("R0", "R1", "R2", "R3", "R4", "R5")
CAP = {"R0": 99, "R1": 1, "R2": 2, "R3": 99, "R4": 1, "R5": 2}
NAME = {"R0": "baseline (today)", "R1": "cap 1 a side", "R2": "cap 2 a side", "R3": "let it run (trail, no target)",
        "R4": "run + cap 1 a side", "R5": "hybrid: 1st books +40, same-side re-entry after a target trails (cap 2)"}
TESTS = [(a, r) for a in D.ARMS for r in RULES[1:]]
ROUND_TRIP_ORDERS = 2


def sims():
    h = D.h20()
    P = D.load()
    exe = h.EXES["real"]
    tgt = h.Spec("target", stop=40.0, tgt=40.0, ladder=h.app_ladder(40.0))
    run = h.Spec("run", stop=40.0, tgt=None, ladder=h.app_ladder(40.0), trail=(40.0, 0.75))
    out = {}
    for arm in D.ARMS:
        T = h.sim(P[arm], tgt, exe, mode="fixed"); T.index = T.cand.values
        R = h.sim(P[arm], run, exe, mode="fixed"); R.index = R.cand.values
        out[arm] = (T, R)
    return out


def take(T, R, rule):
    """The app's position rule with [rule]'s cap and exits. Returns the taken trades (rows of T or R) with flags."""
    order = T.sort_values(["day", "sig_min", "cand"], kind="stable")
    day, sm, side, cand = order.day.values, order.sig_min.values, order.side.values, order.cand.values
    rows, flags = [], []
    cur, block = None, -1
    n, prev_tgt, prev_side, prev_exit_bar = {}, {}, None, None
    for i in range(len(order)):
        if day[i] != cur:
            cur, block, n, prev_tgt, prev_side, prev_exit_bar = day[i], -1, {1: 0, -1: 0}, {1: False, -1: False}, None, None
        bar_start = sm[i] - 4
        s = int(side[i])
        if bar_start <= block or n[s] >= CAP[rule]:
            continue
        use_run = rule in ("R3", "R4") or (rule == "R5" and prev_tgt[s])
        r = (R if use_run else T).loc[cand[i]]
        same_after_target = prev_side == s and prev_tgt[s] and prev_exit_bar is not None and bar_start == prev_exit_bar + 5
        rows.append(r)
        flags.append(dict(nth_side=n[s] + 1, run=use_run, reentry_after_target=bool(same_after_target),
                          same_side_reentry=prev_side == s))
        n[s] += 1
        prev_tgt[s] = r.why == "target"
        prev_side = s
        ex = int(r.exit_min)
        block = ex - ((ex - C.OPEN_M) % 5)
        prev_exit_bar = block
    t = pd.DataFrame(rows).reset_index(drop=True)
    return pd.concat([t, pd.DataFrame(flags)], axis=1) if len(t) else t


def part_of(df, part):
    if part == "all":
        return df
    m = df.day < HOLDOUT if part == "design" else df.day >= HOLDOUT
    return df[m]


def daily(tr, days):
    s = tr.groupby("day").net.sum() if len(tr) else pd.Series(dtype=float)
    return s.reindex(days, fill_value=0.0).astype(float)


def summary(tr, days):
    d = daily(tr, days)
    cum = d.cumsum()
    dd = float((cum - np.maximum.accumulate(np.maximum(cum, 0))).min()) if len(cum) else 0.0
    gp = float(tr.gross[tr.gross > 0].sum()) if len(tr) else 0.0
    yr = {str(y): round(float(v)) for y, v in tr.groupby(tr.day.map(lambda x: x.year)).net.sum().items()} if len(tr) else {}
    return dict(trades=len(tr), orders=ROUND_TRIP_ORDERS * len(tr), win=round(100 * float((tr.net > 0).mean()), 1) if len(tr) else 0.0,
                gross=round(float(tr.gross.sum())), charges=round(float(tr.charges.sum())), net=round(float(tr.net.sum())),
                chg_pct_of_gross_profit=round(100 * float(tr.charges.sum()) / gp, 1) if gp > 0 else None,
                maxdd=round(dd), worst_day=round(float(d.min())) if len(d) else 0, years=yr,
                why={k: int(v) for k, v in tr.why.value_counts().items()} if len(tr) else {})


def paired(a, b, B=10_000, seed=14):
    """One-sided tests that a (rule) beats b (baseline) per day: t p-value and a day-bootstrap p."""
    from math import erf, sqrt
    d = (a - b).values
    n = len(d)
    if n < 3 or d.std(ddof=1) == 0:
        return dict(delta=round(float(d.sum())), t=None, p_t=1.0, p_boot=1.0)
    t = d.mean() / (d.std(ddof=1) / np.sqrt(n))
    try:
        from scipy import stats
        p_t = float(stats.t.sf(t, n - 1))
    except Exception:  # normal approximation
        p_t = 0.5 * (1 - erf(t / sqrt(2)))
    rng = np.random.default_rng(seed)
    bs = np.array([d[rng.integers(0, n, n)].mean() for _ in range(B)])
    return dict(delta=round(float(d.sum())), t=round(float(t), 2), p_t=round(p_t, 4), p_boot=round(float((bs <= 0).mean()), 4))


def bh(ps):
    ps = np.asarray(ps, float)
    m = len(ps)
    o = np.argsort(ps)
    q = np.empty(m)
    prev = 1.0
    for rank in range(m, 0, -1):
        i = o[rank - 1]
        prev = min(prev, ps[i] * m / rank)
        q[i] = prev
    return q


def churn(tr, days):
    """R0's churn: entries a day and a side, same-side re-entries right after a target, their gross / charges / net."""
    if not len(tr):
        return {}
    per_day = tr.groupby("day").size().reindex(days, fill_value=0)
    per_side = tr.groupby(["day", "side"]).size()
    re = tr[tr.reentry_after_target]
    first = tr[tr.nth_side == 1]
    later = tr[tr.nth_side > 1]
    tg = tr[tr.why == "target"]

    def g(x):
        return dict(n=len(x), gross_avg=round(float(x.gross.mean()), 1) if len(x) else None,
                    charges_avg=round(float(x.charges.mean()), 1) if len(x) else None,
                    net_avg=round(float(x.net.mean()), 1) if len(x) else None, net=round(float(x.net.sum())),
                    win=round(100 * float((x.net > 0).mean()), 1) if len(x) else None)
    return dict(
        days=len(days), trade_days=int((per_day > 0).sum()), entries_per_trade_day=round(float(per_day[per_day > 0].mean()), 2),
        max_entries_day=int(per_day.max()), max_same_side_day=int(per_side.max()),
        days_3plus_same_side=int((per_side.groupby(level=0).max() >= 3).sum()),
        all=g(tr), first_on_side=g(first), later_on_side=g(later), reentry_right_after_target=g(re), target_exits=g(tg),
        charges_share_of_gross_profit=round(100 * float(tr.charges.sum()) / float(tr.gross[tr.gross > 0].sum()), 1),
        target_trade_charges_share=round(100 * float(tg.charges.sum()) / float(tg.gross.sum()), 1) if len(tg) else None,
    )


def run(part):
    S = sims()
    res, tests, churns, keep = {}, [], {}, []
    for arm in D.ARMS:
        T, R = S[arm]
        days_all = sorted(set(T.day))
        days = [d for d in days_all if (d < HOLDOUT) == (part == "design")]
        trs = {r: part_of(take(T, R, r), part) for r in RULES}
        base = daily(trs["R0"], days)
        churns[arm] = churn(trs["R0"], days)
        for r in RULES:
            s = summary(trs[r], days)
            if r != "R0":
                s.update(paired(daily(trs[r], days), base))
                yd = (trs[r].groupby(trs[r].day.map(lambda x: x.year)).net.sum()
                      .sub(trs["R0"].groupby(trs["R0"].day.map(lambda x: x.year)).net.sum(), fill_value=0))
                s["delta_years"] = {str(k): round(float(v)) for k, v in yd.items()}
                s["years_positive"] = int((yd > 0).sum())
                s["n_years"] = int(len(yd))
                tests.append((arm, r, s["p_t"]))
            res[(arm, r)] = s
            t = trs[r].copy()
            t["arm"], t["rule"] = arm, r
            keep.append(t)
    q = bh([p for _, _, p in tests])
    for (arm, r, _), qq in zip(tests, q):
        res[(arm, r)]["bh_q"] = round(float(qq), 4)
    return res, churns, pd.concat(keep, ignore_index=True)


def pick(res):
    picks = {}
    for arm in D.ARMS:
        ok = [(r, res[(arm, r)]) for r in RULES[1:]
              if res[(arm, r)]["delta"] > 0 and res[(arm, r)]["bh_q"] < 0.10 and res[(arm, r)]["years_positive"] >= 4]
        if not ok:
            picks[arm] = None
            continue
        best = max(d["delta"] for _, d in ok)
        near = [r for r, d in ok if d["delta"] >= 0.95 * best]
        lower_risk = [r for r in near if r in ("R1", "R2")]
        picks[arm] = (lower_risk or near)[0] if lower_risk else max(ok, key=lambda x: x[1]["delta"])[0]
    return picks


def table(res, churns, part):
    rows = []
    for (arm, r), s in res.items():
        rows.append(dict(arm=arm, rule=r, name=NAME[r], **{k: v for k, v in s.items() if k not in ("years", "why", "delta_years")},
                         years=json.dumps(s.get("years")), delta_years=json.dumps(s.get("delta_years")), why=json.dumps(s.get("why"))))
    df = pd.DataFrame(rows)
    df.to_csv(os.path.join(OUT, f"{part}_rules.csv"), index=False)
    with open(os.path.join(OUT, f"{part}_churn.json"), "w") as f:
        json.dump(churns, f, indent=1, default=str)
    pd.set_option("display.width", 260)
    pd.set_option("display.max_columns", 40)
    cols = ["arm", "rule", "trades", "orders", "win", "gross", "charges", "net", "chg_pct_of_gross_profit", "maxdd", "worst_day",
            "delta", "p_t", "p_boot", "bh_q", "years_positive", "n_years"]
    print(df[[c for c in cols if c in df.columns]].to_string(index=False))
    print(df[["arm", "rule", "years", "delta_years", "why"]].to_string(index=False))
    print(json.dumps(churns, indent=1, default=str))
    return df


if __name__ == "__main__":
    part = sys.argv[1]
    if part == "design":
        res, ch, tr = run("design")
        table(res, ch, "design")
        p = pick(res)
        print("PICKS", p)
        with open(os.path.join(OUT, "picks.json"), "w") as f:
            json.dump(dict(picks=p, at=datetime.now(timezone.utc).isoformat()), f)
        tr.drop(columns=[c for c in ("parent", "gate", "strike", "lot") if c in tr.columns]).to_csv(
            os.path.join(OUT, "trades_design.csv.gz"), index=False)
    elif part == "holdout":
        with open(os.path.join(OUT, "picks.json")) as f:
            picks = json.load(f)["picks"]
        with open(os.path.join(OUT, "HOLD_READ"), "w") as f:
            f.write(f"holdout read {datetime.now(timezone.utc).isoformat()} picks={picks}\n")
        res, ch, tr = run("holdout")
        print("PICKS (frozen on design):", picks)
        for arm, r in picks.items():
            if r:
                s = res[(arm, r)]
                print(arm, r, "holdout delta", s["delta"], "p_t", s["p_t"], "p_boot", s["p_boot"],
                      "CONFIRMED" if s["delta"] > 0 and s["p_t"] < 0.10 else "NOT confirmed")
        print("--- every candidate, for information (the picks above were frozen first) ---")
        table(res, ch, "holdout")
        tr.drop(columns=[c for c in ("parent", "gate", "strike", "lot") if c in tr.columns]).to_csv(
            os.path.join(OUT, "trades_holdout.csv.gz"), index=False)
