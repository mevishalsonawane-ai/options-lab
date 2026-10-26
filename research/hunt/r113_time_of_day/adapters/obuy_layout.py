"""R113 (26 Oct 2026): lay the Drive lake out the way research/obuy/data.py reads it (raw, not cleaned), for the old
obuy-based studies (R1 VIX divergence, h22 Pine replay). Uses R52's own converter unchanged, for NIFTY and BANKNIFTY,
2021-2026 (the old studies ran on the raw Dhan files, so no cleaning here).

    python3 research/hunt/r113_time_of_day/adapters/obuy_layout.py <OBUY_SCRATCH>
        -> <OBUY_SCRATCH>/dhan/repo/dhan-data/{options,candles}/...
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "research" / "hunt" / "r52"))
import lake2obuy  # noqa: E402

UNDS = ("NIFTY", "BANKNIFTY")
YEARS = (2021, 2022, 2023, 2024, 2025, 2026)

if __name__ == "__main__":
    out = Path(sys.argv[1]).expanduser() / "dhan" / "repo" / "dhan-data"
    out.mkdir(parents=True, exist_ok=True)
    lake2obuy.convert(out, unds=UNDS, years=YEARS, clean=False)
    print("written", out)
