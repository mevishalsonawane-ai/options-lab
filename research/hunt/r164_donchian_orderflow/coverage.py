"""R164: how many Donchian trades the phone's recordings can cover, and how many a decision needs (12 Nov 2026).

Pure arithmetic from published, committed numbers (no recording read):

- trades a session: R72 final 1,202 trades / R51+Supertrend (R73 T7) final 1,670 trades over the same 133 sessions
  (1 Apr - 13 Oct 2026); R151's pickers kept 652 (R72) and 693 (R51) of them;
- the tick zone covers 2 of the arms' 5 indices (NIFTY, BANKNIFTY). Their share of trades is NOT in the committed results,
  so it is taken as 2/5 (an assumption, said wherever it is used);
- NSE sessions with the tick zone on: 12 Oct - 11 Nov 2026 = 21 (holidays 20 Oct Dussehra, 10 Nov Diwali Balipratipada;
  the 8 Nov Muhurat hour is left out).

    python -m research.hunt.r164_donchian_orderflow.coverage      # writes results/coverage.json
"""
from __future__ import annotations

import json
from datetime import date, timedelta
from math import ceil, sqrt
from pathlib import Path

from statistics import NormalDist

HERE = Path(__file__).resolve().parent
SESSIONS_RESEARCH = 133
TRADES = {"R72": 1202, "R51": 1670, "R72 picker": 652, "R51 picker": 693}
WIN = {"R72": 0.4584, "R51": 0.4485, "R72 picker": 0.489, "R51 picker": 0.499}   # final-period win rates (R72, R73, R151)
SHARE_COVERED = 2 / 5
HOLIDAYS = {date(2026, 10, 20), date(2026, 11, 10)}
REC_FROM, REC_TO = date(2026, 10, 12), date(2026, 11, 11)


def sessions(a: date, b: date, holidays=HOLIDAYS) -> int:
    n, d = 0, a
    while d <= b:
        if d.weekday() < 5 and d not in holidays:
            n += 1
        d += timedelta(days=1)
    return n


def expected_covered(trades: int, n_sessions: int, share: float = SHARE_COVERED, per: int = SESSIONS_RESEARCH) -> float:
    return trades / per * n_sessions * share


def trades_needed(p0: float, gain: float = 0.05, skip: float = 0.30, alpha: float = 0.05, power: float = 0.80) -> int:
    """Trades needed (before skipping) so that a filter skipping a share ``skip`` and lifting the kept trades' win rate by
    ``gain`` over all trades is found with ``power`` at one-sided ``alpha``.

    Kept win = p0 + gain, so the skipped win = p0 - gain (1-skip)/skip. The test is kept against skipped (two proportions,
    normal approximation): n_kept = (1-skip) n, n_skip = skip n."""
    pk = p0 + gain
    ps = p0 - gain * (1 - skip) / skip
    if not 0 < ps < 1:
        raise ValueError("gain too large for that skip share")
    z = NormalDist().inv_cdf(1 - alpha) + NormalDist().inv_cdf(power)
    var = pk * (1 - pk) / (1 - skip) + ps * (1 - ps) / skip
    return ceil((z ** 2) * var / (pk - ps) ** 2)


def main() -> int:
    n = sessions(REC_FROM, REC_TO)
    m_total = 131 + 18
    out = {
        "sessions_recorded_at_most": n,
        "share_covered_assumed": SHARE_COVERED,
        "expected_covered_trades": {k: round(expected_covered(v, n), 1) for k, v in TRADES.items()},
        "per_session_covered": {k: round(expected_covered(v, 1), 2) for k, v in TRADES.items()},
        "trades_needed": {},
        "sessions_needed": {},
    }
    for k, p0 in WIN.items():
        row = {}
        for skip in (0.2, 0.3, 0.5):
            row[f"skip{int(skip*100)}_alpha05"] = trades_needed(p0, skip=skip)
            # the first BH rank among the family's m tests at q 0.10 needs p <= 0.10 / m
            row[f"skip{int(skip*100)}_bh_first"] = trades_needed(p0, skip=skip, alpha=0.10 / m_total)
        out["trades_needed"][k] = row
        per = expected_covered(TRADES[k], 1)
        out["sessions_needed"][k] = {kk: ceil(v / per) for kk, v in row.items()}
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / "coverage.json").write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
