#!/usr/bin/env python3
"""R58 study steps (PREREG.md). Research only, 15 Oct 2026.

    python3 research/hunt/r58/r58study.py design    # 38 rules on DESIGN (20 Nov 2024 - 31 Mar 2026) at 1x / 2x + contexts
    python3 research/hunt/r58/r58study.py final     # the ONE read of the FINAL TEST (1 Apr - 12 Oct 2026): twins, BH, gates
    python3 research/hunt/r58/r58study.py report    # results/report.md from design.json + final.json
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np

import r58cfg as K
import r58data as D
import r58engine as E
import r58rules as RR

BOOKS: dict = {}


def books() -> dict:
    if not BOOKS:
        for s in K.UNIVERSE:
            BOOKS[s] = D.load(s)
    return BOOKS


def end_index(bk, part: str) -> int:
    last = K.DESIGN_END if part == "design" else K.LAST_DAY
    return max(i for i, d in enumerate(bk.days) if d <= last)


def mode_for(bk, part: str, **kw) -> E.Mode:
    return E.Mode(end=end_index(bk, part), **kw)


# ------------------------------------------------------------------------------------------------ one rule, one period
def run_rule(rule: RR.Rule, part: str, cutoff=True, band=True, cost=1.0, want_cash=True) -> list[dict]:
    if part == "final" and not K.FINAL_FLAG.exists():
        raise PermissionError("the final test is locked: only `r58study.py final` may read it (PREREG 1)")
    out = []
    for sym in RR.stocks_for(rule):
        bk = books()[sym]
        mode = mode_for(bk, part, cutoff=cutoff, band=band, cost=cost)
        busy = {1: -1, -1: -1}
        fills = defaultdict(int)
        for sp in RR.specs(bk, rule, RR.entry_days(bk, part)):
            g = sp.di * K.NMIN + sp.s
            key = sp.side if rule.per_side else 1
            if g < busy[key]:
                continue
            if fills[(sp.di, sp.side)] >= rule.max_side_day:
                continue
            t = E.simulate(bk, sp, mode, want_cash=want_cash)
            if t is None:
                continue
            t["rule"] = rule.id
            t["spec"] = sp
            out.append(t)
            fills[(sp.di, sp.side)] += 1
            busy[key] = sp.di * K.NMIN + t["exit_f"]
    out.sort(key=lambda t: (t["day"], t["sym"], t["entry_row"]))
    return out


# ------------------------------------------------------------------------------------------------ scoring
def score(tr: list, part: str, key: str = "net") -> dict:
    vals = [t for t in tr if np.isfinite(t.get(key, np.nan))]
    nets = np.array([t[key] for t in vals]) if vals else np.zeros(0)
    days = sorted({d for bk in books().values() for d in bk.days if K.period(d) == part})
    by = defaultdict(float)
    for t in vals:
        by[t["exit_day"]] += t[key]
    series = np.array([by.get(d, 0.0) for d in days])
    eq = np.cumsum(series) if len(series) else np.zeros(1)
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(series) else 0.0
    months = defaultdict(float)
    for t in vals:
        months[f"{t['exit_day'].year}-{t['exit_day'].month:02d}"] += t[key]
    quarters = defaultdict(lambda: [0.0, 0])
    for t in vals:
        q = K.quarter(t["day"])
        quarters[q][0] += t[key]
        quarters[q][1] += 1
    stocks = defaultdict(lambda: [0, 0.0])
    for t in vals:
        stocks[t["sym"]][0] += 1
        stocks[t["sym"]][1] += t[key]
    wins, losses = nets[nets > 0], nets[nets <= 0]
    srt = np.sort(nets)[::-1]
    net = float(nets.sum())
    best_stock = max(stocks, key=lambda s: stocks[s][1]) if stocks else None
    caps = [t["capital"] if key == "net" else t.get("cash_capital", np.nan) for t in vals]
    caps = np.array([c for c in caps if np.isfinite(c)])
    return dict(
        trades=len(vals), win_rate=float((nets > 0).mean()) if len(nets) else 0.0, net=net,
        per_trade=net / len(nets) if len(nets) else 0.0,
        avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
        worst_day=float(min(by.values())) if by else 0.0, max_dd=dd,
        months_green=f"{sum(v > 0 for v in months.values())}/{len(months)}",
        capital_median=float(np.median(caps)) if len(caps) else 0.0,
        top5=float(srt[:5].sum() / net) if net > 0 else float("inf"),
        without_top5=float(srt[5:].sum()),
        best_stock=best_stock, without_best_stock=net - (stocks[best_stock][1] if best_stock else 0.0),
        stocks={s: [v[0], round(v[1])] for s, v in sorted(stocks.items())},
        quarters={q: [round(v[0]), v[1]] for q, v in sorted(quarters.items())},
        months={m: round(v) for m, v in sorted(months.items())},
        src={k: sum(t.get("src") == k for t in vals) for k in ("print", "stale", "model")},
        why={w: sum(t.get("why") == w for t in vals) for w in E.WHY},
        sides={"CE": sum(t["side"] > 0 for t in vals), "PE": sum(t["side"] < 0 for t in vals)},
        held_sessions=float(np.mean([t["held_sessions"] for t in vals])) if vals else 0.0,
    )


def intrinsic_net(tr: list) -> float:
    """Stale and model exits re-priced at intrinsic value (PREREG 4 robustness)."""
    tot = 0.0
    for t in tr:
        if t["src"] == "print" or not np.isfinite(t["intrinsic_exit"]):
            tot += t["net"]
        else:
            tot += t["net"] + (t["intrinsic_exit"] - t["exit"]) * t["lot"]
    return tot


def design_gate(sc: dict) -> bool:
    pos = sum(sc["quarters"].get(q, [0, 0])[0] > 0 for q in K.QUARTERS)
    return sc["net"] > 0 and pos >= 3


def bundle(rule: RR.Rule, part: str) -> tuple[dict, dict]:
    """Every number of one rule in one period: 1x, 2x, cash 1x / 2x, and the two context runs."""
    t1 = run_rule(rule, part)
    t2 = run_rule(rule, part, cost=2.0)
    res = {"x1": score(t1, part), "x2": score(t2, part), "cash_x1": score(t1, part, "cash_net"),
           "cash_x2": score(t2, part, "cash_net"), "intrinsic_net_x1": intrinsic_net(t1)}
    tn = run_rule(rule, part, cutoff=False, want_cash=False)
    res["ctx_no_cutoff"] = score(tn, part)
    if rule.hold > 1:
        tb = run_rule(rule, part, band=False, want_cash=False)
        res["ctx_no_band"] = score(tb, part)
    res["design_gate"] = design_gate(res["x1"]) if part == "design" else None
    return res, {"x1": t1, "x2": t2}


def strip(t: dict) -> dict:
    return {k: (str(v) if k in ("day", "exit_day", "expiry") else v) for k, v in t.items() if k != "spec"}


def family_walk_forward(res: dict, rules: list) -> dict:
    """Context (PREREG 7): each design quarter, the family's best variant over the quarters before it."""
    out = {}
    fams = defaultdict(list)
    for r in rules:
        fams[r.family].append(r.id)
    for f, ids in fams.items():
        tot, picks = 0.0, {}
        for i, q in enumerate(K.QUARTERS[1:], start=1):
            prior = K.QUARTERS[:i]
            best = max(ids, key=lambda r: sum(res[r]["x1"]["quarters"].get(p, [0, 0])[0] for p in prior))
            got = res[best]["x1"]["quarters"].get(q, [0, 0])[0]
            picks[q] = [best, got]
            tot += got
        out[f] = dict(picks=picks, net=round(tot))
    return out


