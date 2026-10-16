"""Parity fixture for the engine's BreadthDonchianArmTest (16 Oct 2026): five synthetic indices' 5-minute bars (one common
market move plus each index's own noise, so the indices often break together; a few bars dropped on some indices, as a feed
gap), judged by the research's own code - research/hunt/r72/r72rules.py `Panel` and `breadth_breakout` (M = 4) - written as
JSON. The signal bars are the research's raw signals (every bar of the day, before the study's 09:20-15:10 window).

    python3 android/tools/breadth_donchian_fixture.py . > android/engine/src/test/resources/passedarms/breadth_parity.json
"""
import importlib.util
import json
import sys

import numpy as np

repo = sys.argv[1]
sys.path.insert(0, f"{repo}/research/hunt/r71")
spec = importlib.util.spec_from_file_location("r72rules", f"{repo}/research/hunt/r72/r72rules.py")
R72 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R72)

UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
BASE = {"NIFTY": 25_000.0, "BANKNIFTY": 56_000.0, "FINNIFTY": 26_500.0, "MIDCPNIFTY": 13_000.0, "SENSEX": 82_000.0}
DAYS, BARS = 6, 75
rng = np.random.default_rng(72)

common = rng.normal(0, 0.0012, DAYS * BARS)            # the market's move, shared by the five
data, rows = {}, {}
for k, u in enumerate(UNDS):
    r = 0.9 * common + rng.normal(0, 0.0007, DAYS * BARS)
    c = BASE[u] * np.exp(np.cumsum(r))
    o = np.r_[BASE[u], c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.0003, len(c))))
    lo = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.0003, len(c))))
    d = np.repeat(np.arange(DAYS), BARS)
    b = np.tile(np.arange(BARS), DAYS)
    keep = np.ones(len(c), bool)
    if k in (2, 4):                                    # FINNIFTY and SENSEX miss a few bars (a feed gap)
        keep[rng.choice(np.arange(150, len(c)), 12, replace=False)] = False
    d, b, o, h, lo, c = d[keep], b[keep], o[keep], h[keep], lo[keep], c[keep]
    data[u] = (d, b, o, h, lo, c)
    rows[u] = [[int(d[i]), int(b[i]), float(o[i]), float(h[i]), float(lo[i]), float(c[i])] for i in range(len(c))]

P = R72.Panel(data)
sig = R72.breadth_breakout(P, M=4)
out = sorted({(u, int(data[u][0][j]), int(data[u][1][j]), int(s)) for u, v in sig.items() for (j, s, _inval) in v},
             key=lambda x: (x[1], x[2], UNDS.index(x[0])))
# The research trades only from its WARM-th bar on (P.ok): the bars before it are not compared.
json.dump({"unds": UNDS, "warm": int(R72.WARM), "bars": rows, "signals": [list(x) for x in out]}, sys.stdout)
