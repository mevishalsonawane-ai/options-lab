#!/usr/bin/env python3
"""Writes R52's experiment entries (research/experiments/entries/r52-*.yaml), one per rule, from results/design.json,
results/final.json and results/rules_prereg.json.

    python3 research/hunt/r52/entries.py
"""
from __future__ import annotations

import json
import re

import yaml

import r52cfg as K

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R52_OLD_CATALOGUE_RECENT.md"
PREREG = "research/hunt/r52/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK series = nearest expiry, ATM+-10, "
        "index_daily VIX), laid out for research/obuy by research/hunt/r52/lake2obuy.py; held contracts re-keyed by strike")
PERIOD = {"design": "20 Nov 2024 - 31 Mar 2026 (walk-forward: 4 quarters Apr 2025 - Mar 2026)",
          "holdout": "final test 1 Apr - 13 Oct 2026, read once"}
COSTS = ("Costs('dated') = charges in force on each date (STT 0.1% to Mar 2026, 0.15% after); Fills('liq') = app bps + "
         "1/2/4-tick half-spread by 5-minute volume, stops x2; next-minute fills; gap-through stops at the open; "
         "1 lot in force")
FAMILY = {"fam_liq": "liquidity", "fam_ctx": "flow-oi", "fam_h42h28": "calendar-events", "fam_swingvol": "overnight",
          "fam_r1": "outside-strategies", "fam_r4": "patterns-indicators", "fam_h25h37": "patterns-indicators",
          "fam_misc": "patterns-indicators"}


def eid(key):
    return "r52-" + re.sub(r"[^a-z0-9]+", "-", key.lower()).strip("-")


def family(key, spec):
    if key in ("OBUY_GC-14", "OBUY_GC-15") or key.startswith("H31"):
        return "overnight"
    if key.startswith("LV-") or key == "BS-1":
        return "option-structure"
    fn = spec.get("fn", "")
    for m, f in FAMILY.items():
        if fn.startswith(m):
            return f
    return "outside-strategies" if spec.get("kind") == "obuy" else "patterns-indicators"


def _s(x):
    if x is None:
        return "-"
    return (f"{x['trades']} trades, win {x['win_rate']:.0%}, net Rs {x['net']:+,.0f} (Rs {x['per_day']:+,.0f}/day), "
            f"worst day Rs {x['worst_day']:+,.0f}, max DD Rs {x['max_dd']:+,.0f}, p {x['p']:.3f} (coin-flip p "
            f"{x.get('p_flip', float('nan')):.3f})")


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    fp = K.RESULTS / "final.json"
    fin = json.loads(fp.read_text()) if fp.exists() else {}
    old = json.loads((K.RESULTS / "old_verdicts.json").read_text())
    n = 0
    for key, v in d.items():
        sp = v["spec"]
        o = fin.get(f"{key}:orig")
        pk = fin.get(f"{key}:{v['pick']}") if v["pick"] else None
        passed = any(x and x["gates"]["pass"] for x in (o, pk))
        parts = [f"Design original: {_s(v['scores']['orig'])}; walk-forward Rs {v['wf_orig']['net']:+,.0f}."]
        if v["pick"]:
            parts.append(f"Re-tune pick {v['pick']} ({v.get('pick_label') or ''}): design {_s(v['scores'][v['pick']])}; "
                         f"walk-forward Rs {v['wf_each'][v['pick']]:+,.0f}.")
        if o:
            parts.append(f"FINAL original: {_s(o)}, q {o['q']:.2f} -> {o['gates']['verdict']}.")
        if pk:
            parts.append(f"FINAL pick: {_s(pk)}, q {pk['q']:.2f} -> {pk['gates']['verdict']}.")
        title = old.get(key, {}).get("title") or sp.get("title") or sp.get("strategy") or key
        e = dict(
            id=eid(key), title=f"R52 re-test on Nov 2024+: {key} {title}", family=family(key, sp), date="2026-10-15",
            doc=DOC,
            hypothesis=(f"The older rejected rule {key} (old verdict: {old.get(key, {}).get('old', 'see CATALOGUE.md')}) "
                        "makes money net of real costs on the recent market (Nov 2024+) at its original setting or a small "
                        "design-only re-tune."),
            data_version=DATA, period=PERIOD, instruments=list(sp.get("unds") or old.get(key, {}).get("unds") or []),
            features=[f"signal source: {sp.get('source')}"],
            model_or_rule=f"{sp.get('params')} | {sp.get('rule')} | exits {sp.get('exits')} | expiry {sp.get('expiry')}",
            settings="research/hunt/r52/results/rules_prereg.json", prereg=PREREG, variants_tried=int(v["n_variants"]),
            results=dict(summary=" ".join(parts), costs=COSTS), holdout_read=bool(o),
            verdict="paper-only" if passed else "rejected",
            notes=(f"Signal source: {sp.get('source')}. Why this original: {sp.get('why')}. Gates: final net > 0, p < 0.05, "
                   "q <= 0.10 (BH over every final read), 40+ final trades, top-5 < 100% of net, design walk-forward > 0."))
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
