"""R62 probe (reverse engineering, 15 Oct 2026; DESIGN sessions only, after the final read): what is the video's setup
really trading, and is the volume profile's level special?

The setup is "after a structure break, buy the first pullback into the leg that made it, at a level where price
spent time / volume, and ride it back to the high". This probe keeps EVERYTHING of R62's walk (legs, activation, touch
+ next-bar confirmation, cancellation, stop beyond the anchor) and only swaps the LEVEL:
    poc, lvn, vah, val      the profile's levels (volume source per market)
    f382, f500, f618        plain retracements of the leg (no volume at all)
    rand                    a random retracement in [0.2, 0.8] of the leg (seed 62)
For each, the first signal of each leg is measured BEFORE costs on the underlying: R multiple at the structural target
(or stop, or 15:15), and at a 2R target. No option, no charges: if a level has no edge before costs it cannot have one
after. Nothing here is a test with gates; it is a diagnosis, and the final-test sessions are never read.

    python research/hunt/r62/r62probe.py         # results/probe_design.json
"""
from __future__ import annotations

import json

import numpy as np

import r62cfg as K
import r62sim as Z
import r62smc as S
import r62study as T

LEVELS = ("poc", "lvn", "vah", "val", "f382", "f500", "f618", "rand")
MARKETS = {"IDX-a": (K.IDX, "vol_a"), "IDX-c": (K.IDX, "vol_c"), "STK": (K.STOCKS, "vol")}


def level_zones(leg: S.Leg, p: S.Profile, rng) -> dict:
    """{level: (lo, hi)}; one profile row tall for the point levels."""
    out = {"poc": p.edges(p.poc, p.poc), "vah": p.edges(p.vah, p.vah), "val": p.edges(p.val, p.val)}
    lz = S.lvn_zone(p, leg.dir)
    if lz is not None:
        out["lvn"] = lz
    span, half = leg.hi - leg.lo, p.size / 2
    for name, f in (("f382", 0.382), ("f500", 0.5), ("f618", 0.618), ("rand", float(rng.uniform(0.2, 0.8)))):
        x = leg.hi - f * span if leg.dir > 0 else leg.lo + f * span
        out[name] = (x - half, x + half)
    return out


def outcome(M: np.ndarray, di: int, s: int, side: int, stop: float, tgt: float) -> float:
    """Pre-cost R on the underlying: in at the open after row s, out at the stop / target (next minute's open) or 15:15."""
    h, l, o, c = M[di, :, 1], M[di, :, 2], M[di, :, 0], M[di, :, 3]
    f0 = s + 1
    if f0 >= K.SQ_IDX or not np.isfinite(o[f0]):
        return np.nan
    ref = o[f0]
    risk = abs(ref - stop)
    if not risk > 0 or (side > 0 and stop >= ref) or (side < 0 and stop <= ref):
        return np.nan
    fx, _ = Z.scan_exit(h, l, f0, K.SQ_IDX, side, stop, tgt)
    px = o[fx] if np.isfinite(o[fx]) else Z._last(c, fx)
    return float(side * (px - ref) / risk)


def probe_inst(inst: str, vkey: str, rng) -> dict:
    g = T.grid(inst)
    n = T.last_session(g["days"], K.DESIGN_END) + 1
    M, V = g["M"][:n], g[vkey][:n]
    b5, b15 = S.make_bars(M, K.TF_EXEC), S.make_bars(M, K.TF_STRUCT)
    s5, s15 = S.structure(b5), S.structure(b15)
    lg = sorted(S.legs(b15, s15), key=lambda x: x.active_t)
    st15_5 = S.state_at(b15, s15, b5.t)
    res = {lv: dict(st=[], r2=[]) for lv in LEVELS}
    for li, leg in enumerate(lg):
        nxt = lg[li + 1].active_t if li + 1 < len(lg) else 1 << 62
        f0, f1 = leg.t0, leg.t1
        p = S.profile(M[:, :, 1].reshape(-1)[f0:f1 + 1], M[:, :, 2].reshape(-1)[f0:f1 + 1], V.reshape(-1)[f0:f1 + 1],
                      leg.lo, leg.hi)
        if p is None:
            continue
        j0 = int(np.searchsorted(b5.t, leg.active_t, side="right") - 1)
        if j0 < 0:
            continue
        act_di = int(leg.active_t // K.NROWS)
        stop = leg.lo * (1 - K.STOP_BUFFER) if leg.dir > 0 else leg.hi * (1 + K.STOP_BUFFER)
        st_tgt = leg.hi if leg.dir > 0 else leg.lo
        for lv, (zlo, zhi) in level_zones(leg, p, rng).items():
            js = S.walk_zone(b5, leg, zlo, zhi, j0, nxt, act_di, st15_5)
            if not js:
                continue
            j = js[0]
            di, s = int(b5.di[j]), int(b5.rend[j])
            if not (K.START <= g["days"][di] <= K.DESIGN_END):
                continue
            ref = M[di, s + 1, 0] if s + 1 < K.NROWS else np.nan
            r_st = outcome(M, di, s, leg.dir, stop, st_tgt)
            r_2 = outcome(M, di, s, leg.dir, stop, ref + leg.dir * 2 * abs(ref - stop)) if np.isfinite(ref) else np.nan
            if np.isfinite(r_st):
                res[lv]["st"].append(r_st)
                res[lv]["r2"].append(r_2)
    return res


def summary(xs: list) -> dict:
    x = np.asarray([v for v in xs if np.isfinite(v)])
    if not len(x):
        return dict(n=0)
    return dict(n=int(len(x)), mean_r=round(float(x.mean()), 4), se=round(float(x.std(ddof=1) / np.sqrt(len(x))), 4)
                if len(x) > 1 else None, win=round(float((x > 0).mean()), 3))


def main():
    rng = np.random.default_rng(K.SEED)
    out = {}
    for mk, (insts, vkey) in MARKETS.items():
        pool = {lv: dict(st=[], r2=[]) for lv in LEVELS}
        for inst in insts:
            r = probe_inst(inst, vkey, rng)
            for lv in LEVELS:
                pool[lv]["st"] += r[lv]["st"]
                pool[lv]["r2"] += r[lv]["r2"]
        out[mk] = {lv: dict(structural=summary(pool[lv]["st"]), two_r=summary(pool[lv]["r2"])) for lv in LEVELS}
        for lv in LEVELS:
            print(mk, lv, out[mk][lv], flush=True)
    (K.RESULTS / "probe_design.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
