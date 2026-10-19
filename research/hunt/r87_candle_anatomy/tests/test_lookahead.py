"""The look-ahead guard (CLAUDE.md: research code gets the look-ahead guard).

Every context / anatomy / family column of a candle must be the same whether or not the data after that candle exists.
The test builds the full candle table, then rebuilds it from minutes cut at random minutes, and compares every non-label
column of every candle that was complete before the cut. Labels (f_*) must be the only columns that may differ, and at
least one of them must differ (else the test would not be testing anything)."""
import numpy as np
import pandas as pd
import pytest

from research.hunt.r87_candle_anatomy import build as B
from research.hunt.r87_candle_anatomy import context as CX
from research.hunt.r87_candle_anatomy.tests import synth


@pytest.mark.parametrize("tf", [1, 5, 15])
def test_no_lookahead(tf):
    m = synth.minutes(days=24, seed=5)
    full = B.candle_table(CX.minute_context(m, "NIFTY"), "NIFTY", tf, set())
    rng = np.random.default_rng(tf)
    cuts = rng.choice(len(m), 4, replace=False)
    for cut in sorted(cuts):
        T = m.ts.iloc[cut]
        part = B.candle_table(CX.minute_context(m[m.ts <= T], "NIFTY"), "NIFTY", tf, set())
        # candles complete at T: their last minute in the full data is <= T and the next minute exists after it
        done = full[(full.ts + pd.Timedelta(minutes=tf - 1) <= T)]
        a = done.set_index("ts")
        b = part.set_index("ts").reindex(a.index)
        feats = [c for c in a.columns if not c.startswith("f_")]
        for c in feats:
            x, y = a[c], b[c]
            if x.dtype.kind in "fc":
                ok = np.allclose(x.to_numpy(float), y.to_numpy(float), equal_nan=True, rtol=1e-9, atol=1e-9)
            else:
                ok = (x.astype(object).where(x.notna(), "<na>").astype(str).to_numpy()
                      == y.astype(object).where(y.notna(), "<na>").astype(str).to_numpy()).all()
            assert ok, f"tf {tf} cut {T}: column {c} changed when the future was removed"
        labs = [c for c in a.columns if c.startswith("f_")]
        if len(a) > 30:
            changed = any(not np.allclose(a[c].to_numpy(float), b[c].to_numpy(float), equal_nan=True) for c in labs)
            assert changed, "labels did not change: the guard is not exercising the future"
