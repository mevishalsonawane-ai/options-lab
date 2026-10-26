"""R105E (PREREG.md): buy only a liquid option. Option-liquidity checks Q1-Q3 on R72 (C) and R51 + Supertrend (B).

    python research/hunt/r105e_optliq/r105e.py design
    python research/hunt/r105e_optliq/r105e.py final        # the ONE final read
    R105_EXT=1 python research/hunt/r105e_optliq/r105e.py oos   # after final: 14-19 Oct (R105's store copy), once

Candidates and prices: R105's (r105study.day_cands / price with exits P); walks, twins, stats, gate and BH: R105B's
tested helpers (r105blib). Each rule reads only the bought contract's volume BEFORE the entry row.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
OOS_FLAG = RESULTS / "OOS_READ"
RULES = ("Q1", "Q2", "Q3")
NAMES = {"Q1": "VOL20: the option traded >= 20 lots in the 5 minutes before entry",
         "Q2": "VOL100: the option traded >= 100 lots in the 5 minutes before entry",
         "Q3": "LIVE5: the option traded in every one of the 5 minutes before entry"}
ARMS = ("C", "B")
PRIOR_EXTRA = ("r105b-", "r105c-", "r105d-")


# ================================================================================================ pure
def liquid(rule: str, vol: np.ndarray, er: int, qty: float) -> bool:
    """vol: the contract's (375,) minute volumes in units; only rows er-5 .. er-1 are read."""
    if er < 5 or qty <= 0:
        return False
    seg = np.asarray(vol[er - 5:er], float)
    if rule == "Q1":
        return float(np.nansum(seg)) / qty >= 20.0
    if rule == "Q2":
        return float(np.nansum(seg)) / qty >= 100.0
    if rule == "Q3":
        return bool(np.isfinite(seg).all() and (seg > 0).all())
    raise ValueError(rule)


# ================================================================================================ data
def _mods():
    sys.path.insert(0, str(HERE.parent / "r105_donchian_giveback"))
    sys.path.insert(0, str(HERE.parent / "r105b_direction"))
    import r105study as T
    import r105lib as L5
    import r105blib as LB
    return T, L5, LB


def units(arm, lo, hi):
    """R105B-style units [(key, cands, res, 'side2')], each candidate carrying its liquidity flags."""
    T, L5, LB = _mods()
    E, K = T.mods()["E"], T.K
    spec = L5.rule_spec(None)
    out = []
    for und, D, cs in T.day_cands(arm, lo, hi):
        res = T.price(und, D, cs, spec)
        st = E.stack(und, D)
        qty = float(K.lot(und, D.d))
        for c in cs:
            ci = st.idx(c["side"], c["strike"])
            vol = st.S[ci][4] if ci >= 0 else np.full(375, np.nan)
            c["liq"] = {r: liquid(r, vol, c["er"], qty) for r in RULES}
        out.append(((und, D.d), cs, res, "side2"))
    return out


def block(arm, lo, hi, phase, twins=True):
    T, L5, LB = _mods()
    u = units(arm, lo, hi)
    base = [[True] * len(cs) for _, cs, _, _ in u]
    b0 = LB.run_keep(u, base)
    if phase in ("design", "final"):
        exp = L5.EXPECT[(arm, phase)]
        if (len(b0), round(sum(t["net"] for t in b0))) != exp:
            raise SystemExit(f"{arm} {phase} baseline does not reproduce {exp}")
    out = dict(base=LB.stats(b0), rules={})
    if phase == "design":
        out["base_q"] = LB.quarters(b0)
        out["base_wf_net"] = round(sum(t["net"] for t in LB.split(b0, LB.WF_START, LB.DESIGN_END)))
    for rule in RULES:
        keep = [[c["liq"][rule] for c in cs] for _, cs, _, _ in u]
        vt = LB.run_keep(u, keep)
        r = dict(stats=LB.stats(vt), dropped=sum(1 for k in keep for x in k if not x))
        r["stats"]["avg_loss"] = round(float(np.mean([t["net"] for t in vt if t["net"] <= 0]))) if vt else None
        if phase == "design":
            r["q"] = LB.quarters(vt)
            r["wf_net"] = round(sum(t["net"] for t in LB.split(vt, LB.WF_START, LB.DESIGN_END)))
        if twins:
            rng = np.random.default_rng(1055 + 7 * ARMS.index(arm) + RULES.index(rule))
            tw = LB.twin_totals(u, base, keep, LB.TWINS, rng)
            r["p"] = LB.p_of(tw, sum(t["net"] for t in vt))
        out["rules"][rule] = r
        print(phase, arm, rule, r["stats"]["trades"], r["stats"]["win"], r["stats"]["net"], "base",
              out["base"]["net"], "dropped", r["dropped"], "p", r.get("p"), flush=True)
    return out


def _json(o, p):
    RESULTS.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(o, indent=1, default=str) + "\n")


def design():
    T, L5, LB = _mods()
    _json({a: block(a, L5.START, L5.DESIGN_END, "design") for a in ARMS}, RESULTS / "design.json")


def prior():
    T, L5, LB = _mods()
    pf = LB.prior_family()
    import re
    for f in sorted(LB.ENTRIES.glob("*.yaml")):
        if f.name.startswith(PRIOR_EXTRA):
            txt = f.read_text(errors="replace")
            ps = [float(x) for x in LB._P_RE.findall(txt) if 0 < float(x) <= 1]
            pf.append((f.stem, max(ps) if ps else 1.0))
    return pf


def final():
    T, L5, LB = _mods()
    if FINAL_FLAG.exists():
        raise SystemExit("R105E's final period was already read once")
    des = json.loads((RESULTS / "design.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    fin = {a: block(a, L5.FINAL_START, L5.FINAL_END, "final") for a in ARMS}
    pf = prior()
    new_p = {f"{a}:{r}": max(des[a]["rules"][r]["p"], fin[a]["rules"][r]["p"]) for a in ARMS for r in RULES}
    q = LB.family_q(new_p, pf)
    tests = {}
    for a in ARMS:
        for r in RULES:
            d, f = des[a]["rules"][r], fin[a]["rules"][r]
            t = dict(design=d["stats"], base_design=des[a]["base"], final=f["stats"], base_final=fin[a]["base"],
                     wf_net=d["wf_net"], base_wf_net=des[a]["base_wf_net"], q=d["q"], base_q=des[a]["base_q"],
                     p_design=d["p"], p_final=f["p"], q_bh=q[f"{a}:{r}"])
            t["gate"] = LB.gate(t)
            tests[f"{a}:{r}"] = t
            print(a, r, "PASS" if t["gate"]["passed"] else "fail", t["gate"]["failed"], flush=True)
    _json(dict(read=FINAL_FLAG.read_text().strip(), family=dict(new=6, prior=len(pf)), arms=fin, tests=tests),
          RESULTS / "final.json")


def oos():
    if os.environ.get("R105_EXT", "0") != "1":
        raise SystemExit("run with R105_EXT=1")
    if not FINAL_FLAG.exists() or OOS_FLAG.exists():
        raise SystemExit("OOS is read once, after the final")
    T, L5, LB = _mods()
    OOS_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    _json({a: block(a, L5.OOS_START, L5.OOS_END, "oos", twins=False) for a in ARMS}, RESULTS / "oos.json")


if __name__ == "__main__":
    {"design": design, "final": final, "oos": oos}.get(sys.argv[1] if len(sys.argv) > 1 else "", lambda: print(__doc__))()
