"""Solo 2.0 (R57 A2) feature-parity fixture for the engine's Solo2Test: synthetic index sessions (with missing minutes),
their 12 brain features computed by research/brain/features itself (registry.compute, as r57data.brain_features does,
kept in float64), and R57's own inputs and signing by research/hunt/r57/r57model.row_a, written as JSON.

    python3 android/tools/solo2_parity_fixture.py . > android/engine/src/test/resources/solo2/features_parity.json

The Kotlin test rebuilds each case's day from the minutes given here, its context (previous close, the previous sessions'
full ranges, ATR14, VIX, sessions to expiry, the chain context) and compares its 29 inputs with these (1e-9).
"""
import json
import math
import sys
from datetime import date, timedelta
from types import SimpleNamespace

repo = sys.argv[1]
for p in (repo, repo + "/research", repo + "/research/hunt/r51", repo + "/research/hunt/r57"):
    sys.path.insert(0, p)

import numpy as np  # noqa: E402

import r57data  # noqa: E402
import r57model  # noqa: E402
from research.brain.features import registry  # noqa: E402

N = 375
rng = np.random.default_rng(57)


def session(start_px):
    c = start_px * np.exp(np.cumsum(rng.normal(0, 0.0008, N)))
    o = np.r_[start_px * (1 + rng.normal(0, 0.002)), c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.0003, N)))
    lo = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.0003, N)))
    a = np.vstack([o, h, lo, c])
    gaps = rng.random(N) < 0.004
    a[:, gaps] = np.nan
    return a


def build(und, n_days=17):
    days = {}
    d0 = date(2026, 9, 1)
    px = 25000.0 if und == "NIFTY" else 12000.0
    d = d0
    for i in range(n_days):
        while d.weekday() >= 5:
            d += timedelta(days=1)
        a = session(px)
        if i == n_days - 1:
            a[:, 0:2] = np.nan          # today's first two minutes missing (day_first, or30 still work)
            a[:, 150] = np.nan          # a gap 14 minutes before the S1 row
        days[d] = SimpleNamespace(idx=a, d=d)
        px = float(np.nanmax(a[3][np.isfinite(a[3])][-1:]))
        d += timedelta(days=1)
    return days


def fnum(x):
    x = float(x)
    return None if not math.isfinite(x) else x


cases = []
for und in ("NIFTY", "MIDCPNIFTY"):
    days = build(und)
    ds = sorted(days)
    m = r57data.minutes_frame(days)
    f = registry.compute(m, None, und, names=list(r57data.FEATURES_BRAIN))
    arr = f[list(r57data.FEATURES_BRAIN)].to_numpy(np.float64)
    feats = {d: arr[i * N:(i + 1) * N] for i, d in enumerate(ds)}
    r57data.load_feat = lambda u, feats=feats: dict(names=r57data.FEATURES_BRAIN, days=feats)
    atr = r57data.daily_atr14(days)
    today = ds[-1]
    D = days[today]
    vix = np.full(N, np.nan)
    vix[3:] = 13.0 + np.cumsum(rng.normal(0, 0.01, N - 3))
    D.atr = atr[today]
    D.dte = 3.0
    D.vix = r57data.ffill_row(vix)
    D.vix_prev = 12.5
    ctx_vals = {"pcr5": 1.1, "iv_atm": 14.2, "iv_skew": 1.7}
    D.ctx = {k: np.full(N, v) for k, v in ctx_vals.items()}
    prev = days[ds[-2]].idx
    prev_close = float(prev[3][np.isfinite(prev[3])][-1])
    ranges = []
    for d in ds[:-1][-10:]:
        a = days[d].idx
        ranges.append(fnum(np.nanmax(a[1]) - np.nanmin(a[2])))
    for frow, side, setup in ((4, "C", "S3"), (29, "P", "S2"), (30, "C", "S4"), (59, "P", "S2"), (164, "C", "S1"),
                              (164, "P", "S1"), (250, "C", "S4"), (370, "P", "S2")):
        row = r57model.row_a(und, D, frow, side, setup)
        cases.append(dict(und=und, frow=frow, side=1 if side == "C" else -1, setup=setup,
                          expected=[fnum(row[n]) for n in r57model.names_a(False)]))
    if und == "NIFTY":
        nifty = dict(day=str(today), o=[fnum(x) for x in D.idx[0]], h=[fnum(x) for x in D.idx[1]], l=[fnum(x) for x in D.idx[2]],
                     c=[fnum(x) for x in D.idx[3]], prev_close=prev_close, prev_ranges=ranges, atr=fnum(D.atr), vix=[fnum(x) for x in vix],
                     vix_prev=12.5, dte=3.0, ctx=ctx_vals)
    else:
        midcp = dict(day=str(today), o=[fnum(x) for x in D.idx[0]], h=[fnum(x) for x in D.idx[1]], l=[fnum(x) for x in D.idx[2]],
                     c=[fnum(x) for x in D.idx[3]], prev_close=prev_close, prev_ranges=ranges, atr=fnum(D.atr), vix=[fnum(x) for x in vix],
                     vix_prev=12.5, dte=3.0, ctx=ctx_vals)

print(json.dumps(dict(names=r57model.names_a(False), days={"NIFTY": nifty, "MIDCPNIFTY": midcp}, cases=cases)))
