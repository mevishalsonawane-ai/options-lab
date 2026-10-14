"""R25 Step 3 driver (PREREG.md): design, walk-forward, the ONE holdout read, twins, BH and the gates.

    python3 research/hunt/r25/study.py design        # design-period results of every rule -> results/design.json
    python3 research/hunt/r25/study.py walkforward   # anchored yearly walk-forward inside design -> results/walkforward.json
    python3 research/hunt/r25/study.py holdout       # the single holdout read -> results/holdout.json (refuses a 2nd read)

The holdout flag lives in <OUT>/holdout_read.flag AND results/holdout.json; either one blocks a second read.
"""
from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timezone

import numpy as np

import r25cfg as K
import r25data as D
import r25rules as RU
import triggers as TG

FLAG = K.OUT / "holdout_read.flag"
HOLDOUT_JSON = K.RESULTS / "holdout.json"


def days(part: str) -> list:
    out = []
    for u in K.UNDS:
        out += D.load(u, part)
    out.sort(key=lambda d: (d.day, d.und))
    return out


def rules_final() -> list:
    """The 9 pre-registered rules; T1-T3 with thresholds chosen on ALL design days (Part B's selection rule)."""
    des = days("design")
    return RU.FIXED + [TG.choose(spec, des) for spec in TG.SPECS]


def evaluate(rule, ds, with_grid=True) -> dict:
    tr = RU.run(ds, rule)
    out = {"rule": rule.id, "note": rule.note, "params": [list(p) for p in rule.params], "exits": rule.exits,
           "budget": rule.budget, "m": RU.metrics(tr, ds, rule)}
    one = RU.run(ds, rule, per_lot=True)
    m1 = RU.metrics(one, ds, rule)
    out["per_lot"] = {k: m1[k] for k in ("trades", "net", "per_expiry", "hit_pct", "worst_day", "max_dd")}
    out["x2_spread"] = RU.metrics(RU.run(ds, rule, mult=2.0), ds, rule)["net"]
    out["oor_zero"] = RU.metrics(RU.run(ds, rule, oor_zero=True), ds, rule)["net"]
    if with_grid:
        out["exit_grid"] = {ex: RU.metrics(RU.run(ds, rule, exits=ex), ds, rule)["net"] for ex in RU.EXITS}
    out["per_year"] = {str(y): {k: v[k] for k in ("expiries", "trades", "net", "hit_pct")}
                       for y, v in RU.per_year(tr, ds, rule).items()}
    # sig = the signal bar's CLOSE time; fills and exits happen at a bar's OPEN, so they carry its START time
    out["trades_list"] = [dict(und=t.und, day=str(t.day), side=t.side, strike=t.strike, sig=_hm(t.sig_row),
                               fill=_hm(t.fill_row - 1), entry=t.entry, lots=t.lots, net=t.net, peak_x=round(t.peak_mult, 2),
                               exits=[(_hm(r - 1), q, p, w) for r, q, p, w in t.exits]) for t in tr]
    return out


def _hm(row: int) -> str:
    m = 9 * 60 + 15 + int(row) + 1                 # the bar's CLOSE time
    return f"{m // 60:02d}:{m % 60:02d}"


def significance(rule, ds, res) -> dict:
    """Gate 4's p on these days: lottery -> bootstrap of per-expiry net; others -> random-time twin."""
    net = res["m"]["net"]
    out = {}
    if rule.kind == "lottery":
        tr = RU.run(ds, rule)
        daily = {}
        for d in ds:
            if d.und in rule.unds:
                daily[(d.und, d.day)] = 0.0
        for t in tr:
            daily[(t.und, t.day)] += t.net
        out["p"] = RU.boot_p(np.array(list(daily.values())))
        out["test"] = "bootstrap mean per-expiry net > 0"
    else:
        tw = RU.twins(ds, rule, "time")
        out["p"] = float((tw >= net - 1e-9).mean())
        out["twin_time_mean"] = round(float(tw.mean()), 1)
        out["test"] = "random-time twin"
    tws = RU.twins(ds, rule, "strike", n=300)
    out["twin_strike_p"] = float((tws >= net - 1e-9).mean())
    out["twin_strike_mean"] = round(float(tws.mean()), 1)
    return out


def cmd_design():
    ds = days("design")
    res = {}
    for rule in rules_final():
        t0 = time.time()
        res[rule.id] = evaluate(rule, ds)
        print(rule.id, res[rule.id]["m"]["trades"], res[rule.id]["m"]["net"], f"{time.time() - t0:.0f}s", flush=True)
    (K.RESULTS / "design.json").write_text(json.dumps(res, indent=1, default=str))


