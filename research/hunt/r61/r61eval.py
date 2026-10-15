"""R61 reference evaluator, pure Python (no numpy, no file, clock or network): the spec a Kotlin port is
parity-tested against.

- score / take: the exported model JSON (R57's format; R61's L1 logistic is a "logit" too), from r57eval unchanged.
- One-snapshot formulas for R61's chain features, the same definitions as the vectorised research code in r61chain.py
  (test_r61.py checks they agree): Black-Scholes Greeks, max pain, OI walls, PCR, build-up code, net / gross GEX,
  gamma-flip distance, synthetic basis.

A chain snapshot here is two lists of slots, calls and puts, each slot (strike, oi, iv_pct, close) or None for an
empty slot.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r57"))
from r57eval import score, take  # noqa: E402,F401

RATE = 0.065
YEAR_SESSIONS = 252.0
NROWS = 375


def _ncdf(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def years_left(dte_sessions: float, minute: int) -> float:
    """T in years at a minute (0 = 09:15) with `dte_sessions` sessions to expiry (0 on the expiry day)."""
    return (dte_sessions + (NROWS - minute) / NROWS) / YEAR_SESSIONS


def greeks(S: float, K: float, iv_pct: float, T: float, call: bool, r: float = RATE):
    """(delta, gamma, theta per session, vega per IV point), or None when an input is not positive."""
    if not (S > 0 and K > 0 and iv_pct > 0 and T > 0):
        return None
    s = iv_pct / 100.0
    sq = math.sqrt(T)
    d1 = (math.log(S / K) + (r + 0.5 * s * s) * T) / (s * sq)
    d2 = d1 - s * sq
    pdf = math.exp(-0.5 * d1 * d1) / math.sqrt(2 * math.pi)
    disc = math.exp(-r * T)
    delta = _ncdf(d1) if call else _ncdf(d1) - 1.0
    gamma = pdf / (S * s * sq)
    if call:
        th = -S * pdf * s / (2 * sq) - r * K * disc * _ncdf(d2)
    else:
        th = -S * pdf * s / (2 * sq) + r * K * disc * _ncdf(-d2)
    return delta, gamma, th / YEAR_SESSIONS, S * pdf * sq / 100.0


def held_greeks(S: float, K: float, iv_pct: float, premium: float, T: float, call: bool) -> dict | None:
    """The GK block: delta_abs, gamma_1pct, theta_pct_day, vega_pct."""
    g = greeks(S, K, iv_pct, T, call)
    if g is None or not premium > 0:
        return None
    d, ga, th, ve = g
    return dict(delta_abs=abs(d), gamma_1pct=ga * S * 0.01, theta_pct_day=th / premium * 100,
                vega_pct=ve / premium * 100)


def max_pain(calls: list, puts: list):
    """Strike (among the chain's) minimising sum OI_C x max(X - K, 0) + OI_P x max(K - X, 0); ties: lowest strike."""
    cs = [(k, oi or 0.0) for k, oi, *_ in (x for x in calls if x)]
    ps = [(k, oi or 0.0) for k, oi, *_ in (x for x in puts if x)]
    if sum(o for _, o in cs) + sum(o for _, o in ps) <= 0:
        return None
    best = None
    for X in sorted({k for k, _ in cs} | {k for k, _ in ps}):
        pain = sum(o * max(X - k, 0.0) for k, o in cs) + sum(o * max(k - X, 0.0) for k, o in ps)
        if best is None or pain < best[0]:
            best = (pain, X)
    return best[1]


def walls(S: float, calls: list, puts: list):
    """(% to the biggest call-OI strike at or above S, % to the biggest put-OI strike at or below S); ties: nearest S."""
    def pick(slots, above):
        el = [(oi, -abs(k - S), k) for k, oi, *_ in (x for x in slots if x)
              if oi is not None and oi > 0 and (k >= S if above else k <= S)]
        if not el:
            return None
        k = max(el)[2]
        return (k - S) / S * 100 if above else (S - k) / S * 100
    return pick(calls, True), pick(puts, False)


def pcr(put_total: float, call_total: float):
    return put_total / call_total if call_total and call_total > 0 else None


def buildup_code(dp: float, doi: float) -> int:
    if dp > 0 and doi > 0:
        return 2
    if dp > 0 and doi < 0:
        return 1
    if dp < 0 and doi < 0:
        return -1
    if dp < 0 and doi > 0:
        return -2
    return 0


def net_gex(S: float, calls: list, puts: list, T: float):
    """(net, gross) gamma exposure: sum gamma x OI x S^2 x 0.01, calls +, puts -."""
    c = p = 0.0
    for slots, sgn in ((calls, 1), (puts, -1)):
        for x in slots:
            if not x:
                continue
            k, oi, iv = x[0], x[1], x[2]
            g = greeks(S, k, iv, T, True) if (iv is not None and oi is not None) else None
            if g is None:
                continue
            w = g[1] * oi
            if sgn > 0:
                c += w
            else:
                p += w
    s2 = S * S * 0.01
    return (c - p) * s2, (c + p) * s2


def flip_distance(S: float, calls: list, puts: list, T: float, grid: float = 0.03, step: float = 0.001):
    """% from S to the nearest sign change of net GEX re-priced at S x (1 + x), x in -grid..+grid; None if none."""
    n = int(round(2 * grid / step))
    xs = [round(-grid + i * step, 6) for i in range(n + 1)]
    v = [net_gex(S * (1 + x), calls, puts, T)[0] for x in xs]
    best = None
    for i in range(n):
        a, b = v[i], v[i + 1]
        if a == 0 and b == 0:
            continue
        if a == 0:
            x0 = xs[i]
        elif a * b < 0:
            x0 = xs[i] + (xs[i + 1] - xs[i]) * a / (a - b)
        else:
            continue
        if best is None or abs(x0) < abs(best):
            best = x0
    return None if best is None else best * 100.0


def synth_basis_bps(S: float, K: float, call_px: float, put_px: float):
    if not (S > 0 and call_px > 0 and put_px > 0):
        return None
    return (K + call_px - put_px - S) / S * 1e4
