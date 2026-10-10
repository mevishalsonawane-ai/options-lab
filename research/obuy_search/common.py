"""Shared helpers for the OBUY search scripts (rule -> trades on a build file)."""
from __future__ import annotations

import numpy as np

WINDOWS = {"0920-1015": (0, 11), "1015-1200": (11, 32), "1200-1430": (32, 63), "0920-1430": (0, 63)}


def rule_trades(z, atom_names, dr, window, strike, exit_k):
    """Trades of one strategy on build file z: (day index, slot, net Rs P&L of 1 lot, premium) - valid trades only."""
    atoms = list(z["atoms"])
    feat = z["feat"]
    a, b = WINDOWS[window]
    ok = np.ones(feat.shape[:2], dtype=bool)
    for nm in atom_names:
        ok &= feat[:, :, dr, atoms.index(nm)]
    ok[:, :a] = False
    ok[:, b:] = False
    days = np.nonzero(ok.any(1))[0]
    slot = ok[days].argmax(1)
    p = z["pnl"][days, slot, dr, strike, exit_k]
    pr = z["prem"][days, slot, dr, strike]
    v = ~np.isnan(p)
    return days[v], slot[v], p[v].astype(np.float64), pr[v].astype(np.float64)


def max_dd(eq, start):
    """max drawdown of an equity path (start + cumulative P&L) as a fraction of the running peak."""
    path = start + np.concatenate([[0.0], np.cumsum(eq)])
    peak = np.maximum.accumulate(path)
    return float(((peak - path) / peak).max())


def stationary_boot_idx(rng, n_src, n_out, block):
    idx = np.empty(n_out, dtype=np.int64)
    i = rng.integers(n_src)
    for k in range(n_out):
        if k > 0:
            i = rng.integers(n_src) if rng.random() < 1.0 / block else (i + 1) % n_src
        idx[k] = i
    return idx


def monte_carlo(daily, rng, capital=500_000.0, n_days=250, sims=5000, block=5):
    """Bootstrapped one-year P&L on a daily series (0 = no trade). Returns dict of probabilities / EV."""
    daily = np.asarray(daily, dtype=np.float64)
    tot = np.empty(sims)
    dd = np.empty(sims)
    for s in range(sims):
        x = daily[stationary_boot_idx(rng, len(daily), n_days, block)]
        tot[s] = x.sum()
        dd[s] = max_dd(x, capital)
    return dict(p_profit=float((tot > 0).mean()), p_dd20=float((dd > 0.20).mean()), p_dd50=float((dd > 0.50).mean()),
                ev=float(tot.mean()), ci_lo=float(np.percentile(tot, 2.5)), ci_hi=float(np.percentile(tot, 97.5)),
                med=float(np.median(tot)))
