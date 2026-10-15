"""Parity fixture for the engine's OovArmTest and WatchlistTest: synthetic index sessions judged by the research's own code -
research/hunt/h37/sigs.py (bars, profile_levels, mp_signals: R52's H37-10 "open outside value") and research/hunt/r4/signals4.py
`gann_swing` (R52's R4-8, run on h37's 15-minute bars with its request recorder stubbed) - written as JSON.

    python3 android/tools/passed_arms_fixture.py . > android/engine/src/test/resources/passedarms/parity.json
"""
import ast
import importlib.util
import json
import math
import sys
import types

repo = sys.argv[1]

import numpy as np  # noqa: E402

spec = importlib.util.spec_from_file_location("h37sigs", f"{repo}/research/hunt/h37/sigs.py")
S37 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(S37)

# r4 signals4.gann_swing, exactly as written, with its recorder and day filter stubbed (bar_ok: every bar; add_std: record).
src = open(f"{repo}/research/hunt/r4/signals4.py").read()
fn = next(n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name == "gann_swing")
events = []
L4 = types.SimpleNamespace(add_std=lambda R, base, di, s, sig, nat=None: events.append((di, s, sig, nat[0])))
ns = dict(np=np, L4=L4, bar_ok=lambda B, t, okd: True, NAN=float("nan"))
exec(compile(ast.Module(body=[fn], type_ignores=[]), "signals4.py", "exec"), ns)
gann_swing = ns["gann_swing"]

N = 375
rng = np.random.default_rng(52)


def session(px, gap):
    o0 = px * (1 + gap)
    c = o0 * np.exp(np.cumsum(rng.normal(0, 0.0009, N)))
    o = np.r_[o0, c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.0004, N)))
    lo = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.0004, N)))
    a = np.vstack([o, h, lo, c])
    a[:, rng.random(N) < 0.01] = np.nan
    return a


gaps = [0.0, 0.012, -0.011, 0.0005, 0.009, -0.013, 0.0, 0.002]
days = []
px = 25000.0
for g in gaps:
    a = session(px, g)
    days.append(a)
    px = float(a[3][np.isfinite(a[3])][-1])
short = days[3].copy()
short[:, 150:] = np.nan                  # a session with fewer than 200 minutes: no levels
days[3] = short

M = {k: np.stack([d[i] for d in days]) for i, k in enumerate("ohlc")}
lev = S37.profile_levels(M["h"], M["l"], M["c"])
levp = np.vstack([np.full((1, 3), np.nan), lev[:-1]])
B5 = S37.bars(M, 5)
oov = S37.mp_signals(B5, M["o"], M["h"], M["l"], levp)["MP_OOV"]
oov_by_day = [0] * len(days)
for t in np.nonzero(oov)[0]:
    oov_by_day[int(B5["dpos"][t])] = int(oov[t])
B15 = S37.bars(M, 15)
gann_swing(None, None, B15, 15, None)


def f(x):
    x = float(x)
    return None if not math.isfinite(x) else x


out = dict(
    days=[dict(o=[f(x) for x in d[0]], h=[f(x) for x in d[1]], l=[f(x) for x in d[2]], c=[f(x) for x in d[3]]) for d in days],
    levels=[[f(x) for x in row] for row in lev],
    oov=oov_by_day,
    bars15=dict(dpos=[int(x) for x in B15["dpos"]], col_end=[int(x) for x in B15["col_end"]], o=[f(x) for x in B15["o"]],
                h=[f(x) for x in B15["h"]], l=[f(x) for x in B15["l"]], c=[f(x) for x in B15["c"]]),
    gann=[dict(s=int(s), side=1 if sig == 0 else -1, stop=f(stop), di=int(di))
          for di, s, sig, stop in events],
)
print(json.dumps(out))
