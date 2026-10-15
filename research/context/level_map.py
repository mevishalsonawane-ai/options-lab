"""I12 Level map: how often each kind of level held, point in time, against placebo levels (PREREG.md, I12). The same
definitions as ira-core's LevelMap.kt (the phone counts "held N of M" on its own candles the same way).

    python -m research.context.level_map   -> results/level_map.json
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats

from . import config as C, data as D, lib as L

TOL = 0.0005               # 0.05% of the level
SWING_DAYS = 5
PLACEBOS = 5
TYPES = ["PDH", "PDL", "PDC", "P", "R1", "S1", "R2", "S2", "SWING_HIGH", "SWING_LOW", "MAX_OI_CALL", "MAX_OI_PUT"]


def bars5(s: dict):
    o, h, l, c = s["o"], s["h"], s["l"], s["c"]
    B = []
    for k in range(75):
        sl = slice(5 * k, 5 * k + 5)
        if not np.isfinite(c[sl]).any():
            B.append(None)
            continue
        cc = c[sl][np.isfinite(c[sl])]
        oo = o[sl][np.isfinite(o[sl])]
        B.append((oo[0] if len(oo) else cc[0], np.nanmax(h[sl]), np.nanmin(l[sl]), cc[-1]))
    return B


def swings(prev: list[dict]) -> tuple[list[float], list[float]]:
    """5-minute fractal highs / lows (a bar's high above the 2 bars each side) of the previous sessions."""
    hs, ls = [], []
    for s in prev:
        b = [x for x in bars5(s) if x is not None]
        for i in range(2, len(b) - 2):
            if b[i][1] > max(b[j][1] for j in (i - 2, i - 1, i + 1, i + 2)):
                hs.append(b[i][1])
            if b[i][2] < min(b[j][2] for j in (i - 2, i - 1, i + 1, i + 2)):
                ls.append(b[i][2])
    return hs, ls


def levels(prev: list[dict], s: dict) -> dict[str, tuple[float, int]]:
    """Each level type for session s: (price, first 5-minute bar it may be used from). Only earlier data."""
    p = prev[-1]
    H, Lo = np.nanmax(p["h"]), np.nanmin(p["l"])
    Cl = p["c"][np.isfinite(p["c"])][-1]
    P = (H + Lo + Cl) / 3
    out = {"PDH": (H, 0), "PDL": (Lo, 0), "PDC": (Cl, 0), "P": (P, 0), "R1": (2 * P - Lo, 0), "S1": (2 * P - H, 0),
           "R2": (P + (H - Lo), 0), "S2": (P - (H - Lo), 0)}
    op = s["o"][np.isfinite(s["o"])][0]
    hs, ls = swings(prev[-SWING_DAYS:])
    up = [x for x in hs if x > op]
    dn = [x for x in ls if x < op]
    if up:
        out["SWING_HIGH"] = (min(up), 0)
    if dn:
        out["SWING_LOW"] = (max(dn), 0)
    oi = s.get("oi_open") or {}
    for side, key in (("C", "MAX_OI_CALL"), ("P", "MAX_OI_PUT")):
        x = {k: v for (k, sd), v in oi.items() if sd == side}
        if len(x) >= 10:
            out[key] = (max(x, key=x.get), 2)          # the 09:20 OI is known after 09:21: used from the 09:25 bar
    return out


def test(B: list, level: float, start: int) -> tuple[bool, bool]:
    """(tested, held) on the session's 5-minute bars from bar [start] - the rules in PREREG.md I12."""
    prior = None
    for k in range(start - 1, -1, -1):
        if B[k] is not None:
            prior = B[k][3]
            break
    first = next((k for k in range(start, len(B)) if B[k] is not None), None)
    if first is None:
        return False, False
    if prior is None:
        prior = B[first][0]
    tol = level * TOL
    resist = prior < level
    touch = None
    for k in range(first, len(B)):
        b = B[k]
        if b is None:
            continue
        if (resist and b[1] >= level - tol) or (not resist and b[2] <= level + tol):
            touch = k
            break
    if touch is None:
        return False, False
    for k in range(touch, len(B)):
        b = B[k]
        if b is None:
            continue
        if (resist and b[3] > level + tol) or (not resist and b[3] < level - tol):
            return True, False
    return True, True


def run():
    rng = np.random.default_rng(13)
    out, ps = {}, {}
    for und in C.UNDS:
        ss = D.load(und)
        d = D.daily(ss)
        hold, h0 = L.split(d)
        hset = set(d.day[hold])
        rows = []
        for i in range(SWING_DAYS, len(ss)):
            s = ss[i]
            B = bars5(s)
            for typ, (lv, start) in levels(ss[:i], s).items():
                t, hd = test(B, lv, start)
                rows.append(dict(day=s["day"], type=typ, placebo=False, tested=t, held=hd, hold=s["day"] in hset))
                for _ in range(PLACEBOS):
                    sh = lv * (1 + rng.choice([-1, 1]) * rng.uniform(0.003, 0.007))
                    t, hd = test(B, sh, start)
                    rows.append(dict(day=s["day"], type=typ, placebo=True, tested=t, held=hd, hold=s["day"] in hset))
        r = pd.DataFrame(rows)
        res = {}
        for typ in TYPES:
            g = r[r.type == typ]
            x = {}
            for part, sel in (("design", ~g.hold), ("holdout", g.hold), ("all", g.hold | ~g.hold)):
                a = g[sel & ~g.placebo & g.tested]
                b = g[sel & g.placebo & g.tested]
                n1, k1, n2, k2 = len(a), int(a.held.sum()), len(b), int(b.held.sum())
                p = float("nan")
                if n1 >= 10 and n2 >= 10:
                    pp = (k1 + k2) / (n1 + n2)
                    se = np.sqrt(pp * (1 - pp) * (1 / n1 + 1 / n2))
                    p = float(stats.norm.sf((k1 / n1 - k2 / n2) / se)) if se > 0 else float("nan")
                x[part] = dict(days=int((sel & ~g.placebo).sum()), tested=n1, held=k1,
                               held_pct=round(k1 / n1 * 100, 1) if n1 else None,
                               placebo_tested=n2, placebo_held_pct=round(k2 / n2 * 100, 1) if n2 else None, p=p)
            res[typ] = x
            ps[f"{und}:{typ}"] = x["design"]["p"]
        out[und] = res
    q = L.bh(ps)
    out["bh"] = {k: dict(p=ps[k], q=q[k]) for k in ps}
    out["confirmed"] = {k: out[k.split(":")[0]][k.split(":")[1]]["holdout"]["p"] < 0.10 for k, v in out["bh"].items() if v["q"] < C.BH_Q}
    L.save("level_map.json", out)
    return out


if __name__ == "__main__":
    r = run()
    for u in C.UNDS:
        for t, x in r[u].items():
            print(u, t, {k: (v["tested"], v["held_pct"], v["placebo_held_pct"]) for k, v in x.items()}, round(r["bh"][f"{u}:{t}"]["q"], 3))
    print(r["confirmed"])
