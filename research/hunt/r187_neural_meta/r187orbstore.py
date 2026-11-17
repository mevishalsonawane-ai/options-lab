"""R187: rebuild R3's BANKNIFTY session store from the lake to 13 Nov 2026 (PREREG section 1b), outside git.

    IRAALGO_LAKE=~/.cache/iraalgo/r187/lake R169_OUT=~/.cache/iraalgo/r187/r3 \
        python research/hunt/r187_neural_meta/r187orbstore.py

The lake copy joins the 2026 files with their 20 Oct - 13 Nov parts (R186's extended copy) and adds 2021-2023 from the
Drive. R3's LAST_DAY (12 Oct) is moved to 13 Nov here only; every other R3 rule is unchanged. Plain-English notes,
16 Nov 2026.
"""
from __future__ import annotations

import os
import pickle
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r169_orb_enhance"))
import r169cfg  # noqa: E402,F401  (puts R3 on the path; R3_OUT = R169_OUT)
import r3cfg as K3  # noqa: E402
import r3data as D3  # noqa: E402

STORE_END = date(2026, 11, 13)


def main():
    assert "r187" in os.environ.get("R169_OUT", ""), "set R169_OUT to the R187 cache (never R169's own store)"
    K3.LAST_DAY = STORE_END
    ss = D3.build("BANKNIFTY")
    K3.OUT.mkdir(parents=True, exist_ok=True)
    D3.path("BANKNIFTY").write_bytes(pickle.dumps(ss, protocol=pickle.HIGHEST_PROTOCOL))
    print(len(ss), ss[0].day, ss[-1].day, flush=True)


if __name__ == "__main__":
    main()
