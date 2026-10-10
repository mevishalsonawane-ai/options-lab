"""h37: how often the constituent-volume profile agrees with the TPO profile (NIFTY, BANKNIFTY, days with volume)."""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
from obuy.data import market  # noqa: E402
import evaluate as EV  # noqa: E402
import sigs  # noqa: E402

mk = market()
for u in ("NIFTY", "BANKNIFTY"):
    ix = mk.index(u)
    M = ix.mat()
    W = EV.vp_weights(u, ix)
    t = sigs.profile_levels(M["h"], M["l"], M["c"])
    v = sigs.profile_levels(M["h"], M["l"], M["c"], weights=W)
    ok = ~np.isnan(v[:, 0]) & ~np.isnan(t[:, 0])
    t, v = t[ok], v[ok]
    bs = 0.0002 * t[:, 0]
    poc_in = ((v[:, 0] >= t[:, 2]) & (v[:, 0] <= t[:, 1])).mean()
    dpoc = np.abs(v[:, 0] - t[:, 0]) / t[:, 0] * 100
    ov = np.maximum(0, np.minimum(t[:, 1], v[:, 1]) - np.maximum(t[:, 2], v[:, 2]))
    jac = ov / (np.maximum(t[:, 1], v[:, 1]) - np.minimum(t[:, 2], v[:, 2]))
    print(f"{u}: days {ok.sum()}, VP POC inside TPO value area {poc_in:.0%}, median |POC gap| {np.median(dpoc):.3f}% "
          f"(90th pct {np.percentile(dpoc, 90):.3f}%), value-area overlap (intersection/union) median {np.median(jac):.2f}, "
          f"VA width TPO {np.median((t[:, 1] - t[:, 2]) / t[:, 0] * 100):.2f}% vs VP {np.median((v[:, 1] - v[:, 2]) / t[:, 0] * 100):.2f}%")
