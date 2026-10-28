"""R124 part C rules, twins and statistics (PREREG section 6). Frames carry the network's p_up{h}, p_dn{h} and the
simulated index-exit trades tr{h}_{C,P,S}_{net,netx2,exit,why}."""
from __future__ import annotations

import numpy as np
import pandas as pd

import r124cfg as K
import r123rules as R3

WHY_CLS = {"target": 0, "stop": 1, "time": 2, "touch": 2}


def top_threshold(p_train: np.ndarray) -> float:
    """The 90th percentile of P(move) on the TRAINING rows (never test rows)."""
    p = np.asarray(p_train, dtype=float)
    return float(np.nanquantile(p, K.TOP_Q))


def candidates(F: pd.DataFrame, rule: str, thr_move: float) -> pd.DataFrame:
    R = K.RULES[rule]
    h = R["h"]
    pu, pdn = F[f"p_up{h}"].values, F[f"p_dn{h}"].values
    pm = pu + pdn
    ok = pm >= thr_move
    if R["rows"] is not None:
        ok &= F.row.isin(R["rows"]).values
    if R["kind"] == "side":
        with np.errstate(all="ignore"):
            share = pu / pm
        side = np.where(share >= K.DIR_SHARE, "C", np.where(share <= 1 - K.DIR_SHARE, "P", ""))
        ok &= side != ""
    else:
        side = np.full(len(F), "S")
    cols = {}
    for k in ("net", "netx2", "exit", "why"):
        cols[k] = np.array([F[f"tr{h}_{s}_{k}"].values[n] if s else np.nan for n, s in enumerate(side)], dtype=object)
    net = cols["net"].astype(float)
    ok &= np.isfinite(net)
    C = pd.DataFrame(dict(und=F.und.values[ok], day=F.day.values[ok], row=F.row.values[ok], side=side[ok],
                          score=pm[ok], net=net[ok], netx2=cols["netx2"][ok].astype(float),
                          exit=cols["exit"][ok].astype(int), why=cols["why"][ok]))
    C["cls"] = C.why.map(WHY_CLS).fillna(2).astype(int)
    C["j"] = np.where(C.side == "P", 1, 0)
    return C


def trades(F: pd.DataFrame, rule: str, thr_move: float) -> pd.DataFrame:
    return R3.portfolio(candidates(F, rule, thr_move), day_max=K.DAY_MAX)


def twin_pools(F: pd.DataFrame) -> dict:
    pools = {}
    for h in K.HS:
        for s in ("C", "P", "S"):
            n = F[f"tr{h}_{s}_net"].values
            ok = np.isfinite(n)
            sub = pd.DataFrame(dict(und=F.und.values[ok], day=F.day.values[ok], net=n[ok]))
            for (u, d), g in sub.groupby(["und", "day"]):
                pools[(u, d, h, s)] = g.net.values
    return pools


def twins(T: pd.DataFrame, pools: dict, rule: str, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Random-entry twins with the same exits: per trade, a random row of the same index and day where the trade is
    defined; a random side (M4: the straddle)."""
    rng = np.random.default_rng(seed)
    h = K.RULES[rule]["h"]
    strad = K.RULES[rule]["kind"] == "straddle"
    tot = np.zeros(n)
    for u, d in zip(T.und.values, T.day.values):
        if strad:
            pl = pools.get((u, d, h, "S"))
            if pl is not None and len(pl):
                tot += pl[rng.integers(0, len(pl), n)]
            continue
        side = rng.integers(0, 2, n)
        for k, s, o in ((0, "C", "P"), (1, "P", "C")):
            m = side == k
            pl = pools.get((u, d, h, s))
            if pl is None or not len(pl):
                pl = pools.get((u, d, h, o))
            if pl is None or not len(pl) or not m.any():
                continue
            tot[m] += pl[rng.integers(0, len(pl), m.sum())]
    return tot


def side_twins(T: pd.DataFrame, F: pd.DataFrame, rule: str, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    rng = np.random.default_rng(seed + 1)
    h = K.RULES[rule]["h"]
    key = F.set_index(["und", "day", "row"])
    tot = np.zeros(n)
    for u, d, r, s, net in zip(T.und.values, T.day.values, T.row.values, T.side.values, T.net.values):
        o = "P" if s == "C" else "C"
        other = key.at[(u, d, r), f"tr{h}_{o}_net"]
        other = other if np.isfinite(other) else net
        tot += np.where(rng.integers(0, 2, n).astype(bool), other, net)
    return tot


def stats(T: pd.DataFrame, sessions: list) -> dict:
    s = R3.stats(T, sessions)
    s.pop("by_cell", None)
    s.pop("calls", None)
    if len(T):
        s["calls"] = float((T.side == "C").mean())
        s["puts"] = float((T.side == "P").mean())
        s["exits"] = {k: int(v) for k, v in T.why.value_counts().items()}
    return s
