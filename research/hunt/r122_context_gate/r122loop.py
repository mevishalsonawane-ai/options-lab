"""R122 loop (PREREG section 1): rounds of variants on the design walk-forward, then the frozen variant's final, once.

    python research/hunt/r122_context_gate/r122loop.py round 1                 # round 1 (fixed in PREREG)
    python research/hunt/r122_context_gate/r122loop.py round 2 <specs.json>    # later rounds: specs chosen from design only
    python research/hunt/r122_context_gate/r122loop.py final <variant>         # the frozen variant, final read ONCE

results/loop.json keeps every variant tried (counted in the BH family). 28 Oct 2026.
"""
from __future__ import annotations

import json
import math
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r122feat as F  # noqa: E402
import r122lib as L  # noqa: E402

RESULTS = HERE / "results"
LOOP = RESULTS / "loop.json"
FINAL_FLAG = RESULTS / "FINAL_READ"
MAX_VARIANTS = 30
ROUND1 = {
    "R1_TREND": dict(kind="rule", rules=["R1_TREND"]), "R2_INTRADAY": dict(kind="rule", rules=["R2_INTRADAY"]),
    "R3_VIX_HIGH": dict(kind="rule", rules=["R3_VIX_HIGH"]), "R4_GAP_AGAINST": dict(kind="rule", rules=["R4_GAP_AGAINST"]),
    "R5_OPPOSED": dict(kind="rule", rules=["R5_OPPOSED"]), "R6_SUPERTREND": dict(kind="rule", rules=["R6_SUPERTREND"]),
    "R7_EMA": dict(kind="rule", rules=["R7_EMA"]), "R8_CHASE": dict(kind="rule", rules=["R8_CHASE"]),
    "R9_TM_SETUP": dict(kind="rule", rules=["R9_TM_SETUP"]),
    "L1_LOGIT": dict(kind="L1", share=0.20), "L2_ANALOGS": dict(kind="L2", share=0.20), "L3_TREES": dict(kind="L3", share=0.20),
}


def data():
    t = F.load()
    design = [x for x in t if x["period"] == "design"]
    final = [x for x in t if x["period"] == "final"]
    return design, final


def load_loop() -> dict:
    return json.loads(LOOP.read_text()) if LOOP.exists() else dict(rounds=[], variants={})


def run_round(n: int, specs: dict) -> dict:
    lp = load_loop()
    if any(r["round"] == n for r in lp["rounds"]):
        raise SystemExit(f"round {n} already run")
    if FINAL_FLAG.exists():
        raise SystemExit("the final has been read: the loop is closed")
    if len(lp["variants"]) + len(specs) > MAX_VARIANTS:
        raise SystemExit(f"at most {MAX_VARIANTS} variants in all")
    design, _ = data()
    best_before = min((v["wf"]["pooled"]["p"] for v in lp["variants"].values()), default=1.0)
    for name, spec in specs.items():
        if name in lp["variants"]:
            raise SystemExit(f"{name} was already tried")
        wf, keep = L.walk_forward(spec, design)
        ev = L.evaluate(wf, keep)
        lp["variants"][name] = dict(round=n, spec=spec, wf=ev)
        p = ev["pooled"]
        print(f"{name:24s} skipped {p['skipped']:5d}/{p['trades']}  net {p['net_before']:>12,.0f} -> {p['net_after']:>12,.0f}"
              f"  uplift {p['uplift']:>10,.0f}  z {p['z']}  p {p['p']}", flush=True)
    best_after = min(v["wf"]["pooled"]["p"] for v in lp["variants"].values())
    lp["rounds"].append(dict(round=n, variants=list(specs), best_p_before=best_before, best_p_after=best_after,
                             improved=best_after < best_before))
    RESULTS.mkdir(parents=True, exist_ok=True)
    LOOP.write_text(json.dumps(lp, indent=1, default=str))
    return lp


def best_variant(lp: dict) -> str:
    return min(lp["variants"], key=lambda k: (lp["variants"][k]["wf"]["pooled"]["p"],
                                             -lp["variants"][k]["wf"]["pooled"]["uplift"]))


def run_final(name: str) -> dict:
    import r120lib as L120
    if FINAL_FLAG.exists():
        raise SystemExit("R122's final has been read once already")
    lp = load_loop()
    if name != best_variant(lp):
        raise SystemExit(f"the frozen variant must be the loop's best on design ({best_variant(lp)})")
    FINAL_FLAG.write_text(f"R122 final read once, 28 Oct 2026: {name}\n")
    design, final = data()
    spec = lp["variants"][name]["spec"]
    keep = L.variant_mask(spec, design, final)
    fin = L.evaluate(final, keep, draws=True, seed=L.SEED + 7)
    wf, wk = L.walk_forward(spec, design)
    wfe = L.evaluate(wf, wk, draws=True, seed=L.SEED + 3)
    m_prior = count_prior()
    m_all = m_prior + len(lp["variants"])
    q = float(L120.bh([fin["pooled"]["p"]], m_all)[0])
    g = L.step3_gates(wfe, fin, q)
    res = dict(variant=name, spec=spec, wf=wfe, final=fin, m_prior=m_prior, m_all=m_all, q=q, gates=g,
               variants_tried=len(lp["variants"]))
    (RESULTS / "final.json").write_text(json.dumps(res, indent=1, default=str))
    return res


def count_prior() -> int:
    import re
    from collections import defaultdict
    import yaml
    import r120cfg as K
    fam, var = defaultdict(int), defaultdict(int)
    for f in sorted(K.ENTRIES.glob("*.yaml")):
        y = yaml.safe_load(f.read_text()) or {}
        i = str(y.get("id", ""))
        if str(y.get("date", "")) > "2026-10-28" or i.startswith("r122"):
            continue
        mm = re.match(r"r(\d+)", i)
        key = ("R" + mm.group(1)) if mm else re.split(r"[-_]", i)[0]
        fam[key] += 1
        try:
            n = int(y.get("variants_tried") or 1)
        except (TypeError, ValueError):
            n = 1
        var[key] += max(n, 1)
    return sum(max(fam[k], var[k]) for k in fam)


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:2] == ["round", "1"]:
        run_round(1, ROUND1)
    elif len(a) == 3 and a[0] == "round":
        run_round(int(a[1]), json.loads(Path(a[2]).read_text()))
    elif len(a) == 2 and a[0] == "final":
        r = run_final(a[1])
        print(json.dumps(dict(pooled=r["final"]["pooled"], q=r["q"], gates=r["gates"]), indent=1))
    elif a == ["best"]:
        print(best_variant(load_loop()))
    else:
        print(__doc__)
