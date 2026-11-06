"""Writes R151's experiment entries (research/experiments/entries/r151-*.yaml) from results/*.json."""
import json
from pathlib import Path

import yaml

R = Path("research/hunt/r151_donchian_winrate60/results")
d, dw, f, o = (json.loads((R / f"{p}.json").read_text()) for p in ("design", "design_w20", "final", "old"))
dec = {r["id"]: r for r in json.loads((R / "decision.json").read_text())}
COMMON = {"date": "2026-11-05", "doc": "research/HUNT_R151_DONCHIAN_WINRATE60.md",
          "data_version": "Drive lake copied 5 Nov 2026; R51 clean stores + R143's old store (Dec 2022 - Dec 2024)",
          "period": {"design": "20 Nov 2024 - 31 Mar 2026 (WF folds Apr 2025 - Mar 2026)",
                     "holdout": "Apr - Oct 2026 and Dec 2022 - Nov 2024 (both second looks; read once 5 Nov 2026)"},
          "instruments": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
          "settings": "research/hunt/r151_donchian_winrate60/study.py", "prereg": "research/hunt/r151_donchian_winrate60/PREREG.md"}


def best(curve, base_trades):
    ok = [m for m in curve if m.get("trades", 0) >= 0.25 * base_trades]
    return max(ok, key=lambda m: m["win"]) if ok else None


def fm(m):
    return f"{m['trades']} trades, {m['win']:.1%} won, net Rs {m['net']:,.0f}, Rs {m['per_trade']:,.0f} a trade"


out = []
for arm in ("R51", "R72"):
    allp = {**d[arm]["pickers"], **dw[arm]["pickers"]}
    for pid, pk in allp.items():
        b = best(pk["curve"], pk["base_wf"]["trades"])
        s = (f"WF folds: the arm {fm(pk['base_wf'])}; " + (f"W20 alone {fm(pk['w20_wf'])}; " if pk.get("w20") else "") +
             (f"best win keeping >= 25% of trades: {b['win']:.1%} on {b['trades']} trades (Rs {b['per_trade']:,.0f} a trade). "
              if b else "no threshold keeps >= 25% of the arm's trades. ") +
             "No threshold gave >= 60% wins with per trade >= the arm's: fails at design, FINAL / OLD not read for it.")
        out.append({"id": f"r151-{pid.lower().replace('.0', '')}", "family": "ml", "title": f"R151 {arm} trade picker {pk['model']} on Donchian {pk['params'][0]} / "
                    f"{'Supertrend' if arm == 'R51' else 'M'} {pk['params'][1]}" + (" under W20" if pk.get("w20") else ""),
                    **COMMON, "hypothesis": f"A model scoring {arm}'s candidates picks a subset that wins >= 60% with more per trade.",
                    "features": ["signal source: the index (5-min Donchian); features: index minutes, 5-min EMA, 5-min S/R levels, "
                                 "pivots, India VIX, 5-index breadth, all at the decision minute"],
                    "model_or_rule": ("HistGradientBoosting (depth 3)" if pk["model"] == "M1" else "L2 logistic (C 0.1)") +
                    "; take when score >= the design threshold", "variants_tried": 16,
                    "results": {"summary": s + f" BH q {dec[pid]['bh_q']:.2f}.", "costs": "R101 / R106 engine: app fills, dated charges"},
                    "holdout_read": False, "verdict": "rejected"})
    cp = d[arm]["chosen_params"]
    cf, co = f[arm]["chosen_variant"], o[arm]["chosen_variant"]
    sd = next(m for m in d[arm]["sweep"] if m["params"] == cp)
    s = (f"12 variants on design; chosen {cp}: design {fm(sd)}. FINAL {fm(cf)} (today {fm(f[arm]['today'])}); "
         f"OLD {fm(co)} (today {fm(o[arm]['today'])}). Fails: win < 60% in both; OLD net < 0"
         + ("; FINAL win below today's" if cf["win"] <= f[arm]["today"]["win"] else "") + ".")
    out.append({"id": f"r151-p-{arm.lower()}", "family": "patterns-indicators", "title": f"R151 Part P: {arm} Donchian lookback x "
                f"{'Supertrend multiple' if arm == 'R51' else 'indices breaking together'} sweep", **COMMON,
                "hypothesis": f"Another {arm} indicator setting wins >= 60% with per trade >= today's.",
                "features": ["signal source: the index (5-min Donchian)"],
                "model_or_rule": f"Donchian 10/20/30/55 x {'Supertrend (ATR 10) 2/3/4' if arm == 'R51' else 'M 3/4/5 of 5 indices'}",
                "variants_tried": 12, "results": {"summary": s, "costs": "R101 / R106 engine: app fills, dated charges"},
                "holdout_read": True, "verdict": "rejected"})
