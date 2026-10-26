"""R105C (PREREG.md): percent targets for a 60% win rate on R72 Breadth Donchian (C) and R51 + Supertrend (B).

    python research/hunt/r105c_winrate60/r105c.py design     # 24 rules on design + walk-forward; writes results/pick.json
    python research/hunt/r105c_winrate60/r105c.py final      # the ONE final read (refuses a second)

Prices: R105's engine and candidates (r105study.price / day_cands / walk), unchanged; a rule only adds a limit target
on the premium (and, for TkB, breakeven once half-way). Signal on the index.
"""
from __future__ import annotations

import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
PICK = RESULTS / "pick.json"

TARGETS = (5, 8, 10, 12, 15, 20)
RULES = tuple([f"T{k}" for k in TARGETS] + [f"T{k}B" for k in TARGETS])
ARMS = ("C", "B")
WIN_NEED = 0.60


def spec_of(rule: str, off: dict, p_steps: tuple) -> dict:
    """Engine inputs of a rule on top of exits P. Pure."""
    b = rule.endswith("B")
    k = int(rule[1:-1] if b else rule[1:]) / 100.0
    steps = p_steps + (((k / 2, math.nan),) if b else ())
    return dict(off, steps=steps, target=k)


def pick(design: dict) -> dict:
    """PREREG section 3: the highest design net with design win >= 60%; else the highest design win. Pure.
    design: {rule: {"win": .., "net": ..}}."""
    ok = [r for r, s in design.items() if (s.get("win") or 0) >= WIN_NEED - 1e-12]
    if ok:
        r = max(ok, key=lambda x: (design[x]["net"], design[x]["win"]))
        return dict(rule=r, reached60=True)
    r = max(design, key=lambda x: (design[x]["win"] or 0, design[x]["net"]))
    return dict(rule=r, reached60=False)


def verdict(wf: dict, fin: dict) -> str:
    w = (wf.get("win") or 0) >= WIN_NEED and (fin.get("win") or 0) >= WIN_NEED
    if w and wf["net"] > 0 and fin["net"] > 0:
        return "meets 60%"
    if w:
        return "60% but losing"
    return "not reached"


def _study():
    sys.path.insert(0, str(HERE.parent / "r105_donchian_giveback"))
    import r105study as T
    import r105lib as L
    return T, L


def run(arm, rule, lo, hi):
    T, L = _study()
    spec = L.rule_spec(None) if rule is None else spec_of(rule, L.OFF, L.P_STEPS)
    tr = []
    for und, D, cs in T.day_cands(arm, lo, hi):
        tr += T.walk(cs, T.price(und, D, cs, spec))
    return tr


def summ(tr, L):
    s = L.stats(tr)
    return {k: s.get(k) for k in ("trades", "win", "net", "avg_win", "avg_loss", "worst_day", "max_dd")}


def design():
    T, L = _study()
    out = {}
    for arm in ARMS:
        a = {}
        for rule in (None,) + RULES:
            tr = run(arm, rule, L.START, L.DESIGN_END)
            wf = L.split(tr, L.WF_START, L.WF_END)
            a[rule or "today"] = dict(design=summ(tr, L), wf=summ(wf, L))
            print(arm, rule, a[rule or "today"]["design"]["win"], a[rule or "today"]["design"]["net"], flush=True)
        if (a["today"]["design"]["trades"], a["today"]["design"]["net"]) != L.EXPECT[(arm, "design")]:
            raise SystemExit(f"baseline {arm} does not reproduce")
        out[arm] = a
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(out, indent=1) + "\n")
    picks = {arm: pick({r: out[arm][r]["design"] for r in RULES}) for arm in ARMS}
    PICK.write_text(json.dumps(picks, indent=1) + "\n")
    print(picks)


