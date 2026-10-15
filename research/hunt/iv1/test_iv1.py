"""IV1 tests: the fixed rule O3 and the input list."""
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import study as ST  # noqa: E402


def test_o3_rule():
    te = pd.DataFrame(dict(oi_pe_atm_chg30_pct=[5, -5, 5, 5, np.nan], oi_ce_atm_chg30_pct=[-5, 5, -5, -5, -5],
                           pcr_oi_chg30=[0.1, -0.1, -0.1, 0.1, 0.1], straddle_chg15_pct=[1, 1, 1, -1, 1]))
    assert list(ST.o3(te)) == ["C", "P", "", "", ""]      # PCR falling vetoes CE; a shrinking straddle vetoes; NaN no trade


def test_inputs_are_option_market_and_context_only():
    assert not any(c.startswith(("rib", "cls", "ema")) for c in ST.OPT)
    assert {"iv_skew2", "straddle_pct", "pcr_oi", "und_id"} <= set(ST.OPT)