c = json.loads((R / "c_r72_picker.json").read_text())
cf, ca, g = c["final_apr_oct26"]["picker"], c["final_apr_oct26"]["arm"], c["gate"]
out.append({"id": "r151-c-r72-picker", "family": "ml", "title": "R151 Amendment C: R72 logistic picker (score >= 0.4656) over Nov 2024 - Oct 2026",
            **COMMON, "period": {"design": "20 Nov 2024 - 31 Mar 2026 (Nov 2024 - Mar 2025 in-sample, Apr 2025 - Mar 2026 walk-forward)",
                                 "holdout": "Apr - Oct 2026 (frozen model; second look, disclosed)"},
            "hypothesis": "The R72 logistic picker at its quoted design point keeps trades that win >= 50% with more per trade on Apr - Oct 2026.",
            "features": ["signal source: the index (5-min Donchian 20 breadth, 4 of 5); picker features as R151 section 2"],
            "model_or_rule": "L2 logistic (C 0.1); take when score >= 0.4656", "variants_tried": 1,
            "results": {"summary": (f"Apr 2025 - Mar 2026 WF {fm(c['wf_apr25_mar26']['picker'])} (arm {fm(c['wf_apr25_mar26']['arm'])}); "
                                    f"Apr - Oct 2026 {fm(cf)}, worst month Rs {cf['worst_month']:,.0f}, DD Rs {cf['max_dd']:,} "
                                    f"(arm {fm(ca)}, worst month Rs {ca['worst_month']:,.0f}, DD Rs {ca['max_dd']:,}); twin p {g['twin_win_p']:.3f}. "
                                    f"Fails: {'; '.join(g['fails'])}. Lead: more net and per trade, smaller worst month and drawdown."),
                        "costs": "R101 / R106 engine: app fills, dated charges"},
            "holdout_read": True, "verdict": "rejected",
            "notes": "Amendment D: the first run's features had a cache bug (R72 took R51's); re-run clean, buggy files in results/bug_cache."})
e5 = json.loads((R / "e_r51_picker.json").read_text())
ef, ea, eg = e5["final_apr_oct26"]["picker"], e5["final_apr_oct26"]["arm"], e5["gate"]
out.append({"id": "r151-e-r51-picker", "family": "ml", "title": "R151 Amendment E: R51 logistic picker (score >= 0.4469) over Nov 2024 - Oct 2026",
            **COMMON, "period": {"design": "20 Nov 2024 - 31 Mar 2026 (Nov 2024 - Mar 2025 in-sample, Apr 2025 - Mar 2026 walk-forward)",
                                 "holdout": "Apr - Oct 2026 (frozen model; second look, disclosed)"},
            "hypothesis": "The R51 logistic picker at its quoted design point keeps trades that win >= 50% with more per trade on Apr - Oct 2026.",
            "features": ["signal source: the index (5-min Donchian 20 + Supertrend (10, 3)); picker features as R151 section 2"],
            "model_or_rule": "L2 logistic (C 0.1); take when score >= 0.4469", "variants_tried": 1,
            "results": {"summary": (f"Apr 2025 - Mar 2026 WF {fm(e5['wf_apr25_mar26']['picker'])} (arm {fm(e5['wf_apr25_mar26']['arm'])}); "
                                    f"Apr - Oct 2026 {fm(ef)}, worst month Rs {ef['worst_month']:,.0f}, DD Rs {ef['max_dd']:,} "
                                    f"(arm {fm(ea)}, worst month Rs {ea['worst_month']:,.0f}, DD Rs {ea['max_dd']:,}); twin p {eg['twin_win_p']:.4f}. "
                                    f"Fails: {'; '.join(eg['fails'])}. In the app as a shadow log (6 Nov)."),
                        "costs": "R101 / R106 engine: app fills, dated charges"},
            "holdout_read": True, "verdict": "rejected"})
for e in out:
    Path(f"research/experiments/entries/{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
print(len(out), "entries")