def final():
    T, L = _study()
    if FINAL_FLAG.exists():
        raise SystemExit("R105C's final period was already read once (results/FINAL_READ)")
    if not PICK.exists():
        raise SystemExit("run design first (the pick is fixed before the final read)")
    picks = json.loads(PICK.read_text())
    des = json.loads((RESULTS / "design.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    out = {}
    for arm in ARMS:
        a = {}
        for rule in (None,) + RULES:
            tr = run(arm, rule, L.FINAL_START, L.FINAL_END)
            a[rule or "today"] = summ(tr, L)
            print(arm, rule, a[rule or "today"]["win"], a[rule or "today"]["net"], flush=True)
        if (a["today"]["trades"], a["today"]["net"]) != L.EXPECT[(arm, "final")]:
            raise SystemExit(f"baseline {arm} final does not reproduce")
        p = picks[arm]["rule"]
        a["pick"] = dict(picks[arm], verdict=verdict(des[arm][p]["wf"], a[p]))
        out[arm] = a
    (RESULTS / "final.json").write_text(json.dumps(out, indent=1) + "\n")
    print({k: v["pick"] for k, v in out.items()})


# ------------------------------------------------------------------------------------------------ Amendment 1
S10_RULES = ("S10",) + tuple(f"S10T{k}" for k in TARGETS)
FINAL_FLAG_S10 = RESULTS / "FINAL_READ_S10"
PICK_S10 = RESULTS / "pick_s10.json"


def s10_spec(rule: str, off: dict, p_steps: tuple) -> dict:
    """S10 = exits P with no target; S10Tk = exits P + a +k% target (the -10% stop is set by run_s10). Pure."""
    return dict(off, steps=p_steps) if rule == "S10" else spec_of(rule[3:], off, p_steps)


def run_s10(arm, rule, lo, hi):
    T, L = _study()
    old = L.P_STOP
    L.P_STOP = 0.10
    try:
        spec = s10_spec(rule, L.OFF, L.P_STEPS)
        tr = []
        for und, D, cs in T.day_cands(arm, lo, hi):
            tr += T.walk(cs, T.price(und, D, cs, spec))
    finally:
        L.P_STOP = old
    return tr


def design_s10():
    T, L = _study()
    out = {}
    for arm in ARMS:
        a = {}
        for rule in S10_RULES:
            tr = run_s10(arm, rule, L.START, L.DESIGN_END)
            a[rule] = dict(design=summ(tr, L), wf=summ(L.split(tr, L.WF_START, L.WF_END), L))
            print(arm, rule, a[rule]["design"]["win"], a[rule]["design"]["net"], flush=True)
        out[arm] = a
    (RESULTS / "design_s10.json").write_text(json.dumps(out, indent=1) + "\n")
    picks = {arm: pick({r: out[arm][r]["design"] for r in S10_RULES[1:]}) for arm in ARMS}
    PICK_S10.write_text(json.dumps(picks, indent=1) + "\n")
    print(picks)


def final_s10():
    T, L = _study()
    if FINAL_FLAG_S10.exists():
        raise SystemExit("R105C Amendment 1's final period was already read once")
    if not PICK_S10.exists():
        raise SystemExit("run design_s10 first")
    picks = json.loads(PICK_S10.read_text())
    des = json.loads((RESULTS / "design_s10.json").read_text())
    FINAL_FLAG_S10.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    out = {}
    for arm in ARMS:
        a = {r: summ(run_s10(arm, r, L.FINAL_START, L.FINAL_END), L) for r in S10_RULES}
        for r in S10_RULES:
            print(arm, r, a[r]["win"], a[r]["net"], flush=True)
        p = picks[arm]["rule"]
        a["pick"] = dict(picks[arm], verdict=verdict(des[arm][p]["wf"], a[p]))
        out[arm] = a
    (RESULTS / "final_s10.json").write_text(json.dumps(out, indent=1) + "\n")
    print({k: v["pick"] for k, v in out.items()})


if __name__ == "__main__":
    {"design": design, "final": final, "design_s10": design_s10, "final_s10": final_s10}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: print(__doc__))()
