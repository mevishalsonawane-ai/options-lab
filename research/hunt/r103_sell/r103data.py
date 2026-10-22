"""R103 data: R51's held-contract store builder (Amendment A cleaning) run over Aug 2021 - 19 Oct 2026 for NIFTY and
BANKNIFTY, into R103's own folder (never in git). No network.

    R103_LAKE=<lake> python research/hunt/r103_sell/r103data.py build      # writes <R103_OUT>/day_<UND>.pkl

The lake folder needs index_1m/ and options_rolling_1m/ (as copied from Drive); a flat view of both is made in
<R103_OUT>/flat (symlinks) because R51 reads one flat folder.
"""
from __future__ import annotations

import os
import pickle
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE.parent / "r51", HERE.parent):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

LAKE = Path(os.environ.get("R103_LAKE", "/dev/shm/lake")).expanduser()
OUT = Path(os.environ.get("R103_OUT", Path.home() / ".cache" / "iraalgo" / "r103")).expanduser()
UNDS = ("NIFTY", "BANKNIFTY")
WARM_START = date(2021, 7, 1)
START = date(2021, 8, 1)
END = date(2026, 10, 19)


def flat() -> Path:
    """A flat folder of symlinks to the lake's index and option files (R51 reads K.LAKE / <file>)."""
    f = OUT / "flat"
    f.mkdir(parents=True, exist_ok=True)
    for sub in ("index_1m", "options_rolling_1m"):
        for p in sorted((LAKE / sub).glob("*.parquet")):
            q = f / p.name
            if not q.exists():
                q.symlink_to(p.resolve())
    return f


def r51():
    """R51's config and store builder, pointed at R103's window and folder (Amendment A cleaning on)."""
    os.environ["R51_CLEAN"] = "1"
    import r51cfg as K
    K.LAKE, K.OUT = flat(), OUT
    K.WARM_START, K.START, K.END = WARM_START, START, END
    K.CLEAN, K.STALE = True, False
    import r51data
    return K, r51data


_CACHE: dict = {}


def load(und: str) -> dict:
    """{day: Day} with idx (4, 375), atm (375,), con {(side, strike): (5, 375) O H L C V}, expiry (R51's own flag)."""
    if und not in _CACHE:
        r51()                                             # the Day class must be importable for unpickling
        with open(OUT / f"day_{und}.pkl", "rb") as f:
            _CACHE[und] = pickle.load(f)
    return _CACHE[und]


if __name__ == "__main__":
    K, D = r51()
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or UNDS:
            print(D.build(u), flush=True)
    else:
        raise SystemExit(__doc__)
