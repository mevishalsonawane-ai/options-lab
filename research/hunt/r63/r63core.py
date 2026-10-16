"""R63 core for strategies priced by R51's engine (Liquidity, the passed arms, the Solos).

Import AFTER the strategy's own settings module (r51cfg with R51_CLEAN=1, or r57cfg), so `engine` / `r51data` read that
study's store. `record()` wraps engine.run_day so every plain-rules trade carries the entry row it was simulated from
(`e_sig`) and the strategy's own forced exit row (`fx_own`); `resim()` prices the same trades again with another forced
row, exactly as run_day would; `apply_rules()` adds each trend rule and draws the random early-exit twins.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import engine as E
import r51cfg as K51
import r51data
import r63cfg as K
import r63data
import r63rules as RR

_ORIG_RUN_DAY = E.run_day


def _run_day_rec(und, D, sides, strikes, entries, ex, forced=None):
    out = _ORIG_RUN_DAY(und, D, sides, strikes, entries, ex, forced)
    fx = [-1] * len(entries) if forced is None else list(forced)
    for i, o in enumerate(out):
        if o is not None:
            o["e_sig"] = int(entries[i])
            o["fx_own"] = int(fx[i])
    return out


def record():
    E.run_day = _run_day_rec


combine = RR.combine


def resim(T: pd.DataFrame, fx: np.ndarray, exits: dict) -> tuple:
    """Price trades T again with forced rows fx (one per row). exits: exit key -> engine.Exits (T.exk). Returns
    (net, xr, why) arrays. Identical to engine.run_day for the same inputs."""
    n = len(T)
    net, xr, why = np.full(n, np.nan), np.full(n, -1, np.int64), np.empty(n, object)
    if not n:
        return net, xr, why
    for (und, d, k), g in T.groupby(["und", "day", "exk"], sort=False):
        ii = g.index.to_numpy()
        pos = T.index.get_indexer(ii)
        D = r51data.load(und)[d]
        ex = exits[k]
        st = E.stack(und, D)
        ci = np.array([st.idx(s, kk) for s, kk in zip(g.side, g.strike)], dtype=np.int64)
        qty = float(K51.lot(und, d))
        res, er, x, rs = E.simulate_many(st.S, ci, g.e_sig.to_numpy(np.int64), E.sq_row(D, ex), fx[pos].astype(np.int64),
                                         ex.stop, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(),
                                         ex.tmax, ex.need, ex.close_stop, K51.TICK, qty, *E.SLIP)
        rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        ch = E.charges(und, rE, rX, qty, d)
        net[pos] = np.where(rs != E.R_NOFILL, (rX - rE) * qty - ch, np.nan)
        xr[pos] = x
        why[pos] = [E.REASONS[int(r)] for r in rs]
    return net, xr, why


def twin_impr(T: pd.DataFrame, chg: np.ndarray, exits: dict, n: int, rng) -> np.ndarray:
    """(n,) twin improvements: each changed trade cut at a uniform row in [fill + 5, plain exit - 1] (own exits first)."""
    tot = np.zeros(n)
    C = T[chg]
    if not len(C):
        return tot
    for (und, d, k), g in C.groupby(["und", "day", "exk"], sort=False):
        D = r51data.load(und)[d]
        ex = exits[k]
        st = E.stack(und, D)
        m = len(g)
        lo = g.er.to_numpy(np.int64) + RR.MIN_OPEN
        hi = g.xr.to_numpy(np.int64) - 1
        rows = rng.integers(lo, np.maximum(hi, lo) + 1, size=(n, m))
        own = g.fx_own.to_numpy(np.int64)
        fx = np.where(own[None, :] >= 0, np.minimum(own[None, :], rows), rows).ravel()
        ci = np.tile(np.array([st.idx(s, kk) for s, kk in zip(g.side, g.strike)], dtype=np.int64), n)
        ent = np.tile(g.e_sig.to_numpy(np.int64), n)
        qty = float(K51.lot(und, d))
        res, er, x, rs = E.simulate_many(st.S, ci, ent, E.sq_row(D, ex), fx.astype(np.int64), ex.stop, ex.target,
                                         ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need,
                                         ex.close_stop, K51.TICK, qty, *E.SLIP)
        rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        tw = np.where(rs != E.R_NOFILL, (rX - rE) * qty - E.charges(und, rE, rX, qty, d), 0.0).reshape(n, m)
        tot += (tw - g.net.to_numpy()[None, :]).sum(axis=1)
    return tot


def apply_rules(T: pd.DataFrame, exits: dict, strat: str, period: str) -> tuple:
    """Adds, per rule, columns x_<R> (trend act row or -1), net_<R>, xr_<R>, why_<R>, chg_<R>; returns (T, twins) with
    twins {rule: (TWINS,) improvements}. T needs und, day, side, strike, er (fill row), xr, net, e_sig, fx_own, exk."""
    T = T.reset_index(drop=True)
    twins = {}
    if not len(T):
        return T, {r: np.zeros(K.TWINS) for r in RR.RULES}
    tr = {u: r63data.turns(u) for u in T.und.unique()}
    base_net, _, _ = resim(T, T.fx_own.to_numpy(np.int64), exits)
    if not np.allclose(np.nan_to_num(base_net), T.net.to_numpy(), atol=0.011):
        raise AssertionError(f"R63 {strat}: re-pricing the plain trades does not reproduce them")
    for j, rule in enumerate(RR.RULES):
        x = np.array([r63data.trend_row(tr[u], rule, d, s, int(e)) for u, d, s, e in zip(T.und, T.day, T.side, T.er)],
                     np.int64)
        fx = np.array([combine(int(o), int(v)) for o, v in zip(T.fx_own, x)], np.int64)
        net, xr, why = resim(T, fx, exits)
        T[f"x_{rule}"] = x
        T[f"net_{rule}"] = np.where(np.isfinite(net), net, T.net)
        T[f"xr_{rule}"] = xr
        T[f"why_{rule}"] = why
        T[f"chg_{rule}"] = (xr != T.xr.to_numpy()) & (x >= 0)
        rng = np.random.default_rng([K.SEED, K.STRATS.index(strat), j, 0 if period == "design" else 1])
        twins[rule] = twin_impr(T, T[f"chg_{rule}"].to_numpy(), exits, K.TWINS, rng)
    return T, twins
