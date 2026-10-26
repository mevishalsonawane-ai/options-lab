"""R107 meters (PREREG section 2): the GEX reading a trade uses and the app's danger meter, day by day.

Pure functions (tested in test_r107.py) plus two loaders (GEX parquet, the meter's inputs from the R51 stores).

GEX meter: R28's per-minute table; the reading at 09:20 is grid row 4 (the bar that closes at 09:20), at 10:00 row 44.
Each value is computed by R28 from that session's earlier rows only. A trade entering at row >= 45 uses the 10:00
reading, rows 5-44 the 09:20 one; "negative" = near_net_cr < 0; no reading = not flagged.

Danger meter: a line-for-line copy of the app's fixed model (android ira-core PreTradeChecks.kt, object DangerMeter,
13 Oct 2026): buckets, shrunk deviations, cut points and levels. Its inputs are known by 09:16.
"""
from __future__ import annotations

import calendar
import csv
import math
from datetime import date

import r107cfg as K

# ------------------------------------------------------------------------------------------------ GEX
def gex_reading(er: int, v0920: float, v1000: float) -> float:
    """The reading a trade entering at grid row `er` may use (NaN = none): 10:00's from row 45, else 09:20's from row 5.
    A row before 5 (before the 09:20 bar has closed) has no reading."""
    if er >= K.FIRST_ROW_1000:
        return v1000
    if er > K.ROW_0920:
        return v0920
    return float("nan")


def is_neg(v) -> bool:
    return v is not None and not (isinstance(v, float) and math.isnan(v)) and v < 0


def load_gex(und: str) -> dict:
    """{day: (gex at 09:20, gex at 10:00)} (Rs crore, S1 near expiry; NaN where the row is missing)."""
    import pandas as pd
    p = K.GEX_DIR / f"gex_{und}.parquet"
    g = pd.read_parquet(p, columns=["day", "minute", "near_net_cr"])
    g = g[g.minute.isin([K.ROW_0920, K.ROW_1000])]
    out = {}
    for d, x in g.groupby("day"):
        m = dict(zip(x.minute.astype(int), x.near_net_cr.astype(float)))
        dd = d if isinstance(d, date) else d.date()
        out[dd] = (m.get(K.ROW_0920, float("nan")), m.get(K.ROW_1000, float("nan")))
    return out


# ------------------------------------------------------------------------------------------------ danger meter (app copy)
OVERALL = -895.2
CAREFUL_AT = -372.5
PAPER_ONLY_AT = -753.9
GAP_CUTS = (0.11, 0.301)
VIX_CUTS = (10.68, 11.98)
VIX_MOVE = 0.05
# (feature, bucket) -> (sessions, mean, shrunk deviation), DangerMeter.MODEL
MODEL = {
    ("gap", "large"): (110, -709.5, 157.1), ("gap", "mid"): (111, -610.8, 241.0), ("gap", "small"): (126, -1307.8, -356.1),
    ("vix", "high"): (119, -691.4, 174.4), ("vix", "low"): (156, -982.2, -77.1), ("vix", "mid"): (72, -1043.4, -116.0),
    ("vix_chg", "down"): (25, -1157.1, -145.5), ("vix_chg", "flat"): (279, -847.1, 44.9), ("vix_chg", "up"): (43, -1055.0, -109.1),
    ("event", "no"): (338, -836.6, 55.3), ("event", "yes"): (9, -3095.7, -682.9),
    ("expiry", "no"): (265, -760.9, 124.8), ("expiry", "yes"): (82, -1329.0, -348.8),
    ("dow", "Fri"): (69, -1243.6, -270.1), ("dow", "Mon"): (71, -131.9, 595.5), ("dow", "Sat"): (1, -4084.7, -151.9),
    ("dow", "Thu"): (69, -988.2, -72.1), ("dow", "Tue"): (71, -1251.5, -278.0), ("dow", "Wed"): (66, -823.2, 55.3),
}
DOW = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")


