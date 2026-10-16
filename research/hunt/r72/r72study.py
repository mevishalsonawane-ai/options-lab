"""R72 study (PREREG.md): six cross-index rules, run by R71's study code with R72's rules and settings.

    python research/hunt/r72/r72study.py design      # every grid point on design, walk-forward picks
    python research/hunt/r72/r72study.py final       # the ONE final read (refuses a second), twins, BH over 12, gates
    python research/hunt/r72/r72study.py report      # results/*.json -> results/report.md

R71's `r71study` and `r71report` read their rules (P) and settings (R) as module globals; `use_r72()` points them at
r72rules / r72cfg and replaces the per-index signal lookup with the cross-index panel. Everything else (stores, engine,
Donchian's trade-taking, twins, walk-forward, gates, statistics) is R71's tested code, unchanged.
"""
from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r71"))
import r71report as RP  # noqa: E402
import r71study as S  # noqa: E402
import r72cfg as R  # noqa: E402
import r72rules as P  # noqa: E402

_PANEL = None
_SIG: dict = {}


def panel():
    global _PANEL
    if _PANEL is None:
        bars5 = S.mods()["fam_r44"].bars5
        _PANEL = P.Panel({u: bars5(u) for u in S.mods()["K"].UNDS})
    return _PANEL


def rule_signals(und: str, rule: str, param) -> dict:
    """{day: [(entry row, side, inval)]} of one index, from the cross-index rule (cached per rule and setting)."""
    key = (rule, param)
    if key not in _SIG:
        Pn = panel()
        allsig = P.signals_all(rule, param, Pn)
        per = {}
        for u, sig in allsig.items():
            d, b = Pn.f[u]["d"], Pn.f[u]["b"]
            out = defaultdict(list)
            for i, s, inval in sig:
                out[d[i]].append((5 * (int(b[i]) + 1), "C" if s > 0 else "P", float(inval)))
            per[u] = {k: sorted(v) for k, v in out.items()}
        _SIG[key] = per
    return _SIG[key].get(und, {})


def use_r72():
    S.R, S.P, RP.R, RP.P = R, P, R, P
    S.rule_signals = rule_signals
    RP.TITLE = "# R72 report: cross-index signals (tables)"


if __name__ == "__main__":
    use_r72()
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        S.design()
    elif cmd == "final":
        S.final()
    elif cmd == "report":
        RP.write()
    else:
        raise SystemExit(__doc__)
