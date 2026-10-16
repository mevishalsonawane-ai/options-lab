"""R74 study (PREREG.md): five untried families, run by R71's study code with R74's rules and settings (as R72 did).

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (once)
    python research/hunt/r74/r74study.py design                # every grid point on design, walk-forward picks
    python research/hunt/r74/r74study.py final                 # the ONE final read (refuses a second), twins, BH over 10
    python research/hunt/r74/r74study.py report                # results/*.json -> results/report.md

`use_r74()` points R71's `r71study` / `r71report` at r74rules / r74cfg and replaces the per-index signal lookup with
R74's context (R72's five-index panel + India VIX + the 59 large caps). After each phase `extras_*` adds the comparison
with Breadth Donchian (R72 C1, M = 4, XL: priced by the same code) and, after the final, BH across all 38 tests of
16 Oct (R71's 16 + R72's 12 + R74's 10). Everything else is R71's tested code, unchanged.
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r72"))
sys.path.insert(0, str(HERE.parent / "r71"))
import r71report as RP  # noqa: E402
import r71study as S  # noqa: E402
import r72rules as P72  # noqa: E402
import r74cfg as R  # noqa: E402
import r74data as DATA  # noqa: E402
import r74rules as P  # noqa: E402

_CTX = None
_SIG: dict = {}
C1 = ("C1", 4)                                  # Breadth Donchian, R72's passing original (exit XL)


def ctx() -> P.Ctx:
    global _CTX
    if _CTX is None:
        bars5 = S.mods()["fam_r44"].bars5
        _CTX = P.Ctx(P72.Panel({u: bars5(u) for u in S.mods()["K"].UNDS}), DATA.load_vix(), DATA.load_stocks())
    return _CTX


def rule_signals(und: str, rule: str, param) -> dict:
    """{day: [(entry row, side, inval)]} of one index (cached per rule and setting). Rule "C1" is R72's Breadth
    Donchian, priced only for the comparison."""
    key = (rule, param)
    if key not in _SIG:
        X = ctx()
        allsig = P72.signals_all(rule, param, X.P) if rule == "C1" else P.signals_all(rule, param, X)
        per = {}
        for u, sig in allsig.items():
            d, b = X.P.f[u]["d"], X.P.f[u]["b"]
            out = defaultdict(list)
            for i, s, inval in sig:
                out[d[i]].append((5 * (int(b[i]) + 1), "C" if s > 0 else "P", float(inval)))
            per[u] = {k: sorted(v) for k, v in out.items()}
        _SIG[key] = per
    return _SIG[key].get(und, {})


def use_r74():
    S.R, S.P, RP.R, RP.P = R, P, R, P
    S.rule_signals = rule_signals
    RP.TITLE = "# R74 report: five untried families (tables)"


def _c1(lo, hi):
    return S.rule_trades(*C1, "XL", lo, hi)


def extras_design():
    """Design: daily correlation and same-trade overlap of each rule's original with Breadth Donchian."""
    f = R.RESULTS / "design.json"
    des = json.loads(f.read_text())
    lo, hi = R.START, R.DESIGN_END
    ses = S.sessions(lo, hi)
    c1 = _c1(lo, hi)
    des["breadth_donchian"] = dict(design=S.stats(c1, S.months_between(lo, hi)))
    for rule, r in des["rules"].items():
        p, ex = S.gp_parse(rule, r["original"])
        tr = S.rule_trades(rule, p, ex, lo, hi)
        r["corr_c1"] = S.daily_corr(tr, c1, ses)
        r["overlap_c1"] = S.overlap(tr, c1)
    S._json(des, f)
    return des


def extras_final():
    """Final: the same against Breadth Donchian, the combined book with it, and BH across the 38 tests of 16 Oct."""
    f = R.RESULTS / "final.json"
    fin = json.loads(f.read_text())
    M = S.mods()
    lo, hi = R.FINAL_START, M["K"].END
    ses = S.sessions(lo, hi)
    c1 = _c1(lo, hi)
    fin["breadth_donchian"] = S.stats(c1, S.months_between(lo, hi))
    for k, t in fin["tests"].items():
        p, ex = S.gp_parse(t["rule"], t["setting"])
        tr = S.rule_trades(t["rule"], p, ex, lo, hi)
        t["corr_c1"] = S.daily_corr(tr, c1, ses)
        t["overlap_c1"] = S.overlap(tr, c1)
        t["with_c1"] = S.combined(c1, tr)
        t["duplicate"] = max(t["corr_donchian"] or 0, t["corr_c1"] or 0) >= R.DUPLICATE_CORR
    prior = []
    for r in ("r71", "r72"):
        pf = json.loads((HERE.parent / r / "results" / "final.json").read_text())
        prior += [t["p"] if t["p"] is not None else 1.0 for t in pf["tests"].values()]
    keys = list(fin["tests"])
    q38 = S.bh(prior + [fin["tests"][k]["p"] if fin["tests"][k]["p"] is not None else 1.0 for k in keys])
    for k, q in zip(keys, q38[len(prior):]):
        fin["tests"][k]["q38"] = round(float(q), 4)
    S._json(fin, f)
    return fin


if __name__ == "__main__":
    use_r74()
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        S.design()
        extras_design()
    elif cmd == "final":
        S.final()
        extras_final()
    elif cmd == "report":
        RP.write()
    else:
        raise SystemExit(__doc__)