def _finite(x) -> bool:
    return x is not None and isinstance(x, (int, float)) and math.isfinite(x)


def danger_buckets(gap_pct, vix_prev, vix_change, event: bool, expiry: bool, day: date) -> dict:
    """DangerMeter.buckets: the bucket of each known input (an unknown input is left out, i.e. neutral)."""
    b = {}
    if _finite(gap_pct):
        a = abs(gap_pct)
        b["gap"] = "small" if a <= GAP_CUTS[0] else "mid" if a <= GAP_CUTS[1] else "large"
    if _finite(vix_prev) and vix_prev > 0:
        b["vix"] = "low" if vix_prev <= VIX_CUTS[0] else "mid" if vix_prev <= VIX_CUTS[1] else "high"
    if _finite(vix_change):
        b["vix_chg"] = "up" if vix_change > VIX_MOVE else "down" if vix_change < -VIX_MOVE else "flat"
    b["event"] = "yes" if event else "no"
    b["expiry"] = "yes" if expiry else "no"
    b["dow"] = DOW[day.weekday()]
    return b


def danger_read(gap_pct, vix_prev, vix_change, event: bool, expiry: bool, day: date) -> tuple:
    """(level, score): DangerMeter.read. level 'paper-only' / 'careful' / 'normal'."""
    b = danger_buckets(gap_pct, vix_prev, vix_change, event, expiry, day)
    score = sum(MODEL[(f, v)][2] for f, v in b.items() if (f, v) in MODEL)
    level = "paper-only" if score <= PAPER_ONLY_AT else "careful" if score <= CAREFUL_AT else "normal"
    return level, round(score, 1)


def is_flagged(level: str | None) -> bool:
    return level in ("careful", "paper-only")


# ------------------------------------------------------------------------------------------------ event days (app rules)
BUNDLED_2026 = (date(2026, 2, 1), date(2026, 2, 6), date(2026, 4, 8), date(2026, 6, 5), date(2026, 8, 6),
                date(2026, 10, 1))          # EventCalendar.BUNDLED: Union Budget and RBI policy days in 2026


def csv_events() -> set:
    p = K.ROOT / "research" / "brain" / "calendar_data" / "events.csv"
    out = set()
    with open(p, newline="") as f:
        for r in csv.DictReader(f):
            if r["kind"] in ("budget", "rbi_policy"):
                out.add(date.fromisoformat(r["date"]))
    return out


def rebalance_days(sessions: list) -> set:
    """EventCalendar.rules: the last trading day of Mar / Sep (NSE) and Feb / May / Aug / Nov (MSCI), from the sessions."""
    last = {}
    for d in sessions:
        k = (d.year, d.month)
        if k not in last or d > last[k]:
            last[k] = d
    return {d for (y, m), d in last.items() if m in (2, 3, 5, 8, 9, 11)
            and d.day > calendar.monthrange(y, m)[1] - 10}   # a month cut short by the data's end is not a month end


def event_days(sessions: list) -> set:
    return csv_events() | set(BUNDLED_2026) | rebalance_days(sessions)


def danger_inputs(sessions: list, bn_open: dict, bn_last: dict, vix_last: dict) -> dict:
    """{day: (gap %, VIX previous close, VIX change)} from BANKNIFTY's first open / last close and INDIA VIX's last
    close per session; only the previous sessions' closes and today's first open are read."""
    out = {}
    s = sorted(sessions)
    for i, d in enumerate(s):
        prev = s[i - 1] if i >= 1 else None
        prev2 = s[i - 2] if i >= 2 else None
        pc = bn_last.get(prev) if prev else None
        o = bn_open.get(d)
        gap = (o - pc) / pc * 100 if _finite(o) and _finite(pc) and pc > 0 and o > 0 else None
        vp = vix_last.get(prev) if prev else None
        vp2 = vix_last.get(prev2) if prev2 else None
        vchg = (vp - vp2) / vp2 if _finite(vp) and _finite(vp2) and vp2 > 0 else None
        out[d] = (gap, vp, vchg)
    return out