# ------------------------------------------------------------------------------------------------ design
def design():
    rs = RR.rules()
    res, dump = {}, {}
    for r in rs:
        res[r.id], tr = bundle(r, "design")
        dump[r.id] = [strip(t) for t in tr["x1"]]
        a, b = res[r.id]["x1"], res[r.id]["cash_x1"]
        print(r.id, a["trades"], round(a["net"]), "2x", round(res[r.id]["x2"]["net"]), "cash", round(b["net"]),
              "q+", sum(a["quarters"].get(q, [0, 0])[0] > 0 for q in K.QUARTERS), "gate", res[r.id]["design_gate"],
              a["src"], flush=True)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), results=res,
               family_walk_forward=family_walk_forward(res, rs),
               rules=[dict(id=r.id, family=r.family, kind=r.kind, p=r.p, hold=r.hold, itm=r.itm) for r in rs])
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / "design_trades.pkl").write_bytes(pickle.dumps(dump))


# ------------------------------------------------------------------------------------------------ twins
_G: dict = {}


def eligible(bk, part: str, hold: int) -> list[int]:
    mode = mode_for(bk, part)
    return [di for di in RR.entry_days(bk, part) if E.last_hold(bk, di, hold, mode) >= di]


def twin_job(i: int):
    rule = _G["rules"][i]
    trades = _G["trades"][rule.id]
    part, n = _G.get("part", "final"), _G.get("n", K.TWINS)
    rng = np.random.default_rng(K.SEED + i)
    rows = rule.twin_rows()
    el = {s: eligible(books()[s], part, rule.hold) for s in RR.stocks_for(rule)}
    modes = {s: mode_for(books()[s], part) for s in el}
    out = np.zeros(n)
    for j in range(n):
        tot = 0.0
        for t in trades:
            bk = books()[t["sym"]]
            sp = t["spec"]
            for _ in range(5):
                di = el[t["sym"]][int(rng.integers(len(el[t["sym"]])))]
                s = rows[int(rng.integers(len(rows)))]
                tw = E.simulate(bk, E.twin_spec(bk, t, sp, di, s), modes[t["sym"]], want_cash=False)
                if tw is not None:
                    tot += tw["net"]
                    break
        out[j] = tot
    return out


