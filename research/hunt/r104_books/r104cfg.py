"""R104 settings (PREREG.md): paths, periods and folds per market, lots, futures / crypto / stock charges, gates.

    lake     $R104_LAKE (default /root/lake): the Drive lake copy (index_1m, index_daily, fut_*, equity_daily, crypto/)
    stores   $R104_STORE (default /root/cache/r104/store): R103's builder output, day_<UND>.pkl (never in git)
    cache    $R104_OUT (default /root/cache/r104): trade lists (never in git)
    results  research/hunt/r104_books/results (small JSON / markdown only)

Pure: numbers and small helpers only.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE.parent / "r51", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

LAKE = Path(os.environ.get("R104_LAKE", "/root/lake")).expanduser()
STORE = Path(os.environ.get("R104_STORE", "/root/cache/r104/store")).expanduser()
OUT = Path(os.environ.get("R104_OUT", "/root/cache/r104")).expanduser()
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
SEED = 104

# ------------------------------------------------------------------------------------------------ periods (PREREG 6)
D = date
PERIODS = {
    "IDX": dict(design=(D(2021, 8, 2), D(2026, 3, 31)), holdout=(D(2026, 4, 1), D(2026, 10, 19)),
                folds=((D(2021, 8, 2), D(2022, 12, 31)), (D(2023, 1, 1), D(2023, 12, 31)),
                       (D(2024, 1, 1), D(2024, 12, 31)), (D(2025, 1, 1), D(2026, 3, 31)))),
    "DLY": dict(design=(D(2006, 1, 1), D(2021, 12, 31)), holdout=(D(2022, 1, 1), D(2026, 10, 19)),
                folds=((D(2006, 1, 1), D(2009, 12, 31)), (D(2010, 1, 1), D(2013, 12, 31)),
                       (D(2014, 1, 1), D(2017, 12, 31)), (D(2018, 1, 1), D(2021, 12, 31)))),
    "MCXD": dict(design=(D(2012, 1, 1), D(2021, 12, 31)), holdout=(D(2022, 1, 1), D(2026, 10, 16)),
                 folds=((D(2012, 1, 1), D(2014, 12, 31)), (D(2015, 1, 1), D(2017, 12, 31)),
                        (D(2018, 1, 1), D(2021, 12, 31)))),
    "MCXI": dict(design=(D(2025, 10, 20), D(2026, 7, 31)), holdout=(D(2026, 8, 1), D(2026, 10, 19)),   # Amendment 0
                 folds=((D(2025, 10, 20), D(2026, 3, 31)), (D(2026, 4, 1), D(2026, 5, 31)),
                        (D(2026, 6, 1), D(2026, 7, 31)))),
    "CRY": dict(design=(D(2023, 10, 1), D(2026, 3, 31)), holdout=(D(2026, 4, 1), D(2026, 12, 31)),
                folds=((D(2023, 10, 1), D(2024, 6, 30)), (D(2024, 7, 1), D(2025, 3, 31)),
                       (D(2025, 4, 1), D(2026, 3, 31)))),
    "ARM": dict(design=(D(2024, 11, 20), D(2026, 3, 31)), holdout=(D(2026, 4, 1), D(2026, 10, 13)),
                folds=((D(2025, 4, 1), D(2025, 6, 30)), (D(2025, 7, 1), D(2025, 9, 30)),
                       (D(2025, 10, 1), D(2025, 12, 31)), (D(2026, 1, 1), D(2026, 3, 31)))),
}
PERIODS["VOL"] = PERIODS["IDX"]
PERIODS["STK"] = PERIODS["DLY"]
PERIODS["MM"] = PERIODS["ARM"]
PERIODS["IDXF"] = dict(PERIODS["IDX"], folds=((D(2017, 1, 1), D(2021, 7, 31)),) + PERIODS["IDX"]["folds"],
                       design=(D(2017, 1, 1), D(2026, 3, 31)))

MIN_TRADES = {"IDX": (100, 20), "IDXF": (100, 20), "MCXI": (40, 20), "CRY": (60, 20), "VOL": (60, 20),
              "DLY": (30, 10), "MCXD": (30, 10), "STK": (30, 10), "ARM": (0, 40), "MM": (0, 0)}
GATE_Q = 0.10
GATE_TWIN_P = 0.05
N_TWINS = 500
N_BOOT = 10_000


def period_of(market: str, d: date) -> str | None:
    P = PERIODS[market]
    if P["design"][0] <= d <= P["design"][1]:
        return "design"
    if P["holdout"][0] <= d <= P["holdout"][1]:
        return "holdout"
    return None


def fold_of(market: str, d: date) -> int:
    for i, (a, b) in enumerate(PERIODS[market]["folds"]):
        if a <= d <= b:
            return i
    return -1


# ------------------------------------------------------------------------------------------------ index facts
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
LOTS = {   # R51's table (dated); before 2021 the NIFTY 75 / BANKNIFTY 25 rows stand in for the older lots (labelled)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


NSE_OPEN = 9 * 60 + 15
NROWS = 375
SQ_ROW = 15 * 60 + 10 - NSE_OPEN
SQ_ROW_EXPIRY = 15 * 60 + 5 - NSE_OPEN
CARRY = 0.05              # index future carry, a year of notional (r - q)


# ------------------------------------------------------------------------------------------------ futures charges
_C94 = None


def mcx_costs():
    """R94's MCX cost module (research/hunt/r94_mcx_advanced/common.py), loaded under its own name so it never shadows
    R51's `common`."""
    global _C94
    if _C94 is None:
        import importlib.util
        spec = importlib.util.spec_from_file_location("r94_common", HERE.parent / "r94_mcx_advanced" / "common.py")
        _C94 = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(_C94)
    return _C94


def _dated(d: date, table):
    for until, r in table:
        if d < until:
            return r
    return table[-1][1]


_FUT_STT = ((D(2023, 4, 1), 0.0001), (D(2024, 10, 1), 0.000125), (D(2026, 4, 1), 0.0002), (D(9999, 1, 1), 0.0005))
_FUT_EXCH = ((D(2024, 10, 1), 0.000019), (D(9999, 1, 1), 0.0000173))


def index_fut_charges(buy_rs: float, sell_rs: float, d: date) -> float:
    """Zerodha-style NSE index futures charges for one round trip of one leg, rates in force on d (PREREG 5)."""
    brok = 40.0
    stt = _dated(d, _FUT_STT) * sell_rs
    txn = _dated(d, _FUT_EXCH) * (buy_rs + sell_rs)
    sebi = 1e-6 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs
    return brok + stt + txn + sebi + stamp + 0.18 * (brok + txn + sebi)


def index_half_spread(price: float) -> float:
    return max(0.05, 0.00005 * price)


def crypto_charges(notional_rs: float) -> float:
    """Delta Exchange India taker fee 0.05% + 18% GST, each side, round trip."""
    return 2 * notional_rs * 0.0005 * 1.18


CRY_NOTIONAL = 100_000.0
CRY_HALF = 0.0001
STK_NOTIONAL = 500_000.0
STK_HALF = 0.0002
