"""Build R180's stores (R51's r51data.build, unchanged, from 1 Nov 2024 to 19 Oct 2026 into clean_r180).

    python research/hunt/r180_dual_book_check/build_stores.py [UND ...]
"""
from __future__ import annotations

import sys

import r180cfg as K  # noqa: F401  (points r51cfg at the R180 window and folder first)
import r51data

if __name__ == "__main__":
    for u in sys.argv[1:] or K.UNDS:
        print(r51data.build(u), flush=True)
