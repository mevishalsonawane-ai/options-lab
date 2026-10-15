"""R62 settings (PREREG.md): anchored volume profile + Smart-Money-Concepts entries, Nov 2024 onwards only.

    lake (index)  $R62_LAKE_FLAT (default ~/.cache/iraalgo/lake_flat): index_1m_<U>_<Y> and opt_<U>_WEEK_<Y> files, flat,
                  as R51 reads them
    lake (stock)  $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): equity_1m/equity_1m_202[456].parquet
    cache         $R62_OUT (default ~/.cache/iraalgo/r62): day stores, minute grids, trade dumps (never in git)
    results       research/hunt/r62/results (small JSON / markdown only)

R51's modules (store, engine) are used unchanged; R51's settings are pointed at R62's cache and lake here, BEFORE any R51
module that reads them at import time is imported. R51_CLEAN=1 (Amendment A cleaning) is always on for R62.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R51 = HERE.parent / "r51"
R58 = HERE.parent / "r58"
for p in (ROOT, ROOT / "research", R51, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R62_OUT", CACHE_ROOT / "r62")).expanduser()
LAKE_FLAT = Path(os.environ.get("R62_LAKE_FLAT", CACHE_ROOT / "lake_flat")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ window and split
WARM_START = date(2024, 9, 1)            # structure warm-up only, never a trade
START = date(2024, 11, 20)               # nothing traded before this (Boss, 14-15 Oct)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 14)                 # upper bound; the lake's last session decides (13 Oct indices, 12 Oct stocks)
QUARTERS = ("2025Q1", "2025Q2", "2025Q3", "2025Q4", "2026Q1")   # 2025Q1 includes 20 Nov - 31 Dec 2024

IDX = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STOCKS = ("RELIANCE", "SBIN", "MARUTI", "ICICIBANK", "INFY", "LT", "KOTAKBANK", "M&M", "TRENT", "TATASTEEL")   # R58
NIFTY50 = ("ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK", "BAJAJ-AUTO", "BAJAJFINSV", "BAJFINANCE",
           "BEL", "BHARTIARTL", "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "ETERNAL", "GRASIM", "HCLTECH", "HDFCBANK",
           "HDFCLIFE", "HINDALCO", "HINDUNILVR", "ICICIBANK", "INDIGO", "INDUSINDBK", "INFY", "ITC", "JIOFIN", "JSWSTEEL",
           "KOTAKBANK", "LT", "M&M", "MARUTI", "MAXHEALTH", "NESTLEIND", "NTPC", "ONGC", "POWERGRID", "RELIANCE",
           "SBILIFE", "SBIN", "SHRIRAMFIN", "SUNPHARMA", "TATACONSUM", "TATASTEEL", "TCS", "TECHM", "TITAN", "TMPV",
           "TRENT", "ULTRACEMCO")
BANKS = ("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BANKBARODA", "FEDERALBNK", "IDFCFIRSTB",
         "PNB", "AUBANK", "CANBK")
CONSTITUENTS = {"NIFTY": NIFTY50, "BANKNIFTY": BANKS}
EXDATES = {"KOTAKBANK": (date(2026, 1, 14),), "TRENT": (date(2026, 6, 4),)}   # R58: no swing held across these

# ------------------------------------------------------------------------------------------------ grid and rules
NROWS = 375                              # rows 0..374 = 09:15..15:29
MIN_BARS = 300                           # a session needs >= 300 one-minute bars


def row(hh: int, mm: int) -> int:
    return (hh - 9) * 60 + mm - 15


TF_EXEC, TF_STRUCT, TF_HTF = 5, 15, 60
FRACTAL_N = 2
PROFILE_ROWS = 50
VALUE_AREA = 0.70
PROFILE_MIN_COVER = 0.80                 # volume on >= 80% of the leg's minutes
LVN_FRAC = 0.20                          # LVN: row volume < 20% of the POC row's
LVN_EDGE = 5                             # only rows 5..44 can be LVNs
OB_LOOKBACK = 10                         # the OB candle is among the 10 five-minute bars before the internal break
STOP_BUFFER = 0.0002                     # "just beyond" the swing: 0.02% of price
SIG_FIRST = row(9, 24)                   # signal bar closes 09:24 .. 14:29 (fill 09:25 .. 14:30)
SIG_LAST = row(14, 29)
SQ_IDX = row(15, 15)                     # index options (15:05 on expiry days, R51 sq_row)
SQ_STK = row(15, 10)                     # stocks (the lake's Aug-Oct 2026 stock sessions end 15:14)
SWING_HOLD = 5                           # STK-sw: sessions held at most (the entry session counts)
ITM_MONEY = 1                            # index options: 1-ITM (CLAUDE.md default)

ZONES = ("poc", "lvn")
VARIANTS = tuple(f"{z}-{c}-{h}-{t}" for z in ZONES for c in ("x", "c") for h in ("x", "h") for t in ("2r", "st"))
PRIMARY = ("IDX", "STK")
SECONDARY = ("IDX-b", "IDX-c", "STK-c", "STK-sw", "STK-opt")

TWINS = 500
SEED = 62
GATE_P, GATE_Q, GATE_MIN_TRADES = 0.05, 0.10, 40
RISK_CAPITAL, RISK_FRAC, MIS_LEVERAGE, ASSUMED_DELTA = 100_000, 0.01, 5.0, 0.6


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None


def quarter(d: date) -> str:
    if d < date(2025, 1, 1):
        return "2025Q1"
    return f"{d.year}Q{(d.month - 1) // 3 + 1}"


def parse_variant(v: str) -> dict:
    z, c, h, t = v.split("-")
    return dict(zone=z, conf=c == "c", htf=h == "h", target=t)


# ------------------------------------------------------------------------------------------------ the period guard
_FINAL_OPEN = False


def open_final():
    global _FINAL_OPEN
    _FINAL_OPEN = True


def final_open() -> bool:
    return _FINAL_OPEN


def check_range(lo: date, hi: date):
    if lo < START:
        raise PermissionError(f"R62 trades nothing before {START} (asked for {lo})")
    if hi > DESIGN_END and not _FINAL_OPEN:
        raise PermissionError(f"R62: {hi} is in the final test (from {FINAL_START}); read only by `r62study.py final`")


# ------------------------------------------------------------------------------------------------ point R51 at R62
def point_r51():
    """R51's store and engine, on R62's flat lake and cache, with Amendment A cleaning. Call before importing r51data /
    engine."""
    os.environ["R51_CLEAN"] = "1"
    os.environ.setdefault("IRAALGO_LAKE", str(LAKE))
    import r51cfg as K51
    K51.LAKE = LAKE_FLAT
    K51.CLEAN = True
    K51.OUT = OUT / "idx"
    K51.END = END
    return K51
