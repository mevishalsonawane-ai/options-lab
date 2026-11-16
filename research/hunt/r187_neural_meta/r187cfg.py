"""R187 settings (PREREG.md): periods, folds, grids, exit menus, gates and the BH count. Pure: no file is read here.

    lake      $R187_LAKE (default ~/.cache/iraalgo/r187/lake): the local lake (2024-2026, the 2026 files joined to their
              20 Oct - 13 Nov parts as R186's extended copy) plus 2021-2023 from the Drive. Never in git.
    cache     $R187_OUT (default ~/.cache/iraalgo/r187): rows, scores, the ORB store (never in git)
    results   research/hunt/r187_neural_meta/results (small JSON / markdown only)

Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE_R187", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R187_OUT", CACHE_ROOT / "r187")).expanduser()
LAKE = Path(os.environ.get("R187_LAKE", OUT / "lake")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ periods (PREREG 2, 4)
R_START = date(2024, 11, 20)          # R72 / R51 / Liquidity: the stores' first usable day
ORB_START = date(2021, 8, 1)          # ORB rows: the lake's options start
DESIGN_END = date(2026, 3, 31)
Y_START, Y_END = date(2025, 10, 20), date(2026, 10, 19)
FINAL_START, FINAL_END = date(2026, 4, 1), date(2026, 10, 13)
SIX_START = date(2026, 4, 20)
NEW_START, NEW_END = date(2026, 10, 21), date(2026, 11, 13)
PERIODS = ("early", "final", "six", "year")


def span_of(period: str) -> tuple:
    return {"early": (Y_START, DESIGN_END), "final": (FINAL_START, FINAL_END), "six": (SIX_START, Y_END),
            "year": (Y_START, Y_END), "new": (NEW_START, NEW_END), "wf": (date(2025, 4, 1), DESIGN_END)}[period]


# folds: (name, test_lo, test_hi, inner-validation lo); training = every trade day < test_lo; inner fit < val_lo
FOLDS = (
    ("F1", date(2025, 4, 1), date(2025, 6, 30), date(2025, 1, 1)),
    ("F2", date(2025, 7, 1), date(2025, 9, 30), date(2025, 4, 1)),
    ("F3", date(2025, 10, 1), date(2025, 12, 31), date(2025, 7, 1)),
    ("F4", date(2026, 1, 1), date(2026, 3, 31), date(2025, 10, 1)),
    ("F5", date(2026, 4, 1), NEW_END, date(2026, 1, 1)),
)
DESIGN_FOLDS = ("F1", "F2", "F3", "F4")


def fold_of(d: date) -> str | None:
    for name, lo, hi, _ in FOLDS:
        if lo <= d <= hi:
            return name
    return None


# ------------------------------------------------------------------------------------------------ arms and exits (PREREG 3)
R_ARMS = ("R72", "R51", "LIQ")
ORB_ARMS = ("orb", "orb_fresh", "orb_sweep")
ARMS = R_ARMS + ORB_ARMS
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5",
            "orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep"}
ARM_ID = {"R72": "R72", "R51": "R51", "LIQ": "LIQ", "orb": "ORB", "orb_fresh": "ORB", "orb_sweep": "SWEEP"}
ARM_IDS = ("R72", "R51", "LIQ", "ORB", "SWEEP")
PICKER_ARMS = ("R72", "R51")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
CROSS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX")
V5_LOCK = {"R72": "L500", "R51": "L500", "LIQ": "LBE"}
OTHER_LOCK = {"R72": "LBE", "R51": "LBE", "LIQ": "L500"}
# R exit id -> r186engine.price keyword arguments (filled per arm by r_exit_kw)
R_EXITS = ("V5", "V4", "A", "OL", "P60", "IDX", "T1000", "T1500")
ORB_EXITS = ("B0", "X1", "X2", "X3", "X4", "X5", "X6")
EXITS = tuple(f"R:{e}" for e in R_EXITS) + tuple(f"O:{e}" for e in ORB_EXITS)


def base_exit(arm: str) -> str:
    return "R:V5" if arm in R_ARMS else "O:B0"


def menu(arm: str) -> tuple:
    return tuple(f"R:{e}" for e in R_EXITS) if arm in R_ARMS else tuple(f"O:{e}" for e in ORB_EXITS)


def r_exit_kw(arm: str, e: str) -> dict:
    """r186engine.price keywords for exit `e` (without the R: prefix) on arm."""
    v5 = V5_LOCK[arm]
    return {"V5": dict(lock=v5), "V4": dict(), "A": dict(be_x=-1.0), "OL": dict(lock=OTHER_LOCK[arm]),
            "P60": dict(lock="P60"), "IDX": dict(lock=v5, idx=True), "T1000": dict(lock=v5, tgt_net=1000.0),
            "T1500": dict(lock=v5, tgt_net=1500.0)}[e]


def orb_changes(e: str) -> tuple:
    return () if e == "B0" else (e,)


# ------------------------------------------------------------------------------------------------ network (PREREG 2)
HIDDEN = ((64, 32, 16), (128, 64, 32))
ALPHAS = (1e-3, 1e-2)
SEEDS = (187, 188, 189)
MLP_KW = dict(activation="relu", solver="adam", learning_rate_init=1e-3, batch_size=256, max_iter=200,
              early_stopping=True, validation_fraction=0.1, n_iter_no_change=10)
WINSOR = (0.01, 0.99)
NET_SCALE = 1000.0

# ------------------------------------------------------------------------------------------------ uses (PREREG 3)
THETAS = tuple(round(0.300 + 0.025 * i, 3) for i in range(17))        # 0.300 .. 0.700
DELTAS = (0.0, 100.0, 250.0, 500.0, float("inf"))
MIN_KEEP_U1 = 0.40
MIN_KEEP_U4 = 0.25
USES = ("U1", "U2", "U3", "U4")
TESTS = tuple((a, u) for a in ARMS for u in ("U1", "U2", "U3")) + tuple((a, "U4") for a in PICKER_ARMS)   # 20

# ------------------------------------------------------------------------------------------------ gates (PREREG 5)
TWINS = 500
SEED = 187
GATE_Q = 0.10
MIN_FINAL = 40
MAX_DD = 40000.0
WORST_MONTH = -13000.0
M_PRIOR = 4882                         # the count after R186 Amendment C
M_TOTAL = M_PRIOR + len(TESTS)         # 4,902

# parity (PREREG 6)
V5_PARITY_YEAR = {"R72": (1097, 213110), "R51": (1430, 162959), "LIQ": (688, 98239)}
ORB_PARITY_APP = {"orb": (320, -41427), "orb_fresh": (320, -41427), "orb_sweep": (454, -112523)}
ORB_PARITY_WF = {"orb": (231, -25965), "orb_fresh": (231, -25965), "orb_sweep": (326, -98449)}


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def months_between(lo: date, hi: date) -> tuple:
    out, y, m = [], lo.year, lo.month
    while (y, m) <= (hi.year, hi.month):
        out.append(f"{y:04d}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return tuple(out)
