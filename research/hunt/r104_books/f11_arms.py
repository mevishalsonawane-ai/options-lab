"""R104 families F11 (exit overlays and entry filters on the arms) and F9 (money management), PREREG.md sections 5 / 7.

The arms are R101's B0: Liquidity 15+5 (LIQ), R72 Breadth Donchian (R72), R51 Donchian 20 (R51), R52 Open outside value
(R52), priced on R101's engine (R51 Amendment A clean stores, R64 simulator, liq fills, dated charges) over R101's window.
B0 must reproduce R101's published design figures before any overlay runs (asserted in `units()`).

    python research/hunt/r104_books/f11_arms.py build            # design units of the four arms + B0 parity
    python research/hunt/r104_books/f11_arms.py design           # every F11 / F9 test on the design period

Overlays re-price each candidate with an extra FORCED exit row computed from the index path (the earliest of the arm's
own forced row and the overlay's); filters drop candidates before the arm's own one-position walk (R80 / R101 method).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r101_arms_enhance"))
sys.path.insert(0, str(HERE))

import r101cfg as K101  # noqa: E402

import r104cfg as K  # noqa: E402

OUTA = K.OUT / "arms"
ARMS = ("LIQ", "R72", "R51", "R52")


def _mods():
    import r101arms as A
    import r101study as S
    return A, S


def units(arm: str, phase: str = "design") -> list:
    """The arm's priced candidates for the phase (cached). The final phase is built only by the holdout read."""
    OUTA.mkdir(parents=True, exist_ok=True)
    p = OUTA / f"units_{arm}_{phase}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    if phase != "design" and not K.HOLDOUT_FLAG.exists():
        raise PermissionError("the arms' final period is locked until the R104 holdout read")
    A, S = _mods()
    lo, hi = (K101.START, K101.DESIGN_END) if phase == "design" else (K101.FINAL_START, K101.END)
    if phase != "design":
        import common as C
        C.open_final()
    u = A.UNITS[arm](lo, hi)
    b0 = S.run_variant(u, None)
    par = S.check_b0(arm, b0, phase, u)
    assert par["reproduced"], (arm, phase, par)
    p.write_bytes(pickle.dumps(u, protocol=pickle.HIGHEST_PROTOCOL))
    (OUTA / f"parity_{arm}_{phase}.json").write_text(json.dumps(par, default=str))
    return u


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "build":
        for a in (sys.argv[2:] or ARMS):
            u = units(a, "design")
            print(a, len(u), "units", flush=True)
    else:
        raise SystemExit(__doc__)
