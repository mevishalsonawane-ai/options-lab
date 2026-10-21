"""R74 settings (PREREG.md): R71's settings (periods, trade-taking, exits, gates) with R74's own seed and folders.

    trades    $R74_OUT (default ~/.cache/iraalgo/r74): every priced trade and the VIX / stock caches (never in git)
    results   research/hunt/r74/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r71"))
from r71cfg import *  # noqa: E402,F401,F403
import r71cfg as _R71  # noqa: E402

HERE = Path(__file__).resolve().parent
OUT = Path(os.environ.get("R74_OUT", _R71.CACHE_ROOT / "r74")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
SEED = 74
