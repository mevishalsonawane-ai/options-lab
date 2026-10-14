"""R26 settings (PREREG.md): Boss's "OI wall blast" hero-zero framework, tested on every expiry of the Dhan lake.

R26 reuses R25's data loader, fills, charges and helpers (research/hunt/r25): this module puts r25 on the path, points
its cache at R26's own folder and adds FINNIFTY (weeklies to 19 Nov 2024), which R25 did not study.

    lake files   $IRAALGO_LAKE (default <cache>/lake), fetched with
                 rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m <lake> --include "opt_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY}_WEEK_*"
                 rclone copy gdrive:IraAlgo-data-lake/index_1m <lake> --include "index_1m_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY,INDIAVIX}_*"
    caches       R26_OUT (default <cache>/r26): per-expiry-day pickles (big: never in git)
    results      research/hunt/r26/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R25 = ROOT / "research" / "hunt" / "r25"
for p in (ROOT, ROOT / "research", R25, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r25cfg as K  # noqa: E402

OUT = Path(os.environ.get("R26_OUT", K.CACHE.root / "r26")).expanduser()
K.OUT = OUT                       # r25data.path() / load() read K.OUT at call time: R26's day pickles live here
RESULTS = HERE / "results"

UNDS = ("NIFTY", "BANKNIFTY", "SENSEX", "FINNIFTY")
K.STEP["FINNIFTY"] = 50.0
# FINNIFTY weeklies ended with the 19 Nov 2024 expiry (SEBI, Nov 2024), as BANKNIFTY's ended 13 Nov 2024
K.LAST_WEEKLY["FINNIFTY"] = date(2024, 11, 19)
K.LOTS["FINNIFTY"] = [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)]

# R25's split, reported separately (R26 tests a third-party rule with no fitted parameter, so it runs on ALL expiries)
HOLDOUT_END = K.HOLDOUT_END       # 31 Dec 2022: R25's locked holdout block (already read by R25)
FORWARD_ONLY = date(2026, 10, 13)  # not in the lake (it ends 12 Oct 2026)

TWINS = 1000
SEED = 26
BUDGET = 2000.0                   # 2% of a Rs 1,00,000 account per expiry; whole lots; 1 lot when one lot costs more
