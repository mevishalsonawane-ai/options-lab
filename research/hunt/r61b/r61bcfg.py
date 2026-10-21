"""R61b settings: trap / fake-breakout / order-flow checks for Solo (PREREG.md). Research only; nothing here changes the
app.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake; never
              committed
    cache     $R61B_OUT (default ~/.cache/iraalgo/r61b): R57's day stores rebuilt here, plus R61b's minute aggregates
    results   research/hunt/r61b/results (small JSON only)

R61b reuses R57 (candidates S1-S4, held-contract labels, twins) and R61 (walk-forward, models, replace rule) unchanged.
R61's config points R57 at $R61_OUT when it is imported, so $R61_OUT is set to R61b's cache here first.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R61 = HERE.parent / "r61"
_CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R61B_OUT", _CACHE_ROOT / "r61b")).expanduser()
os.environ["R61_OUT"] = str(OUT)
for p in (R61, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r61cfg as K61  # noqa: E402

# if r61cfg / r57cfg were imported earlier in this process they kept their own cache: point them at R61b's either way
K61.OUT = OUT
K61.K57.OUT = OUT
K61.K57._K51.OUT = OUT
K57 = K61.K57
LAKE = K61.LAKE
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
START, DESIGN_END, FINAL_START, END = K61.START, K61.DESIGN_END, K61.FINAL_START, K61.END
WF_MONTHS, FINAL_MONTHS = K61.WF_MONTHS, K61.FINAL_MONTHS
UNDS = K61.UNDS
NROWS = K61.NROWS
SEED = 61

# ------------------------------------------------------------------------------------------------ feature settings
TRAP_N = 15              # a break fails if a close is back inside within this many minutes
OR_ROWS = 15             # opening range: rows 0..14 (09:15-09:29)
SWEEP_WIN = 30           # sweeps looked for in the last 30 bars
BRK_BARS = 5             # the signal bar: the 5 one-minute bars frow-4 .. frow
WIN15 = 15
DELTA_WIN = 30
BASE_SESSIONS = 20       # same-window baselines over the previous 20 non-expiry sessions ...
BASE_MIN = 10            # ... at least 10 needed
VOL_OFF = 2              # near-ATM volume: offsets -2..+2
OI_OFF = 5               # near-ATM OI: offsets -5..+5

TRAPS = ["trap_same_or", "trap_opp_or", "trap_same_pd", "trap_opp_pd"]
SWEEPS = ["sweep_same30", "sweep_opp30"]
BAR = ["brk_wick", "brk_clv"]
TR = TRAPS + SWEEPS + BAR + ["vol_brk_rel", "oi_chg15_pct", "bu15", "delta30", "cdelta_day", "optvol_imb15",
                             "absorb15"]
PRICE_ONLY = TRAPS + SWEEPS + BAR
SIGNED = ("brk_clv", "bu15", "delta30", "cdelta_day", "optvol_imb15")
GROUPS = {
    "traps": TRAPS, "sweeps": SWEEPS, "break_bar": BAR, "break_volume": ["vol_brk_rel"],
    "oi_at_break": ["oi_chg15_pct", "bu15"], "delta_proxies": ["delta30", "cdelta_day"],
    "optvol_imbalance": ["optvol_imb15"], "absorption": ["absorb15"],
}
assert sorted(sum(GROUPS.values(), [])) == sorted(TR) and len(TR) == 15

# ------------------------------------------------------------------------------------------------ variants (PREREG 4)
VARIANTS = {
    "V1": dict(kind="logit", extra=(), skip=None),
    "F1": dict(kind="logit", extra=(), skip="F1"),
    "F2": dict(kind="logit", extra=(), skip="F2"),
    "F3": dict(kind="logit", extra=(), skip="F3"),
    "F4": dict(kind="logit", extra=(), skip="F4"),
    "M1": dict(kind="logit", extra=tuple(TR), skip=None),
    "M2": dict(kind="gbc", extra=tuple(TR), skip=None),
    "M3": dict(kind="logit", extra=tuple(PRICE_ONLY), skip=None),
}
REFERENCE = {"T0": dict(kind="gbc", extra=(), skip=None)}   # ablation baseline for M2, not gated

GATE_P, GATE_Q, GATE_MIN_TRADES = K61.GATE_P, K61.GATE_Q, K61.GATE_MIN_TRADES
REPLACE_BOOT_P = K61.REPLACE_BOOT_P
