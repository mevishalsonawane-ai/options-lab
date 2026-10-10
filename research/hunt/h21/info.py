"""h21 information table (no selection): the combined book's Rs/day before / in the holdout under the app's rules today,
with the guard, and with the simple discipline rules, all at kappa 0.02; plus Liquidity trades lost to the guard."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse as A, rules, sim
import pandas as pd

P, F = sim.load()
O = A.outcomes(P)
lq = A.liq_trades()
alld = A.all_days()
first = min(O[("orb", "real", "fixed")].day)
out = {}
for which in ("pre", "hold"):
    days = pd.Index([d for d in alld if (d < sim.HOLDOUT if which == "pre" else d >= sim.HOLDOUT) and d >= first])
    lqw = lq[(lq.day < sim.HOLDOUT) if which == "pre" else (lq.day >= sim.HOLDOUT)]
    bn = lqw[lqw.arm == "liq_bn"]
    for nm, v in (("R0 app today, no guard", dict(rules.V("R0"), guard=False)), ("R0 app today + guard", rules.V("R0")),
                  ("V01 fixed lock + guard", rules.VARIANTS["V01_base_fixed"]), ("V02 max1 + guard", rules.VARIANTS["V02_max1"]),
                  ("V08 PKG + guard", rules.VARIANTS["V08_PKG"])):
        res = A.run_variant(O, F, v, which)
        s = A.summ(res["combined"], days)
        cands = {a: A.candidates(O, F, a, v, which) for a in sim.ARM_ORDER}
        comb, lk = rules.combined_positions(cands, v, guard=True, liq=bn)
        tot = pd.concat([comb[a] for a in sim.ARM_ORDER] + [lk, lqw[lqw.arm == "liq_fin"]])
        st = A.summ(tot, days)
        out[f"{which}|{nm}"] = dict(n=s["n"], gross_day=s["gross_day"], rs_day=s["rs_day"], maxdd=s["maxdd"],
                                    with_liq_rs_day=st["rs_day"], liq_bn_lost=len(bn) - len(lk),
                                    liq_bn_lost_net=round(float(bn.net.sum() - lk.net.sum())))
    out[f"{which}|Liquidity alone"] = dict(rs_day=A.summ(lqw, days)["rs_day"])
json.dump(out, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "info_report.json"), "w"), indent=1)
for k, v in out.items():
    print(k, v)
