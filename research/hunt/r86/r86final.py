"""R86 final window: read ONCE, only for the tests that passed the design gates (PREREG section 2 and 5).

    python -m research.hunt.r86.r86study final

It refuses to run a second time (results/FINAL_READ) and does nothing if no test passed design: then the final
window is never read, as pre-registered.
"""
from __future__ import annotations

import json

from research.hunt.r86 import r86study as S


def main():
    des = json.loads((S.OUT / "design.json").read_text())
    passers = [k for k, r in des.items() if r.get("passes")]
    flag = S.OUT / "FINAL_READ"
    if flag.exists():
        raise SystemExit("final already read once (results/FINAL_READ); a second read is refused")
    if not passers:
        print("no test passed the design gates: the final window is NOT read (PREREG section 2)")
        return
    # A pass would be scored here with the same functions on FINAL_START..data end, the side signs and the model
    # fitted on design data only. Not implemented because R86's design produced no passer (see design.json); a future
    # pass must add the scoring here before reading, and write FINAL_READ first.
    raise SystemExit(f"design passers {passers}: final scoring not implemented yet - add it before reading")


if __name__ == "__main__":
    main()
