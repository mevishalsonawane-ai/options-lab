"""R47 settings, fixed in PREREG.md (15 Oct 2026) before any P&L: relaxed index-option BUYING search.

    lake files   $IRAALGO_LAKE (default <cache>/lake): index_1m_<UND>_<YEAR>.parquet, index_daily.parquet,
                 opt_<UND>_<WEEK|MONTH>_<YEAR>.parquet (rclone copy gdrive:IraAlgo-data-lake/{index_1m,index_daily,
                 options_rolling_1m} ...); never committed
    caches       R47_OUT (default <cache>/r47): per-underlying contract stores (big: never in git)
    results      research/hunt/r47/results (small JSON / markdown only)

Boss (15 Oct): "for index option buying, you can relax some rules, then try". Relaxed: the DESIGN of the strategies
(signal source, holding period, strike / expiry / debit spreads / straddle buying, trade count, search breadth,
calendar and regime filters). Kept: the MEASUREMENT (one fixed contract per leg from entry to exit, R17 'liq' fills,
dated charges, next-minute fills, gap fills at the worse of level and open, no look-ahead, one locked-holdout read).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402
from research.obuy.config import LOT_SCHEDULE  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R47_OUT", CACHE.root / "r47")).expanduser()
RESULTS = HERE / "results"
CANDIDATES = RESULTS / "candidates.json"         # frozen and committed BEFORE the holdout read
HOLDOUT_JSON = RESULTS / "holdout.json"          # written once; its existence refuses a second read

# ------------------------------------------------------------------------------------------------ split (PREREG)
FIRST_DAY = date(2021, 8, 1)                     # the lake's index options start Aug 2021 (SENSEX 2023)
DESIGN_END = date(2025, 10, 31)
EMBARGO_END = date(2025, 11, 9)                  # 1-9 Nov 2025: used by neither (a 5-day hold cannot reach the holdout)
HOLDOUT_START = date(2025, 11, 10)               # to the lake's last session (13 Oct 2026), ~11 months
BLOCKS = (                                       # walk-forward blocks of the design period
    ("B1", date(2021, 8, 1), date(2022, 12, 31)),
    ("B2", date(2023, 1, 1), date(2023, 12, 31)),
    ("B3", date(2024, 1, 1), date(2024, 12, 31)),
    ("B4", date(2025, 1, 1), DESIGN_END),
)

# ------------------------------------------------------------------------------------------------ universe
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")   # MIDCPNIFTY left out: its expiry dates cannot be recovered
SERIES = {"NIFTY": ("WEEK", "MONTH"), "BANKNIFTY": ("WEEK",), "FINNIFTY": ("WEEK",), "SENSEX": ("WEEK", "MONTH")}
# BANKNIFTY / FINNIFTY WEEK and MONTH files are the same contract from Nov 2024: only WEEK ("near") is read.
BSE = {"SENSEX"}
N = 375                                          # minutes 09:15 .. 15:29; minute m starts at 09:15 + m


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def mi(hh: int, mm: int) -> int:
    """Minute index of the clock time hh:mm (09:15 -> 0)."""
    return hh * 60 + mm - (9 * 60 + 15)


SQ = mi(15, 15)                                  # intraday square-off: market sell at the 15:15 minute's open
EXPIRY_SQ = mi(15, 5)                            # expiry day square-off (CLAUDE.md 15:05)
POS_DECIDE = mi(15, 20)                          # positional entries: decided on the 15:20 minute's close
MORNING_EXIT = mi(9, 16)                         # overnight exits: the 09:16 minute's open (not the auction minute)
FILL_WAIT = 3                                    # an entry fills at the first row within 3 minutes after the decision
EDGE = 9                                         # a held contract that reaches ATM+-9 is sold (the lake keeps +-10)

# ------------------------------------------------------------------------------------------------ money
BUDGET = 20_000.0                                # premium budget per position (Rs)
LOT_CAP = 20                                     # never more than 20 lots
VOL_CAP = 0.10                                   # nor more than 10% of the contract's lots traded in the 5 minutes before
TICK = 0.05
BAD_PRINT = 0.20                                 # amendment 1: stray-print guard on fills (r47price)

# ------------------------------------------------------------------------------------------------ instruments
# (name, series, legs) with legs = ((strike offset in moneyness steps, +1 OTM / -1 ITM; sign +1 buy / -1 sell), ...)
# "kind": dir = one bought option in the signal's direction; spread = debit spread; vol = straddle / strangle (no
# direction). Month-series instruments exist only for NIFTY and SENSEX.
INSTR = {
    "itm3": ("WEEK", ((-3, 1),), "dir"),
    "itm1": ("WEEK", ((-1, 1),), "dir"),
    "atm": ("WEEK", ((0, 1),), "dir"),
    "otm2": ("WEEK", ((2, 1),), "dir"),
    "otm5": ("WEEK", ((5, 1),), "dir"),
    "m_atm": ("MONTH", ((0, 1),), "dir"),
    "m_itm2": ("MONTH", ((-2, 1),), "dir"),
    "sp_atm_otm3": ("WEEK", ((0, 1), (3, -1)), "spread"),
    "sp_itm2_otm2": ("WEEK", ((-2, 1), (2, -1)), "spread"),
    "straddle": ("WEEK", ((0, 1),), "vol"),          # ATM call + ATM put
    "strangle2": ("WEEK", ((2, 1),), "vol"),         # OTM2 call + OTM2 put
    "m_straddle": ("MONTH", ((0, 1),), "vol"),
}
DIR_INSTR = ("itm3", "itm1", "atm", "otm2", "otm5", "m_atm", "m_itm2", "sp_atm_otm3", "sp_itm2_otm2")
VOL_INSTR = ("straddle", "strangle2", "m_straddle")

# ------------------------------------------------------------------------------------------------ exits
# (stop, target, minutes or 'sq', lock) on the POSITION value (premium paid; spreads / straddles: their net value).
# stop / target are fractions of the entry value (0 = none). lock = (arm, keep): once the value has reached
# entry x (1 + arm), exit if it falls back to entry x (1 + keep).
XI = [(s, t, h, None) for s in (0.25, 0.40, 0.0) for t in (0.5, 1.0, 0.0) for h in (60, "sq")] + [
    (0.30, 0.0, "sq", (0.30, 0.10)), (0.30, 0.0, "sq", (0.60, 0.30))]
# positional: hold = sessions after the entry day; 'am' = the next session's 09:16 open, else 15:15 of day d+hold
XP = [(s, t, h, None) for s in (0.0, 0.5) for t in (0.0, 1.0) for h in ("am", 1, 3, 5)]

# ------------------------------------------------------------------------------------------------ signal families
# label: U = underlying (the CLAUDE.md default), C = option-chain signal, O = option's own price chart (NON-DEFAULT:
# only Boss could switch such a rule on), E = calendar / event timing (underlying direction or no direction),
# M = model on underlying + option-chain features. 'mode' intraday -> XI exits, positional -> XP exits.
FAMILIES = {
    "U1_orb": dict(label="U", mode="intraday", params=[dict(or_min=m) for m in (15, 30, 60)]),
    "U2_gap": dict(label="U", mode="intraday", params=[dict(th=t, how=h) for t in (0.3, 0.6, 1.0) for h in ("follow", "fade")]),
    "U3_lateday": dict(label="U", mode="positional", params=[dict(th=t, how=h) for t in (0.5, 1.0) for h in ("follow", "fade")]),
    "U4_donchian": dict(label="U", mode="positional", params=[dict(n=n) for n in (10, 20, 55)]),
    "U5_daily_mr": dict(label="U", mode="positional", params=[dict(kind=k) for k in ("rsi2", "streak3")]),
    "U6_ema": dict(label="U", mode="intraday", params=[dict(f=f, s=s, vwap=v) for f, s in ((9, 21), (5, 20)) for v in (False, True)]),
    "U7_bigbar": dict(label="U", mode="intraday", params=[dict(k=k, how=h) for k in (2.0, 3.0) for h in ("follow", "fade")]),
    "U8_pdhl": dict(label="U", mode="intraday", params=[dict(how=h) for h in ("break", "fail")]),
    "U9_vix": dict(label="U", mode="positional", params=[dict(th=t) for t in (0.10, 0.15)]),
    "U10_trendday": dict(label="U", mode="intraday", params=[dict(th=x, at=a) for x in (0.4, 0.8) for a in ((13, 0), (14, 0))]),
    "C1_pcr": dict(label="C", mode="intraday", params=[dict(at=a, how=h) for a in ((10, 15), (11, 30)) for h in ("follow", "fade")]),
    "C2_ivcheap": dict(label="C", mode="vol", params=[dict(pct=p, when=w) for p in (0.10, 0.20) for w in ("pos", "day")]),
    "C3_oishift": dict(label="C", mode="intraday", params=[dict(how=h) for h in ("follow", "fade")]),
    "O1_prem_orb": dict(label="O", mode="intraday", params=[dict(or_min=m) for m in (15, 30)]),
    "O2_prem_mom": dict(label="O", mode="intraday", params=[dict(x=x) for x in (0.20, 0.40)]),
    "E1_event_vol": dict(label="E", mode="event", params=[dict(when=w) for w in ("eve", "day")]),
    "E2_expiry_vol": dict(label="E", mode="expiry", params=[dict(at=a) for a in ((9, 20), (11, 0), (13, 0))]),
    "E3_expiry_hero": dict(label="E", mode="intraday_expiry", params=[dict(at=a, th=t) for a in ((13, 30), (14, 15)) for t in (0.3, 0.6)]),
    "M1_gbm": dict(label="M", mode="intraday", params=[dict(th=t) for t in (0.55, 0.60)]),
}
SIGNAL_SOURCE = {
    "U": "underlying (index spot) - the CLAUDE.md default",
    "C": "option-chain signal (OI / PCR / IV of the lake's ATM+-10 chain) - relaxed item 1",
    "O": "non-default: option-chart signal (the option's own premium bars) - only Boss could switch it on",
    "E": "calendar / event timing (expiry day, RBI, budget); any direction from the underlying",
    "M": "gradient-boosted trees on underlying + option-chain features (ML ranker, relaxed item 5)",
}
# Event exits (E1, E2): (stop, target, exit) on the straddle value; exit 'am' / 'sq' / 'exp' (15:05 expiry square-off)
XE1 = [(0.0, 0.0, "am", None), (0.0, 0.0, "sq1", None), (0.4, 0.5, "sq1", None), (0.0, 0.0, "sq", None)]
XE2 = [(s, t, "exp", None) for s in (0.0, 0.4) for t in (0.0, 0.5, 1.0)]

# ------------------------------------------------------------------------------------------------ filters (regime /
# calendar, relaxed item 6). Each is known before the session: previous VIX close, weekday, sessions to the near expiry,
# the last 3 sessions of a month, an event day (+-1 session).
FILTERS = ("all", "vix_lo", "vix_mid", "vix_hi", "dow0", "dow1", "dow2", "dow3", "dow4", "dte0", "dte1", "dte2p",
           "monthend", "event")
VIX_CUTS = (14.0, 20.0)

# ------------------------------------------------------------------------------------------------ selection (PREREG)
WF_MIN_TRAIN = 40          # a variant needs 40+ training trades and a positive training net to be picked
WF_MIN_OOS = 100           # a family needs 100+ walk-forward out-of-sample trades ...
WF_GREEN_BLOCKS = 2        # ... positive OOS net, and 2 of its 3 test blocks positive
CAND_MIN_TRADES = 100      # the family's candidate: 100+ design trades, net > 0, net without its 5 best trades > 0,
MAX_CANDIDATES = 10        # positive in all but at most one design block; top 10 by walk-forward t, 1 per (family, und)

# ------------------------------------------------------------------------------------------------ holdout gates
N_TWINS = 500
SEED = 47
BH_Q = 0.10
GATE_P = 0.05
GATE_MIN_TRADES = 40
