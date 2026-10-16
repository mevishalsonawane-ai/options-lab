"""R74 diagnostics of the test that passed the gates (B2 original), AFTER the final read: no new gate, nothing re-picked.
Splits B2's trades by index, month / quarter and side, into those Breadth Donchian (R72 C1) also takes (same index, day
and side, entry within 15 minutes) and the rest, and prices its design at twice the slippage.

    python research/hunt/r74/r74diag.py            # -> results/diag.json
"""
from __future__ import annotations

from collections import defaultdict

import r74study as X

X.use_r74()
S, R = X.S, X.R


def by(trades, f) -> dict:
    o = defaultdict(lambda: [0, 0.0])
    for t in trades:
        k = f(t)
        o[k][0] += 1
        o[k][1] += t["net"]
    return {str(k): dict(trades=v[0], net=round(v[1])) for k, v in sorted(o.items())}


def split_c1(trades, c1):
    idx = defaultdict(list)
    for t in c1:
        idx[(t["und"], t["day"], t["side"])].append(t["er"])
    shared = [t for t in trades if any(abs(t["er"] - e) <= R.OVERLAP_ROWS for e in idx.get((t["und"], t["day"], t["side"]), ()))]
    ids = {id(t) for t in shared}
    return shared, [t for t in trades if id(t) not in ids]


def tot(tr):
    return dict(trades=len(tr), net=round(sum(t["net"] for t in tr)))


def main():
    M = S.mods()
    out = {}
    dlo, dhi = R.START, R.DESIGN_END
    bd, c1d = S.rule_trades("B2", 1.5, "XL", dlo, dhi), X._c1(dlo, dhi)
    sh, own = split_c1(bd, c1d)
    E = M["E"]
    old = E.SLIP
    E.SLIP = E.SLIP2
    try:
        bd2 = S.rule_trades("B2", 1.5, "XL", dlo, dhi)
    finally:
        E.SLIP = old
    out["design"] = dict(all=tot(bd), shared_with_c1=tot(sh), not_in_c1=tot(own), slip2=tot(bd2),
                         by_index=by(bd, lambda t: t["und"]),
                         by_quarter=by(bd, lambda t: f"{t['day'].year}Q{(t['day'].month - 1) // 3 + 1}"))
    M["C"].open_final()
    lo, hi = R.FINAL_START, M["K"].END
    bf, c1f = S.rule_trades("B2", 1.5, "XL", lo, hi), X._c1(lo, hi)
    sh, own = split_c1(bf, c1f)
    out["final"] = dict(all=tot(bf), shared_with_c1=tot(sh), not_in_c1=tot(own), by_index=by(bf, lambda t: t["und"]),
                        by_month=by(bf, lambda t: str(t["day"])[:7]), by_side=by(bf, lambda t: t["side"]),
                        c1_alone=S.combined(c1f, []), c1_plus_b2_not_in_c1=S.combined(c1f, own))
    S._json(out, R.RESULTS / "diag.json")
    print(out)


if __name__ == "__main__":
    main()
