"""R89 stores: R51's tested loader (research/hunt/r51/r51data.py), Amendment A clean + 18 Oct stale-print step, with the
window extended to the lake's newest session (19 Oct 2026). Stores go to $R89_OUT (never git).

    IRAALGO_LAKE=~/lakeflat python research/hunt/r89_pdhl_break/store.py build [UND ...]
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = Path(os.environ.get("R89_OUT", Path.home() / ".cache" / "iraalgo" / "r89")).expanduser()
os.environ.setdefault("R51_CLEAN", "1")
os.environ.setdefault("R51_STALE", "1")
sys.path.insert(0, str(HERE.parent / "r51"))
import r51cfg as K  # noqa: E402

K.END = date(2026, 10, 19)            # the lake's newest session (R51 stopped at 13 Oct)
K.OUT = OUT / "stores"
if os.environ.get("R89_STORE") == "old":
    # R89B holdout (PREREG_B 1): 2 Aug 2021 - 19 Nov 2024, warm-up from 1 Jun 2021; built in its own folder
    K.WARM_START, K.START, K.END = date(2021, 6, 1), date(2021, 8, 2), date(2024, 11, 19)
    K.OUT = OUT / "stores_old"
import r51data  # noqa: E402,F401

_CACHE: dict = {}


def load(und: str, which: str = "new") -> dict:
    """R51 day store of one index: 'new' (20 Nov 2024 - 19 Oct 2026) or 'old' (R89B holdout, Aug 2021 - Nov 2024).
    Read straight from its pickle so both can be open in one process."""
    import pickle
    key = (und, which)
    if key not in _CACHE:
        with open(OUT / ("stores_old" if which == "old" else "stores") / f"day_{und}.pkl", "rb") as f:
            _CACHE[key] = pickle.load(f)
    return _CACHE[key]


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(r51data.build(u), flush=True)
