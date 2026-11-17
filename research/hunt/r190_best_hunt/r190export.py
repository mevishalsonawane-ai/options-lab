"""R190 trade export for the research dashboard (17 Nov 2026, Boss: "Build the research dashboard").

Writes every R190 trade of the 13 candidates, all three eras, to one compact JSON file OUTSIDE git
(~/.cache/iraalgo/r190/dashboard_trades.json by default), for the dashboard page to embed. It changes no result:
era A was already read once (ERA_A_READ.flag, commit 823379785) and its numbers are in results/report.md.

    python3 -I research/hunt/r190_best_hunt/r190export.py [out.json]

Each trade is a row [candidate, index, day "YYYY-MM-DD", era, side "CE"/"PE", net Rs at 1 lot], plus a table of
candidate names and the verdicts from decision.json.
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402

COLS = ["cand", "index", "day", "era", "side", "net"]


def side_name(side) -> str:
    """CE for a call / long side, PE for a put / short side; arms store 'C' / 'P', the rules +1 / -1."""
    if isinstance(side, str):
        s = side.upper()
        if s in ("C", "CE", "CALL"):
            return "CE"
        if s in ("P", "PE", "PUT"):
            return "PE"
        raise ValueError(f"unknown side {side!r}")
    if side > 0:
        return "CE"
    if side < 0:
        return "PE"
    raise ValueError("side 0 is not a trade")


def compact(cid: str, trades: list) -> list:
    """One row per trade; drops trades with no era or a non-finite net (none should exist, but never emit NaN)."""
    out = []
    for t in trades or []:
        era, net = t.get("era"), t.get("net")
        if era is None or net is None or not math.isfinite(float(net)):
            continue
        out.append([cid, t.get("und", "NIFTY"), t["day"].isoformat(), era, side_name(t["side"]), round(float(net), 2)])
    out.sort(key=lambda r: (r[2], r[1]))
    return out


def build(sets: dict, decision: dict) -> dict:
    rows = []
    for cid in K.CANDIDATES:
        rows += compact(cid, sets.get(cid))
    cands = decision.get("candidates") or {}
    verdicts = {c: dict(reliable=bool(cands[c].get("reliable")), fails=list(cands[c].get("fails") or []),
                        stop=cands[c].get("account_stop_breaches") or {})
                for c in K.CANDIDATES if c in cands}
    return dict(cols=COLS, names={c: K.NAMES[c] for c in K.CANDIDATES}, verdicts=verdicts, rows=rows,
                m_total=decision.get("m_total"))


def main(argv: list) -> None:
    import r190study as S
    out = Path(argv[1]) if len(argv) > 1 else K.OUT / "dashboard_trades.json"
    ns, rs, ar = S.night_sets(), S.rule_sets(), S.arm_sets()
    sets = {**{c: ns[c] for c in S.NIGHT}, **{c: rs[c] for c in S.RULES}, **{c: ar.get(c) for c in S.ARMS}}
    decision = json.loads((HERE / "decision.json").read_text())
    data = build(sets, decision)
    out.write_text(json.dumps(data, separators=(",", ":")))
    print(f"{len(data['rows'])} trades -> {out}")


if __name__ == "__main__":
    main(sys.argv)
