"""R72 cross-index rules (PREREG.md section 2). Signal source: the five INDICES read together, on the same 5-minute grid.

Input: {und: (d, b, o, h, l, c)}, each index's continuous 5-minute bars as R51's `fam_r44.bars5` builds them. Output of
every rule: {und: [(i, side, inval)]} with i that index's flat bar whose CLOSE gives the signal (fill at the next
5-minute row), side +1 call / -1 put, inval the index stop of exit set XI.

Each index's channel, EMA and ATR are computed on its own series from its bars 0..i only; the panel lines the indices up
by (day, bar of day), so a rule at row r reads every index at or before r. test_r72.py's look-ahead guard proves it.
Pure: numpy only.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r71"))
from r71rules import WARM, atr, day_starts, ema  # noqa: E402

N_CH = 20                                   # Donchian's channel: the previous 20 bars


class Panel:
    """Rows = the union of (day, bar) over the indices; per index its flat bar at each row (-1 if none) and features."""

    def __init__(self, data: dict):
        self.unds = list(data)
        keys = sorted({(d, int(b)) for (dd, bb, *_r) in data.values() for d, b in zip(dd, bb)})
        self.keys = keys
        self.day = np.array([k[0] for k in keys], dtype=object)
        self.bar = np.array([k[1] for k in keys])
        pos = {k: r for r, k in enumerate(keys)}
        R = len(keys)
        self.j = {}
        self.f = {}
        for u, (d, b, o, h, l, c) in data.items():
            jj = np.full(R, -1, np.int64)
            for i in range(len(d)):
                jj[pos[(d[i], int(b[i]))]] = i
            self.j[u] = jj
            T = len(c)
            chi, clo = np.full(T, np.nan), np.full(T, np.nan)
            for i in range(N_CH, T):
                chi[i] = h[i - N_CH:i].max()
                clo[i] = l[i - N_CH:i].min()
            ds = day_starts(d)
            lo_before = np.full(T, np.nan)            # the day's low / high before bar i (NaN on the day's first bar)
            hi_before = np.full(T, np.nan)
            for i in range(T):
                if ds[i] < i:
                    lo_before[i] = l[ds[i]:i].min()
                    hi_before[i] = h[ds[i]:i].max()
            self.f[u] = dict(d=d, b=b, o=o, h=h, l=l, c=c, chi=chi, clo=clo, mid=(chi + clo) / 2,
                             up=np.nan_to_num(c > chi) & (np.arange(T) >= N_CH),
                             dn=np.nan_to_num(c < clo) & (np.arange(T) >= N_CH),
                             ema=ema(c, 20), atr=atr(h, l, c, 14), ds=ds, dopen=o[ds],
                             lo_before=lo_before, hi_before=hi_before)
        self.rs = day_starts(self.day)              # first row of each row's day

    def ok(self, u, r) -> bool:
        j = self.j[u][r]
        return j >= WARM

    def flag(self, u, name, r) -> bool:
        j = self.j[u][r]
        return bool(j >= 0 and self.f[u][name][j])


def _out(unds):
    return {u: [] for u in unds}


# ------------------------------------------------------------------------------------------------ C1 breadth breakout
def breadth_breakout(P: Panel, M=4):
    out = _out(P.unds)
    for r in range(len(P.keys)):
        for s, name, side_inval in ((1, "up", "mid"), (-1, "dn", "mid")):
            hit = [u for u in P.unds if P.flag(u, name, r)]
            if len(hit) >= M:
                for u in hit:
                    if P.ok(u, r):
                        j = P.j[u][r]
                        out[u].append((int(j), s, float(P.f[u][side_inval][j])))
    return out


# ------------------------------------------------------------------------------------------------ C2 laggard catch-up
def _recent(P, u, name, r, K):
    lo = max(P.rs[r], r - K + 1)
    return any(P.flag(u, name, x) for x in range(lo, r + 1))


def laggard(P: Panel, K=3):
    out = _out(P.unds)
    prev = {}
    for r in range(len(P.keys)):
        new_day = P.rs[r] == r
        for X in P.unds:
            j = P.j[X][r]
            for s, name in ((1, "up"), (-1, "dn")):
                cond = False
                if j >= 0:
                    others = sum(_recent(P, u, name, r, K) for u in P.unds if u != X)
                    side_ok = P.f[X]["c"][j] > P.f[X]["mid"][j] if s > 0 else P.f[X]["c"][j] < P.f[X]["mid"][j]
                    cond = others >= 3 and not _recent(P, X, name, r, K) and bool(side_ok)
                was = prev.get((X, s), False) and not new_day
                if cond and not was and P.ok(X, r):
                    out[X].append((int(j), s, float(P.f[X]["mid"][j])))
                prev[(X, s)] = cond
    return out


# ------------------------------------------------------------------------------------------------ C3 lonely breakout fade
def lonely_fade(P: Panel, K=3):
    out = _out(P.unds)
    prev = {}
    for r in range(len(P.keys)):
        new_day = P.rs[r] == r
        for X in P.unds:
            j = P.j[X][r]
            for s_brk, name in ((1, "up"), (-1, "dn")):
                cond = j >= 0 and P.flag(X, name, r) and not any(_recent(P, u, name, r, K) for u in P.unds if u != X)
                was = prev.get((X, s_brk), False) and not new_day
                if cond and not was and P.ok(X, r):
                    inval = P.f[X]["h"][j] if s_brk > 0 else P.f[X]["l"][j]
                    out[X].append((int(j), -s_brk, float(inval)))       # the fade: the opposite side
                prev[(X, s_brk)] = cond
    return out


# ------------------------------------------------------------------------------------------------ C4 leader at a time
def leader(P: Panel, T_bar=11):
    """T_bar = the bar of day ending at T (bar 11 = 10:10-10:14 -> T 10:15; bar 23 -> 11:15)."""
    out = _out(P.unds)
    for r in np.flatnonzero(P.bar == T_bar):
        js = {u: P.j[u][r] for u in P.unds}
        if any(j < WARM for j in js.values()):
            continue
        sc = {}
        for u, j in js.items():
            F = P.f[u]
            if not np.isfinite(F["atr"][j]) or F["atr"][j] <= 0:
                break
            sc[u] = (F["c"][j] - F["dopen"][j]) / F["atr"][j]
        else:
            if all(v > 0 for v in sc.values()):
                u = max(sc, key=sc.get)
                j = js[u]
                F = P.f[u]
                out[u].append((int(j), 1, float(F["l"][F["ds"][j]:j + 1].min())))
            elif all(v < 0 for v in sc.values()):
                u = min(sc, key=sc.get)
                j = js[u]
                F = P.f[u]
                out[u].append((int(j), -1, float(F["h"][F["ds"][j]:j + 1].max())))
    return out


# ------------------------------------------------------------------------------------------------ C5 breadth thrust
def thrust(P: Panel, M=4):
    out = _out(P.unds)
    R = len(P.keys)
    above = np.zeros(R, int)
    below = np.zeros(R, int)
    for r in range(R):
        for u in P.unds:
            j = P.j[u][r]
            if j >= 0:
                above[r] += P.f[u]["c"][j] > P.f[u]["ema"][j]
                below[r] += P.f[u]["c"][j] < P.f[u]["ema"][j]
    for r in range(R):
        if r - 3 < P.rs[r]:
            continue
        for s, cnt in ((1, above), (-1, below)):
            if cnt[r] >= M and cnt[r - 3:r].min() <= 1:
                for u in P.unds:
                    j = P.j[u][r]
                    if j >= WARM and ((P.f[u]["c"][j] > P.f[u]["ema"][j]) if s > 0 else (P.f[u]["c"][j] < P.f[u]["ema"][j])):
                        out[u].append((int(j), s, float(P.f[u]["ema"][j])))
    return out


# ------------------------------------------------------------------------------------------------ C6 SMT divergence
def smt(P: Panel, R_=3, start_b=6, confirm=3, gap_atr=0.25):
    out = _out(P.unds)
    for r in range(len(P.keys)):
        if P.bar[r] < start_b:
            continue
        for X in P.unds:
            j = P.j[X][r]
            if j < WARM:
                continue
            F = P.f[X]
            for s in (1, -1):
                ext = F["lo_before"][j] if s > 0 else F["hi_before"][j]
                if not np.isfinite(ext) or not ((F["l"][j] < ext) if s > 0 else (F["h"][j] > ext)):
                    continue
                hold = 0
                for u in P.unds:
                    if u == X:
                        continue
                    ju = P.j[u][r]
                    if ju < 0:
                        continue
                    G = P.f[u]
                    a = G["atr"][ju]
                    if s > 0:
                        e = G["lo_before"][ju]
                        hold += bool(np.isfinite(e) and np.isfinite(a) and G["l"][ju] > e + gap_atr * a)
                    else:
                        e = G["hi_before"][ju]
                        hold += bool(np.isfinite(e) and np.isfinite(a) and G["h"][ju] < e - gap_atr * a)
                if hold < R_:
                    continue
                for k in range(j + 1, min(j + 1 + confirm, len(F["c"]))):
                    if F["d"][k] != F["d"][j]:
                        break
                    if (F["l"][k] < F["l"][j]) if s > 0 else (F["h"][k] > F["h"][j]):
                        break
                    if (F["c"][k] > F["h"][j]) if s > 0 else (F["c"][k] < F["l"][j]):
                        out[X].append((int(k), s, float(F["l"][j] if s > 0 else F["h"][j])))
                        break
    return out


# ------------------------------------------------------------------------------------------------ the family
RULES = {   # id: (function, param name, original, grid) - PREREG section 2
    "C1": (breadth_breakout, "M", 4, (4, 5)),
    "C2": (laggard, "K", 3, (3, 6)),
    "C3": (lonely_fade, "K", 3, (3, 6)),
    "C4": (leader, "T_bar", 11, (11, 23)),
    "C5": (thrust, "M", 4, (4, 5)),
    "C6": (smt, "R_", 3, (3, 4)),
}
NAMES = {"C1": "Breadth breakout", "C2": "Laggard catch-up", "C3": "Lonely breakout fade",
         "C4": "Leader at a fixed time", "C5": "Breadth thrust", "C6": "Divergence at the day's extreme (SMT)"}


def signals_all(rule: str, param, P: Panel) -> dict:
    fn, name, _, _ = RULES[rule]
    out = fn(P, **{name: param})
    return {u: sorted(set(v)) for u, v in out.items()}
