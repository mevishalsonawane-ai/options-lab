"""Parity fixture for the engine's SupertrendParityTest (26 Oct 2026, R105 lead 7): a synthetic index's 5-minute bars over 60
sessions (a random walk, so Supertrend flips often and some breaks come against it, a flat stretch, a gap open, a few bars dropped as a feed
gap), judged by the research's own code - research/hunt/r73/r73rules.py `supertrend_dir`, `channel`, `features` and
`trend_ok("T7")` - written as JSON: the direction of every bar, R73's ATR and final bands (the same loop, recorded, and
checked here to give exactly `supertrend_dir`'s directions), and every Donchian break in the entry window with its T7 verdict.

    python3 android/tools/supertrend_parity_fixture.py . > android/engine/src/test/resources/passedarms/supertrend_parity.json
"""
import importlib.util
import json
import sys

import numpy as np

repo = sys.argv[1]
spec = importlib.util.spec_from_file_location("r73rules", f"{repo}/research/hunt/r73/r73rules.py")
R73 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R73)

DAYS, BARS = 60, 75
rng = np.random.default_rng(105)
r = rng.normal(0, 0.0012, DAYS * BARS)                                          # a random walk (breaks both with and against Supertrend)
r[30 * BARS] += 0.012                                                           # a gap open on day 30
c = 25_000.0 * np.exp(np.cumsum(r))
c[600:612] = c[599]                                                             # a flat stretch (no range at all)
o = np.r_[25_000.0, c[:-1]]
h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.0004, len(c))))
lo = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.0004, len(c))))
h[600:612] = c[600:612]; lo[600:612] = c[600:612]; o[600:612] = c[600:612]
d = np.repeat(np.arange(DAYS), BARS)
b = np.tile(np.arange(BARS), DAYS)
keep = np.ones(len(c), bool)
keep[rng.choice(np.arange(400, len(c)), 15, replace=False)] = False            # a feed gap: bars missing, skipped as bars5 does
d, b, o, h, lo, c = d[keep], b[keep], o[keep], h[keep], lo[keep], c[keep]
o, h, lo, c = (np.round(x, 2) for x in (o, h, lo, c))                         # index prices to the paisa (a short file)

st = R73.supertrend_dir(h, lo, c, 10, 3.0)


def lines(h, l, c, n=10, mult=3.0):
    """r73rules.supertrend_dir's own loop, its ATR and final bands recorded as it goes."""
    m = len(c)
    tr = np.empty(m); tr[0] = h[0] - l[0]
    for i in range(1, m):
        tr[i] = max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
    atr = np.full(m, np.nan)
    atr[n - 1] = tr[:n].mean()
    for i in range(n, m):
        atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    up_b = dn_b = np.nan; cur = 0
    d_ = np.zeros(m, np.int64); U = np.full(m, np.nan); D = np.full(m, np.nan)
    for i in range(m):
        if not np.isfinite(atr[i]):
            continue
        mid = (h[i] + l[i]) / 2.0
        bu, bd = mid - mult * atr[i], mid + mult * atr[i]
        if cur == 0:
            up_b, dn_b, cur = bu, bd, 1 if c[i] >= mid else -1
        else:
            up_b = max(bu, up_b) if c[i - 1] > up_b else bu
            dn_b = min(bd, dn_b) if c[i - 1] < dn_b else bd
            if cur == -1 and c[i] > dn_b:
                cur = 1
            elif cur == 1 and c[i] < up_b:
                cur = -1
        d_[i] = cur; U[i] = up_b; D[i] = dn_b
    return d_, atr, U, D


dd, atr, U, D = lines(h, lo, c)
assert (dd == st).all(), "the recorded loop must give supertrend_dir's directions exactly"
assert (np.diff(st[st != 0]) != 0).sum() >= 20, "Supertrend should flip often in the fixture"

F = R73.features(d, b, o, h, lo, c)
hi, lw = F["hi"], F["lo"]
breaks = []
for i in range(len(c)):
    if not (0 <= b[i] <= 70) or not np.isfinite(hi[i]):        # the entry window 09:20 - 15:10 (bar 0 .. 70)
        continue
    s = 1 if c[i] > hi[i] else (-1 if c[i] < lw[i] else 0)
    if s:
        breaks.append([int(i), int(s), bool(R73.trend_ok("T7", F, i, s))])
print("against", sum(1 for x in breaks if not x[2]), "of", len(breaks), file=sys.stderr)
assert sum(1 for x in breaks if not x[2]) >= 10, "the fixture needs breaks Supertrend is against"

nan = lambda x: None if not np.isfinite(x) else float(x)
json.dump({"bars": [[int(d[i]), int(b[i]), float(o[i]), float(h[i]), float(lo[i]), float(c[i])] for i in range(len(c))],
           "dir": [int(x) for x in st], "atr": [nan(x) for x in atr], "up": [nan(x) for x in U], "dn": [nan(x) for x in D],
           "breaks": breaks}, sys.stdout)
