"""R26 driver (PREREG.md): every rule on every expiry, the splits, the random-time twins, BH and the gates.

    python3 research/hunt/r26/r26study.py        # -> results/results.json, results/report.md (with describe.py's tables)

Every parameter comes from Boss's text (PREREG.md), so there is nothing to fit and no holdout to lock: all 770 expiries
are an out-of-sample test. R25's split (2021-22 / 2023 - Oct 2026) and every year are reported separately.
"""
from __future__ import annotations

import json
import time
from dataclasses import replace

import numpy as np

import r26cfg as K26
import r25cfg as K
import r25data as D
import r25rules as RU
import r26lib as L

BLOCKS = {"2021-22": lambda d: d <= K26.HOLDOUT_END, "2023-26": lambda d: d > K26.HOLDOUT_END}
EXITS = ("E1", "E1b", "E2", "E3")


def all_days() -> list:
    out = []
    for u in K26.UNDS:
        out += D.load(u, "all")
    out.sort(key=lambda d: (d.day, d.und))
    return out


def hm(r: int) -> str:
    m = 9 * 60 + 15 + int(r) + 1
    return f"{m // 60:02d}:{m % 60:02d}"


def r21_r25_ps() -> dict:
    """The 18 earlier p-values of the hero family (R25's BH family: R25's 9 + R21's 9)."""
    h = json.loads((K.RESULTS / "holdout.json").read_text())["bh_family"]
    return {(k if k.startswith("R21-") else f"R25-{k}"): float(v["p"]) for k, v in h.items()}


def tlist(trades) -> list:
    return [dict(und=t.und, day=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike, signal=hm(t.sig_row),
                 fill=hm(t.fill_row - 1), entry=t.entry, lots=t.lots, qty=t.qty, risked=round(t.risked, 0),
                 peak_mult=round(t.peak_mult, 2), net=round(t.net, 0), exit=t.exits[-1][3] if t.exits else "",
                 exit_px=t.exits[-1][2] if t.exits else None, over_budget=bool(getattr(t, "over_budget", False)),
                 proxy=bool(t.proxy)) for t in trades]


def split(trades, days, rule, key) -> dict:
    out = {}
    for name, f in key.items():
        ds = [d for d in days if f(d)]
        ts = [t for t in trades if any(t.und == d.und and t.day == d.day for d in ds)] if ds else []
        out[name] = L.metrics(ts, ds, rule)
    return out


def evaluate(rule, days) -> dict:
    tr, sigs = L.run(days, rule)
    scope = [d for d in days if d.und in rule.unds]
    m = L.metrics(tr, scope, rule)
    res = {"rule": rule.id, "note": rule.note, "m": m, "signals": len(sigs)}
    res["blocks"] = split(tr, scope, rule, {k: (lambda d, f=f: f(d.day)) for k, f in BLOCKS.items()})
    res["years"] = split(tr, scope, rule, {str(y): (lambda d, y=y: d.day.year == y) for y in sorted({d.day.year for d in scope})})
    res["index"] = split(tr, scope, rule, {u: (lambda d, u=u: d.und == u) for u in rule.unds})
    up = [t for t in tr if t.side > 0]
    dn = [t for t in tr if t.side < 0]
    res["direction"] = {"UP (CE)": L.metrics(up, scope, rule), "DOWN (PE)": L.metrics(dn, scope, rule)}
    res["x2_spread"] = L.metrics(L.run(scope, rule, mult=2.0)[0], scope, rule)["net"]
    res["oor_zero"] = L.metrics(L.run(scope, rule, oor_zero=True)[0], scope, rule)["net"]
    res["exit_grid"] = {ex: L.metrics([t for t in (L.trade(d, rule, s, exits=ex) for d, s in sigs) if t], scope, rule)["net"]
                        for ex in EXITS}
    t0 = time.time()
    tw = L.twins(sigs, rule)
    res["twins"] = {"p": round(L.twin_p(m["net"], tw), 4), "mean": round(float(tw.mean()), 0),
                    "p05": round(float(np.percentile(tw, 5)), 0), "p95": round(float(np.percentile(tw, 95)), 0),
                    "n": len(tw), "secs": round(time.time() - t0, 1)}
    res["trades_list"] = tlist(tr)
    res["_trades"] = tr
    res["_sigs"] = sigs
    return res


def gates(r: dict) -> dict:
    m = r["m"]
    b = r["blocks"]
    g = {"1 >= 30 trades": m["trades"] >= 30,
         "2 net > 0": m["net"] > 0,
         "3 both blocks > 0": all(b[k]["net"] > 0 for k in b if b[k]["expiries"] > 0),
         "4 BH q < 0.05": r["q"] < 0.05,
         "5 x2 spread and oor 0 > 0": r["x2_spread"] > 0 and r["oor_zero"] > 0,
         "6 net w/o best day > 0": m["net_without_best_day"] > 0,
         "7 max DD > -40k": m["max_dd"] > -40000}
    return {"gates": g, "passes": all(g.values())}


def main():
    days = all_days()
    print("expiries", len(days), {u: sum(d.und == u for d in days) for u in K26.UNDS}, flush=True)
    res = {}
    for rule in L.RULES:
        res[rule.id] = evaluate(rule, days)
        m = res[rule.id]["m"]
        print(rule.id, m["trades"], m["net"], "p", res[rule.id]["twins"]["p"], flush=True)
    # BH: R21 9 + R25 9 + R26 10 = 28
    fam = r21_r25_ps() | {f"R26-{k}": v["twins"]["p"] for k, v in res.items()}
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for k, v in res.items():
        v["q"] = round(qs[f"R26-{k}"], 4)
        v.update(gates(v))
    # baseline (b): the trades B1 took on expiries P did not trade
    p_days = {(t.und, t.day) for t in res["P"]["_trades"]}
    extra = [t for t in res["B1"]["_trades"] if (t.und, t.day) not in p_days]
    scope = days
    b_extra = L.metrics(extra, scope, L.P)
    # A7 vs P's BANKNIFTY trades; A8 vs P's SENSEX trades
    cmp_ = {"P BANKNIFTY": res["P"]["index"]["BANKNIFTY"], "A7 BANKNIFTY": res["A7"]["m"],
            "P SENSEX": res["P"]["index"]["SENSEX"], "A8 SENSEX": res["A8"]["m"]}
    out = {"expiries": {u: sum(d.und == u for d in days) for u in K26.UNDS}, "n_expiries": len(days),
           "blocks": {k: sum(f(d.day) for d in days) for k, f in BLOCKS.items()},
           "bh_family": {k: {"p": round(p, 4), "q": round(qs[k], 4)} for k, p in fam.items()}, "bh_size": len(fam),
           "b1_extra": b_extra, "compare": cmp_,
           "rules": {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_")} for k, v in res.items()}}
    K26.RESULTS.mkdir(parents=True, exist_ok=True)
    (K26.RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print("BH family", len(fam), "min q", round(min(qs.values()), 3))
    print("passes:", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
