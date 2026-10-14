"""R26 parity fixture for ira-core's OiWallBlastTest: synthetic expiry days from research/hunt/r26/test_r26.py's builders,
judged by research/hunt/r26/r26lib.py (rule P and A3), written as compact text.

    python3 android/tools/oiwall_r26_fixture.py . <r26 dir> > android/ira-core/src/test/resources/oiwall/r26_parity.txt

<r26 dir> holds r26lib.py, r26cfg.py and test_r26.py from branch claude/r26-oi-wall-blast (research/hunt/r26; R25's code
is in research/hunt/r25 here), e.g. `git show origin/claude/r26-oi-wall-blast:research/hunt/r26/r26lib.py > <r26 dir>/r26lib.py`.
"""
import math
import sys

repo, r26dir = sys.argv[1], sys.argv[2]
for p in (repo, repo + "/research", repo + "/research/hunt/r25", r26dir):
    sys.path.insert(0, p)

import numpy as np  # noqa: E402
from dataclasses import replace  # noqa: E402

import r26lib as L  # noqa: E402
import r25lib as R  # noqa: E402
from r25data import C, OI, V, N, minute  # noqa: E402
import test_r26 as T  # noqa: E402
from test_r25 import make_day  # noqa: E402

A3 = replace(L.P, id="A3", filt=False)
FROM = 150


def rle(vals):
    out, prev, n = [], None, 0
    for v in vals:
        if n and v == prev:
            n += 1
            continue
        if n:
            out.append(prev if n == 1 else f"{prev}*{n}")
        prev, n = v, 1
    if n:
        out.append(prev if n == 1 else f"{prev}*{n}")
    return ",".join(out)


def num(x):
    return "" if x is None or (isinstance(x, float) and math.isnan(x)) else repr(round(float(x), 6))


def keep(day, lo, hi):
    day.opts = {k: a for k, a in day.opts.items() if lo <= k[0] <= hi}
    day.__dict__.pop("_r26chain", None)
    return day


def dump(name, day, lines):
    lines.append(f"case {name} {day.und}")
    lines.append("spot " + rle([num(x) for x in day.ix[FROM:, 3]]))
    for (k, s), a in sorted(day.opts.items()):
        traded = (~np.isnan(a[:, C])) & (np.nan_to_num(a[:, V]) > 0)
        cv = np.cumsum(np.nan_to_num(a[:, V]))
        near = np.nanmin(day.ix[:, 3]) - 150 <= k <= np.nanmax(day.ix[:, 3]) + 150
        close = [num(round(float(a[r, C]), 2)) if traded[r] and near else "" for r in range(FROM, N)]
        oi = [num(a[r, OI]) if (not np.isnan(a[r, C])) and np.nan_to_num(a[r, OI]) > 0 else "" for r in range(FROM, N)]
        dv = [num(np.nan_to_num(a[r, V])) if not np.isnan(a[r, C]) else "" for r in range(FROM, N)]
        has = ["1" if not np.isnan(a[r, C]) else "" for r in range(FROM, N)]
        lines.append(f"opt {k!r} {'CE' if s == 'C' else 'PE'}")
        lines.append("has " + rle(has))
        lines.append("close " + rle(close))
        lines.append("oi " + rle(oi))
        lines.append(f"vol0 {num(cv[FROM - 1])}")
        lines.append("dvol " + rle(dv))
    for rule in (L.P, A3):
        s = L.signal(day, rule)
        lines.append(f"signal {rule.id} " + ("none" if s is None else f"{s.row} {s.side} {s.strike!r} {s.ref!r} {s.wall!r} {int(s.wall_edge)}"))
    for sgn in (1, -1):
        flags = []
        for t in range(L.FIRST, L.LAST + 1):
            c = L.conditions(day, A3, sgn, t)
            flags.append("".join(str(int(bool(c[x]))) for x in ("break", "oi_fall", "vol", "price", "sum_oi")))
        lines.append(f"cond {sgn} " + rle(flags))
    for side in ("C", "P"):
        w = L.chain(day).wall(side, L.ANCHOR)
        lines.append(f"wall {'CE' if side == 'C' else 'PE'} {num(w[0]) or 'none'} {int(w[1])}")


lines = ["# R26 parity fixture (14 Oct 2026): synthetic expiry days from research/hunt/r26/test_r26.py, judged by r26lib.py.",
         f"# Rows {FROM}..374 only (row t = the bar starting 09:15 + t). Values are run-length coded (v*n); empty = none.",
         "# vol0: the cumulative volume to row 149; dvol: each row's volume (a row without a bar: empty); close only near spot.",
         "# signal <rule> <row> <side> <strike> <ref> <wall> <edge> | none; cond <sgn> = break oi_fall vol price sum_oi per row 13:45..14:30"]

# 1. The 12 Sep 2024 shape: NIFTY up through the call wall at 13:30-14:10, wall OI unwinding, volume 5x; ATM+1 CE at Rs 20.
d = keep(T.blast_day(), 24700.0, 25450.0)
s = L.signal(d, A3)
T.price_path(d, s.strike, "C", np.full(N, 20.0))
d.__dict__.pop("_r26chain", None)
dump("blast-up", d, lines)
# 2. The same day without the OI unwinding: no trigger (the B1 baseline would fire).
dump("no-oi-fall", keep(T.blast_day(oi_fall=False), 24700.0, 25450.0), lines)
# 3. A flat day: nothing breaks.
dump("flat", keep(make_day(T.flat()), 24700.0, 25450.0), lines)
# 4. DOWN through the put wall (BANKNIFTY-like 25% theta is not needed: NIFTY), ATM-1 PE at Rs 25.
r = np.arange(N)
spot = 25000.0 - np.clip((r - minute(13, 30)) / 40.0, 0, 1) * 150.0
d = make_day(spot)
for (k, sd), a in d.opts.items():
    if sd == "P" and k == 24900.0:
        T._unwind(a)
    a[minute(13, 35):, V] *= 5
d = keep(d, 24550.0, 25300.0)
s = L.signal(d, A3)
T.price_path(d, s.strike, "P", np.full(N, 25.0))
d.__dict__.pop("_r26chain", None)
dump("blast-down", d, lines)
print("\n".join(lines))
