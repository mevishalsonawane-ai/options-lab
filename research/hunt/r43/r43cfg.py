"""R43 settings (PREREG.md): Boss's 7 sweep signals, on the index, as zero-to-hero expiry-day trades.

    lake       $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<YEAR>.parquet and index_1m_<UND>_<YEAR>.parquet,
               fetched with `rclone copy gdrive:IraAlgo-data-lake/{options_rolling_1m,index_1m} ...`
    caches     R43_OUT (default <cache>/r43): day pickles, 5-minute index bars, outcome tables (big: never in git)
    results    research/hunt/r43/results (small JSON / markdown only)

Engine (fills, charges, the held option's path) is R25's (research/hunt/r25/r25lib.py). R25's settings only knew NIFTY,
BANKNIFTY and SENSEX; R43 ADDS the FINNIFTY and MIDCPNIFTY strike steps and lots to them here (adding keys changes
nothing for R25).

The locked holdout (PREREG.md "Data split"): every expiry day from 1 Jan 2025 to the end of the lake (12 Oct 2026).
Design: every expiry day up to 31 Dec 2024. Only `study.py holdout` reads the holdout, once (a flag file refuses a second
read).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R25 = HERE.parent / "r25"
for p in (ROOT, ROOT / "research", R25, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r25cfg as K25  # noqa: E402
from research.brain import config as BC  # noqa: E402
from obuy.config import LOT_SCHEDULE  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R43_OUT", CACHE.root / "r43")).expanduser()
RESULTS = HERE / "results"
K25.LAKE = LAKE

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
K25.STEP.update(STEP)
K25.LOTS.update({u: LOT_SCHEDULE[u] for u in UNDS})
lot = K25.lot
BSE = K25.BSE

HOLDOUT_START = date(2025, 1, 1)
LAKE_END = date(2026, 10, 12)


def is_holdout(d: date) -> bool:
    return d >= HOLDOUT_START


# ---- the pre-registered rule grid (PREREG.md); every cell is one test in the BH family
BUDGET = 5000.0
CHEAP_BAND = (5.0, 30.0)
STOP_FRAC = 0.5            # a minute close <= 50% of the fill sells (every exit has it)
TRAIL_ARM = 3.0            # TRAIL: once the best close >= 3x the fill ...
TRAIL_KEEP = 0.5           # ... a close <= 50% of the best close sells
TIME_N = 60                # TIME: no close >= 2x the fill within 60 minutes of the fill -> sell
TIME_MULT = 2.0
TWINS = 500
SEED = 43

SIGNALS = ("ema821_adx15", "ema821_adx15_lock", "ema921_adx20", "ema926_adx20", "rsi_fade", "rsi_momo", "donchian20")
STRIKES = ("cheap", "otm2")
WINDOWS = {"all": (0, 65), "hero": (38, 65)}      # 5-minute bar index j: bar j CLOSES at 09:15 + 5(j + 1); 65 = 14:45
EXITS = ("hold", "trail", "time")
LOCK_EXIT = "step"                                  # variant 1b's only exit
BASE_BAR = {"all": 5, "hero": 38}                   # baselines: the bar closing 09:45 / 12:30, CE and PE both

GATE_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10


def rules() -> list[tuple[str, str, str, str]]:
    """(signal, strike, window, exit) for every test, baselines last (signal 'base')."""
    out = []
    for s in SIGNALS:
        for k in STRIKES:
            for w in WINDOWS:
                for e in ((LOCK_EXIT,) if s.endswith("_lock") else EXITS):
                    out.append((s, k, w, e))
    for k in STRIKES:
        for w in WINDOWS:
            for e in EXITS:
                out.append(("base", k, w, e))
    return out


def rule_id(r) -> str:
    return "-".join(r)
