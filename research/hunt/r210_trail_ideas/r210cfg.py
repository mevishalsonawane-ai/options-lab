"""R210 settings (PREREG.md): three ideas from a third-party prompt-only skill pack, tested on the live arms R72, R51 and
Liquidity 15+5 against their CURRENT exits (V5), on R193's span, stores and engine. Plain-English notes, 24 Nov 2026.

    caches    ~/.cache/iraalgo/r210 (never git); R193's store links and caches are reused read-only
    results   research/hunt/r210_trail_ideas/results (small JSON / markdown only)

Signal source of every rule: the index (the arms' own breaks; the trails, the Chandelier and the higher-timeframe filter
read index bars), except the H-P variants, which read the option's own 5-minute premium bars and are labelled as an
option-chart rule (CLAUDE.md: not the default, reported separately). Options are only bought.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
R193 = HUNT / "r193_arms_nov24"
R186 = HUNT / "r186_fixed_profit"
for p in (str(HERE.parents[2]), str(HERE.parents[1]), str(R193), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r193cfg as K3  # noqa: E402  (R193's environment, store window and picker thresholds)

OUT = Path(os.environ.get("R210_OUT", Path.home() / ".cache" / "iraalgo" / "r210")).expanduser()
RESULTS = HERE / "results"
STORE = K3.STORE
PICK_THR = K3.PICK_THR

# ------------------------------------------------------------------------------------------------ windows (PREREG 2)
DESIGN = (date(2024, 11, 1), date(2026, 3, 31))          # R193's W1
HOLDOUT = (date(2026, 4, 1), date(2026, 11, 13))         # R193's W2: locked for every R210 variant, read once
FOLDS = {"F1": (date(2024, 11, 1), date(2025, 2, 28)),
         "F2": (date(2025, 3, 1), date(2025, 6, 30)),
         "F3": (date(2025, 7, 1), date(2025, 10, 31)),
         "F4": (date(2025, 11, 1), date(2026, 3, 31))}
MIN_FOLDS = 3                                            # must beat V5 in at least 3 of the 4 folds

# ------------------------------------------------------------------------------------------------ the current exits (V5)
ARMS = ("R72", "R51", "LIQ")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5"}
V5_LOCK = {"R72": "L500", "R51": "L500", "LIQ": "LBE"}   # R186's lock_levels mode of today's rule
V5_BE = 0.10                                             # V4's breakeven at +10%
PICKER = {"R72": True, "R51": True, "LIQ": False}        # R151's picker stays on R72 / R51 in every row

# ------------------------------------------------------------------------------------------------ rule parameters (fixed)
R_FROM_OWN_STOP = True       # 1R = the arm's own -15% premium stop (Exits.stop), 0.15 x the raw fill, for all three
ATR_N = 14                   # ATR(14) = simple mean of the last 14 true ranges of 5-minute bars
ATR_MIN_PREMIUM = 5          # the premium ATR (H-P) needs at least 5 of the session's own bars, else not active yet
HYB_ATR_K = 1.5              # hybrid stage 2: highest 5-min close since entry - 1.5 x ATR(14)
SWING_K = 2                  # a swing low / high: lower / higher than the 2 bars before and the 2 bars after it
CHAND_K = 3.0                # Chandelier: highest 5-min high since entry - 3 x ATR(14) (index)
EMA_N = 20                   # higher-timeframe trend: the last completed bar's close vs EMA(20) of those bars' closes
TF_MINUTES = {"F15": 15, "F60": 60}

# ------------------------------------------------------------------------------------------------ the 18 tests (PREREG 3)
# id -> (arm, kind); kind in HU (hybrid on the index), HUNT (HU without the arm's time stop), HP (hybrid on the premium,
# option-chart rule), CH (V5 + Chandelier), F15 / F60 (V5 + higher-timeframe filter)
KINDS = ("HU", "HUNT", "HP", "CH", "F15", "F60")
TESTS = {f"{a}-{k}": (a, k) for a in ARMS for k in KINDS}
assert len(TESTS) == 18
LABEL = {
    "V5": "current exits (V5; Liquidity: breakeven at +10% / +Rs 1,000)",
    "HU": "hybrid trail on the index (breakeven at +1R, ATR trail at +2R, structure trail at +3R)",
    "HUNT": "hybrid trail on the index, without the arm's time stop",
    "HP": "hybrid trail on the option premium (option-chart rule, labelled)",
    "CH": "current exits + Chandelier exit on the index (3 x ATR(14), 5-min)",
    "F15": "current exits + 15-minute trend filter (close vs EMA(20))",
    "F60": "current exits + 60-minute trend filter (close vs EMA(20))",
}
OPTION_CHART = {"HP"}        # rules that read the option's own chart (reported separately)

# ------------------------------------------------------------------------------------------------ the gate (PREREG 6)
SEED = 210
FLIPS = 10000                # paired daily sign-flip test
GATE_Q = 0.10
MIN_TRADES = 40
N_PRIOR = 4808               # entry files at the branch base (origin/main f5cc79b8)
M_TOTAL = N_PRIOR + len(TESTS)                           # 4,826

# ------------------------------------------------------------------------------------------------ reproduction (PREREG 1)
R193_W1 = {"R72": (1595, 237089), "R51": (2240, 124786), "LIQ": (908, 218210)}   # R193 span.json, V5, W1, 1 lot
