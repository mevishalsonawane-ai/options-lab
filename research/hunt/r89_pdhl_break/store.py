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
import r51data  # noqa: E402,F401


def load(und: str) -> dict:
    return r51data.load(und)


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(r51data.build(u), flush=True)
