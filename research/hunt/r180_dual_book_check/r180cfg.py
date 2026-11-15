"""R180 settings (PREREG.md): a cross-check of an outside "build manual" (dual-book option buying), rebuilt as written.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, options_rolling_1m (WEEK); ends 19 Oct 2026
    stores    R51's Amendment A clean stores (R51_CLEAN=1, hunt/r51/r51data.py, unchanged code), built in their own folder
              (<r51 cache>/clean_r180) from 1 Nov 2024 (the manual's window start; R51's stores start 20 Nov) to 19 Oct 2026
    trades    $R180_OUT (default ~/.cache/iraalgo/r180): priced trades (never in git)
    results   research/hunt/r180_dual_book_check/results (small JSON / markdown only)

`use_stores()` points R51's config at the R180 stores (the study and the builder call it first). Pure otherwise:
periods, the manual's parameters, gates. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

os.environ["R51_CLEAN"] = "1"
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for _p in (str(HERE.parent / "r51"), str(HERE.parent / "r71"), str(HERE.parent / "r72"), str(HERE)):
    if _p not in sys.path:
        sys.path.insert(0, _p)
import r51cfg as K51  # noqa: E402

# ------------------------------------------------------------------------------------------------ window (the manual's)
START = date(2024, 11, 1)            # manual: evaluation 1 Nov 2024 - 19 Oct 2026 (the lake's first Nov session is 4 Nov)
DESIGN_END = date(2026, 3, 31)       # manual: design Nov 2024 - Mar 2026
HOLD_START = date(2026, 4, 1)        # manual: holdout Apr - Oct 2026 (read by many earlier studies: not clean)
END = date(2026, 10, 19)             # the lake's last session; nothing after it exists (no fresh stretch)


def use_stores():
    """Point R51's store code at R180's window and folder (call before building or loading; idempotent). Kept out of
    import time so importing this module (tests) never changes r51cfg for anything else in the same process."""
    K51.START = START                            # R51's store builder reads options from START ...
    K51.END = END                                # ... to END,
    if K51.OUT.name != "clean_r180":
        K51.OUT = K51.OUT.parent / "clean_r180"  # ... in R180's own folder (R51's / R106's stores stay untouched)


CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R180_OUT", CACHE_ROOT / "r180")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
B2_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")       # manual: Book 2 without MIDCPNIFTY
BSE = {"SENSEX"}
TICK = 0.05


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


# ------------------------------------------------------------------------------------------------ the manual's numbers
R0920 = row(9, 20)                   # 5: the 09:20 minute (its close), the straddle base
# Book 1 "R72-X1 Breakout"
B1_M = 4                             # >= 4 of 5 indices break the same side on the same 5-min bar
B1_NCH = 20                          # Donchian 20 on the PRIOR 20 five-minute bars
B1_WARM = 60                         # bar index >= 60 of the continuous series
B1_ATR_SPAN = 14                     # ATR14 = EWM(span 14) of the true range (manual Step 1.2)
B1_STR = 0.50                        # |C - edge| / ATR14 >= 0.50
B1_FIRST, B1_LAST = row(9, 30), row(14, 45)   # entry minute em = 15 .. 330
B1_STOP = 0.30                       # -30% of the entry price
B1_LOCK_TRIG = 0.20                  # once peak >= +20% ...
B1_LOCK_KEEP = 0.50                  # ... exit when the low gives back to entry + 50% of the peak gain
B1_HOLD = 60                         # out at em + 60 ...
B1_EOD = row(15, 20)                 # ... or 15:20 (row 365)
CAP = 56000.0                        # premium x qty <= Rs 56,000
# Book 2 "R132 S3-V1-E3 Order-Block Pullback" (the manual's definition, NOT the repo's R132 S3-V1-E3)
B2_K = 2                             # fractal swings k = 2
B2_DISP = 1.5                        # body > 1.5 x the 20-bar median body
B2_MED_N = 20
B2_OB_BACK = 5                       # the last opposite-colour candle within 5 bars before the displacement bar
B2_OB_LIFE = 12                      # the zone is valid for 12 five-minute bars
B2_FIRST, B2_LAST = row(12, 15), row(14, 45)  # 1-min entry 180 .. 330
B2_STRAD = -0.05                     # straddle_vs_0920 < -0.05
B2_ATR_K = 0.10                      # index stop = |ref - far edge| + 0.10 x ATR14_5m
B2_LOCK_TRIG = 1000.0                # after peak >= +Rs 1,000 ...
B2_LOCK_KEEP = 0.60                  # ... exit when current <= 60% of peak
B2_EOD = row(15, 19)                 # else out at 15:19 (row 364)
EXPIRY_EOD = row(15, 5)              # 15:05 on expiry days (CLAUDE.md; the manual's guard table says the same)
# combined account
MONTH_PAUSE = -11000.0               # pause for the rest of the month once the month's closed net <= -Rs 11,000

# ------------------------------------------------------------------------------------------------ manual's claims
CLAIMS = {
    "B1": dict(all=(361, 67.9, 78504), all2x=75470, design=(264, 68.6, 53688), hold=(97, 66.0, 24816)),
    "B2": dict(all=(271, 59.8, 43924), all2x=38587, design=(206, 59.2, 29336), hold=(65, 61.5, 14589)),
    "COMB": dict(all=(440, 63.9, 81447), all2x=74730, design=(326, 64.7, 61887), hold=(114, 61.4, 19561)),
    "COMB_NOSTOP": dict(all=(458, 62.7, 74907), all2x=67939, design=(344, 63.1, 55347), hold=(114, 61.4, 19561)),
}
CLAIMS_IDX = {     # manual table 4.1: (all N, win %, net 1x, net 2x, design N, design win %, design net, hold N, hold win %, hold net)
    ("B1", "BANKNIFTY"): (75, 72.0, 14930, 14600, 49, 79.6, 20444, 26, 57.7, -5514),
    ("B1", "FINNIFTY"): (64, 65.6, 10602, 9927, 50, 62.0, 3570, 14, 78.6, 7032),
    ("B1", "MIDCPNIFTY"): (63, 82.5, 36258, 35120, 44, 84.1, 17902, 19, 78.9, 18356),
    ("B1", "NIFTY"): (73, 63.0, 16186, 15512, 56, 64.3, 13137, 17, 58.8, 3049),
    ("B1", "SENSEX"): (86, 59.3, 528, 311, 65, 58.5, -1365, 21, 61.9, 1893),
    ("B2", "BANKNIFTY"): (56, 64.3, 5408, 3844, 36, 61.1, -3438, 20, 70.0, 8846),
    ("B2", "FINNIFTY"): (41, 53.7, 309, -738, 34, 58.8, 4817, 7, 28.6, -4509),
    ("B2", "NIFTY"): (165, 57.6, 12539, 9028, 130, 55.4, 7362, 35, 65.7, 5177),
    ("B2", "SENSEX"): (163, 55.8, 26655, 24384, 127, 55.1, 13294, 36, 58.3, 13360),
}

# ------------------------------------------------------------------------------------------------ gates (PREREG section 5)
TWINS = 500
SEED = 180
N_PRIOR = 4044                       # every earlier test counted by the last study (the caller's count, 15 Nov 2026)
TESTS = ("B1", "B2", "COMB")         # the three gated hypotheses
SENS = ("B1_open", "B2_open", "COMB_open", "COMB_NOSTOP")   # sensitivity runs, also counted in BH
M_TOTAL = N_PRIOR + len(TESTS) + len(SENS)                  # 4,051
GATE_Q = 0.10
GATE_P = 0.05
MIN_HOLD = 40
ACCT_DD = -40000.0                   # CLAUDE.md account stop rule: -Rs 40k drawdown ...
ACCT_MONTH = -13000.0                # ... or -Rs 13k in a month


def lot(und: str, d: date) -> int:
    return K51.lot(und, d)


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if HOLD_START <= d <= END:
        return "hold"
    return None