def bh(ps: list) -> list:
    p = np.asarray(ps, float)
    m = len(p)
    o = np.argsort(p)
    q = np.empty(m)
    prev = 1.0
    for i in range(m - 1, -1, -1):
        prev = min(prev, p[o[i]] * m / (i + 1))
        q[o[i]] = prev
    return q.tolist()


# ------------------------------------------------------------------------------------------------ the ONE final read
def final():
    import multiprocessing as mp
    if K.FINAL_FLAG.exists():
        raise SystemExit("R58's final test has been read already (results/FINAL_READ). A new test needs a new PREREG.")
    des = json.loads((K.RESULTS / "design.json").read_text())
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    rs = RR.rules()
    res, trades, dump = {}, {}, {}
    for r in rs:
        res[r.id], tr = bundle(r, "final")
        trades[r.id] = tr["x1"]
        dump[r.id] = [strip(t) for t in tr["x1"]]
        print(r.id, res[r.id]["x1"]["trades"], round(res[r.id]["x1"]["net"]), flush=True)
    books()
    _G.update(rules=rs, trades=trades)
    with mp.get_context("fork").Pool(4) as pool:
        tw = pool.map(twin_job, range(len(rs)))
    ps = []
    for i, r in enumerate(rs):
        x = res[r.id]["x1"]
        x["twin_mean"] = float(tw[i].mean())
        x["twin_p"] = float((1 + (tw[i] >= x["net"]).sum()) / (K.TWINS + 1))
        ps.append(x["twin_p"])
    q = bh(ps)
    for r, qq in zip(rs, q):
        x = res[r.id]["x1"]
        x["q"] = qq
        dg = des["results"][r.id]["design_gate"]
        few = x["trades"] < K.MIN_TRADES
        x["gates"] = dict(net=x["net"] > 0, p=x["twin_p"] < 0.05, q=qq <= K.BH_Q, trades=not few,
                          top5=x["top5"] < 1.0, design=bool(dg))
        v = "PASS" if all(x["gates"].values()) else ("too few trades to judge" if few else "FAIL")
        if v == "PASS" and x["without_best_stock"] <= 0:
            v = "PASS (carried by one stock)"
        x["verdict"] = v
        print(r.id, x["trades"], round(x["net"]), "p", round(x["twin_p"], 3), "q", round(qq, 3), v, flush=True)
    out = dict(read=K.FINAL_FLAG.read_text().strip(), m=len(ps), results=res)
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    (K.OUT / "final_trades.pkl").write_bytes(pickle.dumps(dump))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "report":
        import r58report
        r58report.main()
    else:
        {"design": design, "final": final}[cmd]()
