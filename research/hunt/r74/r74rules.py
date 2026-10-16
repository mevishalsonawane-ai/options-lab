"""R74 rules (PREREG.md section 3). Signal source: the INDEX, with India VIX and the 59 large caps as context.

Input: a `Ctx`: R72's five-index `Panel` (each index's continuous 5-minute bars, its Donchian-20 channel, day open),
the VIX 5-minute closes {day: (closes (75,), first print)} and the stocks {day: (share above VWAP (75,), volume (75,),
n)} from r74data.py. Output of every rule: {und: [(i, side, inval)]}, i that index's flat bar whose CLOSE gives the
signal (fill at the next 5-minute row), side +1 call / -1 put, inval the index stop of exit set XI.

Every value at bar i is computed from that index's bars 0..i and from the VIX / stock values of bars <= b_i of the same
day (and earlier days) only; test_r74.py's look-ahead guard proves it. Pure: numpy only.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r72"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r71"))
from r71rules import WARM  # noqa: E402
from r72rules import Panel  # noqa: E402,F401

LARGE = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")     # B1 / B2: the large-cap indices (not MIDCPNIFTY)
M1_BAR = 65                                              # the bar closing at 14:45
V1_BARS = (14, 26, 38, 50)                               # closes at 10:30, 11:30, 12:30, 13:30
V1_VIX = 0.02                                            # VIX +-2% since its first print
LOOK = 6                                                 # V2 / B1: the last 6 bars (30 minutes)
N_DAYS = 20                                              # M1 / B2: the previous 20 sessions


class Ctx:
    def __init__(self, panel, vix: dict, stocks: dict):
        self.P, self.V, self.S = panel, vix, stocks
        self._vmean = None

    def vol_mean(self) -> dict:
        """B2: {day: mean stock volume per bar over the previous N_DAYS sessions with stock data (75,)}; days with fewer
        than N_DAYS earlier sessions are left out."""
        if self._vmean is None:
            days = sorted(self.S)
            out = {}
            for k in range(N_DAYS, len(days)):
                out[days[k]] = np.mean([self.S[days[j]][1] for j in range(k - N_DAYS, k)], axis=0)
            self._vmean = out
        return self._vmean


def _out(unds):
    return {u: [] for u in unds}


def _day_index(F):
    """Per index: the flat bar of each day's start, the day's last close, and its high-low range through bar M1_BAR."""
    d, b, h, l, c = F["d"], F["b"], F["h"], F["l"], F["c"]
    starts = [0] + [i for i in range(1, len(d)) if d[i] != d[i - 1]]
    ends = starts[1:] + [len(d)]
    last = np.array([c[e - 1] for e in ends])
    rng = np.full(len(starts), np.nan)
    for k, (s, e) in enumerate(zip(starts, ends)):
        m = b[s:e] <= M1_BAR
        if m.any():
            rng[k] = h[s:e][m].max() - l[s:e][m].min()
    return np.array(starts), last, rng


# ------------------------------------------------------------------------------------------------ M1 late-day momentum
def late_momentum(X: Ctx, k=0.5):
    P = X.P
    out = _out(P.unds)
    for u in P.unds:
        F = P.f[u]
        starts, last, rng = _day_index(F)
        for kd, s in enumerate(starts):
            if kd < N_DAYS + 1:
                continue
            e = starts[kd + 1] if kd + 1 < len(starts) else len(F["d"])
            hit = np.flatnonzero(F["b"][s:e] == M1_BAR)
            if not len(hit):
                continue
            i = s + int(hit[0])
            if i < WARM:
                continue
            prev = last[kd - 1]
            r = F["c"][i] / prev - 1.0
            past = last[kd - N_DAYS - 1:kd]
            sigma = float(np.mean(np.abs(past[1:] / past[:-1] - 1.0)))
            med = np.nanmedian(rng[kd - N_DAYS:kd])
            today = F["h"][s:i + 1].max() - F["l"][s:i + 1].min()
            if not (np.isfinite(sigma) and np.isfinite(med)) or sigma <= 0:
                continue
            if abs(r) >= k * sigma and today >= med and r != 0:
                out[u].append((int(i), 1 if r > 0 else -1, float(prev)))
    return out


# ------------------------------------------------------------------------------------------------ V1 VIX divergence
def vix_divergence(X: Ctx, k=0.002):
    P = X.P
    out = _out(P.unds)
    for u in P.unds:
        F = P.f[u]
        done = set()
        for i in range(WARM, len(F["c"])):
            b, d = int(F["b"][i]), F["d"][i]
            if b not in V1_BARS or d in done or d not in X.V:
                continue
            vc, v0 = X.V[d]
            if not (np.isfinite(vc[b]) and np.isfinite(v0)) or v0 <= 0:
                continue
            ri = F["c"][i] / F["dopen"][i] - 1.0
            rv = vc[b] / v0 - 1.0
            s = F["ds"][i]
            if ri >= k and rv >= V1_VIX:
                out[u].append((int(i), -1, float(F["h"][s:i + 1].max())))
                done.add(d)
            elif ri <= -k and rv <= -V1_VIX:
                out[u].append((int(i), 1, float(F["l"][s:i + 1].min())))
                done.add(d)
    return out


# ------------------------------------------------------------------------------------------------ V2 VIX-confirmed break
def vix_break(X: Ctx, x=0.0):
    P = X.P
    out = _out(P.unds)
    for u in P.unds:
        F = P.f[u]
        for i in range(WARM, len(F["c"])):
            b, d = int(F["b"][i]), F["d"][i]
            if b < LOOK or d not in X.V or not (F["up"][i] or F["dn"][i]):
                continue
            vc = X.V[d][0]
            if not (np.isfinite(vc[b]) and np.isfinite(vc[b - LOOK])) or vc[b - LOOK] <= 0:
                continue
            dv = vc[b] / vc[b - LOOK] - 1.0
            if F["up"][i] and dv < -x:
                out[u].append((int(i), 1, float(F["mid"][i])))
            elif F["dn"][i] and dv > x:
                out[u].append((int(i), -1, float(F["mid"][i])))
    return out


# ------------------------------------------------------------------------------------------------ B1 stock-breadth thrust
def breadth_thrust(X: Ctx, T=0.7):
    P = X.P
    out = _out(P.unds)
    for u in P.unds:
        if u not in LARGE:
            continue
        F = P.f[u]
        done = set()
        for i in range(WARM, len(F["c"])):
            b, d = int(F["b"][i]), F["d"][i]
            if b < LOOK or d not in X.S:
                continue
            sh = X.S[d][0]
            now, prev = sh[b], sh[b - LOOK:b]
            if not np.isfinite(now) or not np.isfinite(prev).any():
                continue
            s0 = F["ds"][i]
            if (d, 1) not in done and now >= T and np.nanmin(prev) <= 1.0 - T:
                out[u].append((int(i), 1, float(F["l"][s0:i + 1].min())))
                done.add((d, 1))
            elif (d, -1) not in done and now <= 1.0 - T and np.nanmax(prev) >= T:
                out[u].append((int(i), -1, float(F["h"][s0:i + 1].max())))
                done.add((d, -1))
    return out


# ------------------------------------------------------------------------------------------------ B2 volume-confirmed break
def volume_break(X: Ctx, m=1.5):
    P = X.P
    out = _out(P.unds)
    vm = X.vol_mean()
    for u in P.unds:
        if u not in LARGE:
            continue
        F = P.f[u]
        for i in range(WARM, len(F["c"])):
            b, d = int(F["b"][i]), F["d"][i]
            if not (F["up"][i] or F["dn"][i]) or d not in vm or d not in X.S:
                continue
            mean = vm[d][b]
            if not (mean > 0 and X.S[d][1][b] >= m * mean):
                continue
            out[u].append((int(i), 1 if F["up"][i] else -1, float(F["mid"][i])))
    return out


# ------------------------------------------------------------------------------------------------ the family
RULES = {   # id: (function, param name, original, grid) - PREREG section 3
    "M1": (late_momentum, "k", 0.5, (0.5, 1.0)),
    "V1": (vix_divergence, "k", 0.002, (0.002, 0.004)),
    "V2": (vix_break, "x", 0.0, (0.0, 0.01)),
    "B1": (breadth_thrust, "T", 0.7, (0.7, 0.8)),
    "B2": (volume_break, "m", 1.5, (1.5, 2.0)),
}
NAMES = {"M1": "Late-day momentum on high-volatility days", "V1": "VIX / index divergence, faded",
         "V2": "VIX-confirmed Donchian break", "B1": "Stock-breadth thrust (VWAP)",
         "B2": "Volume-confirmed Donchian break"}


def signals_all(rule: str, param, X: Ctx) -> dict:
    fn, name, _, _ = RULES[rule]
    out = fn(X, **{name: param})
    return {u: sorted(set(v)) for u, v in out.items()}
