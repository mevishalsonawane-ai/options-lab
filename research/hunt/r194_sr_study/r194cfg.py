"""R194 settings (PREREG.md): support / resistance on R72, R51 and Liquidity 15+5, 1 Nov 2024 - 13 Nov 2026, on R193's
span, windows, stores and engine. Plain-English notes, 18 Nov 2026.

    caches    ~/.cache/iraalgo/r194 (never git); R193's store links and caches are reused read-only
    results   research/hunt/r194_sr_study/results (small JSON / markdown only)

Signal source of every rule: the index. Options are only bought.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
R193 = HUNT / "r193_arms_nov24"
for p in (str(HERE.parents[2]), str(HERE.parents[1]), str(R193), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r193cfg as K3  # noqa: E402  (R193's windows, gate numbers and environment)

OUT = Path(os.environ.get("R194_OUT", Path.home() / ".cache" / "iraalgo" / "r194")).expanduser()
RESULTS = HERE / "results"
WINDOWS = K3.WINDOWS
SPAN = K3.SPAN
STORE = K3.STORE
# R151's own store window (r51cfg defaults under R51_CLEAN): family B's reproduction and retrain run in it
R151_STORE = (date(2024, 9, 1), date(2024, 11, 20), date(2026, 10, 13))
R151_PARAMS = {"R72": (20, 4), "R51": (20, 3.0)}
R151_PID = {"R72": "R72-20-4-M2", "R51": "R51-20-3.0-M2"}
PICK_THR = K3.PICK_THR
MODEL_FILE = {"R72": "r72_picker_model.json", "R51": "r51_picker_model.json"}
REPRO_TOL = 1e-6

TWINS = 2000
SEED = 194
LOCK = "L500"                                   # V5's step lock on R72 / R51

# ------------------------------------------------------------------------------------------------ the 24 tests (PREREG 6)
# id -> (family, arm, variant, baseline)
TESTS = {}
for _a in ("R72", "R51"):
    TESTS[f"A-{_a}"] = ("A", _a, "P", "P-abl")
for _a in ("R72", "R51"):
    TESTS[f"B-{_a}"] = ("B", _a, "P", "P-noSR")
for _f in ("WALL15", "WALL30", "CROSS"):
    for _a in ("R72", "R51"):
        for _b in ("A", "P"):
            TESTS[f"C-{_f}-{_a}-{_b}"] = ("C", _a, f"{_b}+{_f}", _b)
for _a in ("R72", "R51"):
    for _b in ("P", "V5"):
        TESTS[f"D-TGT-{_a}-{_b}"] = ("D", _a, f"{_b}+TGT", _b)
LIQ_VARIANTS = {"D-LQ-R0": (0.0, True), "D-LQ-R05": (0.5, True), "D-LQ-R2": (2.0, True), "D-LQ-NL0": (1.0, False)}
for _t in LIQ_VARIANTS:
    TESTS[_t] = ("D", "LIQ", _t[2:], "L1")
assert len(TESTS) == 24

LABEL = {
    "A": "own exits, no picker", "P": "own exits + R151 picker", "V5": "live rule (picker + breakeven +10% + Rs 500 step lock)",
    "P-abl": "picker with the 5 S/R features at their training medians", "P-noSR": "picker retrained without the 5 S/R features",
    "WALL15": "skip when a 3+-touch level is ahead within 0.15%", "WALL30": "skip when a 3+-touch level is ahead within 0.30%",
    "CROSS": "take only breaks that crossed a 3+-touch level", "TGT": "S/R target: sell when the index touches the next 3+-touch level ahead",
    "L1": "Liquidity as live (room 1 index stop, new-level exit on)", "LQ-R0": "Liquidity, no room check",
    "LQ-R05": "Liquidity, room 0.5 index stop", "LQ-R2": "Liquidity, room 2 index stops", "LQ-NL0": "Liquidity, new-level exit off",
}

N_PRIOR = 4784                                  # entry files at the branch base (origin/main 4398bd6c5)
M_TOTAL = N_PRIOR + len(TESTS)                  # 4,808
