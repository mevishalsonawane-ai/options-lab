"""R168 settings (PREREG.md): the app's own ORB arms (ORB, ORB Fresh, ORB Sweep) with only their exits replaced by R154's
24 scalp exits (10 / 12 / 15 points on the option premium or on the index).

    stores    R154's: R51's Amendment A clean stores from 20 Nov 2024, R154's early store before (r154data.sessions)
    trades    $R168_OUT (default ~/.cache/iraalgo/r168): priced trades (never in git)
    results   research/hunt/r168_orb_10_15/results (small JSON / markdown only)

Pure: arms, windows, periods, exit rules, published figures and gates. Importing it puts R154's folder (and through it
R152 / R112 / R106 / R101 / R51) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
_R154 = HERE.parent / "r154_scalp_10_15"
if str(_R154) not in sys.path:
    sys.path.insert(0, str(_R154))
import r154cfg as K154  # noqa: E402  (R154's paths, periods and the 24 exit rules)

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

ROOT = K154.ROOT
OUT = Path(os.environ.get("R168_OUT", K154.CACHE_ROOT / "r168")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = K154.UNDS                      # NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX
APP_UND = "BANKNIFTY"                 # the arms' own index (OrbRules.UNDERLYING)
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}

# ------------------------------------------------------------------------------------------------ the arms (PREREG 1)
# 5-minute bars k = 0 .. 74 (k = 0 is the bar labelled 09:15; bar k covers minute rows 5k .. 5k+4).
NBARS = 75
OR_BARS = 10                          # bars 09:15 .. 10:00 (OrbRules.OR_START .. OR_END)
STRIKE_BAR = 1                        # the 09:20 bar's close sets the ATM strike
MAX_MISSING_OR = 2                    # a day with more than 2 empty range bars is skipped (h20 / R14)
MIN_PREMIUM = 40.0                    # a real fill <= 40 is refused (the -40 stop has no level)
OWN_STOP = 40.0                       # OrbRules.STOP_POINTS

ARMS = ("ORB", "FRESH", "SWEEP")
ARM_NAME = {"ORB": "ORB", "FRESH": "ORB Fresh", "SWEEP": "ORB Sweep"}
# arm: (signal kind, first decision bar, last decision bar inclusive, cap a side a day, cap a day, own target points)
ARM_RULES = {
    "ORB": ("break", 10, 56, 1, 99, 40.0),        # 10:05 .. 13:55; R14's 1 entry a side (paper default since 13 Oct)
    "FRESH": ("fresh", 10, 56, 1, 99, 40.0),
    "SWEEP": ("sweep", 10, 62, 99, 2, 80.0),      # 10:05 .. 14:25; SweepRules.MAX_ENTRIES = 2
}
ARM_SIGNAL = {
    "ORB": "index 5-min bars: close beyond the 09:15-10:00 range (CE above, PE below)",
    "FRESH": "index 5-min bars: a FRESH close beyond the 09:15-10:00 range (the bar before not beyond the same edge)",
    "SWEEP": "index 5-min bars: a wick beyond the 09:15-10:00 range edge that closes back inside (PE at the high, CE at the low)",
}
OWN_SQ = K154.row(15, 10)             # B0's square-off (OrbRules.SQUARE_OFF)
OWN_SQ_EXPIRY = K154.row(15, 5)
SQ = K154.SQ                          # the scalps': 15:15 (Boss) ...
SQ_EXPIRY = K154.SQ_EXPIRY            # ... 15:05 on expiry days

# ------------------------------------------------------------------------------------------------ exits (PREREG 3)
RULES = K154.RULES
RULE_IDS = K154.RULE_IDS              # 24
READING = K154.READING
LOTS = (1, 2, 3)
TESTS = tuple((a, u, r) for a in ARMS for u in UNDS for r in RULE_IDS)      # 360

# ------------------------------------------------------------------------------------------------ periods (PREREG 5)
EARLY_START = K154.EARLY_START
ARM_START = K154.ARM_START
P1_END, P2_START, P2_END, P2_Q = K154.P1_END, K154.P2_START, K154.P2_END, K154.P2_Q
FINAL_START, FINAL_END = K154.FINAL_START, K154.FINAL_END
FIRST_OPTION_DAY = K154.FIRST_OPTION_DAY

# parity windows (PREREG 4)
PAR_PRE = (date(2021, 8, 4), date(2025, 9, 30))
PAR_HO = (date(2025, 10, 1), date(2026, 10, 5))
PUBLISHED = {  # (trades, net) at 1 lot; obuy Dhan data, app charges at today's rates, h20 'real' fills
    ("ORB", "pre"): (962, -96740), ("ORB", "ho"): (246, -18439),
    ("FRESH", "pre"): (962, -96740), ("FRESH", "ho"): (246, -18439),
    ("SWEEP", "pre"): (1330, -194184), ("SWEEP", "ho"): (331, -78962),
}
PAR_TRADES_TOL = 0.05
PAR_NET_TOL = 0.15

# ------------------------------------------------------------------------------------------------ gates (PREREG 7)
TWINS = 500
SEED = 168
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 2664 + 10 + 24              # R167's tally (R154-R167 included) + R14's 10 + H20's 24
M_TOTAL = N_PRIOR + len(TESTS)        # 3,058


def period_of(d: date) -> str | None:
    if EARLY_START <= d <= P1_END:
        return "P1"
    if P2_START <= d <= P2_END:
        return "P2"
    if FINAL_START <= d <= FINAL_END:
        return "P3"
    return None