def cmd_walkforward():
    ds = days("design")
    out = {}
    for spec in TG.SPECS:
        folds = []
        for y in (2024, 2025, 2026):
            train = [d for d in ds if d.day.year < y]
            test = [d for d in ds if d.day.year == y]
            rule = TG.choose(spec, train)
            tr = RU.run(test, rule)
            m = RU.metrics(tr, test, rule)
            folds.append({"test_year": y, "params": [list(p) for p in rule.params], "exits": rule.exits,
                          "trades": m["trades"], "net": m["net"], "hit_pct": m["hit_pct"]})
            print(spec["id"], y, rule.params, rule.exits, m["trades"], m["net"], flush=True)
        out[spec["id"]] = {"folds": folds, "sum": round(sum(f["net"] for f in folds), 2),
                           "years_positive": sum(f["net"] > 0 for f in folds)}
    for rule in RU.FIXED:
        tr = RU.run(ds, rule)
        py = RU.per_year(tr, ds, rule)
        folds = [{"test_year": y, "trades": py[y]["trades"], "net": py[y]["net"]} for y in (2024, 2025, 2026)]
        out[rule.id] = {"folds": folds, "sum": round(sum(f["net"] for f in folds), 2),
                        "years_positive": sum(f["net"] > 0 for f in folds), "note": "fixed rule: per-year result"}
    (K.RESULTS / "walkforward.json").write_text(json.dumps(out, indent=1, default=str))


def r21_ps() -> dict:
    d = json.loads((K.ROOT / "research/hunt/r21/results/results.json").read_text())
    return {f"R21-{k}": float(v["twin"]["p"]) for k, v in d["variants"].items()}


def cmd_holdout():
    if FLAG.exists() or HOLDOUT_JSON.exists():
        raise SystemExit("R25's holdout has already been read once (PREREG.md). A new look needs a new pre-registration.")
    des = json.loads((K.RESULTS / "design.json").read_text())
    wf = json.loads((K.RESULTS / "walkforward.json").read_text())
    rules = rules_final()
    for r in rules:                                    # the rules read must be exactly the ones the design run reported
        assert des[r.id]["params"] == [list(p) for p in r.params] and des[r.id]["exits"] == r.exits, r.id
    K.OUT.mkdir(parents=True, exist_ok=True)
    FLAG.write_text(datetime.now(timezone.utc).isoformat())
    ho = days("holdout")
    res = {"read_at_utc": FLAG.read_text(), "expiries": {u: sum(d.und == u for d in ho) for u in K.UNDS}}
    for rule in rules:
        t0 = time.time()
        r = evaluate(rule, ho)
        r["sig"] = significance(rule, ho, r)
        res[rule.id] = r
        print(rule.id, r["m"]["trades"], r["m"]["net"], r["sig"], f"{time.time() - t0:.0f}s", flush=True)
    # BH: the 9 R25 rules + R21's 9
    fam = {r.id: res[r.id]["sig"]["p"] for r in rules} | r21_ps()
    q = dict(zip(fam, RU.bh(list(fam.values()))))
    res["bh_family"] = {k: {"p": fam[k], "q": round(q[k], 4)} for k in fam}
    for rule in rules:
        r, d = res[rule.id], des[rule.id]
        w = wf[rule.id]
        g = {"g1_design_net_pos": d["m"]["net"] > 0,
             "g2_walkforward": w["sum"] > 0 and w["years_positive"] >= 2,
             "g3_holdout_30_trades_net_pos": r["m"]["trades"] >= 30 and r["m"]["net"] > 0,
             "g4_bh_q_lt_005": q[rule.id] < 0.05,
             "g5_x2_spread_and_oor0_pos": r["x2_spread"] > 0 and r["oor_zero"] > 0,
             "g6_without_best_day_pos": r["m"]["net_without_best_day"] > 0,
             "g7_max_dd_lt_40k": r["m"]["max_dd"] > -40000}
        r["gates"] = g
        r["passes"] = all(g.values())
    # secondary: the exit grid's own BH (each exit's holdout net vs its random-time twin is costly; the grid is
    # reported as nets only, and its p-values are the bootstrap of per-expiry net for every rule x exit)
    grid_p = {}
    for rule in rules:
        for ex in RU.EXITS:
            tr = RU.run(ho, rule, exits=ex)
            daily = {(d.und, d.day): 0.0 for d in ho if d.und in rule.unds}
            for t in tr:
                daily[(t.und, t.day)] += t.net
            grid_p[f"{rule.id}:{ex}"] = RU.boot_p(np.array(list(daily.values())), n=4000)
    gq = dict(zip(grid_p, RU.bh(list(grid_p.values()))))
    res["exit_grid_bh"] = {k: {"p": round(grid_p[k], 4), "q": round(gq[k], 4)} for k in grid_p}
    # the holdout census (descriptive; added only now)
    import census as CE
    df = CE.run("holdout")
    import pandas as pd
    cv = pd.read_parquet(K.OUT / "coverage_holdout.parquet")
    (K.RESULTS / "census_holdout.json").write_text(json.dumps(CE.summarise(df, cv, "holdout"), indent=1, default=str))
    HOLDOUT_JSON.write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    {"design": cmd_design, "walkforward": cmd_walkforward, "holdout": cmd_holdout}[sys.argv[1]]()
