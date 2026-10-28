"""The forward test's join (FORWARD_PREREG.md): each logged time machine check matched to its paper trade's result in the
same daily diagnostics file. 28 Oct 2026.

    python research/hunt/r122_context_gate/forward.py <folder of diag-YYYY-MM-DD.json.gz>    # counts only

Each check's own result (pct, since 28 Oct) is used when present; otherwise matching: same day, same arm key, same
direction (CE = +1, PE = -1), entry minutes within 2 minutes; each trade is used once. Unmatched checks are counted, never guessed. No outcome statistic is printed here (the read is once, by the
pre-registered rule, after 30 signal days and 300 trades).
"""
from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "research" / "data"))
import read_diagnostics as RD  # noqa: E402

TOLERANCE_MIN = 2


def _minute(day: str, at: str) -> datetime | None:
    """'HH:MM:SS' on day, or a full 'YYYY-MM-DD HH:MM:SS' stamp (a trade from another day)."""
    try:
        return datetime.fromisoformat(at if len(at) > 8 else f"{day} {at}")
    except (TypeError, ValueError):
        return None


def join(data: dict) -> tuple[list, dict]:
    """(rows, counts) for one day's file: each check with its trade's pct, points and exit, or None when unmatched."""
    day = data["day"]
    trades = []
    for a in data.get("arms") or []:
        for t in a.get("trades") or []:
            if t.get("account") != "paper":
                continue
            d = {"CE": 1, "PE": -1}.get(str(t.get("right") or "").upper())
            at = _minute(day, t.get("entry_at") or "")
            if d is None or at is None:
                continue
            trades.append(dict(arm=a.get("key"), direction=d, at=at, pct=t.get("pct"), points=t.get("points"),
                               why=t.get("why"), open=t.get("open"), used=False))
    rows, c = [], dict(checks=0, matched=0, unmatched=0, live=0, open=0)
    for k in RD.time_machine_rows(data):
        c["checks"] += 1
        if k.get("account") != "paper":
            c["live"] += 1
            continue
        if k.get("pct") is not None:                     # the check's own result (the arm reported the close)
            c["matched"] += 1
            rows.append(dict(k, trade=dict(pct=k["pct"], points=None, why=k.get("outcome"), open=False)))
            continue
        at = _minute(day, k.get("at") or "")
        best = None
        for t in trades:
            if t["used"] or t["arm"] != k.get("arm") or t["direction"] != k.get("direction") or at is None:
                continue
            gap = abs((t["at"] - at).total_seconds()) / 60
            if gap <= TOLERANCE_MIN and (best is None or gap < best[0]):
                best = (gap, t)
        if best is None:
            c["unmatched"] += 1
            rows.append(dict(k, trade=None))
            continue
        t = best[1]
        t["used"] = True
        c["matched"] += 1
        c["open"] += bool(t["open"])
        rows.append(dict(k, trade=dict(pct=t["pct"], points=t["points"], why=t["why"], open=t["open"])))
    return rows, c


def main(folder: str) -> dict:
    total = dict(days=0, checks=0, matched=0, unmatched=0, live=0, open=0)
    for f in sorted(Path(folder).glob("diag-*.json.gz")):
        rows, c = join(RD.load(f))
        if c["checks"]:
            total["days"] += 1
        for k in c:
            total[k] += c[k]
    print(total)
    return total


if __name__ == "__main__":
    if len(sys.argv) == 2:
        main(sys.argv[1])
    else:
        print(__doc__)
