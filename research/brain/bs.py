"""Black-Scholes (European, continuous r and q) prices, implied volatility and Greeks, vectorised with numpy.

NSE index options are European and cash-settled, so Black-Scholes on spot with a flat r and q (config.py) is the
standard approximation. Implied volatility is solved by safeguarded Newton (bisection when a Newton step leaves the
bracket). A price outside the no-arbitrage band gives NaN, never a clipped value.
"""
from __future__ import annotations

import numpy as np
from scipy.special import ndtr

SQ2PI = np.sqrt(2 * np.pi)


def _d1d2(S, K, T, sig, r, q):
    v = sig * np.sqrt(T)
    d1 = (np.log(S / K) + (r - q + 0.5 * sig * sig) * T) / v
    return d1, d1 - v


def price(S, K, T, sig, is_call, r=0.0, q=0.0):
    S, K, T, sig = (np.asarray(a, float) for a in (S, K, T, sig))
    d1, d2 = _d1d2(S, K, T, sig, r, q)
    dq, dr = np.exp(-q * T), np.exp(-r * T)
    call = S * dq * ndtr(d1) - K * dr * ndtr(d2)
    put = K * dr * ndtr(-d2) - S * dq * ndtr(-d1)
    return np.where(is_call, call, put)


def implied_vol(P, S, K, T, is_call, r=0.0, q=0.0, lo=0.01, hi=3.0, iters=100, tol=1e-9, min_vega=0.0):
    """IV per row; NaN where T <= 0, the price is outside [intrinsic, upper bound] at lo..hi, no convergence, or the IV
    is not identifiable: vega per 1 IV point below min_vega (e.g. one price tick), as deep in the money near expiry."""
    P, S, K, T = (np.asarray(a, float) for a in (P, S, K, T))
    is_call = np.asarray(is_call, bool)
    out = np.full(P.shape, np.nan)
    good = np.isfinite(P) & np.isfinite(S) & np.isfinite(K) & np.isfinite(T) & (T > 0) & (P > 0) & (S > 0) & (K > 0)
    if not good.any():
        return out
    P, S, K, T, c = P[good], S[good], K[good], T[good], is_call[good]
    plo = price(S, K, T, np.full_like(P, lo), c, r, q)
    phi = price(S, K, T, np.full_like(P, hi), c, r, q)
    ok = (P >= plo - 1e-9) & (P <= phi + 1e-9)
    a, b = np.full_like(P, lo), np.full_like(P, hi)
    x = np.full_like(P, 0.2)
    done = ~ok
    for _ in range(iters):
        f = price(S, K, T, x, c, r, q) - P
        conv = (np.abs(f) < tol * P) | (b - a < 1e-10)
        done |= conv
        if done.all():
            break
        a = np.where(f < 0, x, a)
        b = np.where(f > 0, x, b)
        d1, _ = _d1d2(S, K, T, x, r, q)
        vega = S * np.exp(-q * T) * np.exp(-0.5 * d1 * d1) / SQ2PI * np.sqrt(T)
        with np.errstate(divide="ignore", invalid="ignore", over="ignore"):
            xn = x - f / vega
        bad = ~np.isfinite(xn) | (xn <= a) | (xn >= b)
        xn = np.where(bad, 0.5 * (a + b), xn)
        x = np.where(done, x, xn)
    f = price(S, K, T, x, c, r, q) - P
    d1, _ = _d1d2(S, K, T, x, r, q)
    vega_pt = S * np.exp(-q * T) * np.exp(-0.5 * d1 * d1) / SQ2PI * np.sqrt(T) / 100
    res = np.where(ok & ((np.abs(f) < 1e-6 * P) | (b - a < 1e-8)) & (vega_pt >= min_vega), x, np.nan)
    out[good] = res
    return out


def greeks(S, K, T, sig, is_call, r=0.0, q=0.0):
    """delta, gamma (per 1 point), theta (per calendar day, price units), vega (per 1 IV point = 0.01)."""
    S, K, T, sig = (np.asarray(a, float) for a in (S, K, T, sig))
    with np.errstate(divide="ignore", invalid="ignore"):
        d1, d2 = _d1d2(S, K, T, sig, r, q)
        dq, dr = np.exp(-q * T), np.exp(-r * T)
        pdf = np.exp(-0.5 * d1 * d1) / SQ2PI
        delta = np.where(is_call, dq * ndtr(d1), dq * (ndtr(d1) - 1))
        gamma = dq * pdf / (S * sig * np.sqrt(T))
        vega = S * dq * pdf * np.sqrt(T) / 100
        common = -S * dq * pdf * sig / (2 * np.sqrt(T))
        th_c = common - r * K * dr * ndtr(d2) + q * S * dq * ndtr(d1)
        th_p = common + r * K * dr * ndtr(-d2) - q * S * dq * ndtr(-d1)
        theta = np.where(is_call, th_c, th_p) / 365
    return delta, gamma, theta, vega
