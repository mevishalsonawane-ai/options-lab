"""S5 Trail (R123 N1) input-parity fixture for the engine's S5TrailTest: synthetic sessions of the five indices and India VIX
(with missing minutes and a missing VIX day), a synthetic option chain on the offset grid, and R123's own inputs for them:
the 120 x 7 window (r123data.IndexData.seq) and the 42 tabular inputs (r123data.tab_features + chain_features).

    python3 android/tools/s5_parity_fixture.py . > android/engine/src/test/resources/s5/inputs_parity.json

The Kotlin test rebuilds each case's sessions and chain from the numbers here and compares its window and inputs (1e-9).
"""
import json
import sys
from datetime import date, timedelta

repo = sys.argv[1]
sys.path.insert(0, repo + "/research/hunt/r123_neural_70")

import numpy as np  # noqa: E402

import r123cfg as K  # noqa: E402
import r123data as D  # noqa: E402

N = K.N
rng = np.random.default_rng(123)
START = {"NIFTY": 25000.0, "BANKNIFTY": 56000.0, "FINNIFTY": 26500.0, "MIDCPNIFTY": 13000.0, "SENSEX": 82000.0}


def session(px, gap_p=0.004):
    c = px * np.exp(np.cumsum(rng.normal(0, 0.0008, N)))
    o = np.r_[px * (1 + rng.normal(0, 0.003)), c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.0003, N)))
    lo = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.0003, N)))
    a = np.round(np.vstack([o, h, lo, c]), 2)
    a[:, rng.random(N) < gap_p] = np.nan
    return a


def nan_list(a):
    return [None if not np.isfinite(v) else float(v) for v in np.asarray(a, dtype=float).ravel()]


def main():
    days = []
    d = date(2026, 9, 21)
    while len(days) < 22:
        if d.weekday() < 5:
            days.append(d)
        d += timedelta(days=1)
    grids, raw, past = {}, {}, {}
    for u in K.UNDS:
        px, arrs = START[u], []
        for _ in days:
            a = session(px)
            arrs.append(a)
            px = float(a[3][np.isfinite(a[3])][-1])
        # MIDCPNIFTY misses one whole session (the breadth and alignment paths)
        sel = [k for k in range(len(days)) if not (u == "MIDCPNIFTY" and k == len(days) - 3)]
        dd = [days[k] for k in sel]
        A = np.stack([arrs[k] for k in sel])
        grids[u] = D.Grid(dd, A[:, 0], A[:, 1], A[:, 2], A[:, 3])
        raw[u] = {str(days[k]): [nan_list(arrs[k][j]) for j in range(4)] for k in sel[-3:]}
        # older sessions as R123's daily stats (d_range, d_close), all but today
        past[u] = []
        for k in sel[:-1]:
            a = arrs[k]
            cl = a[3][np.isfinite(a[3])][-1]
            past[u].append([str(days[k]), float((np.nanmax(a[1]) - np.nanmin(a[2])) / cl * 100), float(cl)])
    va = [session(14.0, 0.01) for _ in days]
    vsel = [k for k in range(len(days)) if k != len(days) - 4]          # VIX misses a session
    V = np.stack([va[k] for k in vsel])
    vix = D.Grid([days[k] for k in vsel], V[:, 0], V[:, 1], V[:, 2], V[:, 3])
    raw_vix = {str(days[k]): [nan_list(va[k][j]) for j in range(4)] for k in vsel[-3:]}

    cases = []
    for u in ("NIFTY", "SENSEX", "MIDCPNIFTY"):
        I = D.IndexData(u, grids, vix)
        i = len(I.days) - 1
        for t in ((4, 9, 14, 40) if u == "NIFTY" else (4, 14)):
            # a chain on the offset grid for this session: close, oi, iv, volume (N, 21, 2), with holes
            ch = {}
            base = np.linspace(400, 20, 21)
            cl = np.empty((N, 21, 2))
            cl[..., 0] = base[None, :] * np.exp(rng.normal(0, 0.02, (N, 21)))
            cl[..., 1] = base[::-1][None, :] * np.exp(rng.normal(0, 0.02, (N, 21)))
            oi = rng.integers(1_000, 900_000, (N, 21, 2)).astype(float)
            cl = np.round(cl, 2)
            iv = np.round(rng.uniform(8, 20, (N, 21, 2)), 3)
            vol = rng.integers(-5, 50_000, (N, 21, 2)).astype(float)
            for arr in (cl, oi, iv, vol):
                arr[rng.random(arr.shape) < 0.05] = np.nan
            cl[: t + 1, 10, 0] = np.nan if t == 9 else cl[: t + 1, 10, 0]   # one case: no ATM call yet
            ch = dict(close=cl, oi=oi, iv=iv, volume=vol)
            ctx = dict(to_expiry=(3 if t != 40 else 30), is_expiry=(t == 14), is_budget=(t == 4 and u == "SENSEX"))
            f = D.tab_features(I, i, t, ctx, ch)
            p = I.gpos(i, t)
            W = I.seq[p - K.SEQ_N + 1:p + 1]
            cases.append(dict(und=u, t=t, ctx=ctx,
                              chain={k: nan_list(v[: t + 1]) for k, v in ch.items()},
                              tab=[None if not np.isfinite(f[c]) else float(f[c]) for c in D.TAB_COLS],
                              seq=W.astype(float).tolist()))
    print(json.dumps(dict(days=[str(x) for x in days], bars=raw, past=past, vix=raw_vix, cases=cases)))


if __name__ == "__main__":
    main()
